# Thoughts and ramblings...

In this file will go my thoughts and ramblings about this project and what I have done and what I might do next.

## 20180209

Today I removed all the callback code from the project. HTTP is a request-response type protocol where each request is answered with a response. So having a callback on a request to send the response back to the client makes lots of sense in that case. But MQTT is different: There are quite a few cases there is no response from the broker to an incoming message from a client and in case of a `PUBLISH` there are onward messages to the subscribers. And this can have quite a big fan-out (thousands of client subscribed to the same topic for instance). So hence the code to call back into the server and send the message to a different client.

Next step is to put the sending of messages on an thread pool as well so that that part becomes async as well and handle large loads. Also investigate to use duplicate ByteBuffers and make it all go parallel.
