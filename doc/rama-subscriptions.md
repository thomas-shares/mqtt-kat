# Subscriptions in Rama

How subscriptions could be stored in Rama, what each shape costs, and what
the publish path can and cannot afford. Sections 1–6 were written before
building anything; section 7 is what was built, and why it is neither A
nor B exactly.

## 1. What triennium does

`clojurewerkz.triennium.trie` is nested Clojure maps keyed by topic level.
A node is a map whose keys are the next segments, plus `:values`, the set of
whatever was inserted at that exact filter:

```clojure
;; after (insert "sport/tennis/#" s1) (insert "sport/+" s2) (insert "#" s3)
{"sport" {"tennis" {"#" {:values #{s1}}}
          "+"      {:values #{s2}}}
 "#"     {:values #{s3}}}
```

Matching a topic is a recursive walk: at each node, take the branch for the
literal segment, the `+` branch, and collect the `#` branch's values; the
result is the union of the `:values` on every node the walk ends on. The
cost is the number of nodes visited, and everything is in memory, so a match
is microseconds. mqtt-kat kept the layout and replaced the three functions
that were wrong (`trie-insert`, `matching-values`, `trie-delete` in
`handlers.clj`): the `#`-covers-the-parent rule, the exponential recursion
into missing branches, and the list-vs-set corruption on a prefix insert.

The broker holds two of these tries and one index:

| structure | keyed by | value at a node | used for |
|---|---|---|---|
| `*subscriber-trie*` | filter levels | `{:client-key <SelectionKey> :qos :topic-filter …}` | fan-out of every publish |
| `*offline-trie*` | filter levels | `{:client-id :qos :topic-filter}` | queuing QoS 1/2 for parked sessions |
| `*clients*` `[key :subscribed-topics]` | connection, then parked client-id | `#{subscription-entry}` | resume, unsubscribe, cleanup |

A subscription entry (`subscription-entry`) is `{:filter :topic-filter :qos}`
plus, when present, `:share-group`, `:no-local?`, `:retain-as-published?`,
`:retain-handling`, `:subscription-identifier`. The two filter strings differ
for a shared subscription: `$share/g/a/#` is what the client names, `a/#` is
what goes in the trie.

## 2. What the publish path can afford

From the README's measurements: a QoS 0 publish round-trips in **0.16 ms**
median, QoS 1 in 0.30 ms. Rama's costs, from its own guidance: a RocksDB
seek is **0.3–0.5 ms** on SSD, an iterator step 1–10 µs, and any foreign
call from the broker is a network round trip on top.

So one seek is the whole publish budget twice over, and a trie walk is
several seeks. **Rama cannot be on the publish path.** The in-memory tries
stay exactly where they are; every publish keeps matching in memory. What
Rama is for is the two things memory cannot do:

1. **Durability.** A persistent session's subscriptions (§3.1.2.4) survive
   the connection today and not the broker. With Rama they survive both,
   and on restart the tries are rebuilt from it.
2. **Matching that is already off the hot path.** Anything Rama itself will
   have to do with subscriptions — queue QoS 1/2 for parked sessions once
   those queues move here, or route between brokers if there is ever more
   than one — happens inside a topology, task-locally, asynchronously to
   the publisher's acknowledgement. There the cost of a seek is throughput,
   not latency, and a trie in a PState is the right tool.

Those two wants pull toward two different shapes.

## 3. Shape A — by session

```clojure
(declare-pstate s $$subscriptions
  {String                                ; client-id
   (map-schema String                    ; :filter, as the client sent it
               (fixed-keys-schema {:topic-filter            String
                                   :qos                     Long
                                   :share-group             String
                                   :no-local?               Boolean
                                   :retain-as-published?    Boolean
                                   :retain-handling         Long
                                   :subscription-identifier Long})
               {:subindex? true})})
```

Partitioned by client id — the same key as `$$sessions`, so a client's
session and its subscriptions are on one task and one event writes both.
The subscribe and unsubscribe records go on `*session-events*` with the
connects and disconnects, which is what keeps them ordered: a subscribe,
the unsubscribe that undoes it and the clean-session connect that discards
the lot all land on one partition in the order the broker appended them.

| operation | path | cost |
|---|---|---|
| subscribe | `[(keypath client filter) (termval entry)]` | 1 write, no read |
| unsubscribe | `[(keypath client filter) NONE>]` | 1 delete, no read |
| clean-session CONNECT | `[(keypath client) NONE>]` | 1 delete |
| resume: this client's filters | `[(keypath client) MAP-VALS]` | 1 seek + n iterations |
| restart: everything | query topology, `\|all` + `local-select> ALL {:allow-yield? true}` | 1 sequential scan per task |

Every write is idempotent, so a replayed stream record gives the same
answer, as the connect and disconnect already do. The subindex on the inner
map is for the client with thousands of filters; it costs nothing for the
client with three.

What it cannot do: answer "who is subscribed to `sport/tennis`". That is the
question the trie answers.

## 4. Shape B — the trie

A trie of arbitrary depth cannot be declared as nested schemas, and a whole
subtree as one value is read and rewritten in full on every touch, which is
the wrong side of the size guidance (~100 elements). So the tree is
flattened: **a node per key, keyed by its path**, under the first topic level.

```clojure
(declare-pstate s $$filter-trie
  {String                                ; first level of the filter: "sport", "+", "#", "$SYS"
   (map-schema String                    ; the node's path, levels joined: "sport", "sport/tennis", "sport/tennis/+"
               (fixed-keys-schema
                 {:children (set-schema String)                      ; next levels present below this node
                  :subs     (map-schema String Object {:subindex? true})}) ; client-id -> subscription entry, at this exact filter
               {:subindex? true})})
```

Why each piece is what it is:

- **Keyed by first level** so a publish routes to one task with `(|hash
  first-level)` and the whole walk is local. This is also how storage and
  work spread across tasks: by topic namespace rather than by client.
- **Wildcard-rooted filters (`+/…`, `#`) are written to every task.** They
  match topics on every partition, and a walk that had to visit the `+` and
  `#` partitions as well would be three network hops per match. `|all` on
  subscribe, and they are rare: a monitoring client's `#`, not the
  population's `devices/+/status`.
- **The inner map is sorted by path**, so a subtree is a contiguous key
  range: `(sorted-map-range "sport/" "sport0")` is every node under
  `sport` in one seek. That is what a subtree dump or a prune costs.
- **`:children` is a plain set**, read with the node. It is the branching of
  *filters*, which is small — subscribers say `devices/+/status`, not one
  filter per device — and reading it with the node is what keeps a visit to
  one seek instead of four (node, then `s`, `+`, `#` membership).
- **`:subs` is subindexed** because that is where the big number lives:
  100,000 clients on `news/#` is one node with 100,000 entries, and a
  subscribe must not read them all to add one.

| operation | what happens | cost for a filter of n levels |
|---|---|---|
| subscribe | for each prefix, `[(keypath fl path) :children NONE-ELEM (termval next)]`; at the leaf `[(keypath fl path) :subs (keypath client) (termval entry)]` | n reads + n writes (each prefix node is read to add a child); wildcard-rooted: × tasks |
| unsubscribe | delete from `:subs`; walk up pruning nodes with no subs and no children | 1 delete + up to n reads (`(view count)` is O(1) with size tracking) |
| match topic of n levels | walk from the root node, following `s`, `+` and collecting `#` at each level, exactly as `matching-values` does | 1 seek per node visited; visited ≤ 3 per level in a sparse trie, so ~n–3n seeks; results read with `[:subs MAP-VALS]` on each end node |

Every write is a set add, a map put or a delete, so all are idempotent
under replay. The prune on unsubscribe is a read-then-delete on one task in
one event, which is safe for the same reason the connect count is.

Matching a 5-level topic is therefore 5–15 seeks — 2–7 ms cold, much less
when the trie is hot in RocksDB's block cache, but never the microseconds of
the in-memory walk. That is fine for queuing messages to parked sessions
inside a topology; it is not fine for the fan-out of a live publish, and
nothing here proposes it for that.

Two things the trie owner must replicate from `handlers.clj`, because the
trie does not know them: `#` at the level a topic ends on matches the
parent (`sport/#` matches `sport`), and a wildcard-rooted filter must not
match a `$`-topic (the `sieve-dollar` rule).

## 5. Both, from one topology

A and B are two views of the same events. One stream topology on
`*session-events` can keep both: write A on the client-id partition, then
`(|hash first-level)` and write B. In a stream topology those are two
transactions — a crash between them leaves A written and B not, and the
retry redoes both, harmlessly, since every write is idempotent. It does
mean A is briefly ahead of B, which nothing reads across.

Rebuilding the broker's tries on restart reads A (one scan gives every
client's entries, which is what `add-client!` needs to put back into both
tries and `:subscribed-topics`); B is for Rama's own matching and is never
read by the broker.

## 6. Recommendation

Build **A now**, as the next two events on `*session-events`:

```clojure
{:event :subscribe   :client-id "c" :connect-id "…" :filter "$share/g/a/#" :entry {…} :at …}
{:event :unsubscribe :client-id "c" :connect-id "…" :filter "$share/g/a/#" :at …}
```

with the clean-session connect clearing the client's map, a query topology
for the restart scan, and the broker rebuilding its tries from it on start
when `-Dmqttkat.rama` is set. It is small, it makes persistent sessions
actually persistent, and every read it serves is one seek.

Build **B when something in Rama needs to match a topic** — the first
candidate is moving the parked-session queues here, which is the next piece
of session state after subscriptions. Its cost is only worth paying for a
reader, and there is none yet. When it is built it is a second PState on the
same topology, fed by the same two events, and nothing in A changes.

## 7. What was built: a shared table, reactive copies

The decision that settled it: there will be several brokers in front of one
Rama cluster. Then every broker needs every subscription in memory —
matching stays in memory, §2 — and Rama's job is to hold the one table they
are all copies of and to push each change to all of them. Rama's reactive
queries (`foreign-proxy`) do exactly that: a proxy on a path receives the
value once and then a diff for every change, in order, on a background
thread. Nothing polls.

Two facts, checked on an in-process cluster before designing around them,
decide the shape:

- **A proxy sits on a key, never on the root of a partition.** The root is
  the RocksDB-backed structure itself and cannot be serialised to a client
  (`Serializer not defined for RocksDBWrapper`). So "one proxy per
  partition" is out.
- **A proxied value cannot be subindexed** — same error. So the value under
  the key is a plain map: read, sent and rewritten whole. It has to be
  small, and the way to keep it small is to have many keys.

Hence `$$subscriptions`:

```clojure
{Long                                 ; shard, (mod (hash filter) 64)
 (map-schema String                   ; filter, as the client sent it
   (map-schema String Object))}       ; client-id -> entry + :client-id + :broker-id
```

Sixty-four shards: a broker opens sixty-four proxies, and a subscribe
rewrites one sixty-fourth of the table. The diffs are the trie's own
operations — `KeyDiff[filter | KeyDiff[client | NewValueDiff[entry]]]`,
`KeyDiff[filter | KeyRemoveDiff[client]]`, `KeyRemoveDiff[filter]` — and
the first callback, `ResyncDiff`, carries the whole shard, which is how a
broker that starts later gets everything: same code path, no separate load.
The broker side does not read the diff objects at all; it walks old and new
values, skipping on identity the filters whose map did not change, and
applies the entries that did to its `mqttkat.trie`.

The session side is shape A in all but name: `$$sessions` carries the
client's own filters as a plain map inside the record, written on the
client's partition in the same event, with the clean-session rules applied
there (§3.1.2.4: gone on a clean disconnect or a clean connect, kept for a
persistent session). The shard write follows a `(|hash shard)` hop, so it is
a second transaction — a crash between the two is redone by the retry, and
both writes are replaces or deletes, so redoing is harmless.

Order still comes from the client-id partition: a client's subscribe,
unsubscribe, disconnect and next connect arrive in the order the broker
appended them, and the hop to a shard preserves order between one source
task and one destination. A subscribe that names a connection other than
the one on record is ignored, as a disconnect is.

## 8. The publish path, and the bridge

With the trie in every broker, a publish on broker B is matched in B's copy
(`cluster/remote-brokers`: the distinct broker ids on the matching entries,
minus B's own, with the $-topic rule applied). B's own subscribers are
served exactly as before, by the broker's own tries — nothing on that path
changed. Each other broker named gets one copy, and fans it out to its own
subscribers itself.

The copy travels over MQTT, not through Rama (`mqttkat.bridge`): B is a
client of each peer, connected on first use as `mqttkat-bridge/B`, speaking
version 5 so the properties travel, retain off, with the QoS 1 and 2 flows
the client side owes. The receiving broker recognises a bridge by its client
id and never forwards what arrives on one — every subscription is held by
exactly one broker, so one hop is the whole route and there is no loop to
prevent beyond that. A depot append, a topology, a PState write and a proxy
push per message would have been several milliseconds and a disk write
where a socket write does; Rama carries the state the brokers share, the
traffic goes point to point.

The brokers find each other through Rama: `$$brokers`, one key, broker-id →
{:host :port}, watched with one more proxy; a broker announces itself once it
is listening and withdraws on the way out, including from a shutdown hook.

Verified with a Rama cluster from the 1.9.0 distribution and two broker
processes in front of it: a subscriber on one, publishes at QoS 0, 1 and 2
on the other, both directions, and a broker restarted after the
subscriptions existed rebuilding its table from the proxies' first callback
and forwarding correctly.

Shape B — the trie inside Rama — was not built. Its only reader would have
been a topology matching topics, and with every broker holding the full
table in memory there is no such reader.

## 9. Shared groups across brokers, and retained messages

**Shared groups.** The publisher's broker sees every member of a group
with the broker each is on, so it makes the choice there: per group, one
broker, rotating over the brokers that hold members (`cluster/plan`). If
that is itself, the group is served locally as before; if not, the group is
left alone locally (`select-shared` takes a predicate now) and the chosen
broker is told, in a `mqttkat-share` user property on the forwarded copy,
one per group it is to serve. A publish that arrives over a bridge serves
only the groups named on it — none if none — and the instruction is taken
off before delivery. Verified on the real cluster: a worker on each of two
brokers, six jobs published on one, three each, none twice.

**Retained messages.** `mqttkat.retained` owns the atom the broker always
read; `retain!` and `clear!` tell a sink, the cluster records `:retain` and
`:unretain` events, and `$$retained` — shard → topic → message, watched
like the subscriptions — pushes every change back into every broker's copy
through `sync!`, which does not go through the sink. A broker writes
through locally first, so its own subscribers see a retained message at
once, and the echo from Rama lands on the same value; two brokers
retaining on one topic at once are ordered by Rama and every copy ends on
the same one. The expiry sweep clears through the same seam, so the first
broker to notice a message expired clears it for all. `$`-topics never
reach the sink: `$SYS/…` is each broker's own readings under the same
names everywhere. Verified: retained on one broker, replayed on the other,
still there after every broker restarted.

## 10. A broker that dies, and sessions that roam

**A broker that dies.** `$$broker->clients` holds, per broker, the clients
connected on it (added on connect, removed on disconnect, both a hop to
the broker's partition). Every run of a broker has an incarnation, stamped
on its announcement and on every connect it accepts. On `:broker-up` the
topology walks the previous run's clients and appends a `:lost` record to
each client's own partition — appending to the depot it consumes, so the
record takes the ordinary path with the ordinary ordering — and a `:lost`
ends the connection exactly as a `:disconnect` does, provided the record
still shows the client connected on that broker under a *different*
incarnation. A client that reconnected to the new run before its
announcement got through is left alone, whichever order the two reached
Rama in. Clean sessions lose their subscriptions; persistent ones are
parked. The registry entry is simply replaced.

**Sessions that roam.** Every entry in `$$subscriptions` now carries
`:connected?`, set false on disconnect (or loss) for a persistent session
and true again on reconnect, with the broker it is on. So the publisher's
broker can see, in its own trie, that a matching subscription belongs to a
session that is away — and queues the message itself, in `$$queued`
(client → key → message, subindexed, key = time and a random suffix, so a
resume walks them in order and a replayed enqueue is one), at the lesser
of the publish's and the subscription's QoS, QoS 0 not at all (§4.1),
capped at the broker's own pending limit. While attached, no broker queues
in memory for a session that is away: the cluster does it, from whichever
broker saw the publish, whether or not the broker that parked the session
is alive.

On a persistent CONNECT the broker asks Rama (`cluster/resume`, two
reads, on that path only): if the session exists and is not parked here,
it is parked here — record and offline trie, as remove-client! leaves one
— so add-client! resumes it like any other and Session Present is true;
either way what is queued is put on the broker's own pending queue and
taken off the cluster's. Between those two the messages live in memory,
as the broker's own queue always has; a broker dying in that window loses
them, and acknowledgement-driven removal would close it.

Two things learned building it, both about the proxies: a `byte[]` in a
proxied value hashes by identity, so the client-side check of an applied
diff against the server's hash fails and the shard resyncs on every change
— payloads in `$$retained` are stored as Base64 text for that reason; and
an exception in a proxy callback comes back out of `foreign-proxy` on the
first call, so a bad entry took a starting broker down — callbacks are
guarded now.

Verified on the real cluster: a persistent client on A, A killed with
SIGKILL and restarted, two QoS 1 publishes on B while the client was away,
the client resuming on B and receiving both in order.

## 11. Acknowledged means done; a session live elsewhere is taken over

**Acknowledgement-driven removal.** A message taken from the cluster's
queue keeps its key through the broker's own pending queue and in-flight
map (`::cluster-key`), and comes off the cluster's queue when the client
acknowledges it — PUBACK for QoS 1, PUBREC for QoS 2, the point at which
§4.3.3 has the receiver own the message — or when it is found expired at
send time. Nothing else takes it off. The mirror image, on the way out:
while attached, a persistent session that goes away holds nothing in the
broker's memory. What it leaves in flight or waiting that came from the
cluster's queue is still on it; what came from live delivery is put on it
(`hand-over-unacknowledged!`), at the QoS it would have been sent at and
with the time it had already waited. So a resume anywhere starts from the
cluster's queue alone, and §4.4's redelivery of the unacknowledged holds
across brokers.

**Takeover across brokers.** Every CONNECT, clean or not, asks the cluster
for the session (`cluster/resume`, one read; a persistent one costs a
second for the queue). If the record shows the client connected on another
broker, that broker is told — `mqttkat.bridge/takeover!`, a QoS 0 publish
on `$mqttkat/takeover` over the bridge with the client id and the
connect-id of the connection it holds. A publish on a `$mqttkat/…` topic
arriving over a bridge is an instruction, never delivered; the receiving
broker drops the connection only if it is still the one named, with
`Session taken over` for a version 5 client, publishes the will, and
forgets the client by hand — a close the broker starts is not reported
back to the handlers the way a socket going away is. The session's own
copy of the subscriptions is then the cluster's: when a client comes back
to a broker that has an older parked copy, that copy is replaced.

Verified on the real cluster: a client connected on A, the same id
connecting on B, the client on A receiving `DISCONNECT 0x8E` and its socket
closed, a publish on A reaching the connection on B; and a persistent
client on A sent a QoS 1 message it did not acknowledge before its socket
dropped, resuming on B and receiving it there.

Open:

- A takeover is best effort: if the old broker cannot be reached the old
  connection lives until its socket dies, though nothing is routed to it
  any more. Messages the old broker hands over when that connection
  finally ends land on the cluster's queue and reach the client on its
  next resume, not this one.

## 12. Sessions expire on the cluster's clock

The broker's rule for whether a session outlives its connection is now
Rama's too (`module/kept?`, the same rule as `handlers/keep-session?`):
3.1.1 decides on CleanSession and never expires; version 5 decides on the
Session Expiry Interval, which a DISCONNECT may change on the way out
(§3.14.2.2.2) — the disconnect event carries the interval as it stands —
and 0xFFFFFFFF means never. Before this the topology kept a session on the
clean flag alone, which is 3.1.1's rule applied to version 5.

When a version 5 session with an interval is parked, its due time goes in
the record (`:expires-at`) and in `$$expiring`: one entry per task, keyed
by the task itself through a key partitioner, holding a sorted map of
`due-time|client-id` → connect-id. So the index for a task's sessions is on
that task, and the sweep — a tick depot every five seconds, `|all`, one
range read per task up to now — finds what is due without a hop, checks
the session is still away and still the connection that parked it, and
forgets it: the record, the queue, and the subscriptions in the shards
(one hop per filter). A client coming back in time deletes its own entry;
one whose entry is stale is simply skipped. Tests replace the tick with a
depot they append `{:now …}` to.

The broker that parked a session still discards its own in-memory copy on
its own timer; that copy is replaced from the cluster's on the next
CONNECT anyway.

Verified on the real cluster with the real tick: a version 5 client with a
thirty-second interval connected, subscribed and disconnected; the record
showed it parked and counting down, and it was gone at the first sweep
after its time.

## 13. The brokers, on every console

Every broker reports a small sample of its state every five seconds — a
`:broker-sample` on the event bus from the websocket ticker, recorded as a
`:broker-stats` event — and the topology stores it on the broker's entry
in `$$brokers`, the registry every broker already watches. A report only
lands on an entry that exists and only from the run that announced it, so a
withdrawn or unannounced broker gets no ghost entry and a report in flight
from a previous run is dropped. Each broker's `:brokers` atom therefore
carries every broker's latest figures within a proxy push, and the console's
Brokers tab (`/brokers`, `state/broker-rows`) renders them, this broker
first, with a stale mark once three reports are missing. The page is served
from the atom and kept live over the websocket exactly as the clients
table is.

Found on the way: `add-client!` resumed a parked session by inserting a
reduced entry — filter and QoS only — into the live trie, while every
delete matches the whole entry. A resumed session lost its version 5
options, and after its next disconnect its entry stayed in the live trie
pointing at a socket that was gone, for the life of the broker. It inserts
the whole entry now.

Two more things, after running several brokers from `scripts/brokers.bb`:
a broker killed without its shutdown hook stayed in the registry as stale
for ever, so the expiry sweep now also drops any registry entry nobody has
reported on for ten minutes (`module/broker-forgotten-after-millis`); and
`handle-success` sent the CONNACK before `add-client!` had the resumed
subscriptions in the live trie — a short window that asking the cluster on
the way in made long enough for a publish to fall through. The session is
established first now, then the CONNACK.

## 14. What a load across three brokers found

`scripts/brokers.bb start 3` and `lein run -m mqttkat.load.runner --config
doc/load.edn` — clients spread round-robin, so most deliveries cross a
bridge — delivered 0.9894 at first. Not the bridge: the loss was a fixed
~0.15 s of traffic at the start of the run whatever the rate, which is the
window between a broker acknowledging a SUBSCRIBE and the other brokers'
copies of `$$subscriptions` having it. The listener that records a
subscribe or unsubscribe now waits for the append's `:ack` — the handler
emits before it sends the SUBACK, on the connection's own virtual thread —
so the acknowledgement means the cluster has it, and the other brokers'
proxies are being pushed the diff as it goes out. 1.0000 since, at 10,000
messages a second over three brokers with subscribers cycling.

And one the test suite found under load: a client reconnecting and being
published to straight after its CONNACK, while this broker's own copy of
the table still showed it parked — the diff for its connect not yet pushed
back — was queued for in the cluster *and* delivered live, and got the
message twice on its next resume. Two things now: a CONNECT is not
acknowledged until the cluster has processed it, and `plan` never queues
for a client that is live on this very broker, whatever the copy says.

## 15. Redirecting connections, and a cleanup that has to be bounded

**Redirect.** `$$settings` (one key, proxied like the registry) holds the
cluster's redirect policy, set from any broker's Brokers page. On a version
5 CONNECT, before anything about the session is touched, the broker asks
the policy where the client should go (`cluster/redirect-target`):
round robin rotates over the brokers the registry has heard from lately,
this one included; load based takes the fewest clients as last reported
plus what this broker has sent there since the report. A client sent
elsewhere gets CONNACK 0x9C with the Server Reference and is closed; the
sender first writes, and waits for, a `:redirected` note on the client's
session record, and a broker whose CONNECT finds itself named there takes
the client — without that, every broker applied the policy to every
arrival and a client bounced until the load generator gave up on it.
3.1.1 clients and bridges are never sent on. The load generator follows
(`--mqtt 5 --follow-redirects 1`), all clients pointed at one broker, and
reports where they landed: 40/40/40 over three brokers under round robin,
and under load based with the entry broker busy, 30/30 over the other two.

**The cleanup that wedged the cluster.** The first version of the
dead-broker cleanup walked every client of the replaced run in the one
`:broker-up` event. After a run with six thousand subscribers that event
could not finish inside Rama's five seconds per event, was retried for
ever, and everything behind it on the depot timed out with it: no
announcement, no setting, no session could get through. Every event has to
be small. `:broker-up` now only adds the replaced run to `$$dead-runs`;
the tick sweep takes one dead run and sixty-four of its clients per tick,
telling each `:lost` on its own partition, and forgets the run when it is
empty. A run that held thousands is cleared in minutes, which nothing is
waiting on. `$$broker->clients` is keyed by run — [broker-id incarnation]
— so a new run's clients are never mixed with the old run's.

## 16. Three brokers "cannot find the cluster"

They could: they connected and got their handles, and then every append
timed out. The worker log had the rest: a flood the evening before —
tens of thousands of processing timeouts in a minute — had left a backlog
on the depot, the daemons had died with their terminal, and the restarted
worker was still retrying a handful of records from that backlog for ever,
each one holding a task for five seconds, so nothing behind them got
through. The brokers' single announcement at startup timed out too, and was
never retried.

Three things came out of it. A broker that finds itself missing from its
own copy of the registry now announces itself again with its next report,
so a lost announcement costs seconds, not the broker's life. The depot
client gathers appends for a few milliseconds before sending them
(`foreign.depot.flush.delay.millis`, `-Dmqttkat.rama.flushMillis`), which is
what Rama recommends for throughput. And the dev cluster's module was
destroyed and launched afresh — the code handled the same events in
milliseconds on an in-process cluster, so the poison was the accumulated
state, not the topology — after which 10,000 subscribers connected in six
seconds and a 30-second churn run produced no timeouts at all.

Still worth knowing: a burst of a few thousand connects briefly hits
`topology.stream.max.executing.per.task` on a task ("Filtering streaming
event from push path"); the events wait and go through, and the CONNACKs
they hold up are the back-pressure. (A run at 175 publishes a second
with ten thousand subscribers turned out to be a single broker doing all
of it — the config had two of the three brokers commented out — not the
cluster.)

## 17. Telling the client by DISCONNECT, and two bugs it flushed out

A second cluster setting, `redirect-via`: `:disconnect` (the default) accepts
the client — CONNACK Success, Session Present from the cluster's record,
since a persistent client told 0 discards its own state (§3.2.2.1.1) — and
at once sends DISCONNECT 0x9C with the Server Reference; `:connack` keeps
the earlier form. Nothing is created on the redirecting broker either way.
The load client follows both, re-subscribing on the new connection if its
SUBSCRIBE had gone out on the old one; 40/40/40 and 1.0000 over three real
brokers, three runs running.

Two things came out of it. Every version 5 CONNACK this broker ever sent
carried Maximum QoS = 2, which §3.2.2.3.4 calls a Protocol Error — the
property exists to say a broker does *less* than 2, and absent means 2.
Paho's conformance suite let it pass; mosquitto's client answered every
CONNACK with "a network protocol error occurred", which is also why the
takeover demo with `mosquitto_sub -V 5` failed days ago. The property is
gone. And a client that has just been accepted-and-dismissed may have its
SUBSCRIBE in flight already; the handler acted on it — a subscription for a
socket that was gone, and a subscribe event for a client with no name.
SUBSCRIBE and UNSUBSCRIBE from a connection with no client behind it are
ignored now.

## 18. Two bridge bugs that QoS 2 across three brokers found

The load generator's mixed mode (half 3.1.1, half 5) delivered 1.0000 at QoS
1 over three brokers and 0.5012 at QoS 2. Two bugs, one behind the other:

- **The bridge ignored the peer's Receive Maximum** (§4.9). It sent QoS 1
  and 2 publishes with no window; at QoS 2 the peer holds each until its
  PUBREL, the count passed 128, and the peer rightly dropped the bridge
  with everything in flight. The bridge now waits for the peer's CONNACK,
  sizes a window from its Receive Maximum (absent means 65,535), takes a
  slot per QoS 1/2 publish and gives it back on PUBACK, PUBCOMP, or a
  refusing PUBREC. Waiting for a slot is back-pressure on the publisher,
  as everywhere else in the broker; five seconds without one drops the
  peer. Tested against a stand-in peer advertising a window of two.
- **Bridges were recorded as client sessions.** A broker's bridges share
  one client id, `mqttkat-bridge/<broker-id>`, one per peer. To the
  cluster's session table a broker with two peers was one client connected
  in two places, and the cross-broker takeover had its two bridges knock
  each other off in turn — which left exactly half the remote deliveries
  unserved, 0.6667 of the total once the first bug was fixed. Bridge
  connections are no longer recorded in Rama, adopted or taken over.

After both: 1.0000 at QoS 0, 1 and 2, mixed versions, three brokers, one
bridge connection per direction and not a single reconnect.
