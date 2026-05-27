package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.database.RelayRepository;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService repairExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "group-repair");
        t.setDaemon(true);
        return t;
    });
    private int consecutiveMailboxFailures = 0;
    private long mailboxCircuitOpenUntil = 0;

    public PeerClient(PeerManager peerManager) {
        this.peerManager = peerManager;
        this.mailboxClient = new MailboxClient(peerManager);
        this.e2eeCrypto = E2EECrypto.loadOrCreate();
    }

    public MailboxClient getMailboxClient() { return mailboxClient; }

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
        if (peer == null || !hasEndpoint(peer)) {
            logger.warning("Peer endpoint unknown, storing in mailbox: " + receiver);
            status = storeMailbox(message, payloadJson, payloadHash) ? storedFallbackState(message.getMessageId()) : "QUEUED_LOCAL";
        } else {
            if (!peer.isOnline()) {
                logger.info("Peer marked offline by bootstrap, trying direct anyway: "
                        + receiver + "@" + peer.getAddress());
            }
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
                status = storeMailbox(message, payloadJson, payloadHash) ? storedFallbackState(message.getMessageId()) : "QUEUED_LOCAL";
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
            boolean triedDirect = receiver != null && hasEndpoint(receiver);
            if (triedDirect) {
                if (!receiver.isOnline()) {
                    logger.info("Retrying direct to peer marked offline: "
                            + entry.receiver + "@" + receiver.getAddress());
                }
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
                notifyOutboxState(entry.messageId, stored ? storedFallbackState(entry.messageId) : "FAILED_RETRYABLE", entry.receiver);
            }
    }

    private String storedFallbackState(String messageId) {
        return peerManager.getRelayRepository().activeAssignmentCount(messageId) > 0
                ? "STORED_RELAY"
                : "STORED_MAILBOX";
    }

    private boolean hasEndpoint(PeerInfo peer) {
        return peer != null
                && peer.getHost() != null
                && !peer.getHost().isBlank()
                && peer.getPort() > 0;
    }

    public List<Message> pullMailboxMessages() {
        List<Message> delivered = new ArrayList<>();
        try {
            for (MailboxEnvelope envelope : mailboxClient.pull(100)) {
                Message message = mailboxClient.decryptEnvelope(envelope);
                if (message == null || message.getMessageId() == null) continue;
                
                // Do not manually save here. Let PeerServer handle it.
                boolean alreadyHad = peerManager.getMessageRepository().messageExists(message.getMessageId());
                mailboxClient.deliveryAck(message.getMessageId());
                
                if (!alreadyHad) {
                    delivered.add(message);
                    if (peerManager.getPeerServer() != null) {
                        peerManager.getPeerServer().processMessageFromLocal(message);
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("Mailbox pull failed: " + e.getMessage());
        }
        return delivered;
    }

    /**
     * Poll mailbox for messages we sent that have now been delivered to the receiver.
     * For each one, promote outbox state STORED_MAILBOX → DELIVERED and push an
     * OUTBOX_UPDATE event so the sender UI can show "đã nhận" instead of staying on
     * "đã lưu mailbox" forever.
     */
    public void pollMailboxDeliveries() {
        List<String> pending = peerManager.getOutboxRepository().messageIdsAwaitingMailboxDelivery(100);
        if (pending.isEmpty()) return;
        List<String> delivered;
        try {
            delivered = mailboxClient.checkDelivered(pending);
        } catch (Exception e) {
            logger.fine("checkDelivered failed: " + e.getMessage());
            return;
        }
        for (String id : delivered) {
            String receiver = peerManager.getOutboxRepository().receiverFor(id);
            peerManager.getOutboxRepository().markDelivered(id);
            notifyOutboxState(id, "DELIVERED_VIA_MAILBOX", receiver);
        }
        if (!delivered.isEmpty()) {
            logger.info("Mailbox delivery confirmed for " + delivered.size() + " messages");
        }
    }

    public void retryRelayOutbox() {
        long now = System.currentTimeMillis();
        for (var expired : peerManager.getRelayRepository().assignmentsNeedingRotation(now, 100)) {
            sendRelaySupersede(expired);
        }
        peerManager.getRelayRepository().supersedeExpiredAssignments(now);
        for (var entry : peerManager.getOutboxRepository().relayStoredDueForRotation(25)) {
            if (peerManager.getRelayRepository().activeAssignmentCount(entry.messageId)
                    >= Constants.RELAY_REPLICATION_FACTOR) {
                continue;
            }
            try {
                Message message = JsonUtil.fromJson(entry.payloadJson);
                boolean stored = storeRelayFallback(message, entry.payloadJson, entry.payloadHash, true);
                if (stored) {
                    notifyOutboxState(entry.messageId, "STORED_RELAY", entry.receiver);
                }
            } catch (Exception e) {
                peerManager.getOutboxRepository().markRelayRetryable(entry.messageId, e.getMessage());
                notifyOutboxState(entry.messageId, "RELAY_FAILED_RETRYABLE", entry.receiver);
            }
        }
    }

    public void retryRelayForwards() {
        for (var relay : peerManager.getRelayRepository().dueForForward(25)) {
            PeerInfo receiver = peerManager.getPeer(relay.receiver);
            if (receiver == null || !hasEndpoint(receiver)) {
                peerManager.getRelayRepository().markForwardAttempt(relay.messageId, false);
                continue;
            }
            RelayRepository.RelayEnvelope env = toRelayEnvelope(relay);
            Message forward = Message.builder()
                    .type(MessageType.RELAY_FORWARD.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalUsername())
                    .receiver(relay.receiver)
                    .content(JsonUtil.toJson(env))
                    .timestamp(System.currentTimeMillis())
                    .build();
            Message response = sendAndRead(forward, receiver.getHost(), receiver.getPort(), Constants.ACK_TIMEOUT);
            boolean delivered = response != null && MessageType.RELAY_DELIVERY_ACK.name().equals(response.getType());
            peerManager.getRelayRepository().markForwardAttempt(relay.messageId, delivered);
            if (delivered) {
                sendRelayDeliveredNotice(env);
            }
        }
    }

    public void pullRelayMessages() {
        if (!peerManager.hasLocalIdentity()) return;
        for (PeerInfo peer : peerManager.getOnlinePeers()) {
            if (peer.getUsername().equals(peerManager.getLocalUsername()) || !hasEndpoint(peer)) continue;
            Message request = Message.builder()
                    .type(MessageType.RELAY_PULL.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalUsername())
                    .receiver(peer.getUsername())
                    .content(peerManager.getLocalUsername())
                    .timestamp(System.currentTimeMillis())
                    .build();
            Message response = sendAndRead(request, peer.getHost(), peer.getPort(), 2500);
            if (response == null || !MessageType.RELAY_PULL_RESPONSE.name().equals(response.getType())
                    || response.getContent() == null || response.getContent().isBlank()) {
                continue;
            }
            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<List<RelayRepository.RelayEnvelope>>() {}.getType();
            List<RelayRepository.RelayEnvelope> envelopes = new com.google.gson.Gson().fromJson(response.getContent(), listType);
            if (envelopes == null) continue;
            for (RelayRepository.RelayEnvelope env : envelopes) {
                acceptRelayEnvelopeFromPull(env, peer);
            }
        }
    }

    private void acceptRelayEnvelopeFromPull(RelayRepository.RelayEnvelope env, PeerInfo relayPeer) {
        if (env == null || env.messageId == null || env.payloadJson == null) return;
        if (!peerManager.getLocalUsername().equals(env.receiver)) return;
        boolean alreadyHad = peerManager.getMessageRepository().messageExists(env.messageId);
        if (!alreadyHad && peerManager.getPeerServer() != null) {
            Message relayed = JsonUtil.fromJson(env.payloadJson);
            if (relayed != null) {
                peerManager.getPeerServer().processMessageFromLocal(relayed);
            }
        }
        peerManager.getRelayRepository().addTombstone(env.messageId, env.payloadHash, "DELIVERED");
        Message ack = Message.builder()
                .type(MessageType.RELAY_DELIVERY_ACK.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .receiver(relayPeer.getUsername())
                .content(JsonUtil.toJson(env))
                .timestamp(System.currentTimeMillis())
                .build();
        sendAndRead(ack, relayPeer.getHost(), relayPeer.getPort(), 2500);
    }

    public void gossipAckToMailbox(String messageId) {
        try { mailboxClient.deliveryAck(messageId); }
        catch (Exception e) { logger.fine("gossip ack fail: " + e.getMessage()); }
    }

    public void sendRelayDeliveredNotice(RelayRepository.RelayEnvelope env) {
        if (env == null || env.sender == null || env.sender.equals(peerManager.getLocalUsername())) {
            return;
        }
        PeerInfo sender = peerManager.getPeer(env.sender);
        if (sender == null || !hasEndpoint(sender)) return;
        Message notice = Message.builder()
                .type(MessageType.RELAY_DELIVERED_NOTICE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .receiver(env.sender)
                .content(JsonUtil.toJson(env))
                .timestamp(System.currentTimeMillis())
                .build();
        sendAndRead(notice, sender.getHost(), sender.getPort(), 2500);
    }

    public void broadcastRelayTombstone(RelayRepository.RelayEnvelope env) {
        if (env == null || env.messageId == null) return;
        Message tombstone = Message.builder()
                .type(MessageType.RELAY_TOMBSTONE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .content(JsonUtil.toJson(env))
                .timestamp(System.currentTimeMillis())
                .build();
        for (PeerInfo peer : peerManager.getOnlinePeers()) {
            if (peer.getUsername().equals(peerManager.getLocalUsername()) || !hasEndpoint(peer)) continue;
            sendAndRead(tombstone, peer.getHost(), peer.getPort(), 1500);
        }
    }

    private void notifyMessageToWeb(Message message) {
        var server = peerManager.getPeerServer();
        if (server == null) return;
        try {
            if (MessageType.GROUP_MESSAGE.name().equals(message.getType())) {
                server.broadcastIncoming("GROUP_MESSAGE", message);
            } else if (MessageType.DIRECT_MESSAGE.name().equals(message.getType())) {
                server.broadcastIncoming("DIRECT_MESSAGE", message);
            }
        } catch (Exception ignored) {}
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
        if (peerManager.getPeerServer() != null) {
            peerManager.getPeerServer().broadcastIncoming("GROUP_MESSAGE", message);
        }

        boolean allSent = true;
        String myUsername = peerManager.getLocalUsername();
        java.util.List<String> missedMemberUsernames = new java.util.ArrayList<>();
        boolean needsRepair = false;
        for (String memberUsername : cache.getMembers()) {
            if (memberUsername.equals(myUsername)) continue;
            String targetAddress = resolveCurrentAddress(memberUsername);
            if (targetAddress == null || !targetAddress.contains(":")) {
                missedMemberUsernames.add(memberUsername);
                continue;
            }
            String[] parts = targetAddress.split(":");
            if (parts.length != 2) continue;
            String memberHost = parts[0];
            int memberPort;
            try { memberPort = Integer.parseInt(parts[1]); }
            catch (NumberFormatException e) { continue; }

            boolean delivered = false;
            try { delivered = sendSingle(message, memberHost, memberPort); }
            catch (Exception ignored) {}

            if (!delivered) {
                allSent = false;
                if (memberUsername != null && !memberUsername.isBlank()) {
                    missedMemberUsernames.add(memberUsername);
                }
                needsRepair = true;
            }
        }

        if (needsRepair && peerManager.getLazyRepairManager() != null) {
            repairExecutor.submit(() -> peerManager.getLazyRepairManager().repair(groupId));
        }

        if (!missedMemberUsernames.isEmpty()) {
            try {
                String payloadJson = mailboxClient.payloadJson(message);
                String payloadHash = mailboxClient.payloadHash(payloadJson);
                int totalGroupSize = cache.getMembers().size();
                boolean stored = mailboxClient.storeGroup(message, payloadJson, payloadHash,
                        groupId, missedMemberUsernames, totalGroupSize);
                if (stored) {
                    logger.info("Group msg " + message.getMessageId() + " stored to mailbox for "
                            + missedMemberUsernames + " (group size=" + totalGroupSize + ")");
                }
            } catch (Exception e) {
                logger.warning("Failed to store group msg to mailbox: " + e.getMessage());
            }
        }
        return allSent;
    }

    private String resolveUsernameFromAddress(String address) {
        for (var p : peerManager.getAllKnownPeers()) {
            if (address.equals(p.getHost() + ":" + p.getPort())) return p.getUsername();
        }
        for (var p : peerManager.getRecentPeersCache().getAll()) {
            if (address.equals(p.address)) return p.username;
        }
        return null;
    }

    private String resolveCurrentAddress(String usernameOrAddress) {
        if (usernameOrAddress == null) return null;
        if (!usernameOrAddress.contains(":")) {
            PeerInfo p = peerManager.getPeer(usernameOrAddress);
            if (p != null) return p.getAddress();
            return peerManager.getRecentPeersCache().getAddress(usernameOrAddress);
        } else {
            String username = resolveUsernameFromAddress(usernameOrAddress);
            if (username == null || username.isBlank()) return usernameOrAddress;
            PeerInfo current = peerManager.getPeer(username);
            if (current != null && current.isOnline() && current.getHost() != null && current.getPort() > 0) {
                return current.getAddress();
            }
            return usernameOrAddress;
        }
    }

    private boolean storeRelayFallback(Message message, String payloadJson, String payloadHash, boolean refreshOnly) {
        if (message == null || message.getReceiver() == null || message.getReceiver().isBlank()) return false;
        PeerInfo receiver = peerManager.getPeer(message.getReceiver());
        Message encrypted = encryptDirectMessage(message, receiver);
        if (encrypted == null) return false;

        List<PeerInfo> relays = chooseRelayPeers(message.getMessageId(), message.getReceiver());
        if (relays.isEmpty()) return false;

        boolean storedAny = false;
        int generation = Math.max(1, (int) (System.currentTimeMillis() / Constants.RELAY_LEASE_MS));
        long now = System.currentTimeMillis();
        long leaseUntil = now + Constants.RELAY_LEASE_MS;
        long expiresAt = now + Constants.RELAY_MESSAGE_TTL_MS;
        int index = 0;
        for (PeerInfo relay : relays) {
            RelayRepository.RelayEnvelope env = new RelayRepository.RelayEnvelope();
            env.messageId = message.getMessageId();
            env.sender = message.getSender();
            env.receiver = message.getReceiver();
            env.payloadJson = JsonUtil.toJson(encrypted);
            env.payloadHash = payloadHash;
            env.generation = generation;
            env.role = index == 0 ? "PRIMARY" : "BACKUP";
            env.leaseUntil = leaseUntil;
            env.expiresAt = expiresAt;
            index++;

            Message wire = Message.builder()
                    .type(MessageType.RELAY_STORE.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalUsername())
                    .receiver(relay.getUsername())
                    .content(JsonUtil.toJson(env))
                    .timestamp(now)
                    .build();
            Message response = sendAndRead(wire, relay.getHost(), relay.getPort(), Constants.ACK_TIMEOUT);
            if (response != null && MessageType.RELAY_STORE_ACK.name().equals(response.getType())) {
                peerManager.getRelayRepository().saveAssignment(
                        message.getMessageId(), relay.getUsername(), generation, env.role, leaseUntil);
                storedAny = true;
            } else {
                peerManager.getRelayRepository().markAssignmentUnreachable(
                        message.getMessageId(), relay.getUsername(), generation, "RELAY_STORE failed");
            }
        }
        if (storedAny) {
            peerManager.getOutboxRepository().markStoredRelay(message.getMessageId());
            System.out.printf("%n[RELAY] Stored message %s via peer relay fallback%n> ", message.getMessageId());
        } else if (!refreshOnly) {
            peerManager.getOutboxRepository().markRelayRetryable(message.getMessageId(), "No relay peer accepted message");
        }
        return storedAny;
    }

    private List<PeerInfo> chooseRelayPeers(String messageId, String receiver) {
        Set<String> alreadyAssigned = peerManager.getRelayRepository().assignedRelayPeers(messageId);
        return peerManager.getOnlinePeers().stream()
                .filter(p -> !p.getUsername().equals(peerManager.getLocalUsername()))
                .filter(p -> !p.getUsername().equals(receiver))
                .filter(p -> !alreadyAssigned.contains(p.getUsername()))
                .filter(p -> !peerManager.getRelayRepository().isRelayInCooldown(p.getUsername()))
                .filter(this::hasEndpoint)
                .sorted(Comparator.comparing(p -> sha256Hex(messageId + "|" + receiver + "|" + p.getUsername())))
                .limit(Constants.RELAY_REPLICATION_FACTOR)
                .toList();
    }

    private void sendRelaySupersede(RelayRepository.RelayAssignment assignment) {
        PeerInfo relay = peerManager.getPeer(assignment.relayPeer);
        if (relay == null || !hasEndpoint(relay)) {
            peerManager.getRelayRepository().markRelayCooldown(assignment.relayPeer, "Relay offline during supersede");
            return;
        }
        RelayRepository.RelayEnvelope env = new RelayRepository.RelayEnvelope();
        env.messageId = assignment.messageId;
        env.generation = assignment.generation;
        env.role = assignment.role;
        env.leaseUntil = assignment.leaseUntil;
        Message msg = Message.builder()
                .type(MessageType.RELAY_SUPERSEDE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .receiver(assignment.relayPeer)
                .content(JsonUtil.toJson(env))
                .timestamp(System.currentTimeMillis())
                .build();
        sendAndRead(msg, relay.getHost(), relay.getPort(), 1500);
    }

    private RelayRepository.RelayEnvelope toRelayEnvelope(RelayRepository.RelayMessage relay) {
        RelayRepository.RelayEnvelope env = new RelayRepository.RelayEnvelope();
        env.messageId = relay.messageId;
        env.sender = relay.sender;
        env.receiver = relay.receiver;
        env.payloadJson = relay.payloadJson;
        env.payloadHash = relay.payloadHash;
        env.generation = relay.generation;
        env.role = relay.role;
        env.leaseUntil = relay.leaseUntil;
        env.expiresAt = relay.expiresAt;
        return env;
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
            if (coord.equals(peerManager.getLocalUsername())) {
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
            String coordAddress = resolveCurrentAddress(coord);
            if (coordAddress == null || !coordAddress.contains(":")) continue;
            String[] parts = coordAddress.split(":");
            if (parts.length != 2) continue;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), Constants.COORDINATOR_TIMEOUT);
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

    private Message sendAndRead(Message message, String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(JsonUtil.toJson(message));
            out.flush();
            String resp = in.readLine();
            return resp != null && !resp.isBlank() ? JsonUtil.fromJson(resp.trim()) : null;
        } catch (IOException e) {
            logger.fine("Request failed to " + host + ":" + port + ": " + e.getMessage());
            return null;
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
            return storeRelayFallback(message, payloadJson, payloadHash, false);
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
            logger.warning("Failed to store mailbox message: " + e.getMessage());
            boolean relayed = storeRelayFallback(message, payloadJson, payloadHash, false);
            if (!relayed) {
                peerManager.getOutboxRepository().markMailboxRetryable(message.getMessageId(), e.getMessage());
            }
            return relayed;
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

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public void shutdown() {
        repairExecutor.shutdownNow();
    }
}
