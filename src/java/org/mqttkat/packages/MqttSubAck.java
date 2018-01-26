package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import org.mqttkat.server.MqttUtil;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttSubAck extends GenericMessage{

	public static IPersistentMap decode(byte info, byte[] remainAndPayload) {
		System.out.println("SUBACK message...");
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("SUBACK"));
		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map message) {
		byte[] bType = {(byte) (MESSAGE_SUBACK << 4)};
		byte[] bLength = MqttUtil.calculateLenght(2);
		short packetId =  (Short) message.get(PACKET_IDENTIFIER);
		byte[] bPayload = new byte[3];
			
		bPayload[0] = (byte)(packetId & 0xff);
		bPayload[1] = (byte)((packetId >> 8) & 0xff);
	
		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer length = ByteBuffer.wrap(bLength);
		ByteBuffer payload = ByteBuffer.wrap(bPayload);

		return new ByteBuffer[]{type, length, payload};
	}
}
