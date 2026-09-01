package org.mqttkat.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

import clojure.lang.IPersistentMap;

import org.mqttkat.IHandler;
import org.mqttkat.server.MqttDecode;

import static org.mqttkat.MqttStat.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The test and load-generation client.
 *
 * Blocking I/O on a virtual thread, rather than a selector. That removes the
 * whole apparatus the previous version needed to fake blocking — a selector
 * loop, a pending-writes map, a pending-changes list, OP_WRITE juggling — and
 * with it four defects:
 *
 *   - responses were decoded straight out of one read, so a packet split
 *     across two reads was misframed;
 *   - sendMessage only queued the bytes for the selector thread, so closing
 *     straight after sending dropped the packet;
 *   - close() shut the selector and channel while that thread was selecting,
 *     which surfaced as NullPointerException from AbstractSelectableChannel;
 *   - received packets were delivered through a thread pool, so a client could
 *     hand them to its caller out of order.
 *
 * A client now costs one virtual thread and no platform threads, so a load
 * generator can hold as many connections as the broker it is measuring.
 */
public class MqttClient {

	private static final Logger log = LoggerFactory.getLogger(MqttClient.class);
	private static final int READ_BUFFER = 8192;
	private static final int MAX_REMAINING_LENGTH_BYTES = 4;   // MQTT 3.1.1 §2.2.3

	private final SocketChannel socketChannel;
	private final IHandler handler;
	private final Object asyncChannel;
	private final Object writeLock = new Object();

	private volatile boolean running = true;
	private byte[] pending = new byte[0];      // touched only by the reader thread

	/**
	 * @param threadPoolSize retained for the existing call sites; packets are
	 *                       delivered on this client's own thread, in order, so
	 *                       the handler's pool is never used.
	 */
	public MqttClient(String host, int port, int threadPoolSize, IHandler handler, Object asyncChannel)
			throws IOException {
		log.debug("Creating client...");
		this.handler = handler;
		this.asyncChannel = asyncChannel;
		this.socketChannel = SocketChannel.open(new InetSocketAddress(host, port));
		// See the matching call in MqttServer.handleAccept: both ends have to
		// disable Nagle, or the acknowledgement half of the exchange still
		// waits on the delayed-ACK timer.
		this.socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
		Thread.ofVirtual().name("mqtt-client-read").start(this::readLoop);
	}

	public Object getChannel() {
		return this.asyncChannel;
	}

	public boolean isConnected() {
		return this.socketChannel.isOpen();
	}

	/**
	 * Write a packet. Synchronous, unlike the version that queued for a
	 * selector: when this returns the bytes are on the wire, so closing
	 * immediately afterwards cannot lose them. The lock keeps two callers from
	 * interleaving.
	 */
	public void sendMessage(ByteBuffer buffer) throws IOException {
		int size = buffer.limit();
		synchronized (writeLock) {
			while (buffer.hasRemaining()) {
				socketChannel.write(buffer);
			}
		}
		// Counted after the write, and as both queued and written: this send is
		// synchronous, so there is no queue here to fall behind. Keeping the
		// two in step matters because the performance tests run a client and
		// the broker in one JVM, where these counters are shared — counting
		// only `sent` here would show up as broker backlog that does not exist.
		sentMessages.increment();
		sentBytes.add(size);
		writtenMessages.increment();
		writtenBytes.add(size);
	}

	public void close() throws IOException {
		if (!running) {
			return;
		}
		log.debug("Client stopping...");
		running = false;
		socketChannel.close();                 // unblocks the reader
	}

	// ── receiving ────────────────────────────────────────────────────────────

	private void readLoop() {
		log.debug("Client loop started running...");
		ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER);
		try {
			while (running) {
				buffer.clear();
				int read = socketChannel.read(buffer);
				if (read < 0) {
					break;                     // the broker closed the connection
				}
				buffer.flip();
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				append(chunk);
				frameAndDeliver();
			}
		} catch (ClosedChannelException e) {
			// close() shut the socket underneath us, which is how we stop
		} catch (IOException e) {
			if (running) {
				log.debug("client read failed", e);
			}
		} catch (Throwable t) {
			// The old loop caught Exception and did nothing at all with it,
			// which is why client-side failures were invisible.
			log.error("client reader failed", t);
		} finally {
			// Whatever ended the loop — EOF, an error, close() — the socket has
			// to end up shut, or isConnected() goes on claiming a connection
			// the broker has already dropped.
			running = false;
			try {
				socketChannel.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void append(byte[] chunk) {
		if (pending.length == 0) {
			pending = chunk;
			return;
		}
		byte[] merged = new byte[pending.length + chunk.length];
		System.arraycopy(pending, 0, merged, 0, pending.length);
		System.arraycopy(chunk, 0, merged, pending.length, chunk.length);
		pending = merged;
	}

	/** Deliver every complete packet, keeping any tail for the next read. */
	private void frameAndDeliver() {
		int offset = 0;
		while (offset < pending.length) {
			int first = pending[offset] & 0xff;

			int multiplier = 1;
			int length = 0;
			int i = offset + 1;
			int digits = 0;
			boolean lengthComplete = false;
			while (i < pending.length) {
				int digit = pending[i++] & 0xff;
				length += (digit & 0x7F) * multiplier;
				multiplier *= 128;
				if (++digits > MAX_REMAINING_LENGTH_BYTES) {
					log.error("malformed remaining length from the broker, closing");
					closeQuietly();
					return;
				}
				if ((digit & 0x80) == 0) {
					lengthComplete = true;
					break;
				}
			}
			if (!lengthComplete || i + length > pending.length) {
				break;                         // wait for the rest of the packet
			}

			byte type = (byte) (first >> 4);
			byte flags = (byte) (first & 0x0f);
			byte[] body = Arrays.copyOfRange(pending, i, i + length);
			offset = i + length;
			deliver(type, flags, body);
		}
		pending = (offset == 0) ? pending : Arrays.copyOfRange(pending, offset, pending.length);
	}

	private void deliver(byte type, byte flags, byte[] body) {
		try {
			// No SelectionKey on this side: the client has one connection, and
			// :client-key is only meaningful to the broker.
			IPersistentMap incoming = MqttDecode.decode(null, type, flags, body);
			if (incoming == null) {
				log.error("invalid packet type received: {}", type);
				return;
			}
			receivedMessages.increment();
			receivedBytes.add(body.length);
			// In order, on this thread: callers read replies off a channel and
			// expect them in the order the broker sent them.
			handler.handleInOrder(incoming, asyncChannel);
		} catch (Throwable t) {
			log.error("handling a packet of type {} failed", type, t);
		}
	}

	private void closeQuietly() {
		try {
			close();
		} catch (IOException ignored) {
		}
	}
}
