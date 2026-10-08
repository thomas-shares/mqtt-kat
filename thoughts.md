# Thoughts and ramblings...

In this file will go my thoughts and ramblings about this project and what I have done and what I might do next.

## 20261008

### Chaos for a dying broker, from the publisher's side

Every chaos run so far judged a killed broker by what its subscribers got.
Its publishers were clean sessions that never resent, so a QoS 1 or 2 publish
the broker had received and not yet acknowledged simply became "may or may
not arrive", and the half of the promise that matters when a broker dies,
what it does with the retransmission, was never exercised. Now
`:load :pub-persistent` makes that share of publishers persistent. They keep
their unacknowledged publishes across a drop and, if the session is present,
send them again as §4.4 says: PUBLISH with DUP and the same packet
identifier, PUBREL for a QoS 2 publish already PUBRECed. A QoS 1 publish that
went twice may arrive twice, at any subscription QoS; a QoS 2 one is still
held to once, so a broker that forgets the packet identifier across a crash
shows up as a `:duplicate`. Found on the way: a weight map in a scenario
merges over the defaults, so `:qos {1 1, 2 1}` left QoS 0 at weight 1.

Broker actions take `:who` (`:publishers`, `:subscribers`, `:busiest`: kill
the brokers holding the most of them), `:count` and `:min-up 0`, and the
report says per QoS how many publishes a broker's death caught in flight and
how they ended. `chaos/broker-kill.edn` and `chaos/broker-restart.edn` use
all of it. Not yet run against a Rama cluster: only the checks, a resend test
against the suite's own broker and a short single-broker run have.

### First broker-kill runs

Three brokers, QoS 1 and 2, persistent publishers resending, a broker killed
or stopped every ten seconds or so (run 165703). Of 1.15M messages owed, 4
were lost (QoS 1, each acknowledged by a broker killed right after) and 148
QoS 2 messages arrived twice, against 35 and 79 in the run before it, whose
publishers did not resend. 367 packets were resent, 555 QoS 1 repeats were
allowed. The QoS 2 duplicates sit right after a broker stopped, and the
earlier run's did too, which is the open "delivered twice across a crash"
item: a subscriber acknowledged on the dead broker, whose word did not reach
the cluster, is given the message again where it resumes. The report now
says how many of the duplicates were of a message its publisher had also
sent twice (`:duplicate-resent`), which would be a different gap: a QoS 2
publish resent to a broker that has no record of its packet identifier.
Two things seen on the way. A restarted broker takes 20 to 40 s to listen
again, so `:down-ms` is only the start of an outage (`:outages` in the
report has the real ones). And after each kill, up to 64 of 80 clients were
disconnected for around ten seconds with two brokers up, which is about
`hand-over-wait-millis`: a session whose broker died is waited on before it
is handed over.

### Near broker chaos had to mean the right broker

Run 171519: all 70,684 publishes acknowledged this time (keeping the unsent
ones of a persistent publisher, 46 caught in flight by a kill and all
acknowledged after a resend), no QoS 2 duplicate of a resent message, but 42
lost against 4. They were all called near broker chaos, and that was the
classifier's fault: with a broker killed every ten seconds and away for
twenty, some broker was down for 87 % of the run, so "a broker was down" held
for nearly every loss. It now counts only the brokers the message was
published to and the brokers its subscriber was on or went to, and the report
says whether each lost message had been resent (`:lost-resent`). Of the 42, 22
were sent within a second of two brokers being stopped together, and 12 were
sent 170 to 179 s in, when only broker 3 was up and the clients that lost
them were connected to it.

## 20261007

### Taken for unsubscribed while it moved in

Run 130526, traced on the last minute only, caught two of the late losses
in the act. Each copy came over a bridge to broker-1 2 and 3 ms after its
client had resumed there, and broker-1 matched the client, did not choose
it, and neither sent the copy nor queued it. Judging a copy asks who the
sender had here; of those, one connected here with no subscription in the
live trie is taken to have unsubscribed. A session moving in is live a
moment before its subscriptions reach the live trie, and one taken over
here is in neither for a moment, so a copy judged then was dropped for a
client that had never left the topic. Whether it unsubscribed is now asked
of the connection's own record, which carries the subscriptions from the
start: a client that really unsubscribed has none there, and one moving in
has its copy queued, for the reads that follow its resume.

### The late five, again

Run 122731, untraced, kept every broker writing all the way and lost 5:
QoS 1, kept wildcard subscribers, each a different message, each across a
bridge, sent 149 to 166 s in. That is the shape runs 105435 and 221324 had
too, and the one the trace of only the moves found nothing for: so it is
somewhere in the live steps, which that trace left out. Following every
live step of every message is tens of millions of lines; following them for
the late messages only is a quarter of that, and a chaos client's payload
names its sequence, which is its send time. So the trace can now be given
the messages to follow by name (MQTTKAT_TRACE_MESSAGES), and with no topics
named it follows those publishes where they enter as well.

### Broker-2 stopped for two minutes

The traced run 111735 lost 11, and none at the move: each was sent 34 to
82 s in, and the clients that lost one show no move step for it at all.
Seven were on broker-2, sent within five seconds of each other, and one
message was lost by four clients there while three others on the same
broker had it from the cluster's queue.

The stat lines say why to look at broker-2. From about 11:18:45 it wrote
nothing at all, not even PINGRESPs, while PINGREQs still came in; brokers 1
and 3 said their bridges to it had acknowledged nothing for 30, 60, 90 and
120 s, and with their links full they held every publisher, so the whole
cluster sat at under one message a second until the final reconnect. Its own
links timed out the word on 16,000 copies and took them as delivered. A
broker that stops is a bug before it is a loss, and the losses sit at its
edge.

A broker that writes nothing while packets come in now dumps every thread,
virtual ones too (Thread/getAllStackTraces leaves those out, and they are
the connections), next to its log, at most three times; scripts/threads.bb
counts the threads by the first frame of ours they are in. The launcher
also turns on jdk.tracePinnedThreads: the last broker-wide stall was a
monitor pinning connection threads, and deliver-queued! still takes one.

### Five left, and a trace of only the moves

With the runs spread again, 5 of 14.7 million were lost, all QoS 1, all
for kept wildcard subscribers that the final reconnect put back on the
broker they had been on. One message was lost by two of them, on the
same broker. So the race at the hand-over was not it, or not all of it.

Reading the code did not find it, so the next run is traced: every
subscriber, but only the steps a moving session takes: handed over,
restored or left on the cluster's queue at the resume, read from it,
resent under its identifier, redelivered, withheld, not sent.
MQTTKAT_TRACE_WHAT picks the steps, and leaves out the forty million live
sends a trace of every client would otherwise write.

## 20261006

### Six left, and a race at the hand-over

With the drain waiting for Rama the 3,000 a second run lost 6 of 15
million owed: the 333,000 before were late, not lost. The six were all
QoS 1, for kept wildcard subscribers on broker 1, bridged from the other
two, sent 20 to 35 seconds before the final reconnect, with Rama's queue
empty at the end. So they were never put on it.

One way that happens: a delivery asks whether the connection is still
live, and only then takes a place in the client's window. If the client
disconnects in between, the hand-over empties the window first, and the
delivery then puts its message in flight to a socket that is gone. It is
neither handed over nor queued, and nothing sends it again. The two now
take the same lock, the one a read of the cluster's queue already took,
and the delivery asks again under it: a connection gone by then refuses
the message, and the caller queues it as for any client that is away.
It could not be timed from outside, so the run will say whether this was
the six.

It said something else first: every delivery stopped 44 seconds in and
stayed stopped until the final reconnect. The deliveries run on each
connection's virtual thread, and on Java 21 a virtual thread waiting on
a monitor keeps its carrier thread. Four hundred publishers delivering to
the same wildcard subscribers waited on its lock until no carrier was
left, and the connections' writers had none to run on. So no lock: the
delivery puts the message in the window as before, then asks whether the
connection is still live. If it has gone, the delivery takes the message
out again, and the caller queues it. If the hand-over took it first, the
cluster's queue has it already. And since the connection stops being live
before the hand-over starts, a hand-over that runs after the check sees
the message.

### A PUBLISH ahead of its CONNACK

The run after that had one protocol error: a kept subscriber was sent a
QoS 1 PUBLISH at the final reconnect before its CONNACK. Every live
delivery waits for the CONNACK, and the flush after it sends what waited.
A read of the cluster's queue did not: a message the old broker handed
over in flight goes out at once under its identifier, and a restatement
reads the queue of every connection the cluster has as here, including
one whose CONNECT is still waiting on its connect record, which with Rama
45 seconds behind can take a while. Such a read now takes nothing until
the CONNACK is out, and leaves the queue to the reads that follow it.

### A drain that waits for Rama

The next 3,000 a second run, on the 1024 window, lost 350,000 rather than
1.17 million, all in the last minute before the final reconnect. A trace
of every client on one topic found each of its lost messages in the
same place: a copy that reached the broker its client had just left,
which queued it on the cluster, while the broker the client had moved
to read that queue at 2, 5, 10, 20, 40 and 60 seconds and whenever Rama
nudged it. With Rama 45 seconds behind, those writes landed after the
reads, and the drain, which ended on ten seconds without a delivery,
ended before the nudges came. The probe afterwards still had them on
the queues, 1,000 to 2,000 for each client that lost most.

Late is not lost, but the check cannot tell them apart. The drain now
also watches Rama's queue counts, when the brokers use an external Rama,
and ends only once those have not moved for :drain-ms either. Whatever
is still queued then, with every client connected, is printed: that is
a message nothing read back.

### A minute on the bridges

The trace of the next 3,000 a second run, on ten topics, had every copy
that crossed a bridge reach the other broker, and the kept clients it
followed lost no QoS 2 message before the final reconnect. What they did
lose, some 180 each, were copies that reached the broker they had left
after they had gone: it queued them on the cluster, and the drain ended
before the new broker read them back.

They were that late because every bridge was a minute behind. From the
publish's arrival at one broker to its copy's at the next took ten
seconds in the first ten seconds of load, forty by the first minute, and
40 to 75 seconds from then to the end, on all six links. Each link moved
about 450 messages a second. A broker stops reading a bridge while a
subscriber it feeds is behind, and with 135 wildcard subscribers at
3,000 a second one of them always is. That sets the rate, and it is not
wrong: it is the back-pressure that keeps the subscriber from losing
messages. What made it a minute was the bridge's Receive Maximum,
16,384: a link held its publishers once its own queue reached 2,048, and
only filled that once the peer's window was full, so a copy waited
behind some 18,000 others. 18,000 at 450 a second is 40 seconds.

The window is 1024 now. The publishers are held as soon, behind a pipe a
sixteenth as long, and a peer that keeps up still has the window for
10,000 messages a second at a 100 ms round trip. Trace lines now carry
the QoS too: a QoS 0 delivery is not followed, and without it a message
a client was not sent could not be told from one it was sent at QoS 0.

The clients that lost most were not among those followed: the top ten
were kept wildcard subscribers, some 40,000 each, from 54 seconds in.

### Following a message through the brokers

After #53, a 3,000 a second run still lost 1.4 million messages, all for
kept sessions, and none of it showed up anywhere I could look. The lost
messages were not on Rama's queues. Nothing was withheld from the clients
that lost them while the run went on, nothing was handed over at the end,
and every connect and subscribe that failed to record did so at the final
move. Counts and warnings only say how many, so the broker now has a
trace: -Dmqttkat.trace (or MQTTKAT_TRACE) names the clients to follow by a
regex, -Dmqttkat.traceTopics (MQTTKAT_TRACE_TOPICS) the topics. One log
line follows each thing done with their messages: sent, pending, refused,
withheld, left out, queued on the cluster, handed over, taken from the
queue, settled. A publish on a followed topic is also logged where it
enters, with the brokers its plan sends copies to.

### A message taken off a queue stays off

With reads fixed, the next 3,000 a second run still had 1,634 QoS 2
duplicates, each sent some ten to forty times, every ten to fifty seconds
through the drain, by the broker the client had moved to at the end. Its
log had it finding the one message on the client's queue each time. A
read only sends what it has not had, and lets a key go once its take-off
has landed and a read no longer finds it, so the message was gone from
the queue in between and came back. A take-off removes the key, and an
enqueue of the same key puts it back, in whatever order the depot has
them; and a queue write that times out has often reached the depot, so
trying it again puts it there twice. With Rama minutes behind, an
enqueue tried again, or another broker's landing late, came after the
take-off, and the client got the message again.

A key names one message for one client, so once it is taken off it has
been had. The topology now remembers the keys taken off a session's
queue, for an hour by the time in the key, and an enqueue of one of
them is counted as "queue-taken-off" and dropped. Each take-off record
forgets a few of those older than that, once for the record. The first
version did it once for every key, a range read each, which tripled what
a take-off cost: the next run's Rama batches no longer finished in their
five seconds and were tried again for ever, from forty seconds in, so
nothing was queued at all and 1.87 million messages were lost. The broker's second take-off
(redequeue-after-millis) did this for one case, a word from another
broker arriving late, and is left in place.

### QoS 2 sent again from a queue Rama had not caught up with

At 3,000 messages a second the chaos run delivered tens of thousands of
QoS 2 messages twice, one of them about ten times, all from the broker
the client was on. A persistent session's queue on the cluster is read
again and again while the client is connected (catch-up!), and what a
read sends is noted as had, so the next read leaves it out. The note was
let go once the take-off from the queue had landed. Landed was meant to
be when the topology had taken it off. Since the retries wait for the
depot only, not the topology (20261005, below), a take-off tried again
lands as soon as the depot has it. With Rama minutes behind, the next
read found the message still on the queue, no longer had, and sent it
again under a new packet identifier, and so on every read until the
topology caught up. The client, rightly, took each one as a new message.

A key is now let go only by a read begun after its take-off landed that
does not find it on the queue: what the topology says, not what the
depot has. A queue cut off at the read's limit says nothing of the keys
after its last one, which are kept.

The chaos ledger kept the detail of only the first ten thousand repeats,
and QoS 1 repeats, which MQTT allows, used it up in seconds, so all but
three of the QoS 2 duplicates came without where they were delivered.
Each QoS has its own ten thousand now.

## 20261005

### What a moving session was in the middle of, kept

Stage 3, the last of the three. Four things a session or a link was in
the middle of when it moved or dropped, each lost before.

A bridge link that dropped handed back everything in flight, and the
sender queued it on the cluster for the clients it was for. What the
peer already had went out twice, and a QoS 2 message it had taken and
not yet been released went nowhere: this broker publishes on the PUBREL
(§4.3.3), and the PUBREL never came. A link now keeps an MQTT session on
the peer, and when its connection drops while the peer is still listed,
the next link takes up its queue and what it had in flight, connects
again to that session, and sends the PUBRELs and the unacknowledged
publishes again, as DUP. A peer that comes back without the session has
none of them, and they are handed back as before. The first link of a
run starts a fresh session. On the peer's side, a PUBREL publishes from
the connection it came on: from the one the PUBLISH came on, gone after a
reconnect, a bridged message was taken for a client's and forwarded on
to every other broker.

A takeover went down the bridge behind every publish queued there, and
with the bridges a minute behind, the new broker gave up waiting for the
old one's hand-over. It now goes at the front of the queue. What it
overtakes reaches the old broker after the client has left, and is
queued there for it on the cluster, as for any client the sender had
there and that did not get it live.

A session the new broker resumed without its hand-over, after that wait
ran out, gave out packet identifiers at once. The hand-over carries the
identifiers the client still holds messages under on the old broker, and
a new QoS 2 message under one the client held for an old one was taken
for that one. Now such a session gives out none until the old broker's
record moves on, and the queue has been read once more so the hand-over
takes its identifiers first; or until the old broker leaves the cluster,
or a minute has gone by. What would have gone out waits on the pending
queue.

And a PUBREL from a hand-over whose identifier was in use here was taken
off the cluster's queue at the resume and never sent. It now stays
there, and a later read sends it once the identifier is free.

### Brokers forgotten by a topology that was behind

The next run's Rama page said "Pushed by Rama 17:25:59" at 18:24, and a
minute later 17:27:20: the sessions topology was an hour behind its
session events, and catching up at about a third of real time. Behind like
that, it also showed no broker registered, with three announcements and no
withdrawals. The sweep had forgotten all three.

The sweep runs off a tick depot of its own, so it is on time whatever the
session events are behind on, and it forgot a broker whose latest report
was stamped more than ten minutes before the tick. With the topology more
than ten minutes behind, every report it had taken was that old, from
brokers reporting every few seconds. A forgotten broker's clients are let
go, and copies for a client let go are queued on Rama instead of sent:
more for a topology that was already behind.

A broker is now silent only by the reports the registry has taken: ten
minutes behind the tick and behind the newest report from any broker. A
topology behind on all of them forgets none, and a killed broker is still
forgotten once the others are heard from ten minutes past it.

### Waiting for the depot, not the topology, when trying again

With that fixed, the next run lost publishes from a minute in, and every
bridge link stopped being acknowledged near the end. Rama's own page said
why: its sessions topology had been handed 48,374 connects for some 4,700
connections, and 88,183 subscribes for 1,600 subscriptions, and a quarter
of an hour after the run it was still working through them at 755 a
second.

That was the retry again. An append waits for the topology to have
processed the event (`:ack`), and gives up after five seconds. Once the
topology is five seconds behind, every append times out, with its event
in the depot all the same. Tried again at `:ack`, a connect timed out the
same way, and was appended again five seconds later, for as long as its
connection lasted, each copy adding to what the topology was behind on.
The queue writer did the same with whole batches.

A retry now waits only for the depot (`:append-ack`). In the depot, the
topology gets to it in turn, and a connect, a subscribe or a queue batch
that lands twice ends where it did once. The first attempt still waits for
the topology, as what reads the record next expects. So a backlog costs
at most one copy more of each event, not one every five seconds.

### One record too big for Rama's five seconds

The run after that stopped acknowledging publishes 47 seconds in and
never started again. The Rama worker said why: the sessions topology timed
out on one event of its session-events depot, at 15:31:14, and from then
on its task threads took too long on everything. A queue write is one
append per run of a client's requests, gathered while its last batch was
out, so the slower Rama gets the bigger the next one. One grew past what
the topology does in five seconds, a message at a time, and Rama timed it
out and tried it again, as a stream topology does, and so did the broker,
the whole batch, again. Everything sharing its partition waited behind
it, publishers' PUBACKs among them.

A queue record now carries at most 256 messages or keys taken off; a
longer run of them goes as several, in order.

### Trying again without adding to the backlog

Making a session event land (20261004) sent each retry past the bound on
appends in flight, and a disconnect up to thirty times whatever had
happened since. When Rama fell behind at the end of a run, every broker
then appended each of its clients' disconnects over and over, to a
cluster that already could not keep up: seven thousand failures per
broker. A retry now goes only when one of the permits is free, and waits
for one otherwise without counting as a try; and a disconnect is not
tried again once its client is back on this broker on a newer
connection, whose connect replaces whatever the record has.

### A client that went round the brokers for the whole run

With the links probing their sockets, the next run still lost a million
deliveries, but to about thirty kept subscribers, each losing nearly all
it was owed from half a minute in. One of them, in the brokers' logs: sent
on by a redirect, it reached a broker that found its session still on
record elsewhere (the disconnect that should have cleared it was among the
twenty thousand Rama did not take), waited the full ten seconds for a
hand-over that never came, and resumed it. The chaos client gives up on a
CONNACK after ten seconds too, so it had always just left. It came back to
the first broker, was redirected, and went round again, every fifteen
seconds, to the end.

The runner now waits as long for a CONNACK as it does for anything else
on connecting, thirty seconds. That a broker holds a CONNACK for ten
seconds on a stale record is the real cost; the record goes stale because
Rama is refusing appends, and the takeover that would end the wait sits
behind a full window on the bridge, which stage 3 moves to the front.

### A link that waited on a socket nobody had

A 3,000-client run with nothing killed lost 1.4 million deliveries, all of
them between brokers. Every bridge stopped being acknowledged at the same
moment, the one where publishing stopped and the subscribers all came back
at once, and stayed that way for the rest of the run. The brokers held no
socket for any of them any more; yet the channels said they were open and
their readers sat in a read that never returned. A link waiting on a full
window asked only whether its channel was open and whether the cluster
still listed the peer, and both said yes for eleven minutes, so everything
queued behind it, minutes of traffic, went nowhere.

Asking is not enough; a link now writes. While it waits on its window it
sends the peer a PINGREQ each time it gives up waiting, and when idle it
sends one at every sweep. A write to a socket that is gone fails, and the
link ends as any lost one does: what it held is handed back, and the next
message opens a new one. A peer that has merely stopped reading takes the
two bytes, or holds the write as it holds the window, and is waited for as
before. Who let go of those sockets is still open: the server's close
paths say nothing above debug.

## 20261004

### A record that never landed

The receivers now trust the cluster's record of where a client is, so a
record that is wrong costs more than it did. Before, a broker delivered
to whoever it had, and a wrong record meant a second copy; now it means
none. Under load an append to Rama can time out, and a session event that
did was logged and forgotten. A load run lost the connect of a client
that way: the record kept it away for the rest of the run, every broker
left it out of every copy, and it lost 33,000 messages. Its subscribes
were lost with it, since they name a connection the record did not have.

So a connect, subscribe or unsubscribe that does not land is appended
again, for as long as the connection it names is still the one here and
still holds (or has given up) the subscription. A connect that lands late
brings the connection's subscriptions after it, or, if the connection has
ended by then, its disconnect. A disconnect or a redirect, which the
broker cannot check against anything, is tried a few times. Each is safe
to run twice: the topology already counts a connect it has once, and
ignores the rest when they name a connection that is not on record.

## 20261003

### Whom a copy was meant for

The publisher's broker plans a publish from its copy of the cluster's
subscriptions, and that copy lags. A client that has just moved from B to
C is still on B in it: the copy for it goes to B, which no longer has it,
and if C gets a copy for its own subscribers the client is among them.
Neither end could tell what the sender meant. B queued for clients that
had left in the last minute, C delivered to whoever it had, and between
the two the no-chaos load run lost some messages and sent others twice.

Now the sender says. Its view of the subscriptions has a version, which
moves on whenever a client's entries change, and every copy carries the
version it was planned at. What changed goes down every bridge link,
client by client, before the new version is planned from, and a new link
starts with a snapshot of the whole. One TCP connection, read in order, so
the receiver always has the sender's view as of the copy in front of it,
and keeps the last few states of each client to judge copies that waited
behind others. It then leaves a kept session out where the sender had a
matching subscription of it elsewhere, or away, and queues under the
message's key for every client the sender had here and that did not get
it live. That holds at QoS 0 too, where nobody queues: the copy is then
the other broker's to deliver or drop. For a while this rule spared QoS
0, to lose nothing, and the load run sent some three million QoS 0
messages twice, most likely to the kept sessions its final reconnect
moves while the bridges are far behind. At most once allows the loss,
and not that. Each broker asks the same question of the same version, so one
of them delivers or queues and the others leave it. A client the sender
delivered to itself, though its view had it elsewhere, is named on the
copy, and nobody else touches it. The rules are in `mqttkat.intent`,
which is pure and tested on its own.

Three smaller things came with it. The sender queues for the clients a
lost or undelivered copy was for minus those it delivered to itself, which
it did not before. It queues for clients its view has away only if their
session is still kept. And a will is named like any other publish, so the
brokers it reaches queue it the same way.

Judging runs on the one thread that reads a bridge, so it has to be
cheap. The first version asked about each subscriber twice per copy, and
split the topic and the filter again for every question: about 1.6 ms
for a copy to a topic with 170 subscribers. Now a copy is judged once,
with its topic split once and each filter's levels kept, and the old way
of judging is asked only when some client's history may be too short for
the copy. That is about 0.1 ms.

A copy planned before its link's snapshot, a copy for groups only, and
one from a broker running older code are judged as before. What is left
for the next stage is the link itself: a link that drops takes the copies
behind it with it, and a QoS 2 copy the peer had taken is handed back as
if it had not. That wants the bridge to keep its MQTT session across
reconnects.

## 20261002

### The queue tells the broker

What another broker puts on a session's queue after the client has
resumed somewhere was read only if a catch-up read came after it, and
those stop a minute after the CONNECT. Under load the writes come later
than that: the copy that makes a broker queue for a client it has just
lost can sit a minute or more behind the bridge, and Rama behind it adds
seconds more. Those messages waited on the queue for the client's next
resume, and in a load run that is never.

So the module says so. An enqueue for a client the cluster has connected
writes the last key to `$$nudges`, under the broker it is connected on and
a shard of the client, and each broker watches its own sixty-four shards
with proxies. A change is a read of that client's queue. The CONNECT
record nudges too when the queue is not empty, since the broker read it a
moment before saying it had the client, and the end of the connection
takes the entry away. Every way a message reaches the queue is covered,
because the nudge is made where they all land.

Reading for the whole connection rather than its first minute meant the
"had" set had to stop growing. A key now stays in it until its take-off
from the queue has landed, and goes when the next read begins: reads are
one at a time per client, and a read that begins after the take-off
landed cannot find it. One that was under way could, which is why a key
cannot simply go when it lands. The suite found the other half of that:
a restatement read the queue on its own while a nudge read it too, and
each sent what the other had just put on the client's queue. Now a
restatement reads through the same one-at-a-time reader, and
deliver-queued! takes one caller at a time per client besides.

### A session that moves, and what it was in the middle of

The no-chaos load run moves every persistent subscriber to another broker
at the end, gracefully, and the duplicates it found were nearly all on
those sessions after that move. The old broker hands what the client had
not acknowledged over to the cluster's queue, each with the identifier it
was in flight under, and records the disconnect. Both were writes to Rama
that nobody waited on, so under load the disconnect could land first; the
new broker read the queue without the hand-over, gave the client fresh
identifiers starting at 1, and a catch-up read found the handed-over
entries seconds later. They then went on the ordinary queue under new
identifiers: a QoS 2 message the client had already taken arrived as a
new one, and a PUBREL went in flight and was never sent, so the client
kept holding that identifier and dropped the next message sent under it
as a repeat.

Now the disconnect is recorded once the hand-over's writes have landed,
and a CONNECT that finds the session still connected on a live peer waits
for that record, ten seconds at most, before it reads the queue. Read
then, the hand-over is restored under its identifiers before anything
else is sent, so live deliveries take others. One a catch-up read still
finds late goes out at once under its own identifier, as a DUP PUBLISH or
a PUBREL, and stays on the queue while that identifier is in use here.
That last case is not fixed, only narrowed: after a takeover the word to
the old broker waits behind the bridge, so the wait can run out, and an
identifier the new broker has used meanwhile cannot be given back. The
old broker would have to record which identifiers it held.

Three smaller things from the same reading. A resume restores half of
pending-limit, not all of it, so a session coming back to a long queue
still has room for live messages; a delivery refused by a full queue no
longer counts as live, so the cluster queues it for a kept session
instead of nobody having it; and a catch-up read checks what the client
has had after the read, not before it, since the read can take seconds.

The larger loss, copies that reach the old broker more than a minute
after the session left, is the design question still open: the receiving
broker guesses from its own view whom a copy was for, and only the sender
knew.

### NOT_ALIVE, or never there

With the cluster emptied for a clean load run, `bb scripts/rama.bb update`
tried to update the module and Rama said "Module not alive". It was not
dead, it was not there: `moduleStatus` answers NOT_ALIVE for a module the
cluster has never heard of, and the script took that for one deployed,
which gets an update. What tells them apart is the depot the module
appends to: `"appendTargetId":null` until its first launch, set from
then on. Read that way, an empty cluster gets a launch. There is a
`reset` too now, for a clean slate before a load run: stop, delete
local-rama-data and local-zk together, start, launch.

### Listed, not reporting

The next no-chaos run on the branch: at 15:10:20 every bridge in the
cluster gave up on its peer within seven seconds of the others, "nothing
acknowledged" after 5 to 20 s, and handed back about 22,000 messages each,
some ninety thousand in all, then gave up on another 28,000 the peers had
taken and not yet confirmed. A handed-back message is queued only for
sessions that outlive their connection, so for every clean subscriber on
the other end it was gone, and one bridged message is a delivery for each
subscriber there.

The wait from the morning's change asked whether the peer had reported to
the cluster in the last 30 s. Under that load the reports reach the others
late, and late for all of them at the same moment, so the safeguard turned
off exactly when it was needed. A connected peer is now waited on for as
long as the registry lists it. A broker that has really gone either closes
its socket, which ends the link at once, or is dropped from the registry,
whose drop! ends it; a broker that is only slow keeps its link and holds
the publishers feeding it, which is the back-pressure QoS 1 asks for.

The cost is a broker that hangs with its sockets open: the others hold
their publishers for it until the registry forgets it, which is
broker-forgotten-after-millis, ten minutes. Slower, but nothing is lost.

### One message with no QoS, and a whole queue behind it

The broker logs from the no-chaos load run had 334 CONNECTs fail at
11:19, all persistent subscribers on their final reconnect, every one with
a NullPointerException out of MqttPublish.encode: a message on the
session's queue had no :qos. The flush after the CONNACK threw on it, so
nothing behind it on that queue was ever sent, and since the message stayed
at the head of the window, the next flush or resume threw again. The same
throw inside a forwarded publish's fan-out left the subscribers after it
without the message and the forwarding broker without its PUBACK.

send-publish! no longer throws on such a message. One with no QoS goes out
at QoS 1: only QoS 1 and 2 are ever held, so at least once is the floor
the subscriber was owed. One with no topic cannot be sent and is dropped,
which also takes it off the cluster's queue. Either is logged with its
keys, once every five seconds, because where it comes from is not found
yet: every path I can read that queues a message gives it a QoS. The next
run's log line should say which.

### A slow broker is not a dead one

Thomas's bridge logs from the 10,000-a-second run: eight links dropped with
"nothing acknowledged for 5000 ms", and nineteen links handing back what they
had in flight. A broker stops reading a bridge, as it stops reading any
publisher, while a subscriber that publisher feeds has more than
`pause-threshold` waiting. A wildcard subscriber at that rate is behind most
of the time. The other end waited five seconds for a slot, took the peer for
dead, and handed back up to 16,384 messages. Each was queued in Rama for the
clients it was for on the peer, which were still connected there and so
never read their queue. For a clean session the queue refused it outright.
Every wildcard subscriber on that peer lost the same messages, which is why
the top ten lost 56,080 each, give or take. A persistent one got them at the
final reconnect, after the peer had delivered them already: those are the
QoS 2 duplicates.

Now a link whose peer is still reporting to the cluster (within 30 s) waits
for its window, as a publisher the peer holds waits. Only a link to a peer
that has gone quiet, or whose socket has closed, hands its messages back.

## 20261001

### Brokers coming and going on the console under load

The same load run, with every client connected throughout, had each broker's
band on the cluster charts drop to nothing and come back. Three things, each
enough on its own:

- The console's tick did its Rama work on the sampling thread. Its report
  waited up to 30 s for an append permit when a busy broker had none free,
  and its read of the other brokers' reports was a round trip to a busy
  Rama. While it waited, the broker took no samples of its own. A gap of
  more than three seconds (`carry-seconds`) is drawn as zero.
- A cluster chart ended 7 s ago, but a busy broker's points could take
  longer than that to come back from Rama. The second was drawn without
  that broker, and a page keeps a point once it has it.
- A report marked its points as sent when it went out. One that never
  landed left a hole in that broker's chart, on every other console, for
  good.

Now the report does not wait for a permit, and it sends its points again
until Rama says it has them. The read runs on a thread of its own, and the
sampling keeps one a second however long a tick takes. A cluster chart
stops at the last point of a broker that is late, for up to 20 s. A stale
broker's points still count: they are what it had at that second.

### Load with no chaos at all

`chaos/load.edn` failed at scale with nothing killed: Thomas's run at 10,000
publishes a second, 400 publishers and 1,600 subscribers, lost three million
messages and delivered seven million QoS 2 messages twice. On this machine a
three-broker run at 3,000 a second (120 publishers, 480 subscribers, 60 s)
lost 84,000. The load itself was fine. What broke it was its end: the runner
reconnects every persistent subscriber, while the brokers are still behind,
and each piece of what that set off had its own hole.

- **Rama writes that failed counted as done.** Each message for a session
  that was away was its own depot append. Two hundred subscribers dropping
  at once asked for tens of thousands, the appends timed out, and a failed
  enqueue was a message lost after its publisher had its PUBACK, a failed
  take-off a message sent again on the next resume. Queue writes now go
  through `cluster/queue-writer`: one lane per client, one batch in flight
  per lane, everything asked for meanwhile gathered into the next batch
  (neighbouring enqueues one append, take-offs another), and a batch that
  fails sent again, in order, until it lands. The module takes an
  `:enqueue` with `:messages`. Its callbacks run on a pool of their own: on
  Rama's client thread, one run stopped delivering altogether.
- **The queue limit was smaller than what a session brings.** Rama refused
  a session's messages past 4,096, the broker's own pending-limit, and a
  subscriber to every topic that drops while it is behind brings its full
  pending queue, its window, and what the bridges hold for it. Now 65,536,
  the bridge's own backstop, and refusals are counted as `queue-refused`.
- **A queue was read once.** A resume read what had landed by then, and a
  write that landed a second later waited for the next resume. A resumed
  session's queue is now read again at 2, 5, 10, 20, 40 and 60 s, and after
  that for as long as a read leaves some behind; the CONNECT reads only the
  first pending-limit, and the later reads take half that at most each, so
  live deliveries still find room. Keys the client has had, from the queue
  or live, are left out, and live deliveries are taken off the queue for as
  long as the reads go on.
- **A copy for a client that had moved on went nowhere.** The sender planned
  from a view a step behind and sent it to the broker the client had just
  left; that broker's view already had it connected elsewhere, so it neither
  delivered nor queued it. It now queues it, kept sessions only, and the
  window for "just left" (`view-lag-millis`) is a minute rather than two
  seconds: a link that is behind holds copies for longer than that.
- **A hand-over dropped the PUBREL.** A QoS 2 message the client had answered
  with a PUBREC was let go on a hand-over as an exchange the client could do
  without. It could not: it holds the identifier until the PUBREL, and took
  the next message a new broker sent under that identifier for the one it
  had. The PUBREL now goes on the queue under a key of its own, a resume
  sends it (§4.4), and the PUBCOMP takes it off.
- **A bridge gave up on a busy peer.** At the start of a run the peers were
  too busy with connecting clients to send a bridge its CONNACK within five
  seconds, and the link handed back what it held, delivered nowhere for a
  clean session. A link now asks again for up to 30 s while the peer takes
  the connection.

Also gone: the Rama write `hold-back!` made for every live copy a kept session
had no room for, from "Acknowledged, then killed" below. At 3,000 a second
those writes and their take-offs were most of what Rama was asked to do.

The same run now loses 161 QoS 2 messages, all for one subscriber within a
second, and delivers 155 twice. Those are still open.

## 20260930

### A broker that dies no longer takes its subscribers' messages with it

A message forwarded to another broker stopped being the forwarding broker's
business at the other one's PUBACK. That only says the other broker has it,
not that its subscribers do. If it died then, with the message still on its
way to a persistent subscriber or waiting for its acknowledgement, nothing
else had a copy, and the subscriber came back on another broker to a session
that had never heard of it.

Now the publisher's broker keeps each forwarded QoS 1 or 2 message after the
PUBACK (or the PUBREC, for QoS 2) in `awaiting` in `mqttkat.bridge`, until
the receiving broker says it is delivered. The receiving broker holds a
forwarded message for each client there that keeps its session and was sent
it, and lets it go when the client acknowledges it or when its session is
handed to the cluster's queue. When the last one lets go, it sends back
`$mqttkat/settled` with the message's key. Those go in batches every 100 ms,
one publish per broker, and for a message that no kept session here was sent,
the word goes back straight after the fan-out.

If the link to the receiving broker goes, the link now notices on its next
poll, not only on its next write. What that broker had taken and not said was
delivered is then queued in the cluster for the clients it was for, under the
message's key, a second later. The second is so word already on its way
still counts. A registry drop gives up at once. A hand-over queues under the
same key, so a broker that stopped cleanly and a publisher that gave up on it
make one queue entry, not two. This broker leaving the cluster forgets what
it was waiting for rather than queuing it, because its peers are still alive.
A message nobody has answered for after a minute is taken as delivered, on
both sides, so neither holds memory for a subscriber that never acknowledges.

What is still open around it:

- A client that had acknowledged the message on the dead broker, before
  that broker's word got back, gets it again on its next resume. For QoS 1
  that is allowed. For QoS 2 it is the "delivered twice across a crash" item
  that was already open.
- The copy is queued a second or so after the broker dies. A client that
  reconnected elsewhere before that gets it on its next reconnect, not
  straight away.
- A shared group's member on the dead broker is not covered: a copy for a
  group is let go at the PUBACK as before.
- A message a broker's own client published to its own persistent
  subscribers never crossed a bridge, so a crash still loses it in flight.
  The fix above has nothing to hold it with.

### Acknowledged, then killed

A three-broker run lost one QoS 1 message: published on broker 2 and
acknowledged at 25.9 s, then broker 2 was killed. Every other subscriber had
it, and one persistent session did not. This is not the bridge gap in "What
is still open" (a *peer* dying after taking a copy). Here the broker that
died was the publisher's own. It sent the PUBACK first and then did the
work, so for a moment the only copy for that session was in its memory. The
copy was in one of two places:

- a Rama append for the session's queue, sent with `foreign-append-async!`
  and not yet out of the client's buffer, when the session was away;
- the session's pending queue on this broker, waiting behind a full window,
  when it was connected here.

On a cluster the PUBACK now goes out after the deliveries, once those writes
have landed. For QoS 2 the PUBCOMP waits the same way. `*hand-offs*` collects
the writes a publish makes: `forward-publish!`'s enqueues for away sessions,
and `hold-back!`'s. `hold-back!` is new. A live copy that a kept session has
no room for is put on the cluster's queue under its message key as well as
held here, and it carries that key as its `::cluster-key`. From then on it is
one of the queue's own messages, and comes off when the client acknowledges
it, like anything read from there on a resume. A copy sent at once is left
alone, because a write the process has made reaches the peer even if the
process is killed. Nothing waits for a subscriber either. The PUBACK is still
the receiver's answer, and a stalled subscriber still holds its publisher
only through the pause threshold, as before.

The cost is a Rama round trip on the PUBACK of a publish that has someone
away or held back. A single broker, with no cluster, still acknowledges first.
The copies still waiting in this broker's bridge links are not covered. A link's queue
is usually empty, but a message in it when the broker dies is lost for every
subscriber on that peer. Waiting for the peer's PUBACK would add the bridge's
tail latency to every publish.

### The cluster's "max" clients

The overview's "of N max" for a cluster was each broker's own peak added
up. Those peaks need not have happened at the same time, and a broker that
restarts starts its peak again from zero, so the figure could be less than
the chart had just drawn: 80 max under a chart that reached 105. It is now
the cluster's own peak, the top of its chart over the history kept, and
never less than the clients connected now.

The tile and the chart also measure different moments. The tile is now:
this broker live, the others as of their last report, at most five seconds
old. The chart ends seven seconds ago, so each second has every broker's
point in it. So when clients leave, the tile shows it up to about twelve
seconds before the chart does.

### A colour per broker

With a cluster the overview added the brokers up, but said so only in a
small stamp, so it read like one broker's page. Now it is titled "Cluster
overview", has a card per broker under the head, and draws both charts as
stacked bands, one per broker, so the top edge is still the cluster's total
and each band is that broker's share. Hovering names each broker's figure.

Each broker gets a colour from a fixed seven-colour categorical palette
(checked for colour-blind separation against the page's background).
`cluster/palette` hashes the id to a preferred slot and probes for the next
free one, in sorted id order. So every console agrees without talking, and
a broker keeps its colour as others join and leave unless it collided with
one. Past seven brokers the rest share a grey rather than colours nobody
could tell apart. The same swatch marks the broker on the clients and
brokers pages, in the events list and on the broker's own page.

The throughput chart stacks in + out per broker: two lines per broker
stacked would be unreadable.

### One console for the whole cluster

The console was one broker's: its overview, topics and clients were read
from that JVM's atoms, and only the Brokers page knew there were others.
With brokers on different machines, a console can't read another broker's
memory, so each broker now reports what its own console shows to Rama
every five seconds, next to the figures it already put in the registry:

- `$$broker-detail`, broker id -> its latest reading, busiest topics, first
  fifty clients and recent events. A plain value, read whole.
- `$$broker-history`, broker id -> millis -> chart point, subindexed. The
  report carries the one-second points taken since the last one, and the
  topology drops points older than half an hour as new ones come in.

Both are only written for the announced run, like the stats, and both go
when the broker withdraws or is forgotten. They're kept out of the registry
on purpose, because every broker proxies the registry and it should stay
small. A console reads them only while someone is looking, at most once a
second.

The overview, topics and clients pages now add the brokers up. Clients,
heap, cores and rates are summed. Retained counts are not, because every
broker holds the cluster's retained messages. A client is shown on the
broker it's connected to. The cluster's chart is summed a second at a time
and ends seven seconds ago, because a report comes every five seconds and
a second that not every broker has reported yet would show as a dip. A
broker that skips a second on its own clock has its last point carried
forward for a couple of seconds. The broker serving the page always uses
its own live figures, not its copy in Rama. With no cluster there is one
broker, and every page reads as it did before.

`/brokers/<id>` is one broker's page: the overview's metrics, charts,
counters and events, plus its clients and topics, all from Rama. It also
links to that broker's own console at the host it advertises.

Message rates added across brokers count a message forwarded from one
broker to another once on each, because the receiving broker counts the
bridged PUBLISH as inbound. I haven't separated those yet.

## 20260929

### Chaos clients go where the brokers send them

Until now a chaos client started on broker `i mod N` and, after every drop,
came back to a random broker that was up. It never looked at a Server
Reference, and the cluster's redirect policy was whatever the console last
set. `:setup :redirect {:policy :via}` now sets that policy through broker
1's console before any client connects, with the same form post the Brokers
page makes. It is set on every run, `:off` by default, because the policy
lives in Rama and would otherwise carry over from one run to the next. With a
policy on, every client connects to the first broker that is up and follows
a CONNACK or DISCONNECT that sends it elsewhere. Progress lines show the
connected clients per broker.

The first run had two problems. `{:mqtt {5 1}}` merged over the default
`{4 1, 5 1}`, so half the clients were 3.1.1, which can't be sent anywhere,
and broker 1 kept two thirds of them. A weight of 0 is the way to take a
version out. Also, the `:disconnect` form accepts the client with a CONNACK
before sending it on, without making a session. The client took that CONNACK
as a session, and the next broker's Session Present 0 then read as a lost
session. The client now counts a session only from a CONNACK that is not
followed by a redirect.

On three brokers for a minute, with clients killed, disconnected and
toggling, round robin spread them 32/24/24 and the check was clean. Load
spread them 27/30/23 and lost 2 QoS 2 messages for one persistent client
that had been sent between brokers twice in a few seconds. I haven't looked
into those yet.

### The unexpected deliveries were the check's, and the Rama writes were mine

Every `:unexpected` delivery in the three-broker runs had been acknowledged
to its publisher between a few microseconds and about 20 ms before the
subscriber sent a SUBSCRIBE that matched it. The check took the PUBACK as
the moment the broker matched the message, but it isn't. The broker
acknowledges before it fans out, and another broker matches only when the
forwarded copy arrives. A subscription that lands in between gets the
message, and MQTT doesn't order a publish from one client against a
SUBSCRIBE from another. The check now counts a delivery as possible if the
SUBSCRIBE went out no later than `:subscribe-settle` after the
acknowledgement. That is the same allowance it already gives a subscription
before it owes one. Unexpected deliveries now also carry `:acked`,
`:pub-broker`, the next SUBSCRIBE and the chaos around them, which is what
it took to see this.

Reproducing this found a regression in queue-by-message-id. With publishers
killed as well as subscribers, and subscriptions toggling, Rama's depot
buffer filled: 20,000 lost, 24,000 unexpected. The code before it lost 72
on the same run. There were two causes:

- The receiving broker queued a bridged copy for every client last seen
  there that was away. With clients down for up to three seconds at a time,
  that doubled the enqueues. It now queues for those its own copy still has
  connected, and for those that left in the last two seconds, which it
  knows without asking the cluster.
- Taking live deliveries off the queue went out every 250 ms per client. It
  now goes out once a second.

After both, the same run has none lost and none duplicated, with no buffer
errors. chaos/three-brokers.edn had 3 lost, all on subscriptions that ended
in an UNSUBSCRIBE, 2 of them next to a killed broker.

### Queued by message, not by moment

The three-broker chaos run still lost QoS 1 and 2 messages and delivered QoS
2 twice, all around clients that had just moved. Both came from the same
place. The publisher's broker decides from its own copy of the cluster,
which lags; the broker the client is on delivers to whoever is live there.
Nothing reconciled the two.

- Lost: the publisher's broker still had the client on broker X and
  forwarded it there. The client had just left X, and X, attached to Rama,
  queued nothing.
- Twice: the publisher's broker had the client away and queued it, while X,
  which had it live again, delivered it too. The copy on the queue went out
  again, under a new packet identifier, on the next resume.

Queuing on X as well fixed the first and made the second worse, because the
two queue entries had different keys. Now a QoS 1 or 2 publish gets a name
where it enters the cluster, the time then eight random hex digits, and it
travels with every forwarded copy as the user property `mqttkat-msg`. Every
queue entry for it uses that name, and `$$queued` already overwrote by key,
so however many brokers decide a client is away, it is queued once.
The hand-over uses the same name, so an in-flight message handed over
merges with a copy another broker queued.

With that, X queues a bridged copy for the clients its own copy of the
cluster has here, or last had here, and that it did not deliver to. For the
other half, a live delivery to a persistent client that connected in the
last ten seconds is marked, and when the client acknowledges it, it comes
off the cluster's queue under its name, then again five seconds later for a
copy queued by a broker whose word arrived late. Those take-offs are
batched every 250 ms. Unbatched, one Rama append per message filled the
depot buffer at once, and the run lost 180,000 messages.

On the client-kill run (three brokers, one minute, every session
persistent), lost went from 227 to 1,079 per run to 0 and 3, and duplicates
from 64 to 442 to 0 and 1. chaos/three-brokers.edn went from 915 lost and
650 duplicate to none of either. Its 11 `:unexpected` deliveries are still
unexplained.

### Ctrl-C against a cluster printed a screenful

Stopping a broker attached to a real cluster logged "Executor pool is shut
down" with a stack trace, over and over. Closing a proxy only hands its
teardown to the cluster manager's executor, and close! shut that executor
down straight after, under a hundred and thirty teardowns still queued. The
Rama tab's proxy, busy every second, made it hard to miss. unwatch! now waits,
up to five seconds, until every proxy reports it is no longer active.

### A Rama tab on the console

`/rama` shows what the module holds and has been through, as the module
counts it: sessions stored, connected and parked, subscriptions, retained and
queued messages, sessions expired, and every `*session-events` record by kind
with a rate. Brokers registered comes from `$$brokers`, which every broker
already watches.

Counting by scanning was never on: `$$sessions` is a top-level map, and
counting its keys walks them. So each task keeps its own counts in
`$$counts`, keyed by task like `$$expiring`, and the topology bumps them where
it makes the change it counts, which costs a read and a write and no hop. The
gauges are read before and after the write they count, never worked out from
the event, so a record run twice (a stream topology is at least once) counts
nothing the second time; the per-kind event counts do count it twice, which a
throughput can bear. A tick once a second copies each task's counts under one
key of `$$rama-stats`, a proxy on that key pushes them to the broker, and the
broker pushes them on to any open Rama page the moment they arrive. Rates are
worked out over the time between two of a task's copies as Rama stamped them,
not between two samples here.

A ramaop cannot see the topology's PStates, and a segmacro would not
normalise, so the bump is a plain macro around `<<atomic`. On a cluster that
already holds data, an update to this module starts every count at zero:
they are right about what changes after, not about what was there.

### A stopped broker forwarded nothing while it stopped

The three-broker chaos run lost messages for clean sessions next to a
`:stop-broker`. On SIGTERM the shutdown hook left the cluster first, which
also removes the bridge's planner and forwarder, while the MQTT server was
still listening. For as long as the rest of the shutdown took, the broker
acknowledged publishes and delivered them to its own clients only. Every
subscriber on another broker missed them, and a clean one stayed connected
long past the acknowledgement, so the check rightly counted them as lost.

The hook now runs `stop!`: close the listener and the clients, then leave
the cluster. `stop!` had its own bug: it called the server's stop function as
the test of a `when`, which returns nil, so the rest of it never ran. With
only `:stop-broker` for chaos, a minute on three brokers went from 809 lost
to none.

Two more gaps turned up in the same run, both when a session moves between
brokers. See "Queued by message, not by moment" above for both.

### The chaos check took longer than the run

After chaos/long.edn drained, the runner seemed to hang. It was checking, with
nothing on screen. The check tried every subscription against every message on
its topics. A subscriber resubscribes all run long, so both grow with the
run's length and the check with its square: a 150 s run took 3.5 minutes to
check, ten minutes would have taken the better part of an hour.

A message can only be owed to a subscription if it was sent while the
subscription lasted, so messages are now sorted by when they were sent and
each subscription looks at its own stretch. Whether a delivery could be
explained at all is asked only of the messages actually delivered, of the
subscriptions that had not ended when it went out. Clients are checked in
parallel, and the runner says it is checking. The same 150 s run now checks in
under a minute on four cores, and a test holds the new lookups to the rules
applied the slow way.

### The chaos runner ran out of heap

`chaos/long.edn` (50 publishers, 300 subscribers, 2000 a second) took the
runner's JVM out of heap after three minutes. It was keeping one
`[msg at qos broker]` per delivery, and 36 million of them by then. Now a
delivery is four bits (per client, per publisher, by sequence number: seen,
seen again, at QoS 1 or more, at QoS 2), with the detail kept only for the
first ten thousand repeats, which is what a :duplicate is reported with. The
check goes one client at a time instead of building a verdict per client per
message for all of them at once. And `chaos.bb` gives the runner `-Xmx` from
`:setup :runner :heap`, 4g unless a scenario says otherwise.

### The chaos run's two open items, closed

Thomas ran `chaos/single-broker.edn` on master and it failed with lost QoS 1
and 2 messages: the two gaps written down yesterday, not something new. Both
are fixed, and the scenario now passes, with in-process Rama and without.

- **A publish as a persistent session drops.** `remove-client!` now parks the
  session in the offline trie while it is still live, and only then takes it
  out of the index and the live trie. Whether a subscriber is live is decided
  once per publish, at delivery (`live-key?`: its key is the one the index
  holds), and the deliveries return the client-ids they reached.
  `queue-for-offline-sessions!` leaves those out, so the overlap in the two
  tries gives one copy, not two, which QoS 2 needed. Resume does the same in
  reverse: live trie, then the index, then out of the offline trie.
  Attached to a cluster, the cluster's word on who is connected lags the
  broker's, so the plan now names every client connected here as well
  (`:leaving`) and `forward-publish!` queues, if the session is kept, any of
  those and of the away ones that the broker did not deliver to live.
- **A PUBLISH before the CONNACK.** `add-client!` marks the connection
  `:awaiting-connack?`, and the connect handler clears it once the CONNACK is
  written. Until then QoS 1 and 2 deliveries go on the session's queue, which
  the flush after the CONNACK sends, and QoS 0 ones are dropped (at most once
  allows it).
- **A QoS 2 message delivered twice**, found by the reruns once nothing was
  lost any more. A message in flight when its session dropped went to the
  cluster's queue without its packet identifier, and came back on resume
  under a new one, so a client that had it already could not tell. And one
  the client had answered with a PUBREC was handed over, or redelivered on
  its own, as a PUBLISH, where §4.4 owes it the PUBREL. Now the identifier
  travels with the queued message and the resume puts it back in flight
  under it, and a PUBREC marks the message so that a resume sends the PUBREL
  and a hand-over leaves it out.

## 20260928

### A chaos run

`bb scripts/chaos.bb chaos/<scenario>.edn` starts Rama (`rama.bb`) and the
brokers (`brokers.bb`), puts publishers and subscribers on them, and for the
length of the run kills clients, disconnects them, has subscribers unsubscribe
and subscribe again, and kills or stops brokers and brings them back, all at
random intervals the EDN file sets. Then it checks every message against what
its QoS promised and exits 0 only if nothing broke a promise. The scenarios are
in `chaos/`: `three-brokers.edn` does everything, `three-brokers-calm.edn`
leaves the brokers alone, `single-broker.edn` needs no cluster and is what CI
runs, and files merge left to right, so `long.edn` stretches any of them.

The load runner could not do this: its clients are clean sessions that never
come back. The chaos clients (`mqttkat.chaos.client`) keep what MQTT says a
client keeps. A persistent one reconnects with Clean Session 0, wherever a
broker is up, and expects its session; a QoS 2 one remembers the identifiers
it has PUBRECed across reconnects, so a resend is not counted as a second
delivery; a SUBSCRIBE or UNSUBSCRIBE the connection dropped under is sent
again. Everything they see goes into one ledger, timed on one clock.

What is owed is decided per subscription (`mqttkat.chaos.check`): a QoS 1 or 2
message, at the lower of the two QoSes, acknowledged to its publisher,
published at least `:subscribe-settle-ms` after the SUBACK and acknowledged
before the UNSUBSCRIBE went. A clean session is also owed only what it outlived
by `:clean-grace-ms`. What is owed and missing is `:lost`; QoS 2 or QoS 0 more
than once is `:duplicate` (QoS 1 repeats are counted, not wrong); a delivery
no subscription could explain is `:unexpected`; a persistent session missing
on reconnect is `:session-lost`; and a run in which nothing was owed fails as
`:nothing-checked` rather than passing on nothing. A loss within a few seconds
of a killed broker is counted apart, as `:near-broker-chaos`, since those are
the gaps listed below.

The first runs, one broker, found two bugs, both fixed:

- A persistent version 5 session resumed from the cluster was answered in
  3.1.1. `adopt-session!` parks the cluster's copy of a session, which is its
  subscriptions and nothing else, and `add-client!` resumed the parked entry
  as the new connection's record: no protocol version, so what was queued went
  out without a property block and the client could not parse it, and no
  Session Expiry, so the next disconnect discarded the session. The resumed
  record now takes the connection's terms from the CONNECT. 749 of 31,476
  owed messages were lost to it in a fifteen-second run.
- A QoS 0 subscription kept a QoS 1 or 2 message for its session while it was
  away, at QoS 0. Nothing acknowledges QoS 0, so it stayed in the window and
  went out again on every reconnect. `queue-for-offline-sessions!` now skips a
  subscription whose QoS is 0, as the cluster's `forward-publish!` already did.

And two it did not fix, now in the list below: a publish in the moment a
persistent session drops is lost, and a resumed session can be sent a PUBLISH
before its CONNACK. A thirty-second run of `single-broker.edn` still ends with
a few dozen of the first and about ten of the second, which is why the chaos
job in CI does not block yet.

### What is still open

Everything known and not done, in one place. The "Still open" list under
20260925 is out of date: the late-teardown guard for persistent sessions went in
that day (e4dec76), the late expiry below closes the other half of it, and the
Paho MQTT 5 suite now runs in CI (`paho-v5` in `test.yml`).

Several brokers, after today. A broker killed with persistent subscribers
connected no longer loses their messages. What it could not hand to the dead
broker is queued in Rama for them, and a client that comes back on another
broker gets it there. A shared group whose chosen broker is dead is served by
another member's broker. Left over:

- ~~**Messages at the dead broker when it died are lost.**~~ Fixed for
  forwarded messages on 20260930, see "A broker that dies no longer takes its
  subscribers' messages with it". A message the dead
  broker had acknowledged over the bridge, and was still sending to its
  subscriber, died with it. Nothing else holds a copy. Fixing it means the
  publisher's broker keeps each message until the end subscriber has
  acknowledged it, not just the next broker.
- **A dead broker is listed for ten minutes.** `broker-forgotten-after-millis`
  is 600000, against a report every five seconds. Nothing is lost in that
  window any more, but the other brokers try its bridge every five seconds, its
  clients' session expiry only starts once it is forgotten, and the console
  shows it as `stale`. Left at ten minutes on purpose, see "Why the timeout
  stays at ten minutes" below: a broker forgotten by mistake still loses
  what was published for its clean sessions, and its QoS 0, while it is.
- ~~**A live broker cut off from Rama is treated as dead.**~~ Fixed, see
  "A broker forgotten while it lived gets its clients back" below. What it left
  behind is the next point.
- ~~**Messages queued for a broker's clients while it was forgotten wait for
  their next reconnect.**~~ Fixed, see "What was queued while a broker was
  forgotten is sent once it is back" below. The other half stands: **a clean
  session's subscriptions are gone for that window**, so what was published
  for it then is not kept at all, and neither is QoS 0 for a persistent one.
- **A queued fallback is late when the client is actually still connected.**
  The fallback queues for a peer's persistent sessions whenever that peer does
  not acknowledge. If the peer is alive but this broker cannot reach it (the
  brokers are partitioned from each other, not from Rama), or the client moved
  in the moment before, the message waits in Rama until the client's next
  reconnect. It arrives late and out of order, but it arrives.
- **QoS 2 through the fallback can be delivered twice.** A QoS 2 message whose
  PUBREC was lost in the crash is queued anyway, and the dead broker may
  already have delivered it. Exactly-once does not hold across a broker crash.
- **A shared group with no member left anywhere drops the message** (logged).
  It could be queued for one of the group's persistent members instead.
- **The v5 reason code ignores subscribers on other brokers and offline
  sessions.** The PUBACK's 0x10 No Matching Subscribers is decided on `keys`,
  the subscribers connected to this broker (`qos-1`, and
  `anyone-to-deliver-to?` for the PUBREC). A publish that only subscribers on
  another broker match, or only an offline session, was forwarded or kept, and
  the publisher is still told nobody matched.
- **QoS 1 between brokers is slower in the tail.** At 5,000/s with 300
  publishers and 3,000 subscribers, the median is 205 ms over three brokers
  against 119 ms on one, but the p95 is 1.6 s against 0.4 s. There is one link
  per pair of brokers, and each queues behind itself.
- ~~**The multi-broker failure tests are manual.**~~ `scripts/chaos.bb`, see
  "A chaos run" below. CI still cannot reach a real Rama cluster, so the
  three-broker scenarios run by hand; CI runs the one-broker one.
- **`scripts/rama.bb` has no wipe.** The cure the README gives for a wedged dev
  cluster (destroy and relaunch the module, or clear `local-rama-data` and
  `local-zk`) is still done by hand.

From before, not re-checked today:

- `test_unsubscribe` in the Paho v5 suite, flaky in the run and unexplained
  (see 20260925). Now that the suite runs in CI, its history there will show
  whether it still happens.
- `test_subscribe_failure`, by decision (see 20260909), and the two races in the
  Paho suite itself, which are upstream's to fix.
- The 53 of 150 subscriber sockets with an empty Send-Q, the regex split in
  triennium's `split-topic` on the publish path, and the median latency MQTT 5
  cost.

### What was queued while a broker was forgotten is sent once it is back

While a live broker was forgotten, the other brokers queued QoS 1 and 2
messages in Rama for its persistent sessions, and those waited there until the
client next connected, which for a client that never dropped could be days.

`restate-clients!` now waits for the cluster to have its `:still-connected`
records, and for each connection the cluster took back (the record names this
connection, on this run, connected) reads the client's queue and hands it to
`handlers/deliver-queued!`. That puts each message on the broker's own queue
exactly as `adopt-session!` does on a resume, carrying its cluster key, so it
is flushed at once and comes off Rama's queue only when the client
acknowledges it. A connection the cluster did not take back, or one that has
gone or been replaced here by the time the queue is read, is left alone: its
queue waits for the next resume, which reads it whole.

The queue is read twice, the second time `restated-settle-millis` (2 s) later.
The other brokers learn the client is back through their proxies, a moment
after the cluster does, and a publish they matched in that moment is still
queued. Reading twice cannot send anything twice: a key already waiting or in
flight here, or taken by the first read, is skipped. The test in `rama_test`
queues one message while the broker is forgotten and one after it is back, and
checks each arrives once and leaves the queue on its PUBACK.

### Why the timeout stays at ten minutes

`broker-forgotten-after-millis` is the price of two mistakes, and the tests
only settle one. A broker that is really dead and still listed costs latency
and noise, not messages: the others try its bridge, fail, and queue for its
persistent sessions, which get everything on their next connect. A broker that
is alive and forgotten now gets its clients back and, after today, their queued
messages too. But for as long as it is forgotten its clean sessions have no
subscriptions in the cluster, so what is published for them then is dropped,
and QoS 0 for its persistent sessions is dropped as well. `rama_test` asserts
exactly that ("a clean session lost its subscriptions").

So a shorter timeout turns more stalls (a long GC, a slow Rama leader
election, a network blip to the cluster) into lost messages, to save a stale
console row and ten minutes of bridge retries for a broker that really died.
That is the wrong way round while the second mistake still loses anything.
The order stays: make a false forget lossless for clean sessions too (keep
their subscriptions, marked as away, instead of dropping them, and let the
restatement or the sweep's own expiry settle them), then shorten.

### A broker forgotten while it lived gets its clients back

A broker cut off from Rama for longer than `broker-forgotten-after-millis` is
dropped from the registry and its run goes on `$$dead-runs`, and the sweep then
tells every client of that run `:lost`. When the broker could reach Rama again
it announced itself under the same run, but its clients stayed recorded as
disconnected, so the other brokers queued for them instead of forwarding.

Three changes. `:broker-up` takes the announcing run off `$$dead-runs`, so the
sweep stops letting its clients go. A `:lost` asks the registry's partition
whether its run is still dead before it touches anything, so one still on its
way after the announcement does nothing. And the broker, when its own copy of
the registry shows it listed again after it had been dropped, sends a
`:still-connected` for every connection it holds, with the CONNECT's terms and
its subscriptions. On seeing itself listed rather than on announcing, because
by then the run is live again in Rama, and a `:lost` processed after that
finds it so.

The module takes a `:still-connected` only for the connection the sweep let go
(`:lost?` on the record, which a `:lost` sets and a disconnect clears) or for a
client with no record left, whose session expired in the meantime. A client
that disconnected for real while the broker was cut off, or has connected again
anywhere, is left as it is. A lost clean session gets its subscriptions back
from the broker's copy; a kept one is no longer due to expire. Two tests in
`rama_test`, one on the module with a made-up broker, one through this broker
with a real client.

One narrow race is left. A `:lost` that read the registry just before the
announcement was processed, and gets back to the client's partition only after
the broker's restate was processed there, still lets the client go. That needs
a registry round trip on a cluster worker to take longer than a proxy push to
the broker plus an append back, which the tests have never shown.

### A session expiry that had already fired could empty a resumed session

The last open half of the late-teardown race from 20260925. Parking was already
guarded; expiry was not. `cancel-session-expiry!` kills the timer, but `at/kill`
stops a job that has not started, not one that is running, and the job took
itself out of `session-expiries` before discarding. A reconnect landing in that
window found nothing to cancel, resumed the session and said so in the CONNACK,
and then `discard-session!` emptied `*outbound*` and `*inflight*` under it,
keyed by client-id as they are. The redelivery found nothing to redeliver, and
the next delivery numbered identifiers from 1 again.

Each expiry now carries a token, and the job only discards if its token is still
the one filed, checked under the same lock `cancel-session-expiry!` takes. So a
cancel either lands first and the job stands down, or waits until the session is
gone and the client gets a fresh one, which is what an expired session is. The
token also stops a job that was held up past a resume and a second disconnect
from cutting the second park short. `v5_session_expiry_test` drives all three
cases by hand.

Run this to test a different broker, for instance Mosquitto. This is to also check if our tests are any good:

```
MQTT_BROKER_HOST=localhost MQTT_BROKER_PORT=1883 lein test :mosquitto
```

## 20260925

### The v5 failure list, refreshed

Read against the Paho suite as it stands upstream (eclipse/paho.mqtt.testing
at 9d7bb80, `interoperability/client_test5.py`), not measured: the broker could not be built
where this was written. The failure list under 20260908 is stale — every one of
the ten it names was fixed on 20260909 — and the "range, not a number" notes
under 20260909 get one cause wrong. What is left:

- **`test_subscribe_failure` — fails every run, by decision.** Needs a deny
  policy for `test/nosubscribe`; see 20260909. It also exits without
  disconnecting `aclient`, so the next test (`subscribe_identifiers`) always
  starts with a takeover of a socket the client has already closed.
- **`test_subscribe_options` — flaky, the test's race.** Line 554 waits on
  `callback.subscribeds` after subscribing _bclient_; it should be
  `callback2.subscribeds`. As written it never waits, and aclient's PUBLISH can
  reach the broker before bclient's SUBSCRIBE. Still unfixed upstream.
- **`test_request_response` — flaky, the same race, not retained state.**
  Line 682 is the identical `callback.subscribeds` wait after `bclient.subscribe`.
  The retained-message explanation cannot be right: tests run alphabetically,
  and the only two that publish retained messages (`retained_message`,
  `subscribe_options`) both run _after_ it, and `setUpClass` clears retained
  messages first.
- **`test_unsubscribe` — flaky in the run, passes alone.** It runs straight
  after `subscribe_options`, and when that one loses its race it does so at the
  No Local step, before it publishes anything retained — so not retained state
  either. What it does leave behind is
  both clients still connected, with bclient subscribed to TopicA, so
  `unsubscribe` opens with two takeovers of sockets the client closed without a
  DISCONNECT. Cause not pinned down; the takeover is the lead.

With the two waits corrected in a local copy of the suite, the expected result
is 26 of 27 every run. If `unsubscribe` still fails after that, it is the
broker's, and the broker log for that test is the next thing to read.

One latent race found on the way, which is not known to cause any of these:
`remove-client!` drops `*outbound*` and `*inflight*` by client-id for a session
that is not kept, without the check `forget-live!` makes first. If a displaced
connection's own teardown runs after its replacement has started delivering, it
throws away the new connection's in-flight window, and the next delivery
starts numbering packet identifiers again from 1.

_Later:_ fixed for a clean session. `remove-client!` now leaves `*outbound*` and
`*inflight*` alone when another connection is already registered under the
client-id, and `session_takeover_test` covers the late teardown. A persistent
session takes a different path when it is parked, and whether a late expiry
there can empty a reconnected client's queue is still open. _Later:_ it could,
and does not any more; see 20260928.

### What was fixed today, and what is still open

Fixed, each with a test:

- **A will could go out twice.** Several paths reach `handle-will-if-present`
  for one connection (the keep-alive reaper, the socket closing, a takeover),
  and the will stayed on the client after it fired, so a persistent session
  reaped by keep alive was parked with it. It is taken off in the same atomic
  step that reads it now, so it goes out at most once. The `TODO` in
  `check-timer` asking for exactly this is gone.
- **QoS 2 spent two shared-group turns per message.** The PUBREC ran
  `subscribers-for` only to pick its reason code, which advanced the rotation,
  and the PUBREL picked again. A two-member group sent every QoS 2 message to
  the same member. The PUBREC now asks whether anyone matches without choosing
  who. The No Local and coalescing gap under 20260908 was already closed;
  `v5_qos2_delivery_test` now pins all three rules on the QoS 2 path.
- **A keep-alive timer could outlive its client.** See the struck entry under
  "Found on the way, not fixed".
- **UNSUBSCRIBE left queued messages to be delivered.** §3.10.4 allows either;
  the broker now drops pending, not yet sent, messages that only the removed
  subscriptions matched. Anything another subscription still matches, or that
  was already picked for a shared subscription, stays, and in-flight QoS 1 and
  2 still complete. Cluster-queued ones are settled so a resume does not
  resend them.
- **The late-teardown race** above, for clean sessions.
- **`lein test` runs in GitHub Actions** on every push and PR, Java 21, with the
  `^:performance` simulations left out. The cloud sessions I work from cannot
  reach the Rama repository, so a PR's CI run is now the only way they can
  test anything.

Still open:

- ~~The same late-teardown guard for persistent sessions.~~ Fixed on 20260928.
- `test_unsubscribe` in the Paho v5 suite, flaky in the run and unexplained.
- The Paho conformance suites are not in CI, so a conformance regression only
  shows when someone runs them by hand.
- `test_subscribe_failure`, by decision (see 20260909), and the two races in
  the suite itself, which are upstream's to fix.
- From older lists, not chased: the 53 of 150 subscriber sockets with an empty
  Send-Q, the regex split in triennium's `split-topic` on the publish path, and
  the median latency MQTT 5 cost.

## 20260909

Carried on with version 5, working down the conformance failures. **23-26 of 27
now pass, from 16 yesterday**, and the 3.1.1 suite is 9 of 10 — it was three
failures when I first ran it. A range rather than a number because two of these
tests race against themselves and a third inherits state from the one before
it; all of them pass in isolation. See the notes at the end. 266 unit tests,
2794 assertions.

Almost none of what follows was a missing version 5 feature. Most of it was
already-broken behaviour that only became visible once something looked.

### The matcher never matched `sport/#` against `sport`

§4.7.1.2: "the multi-level wildcard represents the parent and any number of
child levels", so `sport/#` matches `sport` as well as `sport/tennis`.
triennium's matcher walks a level at a time and consults the `#` child of each
node it _passes through_ — but never of the node the topic ends on. So the
parent level, the one the paragraph is specifically about, was the single case
it got wrong:

```
a/b   -> #{:exact :plus :hash-above}      ; a/b/# missing
a     -> #{}                              ; a/# missing
```

A subscription to `sport/#` silently missed every message published to `sport`
itself. Nothing in the broker's own tests had ever subscribed to a filter that
was a prefix of the topic, and nothing had noticed.

Replaced with a recursive matcher of our own, four lines of actual logic, where
the empty-segments case returns this node's values _and_ any `#` beneath it.
That is the whole fix, and it is the whole of §4.7.1.2.

### Overlapping subscriptions delivered twice

§3.3.4 permits either: one copy per matching subscription, each with its own
Subscription Identifier, or a single copy carrying all of them. The broker sent
one per subscription. The Paho suite demands the coalesced form — stricter than
the specification, but it is also the better answer, since the identifiers exist
so a client can tell _why_ a message reached it, and being told the same thing
twice with half the answer each time is not that.

`coalesce-subscriptions` collapses each client's matching subscriptions into one
delivery, at the highest matching QoS (§3.3.5-1) and carrying every identifier.
Shared subscriptions are deliberately left out of it: a client subscribed both
ordinarily and as a group member has asked for the message twice, in two
capacities, and §4.8.2 keeps those independent.

### One pipeline, and what was skipping it

Three steps have to happen between "who matches this topic" and "who gets it":
drop what No Local excludes, collapse each shared group to one member, coalesce
each client's subscriptions. They were applied at each call site separately, or
not at all — `pubrel` called `qos-2-send` with the raw trie result, so **No Local
and shared subscriptions simply did not apply to QoS 2 messages**, and a will
skipped them too. All four paths go through `subscribers-for` now.

I had flagged this yesterday and left it as "a behavioural change beyond
aliases". It was, and it was also the bug behind `test_subscribe_options`.

### The retain flag was only ever right at QoS 0

`send-publish!` — which is every QoS 1 and 2 delivery — hard-coded
`:retain? false`. So Retain As Published (§3.8.3.1) did nothing above QoS 0, and
neither did §3.3.1.3's requirement that a replayed retained message arrive with
RETAIN set. A bridge subscribing at QoS 1, which is the case Retain As Published
exists for, saw every retained message arrive as an ordinary one and mirrored it
onward as ordinary.

### Four more places properties were dropped

Yesterday it was QoS 2 deliveries and wills. The same defect, in four more
places, all found by the same symptom in the Paho output:

- **retained messages** stored only `{:qos :payload}`, so a subscriber arriving
  later got a message stripped of everything the publisher attached — which is
  precisely the difference a retained message exists to remove.
- **the offline queue** stored `{:topic :payload :qos}`, so a message that
  waited for its session arrived stripped while an identical one delivered live
  did not. It also meant there was no Message Expiry Interval left to expire it
  by.
- **redelivery on reconnect** built the PUBLISH by hand and never set the
  protocol version, so no property block was written at all — and a version 5
  client reads the byte where that block should be as the first byte of the
  payload. The packet is malformed; the redelivery arrives as nonsense or not at
  all. This was the source of the `IndexError` tracebacks the Paho client had
  been printing all along, which I had been treating as harness noise.
- **retained wills**, stored at CONNECT, the same way.

The pattern is always the same: a delivery map written out by hand as topic,
payload and QoS, next to another path that passes the whole message through.
Every one of them was correct when it was written and wrong the moment version 5
gave a PUBLISH properties.

### The features that genuinely were missing

**Assigned Client Identifier (§3.2.2.3.7).** A zero-length client id means "you
name me", and version 5 requires the name back in the CONNACK. The broker
stored every anonymous client under `""` — so §3.1.4's takeover rule fired
between them, and **each anonymous client knocked the previous one off with
0x8E**. That is the kind of bug a conformance test finds and a unit suite does
not, because you have to think to connect two clients with no name.

**Server Keep Alive (§3.2.2.3.5).** A cap of 60, sent only when the broker is
actually overriding — §3.2.2.3.5 has the client use its own number when the
property is absent, so echoing it back would be noise. The broker's own timer
uses the negotiated value: having told a client to use 60 it cannot go on
timing it out against the 120 the client asked for.

**Maximum Packet Size (§3.1.2.11.4).** "Where a Packet is too large to send, the
Server MUST discard it and behave as if it had completed delivery." Two paths,
because QoS 0 shares one encoded buffer across a whole group of subscribers —
there the group is filtered by each client's limit rather than the buffer being
rebuilt per client, since the buffer is identical and only the limit differs.

**Message Expiry Interval (§3.3.2.3.3).** Both halves: discard a queued message
whose interval has run out, and send on the interval _reduced by the time it
spent waiting_. The subtraction is the half that is easy to leave out, and
without it a message queued for an hour arrives claiming its full lifetime still
ahead of it, with every hop resetting the clock.

**Topic filter validation (§4.7.1).** `sport/#/tennis`, `sport#`, `sp+ort` were
all accepted with a Success reason code. That is worse than refusing them: the
subscription goes into the trie, matches by accident or not at all, and the
client has been told it worked. Now refused per-filter in the SUBACK — 0x8F for
version 5, 0x80 for 3.1.1, which is the only failure code it knows.

### One discard path, three reasons

Maximum Packet Size, Message Expiry and the ordinary send all end at
`send-publish!`, which now returns whether it sent. That matters because the
packet identifier is reserved _before_ the packet is built, so a discard has to
give it back — otherwise every oversized or expired message leaks one, and after
enough of them the subscriber's window is full of things that were never sent
and delivery stops for good. Three callers release on false.

### What is left

`test_subscribe_failure`, in both suites. It requires the broker to refuse a
named topic filter with 0x80, which is an authorisation policy — the suite has a
`-n` option to tell it which filter the broker is configured to deny. This
broker has no authentication of any kind, so a deny-list would be half a feature
answering half a question, and version 5's honest code for it would be 0x87 Not
authorized rather than the 0x80 the test hard-codes.

**Decided: leave it.** This is the permanent single failure in both suites — 26
of 27 and 9 of 10 — and it is a feature the broker has chosen not to have rather
than a defect. Worth writing down, because the failing line looks like a bug
every time someone runs the suite: the broker answers a SUBSCRIBE for
`test/nosubscribe` with granted QoS 2, the test wanted 0x80, and the whole
difference is an authorisation policy that does not exist. The machinery to
_report_ a refusal is there and working — it is what returns 0x8F for a
malformed filter. What is absent is anything that decides a well-formed filter
is not allowed.

### Retained messages that expire

§3.3.2.3.3's Message Expiry Interval was implemented for the offline queue —
the case the conformance suite exercises — and not for the retained store,
which is the other place a message sits and waits. §3.3.1.3: "If the current
retained message for a Topic expires, it is discarded and there will be no
retained message for that topic."

It matters more here than in the queue, because a retained message is the one
thing in the broker _meant_ to sit indefinitely. An interval on one is a
publisher saying how long its answer stays true, and a broker handing out a
stale answer for ever is worse than one with no answer at all.

Entries are stamped when stored, and everything now reads them through
`retained-for-delivery`, which returns nil for both "nothing retained here" and
"what was retained has expired" — the same thing from a subscriber's point of
view, since §3.3.1.3 makes an expired retained message no retained message
rather than one the broker is withholding. What does come back has the interval
counted down, the same rule as a queued message: a retained message published
with a ten minute life must not still be claiming ten minutes an hour later, or
every hop that passes it on resets the clock.

The store is also swept every ten seconds. Delivery is already safe without
that — the accessor refuses an expired message whatever the map still holds —
but `$SYS` and the console both _count_ what is in the map, and an entry nobody
ever subscribes to again would be held, and reported, for the life of the
broker.

A retained will is stamped the same way. It was not, so a Will Message
published with an expiry interval would have been the one retained message that
never expired.

### A flaky test that was a real race

`session_takeover_test` failed twice under full-suite load and passed every time
in isolation, which is the shape of a test problem. It was not one.

Sending a client a DISCONNECT before closing its socket meant queueing the
packet and then closing. `Connection.close` lands STOP_WRITING _behind_
whatever is already queued, so the writer does send it — but
`MqttServer.closeConnection` then shut the channel without waiting for the
writer to get there. Whether the client saw its DISCONNECT was a race, and the
code said so: a `Thread/sleep 25` on the Clojure side with a comment admitting
it was "a race made unlikely rather than a race removed", because "draining
properly means waiting on the writer, which is a change to every close in the
broker".

Under load, 25 milliseconds is not enough. And the consequence is not cosmetic:
§4.13.1 exists because a displaced client that gets an unexplained close
reconnects, and takes the connection straight back off whoever displaced it.

So: a `CountDownLatch` opened when the writer loop exits, and `closeConnection`
waits on it — bounded at 500ms, because a writer blocked on a peer that has
stopped reading must not be able to hold a close open. Three `Thread/sleep 25`s
and their apologetic comments are gone with it. Four consecutive full runs
clean afterwards, where two of the previous handful had failed.

Worth noticing that the flaky test was the only thing pointing at this. The
conformance suite never caught it, because its clients do not check what
arrives before a close.

### I measured a stale broker again

Second time this session, and this one nearly went into the write-up. The
broker I started failed to bind — an older instance was still on 1883 — so the
conformance run I took afterwards was against the _previous_ jar, and reported
numbers that were not this code's.

The tell was there in the log and I did not read it: no "Server starting on
port 1883" line, and no stats lines at all, just a `BindException` at the top.
The routine now is to grep the startup line and confirm the listening socket's
pid is the process I started, before believing any run:

```
ss -ltnp | grep 1883      # whose pid owns it?
grep "Server starting" broker.log
```

The v5 suite on a verified-fresh broker gives **23 to 26 of 27** depending on
the run, and 3.1.1 gives **9 of 10** consistently. The spread is entirely the
suite's own flakiness — the `subscribe_options` race described above, and
`request_response`/`unsubscribe` failing on retained state a previous test left
behind — and all four pass in isolation except `subscribe_failure`, which needs
an authorisation policy the broker does not have.

### What MQTT 5 cost, and getting it back

Nobody had measured any of this. Two days of work went onto the hottest path in
the broker — a hand-written trie matcher replacing the library's, a coalescing
pass per publish, an alias decision per subscriber, size and expiry checks per
delivery — and the only numbers anyone had were correctness ones.

**Method.** The last pre-v5 commit built into its own jar from a `git worktree`,
and the two brokers run alternately rather than one after the other: this
machine runs 24 niced `dnetc` processes, one per core, so absolute numbers mean
nothing and only paired differences do. One client build drives both arms, so
the load generator is a constant. 50 publishers, 1,000 subscribers, 50 topics,
QoS 0, unpaced — the same shape as the gathering-writes measurement, and a
20-way fan-out per publish, which is where per-subscriber costs show up.

**The first answer, three pairs, every pair the same sign:**

|         | deliveries/s | median   | p99      |
| ------- | ------------ | -------- | -------- |
| pre-v5  | 539,879      | 950 ms   | 1617 ms  |
| with v5 | 486,434      | 1507 ms  | 2490 ms  |
|         | **-9.9%**    | **+59%** | **+54%** |

Delivery ratio 1.0000 in every run of both arms, so this was cost, not loss.

**Where it went.** Per subscriber, per publish, the QoS 0 fan-out was doing
_three_ derefs of `*clients*` and about seven map lookups — the protocol
version, the topic alias maximum, the maximum packet size — where before there
had been one. At twenty subscribers a publish that is sixty lookups where there
were twenty. It was also allocating a fresh `{:topic topic}` map per subscriber
purely to be part of a grouping key, and `coalesce-subscriptions` was rebuilding
every subscription map through `group-by` and a second pass.

Three changes, none of them clever:

- One deref of `*clients*` for the whole fan-out and one lookup per subscriber,
  with version, alias maximum and packet-size limit all read from the same
  entry.
- One shared object for the "no alias" grouping key instead of an identical map
  per subscriber. Identity also makes hashing it trivial.
- Coalescing in a single pass, and — the real win — a fast path that returns the
  matches **untouched** when no client matched more than once, which is the
  overwhelmingly common case. That needs the consumers to read either the
  singular `:subscription-identifier` a stored subscription carries or the
  plural a merged one gets, which is what `identifiers-of` is for.

**Where it ended up**, pooling every paired run of the final build:

|               | deliveries/s | median  | p99     |
| ------------- | ------------ | ------- | ------- |
| pre-v5        | 543,961      | 841 ms  | 1529 ms |
| v5, optimised | 538,847      | 1049 ms | 1769 ms |

Throughput is back to parity — about -3% pooled across seven pairs, which is
inside the ~6% run-to-run spread this machine gives, and one pair came out
positive. Median latency is still up around 20%, and I have not chased that
further: the remaining work is real work, and a message now carries properties
that did not exist before.

Worth saying plainly: with 24 CPU burners running, this is a comparison, not a
benchmark. The paired design is what makes it mean anything, and a single arm's
number is worthless.

### Two different waits

Restoring the writer drain broke `test_flow_control2`, from passing to **one run
in five**, and I nearly blamed the optimisations. Building the suspects out and
measuring said otherwise: the neutralised jar failed at exactly the same rate,
1 in 5.

The drain fix had replaced a `Thread/sleep 25` with a wait on the writer. That
is right as far as it goes — it guarantees the DISCONNECT is _written_ — but
written is not read. The broker has stopped reading that socket, so a client
still sending into it has data sitting unread in the receive buffer, and closing
a socket in that state sends RST rather than FIN. The RST can discard the very
DISCONNECT just written, and the client's next `send` gets EPIPE. That is
exactly what the Paho client reported: `BrokenPipeError` from inside its
receive loop.

So both waits are needed, and they are answering different questions. The drain
answers "has the packet left". The pause that follows answers "has the peer had
a chance to take it". Restoring the second, now _after_ the first rather than
instead of it, took the test back to 5 runs out of 5. It is only on the paths
where the broker hangs up on a client and has told it why — not on every close,
because an ordinary disconnect has nothing in flight to miss and paying it on
fifty thousand teardowns would be its own problem.

### The matcher was exponential, and a flaky test found it

`lein test :performance` failed intermittently with "no packet arrived within
2s". The stack trace pointed at `client-generator`, which turned out to be a red
herring — that one logs through at-at and does not fail a test. The actual error
was in `client-generator-2`, and it was mine.

Three hypotheses, all wrong, all cheap to check and worth checking:

- The generator builds a topic from a filter by substituting for `+` and `#`,
  and I thought the substituted string might contain a `/` and break the match.
  It cannot: spec's generator for `string?` is alphanumeric. 0 of 200.
- Then that my new topic-filter validation was refusing generated filters, which
  the test records as subscribed regardless of the SUBACK. Also no: 0 of 200
  generated filters are invalid.
- Then that coalescing had removed duplicate deliveries the test was quietly
  relying on. Plausible, and still wrong.

What settled it was bisecting instead of theorising. The pre-v5 commit passed
three runs out of three; HEAD failed. Then a debug-logged capture of a failing
run showed the whole exchange: CONNACK, SUBACK, one publish, silence. It failed
on the _first_ publish, and at QoS 1 or 2 — where a PUBACK or PUBREC is owed
whatever the subscriptions are. The broker had not answered at all.

The topic was 27 levels deep, because the filter it came from was
`/+/+/+/3Z2/58r3z/+/X/+/+/epGZ/+/N/...` and each `+` became a word.

**`matching-values` recursed into branches that were not there.** A missing
branch has no children, so it finds nothing and looks harmless — but each nil
node recursed twice more, once per branch, and the cost is
2^levels-remaining. Against a trie holding one short filter:

| topic depth | mine        | triennium |
| ----------- | ----------- | --------- |
| 10 levels   | 7.5 ms      | 0.055 ms  |
| 15 levels   | 37.8 ms     | 0.118 ms  |
| 20 levels   | 493 ms      | 0.086 ms  |
| 22 levels   | **1807 ms** | 0.086 ms  |

Doubling per level. At 27 levels that is about a minute, which is why the
broker never answered, and why the whole test sometimes ran past 400 seconds
rather than failing.

triennium's original had `(when exact-match ...)` and `(when any-match ...)`
guards. I dropped them when I rewrote it to fix the `sport/#`-matches-`sport`
bug, and the rewrite's own tests all used shallow topics, so nothing noticed.

The fix is the guards back. Matching is bounded by the trie again — flat at
0.03-0.04 ms at every depth, same results as triennium's.

**This mattered far more than the test that found it.** Matching runs on every
publish, so for two days any client could have hung a broker thread by
publishing to a deep enough topic — no malformed packet, no special
permissions, just a long topic name. There is a test now that matches a
60-level topic and asserts it finishes, which the old behaviour could not do:
2^59 recursions do not complete.

Two things worth keeping from how this went. The performance suite is not run
by `lein test`, so a bug introduced on day one sat there until something
happened to run it — the tests that would have caught it existed and were
skipped by default. And the first three explanations were all plausible enough
to act on; the one that was right came from bisecting and reading a log, not
from thinking harder.

### A deprecation warning that was hiding a conformance bug

Python 3.11 deprecated returning a value from a test case, and every test in
the Paho suite ends `return succeeded`, so a run printed a `DeprecationWarning`
per test. Sixteen lines, all identical, all dead — `unittest` ignores the
return value — so removing them is the whole fix, and both suites are silent
now.

Except one of the sixteen was load-bearing in the wrong direction.
`test_zero_length_clientid` in `client_test5.py` is the only test in either
file that sets `succeeded = False` in its `except` and then **never asserts
it**. It logged "failed", returned False into a value `unittest` discards, and
reported `ok`. It could not fail.

Adding the missing `self.assertEqual(succeeded, True)` turned it red
immediately, on its first assertion: a zero-length client id connecting with
CleanStart 0.

3.1.1 §3.1.3.1 requires exactly the rejection the broker was giving — a
zero-length id must come with CleanSession 1, because the server has no way to
tell the client what it was named and a session stored under that name would be
unreachable. **Version 5 dropped that restriction**, because it gained Assigned
Client Identifier: the server names the client, says so in the CONNACK, and the
session is addressable after all. The broker was applying the 3.1.1 rule to
version 5 clients and refusing a connection the specification requires it to
accept.

One condition, now qualified by version. What is worth recording is how it was
found: not by reading the specification, and not by the conformance suite, which
had a test for it that was structurally incapable of failing. It came out of
clearing a deprecation warning.

That is the second time in two days that tidying up test output has turned up a
real defect — the flaky `session_takeover_test` was a genuine close race, and
this was a genuine version 5 bug. Noise in a test run is worth reading rather
than filtering.

### Profiling, and what the broker actually spends its time on

`-Dmqttkat.profile=cpu` starts async-profiler at boot and writes a flamegraph on
exit — `mqttkat.profiling`, resolved at run time rather than required, so the
profiler stays a :dev dependency and never reaches the uberjar. `alloc` and
`wall` work too; `wall` is the one worth remembering, because a broker that is
_waiting_ looks idle to a CPU profile.

First real run: 2,200 clients, 42M packets received, peak 210,195/s, nothing
dropped. 2.56M samples.

**Fourteen per cent of all CPU is inside `clojure.lang.Atom.swap`.**

Attributing map lookups to the nearest frame of ours puts almost all of it in
one place — the QoS 1/2 packet identifier bookkeeping:

|                              | inclusive |
| ---------------------------- | --------- |
| `acquire-packet-identifier!` | 8.91%     |
| `release-packet-identifier!` | 6.13%     |
| `drain-pending!`             | 2.27%     |
| —                            |           |
| `MqttPublish/encode`         | 2.21%     |
| `matching-values`            | 1.18%     |
| `coalesce-subscriptions`     | 0.47%     |
| `alias-outbound`             | 0.29%     |

Acquiring and releasing an identifier costs seven times what encoding the packet
costs. `PersistentHashMap$ArrayNode.find`, `RT.get` and
`PersistentArrayMap.indexOf` together are the largest group of leaves in the
whole profile, and `ARef.validate` shows up on its own — a symptom of sheer
volume of atom mutation rather than of any validator.

The cause is structural. `*outbound*` is **one global atom** holding every
client's in-flight map and pending queue, keyed by client id. Every QoS 1
publish and every PUBACK does `update-in [client-id :inflight]` on it. With
2,200 clients that is path-copying through a 2,200-entry hash map, and with
every connection thread hitting the same atom it is CAS retries on top —
`compareAndSet` frames appear under acquire and release at roughly 8,300 samples
each.

The fix is to stop sharing the atom: in-flight state belongs per connection,
on the Connection object or in an atom of its own, where the swap is
uncontended and the map being copied has a handful of entries rather than
thousands. Not done yet, and it is not a small change — every reader of
`*outbound*` moves with it.

What this also settles is the earlier A/B. The version 5 machinery I spent two
days worrying about the cost of — coalescing, aliases — is 0.76% of the profile
between them, against 15% for identifier bookkeeping that predates all of it.
The measurement said parity; the profile says why.

### An atom per client, and what it did and did not buy

The profile said 14% of the broker's time was inside `clojure.lang.Atom.swap`,
nearly all of it acquiring and releasing packet identifiers. `*outbound*` was
one atom holding every client's in-flight map and pending queue, and every QoS 1
publish and every PUBACK did `update-in [client-id :inflight]` on it: path
copying through a map with an entry per client, and CAS retries because every
connection thread wanted the same atom.

It is now an atom _of_ atoms. The registry is still keyed by client id — that
part was never the problem and is required, since §4.4 makes a persistent
session's unacknowledged messages outlive the connection — but each client's
state is its own atom. The registry is read on the hot path and written only
when a session is created or discarded; the mutation happens on a small,
uncontended map.

`queued-count` moved into handlers with it. `sys` and `web.state` had each
written their own fold over `*outbound*`, which meant the shape of the state was
known in three namespaces; now it is known in one.

**Profiled, same load, both builds:**

|                              | base    | per-client  |
| ---------------------------- | ------- | ----------- |
| total samples                | 234,912 | **115,061** |
| `Atom.swap`                  | 45.98%  | **9.23%**   |
| `acquire-packet-identifier!` | 9.20%   | 3.09%       |
| `release-packet-identifier!` | 15.19%  | 3.77%       |
| `take-pending!`              | 15.02%  | 2.72%       |

Absolute samples in `Atom.swap` fell 90%, and the broker did the same 2,400,000
deliveries for **half the CPU**. The top frames are now G1 and
`MqttPublish/encode`, which is a healthy shape for a broker: garbage and the
actual work.

**And end-to-end throughput did not move.** 142,207/s before, 142,081/s after,
three interleaved pairs, one of them negative. That is not a contradiction: at
this load the broker is not CPU-bound. Median service latency is 3.2 seconds,
which is queueing an order of magnitude deeper than the in-flight window, so
what sets the rate is the delivery path and the acknowledgement round trip, not
cycles. Halving the CPU of something that was not the bottleneck changes the
headroom, not the number.

Where it does show is when CPU _is_ scarce. Under the profiler — which taxes
every thread — the same load gave 145,490 deliveries/s on the old code and
213,333 on the new, with median latency 3,146 ms against 1,442 ms. Same work,
ratio 1.0000 both times.

So: a large and real saving, honestly worth having, that this particular
benchmark cannot show. Worth writing down in that shape rather than quoting the
47%, which is a number produced by a profiler's overhead and not by the broker.

### Two things the measuring turned up

**My A/B harness had been starting the broker with no JVM options.** Every
earlier `java -jar` comparison ran on a default heap rather than the `-Xmx4G`
the project sets, which is why the profiled runs and the plain runs disagreed so
violently at first. The comparisons stay valid — both arms had the same handicap
— but the absolute numbers in them were of a differently-configured JVM. The
harness passes the project's options now.

**A 400k-message QoS 1 run wedges, on both builds.** Around 0.6% of publishes
never complete, deliveries stop, and everything sits at zero per second
indefinitely. It reproduces on the pre-refactor build too, so it is not this
change — and it is the same shape as the lead recorded on 20260903, where a
300k run left 27 publishes unacknowledged and 540 deliveries missing. That was
a handful of messages then and is thousands now at 2,000 subscribers, which
makes it far easier to chase. Still not chased.

_Later:_ chased and fixed, in the next section.

### The stall: a publisher paused for ever

The 400,000 message run wedged every time. Around 0.6% of publishes never
completed, deliveries stopped proportionally, and everything sat at zero per
second indefinitely. It reproduced on the pre-refactor build too, so it was not
the per-client atoms, and it is the same shape as the lead from 20260903 — 27
publishes unacknowledged, 540 deliveries missing — which had sat unchased since.

I guessed twice and was wrong twice: first that the pending queue could deadlock
with nothing left to drain it, then that `pausedByInbound` had a lost wakeup.
What settled it was running the load in the same JVM as the broker and dumping
the state the moment it stopped moving:

```
  total pending              : 0
  total inflight             : 0
  reading paused             : 3
  paused connections in detail:
    id=2147 pausedByPeers=true pausedByInbound=false inbound=0 queued=0
  subscribers still holding waiters: {}
```

Nothing queued anywhere, three publishers still paused, and **not one waiter
left in the entire broker** to explain why. That is the whole bug in one dump.

`drained()` iterated the waiters, resumed each, and then called `clear()`:

```java
for (Connection publisher : waiters) { publisher.resumeReading(); }
waiters.clear();
```

The iteration and the clear are not one step. A `pauseUntilDrained` landing
between them adds its publisher to the set, pauses it — and then the `clear()`
removes it without ever resuming it. It is no longer a waiter, so no later
`drained()` finds it either. That publisher's socket is never read again: it
stops acknowledging, its own window fills, and it goes silent for good. The
re-check at the end of `pauseUntilDrained` covers the case where the subscriber
drained _before_ the pause, but not this one, because by then the set is empty
and `drained()` returns on its first line.

The fix is to take each waiter out before resuming it, and never blind-clear.
`remove()` decides ownership: whoever takes a waiter is the one that resumes it,
so it cannot be resumed twice or dropped, and an add that races the pass is left
in the set for the re-check, the next write, or the close to find.

Three consecutive 400,000 message runs afterwards: 8,000,000 delivered,
**ratio 1.0000, zero unacknowledged**, where before it never finished at all.

### A test that passed against the bug it was written for

The first version of the regression test hammered `drained()` and
`pauseUntilDrained` from two threads and asserted the invariant — _a paused
publisher must be somebody's waiter_. It passed against the broken code.

It could not have failed. Each round started with an empty waiter set, so
`drained()` returned at `if (waiters.isEmpty())` and never reached the `clear()`
where the race lives. Adding a decoy waiter, so the drain has real work to do,
took it from 0 failures to 49 in 2,000 rounds against the old code, and 0 with
the fix.

Worth the paragraph because the test looked right, tested the right invariant,
exercised both methods concurrently, and was worthless. The check that caught it
is the only one that matters: put the bug back and watch the test fail.

### The charts remember more than two minutes

The console kept 120 samples — two minutes at one a second — hard-coded on the
server and again in the browser. Two minutes is short enough that a tab opened
just after something interesting happened had already missed it.

Retention is now a duration, `-Dmqttkat.wsHistoryMinutes`, defaulting to thirty,
with `history-size` derived from it and the sample interval rather than being a
second constant that has to agree. The snapshot carries the retention, so the
page knows how far back it can look instead of guessing.

On top of that a window picker: 2 min / 5 min / 15 min / All, sitting in the
page head next to the legend. The options are built from the retention the
server reports, so the page never offers a window it cannot fill, and a broker
started with a different retention needs no edit here. The choice is kept in
localStorage and restored on load; one that the current retention cannot serve
falls back to All rather than charting less than the label claims.

The browser keeps everything the server sends and charts a slice of it. Two
arrays, `samples` and `view`, so switching windows is instant — the history is
already in the page, nothing is refetched — and so that one place decides what
is on screen. Every reader moved to `view`: the axis, the peak, the sparklines,
the hover and the tooltip. Leaving any of them on `samples` would have been a
tooltip disagreeing with the line under the cursor.

Two things worth noting from doing it in front of a browser rather than in the
editor:

The first labels were "2m / 5m / 15m", and the shared control style uppercases
its text — so they rendered as **2M, 5M, 15M** on a page whose every other
figure is a byte count. It looked like a size selector. Spelled out as "2 min"
and the uppercase dropped for that one control.

And the picker is rendered empty by the server and filled in by the browser.
Server-rendering a guess at the options would mean the page shipping "2 min"
selected and then replacing it a moment later, which is a flicker on every
load — the one thing the server-rendered readings exist to avoid.

Verified in the browser, not by reading: with 4m26s of history, All charted 266
seconds, 2 min charted 119, and 5 min charted all 266 because that is all there
was. The selection survived a reload.

### Which topics are actually busy

The Topics page listed retained topics and nothing else, and said why in its
own docstring: a publish that is not retained is forwarded and forgotten, and
keeping the payloads to answer "what is busy" would make the broker a store
rather than a console.

That reasoning is about payloads. Counting _names_ costs neither — the broker
already has the topic string in hand, and a counter per topic is a few bytes.
So the page now has an Active topics table above the retained tree: the busiest
dozen by rate, with the running total beside it.

`TopicStats` is a `ConcurrentHashMap` of `LongAdder`, which is the shape the
outbound state should have been all along — this runs once per publish, and the
lesson from that refactor was that a shared structure everyone swaps is the most
expensive thing a broker can own. The rate is a difference between samples, like
every other rate on the page, so a topic that has gone quiet drops off rather
than sitting near the top on the strength of what it did an hour ago.

Bounded at two thousand topics, because topics are not bounded. MQTT has no
registration step, so a client may publish to a new topic on every message, and
an unbounded map is a leak with a publisher's name on it. Past the cap the
console says "busiest 12 of 2000+ tracked" rather than quietly implying the list
is everything.

**`$SYS` is not counted.** It went in counting everything, and the first run
showed "busiest 12 of 96" on a broker doing nothing but talk to itself — sixty
eight `$SYS` topics published on a timer, filling a table meant to answer "what
are my clients doing". They are listed in their own tree lower down the same
page. Excluded, an idle broker now says "Nothing published yet", which is true.

Two things the tests and the browser caught between them:

The rows went in with `id="active-topic-0"` and so on, and
`every-id-the-page-renders-is-a-field-the-socket-sends` failed on all of them.
It was right: an id on this page is a promise that `state/fields` hands out a
string for it every second, and this table is rebuilt wholesale instead. The
ids were removed rather than the test relaxed — the container keeps its id and
joins `event-list` as something the browser fills rather than assigns into.

And the table builds HTML rather than assigning textContent, which every other
reading on this page does. Topic names are chosen by whoever connected, so they
are untrusted text: it escapes.

### A Clients page, and the socket learning which page it is talking to

The Clients tab had been a disabled nav item pointing at `#` since the design
went in. It lists clients now: id, connected or parked, protocol version,
clean or persistent session, subscriptions, in flight, queued, and how long it
has been here.

Parked sessions are in the list alongside connected ones, which is the point of
having it. `*clients*` is keyed by SelectionKey while a client is connected and
re-keyed to its client id once the socket has gone and the session is kept
(§3.1.2.4), so the key type is what tells the two apart. A parked session still
holds subscriptions and still has messages queued against it — exactly what
someone looking at this page wants to see, and nothing else on the console
showed it.

Capped at fifty rows, with the real count beside the table. The scale tests open
fifty thousand connections; a table of that many rows is a hung browser.

**The socket now knows which page it belongs to.** The topic table went in
sending itself to every open socket, on the reasoning that a dozen entries a
second was cheaper than teaching the socket what page it was. A client list of
fifty rows is not, and two such tables going to three pages that each want one
of them made the shortcut untenable. `console.js` passes `?page=` on the
websocket URL, and the payload carries the one table that page can display. The
broadcast builds a message per distinct page rather than per browser — three at
most, and usually one. There is a test that each page gets its table and not the
other's, because getting that wrong is invisible: everything still works, it
just costs.

### A column that was always a dash

The first version of the table had an Idle column, from `:last-active`. Every
row read "—".

`:last-active` is created by `add-timer!`, and `add-timer!` only runs for a
client that asked for a keep alive. Everything the load generator connects, and
plenty of real clients, ask for none — so for most of them the broker genuinely
does not know when it last heard from them, and the column was honest and
useless at the same time.

Clients now carry `:connected-at`, stamped in `add-client!` on the connection
being accepted, and re-stamped when a session is resumed so the figure is about
this connection rather than the one before it. That is known for every client,
and "how long has this been here" is the more useful question for a list anyway.
Coarse — seconds, then minutes, then hours — because a figure that changes every
second is movement the eye follows for nothing.

Worth noticing that only running it against a live broker showed this. The
column rendered, the tests passed, and it was empty.

### Thirty-eight per cent of the broker was resolving loggers

Profiled again after the outbound refactor, the back-pressure fix and the topic
counter. The counter cost nothing measurable — 111,410 samples against 115,061
before it existed — but something else did.

**38.5% of the broker's CPU was under `clojure.tools.logging`**, at level INFO,
with not one of those messages emitted.

tools.logging expands `(log/trace ...)` into

```clojure
(let [logger (impl/get-logger *logger-factory* ns)]
  (if (impl/enabled? logger level) ...))
```

The logger is fetched _before_ the level is checked, so a disabled statement
still pays for the lookup. And the lookup is not a map read: the factory is
slf4j over log4j2, and log4j2 works out the calling class by walking the stack.
Every frame under tools.logging in the profile was stack-walking machinery —
`vframeStream`, `StackFrameInfo`, `MethodHandles::init_method_MemberName` — and
the lock around it.

Per call site, `send_buffer` was the worst at 18.7%: it logs twice and runs once
per delivery. Then `default_handler_fn` at 9.3% and `puback` at 8.3%.

Loggers are stable for the life of a namespace, so `mqttkat.logging` wraps the
factory in a `ConcurrentHashMap` and `server/start!` installs it. Level changes
still work — a log4j2 Logger is a live view of the configuration, which is what
`enabled?` consults, so the test helper that silences a logger is unaffected.
The suite proves that: it passes unchanged, including the tests that turn
loggers off mid-run.

|                          | before  | after      |
| ------------------------ | ------- | ---------- |
| under tools.logging      | 38.51%  | **0.40%**  |
| total samples, same work | 111,410 | **75,711** |

A third of the broker's CPU, spent on messages nobody asked for. End to end,
three interleaved pairs unprofiled, every pair positive: 247,204 against
256,507 deliveries a second, **+3.8%**, with p99 latency down 8.7%. Less than
the CPU saving, as ever, because this load is not purely CPU-bound — but this
time it does move the number.

Worth keeping in mind before adding a `log/trace` to anything that runs per
message. The statement is free to _read_ and was costing more than encoding the
packets.

### What is left

The profile is a healthy shape now: `MqttPublish/encode` at 6.93% is the top
frame, which is the broker doing its actual job, and G1 behind it at 5.41%.

One thing stands out for next time. `java.util.regex.Matcher.hasMatch` and
`Pattern.split` together are about 2.5%, and they are on the publish path:
triennium's `split-topic` splits the topic on a regex, once per publish, to walk
the trie. Splitting on a single character does not need a regex. Not chased.

### Subscriptions that come and go too

`--churn` cycles whole connections. `--resubscribe N` is the other half: a
client that stays connected and changes what it is subscribed to, N times a
second, one by default. It unsubscribes and subscribes again on a _different_
topic, so the broker's trie really does lose an entry and gain one somewhere
else rather than replacing a value in place.

That is the case connection churn does not reach. A reconnect mutates the trie
too, but wrapped in a whole connection lifecycle — accept, CONNECT, session
setup — so if something goes wrong there it is hard to say which part did it.
This isolates SUBSCRIBE and UNSUBSCRIBE against a live fan-out.

Both draw on the same rotating pool, and both run on **one** thread taking
whichever schedule is due next. Independent schedules without concurrency:
otherwise a resubscribe could land on the very client a reconnect is halfway
through closing.

The report counts the broker's answers, not just the requests:

```
    reconnects     39 over the run, 50 in the pool
    resubscribes   99, 188 SUBACK, 99 UNSUBACK
```

That arithmetic is the check. 188 is 50 initial subscribes plus 39 reconnects
plus 99 resubscribes, and the UNSUBACKs match the resubscribes exactly — so the
broker answered every one. A subscribe it never answered would be a
subscription that silently is not there, and the only other sign would be a
client that had gone quiet.

**A sentinel that overflowed.** Disabled schedules started as a deadline of
`Long/MAX_VALUE`, and `now + Long/MAX_VALUE` wraps to a negative number — so a
disabled schedule looked permanently overdue, the loop spun on it, and the
_enabled_ one never fired. `--resubscribe 0` reported zero reconnects with churn
switched on, which is what caught it. Disabled schedules are `nil` now, and
"nothing scheduled" ends the loop rather than being a very distant time.

### Subscribers that come and go

Every run so far opened its clients, subscribed them, and left them there for
the duration. Real brokers do not get that: clients drop, reconnect, resubscribe,
and the subscription trie is mutated while the fan-out is walking it. None of
that was being exercised.

`--churn N` reconnects N subscribers a second, one by default, 0 to switch it
off. Each cycle closes the oldest of a rotating pool and opens a replacement
that connects, subscribes and starts receiving.

**The cycling subscribers are a pool of their own, not the ones `--subscribers`
names, and they count into their own counters.** That is not tidiness, it is the
whole reason the run still means anything. The delivery ratio is
publishes-per-topic times subscribers-on-that-topic, and it is the single most
valuable number the generator produces — `1.0000` is how you know the broker
lost nothing. A subscriber that was away for part of the run legitimately misses
whatever was published while it was gone. Counted in that sum it would turn an
exact 1.0000 into a number that drifts, and nothing about the drift would
distinguish "this client was reconnecting" from "the broker dropped a message".

So the static pool answers whether the broker delivered everything it owed, and
the cycling pool answers whether it stayed upright while clients came and went.
The report keeps them apart and says so:

```
    delivered     200000 in  15.00 s  (    13331/s)
    expected      200000               (1.0000 delivered)

  cycling subscribers
    reconnects     19 over the run, 20 in the pool
    received       99990 (not counted in delivered, see above)
```

The pool is ten seconds' worth of churn, minimum four: a client wants long
enough to connect, subscribe and be delivered to before its turn comes round
again, or the run measures reconnection and nothing else. Churn stops when
publishing does, so the drain is still a drain rather than a moving target.

The rotating pool is a `PersistentQueue`. It was a vector first, cycled with
`subvec` and `conj` — which is a slow leak: `conj` on a subvec grows the vector
underneath it and slides the window along, so the array retains every client
ever closed. At one a second that is thousands of dead connections held for the
life of an overnight run. The broker's pending queue is a `PersistentQueue` for
the same reason, noted there in 20260902.

Two old lessons turned up again on the way in. A `defn` taking six arguments
cannot carry primitive hints — Clojure allows them on four or fewer — which is
the same wall `put-header!` and `publish!` hit. And a docstring written through
a Python heredoc had its escaped quotes unescaped on the way, closing the string
early and producing an error about `did` not being a parameter vector.

### Notes to self

- **mosquitto had quietly taken port 1883**, as a systemd service, and served
  an entire conformance run before I noticed — the giveaway was `backlog 100 on
127.0.0.1` where the broker listens `1024 on *`. Everything since runs on 1884. Check what is listening before believing a run.
- `client_test.py` cannot be given `-p` at all: it reads the option but never
  removes it from `sys.argv`, so unittest sees it and dies. Same defect as the
  `-h` one in `client_test5.py`. Running the 3.1.1 suite anywhere but 1883 means
  running a copy with the default changed.
- `session_takeover_test` flaked once under full-suite load and passed on a
  re-run and in isolation. It settles for a fixed 400 ms.
- Two of the remaining suite failures — `request_response`, `unsubscribe` —
  pass in isolation and fail in the run. The suite clears retained messages
  once, at startup, so anything a later test leaves behind is somebody else's
  problem. Worth remembering before treating a suite failure as a broker bug.
  _Later:_ the retained-state explanation does not hold for either; see 20260925. `request_response` is the same race as `subscribe_options`, and
  `unsubscribe` is still unexplained.
- **`test_subscribe_options` races, and the race is in the test.** After
  subscribing _bclient_ it waits on `callback.subscribeds` — aclient's callback,
  not `callback2` — and `waitfor` loops `while len(queue) < depth`, so with
  aclient's own SUBACK already sitting there `1 < 1` is false and it returns
  without waiting at all. aclient then publishes, possibly before the broker has
  processed bclient's SUBSCRIBE, and bclient gets nothing: `0 != 1`. Run alone
  three times it gave OK, OK, FAILED. That failure signature is identical to the
  one the No Local bug produced, which is worth knowing — I would otherwise have
  gone looking at `deliverable-subscribers` again.
- So the suite score is a range, not a number: 25 or 26 of 27 depending on the
  run, and 9 or 10 of 10 on the 3.1.1 side. Quote the range.

## 20260908

### MQTT 5, from the bottom up

The broker spoke 3.1.1 and nothing else. Adding 5.0 is not a feature so much as
a second dialect running through every packet the broker already handles, so it
went in as slices — tests first for each, then the implementation.

Three new files carry the whole of it:

- **`MqttProperties.java`** — the property block (§2.2.2). All 27 identifiers
  in one table of `record Prop(int id, Keyword key, Type type, boolean
repeatable)`, across seven wire encodings, with encode and decode driven off
  that table rather than off 27 switch arms. Variable Byte Integers (§1.5.5)
  live here too, since the block is length-prefixed with one.
- **`MqttReasonCode.java`** — every reason code as a byte constant, with
  `isError` reading the 0x80 top bit, which is the whole of §2.4's convention.
  `name(byte)` is built by reflecting over the class's own fields, so a code
  cannot be added without becoming printable.
- **`MqttProtocolError.java`** — an `IOException` that carries the reason code
  to send back. That is the point of it: in 3.1.1 a malformed packet can only
  be met with a closed socket, and in 5.0 the client is owed an explanation.

One design note worth keeping. `Type.BOOLEAN` exists separately from
`Type.BYTE` even though both are one byte on the wire, because in Clojure `0`
is truthy. A broker answering `retain-available 0` would decode to a `0` that
every `when`/`if` in the codebase reads as "retain is available" — the exact
inversion of what was sent. Decoding it to a real boolean at the boundary is
the only place that can be fixed once.

### A bug the properties work found on the way

`MqttUtil.decodeUTF8` read a two-byte length and then
`Arrays.copyOfRange(input, offset+2, offset+2+length)`. `copyOfRange` is
documented to pad with zeros past the end of the source rather than throwing —
so a truncated packet did not fail, it produced a String of NUL characters and
carried on. A client id, a topic name, a will topic: all silently
well-formed-looking garbage.

It surfaced only because the property decoder needed bounds checks of its own
and I went looking for what else read lengths off the wire. Now it throws
`MqttProtocolError.malformed`, which the connection turns into a DISCONNECT
with 0x81 rather than a mystery.

### Sixteen test files that all agreed with me

The slices went in one at a time — CONNECT/CONNACK, PUBLISH, SUBSCRIBE,
UNSUBSCRIBE/UNSUBACK, DISCONNECT, then the acknowledgements, flow control and
shared subscriptions together. Each landed with its own test file, and by the
end there were sixteen `v5_*_test.clj` and a comfortable green bar.

One helper is worth calling out, because it exists to stop a mistake I made
twice. `tu/send-v5!` stamps `:protocol-version 5` on every packet after the
CONNECT. Without it a test sends a 3.1.1-shaped SUBSCRIBE on a version 5
connection, the decoder reads the topic filter's length prefix as the property
block that should have been there, and the rest of the packet is nonsense. The
broker is right to refuse it — and that is exactly the problem, because the
mistake is invisible in the test source and shows up only as a reply that never
arrives. I called it an "unexplained intermittent failure" the first time. It
was not intermittent; it became deterministic the moment the decoder learned
the next packet type, which is a different thing entirely.

### "Does v5 now work, or do we just support the packets?"

The best question anyone has asked about this project, and I did not have an
answer. Sixteen test files, all written by me, all exercised through a client
written by me, against a decoder written by me. Every one of them could agree
perfectly while the broker was unusable by anything else on earth. Green tests
of that shape are self-confirmation, not evidence.

So: `client_test5.py`, the MQTT 5 half of the Paho interoperability suite.
**8 passed, 18 failed, 1 hung.**

That is the honest starting number for "does v5 work", against sixteen green
files claiming it did.

### Why the suite would not run

Worth recording, because it had defeated an earlier attempt. The suite must be
invoked with **no arguments at all**. Its `-h` is `--hostname`, but it never
removes it from `sys.argv`, and `unittest.main()` then reads `-h` as `--help`,
prints usage and exits. `python3 client_test5.py -h localhost` therefore looks
like it does nothing. The defaults are already localhost:1883, so bare
`python3 client_test5.py` is the way in — and `-v` does pass through usefully,
which is how you find which test is hanging.

### Four things the suite found

**The hang, first.** `test_flow_control2` publishes one QoS 2 message more than
the broker's advertised Receive Maximum and then blocks for ever waiting for
the DISCONNECT that says so. §4.9 is a promise in both directions and the
broker was only keeping the outbound half: it accepted everything and answered
nothing. Now inbound in-flight QoS 2 messages are counted per client and one
too many is met with 0x93.

Fixing the hang mattered out of proportion to the fix, because the suite runs
alphabetically and a hang means every test after it never runs. Five of 27 were
reachable before; 27 after.

**Session takeover (§3.1.4).** A second CONNECT under a live client id must
disconnect the first, with 0x8E. Needed an index of client-id to SelectionKey
to be O(1) on the connect path rather than a scan.

**Will Delay (§3.1.3.2.2).** The will fires at `min(delay, session expiry)`, not
at the delay — a session expiry of 0, which is what a 3.1.1 client effectively
has, means immediately however long a delay was asked for. There would be
nothing left to come back to. And §3.1.2.5: a reconnection _deletes_ the
pending will.

That last one had an ordering bug in my first attempt. I cancelled the pending
will and then did the takeover — but the takeover disconnects the old
connection, which schedules a _new_ will, and that one had nothing left to
cancel it. The order has to be takeover first, then cancel.

**Session Expiry (§3.1.2.11.2).** Version 5 splits into two things what 3.1.1
did with one flag: Clean Start says whether to resume, Session Expiry says how
long the session outlives the connection. Without a timer a session with a
five-second expiry lived as long as the broker did.

Also fixed while in there: a polite DISCONNECT was publishing the will. §3.14.4
says the server discards it _without publishing_, so every clean goodbye was
telling that client's subscribers it had crashed. No test caught it because
both will tests drop the socket instead — which is the one case where the will
genuinely should fire. Fixing it then broke `last-will-test`, because the
broker synthesises a DISCONNECT for a dead socket and the fix could not tell
the two apart; hence a `FROM_CLIENT` marker.

**8 → 12 passing, 1 hang → 0.**

### Topic aliases

§3.3.2.3.4. An alias replaces a topic name with a two-byte integer for the rest
of a connection, which is worth having because brokers carry the same long
topic over and over: `sensors/building-4/floor-2/room-17/temperature` costs 49
bytes every time and 2 after the first.

Two independent mappings, one per direction, each bounded by what the
_receiver_ agreed to. The broker's Topic Alias Maximum in the CONNACK bounds
what a client may send it; the client's, in its CONNECT, bounds what the broker
may send back. A client's alias 1 and the broker's alias 1 on the same
connection are different things pointing at different topics.

**Inbound** was half-built: it remembered any alias offered and rejected only
undeclared ones. It now refuses alias 0 — which is not a small alias but no
alias at all — and anything above the maximum the CONNACK advertised, both with
0x94. The advertised number is now `broker-topic-alias-maximum` rather than a
literal `10` repeated in two files, since the code enforcing a limit has to be
quoting the same number the client was promised.

**Outbound** did not exist. `alias-outbound` decides, per subscriber, one of
three things: no alias yet and room for one, so send the topic _and_ the alias;
already bound, so send the alias with an empty topic name; allowance spent, so
send the topic in full. The first case has to send both, because an alias the
receiver has never seen means nothing to it — the saving starts with the second
message.

Two details I got wrong first.

The whole decision is one `swap-vals!`, and it has to be: my first version
tested "is this alias the highest number assigned?" to decide whether the name
had to go out with it. That is right with one alias in play and wrong with two
— re-publishing the most recently assigned topic would spell its name out on
every message, and the alias would never save anything. `swap-vals!` gives the
before and after, and "was it me who created it" is the question actually being
asked.

The other is the fan-out. `qos-0` groups subscribers so that one encoded buffer
is written to many sockets, which is what makes a wide fan-out cheap, and the
grouping key is everything that changes the bytes. How a delivery is addressed
is now part of that key. It sounds like it would shatter the groups; it does
not, because a client that advertised no maximum — every 3.1.1 subscriber and
every version 5 one that did not ask — answers `{:topic topic}` and lands in
exactly the group it was in before.

The alias tables live in an atom of their own keyed by connection, not in
`*clients*` where the inbound half used to sit. `*clients*` is what a persistent
session gets _parked_ under when the socket drops, so aliases stored there
survived into a resumed session and the new connection would resolve numbers it
had never declared. The lifetime is the connection's, so the storage should be
too.

Both Paho alias tests pass.

### Three bugs the alias work uncovered

The suite hung after the alias work, so I chased the hang rather than shipping
on my own tests. None of the three was mine.

**1. The trie corrupted itself.** triennium's `insert` does
`(conj (:values node) val)`, and when the node already exists as some other
filter's parent its `:values` is nil — so `conj` onto nil stores a **list**.
`delete` then calls `disj` on it and throws `ClassCastException: PersistentList
cannot be cast to IPersistentSet`. Three lines reproduce it:

```clojure
(-> (tr/make-trie) (tr/insert "a/b" x) (tr/insert "a" y))
```

A subscription filter that is a prefix of another is entirely ordinary —
`sport/#` alongside `sport/tennis/#` — so this fired in the wild rather than in
theory. It threw out of the CONNECT handler while restoring a resumed session's
subscriptions, which left that client never added to `*clients*`, and the
broker then wedged for anything that waited on it. Twenty-five of them in one
run.

Both tries now go through `trie-insert`/`trie-delete` in `handlers.clj`, which
keep `:values` a set and match deletes on the whole stored value.

**2. QoS 2 deliveries lost every property.** `qos-2-send` built its delivery map
by hand as topic, payload and QoS. No content type, no response topic, no
correlation data, no user properties, and no subscription identifier — which
§3.3.4 says the server adds on the way out. QoS 0 and 1 were correct, which is
what made it hard to see: the same publish arrived properly at two QoS levels
out of three.

**3. Wills lost their Will Properties**, in the same way and for the same
reason: the will was rebuilt as topic, QoS, payload and retain, and everything
§3.1.3.2 attached to it was dropped. It goes through the same whitelist as a
forwarded publish now, which is also what keeps the Will Delay Interval out of
it — that one is an instruction to the broker about _when_ to send this, and
means nothing to a subscriber.

Two and three were the same symptom in the Paho output (`'Properties' object
has no attribute 'UserProperty'`) from two unrelated causes, which is a good
argument for chasing the second one instead of assuming the first fix covered
it.

### Where it stands

**16 of 27 passing as a suite, up from 12; 17 of 27 run individually; no
hangs.** Unit suite: 226 tests, 2546 assertions, `lein check` clean.

The gap between 16 and 17 is cross-test contamination rather than broker bugs —
a retained message from `test_subscribe_options` leaks into
`test_user_properties`, which then counts four deliveries where it expects
three. Several of these tests are also openly timing-sensitive
(`assertAlmostEqual(duration, 4, delta=1)`), so a number measured while
anything else is running on the machine is not a number. I nearly reported a
regression from a run I had taken while `lein test` was going in another
terminal: 1 pass, 21 fail. Re-measured on a quiet machine it was 12.

Still failing: `maximum_packet_size`, `publication_expiry` (Message Expiry),
`redelivery_on_reconnect`, `server_keep_alive`, `subscribe_failure`,
`subscribe_identifiers`, `subscribe_options`, `assigned_clientid`,
`retained_message`, `request_response`.

_Later:_ all ten fixed on 20260909, apart from `subscribe_failure` (left by
decision) and the flaky ones; see 20260925 for the list as it stands.

One thing found and deliberately not fixed, since it is a behavioural change
well beyond aliases: `pubrel` calls `qos-2-send` with raw
`matching-subscribers`, skipping both `deliverable-subscribers` and
`select-shared`. So No Local and shared-subscription round-robin do not apply to
QoS 2 messages at all. That probably bears on `subscribe_options`.

_Later:_ fixed since. `pubrel` now delivers through `subscribers-for`, the same
No Local, shared-group and coalescing steps every other publish takes, and
`v5_qos2_delivery_test.clj` pins all three on the QoS 2 path.

Enhanced authentication (the AUTH packet) is also still absent, and I am
inclined to leave it: the broker has no authentication mechanism of any kind
for it to enhance.

### The console, while I was in there

Smaller, and mostly presentation. The chart was rebuilt; more of the data the
broker already had made it onto the page; the dummy Settings tab was taken out
of the navigation but left in the source; the middle column scrolls on its own
so the broker panel sits at the bottom of the _screen_ rather than the bottom of
the page; the `$SYS` twisty in the topic tree does something now. Two of these
were real bugs rather than polish — the readings were not aligned with their
headings, and values jumped as data arrived and left, which came down to the
page having no single place that decided what a reading should say. There is
one now: `mqttkat.web.state` maps element id to display string, and both the
snapshot and the tick go through it.

### What I got wrong, collected

- Called a deterministic decoder failure "intermittent", twice, before working
  out it was the same 3.1.1-shaped-packet mistake both times.
- Took a conformance measurement with the unit suite running concurrently and
  nearly reported 1 pass as a regression.
- Cancelled a pending will before a takeover that then scheduled another one.
- Wrote an alias assignment that re-sent the topic name for ever, and would
  have shipped it if the test had used one alias instead of two.
- Assumed the QoS 2 property loss explained the will's missing properties too.
  It did not; they were two bugs.

## 20260906

────────────────────────────────────────────────────────────────
RESULTS (duration-reached)
────────────────────────────────────────────────────────────────

run
broker localhost:1883
clients 2000 publishers, 20000 subscribers over 1000 topics
messages QoS 1, 128 byte payloads, window 100
target 10000/s
asked for 100800 seconds
setup 20731 ms to connect and subscribe
ran for 100800.21 s publishing, 5.00 s draining

throughput
published 1008000566 in 100800.21 s ( 10000/s)
delivered 20160011320 in 100805.21 s ( 199990/s)
expected 20160011320 (1.0000 delivered)
payload 123046.94 MB out, 2460938.88 MB in (1.22 MB/s, 24.41 MB/s)

latency, milliseconds
service n 20160011320 min 0.14 med 131.07 mean 135.21 sd 64.01 p95 237.57 p99 278.53 p99.9 360.45 max 777.61
response n 20160011320 min 0.44 med 147.46 mean 151.11 sd 65.59 p95 253.95 p99 294.91 p99.9 376.83 max 797.01
ack n 1008000566 min 0.12 med 131.07 mean 135.31 sd 64.30 p95 237.57 p99 278.53 p99.9 360.45 max 683.61

was the generator the bottleneck?
achieved 10000/s against 10000/s asked for (the target was met, so this is the broker's number)
mean lateness 15.903 ms per message (added 15.905 ms to the average delivery)
window-blocked 100.68 s total (waiting for acknowledgements)
send failures 0
unacknowledged 0 publishes still outstanding at the end
attempted 1008000566, published 1008000566

counters
attempted 1008000566
published 1008000566
failed 0
acked 1008000566
received 20160011320
received-dup 0
received-unparseable 0

## 20260904

### A load generator of our own

The whole line of work that started with mqttloader reporting 9.8 second
latencies ended with the finding that the _client_ was the bottleneck, not the
broker. Everything since has been about measuring the broker honestly — the
queued/written/discarded/dropped split, the back-pressure counters, `$SYS`, the
console — while the thing generating the load stayed borrowed and opaque. This
closes that.

    lein run -m mqttkat.load.runner --publishers 200 --subscribers 2000 \
      --topics 100 --messages 2000000 --rate 20000 --qos 1

Or from the uberjar, which is the better way when the run is one you might want
to interrupt, because signals then reach the JVM rather than the `lein` wrapper:

    java -cp target/mqtt-kat-0.0.1-standalone.jar clojure.main \
      -m mqttkat.load.runner --publishers 200 --subscribers 2000 --messages 0

It knows nothing about this broker beyond `--host` and `--port`, so the same
run can be pointed at mosquitto for a comparison that means something.

### The options

| option             | default   | what it does                                       |
| ------------------ | --------- | -------------------------------------------------- |
| `--host HOST`      | localhost | broker to connect to                               |
| `--port PORT`      | 1883      | broker port                                        |
| `--publishers N`   | 10        | clients that only publish                          |
| `--subscribers N`  | 10        | clients that only subscribe                        |
| `--topics N`       | 5         | topics, shared between both pools                  |
| `--messages N`     | 100000    | total to publish; **0 runs until stopped**         |
| `--duration N`     | 0         | stop after N seconds; 0 for no time limit          |
| `--rate N`         | 10000     | target messages/second, aggregate; 0 for unlimited |
| `--qos 0\|1\|2`    | 0         | publish and subscribe QoS                          |
| `--size N`         | 128       | payload bytes, minimum 28                          |
| `--window N`       | 100       | unacknowledged publishes allowed per publisher     |
| `--progress-ms N`  | 5000      | how often to print a progress line                 |
| `--drain-ms N`     | 5000      | quiet period that counts as fully drained          |
| `--max-drain-ms N` | 300000    | cap on the whole drain                             |
| `--source-ips N`   | 0         | spread clients over N source addresses; 0 chooses  |

Most are obvious. The ones that are not:

**`--publishers` and `--subscribers` are separate pools**, and the ratio
between them and `--topics` is the fan-out — the single biggest determinant of
what the broker has to do. 2,000 publishers and 20,000 subscribers over 1,000
topics is twenty subscribers per topic, so one publish is twenty deliveries.
That amplification is what turned 300k publishes into 45M deliveries and broke
the back-pressure path in the first place, and it is worth being able to dial
independently of the connection count.

**`--rate` is the aggregate**, split evenly across publishers, and the schedule
it produces is absolute: the nth message is due at `start + n*interval`, not
`interval` after the last one. Sleeping between sends makes the interval a
floor and every overshoot permanent, so the run drifts to a lower rate than it
reports. Below a millisecond of interval the publisher parks once per
millisecond and sends that millisecond's worth in a burst, because `parkNanos`
has no finer pacing to give; the report says so when it is doing that.

`--rate 0` means flat out, and the report is careful to call the resulting
number a ceiling for the generator _and_ the broker together rather than a
measurement of the broker.

**`--churn`** is how many subscribers are reconnected per second, and
**`--resubscribe`** how many unsubscribe and subscribe again without dropping
their connection. Both default to one a second and take 0 to switch off; both
draw on the same pool. See the 20260910 entry for why those clients are a pool
of their own rather than the ones named by `--subscribers`.

**`--window`** is the in-flight limit per publisher, MQTT 5's Receive Maximum
by another name. Time spent waiting for a slot is reported, and it is usually
the first thing that moves when the broker is the limit.

**`--size` has a floor of 28** because the payload carries a header: the
intended send time, the actual send time, the publisher index and a sequence
number. The two timestamps are the point — see below.

**`--source-ips`** exists because of the port ceiling. One source address gives
about 14,000 connections and not the 28,000 the ephemeral range suggests, since
Linux hands `bind()` the odd ports and `connect()` the even ones; past roughly
12,000 the allocator starts scanning and the connect rate drops twenty-fold.
The generator spreads over `127.0.0.x` at 8,000 apiece when the broker is on
loopback, and leaves well alone when it is not, because the addresses of a real
interface are not ours to invent. `0` picks; a number overrides.

**`--drain-ms` is a quiet period, not a duration.** After publishing stops the
run waits until nothing has been delivered for that long, and the window
restarts every time something arrives. `--max-drain-ms` caps the whole wait so
a broker dribbling forever cannot hang the run — and if that cap is reached the
report says the delivered count is a floor rather than a total.

### Two latencies, and why there are two

The payload carries when the publisher was _scheduled_ to send and when it
_actually_ sent, so the subscriber can compute both:

    service   now - actual    what the broker did with it
    response  now - intended  what a client would have experienced

Reporting only the first is the coordinated-omission mistake: a generator that
falls behind stops sending during exactly the moments the broker is slowest,
and then reports the fast messages it did manage. The gap between the two is
how much of the delay the generator added, and it is printed on every run.

The report also answers "was the generator the bottleneck?" whether or not it
was, because a number that only appears when something is wrong is a number
nobody learns to read. Achieved against target, mean lateness, time blocked on
the window, send failures, publishes never acknowledged.

### Four things I got wrong building it

**The bottleneck verdict fired on a healthy run.** It compared lateness _summed
over every message_ against wall time, so 9.14 s looked alarming when it was
457 us each — `parkNanos` granularity — on a run that held 5,001/s against a
5,000/s target.

**Parking per message was inside the measurement, not just the pacing.**
Batching to one park per millisecond took the median service latency from
0.96 ms to 0.54 ms. That was the instrument, not the broker.

**The burst then broke my own test.** I asserted `response >= service`, which
is the obvious invariant and is false: a burst can send a message ahead of its
intended time, so it arrives before a perfectly paced generator would have sent
it. Half the messages, at those settings. The same clamp fixed 484 of 4,000,000
samples being dropped as negative, which had made the two histograms disagree
on `n` and read like lost messages.

**The drain could never wait longer than `--drain-ms`.** The deadline was
absolute from the moment draining began rather than restarting on each arrival,
so a two million message run reported deliveries that were still arriving at
44,000/s as though they were never coming — and my docstring claimed the
opposite, that a slow run "is not cut off in the middle of the tail it is
trying to measure". Fixed, a 300k message run drains for 13.79 s against 18.53 s
of publishing, and reports 0.9999 delivered where it had been reporting far
less. The delivery _rate_ was wrong for the same reason: it divided deliveries
that arrived during the drain by the publishing window alone.

### The console was counting packets and calling them messages

Found by holding the two against each other, which is the entire point of
having a generator whose numbers are independent of the broker's. The page
showed 122,328/s in and 122,552/s out while the client reported 24,269/s
published and 106,194/s delivered — and the `PUBLISH in`/`PUBLISH out` rows of
the same table agreed with the client.

`Connection` increments `receivedMessages` and `writtenMessages` once for every
packet, right beside `countReceived(type)`. At QoS 1 with a wide fan-out that is
about five times the message rate, because every delivery brings back a PUBACK:
in = 24k PUBLISH + 98k PUBACK, out = 98k delivery + 24k PUBACK. Both numbers
looked plausible, which is why it needed a test rather than an eye.

The headline metric and both lines of the throughput chart are PUBLISH now.
Packets have their own rows next to the PUBLISH ones, because the gap between
them _is_ the acknowledgement traffic, which at QoS 1 is most of what the broker
is doing and is invisible in a message count. `$SYS/broker/messages/received` is
unchanged and still counts every packet — that is Mosquitto's definition, and
anything pointed at both brokers should keep reading the same thing.

### Would turning Nagle off buy throughput?

The question came the other way round — whether disabling Nagle trades latency
for throughput — and it is worth writing down which way it actually goes.
Nagle _on_ coalesces small writes into fuller packets: better throughput per
byte, worse latency. Nagle _off_ sends every write immediately: better latency,
more packets. So turning it off buys latency at throughput's expense, not the
reverse.

It is already off here, on both ends — `MqttServer` line 174 and `MqttClient`
line 96 — and the comment on the second says why both are needed: with only one
end set, the acknowledgement half still waits on the delayed-ACK timer. Turning
it back on would be actively bad on this broker. At QoS 1 the measured
bottleneck is time publishers spend waiting for PUBACKs — 7,394 seconds summed
across 200 publishers in one 12 second run — and Nagle's whole effect would be
to delay those PUBACKs, up to 40 ms each time it met the peer's delayed ACK.

But the instinct behind the question was right. CPU sat at 1900-2100% — 19 to
21 of 24 cores — at 318,000 packets a second out. Something was being paid per
packet. Nagle is simply the wrong tool for it.

### Measuring before changing, for once

Two candidates: the writer made one `write()` syscall per packet, and
`writeFully` polled a full socket buffer with `Thread.sleep(1)`. Rather than
argue about which mattered, two counters went in first — `socketWrites` and
`writeStalls` — surfaced in the ten-second stats line as packets-per-write and
a stall count.

They answered both questions before a line of the write path changed.

**Packets per write: exactly 1.0.** At saturation that is 806,000 syscalls a
second. Worth attacking.

**Stalls: zero.** Not "few" — zero, in every run, including at full saturation.
`channel.write` never once returned zero, because the queue limit stops the
broker long before the kernel send buffer fills. The `Thread.sleep(1)` that had
been sitting in the not-fixed list as a suspected cost does not execute at all
on loopback with a reader that keeps up. I would have spent an afternoon on it.

### Gathering writes

Not Nagle: gathering only ever collects what is **already** on the queue and
never waits for more. One packet queued is one packet written, immediately, so
it costs no latency by construction — it engages only when the writer is
already behind, which is exactly when the syscall per packet was costing
something.

The writer now takes its first packet with `take()`, drains up to 63 more with
non-blocking `poll()`, and hands the array to `channel.write(buffers, 0, n)` —
one `writev`. `-Dmqttkat.gatherWrites=1` restores the old behaviour, which is
how the two were compared without swapping binaries.

Five runs each, 50 publishers, 1,000 subscribers, 50 topics, QoS 0, unlimited
rate:

|                      | deliveries/s | sd     | median      | p99         | packets/write |
| -------------------- | ------------ | ------ | ----------- | ----------- | ------------- |
| one write per packet | 796,750      | 7,184  | 2202 ms     | 4116 ms     | 1.0           |
| gathered             | **853,458**  | 15,783 | **1927 ms** | **2988 ms** | 13.5-17.6     |

+7.1% throughput, -27% at p99, -12.5% at the median, and about 93% fewer
syscalls. Better on both axes at once, which is the sign it is cheaper work
rather than a trade: less CPU per packet means the queue drains faster, so the
latency falls out of it.

**QoS 1 gains nothing**: 38,451 against 38,381 deliveries a second, inside the
noise, even though packets-per-write still reaches 14. That path is bound on
PUBACK round trips and the in-flight window, not on syscalls, and writing more
cheaply buys nothing there. Worth stating plainly, because "it made QoS 0 7%
faster" invites the assumption that it made everything faster.

Delivery ratio was 1.0000 in every run of both arms, across roughly 40M
messages. The other end-to-end tests are all paced, so the writer takes one
packet off an empty queue and the multi-buffer path never runs; there is now a
test that drives an unpaced burst specifically to cover it.

### The poll, fixed on shape rather than on measurement

`Thread.sleep(1)` is gone anyway, replaced by a backoff from 50 us doubling to
a millisecond. This is not a measured win and the comment in the code says so:
stalls were zero everywhere, so on loopback with a reader that keeps up the
path does not run. The case that would run it — a client on a real network that
stops reading — is precisely the one a loopback benchmark cannot produce, and a
flat millisecond per attempt is the wrong answer for it. The channel is
registered with the selector for reads so it cannot be switched to blocking
mode and waited on properly, which is what makes backing off the best available
shape rather than the right one.

### Found on the way, not chased

A 300k message QoS 1 run at 5,000 subscribers lost **540 of 6,000,000**
deliveries and left **27 publishes unacknowledged**. 27 x 20 subscribers per
topic is exactly 540, so it looks like 27 publishes were accepted and then
neither acknowledged nor fanned out. The broker's own counters say
`dropped 0, discarded 0, backlog 0`, and its written total reconciles to the
5,999,460 the client received — so the client got everything the broker sent,
and the broker never sent those. The drain ended quiet, so they were not merely
late. The packet arithmetic assumes exact accounting and this is a lead rather
than a conclusion, but at-least-once says it should not happen.

## 20260903

### How much of QoS 1 and 2 had ever run

The question was how well QoS 1 and 2 are tested against a real broker, and
cloverage answered it plainly. `mqttkat.handlers` was at 52.84% of forms, and
the shape of what was missing mattered more than the number:

| function        | lines never executed |
| --------------- | -------------------- |
| `qos-2`         | 5                    |
| `qos-2-send`    | 12                   |
| `pubrec`        | 4                    |
| `pubrel`        | 7                    |
| `pubcomp`       | 5                    |
| `take-pending!` | 6                    |

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
_then_, and anything captured at PUBLISH time may point at a connection that
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
the ordering violation in the section above was only visible _because_ one of
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

First, what is _not_ wrong with it. The suite is dormant — last commit January
2024, and the local checkout is level with origin, so there is nothing newer to
pull. On Python 3.14 it makes two kinds of noise, both its own:

- `DeprecationWarning: It is deprecated to return a value that is not None from
a test case`. Every test ends `return succeeded`. Deprecated since 3.11, and
  one day an error.
- `OSError(9, 'Bad file descriptor')` out of the client's receive thread.
  Errno 9 is the giveaway: EBADF means _this process_ closed the descriptor. A
  peer closing a connection gives ECONNRESET or a clean EOF, never EBADF, so
  the broker cannot produce it. It is a teardown race in the suite's own client
  — `disconnect()` ends by resetting `stopping = False`, and `connect()` then
  closes the old socket without stopping a receiver that may still be reading
  it, so the stray read gets logged rather than swallowed.

Neither explains a single failing assertion. Useful to have established, since
"the tooling is old" is a comfortable place to stop looking.

### $ topics, and four session bugs behind them

**A wildcard filter matched a $ topic.** §4.7.2: a filter beginning with `#` or
`+` must not match a topic name beginning with `$`. `+/+`was happily matching`$TopicA/B`. Triennium does not know the rule, so each subscription now records
the filter it was made with and `matching-subscribers` sieves the matches. The
rule is about the first level of the *filter*, not about `$`appearing
anywhere:`$SYS/#` still matches `$SYS/foo`, and a blunt "drop anything
starting with $" would have passed the first test and broken that one.

**Session Present was reported on a clean connect.** §3.2.2.2 requires 0
whenever CleanSession is 1. This answered with whatever happened to be parked
under the client-id, so a client asking for a fresh session was told it had
resumed one — and a client that believes that does not re-subscribe.

**A clean connect did not discard the stored session.** §3.1.2.4. The parked
entry, its subscriptions and its queued messages all survived, so the _next_
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
its _first_ assertion, before it published anything; with Session Present fixed
it got further, published three retained messages, and then failed at a later
assertion — so it never reached its own cleanup and left them set for the next
test to trip over. Fixing the retained replay fixed both.

Worth remembering the shape of that: in a suite of order-dependent tests
sharing two clients and a broker, a test getting _further_ can break the one
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

| Namespace                     | % Forms   | % Lines   |
| ----------------------------- | --------- | --------- |
| mqttkat.client                | 41.25     | 69.01     |
| mqttkat.handlers              | 67.36     | 95.48     |
| mqttkat.handlers.connack      | 9.52      | 66.67     |
| mqttkat.handlers.connect      | 71.33     | 98.11     |
| mqttkat.handlers.disconnect   | 71.43     | 100.00    |
| mqttkat.s                     | 90.91     | 100.00    |
| mqttkat.server                | 52.87     | 76.74     |
| mqttkat.spec                  | 78.20     | 100.00    |
| mqttkat.util                  | 3.79      | 20.45     |
| ----------------------------- | --------- | --------- |
| ALL FILES                     | 66.43     | 87.68     |

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

- **`mqttkat.util` at 3.79%** is not really a gap. It is the stats loop, which
  runs forever by design and is the thing keeping `-main` alive, so no test
  calls it. The parts worth testing — the rate arithmetic, the backlog
  clamping, the connected-versus-parked counting — are pure functions sitting
  inside a `loop` nothing can enter. Pulling `stats` out of `info` far enough
  to call it with two snapshots would cover most of it.
- **`mqttkat.handlers.connack` at 9.52%** is the client's side of the
  handshake. The broker sends CONNACKs constantly, but nothing in the suite
  makes the broker _receive_ one, which is what that namespace handles.

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

- `bind()` was called with no backlog, so Java asked for its default of 50.
  `/proc/sys/net/core/somaxconn` on this machine is 4096.
- `handleAccept` took exactly one connection per `select()` return. Readiness
  is reported once for however many are queued, so the rest waited for the next
  wake-up.

Between them the accept queue stayed full, the kernel dropped the SYNs it could
not queue, and the clients fell back on the TCP retransmission timer. Every one
of those 8 milliseconds was a client waiting to retry.

The backlog is 1024 now (`-Dmqttkat.acceptBacklog`) and handleAccept drains the
queue in a loop, which also removes a latent NPE: `accept()` on a non-blocking
channel returns null when there is nothing there, and the old code would have
dereferenced it.

|                    | before            | after                  |
| ------------------ | ----------------- | ---------------------- |
| 10,000 connections | 63,418 ms — 158/s | **1,328 ms — 7,530/s** |

Forty-seven times faster, and the interoperability suite is unchanged at 9 of
10 afterwards, so nothing was traded for it.

### What the broker costs per connection

At 20,000 connections: **359 MB of heap and 35 platform threads** — the same 35
as at rest. Forty thousand virtual threads for no platform threads at all,
which is the plainest evidence yet that the rebuild on virtual threads was
worth doing.

Three limits showed up and none of them is the broker:

- **28,232 ephemeral ports** (32768-60999). Every connection to one listener
  from one address needs one, so that is the ceiling for a loopback test
  whatever the broker does.
- **TIME-WAIT between ramp steps.** Closing ten thousand connections holds ten
  thousand ports for a minute — measured 26,169 sockets in TIME-WAIT holding
  26,023 of the 28,232. So only the _first_ step of a ramp measures a clean
  accept rate; the 1,334/s at 20,000 is port starvation, not the broker
  slowing down. Worth remembering before reading anything into a ramp that
  gets slower as it climbs.
- **25,000 got the JVM killed** by the kernel's OOM reaper — SIGKILL, not an
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

- **Source addresses across 127.0.0.0/8.** A connection is the whole
  four-tuple, so every source address brings its own range of local ports and
  the ~28,232 ephemeral ports stop being the ceiling. Linux lets any user bind
  to any 127.x.y.z with nothing configured first — checked before relying on
  it, along with whether the JVM could read /proc for the numbers. It cannot,
  so broker memory comes from `ps`.
- **No thread and no reader per connection.** The CONNACKs are left unread in
  the socket buffers. What proves the broker took them is the broker's own
  stats line, parsed out of its stdout — a better witness than the client's
  opinion, since it comes from the process under test.
- **`-main` takes a port now**, so a second broker can run beside one that
  already has 1883. A broker that cannot be told where to listen was a real
  limitation, not just an inconvenience for a test.

| connections | in-process         | out-of-process                            |
| ----------- | ------------------ | ----------------------------------------- |
| 10,000      | 1,328 ms — 7,530/s | **1,031 ms — 9,699/s**, broker RSS 245 MB |
| 25,000      | **SIGKILLed**      | 28,042 ms, broker RSS 328 MB              |
| 50,000      | —                  | 56,832 ms, **broker RSS 484 MB**          |

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

- **It leaked.** `put-packet-identifier` was called from exactly two places,
  PUBACK and PUBCOMP. Nothing returned identifiers when a client disconnected,
  and `*outbound*` deliberately keeps unacknowledged messages for redelivery.
  So every ungraceful disconnect with messages in flight burned its identifiers
  for good. There were 1024. After enough of those the take blocks forever and
  QoS 1/2 delivery stops broker-wide, silently. A long-running broker did not
  have a throughput problem here, it had a countdown.
- **Any client could break it.** PUBACK returned whatever identifier the client
  sent, unchecked. An unsolicited one overfilled a channel sized exactly 1024,
  so `>!!` blocked that connection's reader thread for good; a duplicate put a
  live identifier back into circulation for the next delivery to reuse.
- **It was global**, where §2.3.1 scopes identifiers to a connection: 1024
  shared out rather than 65535 each.
- **The take blocked the fan-out thread**, which deadlocks a client that both
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
anything precisely _because_ it blocked: a global semaphore is back-pressure
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

- A subscriber whose pending queue passes `pause-threshold` (512) pauses the
  publisher feeding it, and remembers it.
- Every acknowledgement drains one pending message and, below
  `resume-threshold` (128), releases everyone waiting. Hysteresis, or the
  interest ops flap once per packet.
- A connection never pauses itself — a client subscribed to a topic it
  publishes to would otherwise stop reading the very acknowledgements that
  would free it.
- `close()` calls `drained()`, so nobody is left throttled on a subscriber
  that has gone.
- `pending-limit` (4096) survives as a memory backstop. Under back-pressure it
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

|                    | pool of 1024 | per-client ids + back-pressure |
| ------------------ | ------------ | ------------------------------ |
| publish rate       | 1,238/s      | **1,671/s**                    |
| total packets/s    | 373,916      | **547,061**                    |
| publishes ingested | 80,200       | **113,616**                    |
| messages delivered | ~12.1M       | **16.2M**                      |
| dropped            | 0            | **0**                          |
| average latency    | —            | 212 ms                         |
| throttle events    | n/a          | 74,239                         |

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

| run                                 | dropped    | delivered | throttled |
| ----------------------------------- | ---------- | --------- | --------- |
| off                                 | 38,421,062 | 6,579,547 | 0         |
| on                                  | 37,963,336 | 7,037,273 | 361       |
| on, plus the read-ahead bound below | 38,102,946 | 6,897,650 | 481       |

About 1%. It is not that nothing happens — ingest over the first interval falls
from 29,997/s to 5,663/s, and the throttle counter climbs — but the publishers
still get all 300,000 in and the drop count hardly moves.

The reason is that **dropping relieves the very congestion the throttling keys
on**. The queue reaches the congestion mark, the publishers are paused, and the
queue then discards its way back below the resume mark and lets them straight
through again. The two mechanisms undo each other. QoS 1 does not have this
problem because its pending queue _retains_ what it cannot send yet, so the
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
publisher to the subscriber's waiters and _then_ paused it. Those are two
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
| ----------------- | -------------- | ----------- |
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

- **A packet split across two TCP reads killed the broker.** `handleRead`
  decoded straight out of an 8 KB buffer with no reassembly, so a partial
  packet raised `BufferUnderflowException` — a RuntimeException, caught by
  neither `handleRead` (catches IOException) nor `run()` — which escaped and
  terminated `server-loop`, taking every connection with it. This is the
  exception the 20180211 entry below saw under JMeter.
- **Partial writes were silently dropped.** `MqttSender` called `ch.write(buf)`
  and discarded the return value; a non-blocking write returns short when the
  socket buffer is full, and the remainder was never sent.
- **Order was lost twice** — once submitting each packet to the handler pool,
  and again submitting each outgoing packet to the sender pool, so a PUBACK
  could overtake the PUBLISH it acknowledged.
- **Decoding ran on the selector thread**, so one slow decode stalled reads for
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

- **Not GC.** 69 young collections and 5.4s of GC across two whole runs. The
  heap grew to 7.4 GB but G1 never struggled, and a forced full collection on
  an idle broker leaves a 12 MB live set — nothing leaks.
- **Not a starved write path.** Thread dumps in the collapse window put ~149 of
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

| counter             | meaning                                            |
| ------------------- | -------------------------------------------------- |
| `sentMessages`      | queued for a client; nothing on a socket yet       |
| `writtenMessages`   | written to the socket, in full                     |
| `discardedMessages` | queued, then abandoned when the connection died    |
| `droppedMessages`   | never queued: refused because the client is behind |

`sent — written — discarded` is the live backlog. `dropped` is deliberately
separate: it is the design working, whereas a non-zero `discarded` is a bug.

`mqttkat.util/info` reports all of it, on real elapsed time rather than the
nominal ten seconds, and warns when the backlog _grows_ over an interval -
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

| phase                  | queued/s  | written/s |
| ---------------------- | --------- | --------- |
| burst, readers busy    | 2,318,397 | 438,502   |
| after publishing stops | —         | 1,099,313 |

The broker wrote 2.5× faster with _less_ work to do. It was never
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

One detail worth keeping: the drop check happens _before_ `buffer.duplicate()`.
Duplicating and then discarding was 40M ByteBuffers of pure garbage, and most
of the GC.

|                       | unbounded  | bounded @ 10k |
| --------------------- | ---------- | ------------- |
| publish ingest        | ~15,000/s  | 30,003/s      |
| subscriber throughput | 19,887/s   | 98,904/s      |
| average latency       | 12,783 ms  | 2,645 ms      |
| max latency           | 53,264 ms  | 7,839 ms      |
| peak backlog          | 32,279,864 | 0             |
| GC time               | ~2.7 s     | 0.65 s        |

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

`MESSAGE_LENGTH` is now where those arrays _start_ rather than where they stop.
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
string with `offset += someString.length() + 2` — the _character_ count of the
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

- ~~**A keep-alive timer fires against a parked session.**~~ Fixed on 20260925.
  The `ClassCastException` itself came from tests handing `check-timer` a
  String for a key, gone since they use a real `SelectionKey`. What it pointed
  at was real: a timer is stopped only by `remove-timer!` finding it in the
  entry, so one whose entry went without it fired forever. Each tick now checks
  it is still the timer filed under its key and cancels itself if not, and
  `add-timer!` stops any timer already there.
- ~~**`writeFully` still polls.**~~ Fixed on 20260904, and the suspicion that
  prompted it was wrong: a counter on that branch measured **zero** stalls
  under every load tried, up to 806,000 deliveries a second. It was not the
  bottleneck because it never ran. Replaced with a 50 us backoff anyway, for
  the case a loopback benchmark cannot produce — a client on a real network
  that stops reading.
- **53 of 150 subscriber sockets had an empty Send-Q** while the rest were
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

We see that the latency lower is on average and the max latency is lower as well. Something to investigate.

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

So lately I have been making loads of additions to this... first of all there is a 'client' now... partly because I managed to write some spec's for most packages (all packages needed for QOS 0 are specced now) and I needed a client to send the packages over the wire to the server and the contents of each package is generated via the spec. So the code encodes and decodes that various packages and all the data is generated via spec... the one thing where it fails at the moment is the two packages that have byte-arrays in them (Connect with username/password and Publish). These fail the `(is ...)` test in the `deftest` code and I think this is due to the fact that the values don't get compared, but the location.

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
