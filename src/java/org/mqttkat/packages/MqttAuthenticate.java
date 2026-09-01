package org.mqttkat.packages;

import static clojure.lang.Keyword.intern;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.TreeMap;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MqttAuthenticate extends GenericMessage{

	private static final Logger log = LoggerFactory.getLogger(MqttAuthenticate.class);

	public static IPersistentMap decode(SelectionKey key) throws IOException {
		log.debug("AUTHENTICATE message");

		Map<Keyword, Object> m = new TreeMap<Keyword, Object>();
		m.put(PACKET_TYPE, intern("AUTHENTICATE"));
		m.put(CLIENT_KEY, key);

		return PersistentArrayMap.create(m);
	}



}
