package com.mycompany.p2pchat.peer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;

import java.io.*;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public class MailboxClient {
    private static final Gson GSON = new Gson();
    private static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long GROUP_TTL_FLOOR_MS = 12L * 60 * 60 * 1000;

    /**
     * Tinh TTL cho group msg: cang dong member, TTL cang ngan vi xac suat
     * it nhat 1 nguoi online de gossip cho nguoi khac cang cao.
     * ttl = max(12h, 7d / log2(max(2, N))).
     */
    static long groupTtlMillis(int totalMembers) {
        int n = Math.max(2, totalMembers);
        double scaled = DEFAULT_TTL_MS / (Math.log(n) / Math.log(2));
        return Math.max(GROUP_TTL_FLOOR_MS, (long) scaled);
    }

    private final PeerManager peerManager;
    private final E2EECrypto e2eeCrypto;

    public MailboxClient(PeerManager peerManager) {
        this.peerManager = peerManager;
        this.e2eeCrypto = E2EECrypto.loadOrCreate();
    }

    public String payloadJson(Message message) {
        return JsonUtil.toJson(message);
    }

    public String payloadHash(String payloadJson) {
        return sha256Hex(payloadJson);
    }

    public boolean store(Message message) throws IOException {
        String payload = payloadJson(message);
        return store(message, payload, payloadHash(payload));
    }

    public boolean storeGroup(Message message, String payloadJson, String payloadHash,
                              String groupId, java.util.List<String> missedMembers, int totalGroupSize) throws IOException {
        MailboxEnvelope envelope = new MailboxEnvelope();
        envelope.messageId = message.getMessageId();
        envelope.conversationId = "group:" + groupId;
        envelope.sender = message.getSender();
        envelope.receiver = "";
        envelope.type = message.getType();
        // Group hiện chưa có shared group key → lưu plaintext payload. Tương lai có thể bọc với sender-key.
        envelope.payloadCiphertext = payloadJson;
        envelope.payloadHash = payloadHash;
        envelope.clientCreatedAt = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        // TTL group ngắn hơn direct vì xác suất ít nhất 1 member online → gossip cho rest cao hơn.
        envelope.expiresAt = System.currentTimeMillis() + groupTtlMillis(totalGroupSize);
        envelope.senderPublicKey = peerManager.getLocalPublicKey();
        envelope.senderKeyId = peerManager.getLocalKeyId();
        envelope.algorithm = "PLAINTEXT-DEMO";
        envelope.groupId = groupId;
        envelope.groupMembers = String.join(",", missedMembers);

        Message wire = Message.builder()
                .type(MessageType.STORE_MESSAGE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .receiver("__group__")
                .content(GSON.toJson(envelope))
                .timestamp(System.currentTimeMillis())
                .build();

        Message response = send(wire);
        if (response == null) return false;
        if (MessageType.ERROR.name().equals(response.getType())) {
            throw new IOException(response.getContent());
        }
        boolean success = MessageType.STORE_ACK.name().equals(response.getType());
        if (success) {
            System.out.printf("%n[MAILBOX] Stored group event %s for %d offline members%n> ", 
                message.getMessageId(), missedMembers.size());
        }
        return success;
    }

    public boolean storeControl(Message message, String payloadJson, String payloadHash) throws IOException {
        MailboxEnvelope envelope = new MailboxEnvelope();
        envelope.messageId = message.getMessageId();
        envelope.conversationId = "control:" + message.getReceiver();
        envelope.sender = message.getSender();
        envelope.receiver = message.getReceiver();
        envelope.type = message.getType();
        // Control messages are plaintext
        envelope.payloadCiphertext = payloadJson;
        envelope.payloadHash = payloadHash;
        envelope.clientCreatedAt = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        envelope.expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MS;
        envelope.senderPublicKey = peerManager.getLocalPublicKey();
        envelope.senderKeyId = peerManager.getLocalKeyId();
        envelope.receiverKeyId = "";
        envelope.algorithm = "PLAINTEXT-DEMO";

        Message wire = Message.builder()
                .type(MessageType.STORE_MESSAGE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .receiver(message.getReceiver())
                .content(GSON.toJson(envelope))
                .timestamp(System.currentTimeMillis())
                .build();

        Message response = send(wire);
        if (response == null) return false;
        if (MessageType.ERROR.name().equals(response.getType())) {
            throw new IOException(response.getContent());
        }
        boolean success = MessageType.STORE_ACK.name().equals(response.getType());
        if (success) {
            System.out.printf("%n[MAILBOX] Stored control event %s for offline member %s%n> ", 
                message.getMessageId(), message.getReceiver());
        }
        return success;
    }

    public boolean store(Message message, String payloadJson, String payloadHash) throws IOException {
        PeerInfo receiverPeer = peerManager.getPeer(message.getReceiver());
        if (receiverPeer == null || receiverPeer.getPublicKey() == null || receiverPeer.getPublicKey().isBlank()
                || receiverPeer.getKeyId() == null || receiverPeer.getKeyId().isBlank()) {
            throw new IOException("Missing E2EE public key for receiver: " + message.getReceiver());
        }
        String encryptedPayload = e2eeCrypto.encryptFor(payloadJson, receiverPeer.getPublicKey(), receiverPeer.getKeyId());

        MailboxEnvelope envelope = new MailboxEnvelope();
        envelope.messageId = message.getMessageId();
        envelope.conversationId = conversationId(message.getSender(), message.getReceiver());
        envelope.sender = message.getSender();
        envelope.receiver = message.getReceiver();
        envelope.type = message.getType();
        envelope.payloadCiphertext = encryptedPayload;
        envelope.payloadHash = payloadHash;
        envelope.clientCreatedAt = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        envelope.expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MS;
        envelope.senderPublicKey = peerManager.getLocalPublicKey();
        envelope.senderKeyId = peerManager.getLocalKeyId();
        envelope.receiverKeyId = receiverPeer.getKeyId();
        envelope.algorithm = E2EECrypto.ALGORITHM;

        Message wire = Message.builder()
                .type(MessageType.STORE_MESSAGE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .receiver(message.getReceiver())
                .content(GSON.toJson(envelope))
                .timestamp(System.currentTimeMillis())
                .build();

        Message response = send(wire);
        if (response == null) return false;
        if (MessageType.ERROR.name().equals(response.getType())) {
            throw new IOException(response.getContent());
        }
        boolean success = MessageType.STORE_ACK.name().equals(response.getType());
        if (success) {
            System.out.printf("%n[MAILBOX] Stored message %s for offline member %s%n> ", 
                message.getMessageId(), message.getReceiver());
        }
        return success;
    }

    public List<MailboxEnvelope> pull(int limit) throws IOException {
        PullRequest req = new PullRequest();
        req.receiver = peerManager.getLocalUsername();
        req.limit = limit;
        Message wire = Message.builder()
                .type(MessageType.PULL_MESSAGES.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .content(GSON.toJson(req))
                .timestamp(System.currentTimeMillis())
                .build();
        Message response = send(wire);
        if (response == null) return List.of();
        if (MessageType.ERROR.name().equals(response.getType())) {
            throw new IOException(response.getContent());
        }
        if (!MessageType.PULL_RESPONSE.name().equals(response.getType()) || response.getContent() == null) {
            return List.of();
        }
        Type listType = new TypeToken<List<MailboxEnvelope>>() {}.getType();
        List<MailboxEnvelope> messages = GSON.fromJson(response.getContent(), listType);
        return messages != null ? messages : List.of();
    }

    public Message decryptEnvelope(MailboxEnvelope envelope) {
        String payload = envelope.payloadCiphertext;
        if (E2EECrypto.isEncryptedAlgorithm(envelope.algorithm)) {
            payload = e2eeCrypto.decrypt(envelope.payloadCiphertext);
        }
        return JsonUtil.fromJson(payload);
    }

    /**
     * Ask the mailbox which of {@code candidateIds} (sent by us) are now DELIVERED.
     * Used by sender to upgrade outbox state from STORED_MAILBOX → DELIVERED_VIA_MAILBOX
     * so the UI can show "đã nhận" instead of staying on "đã lưu mailbox" forever.
     */
    public List<String> checkDelivered(List<String> candidateIds) throws IOException {
        if (candidateIds == null || candidateIds.isEmpty()) return List.of();
        CheckDeliveryRequest req = new CheckDeliveryRequest();
        req.messageIds = candidateIds;
        Message wire = Message.builder()
                .type(MessageType.CHECK_DELIVERY.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .content(GSON.toJson(req))
                .timestamp(System.currentTimeMillis())
                .build();
        Message response = send(wire);
        if (response == null) return List.of();
        if (MessageType.ERROR.name().equals(response.getType())) {
            throw new IOException(response.getContent());
        }
        if (!MessageType.CHECK_DELIVERY_RESPONSE.name().equals(response.getType())
                || response.getContent() == null || response.getContent().isBlank()) {
            return List.of();
        }
        Type listType = new TypeToken<List<String>>() {}.getType();
        List<String> delivered = GSON.fromJson(response.getContent(), listType);
        return delivered != null ? delivered : List.of();
    }

    public void deliveryAck(String messageId) throws IOException {
        DeliveryAck ack = new DeliveryAck();
        ack.receiver = peerManager.getLocalUsername();
        ack.messageId = messageId;
        Message wire = Message.builder()
                .type(MessageType.DELIVERY_ACK.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalUsername())
                .content(GSON.toJson(ack))
                .timestamp(System.currentTimeMillis())
                .build();
        send(wire);
    }

    public void resolveMailboxFromBootstrap() {
        if (!peerManager.isRegisteredToBootstrap()) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerManager.getBootstrapHost(), peerManager.getBootstrapPort()), 3000);
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            Message request = Message.builder()
                    .type(MessageType.RESOLVE_MAILBOX.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalUsername())
                    .timestamp(System.currentTimeMillis())
                    .build();
            out.println(JsonUtil.toJson(request));
            String line = in.readLine();
            if (line == null) return;
            Message response = JsonUtil.fromJson(line.trim());
            if (!MessageType.RESOLVE_MAILBOX_ACK.name().equals(response.getType())) return;
            String endpoint = response.getContent();
            if (endpoint == null || endpoint.isBlank()) return;
            String[] parts = endpoint.split(":");
            if (parts.length != 2) return;
            peerManager.setMailboxHost(parts[0]);
            peerManager.setMailboxPort(Integer.parseInt(parts[1]));
        } catch (Exception ignored) {
            // Keep configured/default mailbox endpoint.
        }
    }

    private Message send(Message wire) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerManager.getMailboxHost(), peerManager.getMailboxPort()), 5000);
            socket.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(JsonUtil.toJson(wire));
            out.flush();
            String line = in.readLine();
            return line != null && !line.isBlank() ? JsonUtil.fromJson(line.trim()) : null;
        }
    }

    private String conversationId(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
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

    private static class PullRequest {
        String receiver;
        int limit;
    }

    private static class DeliveryAck {
        String receiver;
        String messageId;
    }

    private static class CheckDeliveryRequest {
        List<String> messageIds;
    }
}
