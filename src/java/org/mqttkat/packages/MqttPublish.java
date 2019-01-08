package org.mqttkat.packages;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

		m.put(DUPLICATE, (flags & 0x08) == 0x08);
		int qos = qos(flags & 0x06);
		m.put(QOS, qos);
		System.out.println(qos);
		m.put(RETAIN, (flags & 0x01) == 0x01);
		String topic = decodeUTF8(remainAndPayload, 0);
		offset += topic.length() + 2;
		m.put(TOPIC, topic);
		if(qos > 0 ) {
			m.put(PACKET_IDENTIFIER, twoBytesToInt( remainAndPayload[offset++], remainAndPayload[offset++]));
		}
		//System.out.println("index: " + (topic.length() + 2) + " length: " + remainAndPayload.length + " topic: " + topic);
		m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, offset, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map<Keyword, ?> message) throws UnsupportedEncodingException {
		//System.out.println("PUBLISHING MESSAGE TO CLIENT: " + message.toString());
		int runningLength = 0;
		byte[] bType = {(byte)(MESSAGE_PUBLISH << 4)};
		if((Boolean) message.get(RETAIN)) {
			bType[0] = (byte) (0x01 | bType[0]);
		}
		if(message.containsKey(DUPLICATE) && (Boolean) message.get(DUPLICATE)) {
			bType[0] = (byte) (0x08 | bType[0]);
		}
		
		byte qos = Byte.parseByte(((Long) message.get(QOS)).toString());
		bType[0] = (byte) ((qos << 1) | bType[0]);
		List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(2);
		buffers.add(0, ByteBuffer.wrap(bType));
		
		byte[] lengthByte = {0};
		buffers.add(1, ByteBuffer.wrap(lengthByte) );

		ByteBuffer topic = encodeUTF8Buffer((String)message.get(TOPIC));
		topic.flip();
		runningLength += topic.remaining();
		buffers.add(2, topic);
	
		Object obj = message.get(PAYLOAD);
		byte[] bPayload = null;
		if( obj instanceof String) {
			bPayload = ((String) obj).getBytes();
		} else {
			bPayload = (byte[])obj;
		}
		runningLength += bPayload.length;
		
		ByteBuffer packetIndentifier = ByteBuffer.allocate(2);
		if(qos > 0) {
			Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
			String k1 = String.format("%8s", Integer.toBinaryString((byte) ((packetIdentifierL >>> 8) & 0xFF)  & 0xFF)).replace(' ', '0');
			String k2 = String.format("%8s", Integer.toBinaryString((byte) (packetIdentifierL & 0xFF)  & 0xFF)).replace(' ', '0');

			System.out.println("hoog: " +  k1 );
			System.out.println("laag: " + k2);
			packetIndentifier.put((byte) ((packetIdentifierL >>> 8) & 0xFF)).put((byte) ((packetIdentifierL >>> 0) & 0xFF));
			packetIndentifier.flip();
			runningLength += 2;
			
			buffers.add(packetIndentifier);
			buffers.add(ByteBuffer.wrap(bPayload));
		} else {
			buffers.add(ByteBuffer.wrap(bPayload));

		}
				
		String s1 = String.format("%8s", Integer.toBinaryString(bType[0] & 0xFF)).replace(' ', '0');
		log("first byte: " + s1 + " length: " + runningLength);

		buffers.set(1, calculateLenght(runningLength));
		log("buffers.size: " + buffers.size());
		//{type, length, topic, [packet-identifier], payload}
		ByteBuffer[] ret = new ByteBuffer[buffers.size()];
		return buffers.toArray(ret);
		}
}