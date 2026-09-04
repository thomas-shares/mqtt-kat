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
Ran 64 tests containing 1116 assertions.
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

and here are the other options:

```
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

## Thank you

First of all an extra big thank you to [Feng Shen](http://shenfeng.me/) for making http-kit. I have borrowed heavily from his code. And also a big thank you to the [Eclipse Paho Project](https://www.eclipse.org/paho/). I have used their [code](https://github.com/eclipse/paho.mqtt.java) as inspiration as well and yes I have copied the MQTT packet length code from them.

And also a big thank you for the people from [ClojureWerkz](http://clojurewerkz.org/) for their [triennium](https://github.com/clojurewerkz/triennium) library. It just works.

## License

Copyright © 2018 Thomas van der Veen

Distributed under the Apache License Version 2.0.
