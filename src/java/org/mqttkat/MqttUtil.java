package org.mqttkat;

import static org.mqttkat.MqttUtil.log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public abstract class MqttUtil {
	protected static final String STRING_ENCODING = "UTF-8";

	public static String decodeUTF8(byte[] input, int offset) throws IOException 	{
		short encodedLength = (short)((input[offset]<<8) | input[1+offset]);
		String ret =  new String(Arrays.copyOfRange(input,(offset + 2),(offset + 2 + encodedLength)), STRING_ENCODING);
		//System.out.println("ret: " +  ret + " length: " + encodedLength + " string length: " + ret.length());
		return ret;
	}
	
	public static ByteBuffer encodeUTF8Buffer(String str) throws UnsupportedEncodingException {
		byte[] encodedStr = str.getBytes("UTF-8");
		//byte byte1 = (byte) ((encodedStr.length >>> 8) & 0xFF);
		//byte byte2 =  (byte) ((encodedStr.length >>> 0) & 0xFF); 
		ByteBuffer ret = ByteBuffer.allocate(encodedStr.length + 2);
		//log("string: " + str + " length: " + str.length());
		return ret.put((byte) ((encodedStr.length >>> 8) & 0xFF)).put((byte) ((encodedStr.length >>> 0) & 0xFF)).put(encodedStr);
	}

	public static byte[] encodeUTF8Bytes(String str) throws UnsupportedEncodingException {
		int length = str.length();
		byte[] ret = new byte[length + 2];
		ret[0] = (byte) ((length >>> 8) & 0xFF);
		ret[1] = (byte) (length & 0xFF);
		byte[] encodedStr = str.getBytes("UTF-8");

		for(int i = 2; i < length+2; i++) {
			ret[i] = encodedStr[i-2];
		}
		
		return ret;
	}

	public static ByteBuffer calculateLenght(long number) {
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
			String s1 = String.format("%8s", Integer.toBinaryString(digit & 0xFF)).replace(' ', '0');
			log("length byte: " + s1);
			bos.write(digit);
			numBytes++;
		} while ( (no > 0) && (numBytes<4) );

		return ByteBuffer.wrap(bos.toByteArray());
	}

	public static int qos(int qos) {
		return (qos >> 1);
	}
	
	public static void log(String str) {
		System.out.println(str);
	}
	
	public static Integer twoBytesToInt(byte b1, byte b2) {
		log("hoog: " +  b1 + "  laag: " + b2);
		Integer ret = Short.toUnsignedInt((short) (b1<<8)) + Short.toUnsignedInt((short)(b2 & 0xFF));
		log("ret: " +  ret);
		return ret;
	}
}
