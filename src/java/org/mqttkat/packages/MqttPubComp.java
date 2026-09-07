package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

/**
 * PUBCOMP. The packet is identical in shape to the other three
 * acknowledgements, so both directions live in MqttAck.
 */
public class MqttPubComp extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte[] data) throws IOException {
		return decode(key, data, 4);
	}

	public static IPersistentMap decode(SelectionKey key, byte[] data, int protocolVersion)
			throws IOException {
		return MqttAck.decode(key, data, protocolVersion, intern("PUBCOMP"));
	}

	public static ByteBuffer encode(Map<Keyword, ?> message) {
		return MqttAck.encode(message, MESSAGE_PUBCOMP, 0);
	}
}
