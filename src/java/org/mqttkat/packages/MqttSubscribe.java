package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.server.MqttUtil.decodeUTF8;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttSubscribe extends GenericMessage{

	public static IPersistentMap decode(byte info, byte[] data) throws IOException {
		System.out.println("SUBSCRIBE message...");
		int offset = 0;
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("SUBSCRIBE"));
		m.put(PACKET_IDENTIFIER, (short)((data[offset++]<<8) | data[offset++]));


		String topic = decodeUTF8(data, offset);
		m.put(TOPIC, topic);
		//m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, topic.length() + 2, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}

}
