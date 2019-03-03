package org.mqttkat;

import clojure.lang.IPersistentMap;

public interface IHandler {
    void handle(IPersistentMap incoming);

    void handle(IPersistentMap incoming, Object asyncChannel);
    void connect(IPersistentMap connect);
    
    void close(int timeoutMs);
}
