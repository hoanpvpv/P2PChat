package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class PeerServer {

    private static final Logger logger = LoggerUtil.getLogger(PeerServer.class.getName());
    private final int port;
    private final PeerManager peerManager;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private final String localUsername;
    private WebServer webServer;

    public PeerServer(int port, PeerManager peerManager, String localUsername) {
        this.port = port;
        this.peerManager = peerManager;
        this.localUsername = localUsername;
    }

    public void setWebServer(WebServer webServer) {
        this.webServer = webServer;
    }

    public void start() {
        running = true;
        try {
            serverSocket = new ServerSocket(port);
            logger.info("PeerServer started on port " + port);

            threadPool.execute(() -> {
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.execute(() -> handleIncomingConnection(clientSocket));
                    } catch (IOException e) {
                        if (running) {
                            logger.severe("Error accepting peer connection: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (IOException e) {
            logger.severe("Failed to start PeerServer: " + e.getMessage());
        }
    }

    private void handleIncomingConnection(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                Message message = JsonUtil.fromJson(line.trim());
                processMessage(message, socket);
            }
        } catch (IOException e) {
            logger.fine("Peer connection closed: " + socket.getRemoteSocketAddress());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void processMessage(Message message, Socket socket) {
        String type = message.getType();
        logger.info("Received [" + type + "] from " + message.getSender());

        try {
            switch (MessageType.valueOf(type)) {
                case DIRECT_MESSAGE:
                    receiveDirectMessage(message);
                    sendAck(message, socket);
                    break;
                case GROUP_MESSAGE:
                    receiveGroupMessage(message);
                    sendAck(message, socket);
                    break;
                case BROADCAST:
                    receiveBroadcast(message);
                    sendAck(message, socket);
                    break;
                case PEER_JOIN:
                    handlePeerJoin(message);
                    break;
                case PEER_LEAVE:
                    handlePeerLeave(message);
                    break;
                case ACK:
                    logger.fine("Received ACK for message: " + message.getContent());
                    break;
                case OFFLINE_MESSAGE:
                    receiveOfflineMessage(message);
                    break;
                default:
                    logger.warning("Unhandled message type: " + type);
            }
        } catch (Exception e) {
            logger.severe("Error processing message: " + e.getMessage());
        }
    }

    private void receiveDirectMessage(Message message) {
        String time = com.mycompany.p2pchat.utils.TimeUtil.formatTimestamp(message.getTimestamp());
        System.out.println("\n[" + time + "] " + message.getSender() + " -> you: " + message.getContent());
        System.out.print("> ");
        peerManager.getMessageRepository().saveMessage(message);
        if (webServer != null) {
            webServer.broadcastToWeb("DIRECT_MESSAGE", messageToMap(message));
        }
    }

    private void receiveGroupMessage(Message message) {
        String time = com.mycompany.p2pchat.utils.TimeUtil.formatTimestamp(message.getTimestamp());
        System.out.println("\n[" + time + "] [" + message.getGroupName() + "] " + message.getSender() + ": " + message.getContent());
        System.out.print("> ");
        peerManager.getMessageRepository().saveMessage(message);
        if (webServer != null) {
            webServer.broadcastToWeb("GROUP_MESSAGE", messageToMap(message));
        }
    }

    private void receiveBroadcast(Message message) {
        String time = com.mycompany.p2pchat.utils.TimeUtil.formatTimestamp(message.getTimestamp());
        System.out.println("\n[" + time + "] [BROADCAST] " + message.getSender() + ": " + message.getContent());
        System.out.print("> ");
        peerManager.getMessageRepository().saveMessage(message);
        if (webServer != null) {
            webServer.broadcastToWeb("BROADCAST", messageToMap(message));
        }
    }

    private void receiveOfflineMessage(Message message) {
        String time = com.mycompany.p2pchat.utils.TimeUtil.formatTimestamp(message.getTimestamp());
        System.out.println("\n[" + time + "] [OFFLINE-MSG] " + message.getSender() + ": " + message.getContent());
        System.out.print("> ");
        peerManager.getMessageRepository().saveMessage(message);
        if (webServer != null) {
            webServer.broadcastToWeb("OFFLINE_MESSAGE", messageToMap(message));
        }
    }

    private void handlePeerJoin(Message message) {
        String[] parts = message.getContent().split(":");
        if (parts.length == 2) {
            PeerInfo peer = new PeerInfo(message.getSender(), parts[0], Integer.parseInt(parts[1]));
            peerManager.addKnownPeer(peer);
            System.out.println("\n[SYSTEM] " + message.getSender() + " joined the network.");
            System.out.print("> ");
            if (webServer != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("username", message.getSender());
                data.put("host", parts[0]);
                data.put("port", Integer.parseInt(parts[1]));
                webServer.broadcastToWeb("PEER_JOIN", data);
            }
        }
    }

    private void handlePeerLeave(Message message) {
        peerManager.removeKnownPeer(message.getSender());
        System.out.println("\n[SYSTEM] " + message.getSender() + " left the network.");
        System.out.print("> ");
        if (webServer != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("username", message.getSender());
            webServer.broadcastToWeb("PEER_LEAVE", data);
        }
    }

    private Map<String, Object> messageToMap(Message msg) {
        Map<String, Object> map = new HashMap<>();
        map.put("messageId", msg.getMessageId());
        map.put("sender", msg.getSender());
        map.put("receiver", msg.getReceiver());
        map.put("groupName", msg.getGroupName());
        map.put("content", msg.getContent());
        map.put("type", msg.getType());
        map.put("timestamp", msg.getTimestamp());
        return map;
    }

    private void sendAck(Message original, Socket socket) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message ack = ProtocolHandler.createAck(original.getMessageId(), localUsername);
            out.println(JsonUtil.toJson(ack));
            out.flush();
        } catch (IOException e) {
            logger.warning("Failed to send ACK: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            threadPool.shutdown();
        } catch (IOException e) {
            logger.severe("Error stopping PeerServer: " + e.getMessage());
        }
    }
}
