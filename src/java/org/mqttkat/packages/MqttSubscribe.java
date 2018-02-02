package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import static org.mqttkat.server.MqttUtil.decodeUTF8;
import static org.mqttkat.server.MqttUtil.qos;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import clojure.lang.IPersistentVector;

public class MqttSubscribe extends GenericMessage{

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data, int msgLength) throws IOException {
		System.out.println("SUBSCRIBE message...");

		int offset = 0;
		Map<Object, Object> m = new TreeMap<Object, Object>();

		m.put(PACKET_TYPE, intern("SUBSCRIBE"));
		m.put(FLAGS, flags);

		m.put(PACKET_IDENTIFIER, (short)((data[offset++]<<8) | data[offset++]));

	    IPersistentVector v = PersistentVector.create();

		while(offset < msgLength) {
		    Map<Object, Object> topicMap = new TreeMap<Object, Object>();
			String topic = decodeUTF8(data, offset);
			System.out.println("topic: " + topic);
			topicMap.put(TOPIC, topic);
			offset += topic.length() + 2;
			System.out.println(offset);
			topicMap.put(MSG_QOS, qos(data[offset++]));
			System.out.println(offset + " " +  msgLength + " " + v.toString() + " " + topicMap.toString());

			v = v.cons(PersistentArrayMap.create(topicMap));
			System.out.println(v.toString());

		}
		System.out.println("uit de loop: " +  offset + " " + msgLength + " " + v.toString());
	    //IPersistentVector v = PersistentVector.create(1, 2, 3);

		
		//PersistentArrayMap  map = PersistentArrayMap.create(arg0)

		m.put(TOPICS, v);
		m.put(CLIENT_KEY, key);
		//m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, topic.length() + 2, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}

}
