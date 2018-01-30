package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPingReq extends GenericMessage {

	public static IPersistentMap decodePingReq(byte flags) throws IOException {

		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PINGREQ"));
		m.put(FLAGS, flags);

		return PersistentArrayMap.create(m);

	}

}
