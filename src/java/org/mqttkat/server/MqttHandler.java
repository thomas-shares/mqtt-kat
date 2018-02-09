package org.mqttkat.server;

import clojure.lang.IPersistentMap;

import clojure.lang.IFn;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Map;
import static org.mqttkat.server.MqttEncode.mqttEncoder;

class MqttExecutor implements Runnable{
  final IFn handler;
  final IPersistentMap incoming;

  public MqttExecutor(IFn handler, IPersistentMap incoming) {
    this.handler = handler;
    this.incoming = incoming;
  }



public void run() {
    try {
    		handler.invoke(incoming);
     } catch (Throwable e) {
      e.printStackTrace();
      System.out.println("Can't RUN!!! " + e.getMessage());
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
      BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(10);
      this.execs = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS, queue, factory);
    }

	public void handle(IPersistentMap incoming) {
		if( incoming ==  null ) {
			return;
		}
		execs.submit(new MqttExecutor(handler, incoming));
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
