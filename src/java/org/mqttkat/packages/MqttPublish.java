package org.mqttkat.packages;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.*;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public class MqttPublish extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] remainAndPayload) throws IOException {
		//System.out.println("PUBLISH message...");
		int offset = 0;
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("PUBLISH"));
		m.put(CLIENT_KEY, key);

		//for(int i = 0 ; i < remainAndPayload.length; i++) {
		//	System.out.print(remainAndPayload[i] + " ");
		//}
		//System.out.println("\n");
		int qos = qos(flags & 0x06);
		m.put(QOS, qos);
		//System.out.println(qos);
		m.put(RETAIN, (flags & 0x01) == 0x01);
		String topic = decodeUTF8(remainAndPayload, 0);
		offset += topic.length() + 2;
		m.put(TOPIC, topic);
		if(qos > 0 ) {
			m.put(PACKET_IDENTIFIER, twoBytesToLong( remainAndPayload[offset++], remainAndPayload[offset++]));
			m.put(DUPLICATE, (flags & 0x08) == 0x08);
		}
		//System.out.println("index: " + (topic.length() + 2) + " length: " + remainAndPayload.length + " topic: " + topic);
		m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, offset, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer encode(Map<Keyword, ?> message) throws UnsupportedEncodingException {
		//System.out.println("PUBLISHING MESSAGE TO CLIENT: " + message.toString());
		int length = 0;
		
		byte[] bytes = new byte[MESSAGE_LENGTH];
		ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_LENGTH);
		byte firstByte = (byte) (MESSAGE_PUBLISH << 4);
		
		if((Boolean) message.get(RETAIN)) {
			firstByte = (byte) (0x01 | firstByte);
		}
		if(message.containsKey(DUPLICATE) && (Boolean) message.get(DUPLICATE)) {
			firstByte = (byte) (0x08 | firstByte);
		}
		
		byte qos = Byte.parseByte(((Long) message.get(QOS)).toString());
		firstByte = (byte) ((qos << 1) | firstByte);

		buffer.put(firstByte);
		
		byte[] topic = ((String) message.get(TOPIC)).getBytes("UTF-8");
		bytes[length++] = (byte) ((topic.length >>> 8) & 0xFF);
		bytes[length++] = (byte) (topic.length & 0xFF);
		for(int i = 0; i < topic.length; i++) {
			bytes[length++] = topic[i];
		}
		
		if(qos > 0) {
			Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);

			bytes[length++] = (byte) ((packetIdentifierL >>> 8) & 0xFF);
			bytes[length++] = (byte) ((packetIdentifierL >>> 0) & 0xFF);
		}

		Object obj = message.get(PAYLOAD);
		if( obj instanceof String) {
			byte[] strBytes  = ((String) obj).getBytes();
			for(int i = 0; i < strBytes.length; i++) {
				bytes[length++] = strBytes[i];
			}
		} else {
			byte[] payloadBytes = (byte[])obj;
			if( payloadBytes != null ) {
				for(int i = 0; i < payloadBytes.length; i++) {
					bytes[length++] = payloadBytes[i];
				}
			}
		}

		buffer.put(calculateLength(length));
		buffer.put(bytes, 0, length);
		buffer.flip();
		//log("length: " + length);
		return buffer;
	}
}
