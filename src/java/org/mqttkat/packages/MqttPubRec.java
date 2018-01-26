package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPubRec extends GenericMessage{

	public static IPersistentMap decode(byte info, byte[] remainAndPayload) {
		System.out.println("PUBREC message...");
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PUBREC"));
		return PersistentArrayMap.create(m);
	}

}
