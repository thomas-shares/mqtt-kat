package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import clojure.lang.Keyword;

public abstract class GenericMessage {
	public static final int MESSAGE_LENGTH = 4096;
	
	public static final byte MESSAGE_CONNECT = 1;
	public static final byte MESSAGE_CONNACK = 2;
	public static final byte MESSAGE_PUBLISH = 3;
	public static final byte MESSAGE_PUBACK = 4;
	public static final byte MESSAGE_PUBREC = 5;
	public static final byte MESSAGE_PUBREL = 6;
	public static final byte MESSAGE_PUBCOMP = 7;
	public static final byte MESSAGE_SUBSCRIBE = 8;
	public static final byte MESSAGE_SUBACK = 9;
	public static final byte MESSAGE_UNSUBSCRIBE = 10;
	public static final byte MESSAGE_UNSUBACK = 11;
	public static final byte MESSAGE_PINGREQ = 12;
	public static final byte MESSAGE_PINGRESP = 13;
	public static final byte MESSAGE_DISCONNECT = 14;
	public static final byte MESSAGE_AUTHENTICATION = 15;

	// Generic
	public static final Keyword PACKET_TYPE = intern("packet-type");
	public static final Keyword CLIENT_KEY = intern("client-key");
	public static final Keyword PACKET_IDENTIFIER = intern("packet-identifier");

	//CONNECT
	public static final Keyword CLIENT_ID = intern("client-id");
	public static final Keyword PROTOCOL_NAME = intern("protocol-name");
	public static final Keyword PROTOCOL_VERSION = intern("protocol-version");

	/** MQTT 5.0 §2.2.2 — the property block every v5 packet carries. */
	public static final Keyword PROPERTIES = intern("properties");
	/** MQTT 5.0 §2.4 — replaces 3.1.1's per-packet return codes. */
	public static final Keyword REASON_CODE = intern("reason-code");
	/**
	 * Whether a DISCONNECT came from the client or was raised by the broker
	 * because the connection died.
	 *
	 * The broker synthesises a DISCONNECT for a socket that has gone, so the
	 * handler sees the same packet either way — and the will turns on exactly
	 * this distinction (§3.1.2.5): a client that said goodbye keeps its will
	 * unpublished, a connection that simply ended does not.
	 */
	public static final Keyword FROM_CLIENT = intern("from-client?");
	public static final Keyword WILL = intern("will");
	public static final byte WILL_FLAG = (byte) 0x04;
	public static final Keyword WILL_RETAIN = intern("will-retain");
	public static final byte WILLRETAIN_FLAG = (byte) 0x20;
	public static final Keyword WILL_QOS = intern("will-qos");
	public static final byte WILL_QOS_FLAGS = (byte) 0x18;
	public static final Keyword WILL_TOPIC = intern("will-topic");
	public static final Keyword WILL_MSG = intern("will-message");
	public static final Keyword CLEAN_SESSION = intern("clean-session?");
	public static final byte CLEANSESSION_FLAG = (byte) 0x02;

	public static final Keyword KEEP_ALIVE = intern("keep-alive");
	public static final Keyword USER_CREDENTIALS = intern("user-credentials");
	public static final Keyword USER_NAME = intern("username");
	public static final byte USERNAME_FLAG = (byte) 0x80;
	public static final Keyword PASSWORD = intern("password");
	public static final byte PASSWORD_FLAG = (byte) 0x40;


	// CONNACK
	public static final Keyword SESSION_PRESENT = intern("session-present?");
	public static final Keyword CONNECT_RETURN_CODE = intern("connect-return-code");

	//PUBLISH
	public static final Keyword DUPLICATE = intern("duplicate?");
	public static final Keyword RETAIN = intern("retain?");
	public static final Keyword TOPIC = intern("topic");
	public static final Keyword PAYLOAD = intern("payload");

	//SUBSCRIBE
	public static final Keyword TOPICS = intern("topics");
	public static final Keyword TOPIC_FILTER = intern("topic-filter");
	public static final Keyword QOS = intern("qos");
	/** MQTT 5.0 §3.8.3.1 — subscription options. */
	public static final Keyword NO_LOCAL = intern("no-local?");
	public static final Keyword RETAIN_AS_PUBLISHED = intern("retain-as-published?");
	public static final Keyword RETAIN_HANDLING = intern("retain-handling");

	//SUBACK
	public static final Keyword SUBACK_RESPONSE = intern("response");





}
