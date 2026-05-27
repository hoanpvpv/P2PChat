package com.mycompany.p2pchat.mailbox;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MailboxServer {
    private static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private final int port;
    private final MailboxRepository repository;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public MailboxServer(int port, MailboxRepository repository) {
        this.port = port;
        this.repository = repository;
    }

    public void start() {
        running = true;
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("=== Mailbox Server running on port " + port + " ===");
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    pool.execute(() -> handle(socket));
                } catch (IOException e) {
                    if (running) System.err.println("Mailbox accept failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mailbox server on port " + port, e);
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdown();
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            socket.setSoTimeout(10000);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                WireMessage msg = JsonUtil.fromJson(line.trim(), WireMessage.class);
                WireMessage response = dispatch(msg);
                out.println(JsonUtil.toJson(response));
                out.flush();
            }
        } catch (Exception e) {
            System.err.println("Mailbox client error: " + e.getMessage());
        }
    }

    private WireMessage dispatch(WireMessage msg) {
        try {
            MessageType type = MessageType.valueOf(msg.getType());
            return switch (type) {
                case STORE_MESSAGE -> handleStore(msg);
                case PULL_MESSAGES -> handlePull(msg);
                case DELIVERY_ACK -> handleDeliveryAck(msg);
                case CHECK_DELIVERY -> handleCheckDelivery(msg);
                default -> error("Unsupported mailbox message type: " + msg.getType());
            };
        } catch (IllegalArgumentException e) {
            return error("Unknown mailbox message type: " + msg.getType());
        } catch (Exception e) {
            return error(e.getMessage() != null ? e.getMessage() : "Mailbox error");
        }
    }

    private WireMessage handleStore(WireMessage msg) throws SQLException {
        MailboxEnvelope env = JsonUtil.fromJson(msg.getContent(), MailboxEnvelope.class);
        validateEnvelope(env);
        env.applyDefaults(DEFAULT_TTL_MS);
        MailboxRepository.StoreResult result = repository.store(env);
        if (result == MailboxRepository.StoreResult.CONFLICT) {
            return error("ERROR_CONFLICT: messageId exists with different payloadHash");
        }
        String target = (env.receiver != null && !env.receiver.isBlank()) ? env.receiver : env.groupId;
        System.out.println("[STORE] " + env.sender + " đang lưu tin nhắn (ID: " + env.messageId + ") gửi tới " + target);
        return WireMessage.of(MessageType.STORE_ACK.name(), "mailbox", env.sender,
                JsonUtil.toJson(new StoreAck(env.messageId, result.name())));
    }

    private WireMessage handlePull(WireMessage msg) throws SQLException {
        String receiver = msg.getSender();
        int limit = 100;
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            PullRequest req = JsonUtil.fromJson(msg.getContent(), PullRequest.class);
            if (req.receiver != null && !req.receiver.isBlank()) receiver = req.receiver;
            if (req.limit > 0) limit = req.limit;
        }
        if (receiver == null || receiver.isBlank()) {
            return error("Missing receiver for PULL_MESSAGES");
        }
        List<MailboxEnvelope> messages = repository.pull(receiver, limit);
        if (!messages.isEmpty()) {
            System.out.println("[PULL] Đã trả về " + messages.size() + " tin nhắn cho " + receiver);
        }
        return WireMessage.of(MessageType.PULL_RESPONSE.name(), "mailbox", receiver,
                JsonUtil.toJson(messages));
    }

    private WireMessage handleDeliveryAck(WireMessage msg) throws SQLException {
        String receiver = msg.getSender();
        String messageId = msg.getContent();
        DeliveryAck ack = null;
        if (messageId != null && messageId.trim().startsWith("{")) {
            ack = JsonUtil.fromJson(messageId, DeliveryAck.class);
            messageId = ack.messageId;
            if (ack.receiver != null && !ack.receiver.isBlank()) receiver = ack.receiver;
        }
        if (receiver == null || receiver.isBlank() || messageId == null || messageId.isBlank()) {
            return error("Missing receiver or messageId for DELIVERY_ACK");
        }
        boolean updated = repository.markDelivered(messageId, receiver);
        return WireMessage.of(MessageType.STORE_ACK.name(), "mailbox", receiver,
                JsonUtil.toJson(new DeliveryAckResult(messageId, updated ? "DELIVERED" : "NOT_FOUND")));
    }

    private WireMessage handleCheckDelivery(WireMessage msg) throws SQLException {
        String sender = msg.getSender();
        if (sender == null || sender.isBlank() || msg.getContent() == null || msg.getContent().isBlank()) {
            return error("Missing sender or messageId list for CHECK_DELIVERY");
        }
        CheckDeliveryRequest req = JsonUtil.fromJson(msg.getContent(), CheckDeliveryRequest.class);
        if (req == null || req.messageIds == null || req.messageIds.isEmpty()) {
            return WireMessage.of(MessageType.CHECK_DELIVERY_RESPONSE.name(), "mailbox", sender, "[]");
        }
        List<String> delivered = repository.getDeliveredMessageIds(sender, req.messageIds);
        return WireMessage.of(MessageType.CHECK_DELIVERY_RESPONSE.name(), "mailbox", sender,
                JsonUtil.toJson(delivered));
    }

    private void validateEnvelope(MailboxEnvelope env) {
        if (env == null) throw new IllegalArgumentException("Missing envelope");
        if (isBlank(env.messageId)) throw new IllegalArgumentException("Missing messageId");
        if (isBlank(env.sender)) throw new IllegalArgumentException("Missing sender");
        if (isBlank(env.type)) throw new IllegalArgumentException("Missing type");
        if (isBlank(env.payloadCiphertext)) throw new IllegalArgumentException("Missing payloadCiphertext");
        if (isBlank(env.payloadHash)) throw new IllegalArgumentException("Missing payloadHash");
        boolean hasGroup = !isBlank(env.groupId) && !isBlank(env.groupMembers);
        boolean hasReceiver = !isBlank(env.receiver);
        if (!hasGroup && !hasReceiver) {
            throw new IllegalArgumentException("Envelope must have receiver or (groupId + groupMembers)");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private WireMessage error(String error) {
        return WireMessage.of(MessageType.ERROR.name(), "mailbox", null, error);
    }

    private static class StoreAck {
        final String messageId;
        final String status;
        StoreAck(String messageId, String status) {
            this.messageId = messageId;
            this.status = status;
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

    private static class DeliveryAckResult {
        final String messageId;
        final String status;
        DeliveryAckResult(String messageId, String status) {
            this.messageId = messageId;
            this.status = status;
        }
    }
}
