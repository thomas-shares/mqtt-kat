package org.mqttkat.server;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.*;
import java.net.InetSocketAddress;

import java.io.IOException;
import java.util.Iterator;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import clojure.lang.IPersistentMap;

import org.mqttkat.IHandler;
import org.mqttkat.MqttSendExecutor;
import org.mqttkat.packages.*;

import static java.nio.channels.SelectionKey.*;
import static org.mqttkat.MqttStat.*;


class PendingKey {
    public final SelectionKey key;
    // operation: can be register for write or close the selectionkey
    public final int Op;

    PendingKey(SelectionKey key, int op) {
        this.key = key;
        Op = op;
    }

    public static final int OP_WRITE = -1;
}


public class MqttServer implements Runnable {
    static final String THREAD_NAME = "server-loop";

    private final IHandler handler;

    private final Selector selector;
    private final ServerSocketChannel serverChannel;

    private final int port;
    private final ByteBuffer buf = ByteBuffer.allocate(4096);
    private final MqttSendExecutor executor;
    private Thread serverThread = null;

    // queue operations from worker threads to the IO thread
    private final ConcurrentLinkedQueue<PendingKey> pending = new ConcurrentLinkedQueue<PendingKey>();

    private final ConcurrentHashMap<SelectionKey, Boolean> keptAlive = new ConcurrentHashMap<SelectionKey, Boolean>();

    enum Status {STOPPED, RUNNING, STOPPING}

    // Will not set keep-alive headers when STOPPING, allowing reqs to drain
    private final AtomicReference<Status> status = new AtomicReference<Status>(Status.STOPPED);


    public MqttServer(String ip, int port, IHandler handler) throws IOException {
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.handler = handler;
        this.serverChannel.configureBlocking(false);
        this.serverChannel.socket().bind(new InetSocketAddress(ip, port));
        this.serverChannel.register(selector, OP_ACCEPT);
        this.port = port;
        this.executor = new MqttSendExecutor(selector, 16);
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel s = null;

        try {
            while ((s = serverSocketChannel.accept()) != null) {
                s.configureBlocking(false);
                //make Mqtt attribute
                //MqttAtta atta = s.register(selector, OP_READ, null);
                //atta.channel = new AsyncChannel;
            }
        } catch (Exception e) {
            System.out.println("accept incoming request error");
        }
    }

    private void closeKey(final SelectionKey key, int status) {

        keptAlive.remove(key);

        try {
            Channel channel = key.channel();
            channel.close();
        } catch (IOException e) {
            System.out.println("Channel fail, Status : " + status);
            e.printStackTrace();
        }

    }

    public void closeConnection(final SelectionKey key) {
        try {
            key.channel().close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    public void run() {
        System.out.println("Server starting on port " + this.port);

        while (true) {
            try {
                PendingKey pendingKey;
                while (!pending.isEmpty()) {
                    pendingKey = pending.poll();
                    if (pendingKey.Op == PendingKey.OP_WRITE) {
                        if (pendingKey.key.isValid()) {
                            pendingKey.key.interestOps(OP_WRITE);
                        }
                    } else {
                        closeKey(pendingKey.key, pendingKey.Op);
                    }

                    if (selector.select() <= 0) {
                        continue;
                    }
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    for (SelectionKey key : selectedKeys) {
                        if (!key.isValid())
                            continue;

                        if (key.isAcceptable()) {
                            accept(key);

                        } else if (key.isReadable()) {
                            doRead(key);
                        } else if (key.isWritable()) {
                            doWrite(key);
                        }
                    }
                    selectedKeys.clear();
                }
            } catch (IOException e) {
                status.set(Status.STOPPED);
                System.out.println("http server error.");
            }
        }

    }


    private void doRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buf.clear();

            byte type = 0;
            byte flags = 0;
            int read = 0;
            if (read == -1) {
                closeKey(key, 1);
            } else if (read > 0) {
                buf.flip();

                do {
                    byte[] bytes = new byte[1];
                    buf.get(bytes, 0, 1);

                    type = (byte) ((bytes[0] & 0xff) >> 4);
                    flags = (byte) (bytes[0] &= 0x0f);

                    byte digit;
                    int multiplier = 1;
                    int msgLength = 0;

                    do {
                        digit = buf.get(); // bytes[0];
                        msgLength += ((digit & 0x7F) * multiplier);
                        multiplier *= 128;
                    } while ((digit & 0x80) != 0);

                    byte[] remainAndPayload = new byte[msgLength];

                    buf.get(remainAndPayload, 0, msgLength);

                    IPersistentMap incoming = null;
                    switch (type) {
                        case GenericMessage.MESSAGE_CONNECT:
                            incoming = MqttConnect.decode(key, flags, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_CONNACK:
                            incoming = MqttConnAck.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_PUBLISH:
                            incoming = MqttPublish.decode(key, flags, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_PUBACK:
                            incoming = MqttPubAck.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_PUBREC:
                            incoming = MqttPubRec.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_PUBREL:
                            incoming = MqttPubRel.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_PUBCOMP:
                            incoming = MqttPubComp.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_SUBSCRIBE:
                            incoming = MqttSubscribe.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_SUBACK:
                            incoming = MqttSubAck.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_UNSUBSCRIBE:
                            incoming = MqttUnsubscribe.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_UNSUBACK:
                            incoming = MqttUnSubAck.decode(key, remainAndPayload);
                            break;
                        case GenericMessage.MESSAGE_PINGREQ:
                            incoming = MqttPingReq.decode(key);
                            break;
                        case GenericMessage.MESSAGE_PINGRESP:
                            incoming = MqttPingResp.decode(key);
                            break;
                        case GenericMessage.MESSAGE_DISCONNECT:
                            incoming = MqttDisconnect.decode(key);
                            closeKey(key, 0);
                            break;
                        case GenericMessage.MESSAGE_AUTHENTICATION:
                            incoming = MqttAuthenticate.decode(key);
                            break;
                        default:
                            System.out.println("FAIL!!!!!! INVALID packet sent: " + type);
                            closeKey(key, 0);
                            break;
                    }

                    if (incoming != null) {
                        handler.handle(incoming);
                        receivedBytes.getAndAdd(msgLength);
                        receivedMessages.getAndIncrement();
                    }
                } while (buf.limit() > buf.position());

                buf.clear();
            }
        } catch (IOException e) {
            key.cancel();
            ch.close();
        }
    }

    private void doWrite(SelectionKey key) {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        try {
            synchronized (atta) {
                LinkedList<ByteBuffer> toWrites = atta.toWrites;
                int size = toWrites.size();

                if (size == 1) {
                    channel.write(toWrites.get(0));
                } else if (size > 0) {
                    ByteBuffer buffers[] = new ByteBuffer[size];
                    toWrites.toArray(buffers);
                    channel.write(buffers, 0, buffers.length);
                }

                Iterator<ByteBuffer> iterator = toWrites.iterator();
                while (iterator.hasNext()) {
                    if (!iterator.next().hasRemaining()) {
                        iterator.remove();
                    }
                }

                if (toWrites.size() == 0) {
                    if (atta.isKeepAlive()) {
                        key.interestOps(OP_READ);
                        keptAlive.put(key, true);
                    } else {
                        closeKey(key, 0);
                    }
                }
            }
        } catch (Exception e) {
            closeKey(key, 0);
        }
    }

    public boolean start() throws IOException {
        if(!status.compareAndSet(Status.STOPPED, Status.RUNNING))
        {
            return false;
        }
        serverThread = new Thread(this, THREAD_NAME);
        serverThread.start();
        return true;

    }

    public boolean stop(int timeout) {
        if (!status.compareAndSet(Status.RUNNING, Status.STOPPING)) {
            System.err.println("Status is not running.");
            return false;
        }
        try {
            serverChannel.close(); // stop accept any request
        } catch (IOException ignore) {

        }
        handler.close(timeout);

        // close socket, notify on-close handlers
        if (selector.isOpen()) {
            boolean cmex = false;

            do {
                cmex = false;
                try {
                    for (SelectionKey k : selector.keys()) {
                        if (k != null)
                            closeKey(k, 0); // 0 => close by server

                    }
                } catch (java.util.ConcurrentModificationException ignore) {
                    cmex = true;
                }
            } while (cmex);
        }

        try {
            selector.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    public int getPort() {
        return this.serverChannel.socket().getLocalPort();
    }


    public void tryWrite(final SelectionKey key, ByteBuffer... buffers) {
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            ch.write(buffers, 0, buffers.length);
            pending.add(new PendingKey(key, PendingKey.OP_WRITE));
            selector.wakeup();
        } catch (IOException ignored) {
            System.out.println("SocketChannel is ignored.");
            pending.add(new PendingKey(key, 0));
            selector.wakeup();
        }
    }

//	public void sendMessage( final clojure.lang.PersistentVector keys, final Map<Keyword, ?> message) throws IOException {
//		ByteBuffer buffer = MqttEncode.mqttEncoder(message);
//		
//		Iterator<?> it = keys.iterator();
//
//		while(it.hasNext() ) {
//			SelectionKey key = (SelectionKey) it.next();
//			ByteBuffer copyBuf = buffer.duplicate();
//			executor.submit(copyBuf, key);
//			sentMessages.getAndIncrement();
//			sentBytes.getAndAdd(buffer.limit());
//		}
//	}

    public void sendMessageBuffer(final clojure.lang.PersistentVector keys, final ByteBuffer buffer) {
        Iterator<SelectionKey> it = keys.iterator();

        while (it.hasNext()) {
            SelectionKey key = it.next();
            ByteBuffer copyBuf = buffer.duplicate();
            executor.submit(copyBuf, key);
            sentMessages.getAndIncrement();
            sentBytes.getAndAdd(buffer.limit());
        }
    }
}
