package org.mqttkat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class MqttSender implements Runnable{
 	private final ByteBuffer buffer;
 	private final SelectionKey key;
 	private final Selector selector;
 	
	public MqttSender( ByteBuffer buffer, SelectionKey key, Selector selector) {
		this.buffer  = buffer;
		this.key = key;
		this.selector = selector;
	}

	public void run() {
		 SocketChannel ch = (SocketChannel) key.channel();
		 try {
			 ch.write(buffer);
			 selector.wakeup();
		 } catch (IOException e) {
         selector.wakeup();
		 }		
	}
}

