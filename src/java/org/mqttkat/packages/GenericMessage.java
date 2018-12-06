package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import clojure.lang.Keyword;

public abstract class GenericMessage {
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
	public static final Keyword FLAGS = intern("flags");
	public static final Keyword CLIENT_KEY = intern("client-key");
	public static final Keyword PACKET_IDENTIFIER = intern("packet-identifier");

	//CONNECT
	public static final Keyword CALL_BACK = intern("call-back");
	public static final Keyword CLIENT_ID = intern("client-id");
	public static final Keyword PROTOCOL_NAME = intern("protocol-name");
	public static final Keyword PROTOCOL_VERSION = intern("protocol-version");
	public static final Keyword USERNAME_SET = intern("connect-flags-username-flag");
	public static final Keyword PASSWORD_SET = intern("connect-flags-password-flag");
	public static final Keyword WILL_RETAIN = intern("connect-flags-will-retain");
	// connect-flags-will-retain-qos
	public static final Keyword WILL_QOS = intern("connect-flags-will-qos");
	public static final Keyword WILL_FLAG = intern("connect-flags-will-flag");
	public static final Keyword CLEAN_START = intern("connect-flags-clean-session");
	public static final Keyword RESERVED = intern("reserved");
	public static final Keyword KEEP_ALIVE = intern("keep-alive");
	public static final Keyword WILL_TOPIC = intern("will-topic");
	public static final Keyword WILL_MSG = intern("will-message");
	public static final Keyword USER_NAME = intern("username");
	public static final Keyword PASSWORD = intern("password");

	// CONNACK
	public static final Keyword SESSION_PRESENT = intern("session-present");

	//PUBLISH
	public static final Keyword DUPLICATE = intern("duplicate");
	public static final Keyword MSG_QOS = intern("publish-qos");
	public static final Keyword RETAIN = intern("publish-retain");
	public static final Keyword TOPIC = intern("topic");
	public static final Keyword PAYLOAD = intern("payload");

	//SUBSCRIBE
	public static final Keyword TOPICS = intern("topics");







}
