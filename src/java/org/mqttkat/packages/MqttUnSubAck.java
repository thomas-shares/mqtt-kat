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

/**
 * UNSUBACK.
 *
 * The version 5 packet (§3.11) differs from the 3.1.1 one by more than the
 * usual property block: 3.1.1's UNSUBACK is the packet identifier and nothing
 * else — no payload at all — while version 5 adds a reason code for every topic
 * filter the UNSUBSCRIBE listed. That is what finally lets a client tell an
 * unsubscribe that removed something from one that was a no-op; in 3.1.1 the
 * two answers are the same four bytes.
 */
public class MqttUnSubAck extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte[] data) throws IOException {
		return decode(key, data, 4);
	}

	public static IPersistentMap decode(SelectionKey key, byte[] data, int protocolVersion)
			throws IOException {
		int offset = 0;
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("UNSUBACK"));
		m.put(CLIENT_KEY, key);
		m.put(PACKET_IDENTIFIER, twoBytesToLong( data[offset++], data[offset++]));

		if (protocolVersion >= MqttConnect.PROTOCOL_VERSION_5) {
			m.put(PROPERTIES, MqttProperties.decode(data, offset));
			offset += MqttProperties.blockLength(data, offset);

			IPersistentVector vector = PersistentVector.create();
			while (offset < data.length) {
				vector = vector.cons(data[offset++] & 0xFF);
			}
			m.put(SUBACK_RESPONSE, vector);
		}

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer encode(Map<Keyword, ?> message) {
		if (message.containsKey(PROTOCOL_VERSION)
				&& ((Number) message.get(PROTOCOL_VERSION)).intValue() >= MqttConnect.PROTOCOL_VERSION_5) {
			return encodeV5(message);
		}
		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.put((byte) (MESSAGE_UNSUBACK << 4));
		buffer.put((byte) 0x02);
		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		buffer.put((byte) ((packetIdentifierL >>> 8) & 0xFF)).put((byte) (packetIdentifierL & 0xFF));
		buffer.flip();
		return buffer;
	}

	@SuppressWarnings("unchecked")
	private static ByteBuffer encodeV5(Map<Keyword, ?> message) {
		int length = 0;
		byte[] bytes = new byte[MESSAGE_LENGTH];

		Long packetIdentifierL = (Long) message.get(PACKET_IDENTIFIER);
		bytes[length++] = (byte) ((packetIdentifierL >>> 8) & 0xFF);
		bytes[length++] = (byte) (packetIdentifierL & 0xFF);

		byte[] properties = MqttProperties.encode((Map<Keyword, ?>) message.get(PROPERTIES));
		bytes = fit(bytes, length, properties.length);
		for (int i = 0; i < properties.length; i++) {
			bytes[length++] = properties[i];
		}

		Object response = message.get(SUBACK_RESPONSE);
		if (response != null) {
			Iterator<?> it = ((Iterable<?>) response).iterator();
			while (it.hasNext()) {
				bytes = fit(bytes, length, 1);
				bytes[length++] = ((Number) it.next()).byteValue();
			}
		}

		byte[] remaining = calculateLength(length);
		ByteBuffer buffer = ByteBuffer.allocate(1 + remaining.length + length);
		buffer.put((byte) (MESSAGE_UNSUBACK << 4));
		buffer.put(remaining);
		buffer.put(bytes, 0, length);
		buffer.flip();
		return buffer;
	}
}
