package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLenght;
import static org.mqttkat.MqttUtil.decodeUTF8;
import static org.mqttkat.MqttUtil.encodeUTF8Bytes;
import static org.mqttkat.MqttUtil.log;
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
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;

public class MqttUnsubscribe extends GenericMessage{

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data, int msgLength) throws IOException {
		//System.out.println("UNSUBSCRIBE message...");

		int offset = 0;
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();

		m.put(PACKET_TYPE, intern("UNSUBSCRIBE"));

		m.put(PACKET_IDENTIFIER, twoBytesToInt( data[offset++], data[offset++]));
		
	    IPersistentVector v = PersistentVector.create();

		while(offset < msgLength) {
		   // Map<Object, Object> topicMap = new TreeMap<Object, Object>();
			String topic = decodeUTF8(data, offset);
			//System.out.println("topic: " + topic);
			//topicMap.put(TOPIC, topic);
			offset += topic.length() + 2;
			//System.out.println(offset);
			//topicMap.put(MSG_QOS, qos(data[offset++]));
			//System.out.println(offset + " " +  msgLength + " " + v.toString() );

			v = v.cons(topic);
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
	
	public static ByteBuffer[] encode(Map<Keyword, ?> message) throws UnsupportedEncodingException {
		int lengthCounter = 0;

		byte[] bType = {(byte) (MESSAGE_UNSUBSCRIBE << 4)};
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
		ByteBuffer payload = ByteBuffer.allocate(1024);

		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			String topic = (String) it.next();
			payload.put(encodeUTF8Bytes(topic));
			lengthCounter += topic.length() + 2;
		}
		
		payload.flip();
		buffers.add(payload);
		buffers.set(1, calculateLenght(lengthCounter));
		//log("buffers.size: " + buffers.size());
		log("length: " + lengthCounter);
		ByteBuffer[] ret = new ByteBuffer[buffers.size()];
		return buffers.toArray(ret);
	}
}