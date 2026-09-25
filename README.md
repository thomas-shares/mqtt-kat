# mqtt-kat

mqtt-kat is an attempt at an [MQTT](http://mqtt.org/) broker based on the concepts of [http-kit](https://github.com/http-kit/http-kit). As http-kit this means low level java code to do the handling of the NIO and decoding and encoding of the MQTT packets. MQTT packets are en/decoded to Clojure maps and handled by Clojure code to do all the clever stuff a broker needs to do.

The idea is to see if a MQTT Broker could be as scalable as http-kit and handle as many concurrent connection as http-kit does.

## What does it do at the moment?

Thanks to Claude I have extended the test cases and made them more meaning full.

## What it doesn't do:
There is no TLS support or support for username/passwords. Anything will be accepted. 

### Will this be added in the future? 
No idea yet. Depends (but I don't know what it depends)

## Are there any bugs?
Probably, but testing has become a lot better:

```
Ran 271 tests containing 2806 assertions.
0 failures, 0 errors.
```
and

```
lein test mqttkat.client-generator-2
13:47:01.776 INFO  [main] m.client-generator-2 - simulation summary
    events         10000 events in 10.34s
    publishes      qos0 4965  qos1 2551  qos2 2481  (total 9997, 3 skipped)
  round trip, milliseconds (publish sent -> last acknowledgement)
    all            n 9997  min 0.02     med 0.29     mean 0.71     sd 1.11     p95 2.84     p99 5.71     max 16.22
    qos 0          n 4965  min 0.02     med 0.16     mean 0.40     sd 0.67     p95 1.75     p99 2.73     max 16.22
    qos 1          n 2551  min 0.05     med 0.30     mean 0.53     sd 0.63     p95 1.78     p99 2.79     max 10.92
    qos 2          n 2481  min 0.37     med 0.65     mean 1.50     sd 1.68     p95 5.08     p99 6.91     max 13.07
  client-side prepare, milliseconds (spec generation + encode)
    all            n 9997  min 0.15     med 0.26     mean 0.30     sd 0.27     p95 0.47     p99 0.60     max 14.36
  broker throughput over this test only
    messages       3865.62 msg/s in, 3865.62 msg/s out
    bytes          551.24 KB/s in, 560.55 KB/s out

Ran 2 tests containing 24958 assertions.
0 failures, 0 errors.
```

most of these tests pass now as well: https://github.com/eclipse-paho/paho.mqtt.testing
The one failing there is a SUBACK failure.

## What about the name?

I first thought of calling it mqtt-kit... but then decide that mqtt-kat made more sense. Somehow.

## Will it ever be a proper MQTT broker supporting QOS > 0?

It actually does now... but memory only, there is no storing to disk. So a broker crash would loose most inflight message (I guess some would be recovered if a client retries)

## Will it ever support MQTT version 5?

Maybe... with Claude's help I might be able to add this now.

## Rama

The answer to "memory only" above is going to be [Rama](https://redplanetlabs.com/):
the state MQTT says must outlive a connection or a restart — persistent sessions,
their subscriptions and queued messages, retained messages — belongs in a durable,
replicated store, and Rama is one that speaks Clojure. The broker's hot path stays
in memory; Rama is where the things that must not be lost end up.

Several brokers in front of one Rama cluster. Each broker keeps its own
in-memory subscription trie — matching a publish must stay in memory, a single
disk seek costs more than a whole QoS 0 round trip — and Rama holds the table
every broker's trie is a copy of, pushing each change to all of them through its
reactive queries. A subscribe on one broker is in every broker's trie a few
milliseconds later; a publish on any broker is matched there, delivered to its
own clients, and forwarded over MQTT to the brokers holding the rest. See
`doc/rama-subscriptions.md` for the shapes considered and why.

In `src/mqttkat/rama/` and around it:

- `module.clj` — `MqttKatModule`: one `*session-events` depot carrying connects,
  disconnects, subscribes, unsubscribes, queued messages, retained messages and
  broker announcements, and a stream topology keeping six PStates. `$$sessions`
  is one record per client id: the last CONNECT's terms, whether it is still
  connected and on which broker, how many times it has been, and its
  subscriptions — a clean session's go on disconnect, a persistent session's
  stay. `$$subscriptions` is the same subscriptions arranged for the brokers:
  `shard → filter → client-id → entry`, sixty-four shards, each small enough to
  be proxied, each entry saying whether its client is connected. `$$retained` is
  the retained messages, sharded the same way. `$$queued` is what is waiting for
  each persistent session that is away. `$$brokers` is where each broker says
  where it listens and how it is doing — a few figures every five seconds, so
  any broker's console can show every broker — and `$$broker->clients` which
  clients it holds; when a broker comes back, whatever its previous run held
  is let go. `$$expiring`
  is when each parked session is due, swept by a tick. Stream, so an
  append with `:ack` returns with the PStates updated; every connection has a
  `connect-id`, every run of a broker an incarnation, and every write is a
  replace or a delete, so a record run twice gives the same answer.
- `cluster.clj` — the switch between in-process and a real cluster, and both
  directions of traffic. Up: it listens on the broker's event bus and appends
  asynchronously, so nothing a client waits for ever waits on Rama; the handlers
  know nothing about it. Down: `watch!` opens a reactive proxy per shard and one
  on the registry, and applies every diff to `:trie` and `:brokers`. The first
  callback of each proxy carries the whole value, so a broker that starts later
  has everything without a separate load. On a publish it names the other
  brokers with a matching subscription and hands the message to the bridge.
- `bridge.clj` — broker to broker, in MQTT: one client connection per peer,
  opened on first use, identified as `mqttkat-bridge/<broker-id>`. A publish
  arriving on a bridge connection is delivered locally and never forwarded again;
  every subscription is held by exactly one broker, so one hop is the whole
  route. Not through Rama, on purpose: state goes through Rama, traffic is one
  socket write. A shared subscription (`$share/g/…`) with members on several
  brokers is served by one of them per publish, chosen on the publisher's broker
  where the whole group is visible and told to the chosen one in a user property
  on the forwarded copy; the others leave the group alone.
- Sessions roam. A persistent session's subscriptions and whatever is owed to
  it — queued while it was away by whichever broker saw the publish, or left
  unacknowledged when it went — live in Rama; a client coming back on any
  broker, including after the broker it was on died, gets its session and its
  messages there, and a message leaves the cluster's queue only once the
  client has acknowledged it. A client connecting while its session is live on
  another broker takes it over from there: that broker is told, and drops the
  old connection with `Session taken over`.
- `retained.clj` — the retained messages, one atom every broker reads from;
  writes go through it and are recorded in Rama, and what Rama pushes back is
  applied to every broker's copy, so a message retained on one broker is
  replayed on any and is still there after all of them have restarted. `$SYS`
  topics stay each broker's own.
- `trie.clj` — the subscription trie, triennium's layout with the three
  operations the broker had to fix, shared by the broker's own tries and the
  cluster copy.
- `test/mqttkat/rama_test.clj` — the module under an InProcessCluster with two
  "brokers" on it plus a stand-in peer listener; connects, disconnects,
  subscribes, unsubscribes, retained messages, shared groups and forwarded
  publishes at every QoS through the real broker; part of `lein test`.

The console has a **Brokers** tab (`/brokers`): every broker in the cluster
as the registry has it — address, version, uptime, clients, parked sessions and
subscriptions, message rates, queue depth, heap and CPU, and how long ago it
last reported. A broker three reports behind is shown as stale. Live over the
websocket like the other pages; on a broker running without Rama it says so.

The same page sets the **connection redirect** policy, for the whole cluster
(§4.13, CONNACK `0x9C Use another server` with a Server Reference — MQTT 5
clients only, since 3.1.1 has no way to be told): *off*, *round robin* — each
broker takes its turn and sends the rest on, one to each other broker in turn —
or *load based* — to the broker with the fewest clients as last reported, plus
what has been sent there since. And how the client is told: *accept, then
DISCONNECT* (the default — CONNACK Success, with Session Present as the cluster
knows it, then `DISCONNECT 0x9C` with the Server Reference, which is what most
client libraries act on) or the *CONNACK reason code* (`CONNACK 0x9C` with the
reference, then the close the spec requires). A broker sent a client takes it: the
sender notes it on the client's session record first, so a client is never passed
on twice. Bridges are never redirected.

Each broker needs a name and an address the others can reach:
`-Dmqttkat.brokerId` (default: the host name) and `-Dmqttkat.advertise`
(default: the host name; the port is the one it listens on).

### In-process

```
java -Dmqttkat.rama=in-process -jar target/mqtt-kat-0.0.1-standalone.jar 1883 8081
```

Starts an InProcessCluster inside the broker and launches the module into it. No
cluster to run and nothing to deploy — this is what to use in the REPL and what
the tests use. Its data is in a temporary directory that goes with the JVM, so it
gives durability across a client's reconnect, not across a broker restart.

### External cluster

A real cluster from the Rama 1.9.0 distribution (the version must match the
`com.rpl/rama` dependency in `project.clj`). A single node is Zookeeper, a
Conductor and one Supervisor, each in its own terminal from the unpacked release;
the `rama.yaml` there needs `conductor.host` and `zookeeper.servers` pointing at
`localhost`:

```
./rama devZookeeper
./rama conductor
./rama supervisor
```

The module goes to the cluster as the thin jar — `lein jar`, not `lein uberjar`:
the workers have Rama and Clojure already, and the module's namespace depends on
nothing else in this project.

```
lein jar
./rama deploy --action launch --jar /path/to/mqtt-kat/target/mqtt-kat-0.0.1.jar \
    --module mqttkat.rama.module/MqttKatModule --tasks 4 --threads 2 --workers 1
```

Then the brokers connect to it as clients. `scripts/brokers.bb` starts as many as
you like on one machine, each with its own ports, all on the local cluster:

```
lein uberjar
bb scripts/brokers.bb start 3        # broker-1 1885/8085, broker-2 1886/8086, broker-3 1887/8087
bb scripts/brokers.bb status
bb scripts/brokers.bb kill 2         # SIGKILL, to watch the others cope
bb scripts/brokers.bb logs 2
bb scripts/brokers.bb stop           # SIGTERM: each withdraws itself from the cluster
```

`--port`, `--http`, `--conductor`, `--advertise` and `--heap` change the defaults;
logs and pid files are under `logs/brokers/`. By hand, two brokers look like this:

```
java -Dmqttkat.rama=external -Dmqttkat.rama.conductor=localhost \
     -Dmqttkat.brokerId=broker-A -Dmqttkat.advertise=127.0.0.1 \
     -jar target/mqtt-kat-0.0.1-standalone.jar 1885 8085
java -Dmqttkat.rama=external -Dmqttkat.rama.conductor=localhost \
     -Dmqttkat.brokerId=broker-B -Dmqttkat.advertise=127.0.0.1 \
     -jar target/mqtt-kat-0.0.1-standalone.jar 1886 8086

mosquitto_sub -p 1886 -t 'demo/#' -v &
mosquitto_pub -p 1885 -t demo/hello -m "from A"

mosquitto_pub -p 1885 -t state/x -m "kept" -r     # retained on A…
mosquitto_sub -p 1886 -t 'state/#' -v -C 1        # …replayed on B

mosquitto_sub -p 1885 -t '$share/g/work/#' -v &   # one member on each broker:
mosquitto_sub -p 1886 -t '$share/g/work/#' -v &   # each job reaches exactly one

mosquitto_sub -p 1885 -i roamer -c -q 1 -t 'roam/#' # persistent session on A; kill A
mosquitto_pub -p 1886 -t roam/x -m "while away" -q 1  # queued in Rama by B
mosquitto_sub -p 1886 -i roamer -c -q 1 -t 'roam/#' -C 1 # back on B: gets it
```

`-Dmqttkat.rama.conductor` defaults to `localhost`, so on one machine it can be
left out. The Cluster UI is on port 8888 of the Conductor. `./rama deploy --action update` with
a new jar updates the running module; see `./rama help` for the rest. If
something else on the machine has port 3000, the Supervisor will not start:
give it `supervisor.port.range: [3100, 4200]` in `rama.yaml` (the range must be
at least a thousand wide).

Two things about a development cluster that will bite. The daemons started from a
terminal die with it, and the module's data lives on under `local-rama-data`: a
cluster restarted with a large backlog of unprocessed events can spend a long
time — or, after enough kill/restart cycles, forever — retrying them, and every
broker append then times out (`could not record the connect of …` in the broker
log; `Stream processing timeout` in `logs/worker-*.log`; the Brokers page empty).
The brokers are not lost, the cluster is stuck. A fresh module is the cure for a
dev cluster:

```
echo mqttkat.rama.module/MqttKatModule | ./rama destroy mqttkat.rama.module/MqttKatModule
./rama deploy --action launch --jar /path/to/mqtt-kat/target/mqtt-kat-0.0.1.jar \
    --module mqttkat.rama.module/MqttKatModule --tasks 4 --threads 2 --workers 1
```

(the destroy asks for the module name as confirmation, which is what the echo
answers). And `lein jar` removes the uberjar from `target/`, so build the uberjar
again before starting brokers.

Without `-Dmqttkat.rama` at all the broker runs as it always has.

## And here are some links with info to help me:
https://gist.github.com/Botffy/3860641

http://tutorials.jenkov.com/java-nio/non-blocking-server.html

http://docs.oasis-open.org/mqtt/mqtt/v5.0/csprd01/mqtt-v5.0-csprd01.html

http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/errata01/os/mqtt-v3.1.1-errata01-os-complete.html#_Toc442180846

https://github.com/http-kit/http-kit

https://github.com/eclipse/paho.mqtt.java

https://lispcast.com/3-things-java-can-steal-from-clojure/

https://gist.github.com/yukaizhao/155d931326e298d6404f

https://crunchify.com/java-nio-non-blocking-io-with-server-client-example-java-nio-bytebuffer-and-channels-selector-java-nio-vs-io/

http://rox-xmlrpc.sourceforge.net/niotut/

https://deepwiki.com/thomas-shares/mqtt-kat

https://github.com/hobbyquaker/awesome-mqtt
## Usage

I call `(start)` function in the repl and then use an MQTT client to send packets to it and wait for it to crash.

or create an uberjar with `lein uberjar` and start it with `java -Dmqttkat.sysInterval=5  -jar target/mqtt-kat-0.0.1-standalone.jar  1883 8081`

Run the test client with 

`lein run -m mqttkat.load.runner --publishers 2000 --subscribers 20000 --topics 1000 --messages 2000000 --rate 10000 --qos 1 --drain-ms 5000 --source-ips 1`

or from a config file — `doc/load.edn` is one that drives three brokers from
`bb scripts/brokers.bb start 3`, spreading the clients over them round-robin so the
traffic crosses the bridges:

`lein run -m mqttkat.load.runner --config doc/load.edn --rate 20000`

The file is an EDN map of the same options as the flags, keywords for keys;
a flag on the command line wins over the file. `--brokers a:1885,b:1886` does the
same from the command line. `--mqtt mixed` makes every pool half 3.1.1 and half
version 5 — alternating within each broker and topic, so every broker and every
topic has both, and messages cross between the versions both ways; the report
shows the split (`:mqtt :mixed` in the config file). `--mqtt 5 --follow-redirects 1` is the other way of
spreading a load: every client goes to the first broker and follows wherever it
is sent, so the brokers do the balancing by their redirect policy, and the
report says how many were sent on and where they landed.

and here are the other options:

```
  --config FILE      an EDN map of these options, keywords for keys; the command line wins
  --brokers H:P,H:P  several brokers; clients are spread over them round-robin
  --mqtt 4|5|mixed   protocol version the clients speak; mixed is half 3.1.1, half 5 (4)
  --follow-redirects 1  connect everything to the first broker and go where it sends you (0)
  --host HOST        broker host (localhost)
  --port PORT        broker port (1883)
  --publishers N     publishing clients (10)
  --subscribers N    subscribing clients (10)
  --topics N         topics, shared between both pools (5)
  --messages N       total messages to publish; 0 to keep going until stopped (100000)
  --duration N       stop after N seconds; 0 for no time limit (0)
  --progress-ms N    how often to print a progress line while running (5000)
  --rate N           target messages per second, aggregate; 0 for unlimited (10000)
  --qos 0|1|2        publish and subscribe QoS (0)
  --size N           payload bytes, minimum 28 (128)
  --window N         unacknowledged publishes allowed per publisher (100)
  --drain-ms N       quiet period that counts as fully drained (5000)
  --max-drain-ms N   cap on the whole drain, however much is still arriving (300000)
  --source-ips N     spread clients over N source addresses; 0 to choose automatically
```

### Back-pressure

A slow subscriber holds up the publishers feeding it: the broker stops reading
their sockets (`Connection.pauseReading`) instead of dropping messages. There are
two kinds of hold. The first, at QoS 0, is on the subscriber's socket write queue,
which `drained()` releases. The second, at QoS 1 and 2, is on its queue of messages
waiting for an inflight slot. `ackDrained()` releases that one as the
PUBACKs/PUBCOMPs bring the queue back under the resume threshold. A publisher can
be held by several subscribers at once. It is only read again when every one of
them has let go (`peerHolds` is a count, not a flag). The `pending-limit` refusal
exists only as a backstop, and in a healthy run the `dropped` counter stays at 0
for QoS 1 traffic.

The bridges between brokers work the same way. Each peer has a queue and a thread
of its own, which waits for the peer's acknowledgements and writes to its socket;
the broker's handler threads only enqueue. A publisher whose messages pile up in a
link's queue (`bridge/queue-pause-at`) stops being read until the queue has
drained. Brokers grant each other's bridges a Receive Maximum of
`bridge/receive-maximum` (16,384) rather than a client's 128.

## Thank you

First of all an extra big thank you to [Feng Shen](http://shenfeng.me/) for making http-kit. I have borrowed heavily from his code. And also a big thank you to the [Eclipse Paho Project](https://www.eclipse.org/paho/). I have used their [code](https://github.com/eclipse/paho.mqtt.java) as inspiration as well and yes I have copied the MQTT packet length code from them.

And also a big thank you for the people from [ClojureWerkz](http://clojurewerkz.org/) for their [triennium](https://github.com/clojurewerkz/triennium) library. It just works.

## License

Copyright © 2018 Thomas van der Veen

Distributed under the Apache License Version 2.0.
