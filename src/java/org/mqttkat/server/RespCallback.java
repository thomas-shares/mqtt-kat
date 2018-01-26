package org.mqttkat.server;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class RespCallback {
    private final SelectionKey key;
    private final MqttServer server;

    public RespCallback(SelectionKey key, MqttServer server) {
        this.key = key;
        this.server = server;
    }

    // maybe in another thread :worker thread
    public void run(ByteBuffer... buffers) {
      System.out.println("callback runner....");
        server.tryWrite(key, buffers);
    }
}
