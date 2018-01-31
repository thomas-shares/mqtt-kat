# mqtt-kat

mqtt-kat is an attempt at an [MQTT](http://mqtt.org/) broker based on the concepts of [http-kit](https://github.com/http-kit/http-kit). As http-kit this means low level java code to do the handling of the NIO and decoding and encoding of the MQTT packets. MQTT packets are en/decoded to Clojure maps and handled by Clojure code to do all the clever stuff a broker needs to do.

The idea is to see if a MQTT Broker could be as scalable as http-kit and handle as many concurrent connection as http-kit does.

## What does it do at the moment?

Not much. At time of writing the code can accept a connection of a MQTT Client and receive several packets from it and return an appropriate packet in some case. But it certainly doesn't do any more with it. So if a `CONNECT` package is received a `CONNACK` is send back regardless. And while mqtt-kat may accept a `SUBSCRIBE` packet and respond to it as per the spec, at the moment no packets are forward on a `PUBLISH` and most values are hardcoded at the moment as well. So things might work a first time for instance.

## Are there any bugs?

Yes

Loads. Too many to mention actually.

## What about the name?

I first thought of calling it mqtt-kit... but then decide that mqtt-kat made more sense. Somehow.

## Will it ever be a proper MQTT broker supporting QOS > 0?

Probably not.

## Will it ever support MQTT version 5?

Probably not.

## And here are some links with info to help me:
https://gist.github.com/Botffy/3860641

http://tutorials.jenkov.com/java-nio/non-blocking-server.html

http://docs.oasis-open.org/mqtt/mqtt/v5.0/csprd01/mqtt-v5.0-csprd01.html

http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/errata01/os/mqtt-v3.1.1-errata01-os-complete.html#_Toc442180846

https://github.com/http-kit/http-kit

https://github.com/eclipse/paho.mqtt.java

https://lispcast.com/3-things-java-can-steal-from-clojure/

## Usage

I call `(start)` function in the repl and then use an MQTT client to send packets to it and wait for it to crash.

## Thank you

First of all an extra big thank you to [Feng Shen](http://shenfeng.me/) for making http-kit. I have borrowed heavily from his code. And also a big thank you to the [Eclipse Paho Project](https://www.eclipse.org/paho/). I have used their [code](https://github.com/eclipse/paho.mqtt.java) as inspiration as well and yes I have copied the MQTT packet length code from them.

## License

Copyright © 2018 Thomas van der Veen

Distributed under the Apache License Version 2.0.
