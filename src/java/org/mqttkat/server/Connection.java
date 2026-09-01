package org.mqttkat.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import clojure.lang.IPersistentMap;

import org.mqttkat.IHandler;
import org.mqttkat.MqttStat;
import org.mqttkat.packages.MqttDisconnect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One client connection, with a thread of its own for each direction.
 *
 * The selector thread does nothing but read bytes and hand them here. This
 * connection's reader thread reassembles them into packets, decodes them and
 * runs the handler, all in the order they arrived; its writer thread sends
 * queued packets out one at a time, completely.
 *
 * Both are virtual threads, so a connection costs a few hundred bytes rather
 * than a megabyte of stack, and blocking in either direction parks the thread
 * instead of tying up a carrier. That is what makes per-connection ordering
 * affordable: the alternative, a shared pool with a task per packet, cannot
 * preserve order and cannot block.
 */
public class Connection {

	private static final Logger log = LoggerFactory.getLogger(Connection.class);
	private static final AtomicInteger IDS = new AtomicInteger();
	private static final int MAX_REMAINING_LENGTH_BYTES = 4;   // MQTT 3.1.1 §2.2.3

	/**
	 * How far behind one client may fall before QoS 0 publishes to it start
	 * being dropped. 0 disables the limit and restores the old unbounded
	 * behaviour; override with -Dmqttkat.maxQueuedMessages=N.
	 *
	 * At roughly 7k packets/second per connection, 10k is about a second and a
	 * half of buffering — enough to ride out a burst, far short of letting one
	 * subscriber that stops reading consume the broker's heap.
	 */
	private static volatile int maxQueued =
			Integer.getInteger("mqttkat.maxQueuedMessages", 10_000).intValue();

	/** The limit in force. 0 means unbounded. */
	public static int getMaxQueued() {
		return maxQueued;
	}

	/**
	 * Change the limit on a running broker. Read per packet through a volatile,
	 * so a change takes effect on the next publish rather than the next
	 * restart; tests use it to reach the drop path without having to bury a
	 * client under ten thousand messages first.
	 */
	public static void setMaxQueued(int limit) {
		maxQueued = limit;
	}

	/**
	 * Shutdown sentinels. Closing used to interrupt both threads, which also
	 * interrupted whatever the handler happened to be doing — a client that
	 * hung up while its CONNECT was being handled turned an ordinary rejection
	 * into an InterruptedException out of the middle of the handler. A value on
	 * the queue wakes a thread blocked in take() without touching one that is
	 * busy, and because the queues are FIFO it also lands behind anything
	 * already queued, so pending packets are still written on the way out.
	 */
	private static final Object STOP_READING = new Object();
	private static final ByteBuffer STOP_WRITING = ByteBuffer.allocate(0);

	/**
	 * Marker for a disconnect the broker raised itself. Deliberately a marker
	 * and not the two bytes of a DISCONNECT packet: the stream may already hold
	 * the front of a packet that never finished arriving, and appending to it
	 * would let that fragment swallow them as its body.
	 */
	private static final Object DISCONNECT = new Object();

	private final int id = IDS.incrementAndGet();
	private final SelectionKey key;
	private final SocketChannel channel;
	private final IHandler handler;

	private final BlockingQueue<Object> inbound = new LinkedBlockingQueue<Object>();
	private final BlockingQueue<ByteBuffer> outbound = new LinkedBlockingQueue<ByteBuffer>();

	/**
	 * Depth of `outbound`. Kept alongside the queue because
	 * LinkedBlockingQueue.size() is O(1) but its count is only advisory here:
	 * this one is incremented before the offer so a fan-out cannot race past
	 * the limit by more than the number of threads publishing at that instant.
	 */
	private final AtomicInteger queuedCount = new AtomicInteger();

	private volatile boolean running = true;
	private Thread reader;
	private Thread writer;

	/** Bytes read but not yet forming a whole packet. Touched only by the reader thread. */
	private byte[] pending = new byte[0];

	public Connection(SelectionKey key, SocketChannel channel, IHandler handler) {
		this.key = key;
		this.channel = channel;
		this.handler = handler;
	}

	public void start() {
		reader = Thread.ofVirtual().name("conn-" + id + "-read").start(this::readLoop);
		writer = Thread.ofVirtual().name("conn-" + id + "-write").start(this::writeLoop);
	}

	/** Called from the selector thread with a chunk of freshly read bytes. */
	public void offer(byte[] chunk) {
		if (running) {
			inbound.offer(chunk);
		}
	}

	/**
	 * Queue a packet that has to be delivered — CONNACK, SUBACK, PUBACK, a QoS
	 * 1 or 2 PUBLISH. Never refused: dropping one of these breaks the protocol
	 * rather than degrading it, so the limit deliberately does not apply.
	 * Packets are written in the order queued.
	 */
	public void write(ByteBuffer buffer) {
		if (running) {
			queuedCount.incrementAndGet();
			outbound.offer(buffer);
		}
	}

	/**
	 * Queue a QoS 0 PUBLISH, which is dropped if this client is already
	 * maxQueued packets behind.
	 *
	 * MQTT 3.1.1 §4.3.1 makes QoS 0 "at most once", so a broker is entitled to
	 * drop rather than buffer without limit — and it has to be. Unbounded, one
	 * subscriber that stops reading is charged to the broker's heap, and worse,
	 * the fan-out threads that fill the queue outcompete the writer threads
	 * that drain it: the broker ends up spending its CPU accepting work instead
	 * of doing it.
	 *
	 * @return false if the packet was dropped rather than queued.
	 */
	/**
	 * Whether a QoS 0 publish to this client would be dropped. Lets the fan-out
	 * skip the per-subscriber duplicate() for a client it is only going to
	 * refuse — under overload that is most of them, and the allocation is pure
	 * garbage. Advisory: writeDroppable re-checks, so a client that crosses the
	 * limit between the two calls is still refused there.
	 */
	public boolean isBacklogged() {
		int limit = maxQueued;
		return limit > 0 && queuedCount.get() >= limit;
	}

	public boolean writeDroppable(ByteBuffer buffer) {
		if (!running) {
			return false;
		}
		if (isBacklogged()) {
			MqttStat.droppedMessages.increment();
			return false;
		}
		queuedCount.incrementAndGet();
		outbound.offer(buffer);
		return true;
	}

	/**
	 * Hand this connection a DISCONNECT of the broker's own making, as bytes on
	 * its inbound queue. It is then framed, decoded and handled exactly like one
	 * the client sent — and, being behind whatever is already queued, it cannot
	 * overtake the packets that preceded it. Dispatching it anywhere else races
	 * them.
	 *
	 * @return false if this connection is already closing, in which case the
	 *         caller has to dispatch the disconnect itself.
	 */
	public boolean disconnect() {
		if (!running) {
			return false;
		}
		return inbound.offer(DISCONNECT);
	}

	public void close() {
		if (!running) {
			return;
		}
		running = false;
		inbound.offer(STOP_READING);
		outbound.offer(STOP_WRITING);
	}

	// ── inbound ──────────────────────────────────────────────────────────────

	private void readLoop() {
		try {
			// Runs to the sentinel rather than to `running`, so packets queued
			// before the connection was closed are still handled — the
			// disconnect the broker queues on the way out among them.
			while (true) {
				Object item = inbound.take();
				if (item == STOP_READING) {
					break;
				}
				if (item == DISCONNECT) {
					// Anything still part-received can never be completed now.
					pending = new byte[0];
					dispatchDisconnect();
					continue;
				}
				append((byte[]) item);
				frameAndDispatch();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();          // closing
		} catch (Throwable t) {
			log.error("connection {} reader failed", id, t);
			close();
		}
	}

	private void append(byte[] chunk) {
		if (pending.length == 0) {
			pending = chunk;                             // the selector hands us a fresh array
			return;
		}
		byte[] merged = new byte[pending.length + chunk.length];
		System.arraycopy(pending, 0, merged, 0, pending.length);
		System.arraycopy(chunk, 0, merged, pending.length, chunk.length);
		pending = merged;
	}

	/**
	 * Pull every complete packet out of `pending` and dispatch it, keeping
	 * whatever tail is left for the next read. A packet split across two TCP
	 * reads used to raise BufferUnderflowException out of the selector loop,
	 * which killed the thread and with it the whole broker.
	 */
	private void frameAndDispatch() {
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
					log.error("connection {}: malformed remaining length, closing", id);
					close();
					return;
				}
				if ((digit & 0x80) == 0) {
					lengthComplete = true;
					break;
				}
			}
			if (!lengthComplete || i + length > pending.length) {
				break;                                   // wait for the rest of the packet
			}

			byte type = (byte) (first >> 4);
			byte flags = (byte) (first & 0x0f);
			byte[] body = Arrays.copyOfRange(pending, i, i + length);
			offset = i + length;
			dispatch(type, flags, body);
		}
		pending = (offset == 0) ? pending : Arrays.copyOfRange(pending, offset, pending.length);
	}

	private void dispatchDisconnect() {
		try {
			handler.handleInOrder(MqttDisconnect.decode(key));
		} catch (Throwable t) {
			log.error("connection {}: handling the disconnect failed", id, t);
		}
	}

	private void dispatch(byte type, byte flags, byte[] body) {
		try {
			IPersistentMap incoming = MqttDecode.decode(key, type, flags, body);
			if (incoming == null) {
				log.error("connection {}: invalid packet type {}, closing", id, type);
				close();
				return;
			}
			MqttStat.receivedMessages.increment();
			MqttStat.receivedBytes.add(body.length);
			// Run the handler here rather than handing it to a pool: this thread
			// belongs to one connection, so running it inline is what keeps a
			// client's packets in order.
			handler.handleInOrder(incoming);
		} catch (Throwable t) {
			log.error("connection {}: handling a packet of type {} failed", id, type, t);
		}
	}

	// ── outbound ─────────────────────────────────────────────────────────────

	private void writeLoop() {
		try {
			while (true) {
				ByteBuffer buffer = outbound.take();
				if (buffer == STOP_WRITING) {
					break;                               // everything queued before it is written
				}
				queuedCount.decrementAndGet();
				int size = buffer.remaining();
				writeFully(buffer);
				// writeFully also returns when the channel has gone, so ask the
				// buffer whether the packet left rather than assuming it did.
				if (!buffer.hasRemaining()) {
					MqttStat.writtenMessages.increment();
					MqttStat.writtenBytes.add(size);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();          // closing
		} catch (IOException e) {
			log.debug("connection {}: write failed, closing", id, e);
			close();
		} catch (Throwable t) {
			log.error("connection {} writer failed", id, t);
			close();
		} finally {
			discardQueued();
		}
	}

	/**
	 * Count what this connection promised and will now never deliver. Without
	 * it the gap between queued and written only ever grows, and stops meaning
	 * "current backlog" the first time a client hangs up with packets still
	 * queued for it.
	 */
	private void discardQueued() {
		List<ByteBuffer> left = new ArrayList<ByteBuffer>();
		outbound.drainTo(left);
		long n = 0;
		for (ByteBuffer b : left) {
			if (b != STOP_WRITING) {
				n++;
			}
		}
		if (n > 0) {
			MqttStat.discardedMessages.add(n);
		}
	}

	/**
	 * A non-blocking channel writes only what fits in the socket buffer and
	 * reports the rest back; ignoring that return value truncated packets
	 * whenever a client was slow. Parking briefly is cheap on a virtual thread.
	 */
	private void writeFully(ByteBuffer buffer) throws IOException, InterruptedException {
		// Deliberately not conditional on `running`: a packet queued before the
		// connection was closed still goes out, and the loop ends by itself as
		// soon as the socket is gone.
		while (buffer.hasRemaining() && channel.isOpen()) {
			if (channel.write(buffer) == 0) {
				Thread.sleep(1);
			}
		}
	}
}
