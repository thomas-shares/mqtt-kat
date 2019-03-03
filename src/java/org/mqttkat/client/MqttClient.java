package org.mqttkat.client;

import static org.mqttkat.MqttUtil.log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.mqttkat.IHandler;
import org.mqttkat.packages.GenericMessage;
import org.mqttkat.packages.MqttAuthenticate;
import org.mqttkat.packages.MqttConnAck;
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

import clojure.lang.IPersistentMap;
import static org.mqttkat.MqttStat.*;

public class MqttClient implements Runnable {
	private volatile boolean running = true;
	private InetSocketAddress mqttAddr;
	private SocketChannel socketChannel;
	private final Selector selector;
	private final IHandler handler;
	private final Object asyncChannel;
	private Map<SocketChannel, List> pendingData = new HashMap<SocketChannel, List>();
	// A list of PendingChange instances
	private List<ChangeRequest> pendingChanges = new LinkedList<ChangeRequest>();
	// The buffer into which we'll read data when it's available
	private ByteBuffer readBuffer = ByteBuffer.allocate(8192);
	
	public MqttClient(String host, int port, int threadPoolSize, IHandler handler, Object asyncChannel) throws IOException {
		log("Creating client...");
		this.handler = handler;

		mqttAddr = new InetSocketAddress(host, port);
		socketChannel = SocketChannel.open(mqttAddr);
		socketChannel.configureBlocking(false);

		this.asyncChannel = asyncChannel;
		
		selector = Selector.open();
		//this.executor = new MqttSendExecutor(selector, threadPoolSize);

		Thread t = new Thread(this, "mqtt-client");
		t.setDaemon(true);
		t.start();
	}

	public Object getChannel() {
		return this.asyncChannel;
	}
	
	public void sendMessage(ByteBuffer buffer) throws IOException {
		sentMessages.incrementAndGet();
		sentBytes.addAndGet(buffer.limit());

		// And queue the data we want written
		synchronized (this.pendingData) {
			@SuppressWarnings("unchecked")
			List<ByteBuffer> queue = (List<ByteBuffer>) this.pendingData.get(socketChannel);
			if (queue == null) {
				queue = new ArrayList<ByteBuffer>();
				this.pendingData.put(socketChannel, queue);
			}
			queue.add(buffer);
			//System.out.println( "queue size: " + queue.size());
		}
		synchronized(this.pendingChanges) {
			ChangeRequest changeRequest =  new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_WRITE);
			this.pendingChanges.add(changeRequest);
		}
				
		// Finally, wake up our selecting thread so it can make the required changes
		this.selector.wakeup();
		//System.out.println("woken up...");
	}

	public void run() {
		System.out.println("Client loop started running...");
		while (running) {
			try {

				synchronized (this.pendingChanges) {
					Iterator<?> changes = this.pendingChanges.iterator();
					//System.out.println("changes..." + changes.hasNext());
					while (changes.hasNext()) {
						ChangeRequest change = (ChangeRequest) changes.next();
						switch (change.type) {
						case ChangeRequest.CHANGEOPS:
							//System.out.println("OPS...");

							SelectionKey key = change.socket.keyFor(this.selector);
							key.interestOps(change.ops);
							break;
						case ChangeRequest.REGISTER:
							//System.out.println("REGISTER...");
							change.socket.register(this.selector, change.ops);
							break;
						}
					}
					this.pendingChanges.clear();
				}
				// Wait for an event one of the registered channels
				int x = this.selector.select();
				//System.out.println("selected..." + this.selector.selectedKeys().size() + "  " + x);
				
				// Iterate over the set of keys for which events are available
				Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey key = (SelectionKey) selectedKeys.next();
					//System.out.println("key: " + key.toString());

					selectedKeys.remove();

					if (!key.isValid()) {
						continue;
					}

					// Check what event is available and deal with it
					if (key.isConnectable()) {
						this.finishConnection(key);
					} else if (key.isReadable()) {
						//System.out.println("ready for read...");
						this.read(key);
						//System.out.println("done read in loop");
					} else if (key.isWritable()) {
						//System.out.println("ready for write...");
						this.write(key);
					}
				}
			}  catch (Exception e) {
				//System.out.println(e.getMessage());
			//	e.printStackTrace();
			}
		}
	}

	private void read(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Clear out our read buffer so it's ready for new data
		this.readBuffer.clear();

		// Attempt to read off the channel
		int numRead;
		try {
			numRead = socketChannel.read(this.readBuffer);
		} catch (IOException e) {
			// The remote forcibly closed the connection, cancel
			// the selection key and close the channel.
			key.cancel();
			socketChannel.close();
			return;
		}

		if (numRead == -1) {
			// Remote entity shut the socket down cleanly. Do the
			// same from our end and cancel the channel.
			key.channel().close();
			key.cancel();
			return;
		}

		// Handle the response
		this.handleResponse(this.readBuffer.array(), numRead);
	}

	private void handleResponse( byte[] data, int numRead) throws IOException {
		// Make a correctly sized copy of the data before handing it
		// to the client this can be multiple MQTT packets...

		int i = 0 ;
		//System.out.println("read " + numRead);
		do {
 
			//System.out.println("start " + i);

			byte type = (byte) ((data[i] & 0xff) >> 4);
			byte flags = (byte) (data[i] &= 0x0f);
			
	
			byte digit;
			int multiplier = 1;
			int msgLength = 0;
			//System.out.println( "limit: " + buf.limit() + " position: " + buf.position() + " capacity: " + buf.capacity() );
			i++;
			do {
				digit =  data[i++];
				msgLength += ((digit & 0x7F) * multiplier);
				multiplier *= 128;
			} while ((digit & 0x80) != 0);
			//System.out.println(msgLength);
	
			byte[] rspData = new byte[msgLength];
			System.arraycopy(data, i, rspData, 0, msgLength);
	 		i += msgLength;
			
			//for(byte q :rspData) {
			//	System.out.print(q + " ");
			//}
			//System.out.println("\n");
			
	
			
			SelectionKey key = null;
			IPersistentMap incoming = null;
			if (type == GenericMessage.MESSAGE_CONNECT) {	
				incoming =  MqttConnect.decode(key, flags, rspData);
			} else if ( type ==  GenericMessage.MESSAGE_CONNACK) {
				incoming = MqttConnAck.decode(key, flags, rspData);
			} else if (type == GenericMessage.MESSAGE_PUBLISH) {
				incoming = MqttPublish.decode(key, flags, rspData);
			} else if (type == GenericMessage.MESSAGE_PUBACK) {
				incoming = MqttPubAck.decode(key,  flags, rspData);
			} else if (type == GenericMessage.MESSAGE_PUBREC) {
				incoming = MqttPubRec.decode(key, flags, rspData);
			} else if (type == GenericMessage.MESSAGE_PUBREL) {
				incoming = MqttPubRel.decode(key,  flags, rspData);
			} else if (type == GenericMessage.MESSAGE_PUBCOMP) {
				incoming = MqttPubComp.decode(key,  flags, rspData);
			} else if (type == GenericMessage.MESSAGE_SUBSCRIBE) {
				incoming = MqttSubscribe.decode(key, flags, rspData);
			} else if( type == GenericMessage.MESSAGE_SUBACK) {
				incoming = MqttSubAck.decode(key, flags, rspData);
			} else if (type == GenericMessage.MESSAGE_UNSUBSCRIBE) {
				incoming = MqttUnsubscribe.decode(key, flags, rspData);
			} else if ( type == GenericMessage.MESSAGE_UNSUBACK ) {
				incoming = MqttUnSubAck.decode(key,  flags, rspData);
			} else if (type == GenericMessage.MESSAGE_PINGREQ) {
				incoming = MqttPingReq.decode(key,  flags);
			} else if (type == GenericMessage.MESSAGE_PINGRESP) {
				incoming = MqttPingResp.decode(key,  flags);
			} else if (type == GenericMessage.MESSAGE_DISCONNECT) {
				incoming = MqttDisconnect.decode(key,  flags, rspData);
			} else if ( type ==  GenericMessage.MESSAGE_AUTHENTICATION) {
				incoming = MqttAuthenticate.decode(key, flags, rspData);
			} else {
				System.out.println("FAIL!!!!!! INVALID packet sent: " + type);
			}
	
			if( incoming != null ) {
				handler.handle(incoming, this.asyncChannel);
				receivedMessage.incrementAndGet();
				receivedBytes.addAndGet(msgLength);
			}
		} while( i < numRead );
	}
	
	private void write(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		synchronized (this.pendingData) {
			List<?> queue = (List<?>) this.pendingData.get(socketChannel);

			// Write until there's not more data ...
			while (!queue.isEmpty()) {
				ByteBuffer buffer = (ByteBuffer) queue.get(0);
					//log("buf: " + buf.toString());
					socketChannel.write(buffer);
					if (buffer.remaining() > 0) {
						// ... or the socket's buffer fills up
						break;
					}
				queue.remove(0);
			}

			if (queue.isEmpty()) {
				// We wrote away all data, so we're no longer interested
				// in writing on this socket. Switch back to waiting for
				// data.
				key.interestOps(SelectionKey.OP_READ);
			}
		}
	}

	private void finishConnection(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Finish the connection. If the connection operation failed
		// this will raise an IOException.
		try {
			socketChannel.finishConnect();
		} catch (IOException e) {
			// Cancel the channel's registration with our selector
			System.out.println(e);
			key.cancel();
			return;
		}

		// Register an interest in writing on this channel
		key.interestOps(SelectionKey.OP_WRITE);
	}

	public void close() throws IOException {
		//System.out.println("Client stopping...");

		running = false;
		if (selector != null) {
			selector.close();
		}
	}
}
