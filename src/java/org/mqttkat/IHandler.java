package org.mqttkat;

import clojure.lang.IPersistentMap;

public interface IHandler {
    void handle(IPersistentMap incoming);

    /**
     * Run the handler on the calling thread instead of queueing it. The server
     * calls this from a connection's own thread, which is what keeps one
     * client's packets in the order they were sent; handle() cannot, because
     * every packet becomes an independent task.
     */
    void handleInOrder(IPersistentMap incoming);

    void handle(IPersistentMap incoming, Object asyncChannel);

    /** As handleInOrder, for callers that carry an async channel. */
    void handleInOrder(IPersistentMap incoming, Object asyncChannel);
    void connect(IPersistentMap connect);
    
    void close(int timeoutMs);
}
