# Thoughts and ramblings...

In this file will go my thoughts and ramblings about this project and what I have done and what I might do next.

## 20180214

Ok, now I am removing all the subscriptions of a client if it goes away (ie. a `DISCONNECT` or otherwise) and when it sends a `UNSSUBSCRIBE`. When I now test with jMeter I can run with 16 threads for 100 iterations with out a problem and that is over 1600 messages. woohooo. Next probably I need to add some spec to this project. And find the code I wrote for the wildcards.

## 20180213

Ok, now I can run jMeter with 4 threads and I think the next problem is that is a client goes away and it has subscribed to a topic, that subscription remains, so need away to cancel that and that means not just taking care of `UNSUBSCRIBE` or a `DISCONNECT` but also when the client goes AWOL.

## 20180211

So the last two days I have imlemented the asynchronous sending of messages. There is a second thread pool for when the messages are send and I now also use ByteBuffer.duplicate. This all seems to work now with small messages that are send relatively slowly. When I use [MQTT-Spy](https://github.com/eclipse/paho.mqtt-spy/wiki) I can press the publish button as fast as I can and it all works. However when I use [JMeter](http://jmeter.apache.org/) with this MQTT [extension](https://github.com/emqtt/mqtt-jmeter) I am getting java.nio.BufferUnderflowException exceptions pretty quickly.

## 20180209

Today I removed all the callback code from the project. HTTP is a request-response type protocol where each request is answered with a response. So having a callback on a request to send the response back to the client makes lots of sense in that case. But MQTT is different: There are quite a few cases there is no response from the broker to an incoming message from a client and in case of a `PUBLISH` there are onward messages to the subscribers. And this can have quite a big fan-out (thousands of client subscribed to the same topic for instance). So hence the code to call back into the server and send the message to a different client.

Next step is to put the sending of messages on an thread pool as well so that that part becomes async as well and handle large loads. Also investigate to use duplicate ByteBuffers and make it all go parallel.
