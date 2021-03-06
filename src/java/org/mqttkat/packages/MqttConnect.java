package org.mqttkat.packages;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

import static org.mqttkat.MqttUtil.*;
import clojure.lang.PersistentArrayMap;

import java.util.Map;
import static clojure.lang.Keyword.intern;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class MqttConnect extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] remainAndPayload) throws IOException {
		//System.out.println("decode connect from...");


		int offset = 0;
		String protocolName = decodeUTF8(remainAndPayload, offset);
		offset = protocolName.length() + 2;
		//System.out.println("1 " + offset + " protocol name: " +  protocolName);
		//System.out.println("protocolName: " + protocolName);
		byte clientVersion = remainAndPayload[offset++];
		//System.out.println("2 " + offset);

		//System.out.println("clientVersion: " + clientVersion);
		byte connectFlags = remainAndPayload[offset++];
		//System.out.println("3 " + offset + " connectflag: " + connectFlags);

		//offset++;
		//String s1 = String.format("%8s", Integer.toBinaryString(connectFlags & 0xFF)).replace(' ', '0');
		//System.out.println("received connectFlags: " + s1);
		//Byte b1 = remainAndPayload[offset++];
		//Byte b2 = remainAndPayload[offset++];
		//int a = Short.toUnsignedInt((short) (b1<<8));
		//int b = Short.toUnsignedInt((short)(b2 & 0xFF));
		//int keepAlive = Short.toUnsignedInt((short) (b1<<8)) + Short.toUnsignedInt((short)(b2 & 0xFF));
		//System.out.println("4 " + offset + " keepAlive: " + keepAlive);
		
		Long keepAlive = twoBytesToLong( remainAndPayload[offset++], remainAndPayload[offset++]);
		//System.out.println("4 " + offset + "  keep alive:" + keepAlive);

		
		String clientID = decodeUTF8(remainAndPayload, offset);
		offset += clientID.length() + 2;
		//System.out.println("5 " + offset + " ClientId:" + clientID);


		boolean userNameSet = (connectFlags & USERNAME_FLAG) == USERNAME_FLAG;
		boolean passwordSet = (connectFlags & PASSWORD_FLAG) == PASSWORD_FLAG;
		boolean willFlag = (connectFlags & WILL_FLAG) == WILL_FLAG;
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
			boolean willRetain = (connectFlags & WILLRETAIN_FLAG) == 0x20;
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
				//log("passwordlength: " + passwordLength);
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
		m.put(CLEAN_SESSION, (connectFlags & CLEANSESSION_FLAG) == 0x02);
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
	public static ByteBuffer encode(Map<Keyword, ?> message) throws UnsupportedEncodingException {
		//log("encode CONNECT");
		int length = 0;
		byte[] bytes = new byte[MESSAGE_LENGTH];
		ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_LENGTH);
		byte firstByte = (byte) (MESSAGE_CONNECT << 4);
		buffer.put(firstByte);

		byte[] protocolName = ((String) message.get(PROTOCOL_NAME)).getBytes(StandardCharsets.UTF_8);
		bytes[length++] = (byte) ((protocolName.length >>> 8) & 0xFF);
		bytes[length++] = (byte) (protocolName.length & 0xFF);
		for(int i = 0; i < protocolName.length; i++) {
			bytes[length++] = protocolName[i];
		}	
		//Protocol version
		bytes[length++] = Byte.parseByte(((Long) message.get(PROTOCOL_VERSION)).toString());

		int connectFlagOffset = protocolName.length == 4 ? 7 : 9;

		boolean cleanSession = (Boolean) message.get(CLEAN_SESSION);
		bytes[connectFlagOffset] = (byte) (cleanSession ? CLEANSESSION_FLAG : 0);
		//log("connect flags: " + connectFlags[0]);
		length++;
	
		Long keepAlive = (Long) message.get(KEEP_ALIVE);
		bytes[length++] = (byte) ((keepAlive >>> 8) & 0xFF);
		bytes[length++] = (byte) (keepAlive & 0xFF);
	
		byte[] clientId = ((String) message.get(CLIENT_ID)).getBytes(StandardCharsets.UTF_8);
		bytes[length++] = (byte) ((clientId.length >>> 8) & 0xFF);
		bytes[length++] = (byte) (clientId.length & 0xFF);
		for(int i = 0; i < clientId.length; i++) {
			bytes[length++] = clientId[i];
		}	
	
		if(message.containsKey(WILL)) {
			Map<Keyword, ?> will =  (Map<Keyword, ?>) message.get(WILL); 
			bytes[connectFlagOffset] = (byte) (WILL_FLAG | bytes[connectFlagOffset]);
			//log("set will qos: " + will.get(WILL_QOS));
			Byte willQos = Byte.parseByte(will.get(WILL_QOS).toString());
			bytes[connectFlagOffset] = (byte) ((willQos << 3) | bytes[connectFlagOffset]);
			Boolean willRetain = (Boolean) will.get(WILL_RETAIN);
			bytes[connectFlagOffset] = willRetain ? (byte) (WILLRETAIN_FLAG |bytes[connectFlagOffset]) : bytes[connectFlagOffset];
			//log("will topic: " + ((String) will.get(WILL_TOPIC)) );
			byte[] willTopic = ((String) will.get(WILL_TOPIC)).getBytes(StandardCharsets.UTF_8);
			bytes[length++] = (byte) ((willTopic.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (willTopic.length & 0xFF);
			for(int i = 0; i < willTopic.length; i++) {
				bytes[length++] = willTopic[i];
			}	
			
			byte[] willMessage = ((String) will.get(WILL_MSG)).getBytes(StandardCharsets.UTF_8);
			bytes[length++] = (byte) ((willMessage.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (willMessage.length & 0xFF);
			for(int i = 0; i < willMessage.length; i++) {
				bytes[length++] = willMessage[i];
			}	
		}
		//log("length: " + length + " post will");
	
		if(message.containsKey(USER_CREDENTIALS)) { 
			Map<Keyword, ?> userCredentials =  (Map<Keyword, ?>) message.get(USER_CREDENTIALS); 
			String userNameStr = (String) userCredentials.get(USER_NAME);
			//log("set username: " + userNameStr);
			bytes[connectFlagOffset] = (byte) (USERNAME_FLAG | bytes[connectFlagOffset]);
			
			byte[] userName = userNameStr.getBytes(StandardCharsets.UTF_8);
			bytes[length++] = (byte) ((userName.length >>> 8) & 0xFF);
			bytes[length++] = (byte) (userName.length & 0xFF);
			for(int i = 0; i < userName.length; i++) {
				bytes[length++] = userName[i];
			}	
			
			if(userCredentials.containsKey(PASSWORD)) {
				//log("password set " + userCredentials.get(PASSWORD));
				bytes[connectFlagOffset]= (byte) (PASSWORD_FLAG | bytes[connectFlagOffset]);
				//ByteBuffer password = encodeUTF8((String)userCredentials.get(PASSWORD));
				byte[] passwordArray = (byte[]) userCredentials.get(PASSWORD);
				
				bytes[length++] = (byte) ((passwordArray.length >>> 8) & 0xFF);
				bytes[length++] = (byte) ((passwordArray.length >>> 0) & 0xFF);
				for(int i =0 ; i < passwordArray.length; i++) {
					bytes[length++] = passwordArray[i];
				}
			}
		}
		//String s1 = String.format("%8s", Integer.toBinaryString(bytes[connectFlagOffset] & 0xFF)).replace(' ', '0');
		//log("connect flags: " + s1 + "  offset" + connectFlagOffset);
		buffer.put(calculateLength(length));
		buffer.put(bytes, 0, length);
		buffer.flip();
		//log("length: " + length);
		return buffer;
	}
}
