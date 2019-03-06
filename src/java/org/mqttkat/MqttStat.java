package org.mqttkat;

import java.util.concurrent.atomic.AtomicLong;

public class MqttStat {
	
	public static AtomicLong sentMessages = new AtomicLong();
	public static AtomicLong sentBytes = new AtomicLong();
	public static AtomicLong receivedMessages = new AtomicLong();
	public static AtomicLong receivedBytes = new AtomicLong();
	

}
