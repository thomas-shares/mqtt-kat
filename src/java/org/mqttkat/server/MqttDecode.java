package org.mqttkat.server;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import clojure.lang.IPersistentMap;

import org.mqttkat.packages.MqttAuthenticate;
import org.mqttkat.packages.MqttConnAck;
import org.mqttkat.packages.MqttConnect;
import org.mqttkat.packages.MqttDisconnect;
import org.mqttkat.packages.MqttPingReq;
import org.mqttkat.packages.MqttPingResp;
import org.mqttkat.packages.MqttPubAck;
import org.mqttkat.packages.MqttPubComp;
import org.mqttkat.packages.MqttPubRec;
import org.mqttkat.packages.MqttPubRel;
import org.mqttkat.packages.MqttPublish;
import org.mqttkat.packages.MqttSubAck;
import org.mqttkat.packages.MqttSubscribe;
import org.mqttkat.packages.MqttUnSubAck;
import org.mqttkat.packages.MqttUnsubscribe;

import static org.mqttkat.packages.GenericMessage.*;

/**
 * Turns one framed MQTT packet into a Clojure map. The counterpart of
 * MqttEncode, and previously an if/else chain inlined in MqttServer's selector
 * loop; it lives here so that framing, decoding and dispatch can happen on a
 * connection's own thread rather than on the one thread shared by every
 * connection.
 */
public class MqttDecode {

	/** @return the decoded packet, or null if the type is not a valid MQTT type. */
	public static IPersistentMap decode(SelectionKey key, byte type, byte flags, byte[] body)
			throws IOException {
		return decode(key, type, flags, body, 4);
	}

	/**
	 * As above, for a connection that has negotiated a protocol version.
	 *
	 * MQTT 5 adds a property block to packets that are otherwise laid out
	 * exactly as they were in 3.1.1 — a PUBLISH is a topic, an optional packet
	 * identifier and then bytes in both versions — so there is nothing in the
	 * packet to tell them apart. The only thing that knows is the CONNECT that
	 * opened the connection, which is why the version is remembered there and
	 * handed back down here.
	 */
	public static IPersistentMap decode(SelectionKey key, byte type, byte flags, byte[] body,
			int protocolVersion) throws IOException {
		if (type == MESSAGE_CONNECT) {
			return MqttConnect.decode(key, flags, body);
		} else if (type == MESSAGE_CONNACK) {
			return MqttConnAck.decode(key, body);
		} else if (type == MESSAGE_PUBLISH) {
			return MqttPublish.decode(key, flags, body, protocolVersion);
		} else if (type == MESSAGE_PUBACK) {
			return MqttPubAck.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_PUBREC) {
			return MqttPubRec.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_PUBREL) {
			return MqttPubRel.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_PUBCOMP) {
			return MqttPubComp.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_SUBSCRIBE) {
			return MqttSubscribe.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_SUBACK) {
			return MqttSubAck.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_UNSUBSCRIBE) {
			return MqttUnsubscribe.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_UNSUBACK) {
			return MqttUnSubAck.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_PINGREQ) {
			return MqttPingReq.decode(key);
		} else if (type == MESSAGE_PINGRESP) {
			return MqttPingResp.decode(key);
		} else if (type == MESSAGE_DISCONNECT) {
			return MqttDisconnect.decode(key, body, protocolVersion);
		} else if (type == MESSAGE_AUTHENTICATION) {
			return MqttAuthenticate.decode(key);
		}
		return null;
	}
}
