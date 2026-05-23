package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;
import com.mycompany.p2pchat.model.PeerInfo;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
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
    private final MailboxClient mailboxClient;
    private final E2EECrypto e2eeCrypto;
    private int consecutiveMailboxFailures = 0;
    private long mailboxCircuitOpenUntil = 0;

    public PeerClient(PeerManager peerManager) {
        this.peerManager = peerManager;
        this.mailboxClient = new MailboxClient(peerManager);
        this.e2eeCrypto = E2EECrypto.loadOrCreate();
    }

    // ==================== Direct Message ====================

    public boolean sendDirectMessage(String sender, String receiver, String content) {
        return "DELIVERED_DIRECT".equals(sendDirectMessageStatus(sender, receiver, content));
    }

    public String sendDirectMessageStatus(String sender, String receiver, String content) {
        return sendDirectMessageDetailed(sender, receiver, content).status;
    }

    public static class SendResult {
        public final String messageId;
        public final long timestamp;
        public final String status;
        public SendResult(String messageId, long timestamp, String status) {
            this.messageId = messageId;
            this.timestamp = timestamp;
            this.status = status;
        }
    }

    public SendResult sendDirectMessageDetailed(String sender, String receiver, String content) {
        Message message = ProtocolHandler.createDirectMessage(sender, receiver, content);
        String payloadJson = mailboxClient.payloadJson(message);
        String payloadHash = mailboxClient.payloadHash(payloadJson);
        peerManager.getOutboxRepository().savePending(message.getMessageId(), receiver, payloadJson, payloadHash);
        peerManager.getMessageRepository().saveMessage(message);

        String status;
        var peer = peerManager.getPeer(receiver);
        if (peer == null || !peer.isOnline()) {
            logger.warning("Peer not online, storing in mailbox: " + receiver);
            status = storeMailbox(message, payloadJson, payloadHash) ? "STORED_MAILBOX" : "QUEUED_LOCAL";
        } else {
            peerManager.getRecentPeersCache().upsert(receiver, peer.getAddress());
            Message wireMessage = encryptDirectMessage(message, peer);
            peerManager.getOutboxRepository().markDirectInFlight(message.getMessageId());
            boolean sent = wireMessage != null && sendWithRetry(wireMessage, peer.getHost(), peer.getPort());
            if (sent) {
                peerManager.getOutboxRepository().markDelivered(message.getMessageId());
                status = "DELIVERED_DIRECT";
            } else {
                peerManager.removeKnownPeer(receiver);
                peerManager.getOutboxRepository().markDirectRetryable(message.getMessageId(), "Direct send failed");
                status = storeMailbox(message, payloadJson, payloadHash) ? "STORED_MAILBOX" : "QUEUED_LOCAL";
            }
        }
        notifyOutboxState(message.getMessageId(), status, receiver);
        return new SendResult(message.getMessageId(), message.getTimestamp(), status);
    }

    private void notifyOutboxState(String messageId, String state, String receiver) {
        if (peerManager.getPeerServer() != null) {
            peerManager.getPeerServer().broadcastOutboxUpdate(messageId, state, receiver);
        }
    }

    public void resolveMailboxFromBootstrap() {
        mailboxClient.resolveMailboxFromBootstrap();
    }

    public void retryMailboxOutbox() {
        for (var entry : peerManager.getOutboxRepository().dueForMailboxRetry(25)) {
            try {
                Message message = JsonUtil.fromJson(entry.payloadJson);
                if (mailboxClient.store(message, entry.payloadJson, entry.payloadHash)) {
                    peerManager.getOutboxRepository().markStoredMailbox(
                            entry.messageId, peerManager.getMailboxHost(), peerManager.getMailboxPort());
                }
            } catch (Exception e) {
                peerManager.getOutboxRepository().markRetryable(entry.messageId, e.getMessage());
            }
        }
    }

    public void retryOutboxDelivery() {
        for (var entry : peerManager.getOutboxRepository().dueForDeliveryRetry(25)) {
            retryOutboxEntry(entry);
        }
    }

    public void retryOutboxDeliveryForReceiver(String receiver) {
        for (var entry : peerManager.getOutboxRepository().pendingForReceiver(receiver, 50)) {
            retryOutboxEntry(entry);
        }
    }

    private void retryOutboxEntry(com.mycompany.p2pchat.database.OutboxRepository.OutboxEntry entry) {
            Message message;
            try {
                message = JsonUtil.fromJson(entry.payloadJson);
            } catch (Exception e) {
                peerManager.getOutboxRepository().markRetryable(entry.messageId, e.getMessage());
                return;
            }
            if (message == null) {
                peerManager.getOutboxRepository().markRetryable(entry.messageId, "Invalid outbox payload");
                return;
            }

            PeerInfo receiver = peerManager.getPeer(entry.receiver);
            boolean triedDirect = receiver != null && receiver.isOnline();
            if (triedDirect) {
                peerManager.getRecentPeersCache().upsert(entry.receiver, receiver.getAddress());
                Message wireMessage = encryptDirectMessage(message, receiver);
                peerManager.getOutboxRepository().markDirectInFlight(entry.messageId);
                if (wireMessage != null && sendWithRetry(wireMessage, receiver.getHost(), receiver.getPort())) {
                    peerManager.getOutboxRepository().markDelivered(entry.messageId);
                    logger.info("Delivered outbox message directly after peer recovery: " + entry.messageId);
                    notifyOutboxState(entry.messageId, "DELIVERED_DIRECT", entry.receiver);
                    return;
                }
                peerManager.removeKnownPeer(entry.receiver);
                peerManager.getOutboxRepository().markDirectRetryable(entry.messageId, "Direct replay failed");
            }

            if (!"STORED_MAILBOX".equals(entry.state)) {
                boolean stored = storeMailbox(message, entry.payloadJson, entry.payloadHash);
                notifyOutboxState(entry.messageId, stored ? "STORED_MAILBOX" : "FAILED_RETRYABLE", entry.receiver);
            }
    }

    public List<Message> pullMailboxMessages() {
        List<Message> delivered = new ArrayList<>();
        try {
            for (MailboxEnvelope envelope : mailboxClient.pull(100)) {
                Message message = mailboxClient.decryptEnvelope(envelope);
                if (message == null || message.getMessageId() == null) continue;
                boolean alreadyHad = peerManager.getMessageRepository().messageExists(message.getMessageId());
                peerManager.getMessageRepository().saveMessage(message);
                mailboxClient.deliveryAck(message.getMessageId());
                if (!alreadyHad) delivered.add(message);
            }
        } catch (Exception e) {
            logger.fine("Mailbox pull failed: " + e.getMessage());
        }
        return delivered;
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
            if (coord.equals(peerManager.getLocalAddress())) {
                // Process locally if we are a coordinator
                if (peerManager.getCoordinatorManager().isManaging(groupId)) {
                    Message resp = null;
                    try {
                        switch (com.mycompany.p2pchat.protocol.MessageType.valueOf(request.getType())) {
                            case GROUP_ADD -> resp = peerManager.getCoordinatorManager().handleGroupAdd(request);
                            case GROUP_KICK -> resp = peerManager.getCoordinatorManager().handleGroupKick(request);
                            case GROUP_LEAVE -> resp = peerManager.getCoordinatorManager().handleGroupLeave(request);
                            case GROUP_DISBAND -> {
                                peerManager.getCoordinatorManager().handleGroupDisband(request);
                                resp = Message.builder().type(com.mycompany.p2pchat.protocol.MessageType.GROUP_DISBANDED.name()).build();
                            }
                        }
                        if (resp != null) {
                            // Synthesize GROUP_UPDATED processing locally to generate SYSTEM messages
                            if (com.mycompany.p2pchat.protocol.MessageType.GROUP_UPDATED.name().equals(resp.getType())) {
                                // Simulate receiving GROUP_UPDATED so PeerServer generates WS and SYSTEM message
                                peerManager.getPeerServer().processMessageFromLocal(resp);
                            }
                            return resp;
                        }
                    } catch (Exception e) {
                        logger.warning("Error processing locally: " + e.getMessage());
                    }
                }
                continue;
            }
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Constants.ACK_TIMEOUT);
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

    private Message encryptDirectMessage(Message plaintext, PeerInfo receiver) {
        if (receiver == null || receiver.getPublicKey() == null || receiver.getPublicKey().isBlank()
                || receiver.getKeyId() == null || receiver.getKeyId().isBlank()) {
            logger.warning("Missing E2EE public key for direct receiver: " + plaintext.getReceiver());
            return null;
        }
        Message encrypted = Message.builder()
                .type(plaintext.getType())
                .messageId(plaintext.getMessageId())
                .sender(plaintext.getSender())
                .receiver(plaintext.getReceiver())
                .content(e2eeCrypto.encryptFor(plaintext.getContent(), receiver.getPublicKey(), receiver.getKeyId()))
                .timestamp(plaintext.getTimestamp())
                .encryptionAlgorithm(E2EECrypto.ALGORITHM)
                .senderKeyId(peerManager.getLocalKeyId())
                .receiverKeyId(receiver.getKeyId())
                .build();
        return encrypted;
    }

    private boolean storeMailbox(Message message, String payloadJson, String payloadHash) {
        if (isMailboxCircuitOpen()) {
            peerManager.getOutboxRepository().markMailboxRetryable(
                    message.getMessageId(), "Mailbox circuit open until " + mailboxCircuitOpenUntil);
            return false;
        }
        try {
            peerManager.getOutboxRepository().markMailboxInFlight(message.getMessageId());
            boolean stored = mailboxClient.store(message, payloadJson, payloadHash);
            if (stored) {
                recordMailboxSuccess();
                peerManager.getOutboxRepository().markStoredMailbox(
                        message.getMessageId(), peerManager.getMailboxHost(), peerManager.getMailboxPort());
            }
            return stored;
        } catch (Exception e) {
            recordMailboxFailure();
            peerManager.getOutboxRepository().markMailboxRetryable(message.getMessageId(), e.getMessage());
            logger.warning("Failed to store mailbox message: " + e.getMessage());
            return false;
        }
    }

    private boolean isMailboxCircuitOpen() {
        return System.currentTimeMillis() < mailboxCircuitOpenUntil;
    }

    private void recordMailboxSuccess() {
        consecutiveMailboxFailures = 0;
        mailboxCircuitOpenUntil = 0;
    }

    private void recordMailboxFailure() {
        consecutiveMailboxFailures++;
        if (consecutiveMailboxFailures >= Constants.MAILBOX_CIRCUIT_FAILURE_THRESHOLD) {
            mailboxCircuitOpenUntil = System.currentTimeMillis() + Constants.MAILBOX_CIRCUIT_OPEN_MS;
            logger.warning("Mailbox circuit opened until " + mailboxCircuitOpenUntil);
        }
    }

    private Message createError(String msg) {
        return Message.builder().type(MessageType.ERROR.name()).content(msg).build();
    }

    public void shutdown() {}
}
