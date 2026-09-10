package org.mqttkat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * How many messages have been published on each topic.
 *
 * The console can already list retained topics, because a retained message is
 * something the broker is holding anyway. It could not say which topics were
 * actually busy: a publish that is not retained is forwarded and forgotten, and
 * keeping the payloads to answer that would make the broker a store rather than
 * a console. Counting the names costs neither — a topic string the broker
 * already has, and a counter.
 *
 * ConcurrentHashMap of LongAdder rather than anything guarded: this is on the
 * publish path, once per message, and the lesson from the outbound state was
 * that a shared structure everyone swaps is the most expensive thing a broker
 * can own. Here the increment is lock-free and touches one striped counter.
 *
 * Bounded, because topics are not. A client may publish to a new topic every
 * message — MQTT has no registration step — so an unbounded map is a leak with
 * a publisher's name on it. Past the cap new topics are counted in
 * {@code untracked} instead, so the console can say the list is partial rather
 * than quietly implying it is everything.
 */
public final class TopicStats {

	private TopicStats() {}

	/** -Dmqttkat.topicStatsMax=N, 0 to disable tracking entirely. */
	private static final int MAX_TOPICS =
			Integer.getInteger("mqttkat.topicStatsMax", 2000).intValue();

	private static final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

	/** Publishes on topics past the cap — enough to say "and more", not which. */
	public static final LongAdder untracked = new LongAdder();

	public static void record(String topic) {
		if (topic == null || MAX_TOPICS <= 0) {
			return;
		}
		// Not the $ hierarchy. Those are the broker reporting on itself, they
		// are published on a timer whether anything is happening or not, and
		// the console lists them in their own table already. Counted here they
		// would fill the busiest-topics list on an idle broker — sixty-eight
		// rows of the broker talking to itself, where the honest answer is
		// that nothing has been published.
		if (!topic.isEmpty() && topic.charAt(0) == '$') {
			return;
		}
		// get() first: the common case is a topic already known, and it avoids
		// both the lambda and computeIfAbsent's bin lock on that path.
		LongAdder counter = counts.get(topic);
		if (counter == null) {
			if (counts.size() >= MAX_TOPICS) {
				untracked.increment();
				return;
			}
			counter = counts.computeIfAbsent(topic, t -> new LongAdder());
		}
		counter.increment();
	}

	/** Topic to total publishes, as of now. */
	public static Map<String, Long> snapshot() {
		Map<String, Long> out = new HashMap<>(counts.size() * 2);
		counts.forEach((topic, counter) -> out.put(topic, Long.valueOf(counter.sum())));
		return out;
	}

	/** How many distinct topics are being tracked. */
	public static int size() {
		return counts.size();
	}

	/** Whether the cap has been reached, so the list is only part of the story. */
	public static boolean isTruncated() {
		return counts.size() >= MAX_TOPICS;
	}

	public static void clear() {
		counts.clear();
		untracked.reset();
	}
}
