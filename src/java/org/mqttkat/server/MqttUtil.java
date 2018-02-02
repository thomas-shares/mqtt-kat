package org.mqttkat.server;

import static clojure.lang.Keyword.intern;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import clojure.lang.Keyword;

public abstract class MqttUtil {
	protected static final String STRING_ENCODING = "UTF-8";

	public static String decodeUTF8(byte[] input, int offset) throws IOException 	{
		short encodedLength = (short)((input[offset]<<8) | input[1+offset]);
		String ret =  new String(Arrays.copyOfRange(input,offset + 2,offset + 2 + encodedLength), STRING_ENCODING);
		//System.out.println("ret: " +  ret);
		return ret;
	}
	
	public static ByteBuffer encodeUTF8(String str) throws UnsupportedEncodingException {
		byte[] encodedStr = str.getBytes("UTF-8");
		//byte byte1 = (byte) ((encodedStr.length >>> 8) & 0xFF);
		//byte byte2 =  (byte) ((encodedStr.length >>> 0) & 0xFF); 
		ByteBuffer ret = ByteBuffer.allocate(encodedStr.length + 2);

		return ret.put((byte) ((encodedStr.length >>> 8) & 0xFF)).put((byte) ((encodedStr.length >>> 0) & 0xFF)).put(encodedStr);
	}

	public static byte[] calculateLenght(long number) {
		int numBytes = 0;
		long no = number;
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		// Encode the remaining length fields in the four bytes
		do {
			byte digit = (byte)(no % 128);
			no = no / 128;
			if (no > 0) {
				digit |= 0x80;
			}
			bos.write(digit);
			numBytes++;
		} while ( (no > 0) && (numBytes<4) );

		return bos.toByteArray();
	}

	public static Keyword qos(int qos) {
		byte shiftedByte = (byte) (qos >> 3);
		Keyword ret = null;
		switch(shiftedByte) {
			case 0: ret = intern("0");
			break;
			case 1: ret = intern("1");
			break;
			case 2: ret = intern("2");
			break;
			default: ret = intern("invalid");
		}
		return ret;
	}
}
