package org.mqttkat.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.mqttkat.MqttSendExecutor;
import org.mqttkat.server.MqttEncode;

import static org.mqttkat.MqttUtil.log;

public class MqttClient implements Runnable {
	private final int threadPoolSize;
	private final int port;
	private final String host;
    private volatile boolean running = true;
    private InetSocketAddress mqttAddr;
    private SocketChannel mqttClient;
    private final Selector selector;
	private MqttSendExecutor executor;


	public MqttClient(String host, int port, int threadPoolSize) throws IOException {
		this.host = host;
		this.port = port;
		this.threadPoolSize = threadPoolSize;

		log("Creating client...");
		InetSocketAddress mqttAddr = new InetSocketAddress(host, port);
		mqttClient = SocketChannel.open(mqttAddr);
	    selector = Selector.open();
		this.executor = new MqttSendExecutor(selector, threadPoolSize);

	    Thread t = new Thread(this, "mqtt-client");
	    t.setDaemon(true);
	    t.start();
	}
	
	public void sendMessage(final Map<?, ?> message) throws IOException {
		log("sending message...");
		ByteBuffer bufs[] = MqttEncode.mqttEncoder(message);
		for(int i = 0; i< bufs.length ; i++) {
			log(bufs[i].toString());
		}
		mqttClient.write(bufs);
		//executor.submit(bufs, key);
	}

	public void run() {
        while (running) {
        	
        }

        	
	}
	
    public void close() throws IOException {
        running = false;
        if (selector != null) {
            selector.close();
        }
    }

}
