package org.mqttkat.packages;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.server.MqttUtil.decodeUTF8;
import static org.mqttkat.server.MqttUtil.qos;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPublish extends GenericMessage {

	public static IPersistentMap decode(byte flags, byte[] remainAndPayload) throws IOException {
		System.out.println("PUBLISH message...");
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PUBLISH"));
		m.put(FLAGS, flags);

		m.put(DUPLICATE, (flags & 0x08) == 0x08);
		m.put(MSG_QOS, qos((flags & 0x06)));
		m.put(RETAIN, (flags & 0x01) == 0x01);
		String topic = decodeUTF8(remainAndPayload, 0);
		m.put(TOPIC, topic);
		m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, topic.length() + 2, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}

}
