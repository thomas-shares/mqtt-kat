package org.mqttkat.packages;

import static org.mqttkat.MqttUtil.calculateLength;
import static org.mqttkat.MqttUtil.twoBytesToLong;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

import org.mqttkat.MqttReasonCode;

/**
 * The four QoS acknowledgements — PUBACK, PUBREC, PUBREL, PUBCOMP.
 *
 * They are one packet with four type nibbles: a packet identifier, and in MQTT
 * 5 a reason code and properties (§3.4 to §3.7). Version 5 changes all four
 * identically, so the work is here once rather than copied into each of them.
 *
 * Three body shapes, as with DISCONNECT:
 *
 * <ul>
 * <li>two bytes — the identifier alone, meaning Success with no properties.
 *     This is byte for byte what 3.1.1 sends, and what the broker writes for
 *     the overwhelming majority of acknowledgements.</li>
 * <li>three bytes — a reason code, the property length omitted</li>
 * <li>more — a reason code followed by a property block</li>
 * </ul>
 */
public final class MqttAck {

	private MqttAck() {
	}

	public static IPersistentMap decode(SelectionKey key, byte[] data, int protocolVersion,
			Keyword packetType) throws IOException {
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(GenericMessage.PACKET_TYPE, packetType);
		m.put(GenericMessage.CLIENT_KEY, key);
		m.put(GenericMessage.PACKET_IDENTIFIER, twoBytesToLong(data[0], data[1]));

		if (protocolVersion >= MqttConnect.PROTOCOL_VERSION_5) {
			// An identifier with nothing after it is Success (§3.4.2.1), so the
			// short form needs no special case at the reading end either.
			byte reason = data.length > 2 ? data[2] : MqttReasonCode.SUCCESS;
			m.put(GenericMessage.REASON_CODE, (long) reason);
			m.put(GenericMessage.PROPERTIES, data.length > 3
					? MqttProperties.decode(data, 3)
					: PersistentArrayMap.EMPTY);
		}

		return PersistentArrayMap.create(m);
	}

	@SuppressWarnings("unchecked")
	public static ByteBuffer encode(Map<Keyword, ?> message, int messageType, int flags) {
		long identifier = ((Number) message.get(GenericMessage.PACKET_IDENTIFIER)).longValue();
		boolean v5 = message.containsKey(GenericMessage.PROTOCOL_VERSION)
				&& ((Number) message.get(GenericMessage.PROTOCOL_VERSION)).intValue()
						>= MqttConnect.PROTOCOL_VERSION_5;

		byte reason = MqttReasonCode.SUCCESS;
		Map<Keyword, ?> properties = null;
		if (v5) {
			if (message.containsKey(GenericMessage.REASON_CODE)) {
				reason = ((Number) message.get(GenericMessage.REASON_CODE)).byteValue();
			}
			properties = (Map<Keyword, ?>) message.get(GenericMessage.PROPERTIES);
		}
		boolean hasProperties = properties != null && !properties.isEmpty();

		byte[] body;
		if (!v5 || (reason == MqttReasonCode.SUCCESS && !hasProperties)) {
			body = new byte[2];
		} else if (!hasProperties) {
			body = new byte[3];
			body[2] = reason;
		} else {
			byte[] encoded = MqttProperties.encode(properties);
			body = new byte[3 + encoded.length];
			body[2] = reason;
			System.arraycopy(encoded, 0, body, 3, encoded.length);
		}
		body[0] = (byte) ((identifier >>> 8) & 0xFF);
		body[1] = (byte) (identifier & 0xFF);

		byte[] remaining = calculateLength(body.length);
		ByteBuffer buffer = ByteBuffer.allocate(1 + remaining.length + body.length);
		buffer.put((byte) ((messageType << 4) | flags));
		buffer.put(remaining);
		buffer.put(body);
		buffer.flip();
		return buffer;
	}
}
