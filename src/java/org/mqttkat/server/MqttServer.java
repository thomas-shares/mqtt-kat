package org.mqttkat.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import java.io.IOException;
import java.util.Iterator;
import java.nio.ByteBuffer;

import clojure.lang.IPersistentMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.mqttkat.packages.GenericMessage;
import org.mqttkat.packages.MqttAuthenticate;
import org.mqttkat.packages.MqttConnect;
import org.mqttkat.packages.MqttDisconnect;
import org.mqttkat.packages.MqttPingReq;
import org.mqttkat.packages.MqttPingResp;
import org.mqttkat.packages.MqttPubAck;
import org.mqttkat.packages.MqttPubComp;
import org.mqttkat.packages.MqttPubRec;
import org.mqttkat.packages.MqttPubRel;
import org.mqttkat.packages.MqttPublish;
import org.mqttkat.packages.MqttSubAck;
import org.mqttkat.packages.MqttSubscribe;
import org.mqttkat.packages.MqttUnSubAck;
import org.mqttkat.packages.MqttUnsubscribe;

class PendingKey {
    public final SelectionKey key;
    // operation: can be register for write or close the selectionkey
    public final int Op;

    PendingKey(SelectionKey key, int op) {
        this.key = key;
        Op = op;
    }

    public static final int OP_WRITE = -1;
}

public class MqttServer implements Runnable {
	static final String THREAD_NAME = "server-loop";

	private final Selector selector;
	private final ServerSocketChannel serverChannel;
	private Thread serverThread;
	private final IHandler handler;
	private final int port;
	private ByteBuffer buf = ByteBuffer.allocate(256);

	public MqttServer(String ip, int port, IHandler handler) throws IOException {
		this.selector = Selector.open();
		this.serverChannel = ServerSocketChannel.open();
		this.handler = handler;
		serverChannel.configureBlocking(false);
		serverChannel.socket().bind(new InetSocketAddress(ip, port));
		serverChannel.register(selector, OP_ACCEPT);
		this.port = port;
	}

	public void run() {
		System.out.println("Server starting on port " + this.port);
		try {
			Iterator<SelectionKey> iter;
			SelectionKey key;
			while (this.serverChannel.isOpen()) {
				selector.select();
				iter = this.selector.selectedKeys().iterator();
				while (iter.hasNext()) {
					key = iter.next();
					iter.remove();

					if (key.isAcceptable()) {
						this.handleAccept(key);
					}

					if (key.isReadable()) {
						this.handleRead(key);
					}
				}
			}
		} catch (IOException e) {
			System.out.println("IOException, server of port " + this.port + " terminating. Stack trace:");
			e.printStackTrace();
		}
	}

	private void handleAccept(SelectionKey key) throws IOException {
		SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
		String address = (new StringBuilder(sc.socket().getInetAddress().toString())).append(":")
				.append(sc.socket().getPort()).toString();
		sc.configureBlocking(false);
		sc.register(selector, SelectionKey.OP_READ, address);
		System.out.println("accepted connection from: " + address);
	}

	private void handleRead(SelectionKey key) throws IOException {
		SocketChannel ch = (SocketChannel) key.channel();

		buf.clear();
		int read = 0;
		byte[] remainAndPayload = new byte[buf.limit()];
		byte type = 0;
		byte flags = 0;
		int msgLength = 0;

		while ((read = ch.read(buf)) > 0) {
			buf.flip();
			// byte[] bytes = new byte[buf.limit()];
			// buf.get(bytes);
			byte[] bytes = new byte[1];
			buf.get(bytes, 0, 1);
			// sb.append(new String(bytes));
			// byte first = bytes[0];

			System.out.println("byte 0: "  + Integer.toBinaryString( (int) bytes[0]));
			type = (byte) ((bytes[0] & 0xff) >> 4);
			flags = (byte) (bytes[0] &= 0x0f);

			System.out.println("type: " + Integer.toBinaryString( (int)type));
			System.out.println("flags : " + flags);
			// long remLen = readMBI(in).getValue();

			byte digit;
			int multiplier = 1;
			int count = 0;

			do {
				buf.get(bytes, count, count + 1);
				digit = bytes[0]; // bytes[count];
				count++;
				msgLength += ((digit & 0x7F) * multiplier);
				multiplier *= 128;
			} while ((digit & 0x80) != 0);

			//System.out.println("count: " + count);
			//System.out.println("Lenght: " + msgLength);

			buf.get(remainAndPayload, 0, msgLength);
			for(int i=0; i < msgLength ;i++ ){
				System.out.print(" " + remainAndPayload[i]);
			}
			System.out.print("\n");

			buf.clear();
		}

		IPersistentMap incoming = null;
		try {
			if (type == GenericMessage.MESSAGE_CONNECT) {	
					incoming =  MqttConnect.decodeConnect(flags, remainAndPayload);
			} else if (type == GenericMessage.MESSAGE_PUBLISH) {
				incoming = MqttPublish.decode(flags, remainAndPayload);
			}else if (type == GenericMessage.MESSAGE_PUBACK) {
				incoming = MqttPubAck.decode(flags, remainAndPayload);
			}else if (type == GenericMessage.MESSAGE_PUBREC) {
				incoming = MqttPubRec.decode(flags, remainAndPayload);
			}else if (type == GenericMessage.MESSAGE_PUBREL) {
				incoming = MqttPubRel.decode(flags, remainAndPayload);
			}else if (type == GenericMessage.MESSAGE_PUBCOMP) {
				incoming = MqttPubComp.decode(flags, remainAndPayload);
			}else if (type == GenericMessage.MESSAGE_SUBSCRIBE) {
				incoming = MqttSubscribe.decode(flags, remainAndPayload, msgLength);
			}else if (type == GenericMessage.MESSAGE_UNSUBSCRIBE) {
				incoming = MqttUnsubscribe.decode(flags, remainAndPayload);
			} else if (type == GenericMessage.MESSAGE_PINGREQ) {
				incoming = MqttPingReq.decodePingReq(flags);
			} else if (type == GenericMessage.MESSAGE_DISCONNECT) {
				incoming = MqttDisconnect.decode(flags, remainAndPayload);
			} else if ( type ==  GenericMessage.MESSAGE_AUTHENTICATION) {
				incoming = MqttAuthenticate.decode(flags, remainAndPayload);
			} else {
				System.out.println("FAIL!!!!!! INVALID packet send: " + type);
				throw new MallFormedPacketException("type  set to zero (0): " + type);
			}

		} catch (MallFormedPacketException e) {
			e.printStackTrace();
			//TODO close connection...
		}

		if( incoming != null ) {
			handler.handle(incoming, new RespCallback(key, this));
		}
	}

	public void start() throws IOException {
		serverThread = new Thread(this, THREAD_NAME);
		serverThread.start();
	}

	public void stop(int timeout) {
		try {
			serverChannel.close(); // stop accept any request
		} catch (IOException ignore) {
		}
	}

	public int getPort() {
		return this.serverChannel.socket().getLocalPort();
	}


	public void tryWrite(final SelectionKey key, ByteBuffer... buffers) {
		 //tryWrite(key, false, buffers);
		 SocketChannel ch = (SocketChannel) key.channel();
		 //System.out.println("tryWrite.." );
		 try {
			 ch.write(buffers, 0, buffers.length);
			 //pending.add(new PendingKey(key, PendingKey.OP_WRITE));
       selector.wakeup();
		 } catch (IOException e) {
       //pending.add(new PendingKey(key, CLOSE_AWAY));
       selector.wakeup();
    }
 }


}
