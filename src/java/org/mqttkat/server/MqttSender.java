package org.mqttkat.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class MqttSender implements Runnable{
 	private final ByteBuffer[] buffers;
 	private final SelectionKey key;
 	private final Selector selector;
 	
	public MqttSender( ByteBuffer[] buffers, SelectionKey key, Selector selector) {
		this.buffers  = buffers;
		this.key = key;
		this.selector = selector;
	}
	
	public void run() {
		 SocketChannel ch = (SocketChannel) key.channel();
		 try {
			 ch.write(buffers, 0, buffers.length);
			 selector.wakeup();
		 } catch (IOException e) {
         selector.wakeup();
		 }		
	}
}

