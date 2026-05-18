package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Extended PeerClient — handles direct messages, group multicast,
 * coordinator requests with fallback + idempotency, and broadcast.
 */
public class PeerClient {

    private static final Logger logger = LoggerUtil.getLogger(PeerClient.class.getName());
    private final PeerManager peerManager;

    public PeerClient(PeerManager peerManager) {
        this.peerManager = peerManager;
    }

    // ==================== Direct Message ====================

    public boolean sendDirectMessage(String sender, String receiver, String content) {
        var peer = peerManager.getPeer(receiver);
        if (peer == null) {
            logger.warning("Peer not found: " + receiver);
            return false;
        }
        Message message = ProtocolHandler.createDirectMessage(sender, receiver, content);
        peerManager.getMessageRepository().saveMessage(message);
        // Update recent peers cache
        peerManager.getRecentPeersCache().upsert(receiver, peer.getAddress());
        boolean sent = sendWithRetry(message, peer.getHost(), peer.getPort());
        if (!sent) storeOfflineMessage(message, peer.getUsername());
        return sent;
    }

    // ==================== Group Message (Data Plane) ====================

    public boolean sendGroupMessage(String sender, String groupId, String content) {
        GroupCache.GroupCacheEntry cache = peerManager.getGroupCache().get(groupId);
        if (cache == null) {
            logger.warning("Group cache not found: " + groupId);
            return false;
        }
        if ("LEAVING".equals(cache.getGroupState())) {
            logger.warning("Cannot send to group in LEAVING state: " + groupId);
            return false;
        }

        long clock = peerManager.getLamportClock().tick();
        Message message = Message.builder()
                .type(MessageType.GROUP_MESSAGE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(sender)
                .groupId(groupId)
                .groupName(cache.getGroupName())
                .content(content)
                .lamportClock(clock)
                .cacheVersion(cache.getLocalVersion())
                .timestamp(System.currentTimeMillis())
                .build();

        peerManager.getMessageRepository().saveMessage(message);

        boolean allSent = true;
        String myAddress = peerManager.getLocalAddress();
        for (String memberAddress : cache.getMembers()) {
            if (memberAddress.equals(myAddress)) continue;
            String[] parts = memberAddress.split(":");
            if (parts.length != 2) continue;
            try {
                if (!sendSingle(message, parts[0], Integer.parseInt(parts[1]))) {
                    allSent = false;
                    // Trigger lazy repair (TCP fail)
                    peerManager.getLazyRepairManager().repair(groupId);
                }
            } catch (Exception e) {
                allSent = false;
            }
        }
        return allSent;
    }

    // ==================== Coordinator Requests with Fallback ====================

    /**
     * Send a request to coordinator C1→C2→C3 with hard cancel + idempotency.
     */
    public Message sendToCoordinator(String groupId, Message request) {
        GroupCache.GroupCacheEntry cache = peerManager.getGroupCache().get(groupId);
        if (cache == null) {
            return createError("Group cache not found: " + groupId);
        }

        // Assign idempotency key if not already set
        if (request.getRequestId() == null) {
            request.setRequestId(UUID.randomUUID().toString());
        }

        List<String> coords = cache.getCoordinators();
        for (String coord : coords) {
            if (coord.equals(peerManager.getLocalAddress())) continue;
            String[] parts = coord.split(":");
            if (parts.length != 2) continue;
            try (Socket socket = new Socket(parts[0], Integer.parseInt(parts[1]))) {
                socket.setSoTimeout(Constants.COORDINATOR_TIMEOUT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                out.println(JsonUtil.toJson(request));
                out.flush();

                String resp = in.readLine();
                if (resp != null) {
                    return JsonUtil.fromJson(resp.trim());
                }
            } catch (Exception e) {
                logger.warning("Coordinator " + coord + " timeout/error — trying next");
            }
        }
        return createError("All coordinators unreachable for group: " + groupId);
    }

    // ==================== Broadcast ====================

    public void sendSignal(String host, int port, String type, String groupId, String dummy) {
        Message message = Message.builder()
                .type(type)
                .sender(peerManager.getLocalUsername())
                .groupId(groupId != null && !groupId.isEmpty() ? groupId : null)
                .timestamp(System.currentTimeMillis())
                .build();
        sendSingle(message, host, port);
    }

    public void sendBroadcast(String sender, String content) {
        Message message = ProtocolHandler.createBroadcast(sender, content);
        peerManager.getMessageRepository().saveMessage(message);
        for (var peer : peerManager.getOnlinePeers()) {
            sendSingle(message, peer.getHost(), peer.getPort());
        }
    }

    // ==================== TCP Helpers ====================

    private boolean sendWithRetry(Message message, String host, int port) {
        for (int attempt = 1; attempt <= Constants.MAX_RETRIES; attempt++) {
            if (sendSingle(message, host, port)) return true;
            logger.warning("Retry " + attempt + "/" + Constants.MAX_RETRIES + " for " + host + ":" + port);
            try { TimeUnit.MILLISECONDS.sleep(500); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private boolean sendSingle(Message message, String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(Constants.ACK_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(JsonUtil.toJson(message));
            out.flush();
            String resp = in.readLine();
            return resp != null && resp.contains("ACK");
        } catch (IOException e) {
            logger.fine("Send failed to " + host + ":" + port + ": " + e.getMessage());
            return false;
        }
    }

    private void storeOfflineMessage(Message message, String receiverUsername) {
        if (!peerManager.isRegisteredToBootstrap()) return;
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message storeMsg = ProtocolHandler.createStoreMessage(
                    message.getSender(), receiverUsername, message.getContent());
            out.println(JsonUtil.toJson(storeMsg));
            out.flush();
        } catch (IOException e) {
            logger.warning("Failed to store offline message: " + e.getMessage());
        }
    }

    private Message createError(String msg) {
        return Message.builder().type(MessageType.ERROR.name()).content(msg).build();
    }

    public void shutdown() {}
}
