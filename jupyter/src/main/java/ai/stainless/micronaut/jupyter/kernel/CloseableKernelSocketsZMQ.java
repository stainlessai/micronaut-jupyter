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

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static com.twosigma.beakerx.kernel.msg.JupyterMessages.SHUTDOWN_REPLY;
import static com.twosigma.beakerx.kernel.msg.JupyterMessages.SHUTDOWN_REQUEST;
import static com.twosigma.beakerx.message.MessageSerializer.toJson;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

public class CloseableKernelSocketsZMQ extends KernelSockets {

    public static final Logger logger = LoggerFactory.getLogger(CloseableKernelSocketsZMQ.class);

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

    public CloseableKernelSocketsZMQ(KernelFunctionality kernel, Config configuration, SocketCloseAction closeAction) {
        logger.debug("Initializing CloseableKernelSocketsZMQ with config: transport={}, host={}", 
                    configuration.getTransport(), configuration.getHost());
        this.closeAction = closeAction;
        this.kernel = kernel;
        this.hmac = new HashedMessageAuthenticationCode(configuration.getKey());
        this.context = ZMQ.context(1);
        this.sendLock = new ReentrantLock();
        logger.debug("Created ZMQ context and locks, configuring sockets...");
        configureSockets(configuration);
        logger.debug("CloseableKernelSocketsZMQ initialization complete");
    }

    private void configureSockets(Config configuration) {
        // Check for environment variable override for bind host
        String bindHost = System.getenv("JUPYTER_KERNEL_BIND_HOST");
        if (bindHost == null || bindHost.trim().isEmpty()) {
            bindHost = configuration.getHost();
        }
        
        final String connection = configuration.getTransport() + "://" + bindHost;
        logger.debug("Configuring sockets with connection string: {} (bind host: {}, config host: {})", 
                    connection, bindHost, configuration.getHost());
        logger.debug("Socket ports - iopub: {}, heartbeat: {}, control: {}, stdin: {}, shell: {}",
                    configuration.getIopub(), configuration.getHeartbeat(), configuration.getControl(),
                    configuration.getStdin(), configuration.getShell());

        iopubSocket = getNewSocket(ZMQ.PUB, configuration.getIopub(), connection, context);
        logger.trace("Created iopub socket on port {}", configuration.getIopub());
        
        hearbeatSocket = getNewSocket(ZMQ.ROUTER, configuration.getHeartbeat(), connection, context);
        logger.trace("Created heartbeat socket on port {}", configuration.getHeartbeat());
        
        controlSocket = getNewSocket(ZMQ.ROUTER, configuration.getControl(), connection, context);
        logger.trace("Created control socket on port {}", configuration.getControl());
        
        stdinSocket = getNewSocket(ZMQ.ROUTER, configuration.getStdin(), connection, context);
        logger.trace("Created stdin socket on port {}", configuration.getStdin());
        
        shellSocket = getNewSocket(ZMQ.ROUTER, configuration.getShell(), connection, context);
        logger.trace("Created shell socket on port {}", configuration.getShell());

        sockets = new ZMQ.Poller(3);
        sockets.register(hearbeatSocket, ZMQ.Poller.POLLIN);
        sockets.register(shellSocket, ZMQ.Poller.POLLIN);
        sockets.register(controlSocket, ZMQ.Poller.POLLIN);
        logger.debug("Registered {} sockets with poller", 3);
    }

    public void publish(List<Message> message) {
        logger.trace("Publishing {} messages to iopub socket", message.size());
        sendMsg(this.iopubSocket, message);
    }

    public void send(Message message) {
        logger.trace("Sending message to shell socket: type={}, session={}", 
                    message.getHeader().getType(), message.getHeader().getSession());
        sendMsg(this.shellSocket, singletonList(message));
    }

    public String sendStdIn(Message message) {
        logger.trace("Sending stdin message and waiting for response: type={}", message.getHeader().getType());
        sendMsg(this.stdinSocket, singletonList(message));
        String response = handleStdIn();
        logger.trace("Received stdin response: {}", response);
        return response;
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
        logger.trace("Reading message from socket");
        ZMsg zmsg = null;
        Message message = null;
        try {
            zmsg = ZMsg.recvMsg(socket);
            logger.trace("Received ZMsg with {} parts", zmsg.size());
            
            ZFrame[] parts = new ZFrame[zmsg.size()];
            zmsg.toArray(parts);
            byte[] uuid = parts[MessageParts.UUID].getData();
            byte[] header = parts[MessageParts.HEADER].getData();
            byte[] parent = parts[MessageParts.PARENT].getData();
            byte[] metadata = parts[MessageParts.METADATA].getData();
            byte[] content = parts[MessageParts.CONTENT].getData();
            byte[] expectedSig = parts[MessageParts.HMAC].getData();

            logger.trace("Message parts - UUID length: {}, header length: {}, parent length: {}, metadata length: {}, content length: {}",
                        uuid != null ? uuid.length : 0, header.length, parent.length, metadata.length, content.length);

            verifyDelim(parts[MessageParts.DELIM]);
            logger.trace("Delimiter verified successfully");
            
            verifySignatures(expectedSig, header, parent, metadata, content);
            logger.trace("Message signatures verified successfully");

            message = new Message(parse(header, Header.class));
            if (uuid != null) {
                message.getIdentities().add(uuid);
                logger.trace("Added UUID to message identities");
            }
            message.setParentHeader(parse(parent, Header.class));
            message.setMetadata(parse(metadata, LinkedHashMap.class));
            message.setContent(parse(content, LinkedHashMap.class));
            
            logger.debug("Successfully parsed message: type={}, session={}", 
                        message.getHeader().getType(), message.getHeader().getSession());

        } catch (Exception e) {
            logger.error("Error reading message from socket", e);
            throw e;
        } finally {
            if (zmsg != null) {
                zmsg.destroy();
            }
        }

        logger.debug("readMessage content: {}", message.getContent());
        return message;
    }

    @Override
    public void run() {
        logger.debug("Starting CloseableKernelSocketsZMQ message loop");
        try {
            while (!this.isShutdown()) {
                logger.trace("Polling sockets for messages...");
                sockets.poll();
                
                if (isControlMsg()) {
                    logger.trace("Received control message");
                    handleControlMsg();
                } else if (isHeartbeatMsg()) {
                    logger.trace("Received heartbeat message");
                    handleHeartbeat();
                } else if (isShellMsg()) {
                    logger.trace("Received shell message");
                    handleShell();
                } else if (isStdinMsg()) {
                    logger.trace("Received stdin message");
                    handleStdIn();
                } else if (this.isShutdown()) {
                    logger.debug("Shutdown detected, breaking message loop");
                    break;
                } else {
                    logger.error("Unhandled message from sockets - no socket had data ready");
                }
            }
            logger.debug("Message loop completed normally");
        } catch (Exception e) {
            logger.error("Exception in message loop", e);
            throw new RuntimeException(e);
        } catch (Error e) {
            logger.error("Error in message loop: {}", e.toString(), e);
        } finally {
            logger.debug("Closing sockets and cleaning up");
            close();
        }
    }

    private String handleStdIn() {
        logger.trace("Handling stdin message");
        Message msg = readMessage(stdinSocket);
        logger.debug("Stdin message received: type={}, content keys: {}", 
                    msg.getHeader().getType(), msg.getContent().keySet());
        String value = (String) msg.getContent().get("value");
        logger.trace("Extracted stdin value: {}", value);
        return value;
    }

    private void handleShell() {
        logger.trace("Handling shell message");
        Message message = readMessage(shellSocket);
        logger.debug("Shell message received: type={}, session={}", 
                    message.getHeader().getType(), message.getHeader().getSession());
        
        Handler<Message> handler = kernel.getHandler(message.type());
        if (handler != null) {
            logger.trace("Found handler for message type: {}", message.type());
            handler.handle(message);
            logger.trace("Handler completed for message type: {}", message.type());
        } else {
            logger.warn("No handler found for shell message type: {}", message.type());
        }
    }

    private void handleHeartbeat() {
        logger.trace("Handling heartbeat message");
        byte[] buffer = hearbeatSocket.recv(0);
        logger.trace("Received heartbeat data, length: {}", buffer != null ? buffer.length : 0);
        hearbeatSocket.send(buffer);
        logger.trace("Echoed heartbeat response");
    }

    private void handleControlMsg() {
        Message message = readMessage(controlSocket);
        logger.trace("handleControlMsg: "+message.toString());
        JupyterMessages type = message.getHeader().getTypeEnum();
        if (type.equals(SHUTDOWN_REQUEST)) {
            // Parse restart flag from message content per Jupyter protocol
            Map<String, Serializable> content = message.getContent();
            boolean restart = content != null && Boolean.TRUE.equals(content.get("restart"));
            
            logger.info("Received shutdown_request with restart={}", restart);
            
            // Create reply with same restart flag per Jupyter specification
            Message reply = new Message(new Header(SHUTDOWN_REPLY, message.getHeader().getSession()));
            reply.setParentHeader(message.getHeader());
            
            // Mirror the restart flag in reply content
            Map<String, Serializable> replyContent = new HashMap<>();
            replyContent.put("status", "ok");
            replyContent.put("restart", restart);
            reply.setContent(replyContent);
            
            sendMsg(controlSocket, Collections.singletonList(reply));
            
            if (restart) {
                // Handle restart case - restart only this kernel's executor
                logger.info("Handling restart request for specific kernel");
                restartKernelExecutor();
            } else {
                // Handle final shutdown case
                logger.info("Handling final shutdown request");
                shutdown();
            }
        }
        Handler<Message> handler = kernel.getHandler(message.type());
        if (handler != null) {
            handler.handle(message);
        }
        logger.trace("leavin' handleControlMsg()");
  }

    /**
     * Restart only this kernel's executor, not all kernels
     * This coordinates with KernelManager to restart the specific kernel
     */
    private void restartKernelExecutor() {
        try {
            // Get kernel ID from connection file or kernel instance
            String kernelId = getKernelIdFromConnection();
            if (kernelId != null) {
                // TODO: Need to coordinate with KernelManager to restart specific kernel
                // For now, log the intent - full implementation requires KernelManager integration
                logger.info("Should restart kernel '{}' executor only, not affecting other users", kernelId);
                
                // This will need to call something like:
                // kernelManager.restartKernel(kernelId);
                
                // For now, just shutdown this specific socket connection
                // The kernel will need to be restarted by the client
                shutdown();
            } else {
                logger.warn("Could not determine kernel ID for restart, falling back to shutdown");
                shutdown();
            }
        } catch (Exception e) {
            logger.error("Error during kernel restart, falling back to shutdown", e);
            shutdown();
        }
    }

    /**
     * Extract kernel ID from connection information
     * This needs to be implemented based on how kernel ID is passed to this socket handler
     */
    private String getKernelIdFromConnection() {
        // TODO: Implementation depends on how kernel ID is made available to socket handler
        // Options:
        // 1. Pass kernel ID during socket creation
        // 2. Extract from connection config
        // 3. Use session ID as kernel identifier
        
        // For now, return null to indicate inability to determine kernel ID
        logger.debug("Kernel ID extraction not yet implemented - needs integration with KernelManager");
        return null;
    }

    private ZMQ.Socket getNewSocket(int type, int port, String connection, ZMQ.Context context) {
        String socketTypeStr = getSocketTypeString(type);
        String bindAddress = connection + ":" + String.valueOf(port);
        logger.debug("Creating {} socket and binding to {}", socketTypeStr, bindAddress);
        
        ZMQ.Socket socket = context.socket(type);
        try {
            socket.bind(bindAddress);
            logger.trace("Successfully bound {} socket to {}", socketTypeStr, bindAddress);
        } catch (Exception e) {
            logger.error("Failed to bind {} socket to {}", socketTypeStr, bindAddress, e);
            throw e;
        }
        return socket;
    }
    
    private String getSocketTypeString(int type) {
        switch (type) {
            case ZMQ.PUB: return "PUB";
            case ZMQ.ROUTER: return "ROUTER";
            case ZMQ.SUB: return "SUB";
            case ZMQ.DEALER: return "DEALER";
            case ZMQ.REQ: return "REQ";
            case ZMQ.REP: return "REP";
            default: return "UNKNOWN(" + type + ")";
        }
    }

    private void close() {
        logger.debug("Closing CloseableKernelSocketsZMQ");
        try {
            closeAction.close();
            logger.trace("Close action completed");
        } catch (Exception e) {
            logger.error("Error during close action", e);
        }
        closeSockets();
        logger.debug("CloseableKernelSocketsZMQ closed");
    }

    private void closeSockets() {
        logger.debug("Closing all ZMQ sockets");
        try {
            if (shellSocket != null) {
                shellSocket.close();
                logger.trace("Closed shell socket");
            }
            if (controlSocket != null) {
                controlSocket.close();
                logger.trace("Closed control socket");
            }
            if (iopubSocket != null) {
                iopubSocket.close();
                logger.trace("Closed iopub socket");
            }
            if (stdinSocket != null) {
                stdinSocket.close();
                logger.trace("Closed stdin socket");
            }
            if (hearbeatSocket != null) {
                hearbeatSocket.close();
                logger.trace("Closed heartbeat socket");
            }
            context.close();
            logger.debug("Closed ZMQ context");
        } catch (Exception e) {
            logger.error("Error closing sockets", e);
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
        logger.debug("Initiating kernel shutdown");
        this.shutdownSystem = true;
        logger.trace("Shutdown flag set to true");
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
