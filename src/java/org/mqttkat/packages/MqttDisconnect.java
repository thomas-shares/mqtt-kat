package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.calculateLength;

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
 * DISCONNECT.
 *
 * In 3.1.1 this is two bytes and travels one way — a client saying goodbye.
 * MQTT 5 (§3.14) gives it a reason code and properties, and lets the *server*
 * send one, which is the only way a broker can tell a client why it is about
 * to be hung up on rather than just hanging up on it.
 *
 * The body has three legal shapes, and all three appear in practice:
 *
 * <ul>
 * <li>empty — Normal Disconnection with no properties, which is what every
 *     3.1.1 client sends and what a version 5 client may still send</li>
 * <li>one byte — a reason code, the property length omitted (§3.14.2.2.1)</li>
 * <li>more — a reason code followed by a property block</li>
 * </ul>
 */
public class MqttDisconnect extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key) throws IOException {
		return decode(key, new byte[0], 4);
	}

	/**
	 * The DISCONNECT the broker raises for itself when a connection has gone.
	 *
	 * Marked as not coming from the client, because that is what decides the
	 * will: a client that said goodbye keeps its will unpublished, a socket
	 * that died does not (§3.1.2.5). Without the mark the handler sees the same
	 * packet for both and cannot tell a polite goodbye from a crash.
	 */
	public static IPersistentMap broadcastEnded(SelectionKey key) {
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("DISCONNECT"));
		m.put(CLIENT_KEY, key);
		m.put(FROM_CLIENT, Boolean.FALSE);
		return PersistentArrayMap.create(m);
	}

	public static IPersistentMap decode(SelectionKey key, byte[] body, int protocolVersion)
			throws IOException {
		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("DISCONNECT"));
		m.put(CLIENT_KEY, key);
		m.put(FROM_CLIENT, Boolean.TRUE);

		if (protocolVersion >= MqttConnect.PROTOCOL_VERSION_5) {
			// An absent reason code means Normal Disconnection (§3.14.2.1), so
			// the shortest form is not a special case to the reader — it is the
			// default spelled with no bytes.
			byte reason = body.length > 0 ? body[0] : MqttReasonCode.NORMAL_DISCONNECTION;
			m.put(REASON_CODE, (long) reason);
			m.put(PROPERTIES, body.length > 1
					? MqttProperties.decode(body, 1)
					: PersistentArrayMap.EMPTY);
		}

		return PersistentArrayMap.create(m);
	}

	/** The two-byte normal disconnection, for callers with nothing to add. */
	public static ByteBuffer encode() {
		ByteBuffer buffer = ByteBuffer.allocate(2);
		buffer.put((byte)(MESSAGE_DISCONNECT << 4));
		buffer.put((byte)0);
		buffer.flip();

		return buffer;
	}

	@SuppressWarnings("unchecked")
	public static ByteBuffer encode(Map<Keyword, ?> message) {
		boolean v5 = message.containsKey(PROTOCOL_VERSION)
				&& ((Number) message.get(PROTOCOL_VERSION)).intValue() >= MqttConnect.PROTOCOL_VERSION_5;
		if (!v5) {
			return encode();
		}

		byte reason = message.containsKey(REASON_CODE)
				? ((Number) message.get(REASON_CODE)).byteValue()
				: MqttReasonCode.NORMAL_DISCONNECTION;
		Map<Keyword, ?> properties = (Map<Keyword, ?>) message.get(PROPERTIES);
		boolean hasProperties = properties != null && !properties.isEmpty();

		// Written in the shortest form that says the same thing. A version 5
		// client disconnecting normally with nothing to add sends the same two
		// bytes a 3.1.1 one does, which is what §3.14.2 intends.
		if (reason == MqttReasonCode.NORMAL_DISCONNECTION && !hasProperties) {
			return encode();
		}

		byte[] body;
		if (!hasProperties) {
			body = new byte[] { reason };
		} else {
			byte[] encoded = MqttProperties.encode(properties);
			body = new byte[1 + encoded.length];
			body[0] = reason;
			System.arraycopy(encoded, 0, body, 1, encoded.length);
		}

		byte[] remaining = calculateLength(body.length);
		ByteBuffer buffer = ByteBuffer.allocate(1 + remaining.length + body.length);
		buffer.put((byte) (MESSAGE_DISCONNECT << 4));
		buffer.put(remaining);
		buffer.put(body);
		buffer.flip();
		return buffer;
	}
}
