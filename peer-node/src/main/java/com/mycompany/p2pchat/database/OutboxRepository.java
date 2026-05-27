package com.mycompany.p2pchat.database;

import com.mycompany.p2pchat.utils.Constants;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class OutboxRepository {
    public static class OutboxEntry {
        public String messageId;
        public String receiver;
        public String payloadJson;
        public String payloadHash;
        public String state;
        public int attemptCount;
        public int directAttemptCount;
        public int mailboxAttemptCount;
        public long nextRetryAt;
        public long lastAttemptAt;
        public long firstFailureAt;
        public String failureCode;
        public String lastError;
    }

    private final DatabaseManager dbManager;

    public OutboxRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void savePending(String messageId, String receiver, String payloadJson, String payloadHash) {
        long now = System.currentTimeMillis();
        String sql = """
                INSERT OR IGNORE INTO outbound_messages
                (message_id, receiver, payload_json, payload_hash, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING_LOCAL', ?, ?)
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.setString(2, receiver);
            ps.setString(3, payloadJson);
            ps.setString(4, payloadHash);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save outbox message", e);
        }
    }

    public void markDirectInFlight(String messageId) {
        markInFlight(messageId, "DIRECT_IN_FLIGHT", true);
    }

    public void markMailboxInFlight(String messageId) {
        markInFlight(messageId, "MAILBOX_IN_FLIGHT", false);
    }

    public void markDelivered(String messageId) {
        updateState(messageId, "DELIVERED", null, null, 0);
    }

    public void markStoredMailbox(String messageId, String mailboxHost, int mailboxPort) {
        long now = System.currentTimeMillis();
        String sql = """
                UPDATE outbound_messages
                SET state = 'STORED_MAILBOX', mailbox_host = ?, mailbox_port = ?,
                    failure_code = NULL, last_error = NULL, next_retry_at = ?, updated_at = ?
                WHERE message_id = ?
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, mailboxHost);
            ps.setInt(2, mailboxPort);
            ps.setLong(3, now + Constants.OUTBOX_BACKOFF_BASE_MS);
            ps.setLong(4, now);
            ps.setString(5, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark outbox stored", e);
        }
    }

    public void markStoredRelay(String messageId) {
        updateState(messageId, "STORED_RELAY", null, null, System.currentTimeMillis() + Constants.RELAY_ROTATION_INTERVAL_MS);
    }

    public void markRelayRetryable(String messageId, String error) {
        markRetryable(messageId, "ERR_RELAY_SEND", error, false);
    }

    public void markRetryable(String messageId, String error) {
        markRetryable(messageId, "ERR_RETRYABLE", error, false);
    }

    public void markDirectRetryable(String messageId, String error) {
        markRetryable(messageId, "ERR_DIRECT_SEND", error, true);
    }

    public void markMailboxRetryable(String messageId, String error) {
        markRetryable(messageId, "ERR_MAILBOX_SEND", error, false);
    }

    public void markDeadLetter(String messageId, String code, String error) {
        updateState(messageId, "DEAD_LETTER", code, error, 0);
    }

    public List<OutboxEntry> dueForDeliveryRetry(int limit) {
        String sql = """
                SELECT * FROM outbound_messages
                WHERE state NOT IN ('DELIVERED', 'DEAD_LETTER', 'STORED_MAILBOX', 'STORED_RELAY')
                  AND next_retry_at <= ?
                ORDER BY created_at ASC
                LIMIT ?
                """;
        return queryEntries(sql, System.currentTimeMillis(), Math.max(1, limit));
    }

    public List<OutboxEntry> relayStoredDueForRotation(int limit) {
        String sql = """
                SELECT * FROM outbound_messages
                WHERE state = 'STORED_RELAY' AND next_retry_at <= ?
                ORDER BY created_at ASC
                LIMIT ?
                """;
        return queryEntries(sql, System.currentTimeMillis(), Math.max(1, limit));
    }

    public List<OutboxEntry> dueForMailboxRetry(int limit) {
        String sql = """
                SELECT * FROM outbound_messages
                WHERE state IN ('PENDING_LOCAL', 'FAILED_RETRYABLE', 'MAILBOX_IN_FLIGHT')
                  AND next_retry_at <= ?
                ORDER BY created_at ASC
                LIMIT ?
                """;
        return queryEntries(sql, System.currentTimeMillis(), Math.max(1, limit));
    }

    public List<OutboxEntry> recent(int limit) {
        String sql = """
                SELECT * FROM outbound_messages
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        List<OutboxEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) entries.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load outbox entries", e);
        }
        return entries;
    }

    public List<OutboxEntry> pendingForReceiver(String receiver, int limit) {
        String sql = """
                SELECT * FROM outbound_messages
                WHERE receiver = ? AND state NOT IN ('DELIVERED', 'DEAD_LETTER')
                ORDER BY created_at ASC
                LIMIT ?
                """;
        List<OutboxEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, receiver);
            ps.setInt(2, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) entries.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load receiver outbox entries", e);
        }
        return entries;
    }

    /**
     * IDs in outbox that are sitting in mailbox waiting for the receiver to pull.
     * The sender polls these against the mailbox to discover when receivers pick them up,
     * so the outbox state can be promoted from STORED_MAILBOX to DELIVERED.
     */
    public List<String> messageIdsAwaitingMailboxDelivery(int limit) {
        String sql = """
                SELECT message_id FROM outbound_messages
                WHERE state = 'STORED_MAILBOX'
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list pending mailbox deliveries", e);
        }
        return ids;
    }

    public String receiverFor(String messageId) {
        String sql = "SELECT receiver FROM outbound_messages WHERE message_id = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, messageId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read outbox receiver", e);
        }
    }

    public int cleanupDeliveredOlderThan(long cutoffMillis) {
        String sql = "DELETE FROM outbound_messages WHERE state = 'DELIVERED' AND updated_at < ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setLong(1, cutoffMillis);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cleanup delivered outbox", e);
        }
    }

    private void markInFlight(String messageId, String state, boolean direct) {
        long now = System.currentTimeMillis();
        String counter = direct ? "direct_attempt_count" : "mailbox_attempt_count";
        String sql = """
                UPDATE outbound_messages
                SET state = ?, attempt_count = attempt_count + 1,
                    %s = %s + 1, last_attempt_at = ?, updated_at = ?
                WHERE message_id = ?
                """.formatted(counter, counter);
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setString(4, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark outbox in-flight", e);
        }
    }

    private void markRetryable(String messageId, String code, String error, boolean directFailure) {
        OutboxEntry entry = get(messageId);
        boolean alreadyCounted = entry != null && entry.state != null && entry.state.endsWith("_IN_FLIGHT");
        int attempts = entry != null ? entry.attemptCount + (alreadyCounted ? 0 : 1) : 1;
        if (attempts >= Constants.OUTBOX_MAX_ATTEMPTS) {
            markDeadLetter(messageId, code, error);
            return;
        }
        long now = System.currentTimeMillis();
        long retryAt = now + backoffMs(attempts);
        long firstFailureAt = entry != null && entry.firstFailureAt > 0 ? entry.firstFailureAt : now;
        String sql = """
                UPDATE outbound_messages
                SET state = 'FAILED_RETRYABLE',
                    attempt_count = ?,
                    next_retry_at = ?,
                    first_failure_at = ?,
                    failure_code = ?,
                    last_error = ?,
                    updated_at = ?
                WHERE message_id = ?
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setInt(1, attempts);
            ps.setLong(2, retryAt);
            ps.setLong(3, firstFailureAt);
            ps.setString(4, code);
            ps.setString(5, error);
            ps.setLong(6, now);
            ps.setString(7, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark outbox retryable", e);
        }
    }

    private void updateState(String messageId, String state, String code, String error, long retryAt) {
        String sql = """
                UPDATE outbound_messages
                SET state = ?, failure_code = ?, last_error = ?, next_retry_at = ?, updated_at = ?
                WHERE message_id = ?
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setString(2, code);
            ps.setString(3, error);
            ps.setLong(4, retryAt);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update outbox state", e);
        }
    }

    private OutboxEntry get(String messageId) {
        String sql = "SELECT * FROM outbound_messages WHERE message_id = ? LIMIT 1";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, messageId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load outbox entry", e);
        }
    }

    private List<OutboxEntry> queryEntries(String sql, long now, int limit) {
        List<OutboxEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) entries.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load outbox retries", e);
        }
        return entries;
    }

    private long backoffMs(int attempts) {
        int exponent = Math.min(8, Math.max(0, attempts - 1));
        long base = Constants.OUTBOX_BACKOFF_BASE_MS * (1L << exponent);
        long capped = Math.min(base, Constants.OUTBOX_BACKOFF_MAX_MS);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1000, capped / 4));
        return capped + jitter;
    }

    private OutboxEntry map(ResultSet rs) throws SQLException {
        OutboxEntry entry = new OutboxEntry();
        entry.messageId = rs.getString("message_id");
        entry.receiver = rs.getString("receiver");
        entry.payloadJson = rs.getString("payload_json");
        entry.payloadHash = rs.getString("payload_hash");
        entry.state = rs.getString("state");
        entry.attemptCount = rs.getInt("attempt_count");
        entry.directAttemptCount = rs.getInt("direct_attempt_count");
        entry.mailboxAttemptCount = rs.getInt("mailbox_attempt_count");
        entry.nextRetryAt = rs.getLong("next_retry_at");
        entry.lastAttemptAt = rs.getLong("last_attempt_at");
        entry.firstFailureAt = rs.getLong("first_failure_at");
        entry.failureCode = rs.getString("failure_code");
        entry.lastError = rs.getString("last_error");
        return entry;
    }
}
