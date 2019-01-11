package org.mqttkat.packages;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

import java.util.TreeMap;

import static org.mqttkat.MqttUtil.*;
import clojure.lang.PersistentArrayMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static clojure.lang.Keyword.intern;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

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
		String s1 = String.format("%8s", Integer.toBinaryString(connectFlags & 0xFF)).replace(' ', '0');
		System.out.println("received connectFlags: " + s1);
		//Byte b1 = remainAndPayload[offset++];
		//Byte b2 = remainAndPayload[offset++];
		//int a = Short.toUnsignedInt((short) (b1<<8));
		//int b = Short.toUnsignedInt((short)(b2 & 0xFF));
		//int keepAlive = Short.toUnsignedInt((short) (b1<<8)) + Short.toUnsignedInt((short)(b2 & 0xFF));
		//System.out.println("4 " + offset + " keepAlive: " + keepAlive);
		
		int keepAlive = twoBytesToInt( remainAndPayload[offset++], remainAndPayload[offset++]);
		
		String clientID = decodeUTF8(remainAndPayload, offset);
		offset += clientID.length() + 2;
		//System.out.println("5 " + offset + " ClientId:" + clientID);


		boolean userNameSet = (connectFlags & 0x80) == 0x80;
		boolean passwordSet = (connectFlags & 0x40) == 0x40;
		boolean willFlag = (connectFlags & 0x04) == 0x04;
		String willTopic = "";
		String willMessage = "";
		Map<Keyword, Object> will = new TreeMap<Keyword, Object>();

		if( willFlag ) {
			//log("will set...");
			willTopic = decodeUTF8(remainAndPayload, offset);
			will.put(WILL_TOPIC, willTopic);
			offset += willTopic.length() + 2;
			willMessage = decodeUTF8(remainAndPayload, offset);
			will.put(WILL_MSG, willMessage);
			offset += willMessage.length() + 2;
			boolean willRetain = (connectFlags & 0x20) == 0x20;
			will.put(WILL_RETAIN, willRetain);
			
			byte qos = (byte) ((connectFlags & 0x18) >> 3);
			//log("qos: " +  qos);
			will.put(WILL_QOS, qos);
			
		}
		//System.out.println("6 " + offset);


		String userName = null;
		byte[] password = null;
		Map<Keyword, Object> userCredentials = new TreeMap<Keyword, Object>();
		if(userNameSet) {
			userName = decodeUTF8(remainAndPayload, offset);
			offset += userName.length() + 2;
			userCredentials.put(USER_NAME, userName);

			if(passwordSet) {
				short passwordLength = (short)((remainAndPayload[offset++]<<8) | remainAndPayload[offset++]);
				log("passwordlength: " + passwordLength);
				password = new byte[passwordLength];
				for(int i=0; i< passwordLength; i++) {
					password[i] = remainAndPayload[offset + i];
				}
				//passWord = decodeUTF8(remainAndPayload, offset);
				//offset += passWord.length() + 2;
				userCredentials.put(PASSWORD, password);
			}
		}
		//System.out.println("7 " + offset + " username: " + userName + " password: " + passWord);


		//System.out.println("8 " + offset + " password: " + password.toString());

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("CONNECT"));
		m.put(CLIENT_KEY, key);
		m.put(PROTOCOL_NAME, protocolName);
		m.put(CLIENT_ID, clientID);
		m.put(PROTOCOL_VERSION, clientVersion);
		m.put(CLEAN_SESSION, (connectFlags & 0x02) == 0x02);
		m.put(KEEP_ALIVE, keepAlive);

		if(!will.isEmpty()) {
			m.put(WILL, will);
		}

		if(!userCredentials.isEmpty()) {
			m.put(USER_CREDENTIALS, userCredentials);
		}

		return PersistentArrayMap.create(m);
	}
	
	
	@SuppressWarnings({ "unchecked"})
	public static ByteBuffer[] encode(Map<Keyword, ?> message) throws UnsupportedEncodingException {
		log("encode CONNECT");
		int length = 0;

		byte[] bytes = new byte[MESSAGE_LENGTH];
		ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_LENGTH);
		byte firstByte = (byte) (MESSAGE_CONNECT << 4);
		buffer.put(firstByte);

		byte[] topic = ((String) message.get(PROTOCOL_NAME)).getBytes("UTF-8");
		bytes[length++] = (byte) ((topic.length >>> 8) & 0xFF);
		bytes[length++] = (byte) (topic.length & 0xFF);
		for(int i = 0; i < topic.length; i++) {
			bytes[length++] = topic[i];
		}	
	
		boolean cleanSession = (Boolean) message.get(CLEAN_SESSION);
		bytes[7] = (byte) (cleanSession == true ? 0x02 | bytes[7]: bytes[7]);
		//log("connect flags: " + connectFlags[0]);
		length++;
		
		Long keepAlive = (Long) message.get(KEEP_ALIVE);
		bytes[length++] = (byte) ((keepAlive >>> 8) & 0xFF);
		bytes[length++] = (byte) ((keepAlive >>> 0) & 0xFF);
		//log("keep alive: " + message.get(KEEP_ALIVE).toString());
		Long keepAliveL = (Long) message.get(KEEP_ALIVE);
		
		byte[] clientId = ((String) message.get(CLIENT_ID)).getBytes("UTF-8");
		bytes[length++] = (byte) ((clientId.length >>> 8) & 0xFF);
		bytes[length++] = (byte) (clientId.length & 0xFF);
		for(int i = 0; i < clientId.length; i++) {
			bytes[length++] = clientId[i];
		}	
		
		if(message.containsKey(WILL)) {
			Map<Keyword, ?> will =  (Map<Keyword, ?>) message.get(WILL); 
			bytes[7] = (byte) (0x04 | bytes[7]);
			//log("set will qos: " + will.get(WILL_QOS));
			Byte willQos = Byte.parseByte(will.get(WILL_QOS).toString());
			bytes[7] = (byte) ((willQos << 3) | bytes[7]);
			Boolean willRetain = (Boolean) will.get(WILL_RETAIN);
			bytes[7] = willRetain ? (byte) (0x20 |bytes[7]) : bytes[7];
			
			byte[] willTopic = ((String) message.get(WILL_TOPIC)).getBytes("UTF-8");
			bytes[length++] = (byte) ((willTopic.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (willTopic.length & 0xFF);
			for(int i = 0; i < willTopic.length; i++) {
				bytes[length++] = willTopic[i];
			}	
			
			byte[] willMessage = ((String) message.get(WILL_MSG)).getBytes("UTF-8");
			bytes[length++] = (byte) ((willMessage.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (willMessage.length & 0xFF);
			for(int i = 0; i < willMessage.length; i++) {
				bytes[length++] = willMessage[i];
			}	
		}
		
		if(message.containsKey(USER_CREDENTIALS)) { 
			Map<Keyword, ?> userCredentials =  (Map<Keyword, ?>) message.get(USER_CREDENTIALS); 
			//log("set username: " + userCredentials.get(USER_NAME));
			bytes[7] = (byte) (0x80 | bytes[7]);
			
			byte[] userName = ((String) message.get(USER_NAME)).getBytes("UTF-8");
			bytes[length++] = (byte) ((userName.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (userName.length & 0xFF);
			for(int i = 0; i < userName.length; i++) {
				bytes[length++] = userName[i];
			}	
			

			
			if(userCredentials.containsKey(PASSWORD)) {
				//log("password set " + userCredentials.get(PASSWORD));
				bytes[7]= (byte) (0x40 | bytes[7]);
				//ByteBuffer password = encodeUTF8((String)userCredentials.get(PASSWORD));
				byte[] passwordArray = (byte[]) userCredentials.get(PASSWORD);
				
				bytes[length++] = (byte) ((passwordArray.length >>> 8) & 0xFF);
				bytes[length++] = (byte) ((passwordArray.length >>> 0) & 0xFF);
				for(int i =0 ; i < passwordArray.length; i++) {
					bytes[length++] = passwordArray[i];
				}
			}
		}
		String s1 = String.format("%8s", Integer.toBinaryString(bytes[7] & 0xFF)).replace(' ', '0');
		log("connect flags: " + s1);
		buffer.put(calculateLenght(length));
		buffer.put(bytes, 0, length);
		buffer.flip();
		log("length: " + length);
		return new ByteBuffer[]{buffer};
	}
}
