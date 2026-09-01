package org.mqttkat.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;

import java.nio.channels.*;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;

import java.io.IOException;
import java.util.Iterator;
import java.nio.ByteBuffer;

import clojure.lang.IPersistentMap;

import org.mqttkat.IHandler;
import org.mqttkat.packages.*;

import static org.mqttkat.MqttStat.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MqttServer implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(MqttServer.class);
	static final String THREAD_NAME = "server-loop";

	private final Selector selector;
	private final ServerSocketChannel serverChannel;
	private final IHandler handler;
	private final int port;
	private final ByteBuffer buf = ByteBuffer.allocate(8096);

	public MqttServer(String ip, int port, IHandler handler) throws IOException {
		this.selector = Selector.open();
		this.serverChannel = ServerSocketChannel.open();
		this.handler = handler;
		this.serverChannel.configureBlocking(false);
		this.serverChannel.socket().bind(new InetSocketAddress(ip, port));
		this.serverChannel.register(selector, OP_ACCEPT);
		this.port = port;
	}

	public void closeKey(final SelectionKey key) {
		Connection connection = (Connection) key.attachment();
		// Let the connection handle its own disconnect, in order, after anything
		// it still has queued. Only if it is already closing — or was never
		// established — does this fall back to the shared pool, where the
		// disconnect races the client's own packets.
		if (connection == null || !connection.disconnect()) {
			try {
				handler.handle(MqttDisconnect.decode(key));
			} catch (IOException e) {
				log.error("closing key failed", e);
			}
		}
		try {
			stopConnection(key);
			if (key.channel().isOpen()) {
				key.channel().close();
			}
		} catch (Exception e) {
			log.error("closing key failed", e);
		}
	}

	// this one is needed for just closing the connection. The CLJ layer has done
	// everything needed already
	public void closeConnection(final SelectionKey key) {
		try {
			stopConnection(key);
			if (key.channel().isOpen()) {
				key.channel().close();
			}
		} catch (Exception e) {
			log.error("closing connection failed", e);
		}
	}

	/** Stop the reader and writer threads belonging to this connection. */
	private void stopConnection(final SelectionKey key) {
		Connection connection = (Connection) key.attachment();
		if (connection != null) {
			connection.close();
		}
	}

	public void run() {
		log.info("Server starting on port {}", this.port);
		SelectionKey key = null;

		try {
			Iterator<SelectionKey> iter;
			while (this.serverChannel.isOpen()) {
				selector.select();
				iter = this.selector.selectedKeys().iterator();
				while (iter.hasNext()) {
					key = iter.next();
					iter.remove();
					// Another thread can cancel a key between select() returning
					// and the checks below — the keep-alive reaper closes idle
					// connections from an at-at thread, and disconnect-client
					// runs on a connection's own thread. isValid() narrows the
					// window; the catch closes it, because the key can be
					// cancelled immediately after the check too.
					if (!key.isValid()) {
						continue;
					}
					try {
						if (key.isAcceptable()) {
							this.handleAccept(key);
						}
						if (key.isReadable()) {
							this.handleRead(key);
						}
					} catch (CancelledKeyException e) {
						// closed underneath us; whoever cancelled it cleaned up
					} catch (Throwable t) {
						// One connection must never be able to stop the loop:
						// this thread does all the I/O for the whole broker.
						log.error("failure while handling a selected key", t);
						closeKey(key);
					}
				}
			}
		} catch (IOException e) {
			if (key != null) {
				key.cancel();
			}
			log.error("IOException, server on port {} terminating", this.port, e);
		} catch (ClosedSelectorException e) {
			// Here we are stopping... so no need to do anything
		}
	}

	private void handleAccept(SelectionKey key) throws IOException {
		SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
		sc.configureBlocking(false);
		// Nagle's algorithm holds a small write back until the previous one has
		// been acknowledged; combined with the peer's delayed ACK (40ms on
		// Linux) that stalls every exchange needing more than one packet in
		// each direction. Measured on the simulation: median QoS 1 round trip
		// 41.4ms -> 0.6ms, QoS 2 41.7ms -> 1.3ms, QoS 0 unaffected because it
		// is a single packet with no reply.
		sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
		SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ);
		Connection connection = new Connection(clientKey, sc, handler);
		clientKey.attach(connection);
		connection.start();
	}

	private void handleRead(SelectionKey key) throws IOException {
		SocketChannel ch = (SocketChannel) key.channel();
		Connection connection = (Connection) key.attachment();
		try {
			int read;
			buf.clear();
			while ((read = ch.read(buf)) > 0) {
				buf.flip();
				byte[] chunk = new byte[buf.remaining()];
				buf.get(chunk);
				// Framing, decoding and handling all happen on the connection's
				// own thread. This one thread serves every connection, so doing
				// any of that here would make one slow packet everybody's
				// problem — and a packet split across two reads used to throw
				// straight out of this loop and kill the thread.
				connection.offer(chunk);
				buf.clear();
			}
			if (read < 0) {                          // client has gone away
				closeKey(key);
			}
		} catch (IOException e) {
			key.cancel();
			if (connection != null) {
				connection.close();
			}
			ch.close();
		}
	}

	public void start() {
		Thread serverThread = new Thread(this, THREAD_NAME);
		serverThread.start();
	}

	public void stop(int timeout) {
		try {
			serverChannel.close(); // stop accept any request
		} catch (IOException ignore) {
		}
		handler.close(timeout);

		// close socket, notify on-close handlers
		if (selector.isOpen()) {
			// Set<SelectionKey> keys = selector.keys();
			// SelectionKey[] keys = t.toArray(new SelectionKey[t.size()]);
			for (SelectionKey k : selector.keys()) {
				/**
				 * 1. t.toArray will fill null if given array is larger.
				 * 2. compute t.size(), then try to fill the array, if in the mean time, another
				 * thread close one SelectionKey, will result a NPE
				 *
				 * https://github.com/http-kit/http-kit/issues/125
				 */
				if (k != null) {
					log.debug("DISCONNECT with a live key");
					closeKey(k); // 0 => close by server
				}
			}

			try {
				selector.close();
			} catch (IOException ignore) {
			}
		}
	}

	public int getPort() {
		return this.serverChannel.socket().getLocalPort();
	}

	/*
	 * public void tryWrite(final SelectionKey key, ByteBuffer... buffers) {
	 * SocketChannel ch = (SocketChannel) key.channel();
	 * try {
	 * ch.write(buffers, 0, buffers.length);
	 * selector.wakeup();
	 * } catch (IOException ignored) {
	 * }
	 * }
	 */

	// public void sendMessage( final clojure.lang.PersistentVector keys, final
	// Map<Keyword, ?> message) throws IOException {
	// ByteBuffer buffer = MqttEncode.mqttEncoder(message);
	//
	// Iterator<?> it = keys.iterator();
	//
	// while(it.hasNext() ) {
	// SelectionKey key = (SelectionKey) it.next();
	// ByteBuffer copyBuf = buffer.duplicate();
	// executor.submit(copyBuf, key);
	// sentMessages.getAndIncrement();
	// sentBytes.getAndAdd(buffer.limit());
	// }
	// }

	public void sendMessageBuffer(final clojure.lang.PersistentVector keys, final ByteBuffer buffer) {
		for (Object o : keys) {
			SelectionKey key = (SelectionKey) o;
			Connection connection = (Connection) key.attachment();
			if (connection == null) {
				continue;                            // already gone
			}
			// duplicate() shares the bytes but gets its own position, so one
			// encoded packet can be fanned out to every subscriber without
			// being encoded, or copied, again.
			connection.write(buffer.duplicate());
			sentMessages.getAndIncrement();
			sentBytes.getAndAdd(buffer.limit());
		}
	}
}
