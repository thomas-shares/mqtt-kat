# Introduction to mqtt-kat

TODO: write [great documentation](http://jacobian.org/writing/what-to-write/)


{:client-id mosqpub|84157-Thomass-M, :packet-type :CONNECT, :protocol-version 3, :password nil, :user-name nil, :protocol-name MQIsdp, :username-set false, :keep-alive 60, :will-flag false, :password-set false, :will-qos :0, :reserved false, :clean-start true, :flags 0, :will-retain false, :client-key #object[sun.nio.ch.SelectionKeyImpl 0x46b195d sun.nio.ch.SelectionKeyImpl@46b195d]}

{:client-key #object[sun.nio.ch.SelectionKeyImpl 0x46b195d sun.nio.ch.SelectionKeyImpl@46b195d], :flags 0, :packet-type :DISCONNECT}


{:client-key #object[sun.nio.ch.SelectionKeyImpl 0x46b195d sun.nio.ch.SelectionKeyImpl@46b195d], :duplicate false, :flags 0, :message-qos :0, :packet-type :PUBLISH, :payload #object[[B 0x4e4e8a48 [B@4e4e8a48], :retain false, :topic test}

{:client-key #object[sun.nio.ch.SelectionKeyImpl 0x176ca445 sun.nio.ch.SelectionKeyImpl@176ca445], :flags 2, :packet-identifier 1, :packet-type :SUBSCRIBE, :topics [{:message-qos :0, :topic test}]}

{:client-key #object[sun.nio.ch.SelectionKeyImpl 0x176ca445 sun.nio.ch.SelectionKeyImpl@176ca445], :flags 0, :packet-type :PINGREQ}

used to make a Linux machine accept lots of connections:

ulimit -n 3000000

and

#!/bin/bash

sysctl -w fs.file-max=11000000
sysctl -w fs.nr_open=11000000

sysctl -w net.core.somaxconn=65535
sysctl -w net.ipv4.tcp_max_syn_backlog=65535

sysctl -w net.ipv4.ip_local_port_range="1025 65535"

sysctl -w net.ipv4.tcp_mem="100000000 100000000 100000000"
sysctl -w net.ipv4.tcp_rmem='4096 4096 4096'
sysctl -w net.ipv4.tcp_wmem='4096 4096 4096'

from: https://oatpp.io/benchmark/websocket/2-million/


# Retain test

```shell script
1589297311: mosquitto version 1.6.9 starting
1589297311: Using default config.
1589297311: Opening ipv6 listen socket on port 1883.
1589297311: Opening ipv4 listen socket on port 1883.
1589297315: New connection from 127.0.0.1 on port 1883.
1589297315: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297315: No will message specified.
1589297315: Sending CONNACK to myclientid (0, 0)
1589297315: Received DISCONNECT from myclientid
1589297315: Client myclientid disconnected.
1589297315: New connection from 127.0.0.1 on port 1883.
1589297315: New client connected from 127.0.0.1 as myclientid2 (p2, c1, k0).
1589297315: No will message specified.
1589297315: Sending CONNACK to myclientid2 (0, 0)
1589297315: Received DISCONNECT from myclientid2
1589297315: Client myclientid2 disconnected.
1589297315: New connection from 127.0.0.1 on port 1883.
1589297315: New client connected from 127.0.0.1 as clean retained (p2, c1, k0).
1589297315: No will message specified.
1589297315: Sending CONNACK to clean retained (0, 0)
1589297315: Received SUBSCRIBE from clean retained
1589297315: 	# (QoS 0)
1589297315: clean retained 0 #
1589297315: Sending SUBACK to clean retained
1589297317: Received DISCONNECT from clean retained
1589297317: Client clean retained disconnected.
1589297318: New connection from 127.0.0.1 on port 1883.
1589297318: New client connected from 127.0.0.1 as myclientid2 (p2, c1, k0).
1589297318: No will message specified.
1589297318: Sending CONNACK to myclientid2 (0, 0)
1589297318: Received SUBSCRIBE from myclientid2
1589297318: 	TopicA (QoS 2)
1589297318: myclientid2 2 TopicA
1589297318: Sending SUBACK to myclientid2
1589297318: Received SUBSCRIBE from myclientid2
1589297318: 	TopicA/B (QoS 2)
1589297318: myclientid2 2 TopicA/B
1589297318: Sending SUBACK to myclientid2
1589297318: Received SUBSCRIBE from myclientid2
1589297318: 	Topic/C (QoS 2)
1589297318: myclientid2 2 Topic/C
1589297318: Sending SUBACK to myclientid2
1589297319: Received UNSUBSCRIBE from myclientid2
1589297319: 	TopicA
1589297319: myclientid2 TopicA
1589297319: Sending UNSUBACK to myclientid2
1589297319: New connection from 127.0.0.1 on port 1883.
1589297319: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297319: No will message specified.
1589297319: Sending CONNACK to myclientid (0, 0)
1589297319: Received PUBLISH from myclientid (d0, q1, r0, m2, 'TopicA', ... (0 bytes))
1589297319: Sending PUBACK to myclientid (m2, rc0)
1589297319: Received PUBLISH from myclientid (d0, q1, r0, m3, 'TopicA/B', ... (0 bytes))
1589297319: Sending PUBACK to myclientid (m3, rc0)
1589297319: Sending PUBLISH to myclientid2 (d0, q1, r0, m1, 'TopicA/B', ... (0 bytes))
1589297319: Received PUBLISH from myclientid (d0, q1, r0, m4, 'Topic/C', ... (0 bytes))
1589297319: Sending PUBACK to myclientid (m4, rc0)
1589297319: Sending PUBLISH to myclientid2 (d0, q1, r0, m2, 'Topic/C', ... (0 bytes))
1589297319: Received PUBACK from myclientid2 (Mid: 1, RC:0)
1589297319: Received PUBACK from myclientid2 (Mid: 2, RC:0)
1589297321: Received DISCONNECT from myclientid2
1589297321: Client myclientid2 disconnected.
1589297321: Received DISCONNECT from myclientid
1589297321: Client myclientid disconnected.
1589297387: New connection from 127.0.0.1 on port 1883.
1589297387: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297387: No will message specified.
1589297387: Sending CONNACK to myclientid (0, 0)
1589297387: Received DISCONNECT from myclientid
1589297387: Client myclientid disconnected.
1589297387: New connection from 127.0.0.1 on port 1883.
1589297387: New client connected from 127.0.0.1 as myclientid2 (p2, c1, k0).
1589297387: No will message specified.
1589297387: Sending CONNACK to myclientid2 (0, 0)
1589297387: Received DISCONNECT from myclientid2
1589297387: Client myclientid2 disconnected.
1589297387: New connection from 127.0.0.1 on port 1883.
1589297387: New client connected from 127.0.0.1 as clean retained (p2, c1, k0).
1589297387: No will message specified.
1589297387: Sending CONNACK to clean retained (0, 0)
1589297387: Received SUBSCRIBE from clean retained
1589297387: 	# (QoS 0)
1589297387: clean retained 0 #
1589297387: Sending SUBACK to clean retained
1589297389: Received DISCONNECT from clean retained
1589297389: Client clean retained disconnected.
1589297390: New connection from 127.0.0.1 on port 1883.
1589297390: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297390: No will message specified.
1589297390: Sending CONNACK to myclientid (0, 0)
1589297390: Received PUBLISH from myclientid (d0, q0, r1, m0, 'TopicA/B', ... (5 bytes))
1589297390: Received PUBLISH from myclientid (d0, q1, r1, m2, 'Topic/C', ... (5 bytes))
1589297390: Sending PUBACK to myclientid (m2, rc0)
1589297390: Received PUBLISH from myclientid (d0, q2, r1, m3, 'TopicA/C', ... (5 bytes))
1589297390: Sending PUBREC to myclientid (m3, rc0)
1589297390: Received PUBREL from myclientid (Mid: 3)
1589297390: Sending PUBCOMP to myclientid (m3)
1589297391: Received SUBSCRIBE from myclientid
1589297391: 	+/+ (QoS 2)
1589297391: myclientid 2 +/+
1589297391: Sending SUBACK to myclientid
1589297391: Sending PUBLISH to myclientid (d0, q0, r1, m0, 'TopicA/B', ... (5 bytes))
1589297391: Sending PUBLISH to myclientid (d0, q2, r1, m1, 'TopicA/C', ... (5 bytes))
1589297391: Sending PUBLISH to myclientid (d0, q1, r1, m2, 'Topic/C', ... (5 bytes))
1589297391: Received PUBREC from myclientid (Mid: 1)
1589297391: Sending PUBREL to myclientid (m1)
1589297391: Received PUBACK from myclientid (Mid: 2, RC:0)
1589297391: Received PUBCOMP from myclientid (Mid: 1, RC:0)
1589297392: Received DISCONNECT from myclientid
1589297392: Client myclientid disconnected.
1589297392: New connection from 127.0.0.1 on port 1883.
1589297392: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297392: No will message specified.
1589297392: Sending CONNACK to myclientid (0, 0)
1589297392: Received PUBLISH from myclientid (d0, q0, r1, m0, 'TopicA/B', ... (0 bytes))
1589297392: Received PUBLISH from myclientid (d0, q1, r1, m5, 'Topic/C', ... (0 bytes))
1589297392: Sending PUBACK to myclientid (m5, rc0)
1589297392: Received PUBLISH from myclientid (d0, q2, r1, m6, 'TopicA/C', ... (0 bytes))
1589297392: Sending PUBREC to myclientid (m6, rc0)
1589297392: Received PUBREL from myclientid (Mid: 6)
1589297392: Sending PUBCOMP to myclientid (m6)
1589297393: Received SUBSCRIBE from myclientid
1589297393: 	+/+ (QoS 2)
1589297393: myclientid 2 +/+
1589297393: Sending SUBACK to myclientid
1589297394: Received DISCONNECT from myclientid
1589297394: Client myclientid disconnected.
^C1589297402: mosquitto version 1.6.9 terminating
Thomass-MacBook-Pro:mqtt-kat thomasvanderveen$ mosquitto -v
1589297403: mosquitto version 1.6.9 starting
1589297403: Using default config.
1589297403: Opening ipv6 listen socket on port 1883.
1589297403: Opening ipv4 listen socket on port 1883.
1589297413: New connection from 127.0.0.1 on port 1883.
1589297413: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297413: No will message specified.
1589297413: Sending CONNACK to myclientid (0, 0)
1589297413: Received DISCONNECT from myclientid
1589297413: Client myclientid disconnected.
1589297414: New connection from 127.0.0.1 on port 1883.
1589297414: New client connected from 127.0.0.1 as myclientid2 (p2, c1, k0).
1589297414: No will message specified.
1589297414: Sending CONNACK to myclientid2 (0, 0)
1589297414: Received DISCONNECT from myclientid2
1589297414: Client myclientid2 disconnected.
1589297414: New connection from 127.0.0.1 on port 1883.
1589297414: New client connected from 127.0.0.1 as clean retained (p2, c1, k0).
1589297414: No will message specified.
1589297414: Sending CONNACK to clean retained (0, 0)
1589297414: Received SUBSCRIBE from clean retained
1589297414: 	# (QoS 0)
1589297414: clean retained 0 #
1589297414: Sending SUBACK to clean retained
1589297416: Received DISCONNECT from clean retained
1589297416: Client clean retained disconnected.
1589297416: New connection from 127.0.0.1 on port 1883.
1589297416: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297416: No will message specified.
1589297416: Sending CONNACK to myclientid (0, 0)
1589297416: Received PUBLISH from myclientid (d0, q0, r1, m0, 'TopicA/B', ... (5 bytes))
1589297416: Received PUBLISH from myclientid (d0, q1, r1, m2, 'Topic/C', ... (5 bytes))
1589297416: Sending PUBACK to myclientid (m2, rc0)
1589297416: Received PUBLISH from myclientid (d0, q2, r1, m3, 'TopicA/C', ... (5 bytes))
1589297416: Sending PUBREC to myclientid (m3, rc0)
1589297416: Received PUBREL from myclientid (Mid: 3)
1589297416: Sending PUBCOMP to myclientid (m3)
1589297417: Received SUBSCRIBE from myclientid
1589297417: 	+/+ (QoS 2)
1589297417: myclientid 2 +/+
1589297417: Sending SUBACK to myclientid
1589297417: Sending PUBLISH to myclientid (d0, q0, r1, m0, 'TopicA/B', ... (5 bytes))
1589297417: Sending PUBLISH to myclientid (d0, q2, r1, m1, 'TopicA/C', ... (5 bytes))
1589297417: Sending PUBLISH to myclientid (d0, q1, r1, m2, 'Topic/C', ... (5 bytes))
1589297417: Received PUBREC from myclientid (Mid: 1)
1589297417: Sending PUBREL to myclientid (m1)
1589297417: Received PUBACK from myclientid (Mid: 2, RC:0)
1589297417: Received PUBCOMP from myclientid (Mid: 1, RC:0)
1589297418: Received DISCONNECT from myclientid
1589297418: Client myclientid disconnected.
1589297418: New connection from 127.0.0.1 on port 1883.
1589297418: New client connected from 127.0.0.1 as myclientid (p2, c1, k0).
1589297418: No will message specified.
1589297418: Sending CONNACK to myclientid (0, 0)
1589297418: Received PUBLISH from myclientid (d0, q0, r1, m0, 'TopicA/B', ... (0 bytes))
1589297418: Received PUBLISH from myclientid (d0, q1, r1, m5, 'Topic/C', ... (0 bytes))
1589297418: Sending PUBACK to myclientid (m5, rc0)
1589297418: Received PUBLISH from myclientid (d0, q2, r1, m6, 'TopicA/C', ... (0 bytes))
1589297418: Sending PUBREC to myclientid (m6, rc0)
1589297418: Received PUBREL from myclientid (Mid: 6)
1589297418: Sending PUBCOMP to myclientid (m6)
1589297419: Received SUBSCRIBE from myclientid
1589297419: 	+/+ (QoS 2)
1589297419: myclientid 2 +/+
1589297419: Sending SUBACK to myclientid
1589297420: Received DISCONNECT from myclientid
1589297420: Client myclientid disconnected.
```
