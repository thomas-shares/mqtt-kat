package org.mqttkat.packages;

import clojure.lang.IPersistentMap;
import java.util.TreeMap;

import static org.mqttkat.server.MqttUtil.*;

import clojure.lang.PersistentArrayMap;
import java.util.Map;
import static clojure.lang.Keyword.intern;

import java.io.IOException;

public class MqttConnect extends GenericMessage {

	public static IPersistentMap decodeConnect(byte info, byte[] data) throws IOException {
		System.out.println("decode connect...");
		int offset = 0;
		String protocolName = decodeUTF8(data, offset);
		offset = protocolName.length() + 2;
		//System.out.println("1 " + offset);
		//System.out.println("protocolName: " + protocolName);
		byte clientVersion = data[offset++];
		//System.out.println("2 " + offset);

		//System.out.println("clientVersion: " + clientVersion);
		byte connectFlags = data[offset++];
		//System.out.println("3 " + offset);

		//offset++;
		//System.out.println("connectFlags: " + connectFlags);
		short keepAlive = (short)((data[offset++]<<8) | data[offset++]);
		System.out.println("4 " + offset);

		String clientID = decodeUTF8(data, offset);
		offset += clientID.length() + 2;
		//System.out.println("5 " + offset);


		boolean userNameSet = (connectFlags & 0x80) == 0x80;
		boolean passwordSet = (connectFlags & 0x40) == 0x40;
		boolean willFlag = (connectFlags & 0x04) == 0x04;
		String willTopic = "";
		String willMessage = "";
		if( willFlag ) {
			willTopic = decodeUTF8(data, offset);
			offset += willTopic.length() + 2;
			willMessage = decodeUTF8(data, offset);
			offset += willMessage.length() + 2;
		}
		//System.out.println("6 " + offset);


		String userName = "";
		if(userNameSet) {
			userName = decodeUTF8(data, offset);
			offset += userName.length() + 2;
		}
		//System.out.println("7 " + offset + " username " + userName);


		byte[] password = null;
		if(passwordSet) {
			short passwordLength = (short)((data[offset++]<<8) | data[offset++]);
			password = new byte[passwordLength];
			for(int i=0; i< passwordLength; i++) {
				password[i] = data[offset + i];
			}
		}
		//System.out.println("8 " + offset + " password: " + password.toString());

		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("CONNECT"));
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
