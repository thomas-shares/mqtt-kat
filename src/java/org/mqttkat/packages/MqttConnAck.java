package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import org.mqttkat.MqttUtil;

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
		byte[] bType = {(MESSAGE_CONNACK << 4)};
		ByteBuffer length = MqttUtil.calculateLenght(2);
		byte[] bPayload = new byte[2];

		//System.out.println(message.get(SESSION_PRESENT));

		bPayload[0] = (byte) ((Boolean) message.get(SESSION_PRESENT) ? 1 : 0);
		bPayload[1] =  Byte.parseByte(message.get(CONNECT_RETURN_CODE).toString()) ;
		//System.out.println(message.toString() + String.format("%x", bType[0]) + String.format("%x", bLength[0]) + String.format("%x%x", bPayload[0],bPayload[1]));

		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer payload = ByteBuffer.wrap(bPayload);

		return new ByteBuffer[]{type, length, payload};
	}
}