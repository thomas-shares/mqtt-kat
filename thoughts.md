# Thoughts and ramblings...

In this file will go my thoughts and ramblings about this project and what I have done and what I might do next.

## 20190120

I just ran a test of 10000 publish message against mosquitto while being subscribed to each of the possible topics the client sends on (Spec generates the topics to subscribe on and these will be used to publish on). This all worked fine with out any problems. I can't replicate this yet on my own broker... that would be the next step. 

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
