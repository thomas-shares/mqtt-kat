package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class MqttPingReq extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags) throws IOException {

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("PINGREQ"));
		m.put(CLIENT_KEY, key);

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map<Keyword, ?> message) throws UnsupportedEncodingException {
		byte[] bType = {(byte)(MESSAGE_PINGREQ << 4)};
		System.out.println( bType[0]);
		byte[] bLength = {0};
		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer length = ByteBuffer.wrap(bLength);

		return new ByteBuffer[]{type, length};
	}
}