package org.mqttkat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * MQTT 5.0 reason codes (§2.4).
 *
 * A single byte carried by CONNACK, PUBACK, PUBREC, PUBREL, PUBCOMP, SUBACK,
 * UNSUBACK, DISCONNECT and AUTH, replacing 3.1.1's per-packet return codes with
 * one shared vocabulary. The convention that matters is in the top bit: any
 * value from 0x80 up is a failure, so a peer can tell a refusal from an
 * acceptance without knowing what the code means.
 *
 * The same numeric value means different things in different packets — 0x00 is
 * "Success" in a PUBACK and "Normal disconnection" in a DISCONNECT — so both
 * names are here and both are 0x00 on the wire.
 */
public final class MqttReasonCode {

	private MqttReasonCode() {
	}

	// ── success half: 0x00 to 0x7F ───────────────────────────────────────
	public static final byte SUCCESS = 0x00;
	public static final byte NORMAL_DISCONNECTION = 0x00;
	public static final byte GRANTED_QOS_0 = 0x00;
	public static final byte GRANTED_QOS_1 = 0x01;
	public static final byte GRANTED_QOS_2 = 0x02;
	public static final byte DISCONNECT_WITH_WILL_MESSAGE = 0x04;
	public static final byte NO_MATCHING_SUBSCRIBERS = 0x10;
	public static final byte NO_SUBSCRIPTION_EXISTED = 0x11;
	public static final byte CONTINUE_AUTHENTICATION = 0x18;
	public static final byte RE_AUTHENTICATE = 0x19;

	// ── failure half: 0x80 upwards ───────────────────────────────────────
	public static final byte UNSPECIFIED_ERROR = (byte) 0x80;
	public static final byte MALFORMED_PACKET = (byte) 0x81;
	public static final byte PROTOCOL_ERROR = (byte) 0x82;
	public static final byte IMPLEMENTATION_SPECIFIC_ERROR = (byte) 0x83;
	public static final byte UNSUPPORTED_PROTOCOL_VERSION = (byte) 0x84;
	public static final byte CLIENT_IDENTIFIER_NOT_VALID = (byte) 0x85;
	public static final byte BAD_USER_NAME_OR_PASSWORD = (byte) 0x86;
	public static final byte NOT_AUTHORIZED = (byte) 0x87;
	public static final byte SERVER_UNAVAILABLE = (byte) 0x88;
	public static final byte SERVER_BUSY = (byte) 0x89;
	public static final byte BANNED = (byte) 0x8A;
	public static final byte SERVER_SHUTTING_DOWN = (byte) 0x8B;
	public static final byte BAD_AUTHENTICATION_METHOD = (byte) 0x8C;
	public static final byte KEEP_ALIVE_TIMEOUT = (byte) 0x8D;
	public static final byte SESSION_TAKEN_OVER = (byte) 0x8E;
	public static final byte TOPIC_FILTER_INVALID = (byte) 0x8F;
	public static final byte TOPIC_NAME_INVALID = (byte) 0x90;
	public static final byte PACKET_IDENTIFIER_IN_USE = (byte) 0x91;
	public static final byte PACKET_IDENTIFIER_NOT_FOUND = (byte) 0x92;
	public static final byte RECEIVE_MAXIMUM_EXCEEDED = (byte) 0x93;
	public static final byte TOPIC_ALIAS_INVALID = (byte) 0x94;
	public static final byte PACKET_TOO_LARGE = (byte) 0x95;
	public static final byte MESSAGE_RATE_TOO_HIGH = (byte) 0x96;
	public static final byte QUOTA_EXCEEDED = (byte) 0x97;
	public static final byte ADMINISTRATIVE_ACTION = (byte) 0x98;
	public static final byte PAYLOAD_FORMAT_INVALID = (byte) 0x99;
	public static final byte RETAIN_NOT_SUPPORTED = (byte) 0x9A;
	public static final byte QOS_NOT_SUPPORTED = (byte) 0x9B;
	public static final byte USE_ANOTHER_SERVER = (byte) 0x9C;
	public static final byte SERVER_MOVED = (byte) 0x9D;
	public static final byte SHARED_SUBSCRIPTIONS_NOT_SUPPORTED = (byte) 0x9E;
	public static final byte CONNECTION_RATE_EXCEEDED = (byte) 0x9F;
	public static final byte MAXIMUM_CONNECT_TIME = (byte) 0xA0;
	public static final byte SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED = (byte) 0xA1;
	public static final byte WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED = (byte) 0xA2;

	/**
	 * §2.4: the top bit is the whole convention. Everything from 0x80 up is a
	 * failure, whatever the packet it arrived in.
	 */
	public static boolean isError(byte code) {
		return (code & 0xFF) >= 0x80;
	}

	/**
	 * A name for a log line. Built by reflection over this class's own fields
	 * rather than kept as a second list beside the constants, which would be a
	 * list to forget to update. Several codes share a value — 0x00 is Success,
	 * Normal disconnection and Granted QoS 0 — and the first declared wins,
	 * which is why the general-purpose name is declared first.
	 */
	public static String name(byte code) {
		String known = NAMES.get(code & 0xFF);
		return known != null ? known : String.format("0x%02X", code & 0xFF);
	}

	private static final Map<Integer, String> NAMES = new HashMap<>();

	static {
		for (Field f : MqttReasonCode.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) && f.getType() == byte.class) {
				try {
					NAMES.putIfAbsent(f.getByte(null) & 0xFF, f.getName());
				} catch (IllegalAccessException e) {
					throw new ExceptionInInitializerError(e);
				}
			}
		}
	}
}
