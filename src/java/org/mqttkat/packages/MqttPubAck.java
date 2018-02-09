package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPubAck extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] remainAndPayload) throws IOException {
		System.out.println("PUBACK message...");

		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PUBACK"));
		m.put(CLIENT_KEY, key);

		m.put(FLAGS, flags);

		return PersistentArrayMap.create(m);
	}

}
