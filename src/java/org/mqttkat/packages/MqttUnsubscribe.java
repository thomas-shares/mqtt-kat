package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.decodeUTF8;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;

public class MqttUnsubscribe extends GenericMessage{

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data, int msgLength) throws IOException {
		System.out.println("UNSUBSCRIBE message...");

		int offset = 0;
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();

		m.put(PACKET_TYPE, intern("UNSUBSCRIBE"));

		m.put(PACKET_IDENTIFIER, (short)((data[offset++]<<8) | data[offset++]));

	    IPersistentVector v = PersistentVector.create();

		while(offset < msgLength) {
		   // Map<Object, Object> topicMap = new TreeMap<Object, Object>();
			String topic = decodeUTF8(data, offset);
			//System.out.println("topic: " + topic);
			//topicMap.put(TOPIC, topic);
			offset += topic.length() + 2;
			//System.out.println(offset);
			//topicMap.put(MSG_QOS, qos(data[offset++]));
			//System.out.println(offset + " " +  msgLength + " " + v.toString() );

			v = v.cons(topic);
			//System.out.println(v.toString());
		}
		//System.out.println("uit de loop: " +  offset + " " + msgLength + " " + v.toString());
	    //IPersistentVector v = PersistentVector.create(1, 2, 3);

		
		//PersistentArrayMap  map = PersistentArrayMap.create(arg0)

		m.put(TOPICS, v);
		m.put(CLIENT_KEY, key);
		//m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, topic.length() + 2, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}
}