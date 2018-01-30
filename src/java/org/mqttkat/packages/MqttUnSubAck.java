package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttUnSubAck extends GenericMessage {

	public static IPersistentMap decode(byte flags, byte[] remainAndPayload) throws IOException {
		System.out.println("UNSUBACK message...");

		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("UNSUBACK"));
		m.put(FLAGS, flags);
		return PersistentArrayMap.create(m);
	}

}
