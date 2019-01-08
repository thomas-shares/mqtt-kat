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
		    Map<Object, Object> topicMap = new TreeMap<Object, Object>();
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
		int lengthCounter = 0;

		byte[] bType = {(byte) (MESSAGE_SUBSCRIBE << 4)};
		bType[0] =  (byte) (bType[0] & 0xf2);
		
		byte[] lengthByte = {0};
		
		List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(2);
		buffers.add(0, ByteBuffer.wrap(bType));
		buffers.add(1, ByteBuffer.wrap(lengthByte));
		
		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		String k1 = String.format("%8s", Integer.toBinaryString((byte) ((packetIdentifierL >>> 8) & 0xFF)  & 0xFF)).replace(' ', '0');
		String k2 = String.format("%8s", Integer.toBinaryString((byte) (packetIdentifierL & 0xFF)  & 0xFF)).replace(' ', '0');
		ByteBuffer packetIndentifier = ByteBuffer.allocate(2);

		//System.out.println("hoog: " +  k1 );
		//System.out.println("laag: " + k2);
		packetIndentifier.put((byte) ((packetIdentifierL >>> 8) & 0xFF)).put((byte) ((packetIdentifierL >>> 0) & 0xFF));
		packetIndentifier.flip();
		buffers.add(packetIndentifier);
		lengthCounter += 2;

		PersistentVector vector = (PersistentVector) message.get(TOPICS);
		//System.out.println("vector size: " + vector.size());
		ByteBuffer payload = ByteBuffer.allocate(1024);

		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			@SuppressWarnings("unchecked")
			Map<Keyword, ?> topicMap = (Map<Keyword, ?>) it.next();
			String topic = (String) topicMap.get(TOPIC_FILTER);
			Byte qos = Byte.parseByte(((Long) topicMap.get(QOS)).toString());
			//encodeUTF8Bytes(topic);
			payload.put(encodeUTF8Bytes(topic));
			payload.put(qos);
			lengthCounter += topic.length() + 3;

		}
		//log("limit: " +  payload.limit());

		payload.flip();
		buffers.add(payload);
		buffers.set(1, calculateLenght(lengthCounter));
		//log("buffers.size: " + buffers.size());
		log("length: " + lengthCounter);
		ByteBuffer[] ret = new ByteBuffer[buffers.size()];
		return buffers.toArray(ret);		
	}

}
