package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLenght;
import static org.mqttkat.MqttUtil.log;
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

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data) throws IOException {
		//System.out.println("SUBACK message..." + data.length);
		int offset = 0;

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("SUBACK"));
		m.put(CLIENT_KEY, key);
		m.put(PACKET_IDENTIFIER, twoBytesToLong( data[offset++], data[offset++]));
		
	    IPersistentVector v = PersistentVector.create();

	    while(offset < data.length) {
	    	v = v.cons(data[offset++] & 0xFF);
	    }
    	//System.out.println(v.toString());

	    m.put(SUBACK_RESPONSE, v);

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map<Keyword, ?> message) {
		int length = 0;
		byte[] bytes = new byte[MESSAGE_LENGTH];
		
		ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_LENGTH);
		buffer.put((byte) ((MESSAGE_SUBACK << 4) & 0xf2));

		
		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		bytes[length++] = (byte) ((packetIdentifierL >>> 8) & 0xFF);
		bytes[length++] = (byte) ((packetIdentifierL >>> 0) & 0xFF);

		PersistentVector vector = (PersistentVector) message.get(SUBACK_RESPONSE);
		//System.out.println("vector size: " + vector.size());
	
		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			//Byte answer = Byte.parseByte(((Long) it.next()).toString());
			bytes[length++] = ((Long) it.next()).byteValue();

		}

		buffer.put(calculateLenght(length));
		buffer.put(bytes, 0, length);
		//log("buffers.size: " + buffers.size());
		buffer.flip();
		//log("length: " + length);
		return new ByteBuffer[]{buffer};
	}
}
