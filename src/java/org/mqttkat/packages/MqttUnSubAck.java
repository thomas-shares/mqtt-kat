package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.twoBytesToInt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class MqttUnSubAck extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data) throws IOException {
		//System.out.println("Server: UNSUBACK message recieved from client.");

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("UNSUBACK"));
		m.put(CLIENT_KEY, key);
		m.put(PACKET_IDENTIFIER, twoBytesToInt( data[0], data[1]));

		return PersistentArrayMap.create(m);
	}
	
	
	public static ByteBuffer[] encode(Map<Keyword, ?> message) {
		ByteBuffer payload = ByteBuffer.allocate(4);
		payload.put((byte) (MESSAGE_UNSUBACK << 4));
		payload.put((byte) 0x02);
		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		payload.put((byte) ((packetIdentifierL >>> 8) & 0xFF)).put((byte) (packetIdentifierL & 0xFF));
		payload.flip();
		return new ByteBuffer[]{payload};
	}
}
