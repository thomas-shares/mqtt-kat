package org.mqttkat.packages;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import clojure.lang.RT;

import org.mqttkat.MqttProtocolError;
import org.mqttkat.MqttReasonCode;
import org.mqttkat.MqttUtil;

/**
 * MQTT 5.0 properties (§2.2.2), and the variable byte integer that frames them.
 *
 * Every MQTT 5 packet carries a property block: a variable byte integer length
 * followed by that many bytes of identifier-and-value pairs. This is the one
 * piece of the version that every packet type needs, which is why it is here on
 * its own rather than repeated in each of them.
 *
 * On the Clojure side a block is an ordinary keyword map, so a handler reads
 * `(:session-expiry-interval msg)` and never sees an identifier byte. Two
 * properties may legitimately repeat — User Property and Subscription
 * Identifier — and those are vectors, always, so a caller never has to ask
 * whether it got one or several.
 */
public final class MqttProperties {

	private MqttProperties() {
	}

	/** How a property's value is laid out on the wire (§2.2.2.2). */
	private enum Type {
		/** One byte, a number: payload format indicator, maximum QoS. */
		BYTE,
		/**
		 * One byte holding 0 or 1, surfaced as a Clojure boolean.
		 *
		 * Not left as the raw number on purpose. In Clojure 0 is truthy, so a
		 * server answering `retain-available 0` would read as "retain is
		 * available" at every call site that asks the obvious question.
		 */
		BOOLEAN,
		TWO_BYTE,
		FOUR_BYTE,
		/** Only the Subscription Identifier (§3.3.2.3.8). */
		VARIABLE_BYTE,
		UTF8,
		BINARY,
		/** A pair of UTF-8 strings — only the User Property. */
		PAIR
	}

	private record Prop(int id, Keyword key, Type type, boolean repeatable) {
	}

	private static final List<Prop> TABLE = List.of(
			new Prop(0x01, kw("payload-format-indicator"), Type.BYTE, false),
			new Prop(0x02, kw("message-expiry-interval"), Type.FOUR_BYTE, false),
			new Prop(0x03, kw("content-type"), Type.UTF8, false),
			new Prop(0x08, kw("response-topic"), Type.UTF8, false),
			new Prop(0x09, kw("correlation-data"), Type.BINARY, false),
			new Prop(0x0B, kw("subscription-identifiers"), Type.VARIABLE_BYTE, true),
			new Prop(0x11, kw("session-expiry-interval"), Type.FOUR_BYTE, false),
			new Prop(0x12, kw("assigned-client-identifier"), Type.UTF8, false),
			new Prop(0x13, kw("server-keep-alive"), Type.TWO_BYTE, false),
			new Prop(0x15, kw("authentication-method"), Type.UTF8, false),
			new Prop(0x16, kw("authentication-data"), Type.BINARY, false),
			new Prop(0x17, kw("request-problem-information"), Type.BOOLEAN, false),
			new Prop(0x18, kw("will-delay-interval"), Type.FOUR_BYTE, false),
			new Prop(0x19, kw("request-response-information"), Type.BOOLEAN, false),
			new Prop(0x1A, kw("response-information"), Type.UTF8, false),
			new Prop(0x1C, kw("server-reference"), Type.UTF8, false),
			new Prop(0x1F, kw("reason-string"), Type.UTF8, false),
			new Prop(0x21, kw("receive-maximum"), Type.TWO_BYTE, false),
			new Prop(0x22, kw("topic-alias-maximum"), Type.TWO_BYTE, false),
			new Prop(0x23, kw("topic-alias"), Type.TWO_BYTE, false),
			new Prop(0x24, kw("maximum-qos"), Type.BYTE, false),
			new Prop(0x25, kw("retain-available"), Type.BOOLEAN, false),
			new Prop(0x26, kw("user-properties"), Type.PAIR, true),
			new Prop(0x27, kw("maximum-packet-size"), Type.FOUR_BYTE, false),
			new Prop(0x28, kw("wildcard-subscription-available"), Type.BOOLEAN, false),
			new Prop(0x29, kw("subscription-identifier-available"), Type.BOOLEAN, false),
			new Prop(0x2A, kw("shared-subscription-available"), Type.BOOLEAN, false));

	private static final Map<Integer, Prop> BY_ID = new HashMap<>();
	private static final Map<Keyword, Prop> BY_KEY = new HashMap<>();

	static {
		for (Prop p : TABLE) {
			BY_ID.put(p.id(), p);
			BY_KEY.put(p.key(), p);
		}
	}

	private static Keyword kw(String name) {
		return Keyword.intern(name);
	}

	// ── variable byte integer (§1.5.5) ───────────────────────────────────

	/**
	 * Four bytes is the maximum the encoding allows, and the check is not
	 * decoration: without it a run of continuation bits walks the decoder off
	 * the end of the buffer, and on a socket that is not one bad packet but a
	 * stream that never resynchronises.
	 */
	private static final int MAX_VBI_BYTES = 4;

	public static long decodeVariableByteInteger(byte[] in, int offset) throws MqttProtocolError {
		long value = 0;
		long multiplier = 1;
		for (int i = 0; i < MAX_VBI_BYTES; i++) {
			if (offset + i >= in.length) {
				throw MqttProtocolError.malformed("variable byte integer runs past the packet");
			}
			byte b = in[offset + i];
			value += (b & 0x7F) * multiplier;
			if ((b & 0x80) == 0) {
				return value;
			}
			multiplier *= 128;
		}
		throw MqttProtocolError.malformed("variable byte integer longer than four bytes");
	}

	/** How many bytes the integer at `offset` occupies, 1 to 4. */
	public static int variableByteIntegerLength(byte[] in, int offset) throws MqttProtocolError {
		for (int i = 0; i < MAX_VBI_BYTES; i++) {
			if (offset + i >= in.length) {
				throw MqttProtocolError.malformed("variable byte integer runs past the packet");
			}
			if ((in[offset + i] & 0x80) == 0) {
				return i + 1;
			}
		}
		throw MqttProtocolError.malformed("variable byte integer longer than four bytes");
	}

	/** Shares MqttUtil's encoder, which is the same encoding and already bounded. */
	public static byte[] encodeVariableByteInteger(long value) {
		return MqttUtil.calculateLength(value);
	}

	// ── the block ────────────────────────────────────────────────────────

	/** Total size of the property block at `offset`, its length prefix included. */
	public static int blockLength(byte[] in, int offset) throws MqttProtocolError {
		int prefix = variableByteIntegerLength(in, offset);
		return prefix + (int) decodeVariableByteInteger(in, offset);
	}

	public static IPersistentMap decode(byte[] in, int offset) throws MqttProtocolError {
		long declared = decodeVariableByteInteger(in, offset);
		int cursor = offset + variableByteIntegerLength(in, offset);
		long end = cursor + declared;
		if (end > in.length) {
			throw MqttProtocolError.malformed(
					"property block of " + declared + " bytes runs past the packet");
		}

		Map<Keyword, Object> out = new LinkedHashMap<>();
		List<Object> userProperties = new ArrayList<>();
		List<Object> subscriptionIds = new ArrayList<>();

		while (cursor < end) {
			int id = in[cursor++] & 0xFF;
			Prop prop = BY_ID.get(id);
			if (prop == null) {
				// Skipping it is not an option: without knowing the value's
				// encoding there is no way to find where the next one starts.
				throw MqttProtocolError.malformed(
						String.format("unknown property identifier 0x%02X", id));
			}
			if (!prop.repeatable() && out.containsKey(prop.key())) {
				throw MqttProtocolError.protocol(
						"property " + prop.key() + " appears more than once");
			}

			switch (prop.type()) {
			case BYTE: {
				need(cursor, 1, end, prop);
				out.put(prop.key(), (long) (in[cursor++] & 0xFF));
				break;
			}
			case BOOLEAN: {
				need(cursor, 1, end, prop);
				out.put(prop.key(), (in[cursor++] & 0xFF) != 0);
				break;
			}
			case TWO_BYTE: {
				need(cursor, 2, end, prop);
				out.put(prop.key(), ((long) (in[cursor] & 0xFF) << 8) | (in[cursor + 1] & 0xFF));
				cursor += 2;
				break;
			}
			case FOUR_BYTE: {
				need(cursor, 4, end, prop);
				long v = ((long) (in[cursor] & 0xFF) << 24)
						| ((long) (in[cursor + 1] & 0xFF) << 16)
						| ((long) (in[cursor + 2] & 0xFF) << 8)
						| (in[cursor + 3] & 0xFF);
				out.put(prop.key(), v);
				cursor += 4;
				break;
			}
			case VARIABLE_BYTE: {
				long v = decodeVariableByteInteger(in, cursor);
				int width = variableByteIntegerLength(in, cursor);
				need(cursor, width, end, prop);
				subscriptionIds.add(v);
				out.put(prop.key(), subscriptionIds);        // marks it as seen
				cursor += width;
				break;
			}
			case UTF8: {
				need(cursor, 2, end, prop);
				int len = ((in[cursor] & 0xFF) << 8) | (in[cursor + 1] & 0xFF);
				need(cursor, 2 + len, end, prop);
				out.put(prop.key(),
						new String(in, cursor + 2, len, StandardCharsets.UTF_8));
				cursor += 2 + len;
				break;
			}
			case BINARY: {
				need(cursor, 2, end, prop);
				int len = ((in[cursor] & 0xFF) << 8) | (in[cursor + 1] & 0xFF);
				need(cursor, 2 + len, end, prop);
				byte[] data = new byte[len];
				System.arraycopy(in, cursor + 2, data, 0, len);
				out.put(prop.key(), data);
				cursor += 2 + len;
				break;
			}
			case PAIR: {
				need(cursor, 2, end, prop);
				int klen = ((in[cursor] & 0xFF) << 8) | (in[cursor + 1] & 0xFF);
				need(cursor, 2 + klen + 2, end, prop);
				String pk = new String(in, cursor + 2, klen, StandardCharsets.UTF_8);
				int vpos = cursor + 2 + klen;
				int vlen = ((in[vpos] & 0xFF) << 8) | (in[vpos + 1] & 0xFF);
				need(cursor, 2 + klen + 2 + vlen, end, prop);
				String pv = new String(in, vpos + 2, vlen, StandardCharsets.UTF_8);
				userProperties.add(PersistentVector.create(pk, pv));
				out.put(prop.key(), userProperties);         // marks it as seen
				cursor = vpos + 2 + vlen;
				break;
			}
			}
		}

		// The repeatable ones are gathered in java Lists while decoding, then
		// handed over as Clojure vectors so a handler never meets a mutable one.
		if (!userProperties.isEmpty()) {
			out.put(kw("user-properties"), PersistentVector.create(userProperties));
		}
		if (!subscriptionIds.isEmpty()) {
			out.put(kw("subscription-identifiers"), PersistentVector.create(subscriptionIds));
		}
		return PersistentArrayMap.create(out);
	}

	private static void need(int cursor, int wanted, long end, Prop prop) throws MqttProtocolError {
		if (cursor + wanted > end) {
			throw MqttProtocolError.malformed(
					"property " + prop.key() + " runs past the end of its block");
		}
	}

	// ── encoding ─────────────────────────────────────────────────────────

	/**
	 * The whole block, length prefix included. An empty or absent map is a
	 * single zero byte — §2.2.2.1 requires the length even when there are no
	 * properties, and leaving it out is the quickest way to desynchronise a
	 * connection.
	 */
	public static byte[] encode(Map<Keyword, ?> properties) {
		if (properties == null || properties.isEmpty()) {
			return new byte[] { 0 };
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (Map.Entry<Keyword, ?> e : properties.entrySet()) {
			Prop prop = BY_KEY.get(e.getKey());
			if (prop == null) {
				throw new IllegalArgumentException("not an MQTT 5 property: " + e.getKey());
			}
			Object value = e.getValue();
			if (value == null) {
				continue;
			}
			if (prop.repeatable()) {
				for (Object item : (Iterable<?>) value) {
					body.write(prop.id());
					writeRepeatable(body, prop, item);
				}
			} else {
				body.write(prop.id());
				writeSingle(body, prop, value);
			}
		}
		byte[] payload = body.toByteArray();
		byte[] prefix = encodeVariableByteInteger(payload.length);
		byte[] out = new byte[prefix.length + payload.length];
		System.arraycopy(prefix, 0, out, 0, prefix.length);
		System.arraycopy(payload, 0, out, prefix.length, payload.length);
		return out;
	}

	private static void writeRepeatable(ByteArrayOutputStream body, Prop prop, Object item) {
		if (prop.type() == Type.PAIR) {
			writeString(body, (String) RT.nth(item, 0));
			writeString(body, (String) RT.nth(item, 1));
		} else {
			body.writeBytes(encodeVariableByteInteger(((Number) item).longValue()));
		}
	}

	private static void writeSingle(ByteArrayOutputStream body, Prop prop, Object value) {
		switch (prop.type()) {
		case BYTE -> body.write(((Number) value).intValue() & 0xFF);
		case BOOLEAN -> body.write(Boolean.TRUE.equals(value) ? 1 : 0);
		case TWO_BYTE -> {
			long v = ((Number) value).longValue();
			require(v >= 0 && v <= 65535, prop, v, "0..65535");
			body.write((int) ((v >>> 8) & 0xFF));
			body.write((int) (v & 0xFF));
		}
		case FOUR_BYTE -> {
			long v = ((Number) value).longValue();
			require(v >= 0 && v <= 4294967295L, prop, v, "0..4294967295");
			body.write((int) ((v >>> 24) & 0xFF));
			body.write((int) ((v >>> 16) & 0xFF));
			body.write((int) ((v >>> 8) & 0xFF));
			body.write((int) (v & 0xFF));
		}
		case VARIABLE_BYTE -> body.writeBytes(encodeVariableByteInteger(((Number) value).longValue()));
		case UTF8 -> writeString(body, (String) value);
		case BINARY -> {
			byte[] data = (byte[]) value;
			body.write((data.length >>> 8) & 0xFF);
			body.write(data.length & 0xFF);
			body.writeBytes(data);
		}
		case PAIR -> throw new IllegalArgumentException(
				prop.key() + " is a repeating property and needs a sequence of pairs");
		}
	}

	private static void require(boolean ok, Prop prop, long value, String range) {
		if (!ok) {
			throw new IllegalArgumentException(
					prop.key() + " must be in " + range + ", got " + value);
		}
	}

	private static void writeString(ByteArrayOutputStream body, String s) {
		byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
		if (utf8.length > 65535) {
			throw new IllegalArgumentException("string property longer than 65535 bytes");
		}
		body.write((utf8.length >>> 8) & 0xFF);
		body.write(utf8.length & 0xFF);
		body.writeBytes(utf8);
	}

	/** Named so a caller can answer a bad block without re-deriving the code. */
	public static byte malformedPacket() {
		return MqttReasonCode.MALFORMED_PACKET;
	}
}
