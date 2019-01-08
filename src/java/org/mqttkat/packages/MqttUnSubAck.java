package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class MqttUnSubAck extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] remainAndPayload) throws IOException {
		System.out.println("UNSUBACK message...");

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("UNSUBACK"));
		m.put(CLIENT_KEY, key);

		return PersistentArrayMap.create(m);
	}

}
