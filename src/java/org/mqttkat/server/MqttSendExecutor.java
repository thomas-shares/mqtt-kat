package org.mqttkat.server;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MqttSendExecutor {
	private final ExecutorService execs;
	private final Selector selector;
		
	MqttSendExecutor(Selector selector, int thread) {
		PrefixThreadFactory factory = new PrefixThreadFactory("senders-");
	    BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(10);
	    this.execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
	    this.selector = selector;
	}
	
	public void submit(ByteBuffer[] buffers, SelectionKey key) {
		execs.submit(new MqttSender(buffers, key, selector));
	}
	
	public void close(int timeoutMs) {
		if (timeoutMs > 0) {
			execs.shutdown();
		    try {
		    		if (!execs.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
		            execs.shutdownNow();
		        }
		    } catch (InterruptedException ie) {
	            execs.shutdownNow();
		        Thread.currentThread().interrupt();
	        }
		} else {
			execs.shutdownNow();
		}		
	}
}