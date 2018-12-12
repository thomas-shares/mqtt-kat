package org.mqttkat.packages;

import clojure.lang.IPersistentMap;
import java.util.TreeMap;

import org.mqttkat.MqttUtil;

import static org.mqttkat.MqttUtil.*;
import clojure.lang.PersistentArrayMap;
import java.util.Map;
import static clojure.lang.Keyword.intern;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import static org.mqttkat.MqttUtil.log;

public class MqttConnect extends GenericMessage {

	public static IPersistentMap decodeConnect(SelectionKey key, byte flags, byte[] remainAndPayload) throws IOException {
		//System.out.println("decode connect from...");


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
		//System.out.println("4 " + offset);

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


		String userName = null;
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
		m.put(CLIENT_KEY, key);
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
	
	
	public static ByteBuffer[] encode(Map<?, ?> message) throws UnsupportedEncodingException {
		log("encode CONNECT");
		byte[] bType = {(MESSAGE_CONNECT << 4)};
		bType[0] =  (byte) (bType[0] & 0xf0);
		System.out.println("type: " + bType[0]);

		log(message.get(PROTOCOL_NAME).toString());
		ByteBuffer protocolName = encodeUTF8((String)message.get(PROTOCOL_NAME));
		protocolName.flip();
		log(message.get(PROTOCOL_VERSION).toString());
		//byte[] protocolLevel =  {(byte)message.get(PROTOCOL_VERSION)};
		byte[] protocolLevel = {4};
		System.out.println("level: " + protocolLevel[0]);

		byte[] connectFlags = {0};
		System.out.println(connectFlags[0]);

		log(message.get(KEEP_ALIVE).toString());
		Long keepAliveL = (Long) message.get(KEEP_ALIVE);
		ByteBuffer keepAlive = ByteBuffer.allocate(2);
		keepAlive.put((byte) ((keepAliveL >>> 8) & 0xFF)).put((byte) ((keepAliveL >>> 0) & 0xFF));
		keepAlive.flip();

		
		log(message.get(CLIENT_ID).toString());
		ByteBuffer clientId = encodeUTF8((String)message.get(CLIENT_ID));
		byte[] bLength = MqttUtil.calculateLenght(10 + clientId.position());
		clientId.flip();

		System.out.println("length: " + bLength[0]);

		
		//bPayload[0] =  (byte) ((Boolean) message.get(SESSION_PRESENT) ? 1 : 0);
		//bPayload[1] = 0x00;
		//System.out.println(message.toString() + String.format("%x", bType[0]) + String.format("%x", bLength[0]) + String.format("%x%x", bPayload[0],bPayload[1]));

		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer length = ByteBuffer.wrap(bLength);
		ByteBuffer protocolLevelBuffer = ByteBuffer.wrap(protocolLevel);
		ByteBuffer connectFlagsBuffer = ByteBuffer.wrap(connectFlags);
		

		return new ByteBuffer[]{type, length, protocolName, protocolLevelBuffer, connectFlagsBuffer,  keepAlive, clientId};
	}
}
