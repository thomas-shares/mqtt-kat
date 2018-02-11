package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttDisconnect extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] remainAndPayload) throws IOException {
		System.out.println("DISCONNECT message...");

		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("DISCONNECT"));
		m.put(CLIENT_KEY, key);
		m.put(FLAGS, flags);

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map<?, ?> message) {
		byte[] bType = {(byte)(MESSAGE_PUBLISH << 4)};
		byte[] bLength = {0};
		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer length = ByteBuffer.wrap(bLength);

		return new ByteBuffer[]{type, length};
	}
}
