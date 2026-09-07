package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLength;
import static org.mqttkat.MqttUtil.fit;
import static org.mqttkat.MqttUtil.twoBytesToLong;

import java.io.IOException;
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

public class MqttSubAck extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte[] data) throws IOException {
		return decode(key, data, 4);
	}

	public static IPersistentMap decode(SelectionKey key, byte[] data, int protocolVersion)
			throws IOException {
		int offset = 0;

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("SUBACK"));
		m.put(CLIENT_KEY, key);
		m.put(PACKET_IDENTIFIER, twoBytesToLong( data[offset++], data[offset++]));

		if (protocolVersion >= MqttConnect.PROTOCOL_VERSION_5) {
			// §3.9.2.1, before the payload of reason codes.
			m.put(PROPERTIES, MqttProperties.decode(data, offset));
			offset += MqttProperties.blockLength(data, offset);
		}

	    IPersistentVector vector = PersistentVector.create();

	    while(offset < data.length) {
	    	// §3.9.3: granted QoS 0/1/2 are 0x00/0x01/0x02, so a success reads
	    	// the same as 3.1.1 did. It is the failures that gained a
	    	// vocabulary, and they are all at or above 0x80.
	    	vector = vector.cons(data[offset++] & 0xFF);
	    }
	    m.put(SUBACK_RESPONSE, vector);
		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer encode(Map<Keyword, ?> message) {
		int length = 0;
		// MESSAGE_LENGTH is the starting size; fit() grows past it.
		byte[] bytes = new byte[MESSAGE_LENGTH];
		byte firstByte = (byte) ((MESSAGE_SUBACK << 4) & 0xf2);

		
		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		bytes[length++] = (byte) ((packetIdentifierL >>> 8) & 0xFF);
		bytes[length++] = (byte) (packetIdentifierL & 0xFF);

		if (message.containsKey(PROTOCOL_VERSION)
				&& ((Number) message.get(PROTOCOL_VERSION)).intValue() >= MqttConnect.PROTOCOL_VERSION_5) {
			@SuppressWarnings("unchecked")
			byte[] properties = MqttProperties.encode((Map<Keyword, ?>) message.get(PROPERTIES));
			bytes = fit(bytes, length, properties.length);
			for (int i = 0; i < properties.length; i++) {
				bytes[length++] = properties[i];
			}
		}

		PersistentVector vector = (PersistentVector) message.get(SUBACK_RESPONSE);
		//System.out.println("vector size: " + vector.size());
	
		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			//Byte answer = Byte.parseByte(((Long) it.next()).toString());
			bytes = fit(bytes, length, 1);
			bytes[length++] = ((Long) it.next()).byteValue();

		}

		byte[] remaining = calculateLength(length);
		ByteBuffer buffer = ByteBuffer.allocate(1 + remaining.length + length);
		buffer.put(firstByte);
		buffer.put(remaining);
		buffer.put(bytes, 0, length);
		//log("buffers.size: " + buffers.size());
		buffer.flip();
		//log("length: " + length);
		return buffer;
	}
}
