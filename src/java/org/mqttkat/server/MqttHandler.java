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
  RespCallback cb;

  public MqttExecutor(IFn handler, RespCallback cb, IPersistentMap incoming) {
    this.handler = handler;
    this.incoming = incoming;
    this.cb = cb;
  }

  public MqttExecutor(IFn handler, IPersistentMap incoming) {
	    this.handler = handler;
	    this.incoming = incoming;
		this.cb  = null;}

public void run() {
    try {
      //System.out.println("Running in executor..:" +  incoming);
      Map resp = (Map) handler.invoke(incoming);
      //String resp = (String) handler.invoke(incoming);
      //System.out.println("Invoked..." +  handler.invoke(incoming).getClass().getName());
      if( resp != null) {
        //this.cb = (RespCallback) resp.get(CALL_BACK);
        cb.run(mqttEncoder(resp));
 

        //System.out.println("Callback runner called.. NOT NULL");
      } else {
        System.out.println("Handler return NUL, NOTHING TO SEND");
      }
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

    public void handle(IPersistentMap incoming, RespCallback cb) {
    	  if( incoming == null ){
    		  return;
    	  }
      System.out.println("HANDLER!!!" +  incoming);
      execs.submit(new MqttExecutor(handler, cb, incoming));
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
