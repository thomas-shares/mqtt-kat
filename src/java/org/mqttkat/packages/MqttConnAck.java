package org.mqttkat.packages;

import java.nio.ByteBuffer;
import java.util.Map;
import org.mqttkat.server.MqttUtil;

public class MqttConnAck extends GenericMessage{

	public static ByteBuffer[] encode(Map<?, ?> message) {
		byte[] bType = {(MESSAGE_CONNACK << 4)};
		byte[] bLength = MqttUtil.calculateLenght(2);
		byte[] bPayload = new byte[2];

		//System.out.println(message.get(SESSION_PRESENT));

		bPayload[0] =  (byte) ((Boolean) message.get(SESSION_PRESENT) ? 1 : 0);
		bPayload[1] = 0x00;
		//System.out.println(message.toString() + String.format("%x", bType[0]) + String.format("%x", bLength[0]) + String.format("%x%x", bPayload[0],bPayload[1]));

		ByteBuffer type = ByteBuffer.wrap(bType);
		ByteBuffer length = ByteBuffer.wrap(bLength);
		ByteBuffer payload = ByteBuffer.wrap(bPayload);

		return new ByteBuffer[]{type, length, payload};
	}
}