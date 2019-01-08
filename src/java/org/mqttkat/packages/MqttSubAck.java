package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLenght;
import static org.mqttkat.MqttUtil.log;
import static org.mqttkat.MqttUtil.twoBytesToInt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.mqttkat.MqttUtil;

import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;

public class MqttSubAck extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] data) throws IOException {
		//System.out.println("SUBACK message...");
		int offset = 0;

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("SUBACK"));
		m.put(CLIENT_KEY, key);
		m.put(PACKET_IDENTIFIER, twoBytesToInt( data[offset++], data[offset++]));
		
	    IPersistentVector v = PersistentVector.create();

	    while(offset < data.length) {
	    	v = v.cons(data[offset++] & 0xFF);
	    }
    	//System.out.println(v.toString());

	    m.put(SUBACK_RESPONSE, v);

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map<Keyword, ?> message) {
		int lengthCounter = 0;

		byte[] bType = {(byte) (MESSAGE_SUBACK << 4)};
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

		PersistentVector vector = (PersistentVector) message.get(SUBACK_RESPONSE);
		System.out.println("vector size: " + vector.size());
		ByteBuffer payload = ByteBuffer.allocate(1024);

		Iterator<?> it =  vector.iterator();
		while(it.hasNext()) {
			//Byte answer = Byte.parseByte(((Long) it.next()).toString());
			byte answer = ((Long) it.next()).byteValue();
			payload.put(answer);
			lengthCounter++;
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
