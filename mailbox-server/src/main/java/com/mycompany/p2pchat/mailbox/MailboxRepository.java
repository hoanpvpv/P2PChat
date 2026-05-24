package com.mycompany.p2pchat.mailbox;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MailboxRepository {
    public enum StoreResult { STORED, DUPLICATE, CONFLICT }

    private final MailboxDatabase database;

    public MailboxRepository(MailboxDatabase database) {
        this.database = database;
    }

    public StoreResult store(MailboxEnvelope env) throws SQLException {
        StoreResult existingResult = existingStoreResult(env.messageId, env.payloadHash);
        if (existingResult != null) return existingResult;

        String sql = """
                INSERT INTO offline_messages (
                    message_id, conversation_id, sender, receiver, message_type,
                    payload_ciphertext, payload_hash, client_created_at, mailbox_stored_at,
                    expires_at, sender_seq, last_seen_message_id, offline_batch_id,
                    sender_public_key, sender_key_id, receiver_key_id, algorithm, nonce,
                    schema_version, status, group_id, group_members
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STORED', ?, ?)
                """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, env.messageId);
            ps.setString(2, env.conversationId);
            ps.setString(3, env.sender);
            ps.setString(4, env.receiver != null ? env.receiver : "");
            ps.setString(5, env.type);
            ps.setString(6, env.payloadCiphertext);
            ps.setString(7, env.payloadHash);
            ps.setLong(8, env.clientCreatedAt);
            ps.setLong(9, env.mailboxStoredAt);
            ps.setLong(10, env.expiresAt);
            ps.setLong(11, env.senderSeq);
            ps.setString(12, env.lastSeenMessageId);
            ps.setString(13, env.offlineBatchId);
            ps.setString(14, env.senderPublicKey);
            ps.setString(15, env.senderKeyId);
            ps.setString(16, env.receiverKeyId);
            ps.setString(17, env.algorithm);
            ps.setString(18, env.nonce);
            ps.setInt(19, env.schemaVersion);
            ps.setString(20, env.groupId);
            ps.setString(21, env.groupMembers);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isConstraintViolation(e)) {
                StoreResult racedResult = existingStoreResult(env.messageId, env.payloadHash);
                if (racedResult != null) return racedResult;
            }
            throw e;
        }
        return StoreResult.STORED;
    }

    private StoreResult existingStoreResult(String messageId, String payloadHash) throws SQLException {
        try (PreparedStatement existing = database.getConnection().prepareStatement(
                "SELECT payload_hash FROM offline_messages WHERE message_id = ?")) {
            existing.setString(1, messageId);
            ResultSet rs = existing.executeQuery();
            if (rs.next()) {
                String oldHash = rs.getString("payload_hash");
                return oldHash != null && oldHash.equals(payloadHash)
                        ? StoreResult.DUPLICATE
                        : StoreResult.CONFLICT;
            }
        }
        return null;
    }

    private boolean isConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        String message = e.getMessage();
        return "23000".equals(state)
                || (message != null && message.toLowerCase().contains("constraint"));
    }

    public List<MailboxEnvelope> pull(String receiver, int limit) throws SQLException {
        expireOldMessages();
        int cap = Math.max(1, Math.min(limit, 200));
        List<MailboxEnvelope> out = new ArrayList<>();

        // Direct messages addressed to this receiver.
        String directSql = """
                SELECT * FROM offline_messages
                WHERE receiver = ? AND group_id IS NULL AND status = 'STORED' AND expires_at > ?
                ORDER BY mailbox_stored_at ASC
                LIMIT ?
                """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(directSql)) {
            ps.setString(1, receiver);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, cap);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(map(rs));
        }

        if (out.size() >= cap) return out;

        // Group messages where this peer is a snapshot member AND hasn't acked yet.
        String groupSql = """
                SELECT om.* FROM offline_messages om
                LEFT JOIN group_delivery_acks ga
                  ON ga.message_id = om.message_id AND ga.member = ?
                WHERE om.group_id IS NOT NULL
                  AND om.status = 'STORED'
                  AND om.expires_at > ?
                  AND ga.member IS NULL
                  AND ',' || om.group_members || ',' LIKE ?
                ORDER BY om.mailbox_stored_at ASC
                LIMIT ?
                """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(groupSql)) {
            ps.setString(1, receiver);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, "%," + receiver + ",%");
            ps.setInt(4, cap - out.size());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    public boolean markDelivered(String messageId, String receiver) throws SQLException {
        // Group message path: insert per-member ack, mark DELIVERED khi tất cả snapshot members đã ack.
        try (PreparedStatement ps = database.getConnection().prepareStatement(
                "SELECT group_id, group_members FROM offline_messages WHERE message_id = ?")) {
            ps.setString(1, messageId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getString("group_id") != null) {
                String members = rs.getString("group_members");
                try (PreparedStatement ins = database.getConnection().prepareStatement(
                        "INSERT OR IGNORE INTO group_delivery_acks (message_id, member, delivered_at) VALUES (?, ?, ?)")) {
                    ins.setString(1, messageId);
                    ins.setString(2, receiver);
                    ins.setLong(3, System.currentTimeMillis());
                    ins.executeUpdate();
                }
                if (members != null && !members.isBlank()) {
                    int expected = members.split(",").length;
                    try (PreparedStatement cnt = database.getConnection().prepareStatement(
                            "SELECT COUNT(*) FROM group_delivery_acks WHERE message_id = ?")) {
                        cnt.setString(1, messageId);
                        ResultSet crs = cnt.executeQuery();
                        if (crs.next() && crs.getInt(1) >= expected) {
                            try (PreparedStatement upd = database.getConnection().prepareStatement(
                                    "UPDATE offline_messages SET status = 'DELIVERED', delivered_at = ? WHERE message_id = ?")) {
                                upd.setLong(1, System.currentTimeMillis());
                                upd.setString(2, messageId);
                                upd.executeUpdate();
                            }
                        }
                    }
                }
                return true;
            }
        }

        // Direct path (giữ logic cũ).
        String sql = """
                UPDATE offline_messages
                SET status = 'DELIVERED', delivered_at = ?
                WHERE message_id = ? AND receiver = ? AND status IN ('STORED', 'DELIVERED')
                """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, messageId);
            ps.setString(3, receiver);
            return ps.executeUpdate() > 0;
        }
    }

    public int expireOldMessages() throws SQLException {
        try (PreparedStatement ps = database.getConnection().prepareStatement(
                "UPDATE offline_messages SET status = 'EXPIRED' WHERE status = 'STORED' AND expires_at <= ?")) {
            ps.setLong(1, System.currentTimeMillis());
            return ps.executeUpdate();
        }
    }

    /**
     * Return the subset of {@code candidateIds} that were sent by {@code sender}
     * and are now DELIVERED (so the sender can update its UI / outbox state).
     * Filtering by sender prevents one peer from probing another peer's deliveries.
     */
    public List<String> getDeliveredMessageIds(String sender, List<String> candidateIds) throws SQLException {
        List<String> delivered = new ArrayList<>();
        if (sender == null || sender.isBlank() || candidateIds == null || candidateIds.isEmpty()) {
            return delivered;
        }
        // Cap the batch to keep the IN clause sane.
        int cap = Math.min(candidateIds.size(), 500);
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < cap; i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        String sql = "SELECT message_id FROM offline_messages "
                + "WHERE sender = ? AND status = 'DELIVERED' AND message_id IN (" + placeholders + ")";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, sender);
            for (int i = 0; i < cap; i++) {
                ps.setString(i + 2, candidateIds.get(i));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) delivered.add(rs.getString(1));
        }
        return delivered;
    }

    private MailboxEnvelope map(ResultSet rs) throws SQLException {
        MailboxEnvelope env = new MailboxEnvelope();
        env.messageId = rs.getString("message_id");
        env.conversationId = rs.getString("conversation_id");
        env.sender = rs.getString("sender");
        env.receiver = rs.getString("receiver");
        env.type = rs.getString("message_type");
        env.payloadCiphertext = rs.getString("payload_ciphertext");
        env.payloadHash = rs.getString("payload_hash");
        env.clientCreatedAt = rs.getLong("client_created_at");
        env.mailboxStoredAt = rs.getLong("mailbox_stored_at");
        env.expiresAt = rs.getLong("expires_at");
        env.senderSeq = rs.getLong("sender_seq");
        env.lastSeenMessageId = rs.getString("last_seen_message_id");
        env.offlineBatchId = rs.getString("offline_batch_id");
        env.senderPublicKey = rs.getString("sender_public_key");
        env.senderKeyId = rs.getString("sender_key_id");
        env.receiverKeyId = rs.getString("receiver_key_id");
        env.algorithm = rs.getString("algorithm");
        env.nonce = rs.getString("nonce");
        env.schemaVersion = rs.getInt("schema_version");
        env.groupId = rs.getString("group_id");
        env.groupMembers = rs.getString("group_members");
        return env;
    }
}
