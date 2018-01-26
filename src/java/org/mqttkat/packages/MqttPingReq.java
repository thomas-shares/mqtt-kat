package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPingReq extends GenericMessage {

	public static IPersistentMap decodePingReq() {
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PINGREQ"));
		return PersistentArrayMap.create(m);

	}

}
