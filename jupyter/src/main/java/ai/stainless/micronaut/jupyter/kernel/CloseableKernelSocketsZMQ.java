package ai.stainless.micronaut.jupyter.kernel;

/*
 * Customized KernelSockets implementation that can be shutdown programmatically.
 * Uses implementation of KernelSocketsZMQ in BeakerX project.
 * License from BeakerX pasted below.
 */

/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import com.twosigma.beakerx.handler.Handler;
import com.twosigma.beakerx.kernel.Config;
import com.twosigma.beakerx.kernel.KernelFunctionality;
import com.twosigma.beakerx.kernel.KernelSockets;
import com.twosigma.beakerx.kernel.SocketCloseAction;
import com.twosigma.beakerx.kernel.msg.JupyterMessages;
import com.twosigma.beakerx.message.Header;
import com.twosigma.beakerx.message.Message;
import com.twosigma.beakerx.message.MessageSerializer;
import com.twosigma.beakerx.security.HashedMessageAuthenticationCode;
import com.twosigma.beakerx.socket.MessageParts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static com.twosigma.beakerx.kernel.msg.JupyterMessages.SHUTDOWN_REPLY;
import static com.twosigma.beakerx.kernel.msg.JupyterMessages.SHUTDOWN_REQUEST;
import static com.twosigma.beakerx.message.MessageSerializer.toJson;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

public class ClosableKernelSocketsZMQ extends KernelSockets {

    public static final Logger logger = LoggerFactory.getLogger(ClosableKernelSocketsZMQ.class);

    public static final String DELIM = "<IDS|MSG>";

    private KernelFunctionality kernel;
    private SocketCloseAction closeAction;
    private HashedMessageAuthenticationCode hmac;
    private ZMQ.Socket hearbeatSocket;
    private ZMQ.Socket controlSocket;
    private ZMQ.Socket shellSocket;
    private ZMQ.Socket iopubSocket;
    private ZMQ.Socket stdinSocket;
    private ZMQ.Poller sockets;
    private ZMQ.Context context;
    private ReentrantLock sendLock;

    private boolean shutdownSystem = false;

    public ClosableKernelSocketsZMQ(KernelFunctionality kernel, Config configuration, SocketCloseAction closeAction) {
        this.closeAction = closeAction;
        this.kernel = kernel;
        this.hmac = new HashedMessageAuthenticationCode(configuration.getKey());
        this.context = ZMQ.context(1);
        this.sendLock = new ReentrantLock();
        configureSockets(configuration);
    }

    private void configureSockets(Config configuration) {
        final String connection = configuration.getTransport() + "://" + configuration.getHost();

        iopubSocket = getNewSocket(ZMQ.PUB, configuration.getIopub(), connection, context);
        hearbeatSocket = getNewSocket(ZMQ.ROUTER, configuration.getHeartbeat(), connection, context);
        controlSocket = getNewSocket(ZMQ.ROUTER, configuration.getControl(), connection, context);
        stdinSocket = getNewSocket(ZMQ.ROUTER, configuration.getStdin(), connection, context);
        shellSocket = getNewSocket(ZMQ.ROUTER, configuration.getShell(), connection, context);

        sockets = new ZMQ.Poller(3);
        sockets.register(hearbeatSocket, ZMQ.Poller.POLLIN);
        sockets.register(shellSocket, ZMQ.Poller.POLLIN);
        sockets.register(controlSocket, ZMQ.Poller.POLLIN);
    }

    public void publish(List<Message> message) {
        sendMsg(this.iopubSocket, message);
    }

    public void send(Message message) {
        sendMsg(this.shellSocket, singletonList(message));
    }

    public String sendStdIn(Message message) {
        sendMsg(this.stdinSocket, singletonList(message));
        return handleStdIn();
    }

    private void sendMsg(ZMQ.Socket socket, List<Message> messages) {
        // causes StackOverflowException
        logger.trace("sendMsg ("+messages.size()+")");
        if (!isShutdown()) {
            messages.forEach(message -> {
                String header = toJson(message.getHeader());
                String parent = toJson(message.getParentHeader());
                String meta = toJson(message.getMetadata());
                String content = toJson(message.getContent());
                logger.trace("header="+header);
                logger.trace("parent="+parent);
                logger.trace("meta="+meta);
                logger.trace("content="+content);
                String digest = hmac.sign(Arrays.asList(header, parent, meta, content));

                ZMsg newZmsg = new ZMsg();
                message.getIdentities().forEach(newZmsg::add);
                newZmsg.add(DELIM);
                newZmsg.add(digest.getBytes(StandardCharsets.UTF_8));
                newZmsg.add(header.getBytes(StandardCharsets.UTF_8));
                newZmsg.add(parent.getBytes(StandardCharsets.UTF_8));
                newZmsg.add(meta.getBytes(StandardCharsets.UTF_8));
                newZmsg.add(content.getBytes(StandardCharsets.UTF_8));
                message.getBuffers().forEach(x -> newZmsg.add(x));
                logger.trace("obtaining sendLock");
                sendLock.lock();
                try {
                    newZmsg.send(socket);
                } catch (Exception e) {
                    logger.error(e.toString());
                } finally {
                    logger.trace("releasing sendLock");
                    sendLock.unlock();
                }
                logger.trace("sendLock stats: "+sendLock.getHoldCount()+" holding,"+sendLock.getQueueLength()+" queue length");
            });
        }
        logger.trace("leavin' sendMsg...");
    }

    private Message readMessage(ZMQ.Socket socket) {
        ZMsg zmsg = null;
        Message message = null;
        try {
            zmsg = ZMsg.recvMsg(socket);
            ZFrame[] parts = new ZFrame[zmsg.size()];
            zmsg.toArray(parts);
            byte[] uuid = parts[MessageParts.UUID].getData();
            byte[] header = parts[MessageParts.HEADER].getData();
            byte[] parent = parts[MessageParts.PARENT].getData();
            byte[] metadata = parts[MessageParts.METADATA].getData();
            byte[] content = parts[MessageParts.CONTENT].getData();
            byte[] expectedSig = parts[MessageParts.HMAC].getData();

            verifyDelim(parts[MessageParts.DELIM]);
            verifySignatures(expectedSig, header, parent, metadata, content);

            message = new Message(parse(header, Header.class));
            if (uuid != null) {
                message.getIdentities().add(uuid);
            }
            message.setParentHeader(parse(parent, Header.class));
            message.setMetadata(parse(metadata, LinkedHashMap.class));
            message.setContent(parse(content, LinkedHashMap.class));

        } finally {
            if (zmsg != null) {
                zmsg.destroy();
            }
        }

        logger.debug("readMessage="+message.getContent());
        return message;
    }

    @Override
    public void run() {
        try {
            while (!this.isShutdown()) {
                sockets.poll();
                if (isControlMsg()) {
                    handleControlMsg();
                } else if (isHeartbeatMsg()) {
                    handleHeartbeat();
                } else if (isShellMsg()) {
                    handleShell();
                } else if (isStdinMsg()) {
                    handleStdIn();
                } else if (this.isShutdown()) {
                    break;
                } else {
                    logger.error("not handled message from sockets");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } catch (Error e) {
            logger.error(e.toString());
        } finally {
            close();
        }
    }

    private String handleStdIn() {
        Message msg = readMessage(stdinSocket);
        return (String) msg.getContent().get("value");
    }

    private void handleShell() {
        Message message = readMessage(shellSocket);
        Handler<Message> handler = kernel.getHandler(message.type());
        if (handler != null) {
            handler.handle(message);
        }
    }

    private void handleHeartbeat() {
        byte[] buffer = hearbeatSocket.recv(0);
        hearbeatSocket.send(buffer);
    }

    private void handleControlMsg() {
        Message message = readMessage(controlSocket);
        logger.trace("handleControlMsg: "+message.toString());
        JupyterMessages type = message.getHeader().getTypeEnum();
        if (type.equals(SHUTDOWN_REQUEST)) {
            Message reply = new Message(new Header(SHUTDOWN_REPLY, message.getHeader().getSession()));
            reply.setParentHeader(message.getHeader());
            reply.setContent(message.getContent());
            sendMsg(controlSocket, Collections.singletonList(reply));
            shutdown();
        }
        Handler<Message> handler = kernel.getHandler(message.type());
        if (handler != null) {
            handler.handle(message);
        }
        logger.trace("leavin' handleControlMsg()");
  }

    private ZMQ.Socket getNewSocket(int type, int port, String connection, ZMQ.Context context) {
        ZMQ.Socket socket = context.socket(type);
        socket.bind(connection + ":" + String.valueOf(port));
        return socket;
    }

    private void close() {
        closeAction.close();
        closeSockets();
    }

    private void closeSockets() {
        try {
            if (shellSocket != null) {
                shellSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            if (iopubSocket != null) {
                iopubSocket.close();
            }
            if (stdinSocket != null) {
                stdinSocket.close();
            }
            if (hearbeatSocket != null) {
                hearbeatSocket.close();
            }
            context.close();
        } catch (Exception e) {
        }
    }

    private void verifySignatures(byte[] expectedSig, byte[] header, byte[] parent, byte[] metadata, byte[] content) {
        String actualSig = hmac.signBytes(new ArrayList<>(asList(header, parent, metadata, content)));
        String expectedSigAsString = new String(expectedSig, StandardCharsets.UTF_8);
        if (!expectedSigAsString.equals(actualSig)) {
            throw new RuntimeException("Signatures do not match.");
        }
    }

    private String verifyDelim(ZFrame zframe) {
        String delim = new String(zframe.getData(), StandardCharsets.UTF_8);
        if (!DELIM.equals(delim)) {
            throw new RuntimeException("Delimiter <IDS|MSG> not found");
        }
        return delim;
    }

    private boolean isStdinMsg() {
        return sockets.pollin(3);
    }

    private boolean isHeartbeatMsg() {
        return sockets.pollin(0);
    }

    private boolean isShellMsg() {
        return sockets.pollin(1);
    }

    private boolean isControlMsg() {
        return sockets.pollin(2);
    }

    public void shutdown() {
        logger.debug("kernel shutdown");
        this.shutdownSystem = true;
    }

    private boolean isShutdown() {
        return this.shutdownSystem;
    }

    private <T> T parse(byte[] bytes, Class<T> theClass) {
        return bytes != null ? MessageSerializer.parse(new String(bytes, StandardCharsets.UTF_8), theClass) : null;
    }
}

/*
 * End BeakerX implementation.
 */
