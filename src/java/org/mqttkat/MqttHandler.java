package org.mqttkat;

import clojure.lang.IPersistentMap;
import clojure.lang.IFn;

import java.util.concurrent.*;

import org.mqttkat.server.PrefixThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MqttExecutor implements Runnable{

	private static final Logger log = LoggerFactory.getLogger(MqttExecutor.class);
	final IFn handler;
	final IPersistentMap incoming;
	final Object asyncChannel;

	public MqttExecutor(IFn handler, IPersistentMap incoming, Object asyncChannel) {
		this.handler = handler;
		this.incoming = incoming;
		this.asyncChannel = asyncChannel;
	}

	public void run() {
	    try {
				handler.invoke(incoming, asyncChannel);
	     } catch (Throwable e) {
	    	 	log.error("handler invocation failed for {}", incoming, e);
	    }
	}
}

public class MqttHandler implements IHandler {

	private static final Logger log = LoggerFactory.getLogger(MqttHandler.class);
    final ExecutorService execs;
    final IFn handler;

    public MqttHandler(IFn handler, ExecutorService execs) {
      this.handler = handler;
      this.execs = execs;
    }

    public MqttHandler(IFn handler, int thread) {
      this.handler = handler;
      PrefixThreadFactory factory = new PrefixThreadFactory("prefix");
      BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
      this.execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
    }

	public void handleInOrder(IPersistentMap incoming) {
		if( incoming == null ) {
			return;
		}
		try {
			handler.invoke(incoming, null);
		} catch (Throwable e) {
			log.error("handler invocation failed for {}", incoming, e);
		}
	}

	public void handleInOrder(IPersistentMap incoming, Object asyncChannel) {
		if( incoming == null ) {
			return;
		}
		try {
			handler.invoke(incoming, asyncChannel);
		} catch (Throwable e) {
			log.error("handler invocation failed for {}", incoming, e);
		}
	}

	public void handle(IPersistentMap incoming) {
		if( incoming ==  null ) {
			return;
		}

		Runnable task = new MqttExecutor(handler, incoming, null);

		try {
			if(!execs.isShutdown() && !execs.isTerminated())
				execs.submit(task);
		} catch (RejectedExecutionException e) {
			log.error("task rejected, handler={} incoming={}", handler, incoming, e);
		}
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

	public void connect(IPersistentMap connect) {
		// TODO Auto-generated method stub
	}

	public void handle(IPersistentMap incoming, Object asyncChannel) {
		if( incoming ==  null ) {
			return;
		}
		Runnable task = new MqttExecutor(handler, incoming, asyncChannel);

		try {
			execs.submit(task);
		} catch (RejectedExecutionException e) {
			log.error("task rejected, handler={} incoming={}", handler, incoming, e);
		}	}
}
