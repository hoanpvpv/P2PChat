package com.mycompany.p2pchat.bootstrap;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.InetAddress;
import java.util.List;
import java.util.logging.Logger;

public class ClientHandler implements Runnable {

    private static final Logger logger = LoggerUtil.getLogger(ClientHandler.class.getName());
    private final Socket socket;
    private final PeerRegistry registry;
    private final BootstrapServer bootstrapServer;
    private BufferedReader in;
    private PrintWriter out;

    public ClientHandler(Socket socket, PeerRegistry registry, BootstrapServer bootstrapServer) {
        this.socket = socket;
        this.registry = registry;
        this.bootstrapServer = bootstrapServer;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                Message message = com.mycompany.p2pchat.protocol.JsonUtil.fromJson(line.trim());
                handleMessage(message);
            }
        } catch (Exception e) {
            logger.fine("Client connection closed: " + socket.getRemoteSocketAddress());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void handleMessage(Message message) {
        String type = message.getType();
        logger.info("Received [" + type + "] from " + message.getSender());
        bootstrapServer.getEventLog().info("MESSAGE_" + type, message.getSender(), "Received " + type + " request");

        try {
            switch (MessageType.valueOf(type)) {
                case REGISTER:
                    handleRegister(message);
                    break;
                case HEARTBEAT:
                    handleHeartbeat(message);
                    break;
                case DISCOVER:
                    handleDiscover(message);
                    break;
                case RESOLVE_MAILBOX:
                    handleResolveMailbox();
                    break;
                case STORE_MESSAGE:
                    handleStoreMessage(message);
                    break;
                case PEER_LEAVE:
                    handleLeave(message);
                    break;
                default:
                    logger.warning("Unknown message type: " + type);
            }
        } catch (Exception e) {
            logger.severe("Error handling message: " + e.getMessage());
        }
    }

    private void handleRegister(Message message) {
        String[] keyParts = message.getContent().split("\\|", -1);
        String[] parts = keyParts[0].split(":");
        if (parts.length != 2) {
            bootstrapServer.getEventLog().warn("REGISTER_INVALID", message.getSender(), "Invalid register format");
            send(ProtocolHandler.createError("Invalid register format. Use host:port"));
            return;
        }

        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        String remoteHost = socket.getInetAddress().getHostAddress();
        if (shouldPreferRemoteHost(host, remoteHost)) {
            logger.warning("Overriding advertised host for " + message.getSender()
                    + ": " + host + " -> " + remoteHost);
            host = remoteHost;
        }
        PeerInfo peerInfo = new PeerInfo(message.getSender(), host, port);
        if (keyParts.length > 1 && !keyParts[1].isBlank()) peerInfo.setKeyId(keyParts[1]);
        if (keyParts.length > 2 && !keyParts[2].isBlank()) peerInfo.setPublicKey(keyParts[2]);

        boolean wasAlreadyKnown = registry.contains(peerInfo.getUsername());
        if (wasAlreadyKnown) bootstrapServer.getEventLog().warn("REGISTER_REPLACE", peerInfo.getUsername(), "Existing peer registration replaced");

        registry.register(peerInfo);
        bootstrapServer.getEventLog().info("REGISTER", peerInfo.getUsername(), host + ":" + port);

        String peerList = registry.getPeerListJson();
        send(ProtocolHandler.createRegisterAck(peerList));

        bootstrapServer.sendOfflineMessages(peerInfo.getUsername(), out);

        if (!wasAlreadyKnown) {
            bootstrapServer.broadcastPeerJoin(peerInfo);
        }

        logger.info("Peer registered: " + peerInfo.getUsername() + " -> " + host + ":" + port);
    }

    private boolean shouldPreferRemoteHost(String advertisedHost, String remoteHost) {
        if (advertisedHost == null || remoteHost == null || advertisedHost.isBlank() || remoteHost.isBlank()) {
            return false;
        }
        if (!isTailscaleIp(remoteHost)) {
            return false;
        }
        if (advertisedHost.equals(remoteHost)) {
            return false;
        }
        return isDockerBridgeIp(advertisedHost) || isTailscaleIp(advertisedHost);
    }

    private boolean isTailscaleIp(String value) {
        byte[] bytes = parseIpv4(value);
        if (bytes == null) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    private boolean isDockerBridgeIp(String value) {
        byte[] bytes = parseIpv4(value);
        if (bytes == null) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 172 && second >= 16 && second <= 31;
    }

    private byte[] parseIpv4(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            byte[] bytes = address.getAddress();
            return bytes.length == 4 ? bytes : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void handleHeartbeat(Message message) {
        if (!registry.contains(message.getSender())) {
            bootstrapServer.getEventLog().warn("HEARTBEAT_UNKNOWN", message.getSender(), "Peer must register again");
            send(Message.builder()
                    .type(MessageType.REGISTER_NACK.name())
                    .messageId(com.mycompany.p2pchat.protocol.ProtocolHandler.generateMessageId())
                    .sender("bootstrap")
                    .content("Peer is not registered")
                    .timestamp(System.currentTimeMillis())
                    .build());
            return;
        }
        PeerInfo peer = registry.getPeer(message.getSender());
        String remoteHost = socket.getInetAddress().getHostAddress();
        if (peer != null && shouldPreferRemoteHost(peer.getHost(), remoteHost)) {
            logger.warning("Overriding heartbeat host for " + message.getSender()
                    + ": " + peer.getHost() + " -> " + remoteHost);
            registry.updateAddress(message.getSender(), remoteHost, peer.getPort());
        } else {
            registry.updateHeartbeat(message.getSender());
        }
        bootstrapServer.getEventLog().info("HEARTBEAT", message.getSender(), "Heartbeat acknowledged");
        String peerList = registry.getPeerListJson();
        Message ack = ProtocolHandler.createHeartbeatAck();
        ack.setContent(peerList);
        send(ack);
    }

    private void handleDiscover(Message message) {
        bootstrapServer.getEventLog().info("DISCOVER", message.getSender(), "Peer list requested");
        String peerList = registry.getPeerListJson();
        send(ProtocolHandler.createPeerList(peerList));
    }

    private void handleResolveMailbox() {
        String endpoint = bootstrapServer.getMailboxHost() + ":" + bootstrapServer.getMailboxPort();
        Message response = Message.builder()
                .type(MessageType.RESOLVE_MAILBOX_ACK.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender("bootstrap")
                .content(endpoint)
                .timestamp(System.currentTimeMillis())
                .build();
        send(response);
    }

    private void handleStoreMessage(Message message) {
        registry.storeOfflineMessage(message.getReceiver(), message);
        bootstrapServer.getEventLog().info("STORE_MESSAGE", message.getReceiver(), "Stored offline message from " + message.getSender());
        send(ProtocolHandler.createAck(message.getMessageId(), "bootstrap"));
    }

    private void handleLeave(Message message) {
        String username = message.getSender();
        registry.unregister(username);
        bootstrapServer.broadcastPeerLeave(username);
        bootstrapServer.getEventLog().info("LEAVE", username, "Peer left gracefully");
        logger.info("Peer left: " + username);
    }

    private void send(Message message) {
        String json = com.mycompany.p2pchat.protocol.JsonUtil.toJson(message);
        out.println(json);
        out.flush();
    }
}
