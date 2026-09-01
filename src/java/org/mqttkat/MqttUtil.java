package org.mqttkat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class MqttUtil {
	protected static final String STRING_ENCODING = "UTF-8";

	public static String decodeUTF8(byte[] input, int offset) throws IOException 	{
		//short encodedLength = (short)((input[offset]<<8) | input[1+offset]);
		int encodedLength = twoBytesToInt(input[offset], input[1+offset]);
		String ret =  new String(Arrays.copyOfRange(input,(offset + 2),(offset + 2 + encodedLength)), STRING_ENCODING);
		//System.out.println("ret: " +  ret + " length: " + encodedLength + " string length: " + ret.length());
		return ret;
	}
	
	public static ByteBuffer encodeUTF8Buffer(String str) throws UnsupportedEncodingException {
		byte[] encodedStr = str.getBytes(StandardCharsets.UTF_8);
		//byte byte1 = (byte) ((encodedStr.length >>> 8) & 0xFF);
		//byte byte2 =  (byte) ((encodedStr.length >>> 0) & 0xFF); 
		ByteBuffer ret = ByteBuffer.allocate(encodedStr.length + 2);
		//log("string: " + str + " length: " + str.length());
		return ret.put((byte) ((encodedStr.length >>> 8) & 0xFF)).put((byte) (encodedStr.length & 0xFF)).put(encodedStr);
	}

	public static byte[] encodeUTF8Bytes(String str) throws UnsupportedEncodingException {
		int length = str.length();
		byte[] ret = new byte[length + 2];
		ret[0] = (byte) ((length >>> 8) & 0xFF);
		ret[1] = (byte) (length & 0xFF);
		byte[] encodedStr = str.getBytes(StandardCharsets.UTF_8);

		for(int i = 2; i < length+2; i++) {
			ret[i] = encodedStr[i-2];
		}
		
		return ret;
	}

	public static byte[] encodeUTF8Bytes2(String str) throws UnsupportedEncodingException {
		return str.getBytes(StandardCharsets.UTF_8);
	}

	
	public static List<Byte> encodeUTF8List(String str) throws UnsupportedEncodingException {
		byte[] encodedStr = str.getBytes(StandardCharsets.UTF_8);

		List<Byte> ret = new ArrayList<Byte>();
		ret.add(0, (byte) ((encodedStr.length >>> 8) & 0xFF));
		ret.add(1, (byte) (encodedStr.length & 0xFF));

		for(int i = 0; i < encodedStr.length; i++) {
			ret.add(encodedStr[i]);
		}
		
		return ret;
	}

	/**
	 * The largest body a four-byte remaining length can express — MQTT 3.1.1
	 * §2.2.3, and the protocol's own ceiling on a packet.
	 */
	public static final long MAX_REMAINING_LENGTH = 268435455L;

	/**
	 * Room for `needed` more bytes at `length`, growing the array if there is
	 * not any.
	 *
	 * The encoders build a packet body into a scratch array with
	 * bytes[length++]. That array was a fixed MESSAGE_LENGTH — 4096 — with no
	 * bounds check anywhere, so anything bigger threw
	 * ArrayIndexOutOfBoundsException out of encode: a payload over 4 KB, a
	 * SUBSCRIBE with enough filters, a SUBACK answering one. For a broker
	 * forwarding a publish that exception is caught upstream, which made it
	 * silent data loss rather than a crash. MESSAGE_LENGTH is now where these
	 * arrays start, not where they stop.
	 */
	public static byte[] fit(byte[] bytes, int length, int needed) {
		int required = length + needed;
		if (required <= bytes.length) {
			return bytes;
		}
		return Arrays.copyOf(bytes, Math.max(required, bytes.length * 2));
	}

	/**
	 * @throws IllegalArgumentException past MAX_REMAINING_LENGTH. The loop
	 *         below stops at four bytes, so an over-long packet would otherwise
	 *         be handed a truncated length — and a wrong remaining length does
	 *         not corrupt one packet, it desynchronises every packet after it
	 *         on that connection.
	 */
	public static byte[] calculateLength(long number) {
		if (number > MAX_REMAINING_LENGTH) {
			throw new IllegalArgumentException(
					"packet body of " + number + " bytes exceeds the MQTT maximum of " + MAX_REMAINING_LENGTH);
		}
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

	public static int qos(int qos) {
		return (qos >> 1);
	}
	
	public static Long twoBytesToLong(byte b1, byte b2) {
		//log("hoog: " +  b1 + "  laag: " + b2);
		Long ret = Short.toUnsignedLong((short) (b1<<8)) + Short.toUnsignedLong((short)(b2 & 0xFF));
		//log("ret: " +  ret);
		return ret;
	}
	
	/**
	 * How many bytes decodeUTF8 consumed at `offset`: the two-byte length
	 * prefix plus the bytes it counts.
	 *
	 * Advance past a decoded string with this and never with String.length().
	 * MQTT strings are UTF-8 (3.1.1 §1.5.3), so the character count and the
	 * byte count are the same only for ASCII — and every decoder here used the
	 * character count. For a topic like "café/über" that left the offset two
	 * bytes short and put the tail of the topic on the front of the payload,
	 * silently, for anyone whose topics are not plain ASCII.
	 */
	public static int encodedUTF8Length(byte[] input, int offset) {
		return 2 + twoBytesToInt(input[offset], input[1 + offset]);
	}

	public static int twoBytesToInt(byte b1, byte b2) {
		//log("hoog: " + b1 + "  " +  (b1<<8) + "  laag: " + b2);
		int ret = Short.toUnsignedInt((short) (b1<<8)) + Short.toUnsignedInt((short) (b2 & 0xFF));
		return ret;
	}
}
