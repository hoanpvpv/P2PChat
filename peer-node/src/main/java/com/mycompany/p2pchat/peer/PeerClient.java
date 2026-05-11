package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class PeerClient {

    private static final Logger logger = LoggerUtil.getLogger(PeerClient.class.getName());
    private final PeerManager peerManager;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<String, CompletableFuture<Boolean>> pendingAcks = new ConcurrentHashMap<>();

    public PeerClient(PeerManager peerManager) {
        this.peerManager = peerManager;
    }

    public boolean sendDirectMessage(String sender, String receiver, String content) {
        PeerInfo peer = peerManager.getPeer(receiver);
        if (peer == null) {
            System.out.println("[ERROR] Peer not found: " + receiver);
            return false;
        }

        Message message = ProtocolHandler.createDirectMessage(sender, receiver, content);
        peerManager.getMessageRepository().saveMessage(message);
        return sendWithRetry(message, peer);
    }

    public boolean sendGroupMessage(String sender, String groupName, String content) {
        var group = peerManager.getChatGroup(groupName);
        if (group == null) {
            System.out.println("[ERROR] Group not found: " + groupName);
            return false;
        }

        Message message = ProtocolHandler.createGroupMessage(sender, groupName, content);
        boolean allSent = true;
        for (String member : group.getMembers()) {
            if (member.equals(sender)) continue;
            PeerInfo peer = peerManager.getPeer(member);
            if (peer != null && peer.isOnline()) {
                if (!sendWithRetry(message, peer)) {
                    allSent = false;
                    System.out.println("[WARN] Failed to send to " + member);
                }
            }
        }
        peerManager.getMessageRepository().saveMessage(message);
        return allSent;
    }

    public void sendBroadcast(String sender, String content) {
        Message message = ProtocolHandler.createBroadcast(sender, content);
        peerManager.getMessageRepository().saveMessage(message);
        for (PeerInfo peer : peerManager.getOnlinePeers()) {
            sendSingle(message, peer);
        }
        System.out.println("[BROADCAST] Message sent to all peers.");
    }

    private boolean sendWithRetry(Message message, PeerInfo peer) {
        for (int attempt = 1; attempt <= Constants.MAX_RETRIES; attempt++) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pendingAcks.put(message.getMessageId(), future);

            if (sendSingle(message, peer)) {
                try {
                    Boolean acked = future.get(Constants.ACK_TIMEOUT, TimeUnit.MILLISECONDS);
                    if (Boolean.TRUE.equals(acked)) {
                        return true;
                    }
                } catch (TimeoutException e) {
                    logger.warning("ACK timeout (attempt " + attempt + "/" + Constants.MAX_RETRIES + ") for " + peer.getUsername());
                } catch (Exception e) {
                    logger.warning("Error waiting for ACK: " + e.getMessage());
                }
            }

            pendingAcks.remove(message.getMessageId());
        }

        System.out.println("[WARN] Failed to deliver message to " + peer.getUsername() + " after " + Constants.MAX_RETRIES + " attempts. Storing offline.");
        storeOfflineMessage(message, peer);
        return false;
    }

    private boolean sendSingle(Message message, PeerInfo peer) {
        try (Socket socket = new Socket(peer.getHost(), peer.getPort())) {
            socket.setSoTimeout(Constants.ACK_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(JsonUtil.toJson(message));
            out.flush();

            String responseLine = in.readLine();
            if (responseLine != null) {
                Message response = JsonUtil.fromJson(responseLine.trim());
                if (response != null && "ACK".equals(response.getType())) {
                    handleAck(response.getContent());
                }
            }
            return true;
        } catch (IOException e) {
            logger.warning("Failed to send to " + peer.getUsername() + ": " + e.getMessage());
            return false;
        }
    }

    public void handleAck(String originalMessageId) {
        CompletableFuture<Boolean> future = pendingAcks.remove(originalMessageId);
        if (future != null) {
            future.complete(true);
        }
    }

    private void storeOfflineMessage(Message message, PeerInfo peer) {
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message storeMsg = ProtocolHandler.createStoreMessage(message.getSender(), peer.getUsername(), message.getContent());
            out.println(JsonUtil.toJson(storeMsg));
            out.flush();

            String line = in.readLine();
            if (line != null) {
                logger.info("Offline message stored on bootstrap for " + peer.getUsername());
            }
        } catch (IOException e) {
            logger.warning("Failed to store offline message: " + e.getMessage());
        }
    }

    public void shutdown() {
        retryExecutor.shutdown();
    }
}
