package org.mqttkat.client;

import static org.mqttkat.MqttUtil.log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.mqttkat.IHandler;
import org.mqttkat.MqttSendExecutor;
import org.mqttkat.packages.GenericMessage;

public class MqttClient implements Runnable {
	private volatile boolean running = true;
	private InetSocketAddress mqttAddr;
	private SocketChannel socketChannel;
	private final Selector selector;
	private MqttSendExecutor executor;
	private final IHandler handler;
	private Map<SocketChannel, List> pendingData = new HashMap<SocketChannel, List>();
	// A list of PendingChange instances
	private List<ChangeRequest> pendingChanges = new LinkedList<ChangeRequest>();
	// The buffer into which we'll read data when it's available
	private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

	public MqttClient(String host, int port, int threadPoolSize, IHandler handler) throws IOException {
		log("Creating client...");
		this.handler = handler;

		mqttAddr = new InetSocketAddress(host, port);
		socketChannel = SocketChannel.open(mqttAddr);
		socketChannel.configureBlocking(false);


		
		selector = Selector.open();
		//this.executor = new MqttSendExecutor(selector, threadPoolSize);

		Thread t = new Thread(this, "mqtt-client");
		t.setDaemon(true);
		t.start();
	}

	public void sendMessage(ByteBuffer bufs[]) throws IOException {
		for (int i = 0; i < bufs.length; i++) {
			//log(bufs[i].toString());
		}
		// executor.submit(bufs);

		// socketChannel.write(bufs);
		// And queue the data we want written
		synchronized (this.pendingData) {
			@SuppressWarnings("unchecked")
			List<ByteBuffer[]> queue = (List<ByteBuffer[]>) this.pendingData.get(socketChannel);
			if (queue == null) {
				queue = new ArrayList<ByteBuffer[]>();
				this.pendingData.put(socketChannel, queue);
			}
			queue.add(bufs);
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
		System.out.println("started running...");
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
		// to the client
		byte[] rspData = new byte[numRead];
		System.arraycopy(data, 0, rspData, 0, numRead);

		
		for(byte i :rspData) {
			//System.out.print(i + " ");
		}
		//System.out.println("\n");
		
				
		byte type = 0;
		byte flags = 0;
		
		type = (byte) ((rspData[0] & 0xff) >> 4);
		flags = (byte) (rspData[0] &= 0x0f);

		if (type == GenericMessage.MESSAGE_CONNECT) {
			//System.out.println("CONNECT");
		} else if (type == GenericMessage.MESSAGE_CONNACK) {
			System.out.println("CONNACK");
		} else if (type == GenericMessage.MESSAGE_PUBLISH) {
			System.out.println("PUBLISH");
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
		} else if (type == GenericMessage.MESSAGE_UNSUBSCRIBE) {
			System.out.println("UNSUBSCRIBE");
		} else if (type == GenericMessage.MESSAGE_PINGREQ) {
			System.out.println("PINGREQ");
		} else if ( type == GenericMessage.MESSAGE_PINGRESP) {
			System.out.println("PINGRESP");
		} else if (type == GenericMessage.MESSAGE_DISCONNECT) {
			System.out.println("DISCONNECT");
		} else if ( type ==  GenericMessage.MESSAGE_AUTHENTICATION) {	
			System.out.println("AUTHENTICATE");
		} else {
			System.out.println("FAIL!!!!!! INVALID packet sent: " + type);
		}
		//handler.handle(incoming);
	}
	
	private void write(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		synchronized (this.pendingData) {
			List<?> queue = (List<?>) this.pendingData.get(socketChannel);

			// Write until there's not more data ...
			while (!queue.isEmpty()) {
				ByteBuffer[] bufs = (ByteBuffer[]) queue.get(0);
				for(ByteBuffer buf : bufs) {
					//log("buf: " + buf.toString());
					socketChannel.write(buf);
					if (buf.remaining() > 0) {
						// ... or the socket's buffer fills up
						break;
					}
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
		running = false;
		if (selector != null) {
			selector.close();
		}
	}

}
