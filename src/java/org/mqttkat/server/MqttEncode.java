package org.mqttkat.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import org.mqttkat.packages.MqttConnAck;
import org.mqttkat.packages.MqttDisconnect;
import org.mqttkat.packages.MqttPingResp;
import org.mqttkat.packages.MqttPublish;
import org.mqttkat.packages.MqttSubAck;

import static org.mqttkat.packages.GenericMessage.*;

import clojure.lang.Keyword;

public class MqttEncode {

	public static ByteBuffer[] mqttEncoder(Map<?, ?> message) throws IOException {
		if( message == null ) {
			return null;
		}

		ByteBuffer[] outboundMessage = null;
		Object type = message.get(PACKET_TYPE);

		if(type instanceof Keyword) {
			String strType = type.toString();
			//System.out.println(strType);
			if( strType.equals(":CONNACK")) {
				outboundMessage = MqttConnAck.encode(message);
			} else if( strType.equals(":PINGRESP")) {
				outboundMessage = MqttPingResp.encode(message);
			} else if (strType.equals(":SUBACK")) {
				outboundMessage = MqttSubAck.encode(message);
			} else if( strType.equals(":DISCONNECT")) {
				outboundMessage = MqttDisconnect.encode(message);
			} else if ( strType.equals(":PUBLISH")) {
				outboundMessage = MqttPublish.encode(message);
			}
			else {
				System.out.println("DIDN'T RECOGNISE OUTBOUND MESSAGE TYPE!!!");
				System.out.println(message.toString());
				throw new IOException();
			}
		} else {
			System.out.println("FAILURE!!!!! not a keyword!!!");
		}
		
		//for(ByteBuffer buf : outboundMessage) {
	//		buf.flip();
//		}

		return outboundMessage;
	}
}
