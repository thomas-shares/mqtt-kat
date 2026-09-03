# Thoughts and ramblings...

In this file will go my thoughts and ramblings about this project and what I have done and what I might do next.

## 20260903

### How much of QoS 1 and 2 had ever run

The question was how well QoS 1 and 2 are tested against a real broker, and
cloverage answered it plainly. `mqttkat.handlers` was at 52.84% of forms, and
the shape of what was missing mattered more than the number:

| function | lines never executed |
|---|---|
| `qos-2` | 5 |
| `qos-2-send` | 12 |
| `pubrec` | 4 |
| `pubrel` | 7 |
| `pubcomp` | 5 |
| `take-pending!` | 6 |

The one "covered" line on each QoS 2 function was its `defn` being evaluated at
load. There was no `:qos 2` anywhere in the default suite, so the whole
four-packet handshake and the `*inflight*` map it turns on had never run
outside production. `core-test` round-trips every packet through encode and
decode, which covers the wire format and says nothing about the protocol;
`client_generator_2` has a genuine QoS 2 flow over a socket but is tagged
^:performance and so excluded from `lein test`.

`take-pending!` was the other gap, and a self-inflicted one: the queueing half
of the QoS 1 window was covered and the draining half was not, because the only
test that filled a window used a subscriber that never acknowledged anything.

Three tests later — the QoS 2 handshake end to end, the pending queue draining
on acknowledgements, and QoS 2 across a reconnect — the same measurement reads
61.92% of forms and 93.49% of lines, and both of those functions are down to a
line or two.

### The broker said "done" and dropped the message

`*inflight*`, which holds a QoS 2 publish between PUBREC and PUBREL, was keyed
by `[client-key packet-identifier]` — the SelectionKey. A client that
disconnects in the middle of that exchange comes back on a different key, so
the entry could never be found again:

```
inflight keys after publish: [[sun.nio.ch.SelectionKeyImpl 777]]
inflight after disconnect: 1        <- and it stays
session-present: true
after pubrel, got: :PUBCOMP         <- the broker says it is done
sub got: nil                        <- the message is gone
```

Worse than losing it. `pubrel` sends the PUBCOMP before it looks anything up,
so the publisher is told the delivery completed while the message is discarded,
and the entry sits in `*inflight*` for the life of the process. The same leak
shape as the identifier pool the day before: state keyed to a connection when
it belongs to a session.

Keyed by client-id now, and cleared in `remove-client!` for a clean session.
The matched subscribers are no longer stored either — §4.3.3 publishes the
message when PUBREL arrives, so the subscribers are whoever is subscribed
*then*, and anything captured at PUBLISH time may point at a connection that
has since gone.

### Messages jumping the queue

The pending-drain test caught this on its first run: 200 messages published in
order, and `seq-150` arrived before `seq-151`, `seq-171` before `seq-155`.

`reserve` only checked whether the in-flight window was full. So the moment an
acknowledgement freed a slot, a fresh publish on the fan-out thread could take
it while older messages sat waiting in the queue — the fan-out thread and the
thread draining on each acknowledgement competing for the same slots. MQTT
3.1.1 §4.6 requires a client's messages to arrive in the order they were
published.

It now refuses a slot whenever anything is queued, unless the message asking is
the head of that queue. My own bug, from two days ago, in code nothing had
exercised.

### The flake I had been chasing all week

`qos-1-test` had been failing about one run in eight since the packet
identifier work, and I had blamed it on load spilling out of the back-pressure
tests twice, and tightened the settling twice. Both times it came back.

Printing the whole offending packet instead of just its type ended it:

```
expected :CONNACK, got {:duplicate? true, :packet-identifier 1,
                        :packet-type :PUBLISH, :qos 1, ...}
```

A redelivery arriving before the CONNACK. Not on the wire — `tu/client!` hands
every arriving packet to its own `go` block, and go blocks finish in whatever
order the pool gets to them, so two packets sent back to back can surface in
either order. The harness, not the broker, and it had been quietly making the
suite untrustworthy.

`client!` and `connect!` take `:ordered?` now, which puts inline from the
client's own read thread. Opt-in rather than the default, because an inline put
stalls the client's reader once the channel fills, which would change how every
test that does not drain behaves. The tests that assert a sequence use it — and
the ordering violation in the section above was only visible *because* one of
them did.

### A bug I reported that was not there

I said a publish to a topic with no subscribers got no PUBACK, because the
dispatch sat inside a `when-let` on the matched subscribers. It does not.
Triennium returns `#{}` for no match, and an empty set is truthy, so the body
always ran and the acknowledgement always went out. I asserted that from
reading the code without running it; reverting the change and watching the new
test still pass is what showed it.

The change stayed anyway, as a `let` with an honest comment. The correctness of
an acknowledgement should not rest on which empty value a library happens to
return. So did the test, which now covers PUBACK, the full QoS 2 exchange and
retained replay on topics nobody is subscribed to — none of which was covered
before, and all of which would break the day that lookup starts returning nil.

### Somebody else's test suite

Ran the Paho interoperability suite — `client_test.py` from
eclipse-paho/paho.mqtt.testing — against the broker for the first time. Ten
tests, three failures, and every one of them something the unit suite had no
opinion about. Worth doing much earlier: a suite written by people who did not
write this broker asks questions I would not have thought to ask.

First, what is *not* wrong with it. The suite is dormant — last commit January
2024, and the local checkout is level with origin, so there is nothing newer to
pull. On Python 3.14 it makes two kinds of noise, both its own:

* `DeprecationWarning: It is deprecated to return a value that is not None from
  a test case`. Every test ends `return succeeded`. Deprecated since 3.11, and
  one day an error.
* `OSError(9, 'Bad file descriptor')` out of the client's receive thread.
  Errno 9 is the giveaway: EBADF means *this process* closed the descriptor. A
  peer closing a connection gives ECONNRESET or a clean EOF, never EBADF, so
  the broker cannot produce it. It is a teardown race in the suite's own client
  — `disconnect()` ends by resetting `stopping = False`, and `connect()` then
  closes the old socket without stopping a receiver that may still be reading
  it, so the stray read gets logged rather than swallowed.

Neither explains a single failing assertion. Useful to have established, since
"the tooling is old" is a comfortable place to stop looking.

### $ topics, and four session bugs behind them

**A wildcard filter matched a $ topic.** §4.7.2: a filter beginning with `#` or
`+` must not match a topic name beginning with `$`. `+/+` was happily matching
`$TopicA/B`. Triennium does not know the rule, so each subscription now records
the filter it was made with and `matching-subscribers` sieves the matches. The
rule is about the first level of the *filter*, not about `$` appearing
anywhere: `$SYS/#` still matches `$SYS/foo`, and a blunt "drop anything
starting with $" would have passed the first test and broken that one.

**Session Present was reported on a clean connect.** §3.2.2.2 requires 0
whenever CleanSession is 1. This answered with whatever happened to be parked
under the client-id, so a client asking for a fresh session was told it had
resumed one — and a client that believes that does not re-subscribe.

**A clean connect did not discard the stored session.** §3.1.2.4. The parked
entry, its subscriptions and its queued messages all survived, so the *next*
persistent connect resumed a session the client had explicitly asked to be rid
of, and nothing ever cleaned it up.

**Nothing was kept for an offline session.** §4.1 requires QoS 1 and 2 messages
matching a persistent session's subscriptions to be held while its client is
away. Those subscriptions were deleted from the trie on disconnect, so a
publish in between matched nothing at all and there was nothing to keep. They
now move to an `*offline-trie*` on disconnect and back on reconnect; what
matches there is queued against the client-id and flushed when the client
returns. QoS 2 is queued at PUBREL rather than at PUBLISH, because that is when
the message is published (§4.3.3). QoS 0 is deliberately not kept — the spec
requires it only of QoS 1 and 2, and the suite agrees: "This server is not
queueing QoS 0 messages for offline clients" is a pass.

**A retained QoS 2 message was never replayed.** Found while chasing the one
above. `process-retained-messages` rebuilt its subscriber maps as
`{:client-key k}` with no `:qos`, and `qos-2-send` dispatches on exactly that
key — so the QoS 2 branch matched nothing and silently sent nothing. QoS 0 and
1 came through, which is why it takes a test covering all three levels to see
it.

### A failure that was a consequence, not a bug

`test_unsubscribe` started failing after the session fixes, having passed
before. It was not a regression. `test_retained_messages` had been failing at
its *first* assertion, before it published anything; with Session Present fixed
it got further, published three retained messages, and then failed at a later
assertion — so it never reached its own cleanup and left them set for the next
test to trip over. Fixing the retained replay fixed both.

Worth remembering the shape of that: in a suite of order-dependent tests
sharing two clients and a broker, a test getting *further* can break the one
after it. The second failure was information about the first, not a new
problem.

Nine of ten pass now. The tenth, `test_subscribe_failure`, expects SUBACK
`0x80` for `test/nosubscribe` — a topic hardcoded in the suite's own broker
(`mqtt/brokers/V311/MQTTBrokers.py:351`). It tests that a broker configured to
refuse a subscription says so properly. This broker refuses nothing: it has no
authorization at all, and `MqttConnect` parses username and password into the
message map that no handler ever reads. `0x80` is not a missing return code, it
is a missing policy, so the test is left alone deliberately rather than
outstanding.

### And one of my own assertions, wrong again

`qos-0-throttles-the-publisher-when-back-pressure-is-on` asserted that a
throttled publisher could not finish writing, and failed two runs in five. At
the `maxQueued` of 20 the test sets, the resume threshold is two packets, so
the broker pauses and resumes fast enough that the writer gets through all of
them. The broker was doing exactly what it was built to do. That is three
separate times now that a test of mine has asserted something timing-dependent
and blamed the code for it; the pause counter is what is actually promised, and
that is all it checks now.

### Where the coverage actually is

Since cloverage is what started this, the numbers after a day of it. Whole
project, default test suite (the ^:performance generators excluded, as
`lein test` excludes them):

```
lein cloverage --ns-regex 'mqttkat\..*' \
  --test-ns-regex 'mqttkat\.(flow|connection|connect|ping|smoke|keep-alive|core|backpressure|packet-identifier|qos2|session)-test'
```

|                   Namespace | % Forms | % Lines |
|-----------------------------|---------|---------|
|              mqttkat.client |   41.25 |   69.01 |
|            mqttkat.handlers |   67.36 |   95.48 |
|    mqttkat.handlers.connack |    9.52 |   66.67 |
|    mqttkat.handlers.connect |   71.33 |   98.11 |
| mqttkat.handlers.disconnect |   71.43 |  100.00 |
|                   mqttkat.s |   90.91 |  100.00 |
|              mqttkat.server |   52.87 |   76.74 |
|                mqttkat.spec |   78.20 |  100.00 |
|                mqttkat.util |    3.79 |   20.45 |
|-----------------------------|---------|---------|
|                   ALL FILES |   66.43 |   87.68 |

`mqttkat.handlers` started the day at **52.84% of forms and 81.93% of lines**
and is now at 67.36% and 95.48%. That is the QoS 2 handshake, the pending-queue
drain, the session lifecycle and the retained replay going from never executed
to executed — and three of the bugs above were found by tests written to close
those gaps rather than by reading the code.

What is left in `handlers`, by lines never run: `throttle-publisher!` and
`deliver-or-queue!` (the QoS 1 congestion path, which needs a subscriber slow
enough to fill a window and a publisher to throttle for it), `add-subscriber`
(dead — nothing calls it), `publish-will`, and single lines in half a dozen
others.

Two namespaces are worth naming rather than averaging away:

* **`mqttkat.util` at 3.79%** is not really a gap. It is the stats loop, which
  runs forever by design and is the thing keeping `-main` alive, so no test
  calls it. The parts worth testing — the rate arithmetic, the backlog
  clamping, the connected-versus-parked counting — are pure functions sitting
  inside a `loop` nothing can enter. Pulling `stats` out of `info` far enough
  to call it with two snapshots would cover most of it.
* **`mqttkat.handlers.connack` at 9.52%** is the client's side of the
  handshake. The broker sends CONNACKs constantly, but nothing in the suite
  makes the broker *receive* one, which is what that namespace handles.

The percentages are worth exactly as much as knowing which lines they are. 95%
of `handlers` reads well and still leaves the entire QoS 1 congestion path
unexecuted; 3.79% of `util` looks alarming and is mostly a loop that cannot be
called. Both of those are only visible per function.

### Ten thousand connections, and the accept queue

Wrote a scale test — `connection-scale-test`, tagged ^:performance — that opens
a lot of connections at once, checks every one got a CONNACK and that the
broker agrees on the count, then closes them all and checks it noticed. The
ramp is 10,000 and 20,000 by default, `-Dmqttkat.scaleRamp=...` to push it.

It found something on its first run. Ten thousand connections took **63
seconds** — 158 a second, about 8ms each, on loopback. That is absurd, and the
useful move was not to read the code but to take MQTT out of the picture:

```
raw SocketChannel open x3000: 23721 ms (126/s)
MqttClient ctor x3000:        19507 ms (154/s)
ctor + CONNECT x3000:         20662 ms (145/s)
```

Opening a bare socket with no protocol on it at all was just as slow. So none
of it was this broker's code, and the accept path was the only thing left.

Two things there, and they compound:

* `bind()` was called with no backlog, so Java asked for its default of 50.
  `/proc/sys/net/core/somaxconn` on this machine is 4096.
* `handleAccept` took exactly one connection per `select()` return. Readiness
  is reported once for however many are queued, so the rest waited for the next
  wake-up.

Between them the accept queue stayed full, the kernel dropped the SYNs it could
not queue, and the clients fell back on the TCP retransmission timer. Every one
of those 8 milliseconds was a client waiting to retry.

The backlog is 1024 now (`-Dmqttkat.acceptBacklog`) and handleAccept drains the
queue in a loop, which also removes a latent NPE: `accept()` on a non-blocking
channel returns null when there is nothing there, and the old code would have
dereferenced it.

| | before | after |
|---|---|---|
| 10,000 connections | 63,418 ms — 158/s | **1,328 ms — 7,530/s** |

Forty-seven times faster, and the interoperability suite is unchanged at 9 of
10 afterwards, so nothing was traded for it.

### What the broker costs per connection

At 20,000 connections: **359 MB of heap and 35 platform threads** — the same 35
as at rest. Forty thousand virtual threads for no platform threads at all,
which is the plainest evidence yet that the rebuild on virtual threads was
worth doing.

Three limits showed up and none of them is the broker:

* **28,232 ephemeral ports** (32768-60999). Every connection to one listener
  from one address needs one, so that is the ceiling for a loopback test
  whatever the broker does.
* **TIME-WAIT between ramp steps.** Closing ten thousand connections holds ten
  thousand ports for a minute — measured 26,169 sockets in TIME-WAIT holding
  26,023 of the 28,232. So only the *first* step of a ramp measures a clean
  accept rate; the 1,334/s at 20,000 is port starvation, not the broker
  slowing down. Worth remembering before reading anything into a ramp that
  gets slower as it climbs.
* **25,000 got the JVM killed** by the kernel's OOM reaper — SIGKILL, not an
  OutOfMemoryError. The heap was 443 MB at 20,000, so it was never heap: both
  ends run in the one process, so 25,000 connections is 50,000 sockets, and the
  kernel's buffers for those are native memory the JVM never accounts for.
  Out of the default ramp, with the reason written down.

One thing I put in and took out again: a TIME-WAIT count in the test's own
output. `/proc/net/tcp` throws `IOException: Invalid argument` when read from
the JVM — `slurp` and `line-seq` alike — while `cat` and python read it
perfectly well. It printed `?` every time. A metric that never works on the
machine it was written for is worse than not having it, so the docstring says
to watch `ss -tan | grep -c TIME-WAIT` instead.

### Fifty thousand, with the broker in its own process

The in-process test could not go past 25,000 because that is 50,000 sockets in
one JVM and the kernel's buffers for them are native memory the OOM reaper
counts and the heap does not. So: `connection-scale-remote-test`, which starts
the uberjar as a subprocess and connects to it from the test JVM, each carrying
only its own half.

Three things that made it work:

* **Source addresses across 127.0.0.0/8.** A connection is the whole
  four-tuple, so every source address brings its own range of local ports and
  the ~28,232 ephemeral ports stop being the ceiling. Linux lets any user bind
  to any 127.x.y.z with nothing configured first — checked before relying on
  it, along with whether the JVM could read /proc for the numbers. It cannot,
  so broker memory comes from `ps`.
* **No thread and no reader per connection.** The CONNACKs are left unread in
  the socket buffers. What proves the broker took them is the broker's own
  stats line, parsed out of its stdout — a better witness than the client's
  opinion, since it comes from the process under test.
* **`-main` takes a port now**, so a second broker can run beside one that
  already has 1883. A broker that cannot be told where to listen was a real
  limitation, not just an inconvenience for a test.

| connections | in-process | out-of-process |
|---|---|---|
| 10,000 | 1,328 ms — 7,530/s | **1,031 ms — 9,699/s**, broker RSS 245 MB |
| 25,000 | **SIGKILLed** | 28,042 ms, broker RSS 328 MB |
| 50,000 | — | 56,832 ms, **broker RSS 484 MB** |

Fifty thousand connections for 484 MB. The same total socket count that killed
one JVM is comfortable across two.

### Two ways a gone client stayed

The remote test failed the first time it ran properly, and not on the numbers:

```
10000 connections in 1030 ms (9709/s)
timed out waiting for the broker to notice they had gone; broker last reported 10000
timed out waiting for 25000 connections; broker last reported 35000
```

Ten thousand closed sockets, and the broker still counted them. The next rung
then saw 35,000 — the stale ten thousand plus the new twenty-five.

**`handleRead` had two teardowns and only one of them tidied up.** A clean FIN
gives `read < 0` and goes through `closeKey`, which queues a DISCONNECT for the
connection to handle in order, so `remove-client!` runs. An `IOException` — a
reset, which is what Linux sends when a socket is closed with data still unread
on it, and these clients never read their CONNACKs — went to a different branch
that cancelled the key and closed the channel and dispatched nothing. So the
session stayed in `*clients*`, its subscriptions stayed in the trie, and the
broker counted it as present for the life of the process. Both paths go through
`closeKey` now.

**And `remove-client!` only ever unsubscribed persistent sessions.** The clean
ones — the common case, and every client in this test — left their
subscriptions in the live trie pointing at a dead SelectionKey. The trie grew
by every client that had ever connected, and every publish matched all of them.

Neither of these is visible in-process, because clients there have a reader
thread that drains the CONNACK, so they leave by FIN and take the tidy path.

### Being wrong about my own test, again

I wrote a unit test for the reset bug and it **passed with the fix reverted**,
so it was not testing that at all. Checking which fix it did exercise — revert
one, run, revert the other, run — it was the clean-session unsubscribe. It is
called `subscriptions-go-when-a-clean-session-disconnects` now, which is what
it actually proves.

That left the reset fix asserted and unproven, with two changes made at once.
So I put back only that one and ran the remote test again: "broker last
reported 10000". The leak returned exactly, and the fix is what removes it.

Worth keeping the shape of that. A test passing does not mean it covers what
you wrote it for, and the cheapest way to find out is to break the thing on
purpose and watch it fail. Three times this week an assertion of mine has been
measuring something other than what I thought — and the only ones I caught were
the ones I tried to break.

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

### Back-pressure for QoS 0, and why it barely helps

QoS 0 was still losing 85% of a 150x150 fan-out, so the throttling built for
QoS 1 now applies to it too: a subscriber past half of `maxQueued` pauses the
publishers feeding it, and releases them at an eighth. Same pause/resume
machinery, wired through `sendMessageBuffer`. `-Dmqttkat.qos0BackPressure`,
default on, and `Connection/setQos0BackPressure` at runtime.

It works, and it does not rescue this load:

| run | dropped | delivered | throttled |
|---|---|---|---|
| off | 38,421,062 | 6,579,547 | 0 |
| on | 37,963,336 | 7,037,273 | 361 |
| on, plus the read-ahead bound below | 38,102,946 | 6,897,650 | 481 |

About 1%. It is not that nothing happens — ingest over the first interval falls
from 29,997/s to 5,663/s, and the throttle counter climbs — but the publishers
still get all 300,000 in and the drop count hardly moves.

The reason is that **dropping relieves the very congestion the throttling keys
on**. The queue reaches the congestion mark, the publishers are paused, and the
queue then discards its way back below the resume mark and lets them straight
through again. The two mechanisms undo each other. QoS 1 does not have this
problem because its pending queue *retains* what it cannot send yet, so the
pressure persists until something is acknowledged.

At 7x overload — 45M deliveries against roughly 500k/s — that is close to
unwinnable without giving up the drop backstop, which would hand QoS 0 QoS 1's
memory profile. At the mild overload this is actually for, the queue stays
congested and publishers stay held. Worth remembering that the 150x150 config
is pathological, not typical.

The trade the flag buys or sells: with it on, a publisher held back for one
congested subscriber is held back for **every** subscriber it feeds. That
head-of-line cost is exactly the isolation that dropping gives you, and it is
why `a-stalled-subscriber-does-not-starve-a-healthy-one` now pins the flag off
— that property is only true while QoS 0 drops. Both behaviours are wanted and
both are pinned by a test, so neither can be changed by accident.

### Two things found while doing it

**A pause that could never be released.** `pauseUntilDrained` added the
publisher to the subscriber's waiters and *then* paused it. Those are two
steps: a `drained()` running in between saw an empty set, and the publisher was
paused a moment later with nobody left to wake it. On the QoS 1 path that heals
on the next acknowledgement. QoS 0 has no acknowledgement, and a subscriber that
has closed will never drain again, so the publisher stopped for good. It
re-checks after pausing now. Locking instead would mean holding the
subscriber's monitor while taking the publisher's, and two clients each
publishing to a topic the other subscribes to would take those two monitors in
opposite orders.

**`Connection.inbound` is bounded now** — 64 chunks to stop reading, 16 to
start again — which closes an item left open in the entry below. Without it the
selector reads as fast as the kernel will hand it over, so a publisher's whole
payload is inside the broker before any subscriber looks congested, and
stopping OP_READ throttles nothing because everything it was going to send has
already arrived. On the QoS 1 path the acknowledgement window limits the
read-ahead; QoS 0 had nothing playing that role. It is the right change and, as
the table above shows, not the one that was going to save this benchmark.

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
