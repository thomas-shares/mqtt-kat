package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class MqttConnAck extends GenericMessage{
	
	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data) {
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("CONNACK"));
		m.put(CLIENT_KEY, key);
		m.put(SESSION_PRESENT, data[0] == 1);
		m.put(CONNECT_RETURN_CODE, data[1]);

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map<Keyword, ?> message) {
		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.put((byte)(MESSAGE_CONNACK << 4));
		buffer.put((byte)2);
		buffer.put((byte) ((Boolean) message.get(SESSION_PRESENT) ? 1 : 0));
		buffer.put(Byte.parseByte(message.get(CONNECT_RETURN_CODE).toString()));
		buffer.flip();
		//System.out.println(message.get(SESSION_PRESENT));

		return new ByteBuffer[]{buffer};
	}
}