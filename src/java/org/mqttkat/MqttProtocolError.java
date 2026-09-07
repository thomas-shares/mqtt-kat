package org.mqttkat;

import java.io.IOException;

/**
 * A packet the peer had no business sending, with the reason code that goes
 * back to it.
 *
 * MQTT 5 does not merely drop a bad packet: §4.13 requires the receiver to
 * answer with a CONNACK or DISCONNECT carrying a reason code saying what was
 * wrong. That code is decided where the fault is found — the decoder knows it
 * was a Malformed Packet rather than a Protocol Error — so it travels with the
 * exception instead of being guessed again at the catch site.
 *
 * An IOException because every decode entry point already declares one, so this
 * needs no new signature anywhere it is thrown from.
 */
public class MqttProtocolError extends IOException {

	private static final long serialVersionUID = 1L;

	public final byte reasonCode;

	public MqttProtocolError(byte reasonCode, String message) {
		super(message + " [" + MqttReasonCode.name(reasonCode) + "]");
		this.reasonCode = reasonCode;
	}

	public static MqttProtocolError malformed(String message) {
		return new MqttProtocolError(MqttReasonCode.MALFORMED_PACKET, message);
	}

	public static MqttProtocolError protocol(String message) {
		return new MqttProtocolError(MqttReasonCode.PROTOCOL_ERROR, message);
	}
}
