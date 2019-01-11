package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLenght;
import static org.mqttkat.MqttUtil.decodeUTF8;
import static org.mqttkat.MqttUtil.encodeUTF8Buffer;
import static org.mqttkat.MqttUtil.encodeUTF8Bytes;
import static org.mqttkat.MqttUtil.log;
import static org.mqttkat.MqttUtil.qos;
import static org.mqttkat.MqttUtil.twoBytesToInt;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;

public class MqttSubscribe extends GenericMessage{

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data, int msgLength) throws IOException {
		//System.out.println("SUBSCRIBE message...");

		int offset = 0;
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();

		m.put(PACKET_TYPE, intern("SUBSCRIBE"));
		m.put(PACKET_IDENTIFIER, twoBytesToInt( data[offset++], data[offset++]));

	    IPersistentVector v = PersistentVector.create();

		while(offset < msgLength) {
		    Map<Keyword, Object> topicMap = new TreeMap<Keyword, Object>();
			String topic = decodeUTF8(data, offset);
			//System.out.println("topic: " + topic);
			topicMap.put(TOPIC_FILTER, topic);
			offset += topic.length() + 2;
			//System.out.println(offset);
			topicMap.put(QOS, data[offset++]);
			//System.out.println(offset + " " +  msgLength + " " + v.toString() + " " + topicMap.toString());

			v = v.cons(PersistentArrayMap.create(topicMap));
			//System.out.println(v.toString());

		}
		//System.out.println("uit de loop: " +  offset + " " + msgLength + " " + v.toString());
	    //IPersistentVector v = PersistentVector.create(1, 2, 3);

		
		//PersistentArrayMap  map = PersistentArrayMap.create(arg0)

		m.put(TOPICS, v);
		m.put(CLIENT_KEY, key);
		//m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, topic.length() + 2, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}
	
	public static ByteBuffer[] encode(Map<Keyword, ?> message) throws UnsupportedEncodingException  {
		//log("encode SUBSCRIBE");
		int length = 0;

		byte[] bytes = new byte[MESSAGE_LENGTH];
		ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_LENGTH);
		byte[] bType = {(byte) (MESSAGE_SUBSCRIBE << 4)};
		buffer.put((byte) (bType[0] & 0xf2));
		
		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		bytes[length++] = (byte) ((packetIdentifierL >>> 8) & 0xFF);
		bytes[length++] = (byte) ((packetIdentifierL >>> 0) & 0xFF);
	
		PersistentVector vector = (PersistentVector) message.get(TOPICS);
		//System.out.println("vector size: " + vector.size());

		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			@SuppressWarnings("unchecked")
			Map<Keyword, ?> topicMap = (Map<Keyword, ?>) it.next();
			
			byte[] topic = ((String) topicMap.get(TOPIC_FILTER)).getBytes("UTF-8");
			bytes[length++] = (byte) ((topic.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (topic.length & 0xFF);
			for(int i = 0; i < topic.length; i++) {
				bytes[length++] = topic[i];
			}
			
			bytes[length++] = Byte.parseByte(((Long) topicMap.get(QOS)).toString());
		}
		//log("limit: " +  payload.limit());

		buffer.put(calculateLenght(length));
		buffer.put(bytes, 0, length);
		//log("buffers.size: " + buffers.size());
		buffer.flip();
		log("length: " + length);
		return new ByteBuffer[]{buffer};		
	}

}
