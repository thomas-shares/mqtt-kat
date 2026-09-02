# Thoughts and ramblings...

In this file will go my thoughts and ramblings about this project and what I have done and what I might do next.

## 20260902

### The packet identifier pool had a countdown in it

QoS 1 measurements looked odd — `queued` tracking `received` one for one, where
QoS 0 gave a clean 150:1. That is not the fan-out disappearing, it is the
acknowledgement traffic closing the books: each publish costs 1 PUBLISH plus
150 PUBACKs inbound, and 1 PUBACK plus 150 PUBLISHes outbound. Both directions
are 151 × P. Solving each independently from the totals gave P = 80,198 and
P = 80,196, so only 80,200 of 300,000 publishes had got in — the run measured
27% of itself.

Identifiers came from one global core.async channel holding 1024 values, taken
with a blocking `<!!`. Four things wrong with that, and the first two are
permanent hangs rather than slowdowns:

* **It leaked.** `put-packet-identifier` was called from exactly two places,
  PUBACK and PUBCOMP. Nothing returned identifiers when a client disconnected,
  and `*outbound*` deliberately keeps unacknowledged messages for redelivery.
  So every ungraceful disconnect with messages in flight burned its identifiers
  for good. There were 1024. After enough of those the take blocks forever and
  QoS 1/2 delivery stops broker-wide, silently. A long-running broker did not
  have a throughput problem here, it had a countdown.
* **Any client could break it.** PUBACK returned whatever identifier the client
  sent, unchecked. An unsolicited one overfilled a channel sized exactly 1024,
  so `>!!` blocked that connection's reader thread for good; a duplicate put a
  live identifier back into circulation for the next delivery to reuse.
* **It was global**, where §2.3.1 scopes identifiers to a connection: 1024
  shared out rather than 65535 each.
* **The take blocked the fan-out thread**, which deadlocks a client that both
  publishes and subscribes — it waits on an identifier that only its own
  unread PUBACKs could release.

Raising the pool was worth testing before redesigning anything. 1024 → 16384
gave **1.25×**, not the 16× a window-limited pipeline would predict. So the
pool was worth about 21% and something else was binding. Adding both
directions: QoS 0 ran at 474,803 packets/s and QoS 1 with the big pool at
475,869 — 0.2% apart. That looked like a hard per-packet ceiling, and it was
not; see below.

`*outbound*` already recorded what was in flight, keyed by client-id, already
outliving the connection for redelivery. So it is the allocator now: one place
that knows what is outstanding, instead of a pool that has to be kept in step
with it. A wrapping counter is enough because the in-flight window is far below
65535 and cannot lap a live identifier. `release-packet-identifier!` returns
the message it retired, or nil for an identifier never issued — which is the
whole defence against a client corrupting the space.

### Two wrong answers before the right one

The window needed a policy for "full", and I got it wrong twice, both times
measurably.

**Disconnect the subscriber.** Defensible on paper — a client that far behind
is not reading — and a disaster in practice: 408 disconnects, every subscriber
killed, delivery down from 15M to 15,028. Real brokers queue past the in-flight
window. They do not terminate.

**Queue it, drop when the queue is full.** Better, and then much worse than it
should have been: 241 publishes/s and 12.6 second latencies. That was my own
bug, not the design's — `(vec (rest %))` to drop the head of a 4096-element
vector, inside a `swap!` on a contended atom, once per acknowledgement.
`PersistentQueue` with `peek`/`pop` made it 2,863 publishes/s, which is 1.86×
the best the old pool ever managed, and incidentally 563,612 packets/s — so
that 475k "ceiling" was partly the pool after all.

But it dropped 11.9 million QoS 1 messages. QoS 1 is at-least-once; dropping is
not a policy, it is a broken promise. The old blocking pool never dropped
anything precisely *because* it blocked: a global semaphore is back-pressure
all the way to the publisher, arrived at by accident.

### Back-pressure where it belongs: the publisher's socket

Under overload something must give — block, drop, or disconnect. Dropping
breaks the guarantee and disconnecting is worse, so it has to be blocking, and
blocking a broker thread is not available: two clients that each publish to a
topic the other subscribes to would each hold a thread waiting on the other's
window, and neither would ever process the acknowledgements that release it.
Not a slowdown, a cycle.

What is available is refusing to read. `Connection.pauseReading` clears
`OP_READ`, so the bytes stay in the kernel receive buffer; that fills, the
receive window closes, and the publisher blocks in its own `write`. TCP does
the work, and no thread of ours is holding anything.

* A subscriber whose pending queue passes `pause-threshold` (512) pauses the
  publisher feeding it, and remembers it.
* Every acknowledgement drains one pending message and, below
  `resume-threshold` (128), releases everyone waiting. Hysteresis, or the
  interest ops flap once per packet.
* A connection never pauses itself — a client subscribed to a topic it
  publishes to would otherwise stop reading the very acknowledgements that
  would free it.
* `close()` calls `drained()`, so nobody is left throttled on a subscriber
  that has gone.
* `pending-limit` (4096) survives as a memory backstop. Under back-pressure it
  should never fire, and in the run below it did not.

The gap between 512 and 4096 is deliberate: clearing `OP_READ` stops new bytes
arriving, but the publisher's reader thread still has whatever was already
framed to work through, and every one of those publishes fans out 150 ways.
That gap is the headroom for the overshoot.

`MqttStat.publisherPauses` counts it, and it shows in the stats line as
`:throttled`. Without it there is no way to tell a throttled broker from an
idle one.

### What it bought

Same 150×150 QoS 1 config as the run that started this:

| | pool of 1024 | per-client ids + back-pressure |
|---|---|---|
| publish rate | 1,238/s | **1,671/s** |
| total packets/s | 373,916 | **547,061** |
| publishes ingested | 80,200 | **113,616** |
| messages delivered | ~12.1M | **16.2M** |
| dropped | 0 | **0** |
| average latency | — | 212 ms |
| throttle events | n/a | 74,239 |

35% more publishes, 46% more packets, nothing dropped, and the four hangs gone.
`:throttled` climbing steadily is what back-pressure looks like when it is
working: the publishers were held to what the subscribers could actually take.

### A test that was worse than no test

Two of my own tests misbehaved and both were worth the time to fix properly.

The QoS 0 isolation assertion was `>= 90%` of messages reaching a healthy
subscriber, which failed 4 runs in 8 at 75-83%. The threshold was invented, and
it was wrong: with the limit set to 20 for the test, a healthy subscriber that
pauses for a moment crosses it too. It now has the stalled subscriber saturated
first, then a small paced burst to a subscriber that joins afterwards — where
"all of them" is a real assertion rather than a guess.

The QoS 1 back-pressure test published 20,000 large messages from a future it
then cancelled, and left the broker still fanning them out into the next
namespace, where `flow-test`'s reconnect started failing intermittently. Enough
to cross the threshold is enough. It publishes 3072 small ones now and waits
for the broker to settle before returning. Six clean runs of the full suite
afterwards, from four failures in eight before.

Neither was a bug in the broker. Both would have been blamed on one.

## 20260901

### The latency question, answered: Nagle

The 2020 note below says "latency is rather high, to be investigated". It was
TCP, not the broker. No socket in the codebase set `TCP_NODELAY`, so Nagle's
algorithm was holding small writes back until the previous one was
acknowledged, and the peer's delayed ACK (40ms on Linux) supplied that
acknowledgement on a timer. Any exchange needing more than one packet in each
direction paid 40ms.

The performance simulation now reports per-QoS round trips, which made the
shape obvious — a hard floor with almost no variance, and only on the QoS
levels that need a reply:

| median round trip | Nagle (before) | TCP_NODELAY |
|-------------------|----------------|-------------|
| QoS 0 (1 packet)  | 0.55 ms        | 0.42 ms     |
| QoS 1 (2 packets) | 41.43 ms       | 0.47 ms     |
| QoS 2 (4 packets) | 41.65 ms       | 0.82 ms     |

The whole 1000-event simulation went from 21.96s to 0.95s, and throughput over
the same work from 137 to 3117 messages/second. `TCP_NODELAY` is now set on the
accepted sockets in `MqttServer.handleAccept` and on the client's socket in
`MqttClient`; both ends need it, or the acknowledgement half still waits.

That probably also explains the mqttloader comparison in the 2020 entry —
average latency 116ms against Mosquitto's 71ms. Mosquitto sets `TCP_NODELAY`.

### The threading model, rebuilt on virtual threads

Working through the consequences of the ordering race, the I/O layer is now
one connection = two virtual threads, instead of shared platform-thread pools.

Before: the `server-loop` thread read bytes, framed, decoded and then handed
each packet to a 4-thread pool (`prefix1..4`); replies and fan-out went through
a separate 16-thread pool (`senders-1..16`). Four things followed from that,
all of them fixed together because they are the same structural problem:

* **A packet split across two TCP reads killed the broker.** `handleRead`
  decoded straight out of an 8 KB buffer with no reassembly, so a partial
  packet raised `BufferUnderflowException` — a RuntimeException, caught by
  neither `handleRead` (catches IOException) nor `run()` — which escaped and
  terminated `server-loop`, taking every connection with it. This is the
  exception the 20180211 entry below saw under JMeter.
* **Partial writes were silently dropped.** `MqttSender` called `ch.write(buf)`
  and discarded the return value; a non-blocking write returns short when the
  socket buffer is full, and the remainder was never sent.
* **Order was lost twice** — once submitting each packet to the handler pool,
  and again submitting each outgoing packet to the sender pool, so a PUBACK
  could overtake the PUBLISH it acknowledged.
* **Decoding ran on the selector thread**, so one slow decode stalled reads for
  every connection.

Now the selector thread only reads bytes and hands them to the connection they
came from. Each connection has a reader thread that reassembles, decodes and
runs the handler inline, in order, and a writer thread that sends queued
packets one at a time, looping until each is fully written. Virtual threads are
what make that affordable: 200 connections add 400 threads and no measurable
platform threads — the broker still shows exactly one, `server-loop`.

Fan-out also stops queueing through 16 platform threads: a publish to M
subscribers now proceeds on M independent writer threads, and a client that
stops reading parks its own writer instead of occupying a shared one.

Latency is unchanged where it was already good and slightly better at the tail
(max round trip 2.2ms, against 4.7ms through the shared pool).

Known limitation of the new design: a connection's outbound queue is unbounded,
so a client that never reads accumulates packets in memory until it is
disconnected by keep-alive. That wants a bounded queue and a drop-or-disconnect
policy.

### A race this uncovered: packets are handled out of order

Removing the 40ms delay exposed something that had been hiding behind it.
`MqttHandler.handle` submits every incoming packet to a shared thread pool as
an independent task, so two packets **from the same connection** can be handled
concurrently and out of order. MQTT requires a client's packets to be processed
in the order they were sent.

It showed up as `retain-test` failing about half the time: the test publishes a
retained message and then subscribes, and the subscription was sometimes
registered first — so the client got a live delivery with `retain? false`
instead of the retained copy. Nagle had been serialising the two packets by
accident.

The retain tests now wait for the broker to have stored the message before
subscribing, so they test retention rather than scheduling. The underlying
ordering bug is still there and wants a fix of its own: dispatch needs to be
serialised per connection, not per packet.

### The load test that looked like a broker problem

`mqttloader` against the broker, 150 publishers and 150 subscribers all on one
topic, 2000 messages each, QoS 0 both ways:

```
Number of received messages: 1121529
Maximum latency [ms]: 45657.837
Average latency [ms]: 9817.411
```

Ten seconds of average latency looked bad enough to be a regression. It is
arithmetic. 150 publishers × 2000 messages is 300k publishes, and every one
fans out to 150 subscribers: **45 million deliveries**, about 10 GB on the
wire, asked for inside a 60-second window. The 2020 run in the entry below was
15×15×200 — 45,000 deliveries. This config asks for a thousand times the work.

What it is not:

* **Not GC.** 69 young collections and 5.4s of GC across two whole runs. The
  heap grew to 7.4 GB but G1 never struggled, and a forced full collection on
  an idle broker leaves a 12 MB live set — nothing leaks.
* **Not a starved write path.** Thread dumps in the collapse window put ~149 of
  the 150 subscriber writer threads in `parkNanos` — the `Thread.sleep(1)` in
  `writeFully` — and one or two in `write0`. That reads like a broker that
  cannot write, until you look at the sockets:

```
sockets: 300   nonzero Send-Q: 97   total: 118,803,938   max: 3,073,634
```

97 subscriber sockets each holding 2-3 MB the client had not read. When the
send buffer is full there is nothing left for the broker to do.

One conclusion that looked obvious and was wrong: that the 43.8M undelivered
messages never went out. They did. Once the broker could count writes properly
(below) it turned out to have written **all 45,000,761 packets at ~910k/s**,
finishing three seconds before mqttloader disconnected its subscribers. TCP
flow control means `write()` only returns non-zero if the peer's kernel took
the bytes, so those 10 GB genuinely crossed the socket. mqttloader read them
and surfaced 2.4% through Paho callbacks before its window closed.

At this fan-out the benchmark measures the load generator.

### Counting what the broker promises, not only what it accepts

`MqttStat.sentMessages` was incremented in `sendMessageBuffer` immediately
after `connection.write(...)`, which is an `offer()` onto an unbounded queue.
Nothing had touched a socket. The broker's own stats would have reported 45M
"sent" while a million arrived — exactly the kind of number that sends you
hunting in the wrong place.

The outbound side is counted in four places now, because a packet the broker
accepts is not a packet the client receives:

| counter | meaning |
|---|---|
| `sentMessages` | queued for a client; nothing on a socket yet |
| `writtenMessages` | written to the socket, in full |
| `discardedMessages` | queued, then abandoned when the connection died |
| `droppedMessages` | never queued: refused because the client is behind |

`sent — written — discarded` is the live backlog. `dropped` is deliberately
separate: it is the design working, whereas a non-zero `discarded` is a bug.

`mqttkat.util/info` reports all of it, on real elapsed time rather than the
nominal ten seconds, and warns when the backlog *grows* over an interval -
threshold-free, because growth is itself the failure condition. It counts
connected clients apart from parked `clean-session? false` sessions, which live
in the same `*clients*` map and made the old count only ever go up.

The first thing it showed was a number that had never been visible:

```
queued/s 2,318,397 / received/s 15,467.5 = 149.9
```

The fan-out ratio is exactly 150, as it should be — but the broker was only
ingesting **15,000 publishes/second**, while mqttloader believed it had
published 300k in three seconds. The gap sat in kernel buffers and in
`Connection.inbound`. And the ratio that mattered:

| phase | queued/s | written/s |
|---|---|---|
| burst, readers busy | 2,318,397 | 438,502 |
| after publishing stops | — | 1,099,313 |

The broker wrote 2.5× faster with *less* work to do. It was never
write-limited. Fan-out runs inline on the publisher's reader thread, so 150
reader threads enqueueing at 2.3M/s compete with 150 writer threads for 23
carriers — and the readers always win, because they never block. Nothing pushed
back, so the broker spent its CPU accepting work instead of doing it, and built
a 31-million-packet promise it then took 30 seconds to honour.

### Back-pressure, and LongAdder

Two changes, both falling straight out of that.

**LongAdder.** Every `MqttStat` counter is incremented once per packet per
subscriber: a 150-way fan-out of 300k publishes is 90M increments from 150
threads, a CAS fight over two cache lines. `LongAdder` spreads it over
per-thread cells and pays only on the read, once per stats interval. The 15k/s
ingest ceiling was real rather than an artifact of the measurement — it
doubled.

**A bounded outbound queue.** `Connection` refuses QoS 0 publishes past
`maxQueued` (default 10,000, `-Dmqttkat.maxQueuedMessages`, 0 for the old
unbounded behaviour). The constraint that shapes the design: **only QoS 0 may
be dropped.** MQTT 3.1.1 §4.3.1 makes it at-most-once, so dropping degrades a
subscriber's feed; dropping a CONNACK, SUBACK, PUBACK or a QoS 1/2 PUBLISH
breaks the protocol instead. QoS 0 fan-out goes through its own
`send-buffer-droppable`; everything else is queued unconditionally.

One detail worth keeping: the drop check happens *before* `buffer.duplicate()`.
Duplicating and then discarding was 40M ByteBuffers of pure garbage, and most
of the GC.

| | unbounded | bounded @ 10k |
|---|---|---|
| publish ingest | ~15,000/s | 30,003/s |
| subscriber throughput | 19,887/s | 98,904/s |
| average latency | 12,783 ms | 2,645 ms |
| max latency | 53,264 ms | 7,839 ms |
| peak backlog | 32,279,864 | 0 |
| GC time | ~2.7 s | 0.65 s |

The backlog never forms now — the fan-out refuses at the limit rather than
queueing and draining later. The trade is honest: mqttloader counts 791,228
messages against 1,093,785, so ~28% fewer delivered for 5× the throughput, a
fifth of the latency and a twentieth of the memory. The 40.5M drops were QoS 0
publishes to subscribers that were never going to consume them.

`backpressure_test.clj` covers it: a raw socket that subscribes and then never
reads, plus enough volume to overrun the broker's ~2.5 MB socket send buffer.
Small messages never get there — 2000 of them are 70 KB and every write
succeeds, which is why the first version of the test found nothing.

### The 4 KB ceiling on every packet the broker could send

Every encoder built its packet body into `byte[] bytes = new byte[MESSAGE_LENGTH]`
— 4096 — and wrote into it with `bytes[length++]`, with no bounds check
anywhere. Anything larger threw `ArrayIndexOutOfBoundsException` out of
`encode`. Five of them did it: `MqttPublish`, `MqttConnect`, `MqttSubscribe`,
`MqttUnsubscribe`, `MqttSubAck`.

For `MqttPublish` that is worse than a crash. `Connection.dispatch` catches
Throwable, so a publish over 4 KB was logged and then vanished: the publisher
was told nothing, the subscriber simply never heard, and the broker carried on.
MQTT's own limit is the 268,435,455 bytes a four-byte remaining length can
express, so we were three orders of magnitude short of the spec and silent
about it.

`MESSAGE_LENGTH` is now where those arrays *start* rather than where they stop.
`MqttUtil.fit(bytes, length, needed)` grows the scratch array when a
variable-length field will not fit, and each encoder allocates its final
`ByteBuffer` at the end, from the length it actually produced, instead of
guessing 4096 up front.

`calculateLength` now throws past `MAX_REMAINING_LENGTH` instead of silently
returning a truncated varint. That mattered more than it looks: a wrong
remaining length does not corrupt one packet, it desynchronises every packet
after it on that connection.

`flow-test` covers 4096, 4097, 16384 and 100000-byte payloads round-tripping
intact — 4096/4097 for the boundary, 100000 because it needs a three-byte
remaining length.

### The broker only really worked in English

Found while testing the encoder fix. Every decoder advanced past a decoded
string with `offset += someString.length() + 2` — the *character* count of the
String it had just built, not the UTF-8 byte count it had actually read off the
wire. MQTT strings are UTF-8 (3.1.1 §1.5.3), so those two numbers agree only
for ASCII.

Eight sites had it: the topic in `MqttPublish`, the filters in `MqttSubscribe`
and `MqttUnsubscribe`, and the protocol name, client id, will topic, will
message and user name in `MqttConnect`. For anything outside ASCII the offset
landed short and the tail of the string was handed to whatever came next —
the front of the payload, the QoS byte of the next filter, the protocol
version after the client id:

```
topic=plain/ascii   chars=11 utf8bytes=11  ->  payload="PAYLOAD-START"        OK
topic=café/über     chars=9  utf8bytes=11  ->  payload="erPAYLOAD-START"      CORRUPT
topic=日本語/topic    chars=9  utf8bytes=15  ->  payload="/topicPAYLOAD-START"  CORRUPT
```

Silent, and only for people whose topics are not English — which is the worst
shape a bug can have. It also compounds: a publish is decoded once by the
broker and once again by the subscribing client, so the corruption arrives
doubled.

`MqttUtil.encodedUTF8Length(input, offset)` now reports what `decodeUTF8`
consumed — the two-byte prefix plus the bytes it counts — and all eight sites
use it. The point of the helper is that it is the only way to advance, rather
than eight arithmetic expressions each of which has to be right.

`flow-test` covers accented, Japanese and emoji topics end to end, plus a
SUBSCRIBE carrying several UTF-8 filters, where the per-topic error compounds
inside the decode loop. Reverting the one line in `MqttPublish` makes the ASCII
case pass and the other three fail, which is the check worth having.

### Found on the way, not fixed

* **A keep-alive timer fires against a parked session.** `keep-alive-test` logs
  a `ClassCastException: String cannot be cast to SelectionKey` out of
  `check-timer` — a timer outliving the re-keying in `remove-client!`. Caught
  and logged, so harmless today.
* **`writeFully` still polls.** `Thread.sleep(1)` against a non-blocking
  channel is the wrong shape with virtual threads; a blocking channel parks on
  the JDK poller instead, with no 1 ms granularity. It was not the bottleneck,
  so it is still there.
* **`Connection.inbound` is unbounded too.** The selector thread offers chunks
  with no limit, so a slow reader means bytes pile up in heap rather than
  applying TCP back-pressure to the publisher. Only 67 MB at this scale.
* **53 of 150 subscriber sockets had an empty Send-Q** while the rest were
  saturated. With uniform fan-out to one topic they should look alike. Never
  explained.

## 20201014

Thanks to jocatelo I picked this project up again. He has provided me with quite a few PR's and that got me going again as well. Thinks have been cleaned up and several bugs removed and all the tests now pass!!! Woohoooo. I also just ran an MQTT load generator aginst the server:

```
./mqttloader -b tcp://127.0.0.1:1883 -v 3 -p 15 -s 15 -m 200

Measurement started: 2020-10-14 14:18:10.908 CEST
Measurement ended: 2020-10-14 14:18:16.297 CEST

-----Publisher-----
Maximum throughput[msg/s]: 3000
Average throughput[msg/s]: 3000.00
Number of published messages: 3000
Per second throughput[msg/s]: 3000

-----Subscriber-----
Maximum throughput[msg/s]: 45000
Average throughput[msg/s]: 45000.00
Number of received messages: 45000
Per second throughput[msg/s]: 45000
Maximum latency[ms]: 231
Average latency[ms]: 116.81
```
And when I run the same command against Mosquitto I get the following results:

```
Measurement started: 2020-10-14 14:24:09.264 CEST
Measurement ended: 2020-10-14 14:24:14.585 CEST

-----Publisher-----
Maximum throughput[msg/s]: 3000
Average throughput[msg/s]: 3000.00
Number of published messages: 3000
Per second throughput[msg/s]: 3000

-----Subscriber-----
Maximum throughput[msg/s]: 45000
Average throughput[msg/s]: 45000.00
Number of received messages: 45000
Per second throughput[msg/s]: 45000
Maximum latency[ms]: 159
Average latency[ms]: 71.26
```
We see that the latency lower is  on average and the max latency is lower as well. Something to investigate.

## 20190204

I was getting exceptions that queues are getting full and I have replaced them with unbounded queues for the moment. (But they are bounded of course by the heap size eventually). Wild cards seem to work as well at the moment thanks to the triennium library.

## 20190128

The broker now supports QOS 1 on a publish to a client. I have also started using the Triennium library. That was very straight forward even though at the moment it only does a subscribe at the moment, as I haven't tested the unsubscribe yet. Nor does the disconnect clear all the subscribers from a singe client. I also managed to make a flamegraph wit the profiler, but not quite sure yet as to what it is telling me.

## 20190125

QOS 0, 1 and 2 works both ways for the client... and when when testing with Mosquitto I can generate almost 8k message/second for one single threaded client.

## 20190122

More testing... I just ran 10k messages from the client to Mosquitto with QOS 0, 1 and 2 on the publish. yeah!!!!

Next step is to do that on my broker as well. And I think I'll just implement the message flow to start with, not (yet) the underlying logic that is needed.

ooh and I removed the sleep from the event creating loop... still works. And there is some checking with exceptions being thrown if there is a miscompare detected.

## 20190120

I just ran a test of 10000 publish message against mosquitto while being subscribed to each of the possible topics the client sends on (Spec generates the topics to subscribe on and these will be used to publish on). This all worked fine with out any problems. I can't replicate this yet on my own broker... that would be the next step.

Actually I just did... but with a single topic... so I need to implement multiple topic subscribe (the subscribe message is generated by Spec so therefore it can have quite a few topics (unless I tell it not too)). But that is something for tomorrow in the train.

## 20190117

https://clojure.github.io/test.check/generator-examples.html


## 20190116

I can send hundreds of message from my client to a real broker and then receive loads of them when I subscribe to '#' as a topic. I can also send a few messages to my own broker with the real clients and forward any publishes to a real client. I can also send quite a message with my own client to my own broker and I haven't seen any major errors, but this needs more testing. I may need to add triennium now and see if I can use that for packet routing... that would be a good point to start.

hmmm maybe make a generator for topic filters and proper topics first...

## 20190114

So lately I have been making loads of additions to this... first of all there is a 'client' now... partly because I managed to write some spec's for most packages (all packages needed for QOS 0 are specced now) and I needed a client to send the packages over the wire to the server and the contents of each package is generated via the spec. So the code encodes and decodes that various packages and all the data is generated via spec... the one thing where it fails at the moment is the two packages that have byte-arrays in them (Connect with username/password and Publish). These fail the ```(is ...)``` test in the ```deftest``` code and I think this is due to the fact that the values don't get compared, but the location.

Also the server code is no longer 100% compliant as the 'server' will now accept packages that are normally only send by the server, but this was added so that encode/decode code can be tested.

I also started working on the Causatum lib. The plan is to use the client and generate lots of packages and initially sending to a real broker like Mosquitto or RSMB and see if the encoding works as expected. After that I can test my client against my server and it should behave the same. In theory.

## 20180214

Ok, now I am removing all the subscriptions of a client if it goes away (ie. a `DISCONNECT` or otherwise) and when it sends a `UNSSUBSCRIBE`. When I now test with jMeter I can run with 16 threads for 100 iterations with out a problem and that is over 1600 messages. woohooo. Next probably I need to add some spec to this project. And find the code I wrote for the wildcards.

## 20180213

Ok, now I can run jMeter with 4 threads and I think the next problem is that is a client goes away and it has subscribed to a topic, that subscription remains, so need away to cancel that and that means not just taking care of `UNSUBSCRIBE` or a `DISCONNECT` but also when the client goes AWOL.

## 20180211

So the last two days I have imlemented the asynchronous sending of messages. There is a second thread pool for when the messages are send and I now also use ByteBuffer.duplicate. This all seems to work now with small messages that are send relatively slowly. When I use [MQTT-Spy](https://github.com/eclipse/paho.mqtt-spy/wiki) I can press the publish button as fast as I can and it all works. However when I use [JMeter](http://jmeter.apache.org/) with this MQTT [extension](https://github.com/emqtt/mqtt-jmeter) I am getting java.nio.BufferUnderflowException exceptions pretty quickly.

## 20180209

Today I removed all the callback code from the project. HTTP is a request-response type protocol where each request is answered with a response. So having a callback on a request to send the response back to the client makes lots of sense in that case. But MQTT is different: There are quite a few cases there is no response from the broker to an incoming message from a client and in case of a `PUBLISH` there are onward messages to the subscribers. And this can have quite a big fan-out (thousands of client subscribed to the same topic for instance). So hence the code to call back into the server and send the message to a different client.

Next step is to put the sending of messages on an thread pool as well so that that part becomes async as well and handle large loads. Also investigate to use duplicate ByteBuffers and make it all go parallel.
