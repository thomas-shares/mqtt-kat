package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class MqttPingReq extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key) {
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("PINGREQ"));
		m.put(CLIENT_KEY, key);
		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer encode(Map<Keyword, ?> message) {
		ByteBuffer buffer = ByteBuffer.allocate(2);
		buffer.put((byte)(MESSAGE_PINGREQ << 4));
		buffer.put((byte)0);
		buffer.flip();
		return buffer;
	}
}
