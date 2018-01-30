package org.mqttkat.packages;

import clojure.lang.IPersistentMap;
import java.util.TreeMap;

import org.mqttkat.server.RespCallback;
import static org.mqttkat.server.MqttUtil.*;
import clojure.lang.PersistentArrayMap;
import java.util.Map;
import static clojure.lang.Keyword.intern;
import java.io.IOException;

public class MqttConnect extends GenericMessage {

	public static IPersistentMap decodeConnect(String address, byte flags, byte[] remainAndPayload) throws IOException {
		System.out.println("decode connect from :" + address);

		
		int offset = 0;
		String protocolName = decodeUTF8(remainAndPayload, offset);
		offset = protocolName.length() + 2;
		//System.out.println("1 " + offset);
		//System.out.println("protocolName: " + protocolName);
		byte clientVersion = remainAndPayload[offset++];
		//System.out.println("2 " + offset);

		//System.out.println("clientVersion: " + clientVersion);
		byte connectFlags = remainAndPayload[offset++];
		//System.out.println("3 " + offset);

		//offset++;
		//System.out.println("connectFlags: " + connectFlags);
		short keepAlive = (short)((remainAndPayload[offset++]<<8) | remainAndPayload[offset++]);
		System.out.println("4 " + offset);

		String clientID = decodeUTF8(remainAndPayload, offset);
		offset += clientID.length() + 2;
		//System.out.println("5 " + offset);


		boolean userNameSet = (connectFlags & 0x80) == 0x80;
		boolean passwordSet = (connectFlags & 0x40) == 0x40;
		boolean willFlag = (connectFlags & 0x04) == 0x04;
		String willTopic = "";
		String willMessage = "";
		if( willFlag ) {
			willTopic = decodeUTF8(remainAndPayload, offset);
			offset += willTopic.length() + 2;
			willMessage = decodeUTF8(remainAndPayload, offset);
			offset += willMessage.length() + 2;
		}
		//System.out.println("6 " + offset);


		String userName = "";
		if(userNameSet) {
			userName = decodeUTF8(remainAndPayload, offset);
			offset += userName.length() + 2;
		}
		//System.out.println("7 " + offset + " username " + userName);


		byte[] password = null;
		if(passwordSet) {
			short passwordLength = (short)((remainAndPayload[offset++]<<8) | remainAndPayload[offset++]);
			password = new byte[passwordLength];
			for(int i=0; i< passwordLength; i++) {
				password[i] = remainAndPayload[offset + i];
			}
		}
		//System.out.println("8 " + offset + " password: " + password.toString());

		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("CONNECT"));
		m.put(CALL_BACK, "");
		m.put(CLIENT_ADDRESS, address);
		m.put(FLAGS, flags);
		m.put(PROTOCOL_NAME, protocolName);
		m.put(CLIENT_ID, clientID);
		m.put(PROTOCOL_VERSION, clientVersion);
		m.put(USERNAME_SET, userNameSet);
		m.put(PASSWORD_SET, passwordSet );
		m.put(WILL_RETAIN, (connectFlags & 0x20) == 0x20);
		m.put(WILL_QOS, qos((connectFlags & 0x18)));
		m.put(WILL_FLAG, willFlag);
		m.put(CLEAN_START, (connectFlags & 0x02) == 0x02);
		m.put(RESERVED, (connectFlags & 0x01) == 0x01);
		m.put(KEEP_ALIVE, keepAlive);
		if( willFlag ) {
			m.put(WILL_TOPIC, willTopic);
			m.put(WILL_MSG, willMessage);
		}
		m.put(USER_NAME, userName);
		m.put(PASSWORD, password);

		return PersistentArrayMap.create(m);
	}
}
