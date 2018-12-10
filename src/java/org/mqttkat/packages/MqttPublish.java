package org.mqttkat.packages;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static clojure.lang.Keyword.intern;
import static org.mqttkat.MqttUtil.*;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;

public class MqttPublish extends GenericMessage {

	public static IPersistentMap decode(SelectionKey key, byte flags, byte[] remainAndPayload) throws IOException {
		//System.out.println("PUBLISH message...");
		Map<Object, Object> m = new TreeMap<Object, Object>();
		m.put(PACKET_TYPE, intern("PUBLISH"));
		m.put(FLAGS, flags);
		m.put(CLIENT_KEY, key);

		m.put(DUPLICATE, (flags & 0x08) == 0x08);
		m.put(MSG_QOS, qos((flags & 0x06)));
		System.out.println(flags & 0x06);
		m.put(RETAIN, (flags & 0x01) == 0x01);
		String topic = decodeUTF8(remainAndPayload, 0);
		m.put(TOPIC, topic);
		//System.out.println("index: " + (topic.length() + 2) + " length: " + remainAndPayload.length + " topic: " + topic);
		m.put(PAYLOAD, Arrays.copyOfRange(remainAndPayload, topic.length() + 2, remainAndPayload.length));

		return PersistentArrayMap.create(m);
	}

	public static ByteBuffer[] encode(Map<?, ?> message) throws UnsupportedEncodingException {
		//System.out.println("PUBLISHING MESSAGE TO CLIENT: " + message.toString());
		byte[] bType = {(byte)(MESSAGE_PUBLISH << 4)};
		//TODO read flags from map
		bType[0] =  (byte) (bType[0] & 0xf0);
		
		ByteBuffer topic = encodeUTF8((String)message.get(TOPIC));
		topic.flip();
		int topicSize =  topic.remaining();
		byte[] bPayload = (byte[])message.get(PAYLOAD);
				
		byte[] bLength = calculateLenght(topicSize + bPayload.length);

		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer length = ByteBuffer.wrap(bLength);
		ByteBuffer payload =  ByteBuffer.wrap(bPayload);

		return new ByteBuffer[]{type, length, topic, payload};	}
}