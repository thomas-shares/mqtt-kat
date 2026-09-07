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
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;

import org.mqttkat.MqttProtocolError;

public class MqttSubscribe extends GenericMessage{

	/**
	 * The subscription options byte (§3.8.3.1): QoS in bits 0-1, No Local in
	 * bit 2, Retain As Published in bit 3, Retain Handling in bits 4-5, and
	 * bits 6-7 reserved.
	 *
	 * A 3.1.1 subscription writes only the QoS, which is the same as a version
	 * 5 one with every flag at its default — so the two encodings agree on the
	 * bytes for a client that asks for nothing extra.
	 */
	private static byte subscriptionOptions(Map<Keyword, ?> topicMap, boolean v5) {
		int options = ((Number) topicMap.get(QOS)).intValue() & 0x03;
		if (v5) {
			if (Boolean.TRUE.equals(topicMap.get(NO_LOCAL))) {
				options |= 0x04;
			}
			if (Boolean.TRUE.equals(topicMap.get(RETAIN_AS_PUBLISHED))) {
				options |= 0x08;
			}
			Object handling = topicMap.get(RETAIN_HANDLING);
			if (handling != null) {
				options |= (((Number) handling).intValue() & 0x03) << 4;
			}
		}
		return (byte) options;
	}

	public static IPersistentMap decode(SelectionKey key, byte[] data) throws IOException {
		return decode(key, data, 4);
	}

	public static IPersistentMap decode(SelectionKey key, byte[] data, int protocolVersion)
			throws IOException {
		int offset = 0;
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();

		m.put(PACKET_TYPE, intern("SUBSCRIBE"));
		m.put(PACKET_IDENTIFIER, twoBytesToLong( data[offset++], data[offset++]));

		boolean v5 = protocolVersion >= MqttConnect.PROTOCOL_VERSION_5;
		if (v5) {
			// §3.8.2.1, between the packet identifier and the first filter.
			m.put(PROPERTIES, MqttProperties.decode(data, offset));
			offset += MqttProperties.blockLength(data, offset);
		}

	    IPersistentVector vector = PersistentVector.create();

		while(offset < data.length) {
		    Map<Keyword, Object> topicMap = new TreeMap<Keyword, Object>();
			String topic = decodeUTF8(data, offset);
			topicMap.put(TOPIC_FILTER, topic);
			offset += encodedUTF8Length(data, offset);

			byte options = data[offset++];
			topicMap.put(QOS, (byte) (options & 0x03));

			if (v5) {
				// §3.8.3.1. The same byte 3.1.1 used for QoS alone, with the
				// bits above it given meanings — which is why the reserved
				// ones have to be checked rather than masked away: a client
				// setting them has asked for something, and silently doing
				// something else is worse than refusing.
				if ((options & 0xC0) != 0) {
					throw MqttProtocolError.malformed(
							"subscription options reserved bits are set");
				}
				int retainHandling = (options & 0x30) >> 4;
				if (retainHandling == 3) {
					throw MqttProtocolError.malformed(
							"retain handling 3 is not a defined value");
				}
				topicMap.put(NO_LOCAL, (options & 0x04) != 0);
				topicMap.put(RETAIN_AS_PUBLISHED, (options & 0x08) != 0);
				topicMap.put(RETAIN_HANDLING, (long) retainHandling);
			}

			vector = vector.cons(PersistentArrayMap.create(topicMap));
		}
		//System.out.println("uit de loop: " +  offset + " " + data.length + " " + vector.toString());
	    //IPersistentVector vector = PersistentVector.create(1, 2, 3);

		
		//PersistentArrayMap  map = PersistentArrayMap.create(arg0)

		m.put(TOPICS, vector);
		m.put(CLIENT_KEY, key);
		//m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, topic.length() + 2, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}
	
	public static ByteBuffer encode(Map<Keyword, ?> message) throws UnsupportedEncodingException  {
		//log("encode SUBSCRIBE");
		int length = 0;

		// MESSAGE_LENGTH is the starting size; fit() grows past it.
		byte[] bytes = new byte[MESSAGE_LENGTH];
		byte[] bType = {(byte) (MESSAGE_SUBSCRIBE << 4) | 0x02};
		
		//String q1 = String.format("%8s", Integer.toBinaryString(bType[0] & 0xf2)).replace(' ', '0');
		//System.out.println("packet id 2: " + q1);
		byte firstByte = (byte) (bType[0] & 0xf2);

		Long packetIdentifier = (Long) message.get(PACKET_IDENTIFIER);
		bytes[length++] = (byte) ((packetIdentifier >> 8) & 0xFF);
		bytes[length++] = (byte) ((packetIdentifier >> 0) & 0xFF);

		boolean v5 = message.containsKey(PROTOCOL_VERSION)
				&& ((Number) message.get(PROTOCOL_VERSION)).intValue() >= MqttConnect.PROTOCOL_VERSION_5;
		if (v5) {
			@SuppressWarnings("unchecked")
			byte[] properties = MqttProperties.encode((Map<Keyword, ?>) message.get(PROPERTIES));
			bytes = fit(bytes, length, properties.length);
			for (int i = 0; i < properties.length; i++) {
				bytes[length++] = properties[i];
			}
		}
	
		//String s1 = String.format("%8s", Integer.toBinaryString(bytes[0])).replace(' ', '0');
		//System.out.println("packet id 1: " + s1);
	
		
		//String s2 = String.format("%8s", Integer.toBinaryString(bytes[1])).replace(' ', '0');
		//System.out.println("packet id 2: " + s2);

		PersistentVector vector = (PersistentVector) message.get(TOPICS);
		//System.out.println("vector size: " + vector.size());

		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			@SuppressWarnings("unchecked")
			Map<Keyword, ?> topicMap = (Map<Keyword, ?>) it.next();
			byte[] topic = ((String) topicMap.get(TOPIC_FILTER)).getBytes("UTF-8");
			bytes = fit(bytes, length, 3 + topic.length);
			bytes[length++] = (byte) ((topic.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (topic.length & 0xFF);
			for(int i = 0; i < topic.length; i++) {
				bytes[length++] = topic[i];
			}
			
			bytes[length++] = subscriptionOptions(topicMap, v5);
		}

		//for(int i =0; i < length ; i++) {
		//	System.out.print(bytes[i] + " ");
		//}
		//System.out.print("\n");

		byte[] remaining = calculateLength(length);
		ByteBuffer buffer = ByteBuffer.allocate(1 + remaining.length + length);
		buffer.put(firstByte);
		buffer.put(remaining);
		buffer.put(bytes, 0, length);
		buffer.flip();
		//log("length: " + length);
		return buffer;		
	}
}
