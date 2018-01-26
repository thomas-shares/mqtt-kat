package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPubAck extends GenericMessage {

	public static IPersistentMap decode(byte info, byte[] remainAndPayload) {
		System.out.println("PUBACK message...");
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PUBACK"));
		return PersistentArrayMap.create(m);
	}

}
