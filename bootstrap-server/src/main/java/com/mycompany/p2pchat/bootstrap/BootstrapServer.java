package com.mycompany.p2pchat.bootstrap;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class BootstrapServer {

    private static final Logger logger = LoggerUtil.getLogger(BootstrapServer.class.getName());
    private final int port;
    private final PeerRegistry registry;
    private final BootstrapEventLog eventLog = new BootstrapEventLog();
    private ServerSocket serverSocket;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public BootstrapServer(int port) {
        this.port = port;
        this.registry = new PeerRegistry();
    }

    public void start() {
        running = true;
        try {
            serverSocket = new ServerSocket(port);
            logger.info("Bootstrap Server started on port " + port);
            eventLog.info("SERVER_START", "bootstrap", "Bootstrap server started on port " + port);
            System.out.println("=== Bootstrap Server running on port " + port + " ===");

            startDeadPeerDetector();

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(new ClientHandler(clientSocket, registry, this));
                } catch (IOException e) {
                    if (running) {
                        logger.severe("Error accepting connection: " + e.getMessage());
                        eventLog.error("ACCEPT_ERROR", "bootstrap", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.severe("Failed to start bootstrap server: " + e.getMessage());
            eventLog.error("SERVER_START_FAILED", "bootstrap", e.getMessage());
        }
    }

    private void startDeadPeerDetector() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            List<String> deadPeers = registry.checkDeadPeers(Constants.HEARTBEAT_TIMEOUT);
            for (String peer : deadPeers) {
                logger.warning("Removing dead peer: " + peer);
                eventLog.warn("HEARTBEAT_TIMEOUT", peer, "No heartbeat within " + Constants.HEARTBEAT_TIMEOUT + " ms");
                registry.removeDeadPeer(peer);
                eventLog.info("PEER_REMOVED", peer, "Removed dead peer from registry");
                broadcastPeerLeave(peer);
            }
        }, Constants.HEARTBEAT_INTERVAL, Constants.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void broadcastPeerJoin(PeerInfo newPeer) {
        Message joinMsg = ProtocolHandler.createPeerJoin(newPeer.getUsername(), newPeer.getHost(), newPeer.getPort());
        eventLog.info("PEER_JOIN", newPeer.getUsername(), newPeer.getHost() + ":" + newPeer.getPort());
        broadcastToAll(joinMsg, newPeer.getUsername());
    }

    public void broadcastPeerLeave(String username) {
        Message leaveMsg = ProtocolHandler.createPeerLeave(username);
        eventLog.info("PEER_LEAVE", username, "Broadcast leave event");
        broadcastToAll(leaveMsg, username);
    }

    private void broadcastToAll(Message message, String excludePeer) {
        for (PeerInfo peer : registry.getOnlinePeers()) {
            if (peer.getUsername().equals(excludePeer)) continue;
            try {
                Socket socket = new Socket(peer.getHost(), peer.getPort());
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println(JsonUtil.toJson(message));
                out.flush();
                socket.close();
            } catch (IOException e) {
                logger.warning("Failed to broadcast to " + peer.getUsername() + ": " + e.getMessage());
                eventLog.warn("BROADCAST_FAILED", peer.getUsername(), e.getMessage());
            }
        }
    }

    public void sendOfflineMessages(String username, PrintWriter out) {
        List<Message> messages = registry.getOfflineMessages(username);
        for (Message msg : messages) {
            Message offlineMsg = ProtocolHandler.createOfflineMessage(msg.getSender(), msg.getContent());
            String json = JsonUtil.toJson(offlineMsg);
            out.println(json);
        }
        out.flush();
        if (!messages.isEmpty()) {
            logger.info("Sent " + messages.size() + " offline messages to " + username);
            eventLog.info("OFFLINE_DELIVERY", username, "Delivered " + messages.size() + " offline messages");
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            threadPool.shutdown();
            eventLog.info("SERVER_STOP", "bootstrap", "Bootstrap server stopped");
        } catch (IOException e) {
            logger.severe("Error stopping server: " + e.getMessage());
            eventLog.error("SERVER_STOP_ERROR", "bootstrap", e.getMessage());
        }
    }

    public PeerRegistry getRegistry() {
        return registry;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public BootstrapEventLog getEventLog() {
        return eventLog;
    }
}
