package org.mqttkat.packages;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

import java.util.TreeMap;

import org.mqttkat.MqttUtil;

import static org.mqttkat.MqttUtil.*;
import clojure.lang.PersistentArrayMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static clojure.lang.Keyword.intern;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
		String s1 = String.format("%8s", Integer.toBinaryString(connectFlags & 0xFF)).replace(' ', '0');
		//System.out.println("connectFlags: " + s1);
		Byte b1 = remainAndPayload[offset++];
		Byte b2 = remainAndPayload[offset++];
		//int a = Short.toUnsignedInt((short) (b1<<8));
		//int b = Short.toUnsignedInt((short)(b2 & 0xFF));
		int keepAlive = Short.toUnsignedInt((short) (b1<<8)) + Short.toUnsignedInt((short)(b2 & 0xFF));
		//System.out.println("4 " + offset + " keepAlive: " + keepAlive);

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
			willTopic = decodeUTF8(remainAndPayload, offset);
			will.put(WILL_TOPIC, willTopic);
			offset += willTopic.length() + 2;
			willMessage = decodeUTF8(remainAndPayload, offset);
			will.put(WILL_MSG, willMessage);
			offset += willMessage.length() + 2;
			boolean willRetain = (connectFlags & 0x20) == 0x20;
			will.put(WILL_RETAIN, willRetain);
		}
		//System.out.println("6 " + offset);


		String userName = null;
		String passWord = null;
		Map<Keyword, Object> userCredentials = new TreeMap<Keyword, Object>();
		if(userNameSet) {
			userName = decodeUTF8(remainAndPayload, offset);
			offset += userName.length() + 2;
			userCredentials.put(USER_NAME, userName);

			if(passwordSet) {
//				byte[] password = null;
//				short passwordLength = (short)((remainAndPayload[offset++]<<8) | remainAndPayload[offset++]);
//				password = new byte[passwordLength];
//				for(int i=0; i< passwordLength; i++) {
//					password[i] = remainAndPayload[offset + i];
//				}
				passWord = decodeUTF8(remainAndPayload, offset);
				offset += passWord.length() + 2;
				userCredentials.put(PASSWORD, passWord);
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
		int lengthCounter = 0;

		byte[] bType = {(MESSAGE_CONNECT << 4)};
		bType[0] =  (byte) (bType[0] & 0xf0);
		//System.out.println("type: " + bType[0]);

		//log("protocal name: " + message.get(PROTOCOL_NAME).toString());
		ByteBuffer protocolName = encodeUTF8((String)message.get(PROTOCOL_NAME));
		protocolName.flip();
		//log("protocolLevel: " + message.get(PROTOCOL_VERSION).toString());
		byte[] protocolLevel = {(Byte) message.get(PROTOCOL_VERSION)};
		
		byte[] connectFlags = {0};
		boolean cleanSession = (Boolean) message.get(CLEAN_SESSION);
		connectFlags[0] = (byte) (cleanSession == true ? 0x02 | connectFlags[0]: connectFlags[0]);
		//log("connect flags: " + connectFlags[0]);

		//log("keep alive: " + message.get(KEEP_ALIVE).toString());
		Long keepAliveL = (Long) message.get(KEEP_ALIVE);
		ByteBuffer keepAlive = ByteBuffer.allocate(2);
		//String k1 = String.format("%8s", Integer.toBinaryString((byte) ((keepAliveL >>> 8) & 0xFF)  & 0xFF)).replace(' ', '0');
		//String k2 = String.format("%8s", Integer.toBinaryString((byte) (keepAliveL & 0xFF)  & 0xFF)).replace(' ', '0');

		//System.out.println("hoog: " +  k1 );
		//System.out.println("laag: " + k2);
		keepAlive.put((byte) ((keepAliveL >>> 8) & 0xFF)).put((byte) ((keepAliveL >>> 0) & 0xFF));
		keepAlive.flip();

		lengthCounter = 10;
		if(message.get(PROTOCOL_NAME).equals("MQIsdp")) {
			lengthCounter += 2;
		}
		//log("client id:  " + message.get(CLIENT_ID).toString());
		ByteBuffer clientId = encodeUTF8((String)message.get(CLIENT_ID));
		lengthCounter += clientId.position();
		clientId.flip();
		
		//bPayload[0] =  (byte) ((Boolean) message.get(SESSION_PRESENT) ? 1 : 0);
		//bPayload[1] = 0x00;
		//System.out.println(message.toString() + String.format("%x", bType[0]) + String.format("%x", bLength[0]) + String.format("%x%x", bPayload[0],bPayload[1]));

		ByteBuffer type = ByteBuffer.wrap(bType);
		byte[] lengthByte = {0};
		ByteBuffer lengthBuffer =  ByteBuffer.wrap(lengthByte);
		ByteBuffer protocolLevelBuffer = ByteBuffer.wrap(protocolLevel);
		ByteBuffer connectFlagsBuffer = ByteBuffer.wrap(connectFlags);
		
		List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(7);
		buffers.add(0, type);
		buffers.add(1, lengthBuffer );
		buffers.add(2, protocolName);
		buffers.add(3, protocolLevelBuffer);
		buffers.add(4, connectFlagsBuffer);
		buffers.add(5, keepAlive);
		buffers.add(6, clientId);
		//ByteBuffer[] buffers = {type, length, protocolName, protocolLevelBuffer, connectFlagsBuffer,  keepAlive, clientId};

		if(message.containsKey(WILL)) {
			Map<Keyword, ?> will =  (Map<Keyword, ?>) message.get(WILL); 
			connectFlags[0] = (byte) (0x40 | connectFlags[0]);
			//log("set will qos: " + will.get(WILL_QOS));
			Byte willQos = Byte.parseByte(((Long) will.get(WILL_QOS)).toString());
			connectFlags[0] = (byte) ((willQos << 4) | connectFlags[0]);
			Boolean willRetain = (Boolean) will.get(WILL_RETAIN);
			connectFlags[0] = willRetain ? (byte) (0x20 | connectFlags[0]) : connectFlags[0];	
		}
		
		if(message.containsKey(USER_CREDENTIALS)) { 
			Map<Keyword, ?> userCredentials =  (Map<Keyword, ?>) message.get(USER_CREDENTIALS); 
			//log("set username: " + userCredentials.get(USER_NAME));
			connectFlags[0] = (byte) (0x80 | connectFlags[0]);
			ByteBuffer userName = encodeUTF8((String)userCredentials.get(USER_NAME));
			lengthCounter += userName.position();
			userName.flip();
			buffers.add(userName);
			
			if(userCredentials.containsKey(PASSWORD)) {
				//log("password set " + userCredentials.get(PASSWORD));
				connectFlags[0] = (byte) (0x40 | connectFlags[0]);
				ByteBuffer password = encodeUTF8((String)userCredentials.get(PASSWORD));
				//log("password: " + (String)userCredentials.get(PASSWORD) + " " + "length :" + userCredentials.get(PASSWORD).toString().length());
				lengthCounter += password.position();
				password.flip();
				buffers.add(password);			
			}
		}
		String s1 = String.format("%8s", Integer.toBinaryString(connectFlags[0] & 0xFF)).replace(' ', '0');
		log("connect flags: " + s1);
        buffers.set(1, calculateLenght(lengthCounter));
		log("length: " + lengthCounter);
		
		ByteBuffer[] ret = new ByteBuffer[buffers.size()];
		return buffers.toArray(ret);
	}
}
