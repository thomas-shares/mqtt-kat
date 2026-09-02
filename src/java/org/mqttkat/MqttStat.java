package org.mqttkat;

import java.util.concurrent.atomic.LongAdder;

/**
 * Broker-wide counters, static and for the life of the JVM.
 *
 * LongAdder, not AtomicLong: every one of these is incremented once per packet
 * per subscriber, so a 150-way fan-out of 300k publishes is 90M increments from
 * 150 threads. AtomicLong makes that a CAS fight over two cache lines. LongAdder
 * spreads it over per-thread cells and pays only on the read, which happens once
 * per stats interval.
 *
 * The outbound side is counted in three places on purpose, because a packet
 * the broker accepts is not a packet the client receives:
 *
 * <ul>
 * <li>{@code sentMessages} — queued for delivery. The fan-out has decided this
 *     client should get it and put it on the connection's outbound queue.
 *     Nothing has touched a socket yet, and the queue is unbounded.</li>
 * <li>{@code writtenMessages} — written to the socket, in full.</li>
 * <li>{@code discardedMessages} — still queued when the connection's writer
 *     stopped, so it never went anywhere.</li>
 * <li>{@code droppedMessages} — never queued at all: a QoS 0 publish refused
 *     because the subscriber was already too far behind.</li>
 * </ul>
 *
 * sent - written - discarded is therefore the current backlog: work the broker
 * has promised and not yet done. Reporting only the first of the three makes an
 * overloaded broker look like a fast one — under a 150-subscriber fan-out it
 * will happily count 45M "sent" while the clients receive 1M.
 */
public class MqttStat {
	/** Queued for delivery. See the class comment: not the same as written. */
	public static LongAdder sentMessages = new LongAdder();
	public static LongAdder sentBytes = new LongAdder();

	/** Actually written to a socket, in full. */
	public static LongAdder writtenMessages = new LongAdder();
	public static LongAdder writtenBytes = new LongAdder();

	/** Queued, then dropped unwritten because the connection went away. */
	public static LongAdder discardedMessages = new LongAdder();

	/** Refused at the queue: a QoS 0 publish to a subscriber that is too far behind. */
	public static LongAdder droppedMessages = new LongAdder();

	/**
	 * Times a publisher's socket was stopped because a subscriber it feeds
	 * could not keep up. Back-pressure working, not an error — but it is how
	 * you tell throttling apart from a broker that is simply idle.
	 */
	public static LongAdder publisherPauses = new LongAdder();

	public static LongAdder receivedMessages = new LongAdder();
	public static LongAdder receivedBytes = new LongAdder();
}
