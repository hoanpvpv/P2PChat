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
        try (PreparedStatement existing = database.getConnection().prepareStatement(
                "SELECT payload_hash FROM offline_messages WHERE message_id = ?")) {
            existing.setString(1, env.messageId);
            ResultSet rs = existing.executeQuery();
            if (rs.next()) {
                String oldHash = rs.getString("payload_hash");
                return oldHash != null && oldHash.equals(env.payloadHash)
                        ? StoreResult.DUPLICATE
                        : StoreResult.CONFLICT;
            }
        }

        String sql = """
                INSERT INTO offline_messages (
                    message_id, conversation_id, sender, receiver, message_type,
                    payload_ciphertext, payload_hash, client_created_at, mailbox_stored_at,
                    expires_at, sender_seq, last_seen_message_id, offline_batch_id,
                    sender_public_key, sender_key_id, receiver_key_id, algorithm, nonce,
                    schema_version, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STORED')
                """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, env.messageId);
            ps.setString(2, env.conversationId);
            ps.setString(3, env.sender);
            ps.setString(4, env.receiver);
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
            ps.executeUpdate();
        }
        return StoreResult.STORED;
    }

    public List<MailboxEnvelope> pull(String receiver, int limit) throws SQLException {
        expireOldMessages();
        String sql = """
                SELECT * FROM offline_messages
                WHERE receiver = ? AND status = 'STORED' AND expires_at > ?
                ORDER BY mailbox_stored_at ASC
                LIMIT ?
                """;
        List<MailboxEnvelope> out = new ArrayList<>();
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, receiver);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, Math.max(1, Math.min(limit, 200)));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    public boolean markDelivered(String messageId, String receiver) throws SQLException {
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
        return env;
    }
}
