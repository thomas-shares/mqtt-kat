package org.mqttkat.server;

import static java.nio.channels.SelectionKey.OP_ACCEPT;

import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.nio.ByteBuffer;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

import org.mqttkat.IHandler;
import org.mqttkat.MqttSendExecutor;
import org.mqttkat.packages.*;

import static org.mqttkat.MqttStat.*;


public class MqttServer implements Runnable {
	static final String THREAD_NAME = "server-loop";

	private final Selector selector;
	private final ServerSocketChannel serverChannel;
	private Thread serverThread;
	private final IHandler handler;
	private final int port;
	private ByteBuffer buf = ByteBuffer.allocate(1024);
	private MqttSendExecutor executor;

	public MqttServer(String ip, int port, IHandler handler) throws IOException {
		this.selector = Selector.open();
		this.serverChannel = ServerSocketChannel.open();
		this.handler = handler;
		this.serverChannel.configureBlocking(false);
		this.serverChannel.socket().bind(new InetSocketAddress(ip, port));
		this.serverChannel.register(selector, OP_ACCEPT);
		this.port = port;
		this.executor = new MqttSendExecutor(selector, 16);
	}

   private void closeKey(final SelectionKey key) {
		try {
			IPersistentMap incoming = MqttDisconnect.decode(key, (byte)0x00, new byte[] {0x0});
			handler.handle(incoming);
		} catch (IOException ignore) {
		}
        try {
            key.channel().close();
        } catch (Exception ignore) {
        }
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
		System.out.println("Server accepted Client connection from: " + address);
	}

	private void handleRead(SelectionKey key) throws IOException {
		SocketChannel ch = (SocketChannel) key.channel();

		buf.clear();
		int read = 0;
		//byte[] remainAndPayload = null;
		byte type = 0;
		byte flags = 0;
		while ((read = ch.read(buf)) > 0) {
			buf.flip();
			// byte[] bytes = new byte[buf.limit()];
			// buf.get(bytes);
			do {
				
				
				byte[] bytes = new byte[1];
				buf.get(bytes, 0, 1);
	
				//System.out.println("byte 0: "  + Integer.toBinaryString( (int) bytes[0]));
				type = (byte) ((bytes[0] & 0xff) >> 4);
				flags = (byte) (bytes[0] &= 0x0f);
	
				/*
				if (type == GenericMessage.MESSAGE_CONNECT) {
					System.out.println("Server: CONNECT");
				} else if ( type == GenericMessage.MESSAGE_CONNACK) {
					System.out.println("Server: CONNACK");
				} else if (type == GenericMessage.MESSAGE_PUBLISH) {
					System.out.println("Server: PUBLISH");
				} else if (type == GenericMessage.MESSAGE_PUBACK) {
					System.out.println("PUBACK");
				} else if (type == GenericMessage.MESSAGE_PUBREC) {
					System.out.println("PUBREC");
				} else if (type == GenericMessage.MESSAGE_PUBREL) {
					System.out.println("PUBREL");
				} else if (type == GenericMessage.MESSAGE_PUBCOMP) {
					System.out.println("PUBCOMP");
				} else if (type == GenericMessage.MESSAGE_SUBSCRIBE) {
					System.out.println("SUBSCRIBE");
				} else if( type == GenericMessage.MESSAGE_SUBACK) {
					System.out.println("SUBACK");
				} else if (type == GenericMessage.MESSAGE_UNSUBSCRIBE) {
					System.out.println("UNSUBSCRIBE");
				} else if (type == GenericMessage.MESSAGE_PINGREQ) {
					System.out.println("PINGREQ");
				} else if (type == GenericMessage.MESSAGE_PINGRESP) {
					System.out.println("PINGRESP");
			    } else if (type == GenericMessage.MESSAGE_DISCONNECT) {
					System.out.println("Server: DISCONNECT");
				} else if ( type ==  GenericMessage.MESSAGE_AUTHENTICATION) {	
					System.out.println("AUTHENTICATE");
				}else if ( type == GenericMessage.MESSAGE_UNSUBACK ) {
					System.out.println("UNSUBACK");
				} 
				else {
					System.out.println("FAIL!!!!!! INVALID packet sent: " + type);
				}
	
	*/			byte digit;
				int multiplier = 1;
				//System.out.println( "limit: " + buf.limit() + " position: " + buf.position() + " capacity: " + buf.capacity() );
				int msgLength = 0;

				do {
					digit = buf.get(); // bytes[0];
					msgLength += ((digit & 0x7F) * multiplier);
					multiplier *= 128;
				} while ((digit & 0x80) != 0);
	
				//System.out.println("msgLenght: " + msgLength);
	
				byte[] remainAndPayload = new byte[msgLength];
	
				//System.out.println( "limit: " + buf.limit() + " position: " + buf.position() + " capacity: " + buf.capacity() + " remainLength: " +  remainAndPayload.length);
				buf.get(remainAndPayload, 0, msgLength);
				//System.out.println( "limit: " + buf.limit() + " position: " + buf.position() + " capacity: " + buf.capacity());
	
				//for(int i=0; i < msgLength ;i++ ){
				//	System.out.print(" " + remainAndPayload[i]);
				//}
				//System.out.print("\n");
				
				IPersistentMap incoming = null;
				if (type == GenericMessage.MESSAGE_CONNECT) {	
					incoming =  MqttConnect.decode(key, flags, remainAndPayload);
				} else if ( type ==  GenericMessage.MESSAGE_CONNACK) {
					incoming = MqttConnAck.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_PUBLISH) {
					incoming = MqttPublish.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_PUBACK) {
					incoming = MqttPubAck.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_PUBREC) {
					incoming = MqttPubRec.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_PUBREL) {
					incoming = MqttPubRel.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_PUBCOMP) {
					incoming = MqttPubComp.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_SUBSCRIBE) {
					incoming = MqttSubscribe.decode(key, flags, remainAndPayload);
				} else if( type == GenericMessage.MESSAGE_SUBACK) {
					incoming = MqttSubAck.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_UNSUBSCRIBE) {
					incoming = MqttUnsubscribe.decode(key, flags, remainAndPayload);
				} else if ( type == GenericMessage.MESSAGE_UNSUBACK ) {
					incoming = MqttUnSubAck.decode(key, flags, remainAndPayload);
				} else if (type == GenericMessage.MESSAGE_PINGREQ) {
					incoming = MqttPingReq.decode(key, flags);
				} else if (type == GenericMessage.MESSAGE_PINGRESP) {
					incoming = MqttPingResp.decode(key, flags);
				} else if (type == GenericMessage.MESSAGE_DISCONNECT) {
					incoming = MqttDisconnect.decode(key, flags, remainAndPayload);
					closeKey(key);
				} else if ( type ==  GenericMessage.MESSAGE_AUTHENTICATION) {
					incoming = MqttAuthenticate.decode(key, flags, remainAndPayload);
				} else {
					System.out.println("FAIL!!!!!! INVALID packet sent: " + type);
					closeKey(key);
				}
	
				if( incoming != null ) {
					handler.handle(incoming);
					receivedBytes.getAndAdd(msgLength);
					receivedMessage.getAndIncrement();
				}
			} while (buf.limit() > buf.position());

			buf.clear();
		}
		
		//client has gone away...
		if(read<0) {
			System.out.println("Client has gone away...");
			System.out.println("message received: " + receivedMessage.get());
			System.out.println("Message sent: " + sentMessages.get());
			closeKey(key);
		}
		
		//String address = (new StringBuilder(ch.socket().getInetAddress().toString())).append(":")
		//		.append(ch.socket().getPort()).toString();
		
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
        handler.close(timeout);

        // close socket, notify on-close handlers
        if (selector.isOpen()) {
//            Set<SelectionKey> keys = selector.keys();
//            SelectionKey[] keys = t.toArray(new SelectionKey[t.size()]);
            for (SelectionKey k : selector.keys()) {
                /**
                 * 1. t.toArray will fill null if given array is larger.
                 * 2. compute t.size(), then try to fill the array, if in the mean time, another
                 *    thread close one SelectionKey, will result a NPE
                 *
                 * https://github.com/http-kit/http-kit/issues/125
                 */
                if (k != null) {
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


	public void tryWrite(final SelectionKey key, ByteBuffer... buffers) {
		 SocketChannel ch = (SocketChannel) key.channel();
		 try {
			 ch.write(buffers, 0, buffers.length);
			 selector.wakeup();
		 } catch (IOException e) {
		 }
	  }
	
//	public void sendMessage( final clojure.lang.PersistentVector keys, final Map<Keyword, ?> message) throws IOException {
//		ByteBuffer buffer = MqttEncode.mqttEncoder(message);
//		
//		Iterator<?> it = keys.iterator();
//
//		while(it.hasNext() ) {
//			SelectionKey key = (SelectionKey) it.next();
//			ByteBuffer copyBuf = buffer.duplicate();
//			executor.submit(copyBuf, key);
//			sentMessages.getAndIncrement();
//			sentBytes.getAndAdd(buffer.limit());
//		}
//	}
	
	public void sendMessageBuffer( final clojure.lang.PersistentVector keys, final ByteBuffer buffer) throws IOException {
		Iterator<?> it = keys.iterator();

		while(it.hasNext() ) {
			SelectionKey key = (SelectionKey) it.next();
			ByteBuffer copyBuf = buffer.duplicate();
			executor.submit(copyBuf, key);
			sentMessages.getAndIncrement();
			sentBytes.getAndAdd(buffer.limit());
		}
	}

}
