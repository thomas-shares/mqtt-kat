package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLength;
import static org.mqttkat.MqttUtil.fit;
import static org.mqttkat.MqttUtil.decodeUTF8;
import static org.mqttkat.MqttUtil.encodedUTF8Length;
import static org.mqttkat.MqttUtil.twoBytesToLong;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;

public class MqttUnsubscribe extends GenericMessage{

	public static IPersistentMap decode(SelectionKey key, byte[] data) throws IOException {
		//System.out.println("UNSUBSCRIBE message...");

		int offset = 0;
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();

		m.put(PACKET_TYPE, intern("UNSUBSCRIBE"));

		m.put(PACKET_IDENTIFIER, twoBytesToLong( data[offset++], data[offset++]));
		
	    IPersistentVector vector = PersistentVector.create();

		while(offset < data.length) {
			String topic = decodeUTF8(data, offset);
			offset += encodedUTF8Length(data, offset);
			vector = vector.cons(topic);
		}

		m.put(TOPICS, vector);
		m.put(CLIENT_KEY, key);

		return PersistentArrayMap.create(m);
	}
	
	public static ByteBuffer encode(Map<Keyword, ?> message) throws UnsupportedEncodingException {
		int length = 0;
		// MESSAGE_LENGTH is the starting size; fit() grows past it.
		byte[] bytes = new byte[MESSAGE_LENGTH];
		byte[] bType = {(byte) (MESSAGE_UNSUBSCRIBE << 4) | 0x02};
		byte firstByte = (byte) (bType[0] & 0xf2);
		
		
		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		bytes[length++] = (byte) ((packetIdentifierL >>> 8) & 0xFF);
		bytes[length++] = (byte) (packetIdentifierL & 0xFF);

		PersistentVector vector = (PersistentVector) message.get(TOPICS);

		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			byte[] topic = ((String) it.next()).getBytes("UTF-8");
			bytes = fit(bytes, length, 2 + topic.length);
			bytes[length++] = (byte) ((topic.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (topic.length & 0xFF);
			for(int i = 0; i < topic.length; i++) {
				bytes[length++] = topic[i];
			}
		}
		
		byte[] remaining = calculateLength(length);
		ByteBuffer buffer = ByteBuffer.allocate(1 + remaining.length + length);
		buffer.put(firstByte);
		buffer.put(remaining);
		buffer.put(bytes, 0, length);
		//log("buffers.size: " + buffers.size());
		// for(int i=0; i < length ;i++ ){
		//   //System.out.print(" " + buffer.get(i));
		//   System.out.print(" " + String.format("%02X", buffer.get(i)));
		// }
		buffer.flip();
		//log("length: " + length);
		return buffer;
	}
}
