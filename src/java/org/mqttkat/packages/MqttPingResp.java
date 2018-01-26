package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import org.mqttkat.server.MqttUtil;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPingResp extends GenericMessage {

	public static IPersistentMap decode() {
		System.out.println("PINGRESP message...");
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PINGRESP"));
		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map message) {
		byte[] bType = {(byte)(MESSAGE_PINGRESP << 4)};
		byte[] bLength = {0};
		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer length = ByteBuffer.wrap(bLength);

		return new ByteBuffer[]{type, length};
	}

}
