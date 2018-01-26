package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttDisconnect extends GenericMessage {

	public static IPersistentMap decode(byte info, byte[] remainAndPayload) {
		System.out.println("DISCONNECT message...");
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("DISCONNECT"));
		return PersistentArrayMap.create(m);
	}
}
