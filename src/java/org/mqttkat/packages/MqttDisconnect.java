package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class MqttDisconnect extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key) throws IOException {
		//System.out.println("DISCONNECT message...");
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("DISCONNECT"));
		m.put(CLIENT_KEY, key);

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer encode() {
		ByteBuffer buffer = ByteBuffer.allocate(2);
		buffer.put((byte)(MESSAGE_DISCONNECT << 4));
		buffer.put((byte)0);
		buffer.flip();

		return buffer;
	}
}
