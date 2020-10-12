package org.mqttkat;

import clojure.lang.IPersistentMap;
import clojure.lang.IFn;

import java.util.concurrent.*;

import org.mqttkat.server.PrefixThreadFactory;

class MqttExecutor implements Runnable{
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
	    	 	e.printStackTrace();
	    	 	System.out.println("Can't RUN!!! " + e.getMessage() + " " + incoming);
	    }
	}
}

public class MqttHandler implements IHandler {
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

	public void handle(IPersistentMap incoming) {
		if( incoming ==  null ) {
			return;
		}

		Runnable task = new MqttExecutor(handler, incoming, null);

		try {
			execs.submit(task);
		} catch (RejectedExecutionException e) {
			e.printStackTrace();
			System.out.println("handler : " + handler.toString());
			System.out.println("incoming : " + incoming.toString());
			System.out.println ("Handling Fails.");
		}
	}

	public void close(int timeoutMs) {
        MqttSendExecutor.shutdown(timeoutMs, execs);
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
			e.printStackTrace();
			System.out.println("handler : " + handler.toString());
			System.out.println("incoming : " + incoming.toString());
			System.out.println ("Handling Fails.");
		}	}
}
