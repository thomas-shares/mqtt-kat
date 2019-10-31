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
