package org.mqttkat;

import clojure.lang.IPersistentMap;

public interface IHandler {
    void handle(IPersistentMap incoming);
    //void handle(AsyncChannel channel, Frame frame);
    //public void clientClose(AsyncChannel channel, int status);
    // close any resource with this handler
    void close(int timeoutMs);
}