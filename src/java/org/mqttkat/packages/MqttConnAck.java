package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLength;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

import org.mqttkat.MqttProtocolError;

public class MqttConnAck extends GenericMessage{

	/**
	 * A 3.1.1 CONNACK is exactly two bytes — acknowledge flags and return code
	 * — so anything longer is MQTT 5 and the rest is its property block. That
	 * is what lets the client decode one without being told which version it
	 * asked for.
	 */
	private static final int V311_LENGTH = 2;

	public static IPersistentMap decode(SelectionKey key, byte[] data) throws MqttProtocolError {
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("CONNACK"));
		m.put(CLIENT_KEY, key);
		m.put(SESSION_PRESENT, (data[0] & 0x01) == 1);
		// Kept under its 3.1.1 name as well, so existing handlers are unchanged
		// by a version 5 answer arriving.
		m.put(CONNECT_RETURN_CODE, data[1]);

		if (data.length > V311_LENGTH) {
			m.put(REASON_CODE, (long) data[1]);
			m.put(PROPERTIES, MqttProperties.decode(data, V311_LENGTH));
		}

		return PersistentArrayMap.create(m);
	}

	@SuppressWarnings("unchecked")
	public static ByteBuffer encode(Map<Keyword, ?> message) {
		long version = message.containsKey(PROTOCOL_VERSION)
				? ((Number) message.get(PROTOCOL_VERSION)).longValue()
				: 4;
		byte flags = (byte) (Boolean.TRUE.equals(message.get(SESSION_PRESENT)) ? 1 : 0);

		if (version >= MqttConnect.PROTOCOL_VERSION_5) {
			// §3.2: acknowledge flags, reason code, properties. The reason code
			// is the shared §2.4 vocabulary, so "unsupported protocol version"
			// is 0x84 here where 3.1.1 said 0x01.
			byte code = ((Number) message.get(REASON_CODE)).byteValue();
			byte[] properties = MqttProperties.encode((Map<Keyword, ?>) message.get(PROPERTIES));
			byte[] remaining = calculateLength(2 + properties.length);
			ByteBuffer buffer = ByteBuffer.allocate(1 + remaining.length + 2 + properties.length);
			buffer.put((byte) (MESSAGE_CONNACK << 4));
			buffer.put(remaining);
			buffer.put(flags);
			buffer.put(code);
			buffer.put(properties);
			buffer.flip();
			return buffer;
		}

		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.put((byte)(MESSAGE_CONNACK << 4));
		buffer.put((byte)2);
		buffer.put(flags);
		buffer.put(Byte.parseByte(message.get(CONNECT_RETURN_CODE).toString()));
		buffer.flip();

		return buffer;
	}
}
