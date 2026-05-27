package com.mycompany.p2pchat.database;

import com.mycompany.p2pchat.utils.Constants;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RelayRepository {
    public static class RelayEnvelope {
        public String messageId;
        public String sender;
        public String receiver;
        public String payloadJson;
        public String payloadHash;
        public int generation;
        public String role;
        public long leaseUntil;
        public long expiresAt;
    }

    public static class RelayMessage {
        public String messageId;
        public String sender;
        public String receiver;
        public String payloadJson;
        public String payloadHash;
        public int generation;
        public String role;
        public String status;
        public long leaseUntil;
        public long expiresAt;
        public int retryCount;
    }

    public static class RelayAssignment {
        public String messageId;
        public String relayPeer;
        public int generation;
        public String role;
        public String status;
        public long leaseUntil;
    }

    private final DatabaseManager dbManager;

    public RelayRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public boolean hasTombstone(String messageId) {
        expireTombstones();
        String sql = "SELECT 1 FROM relay_tombstones WHERE message_id = ? AND expires_at > ? LIMIT 1";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.setLong(2, System.currentTimeMillis());
            return ps.executeQuery().next();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check relay tombstone", e);
        }
    }

    public boolean storeRelayMessage(RelayEnvelope env) {
        if (env == null || env.messageId == null || env.messageId.isBlank()) return false;
        if (hasTombstone(env.messageId)) return true;
        long now = System.currentTimeMillis();
        String existingHash = payloadHash(env.messageId);
        if (existingHash != null) {
            if (!existingHash.equals(env.payloadHash)) {
                throw new IllegalArgumentException("Relay payload conflict for " + env.messageId);
            }
            String sql = """
                    UPDATE relay_messages
                    SET lease_until = MAX(lease_until, ?), expires_at = MAX(expires_at, ?),
                        generation = MAX(generation, ?), role = CASE WHEN role = 'PRIMARY' THEN role ELSE ? END,
                        status = CASE WHEN status IN ('DELIVERED','TOMBSTONED') THEN status ELSE 'STORED' END
                    WHERE message_id = ?
                    """;
            try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
                ps.setLong(1, env.leaseUntil);
                ps.setLong(2, env.expiresAt);
                ps.setInt(3, env.generation);
                ps.setString(4, env.role == null ? "BACKUP" : env.role);
                ps.setString(5, env.messageId);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to refresh relay message", e);
            }
        }

        String sql = """
                INSERT INTO relay_messages
                (message_id, sender, receiver, payload_json, payload_hash, generation, role, status,
                 stored_at, lease_until, expires_at, next_retry_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'STORED', ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, env.messageId);
            ps.setString(2, env.sender);
            ps.setString(3, env.receiver);
            ps.setString(4, env.payloadJson);
            ps.setString(5, env.payloadHash);
            ps.setInt(6, Math.max(1, env.generation));
            ps.setString(7, env.role == null ? "BACKUP" : env.role);
            ps.setLong(8, now);
            ps.setLong(9, env.leaseUntil);
            ps.setLong(10, env.expiresAt);
            ps.setLong(11, now + Constants.RELAY_FORWARD_INITIAL_DELAY_MS);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store relay message", e);
        }
    }

    private String payloadHash(String messageId) {
        String sql = "SELECT payload_hash FROM relay_messages WHERE message_id = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, messageId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read relay message", e);
        }
    }

    public void saveAssignment(String messageId, String relayPeer, int generation, String role, long leaseUntil) {
        String sql = """
                INSERT INTO relay_assignments
                (message_id, relay_peer, generation, role, status, assigned_at, lease_until)
                VALUES (?, ?, ?, ?, 'STORE_ACKED', ?, ?)
                ON CONFLICT(message_id, relay_peer, generation) DO UPDATE SET
                role = excluded.role, status = 'STORE_ACKED', lease_until = excluded.lease_until, last_ack_at = excluded.assigned_at
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, messageId);
            ps.setString(2, relayPeer);
            ps.setInt(3, generation);
            ps.setString(4, role);
            ps.setLong(5, now);
            ps.setLong(6, leaseUntil);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save relay assignment", e);
        }
    }

    public List<RelayAssignment> assignmentsNeedingRotation(long now, int limit) {
        String sql = """
                SELECT * FROM relay_assignments
                WHERE status IN ('STORE_ACKED', 'ASSIGNED') AND lease_until <= ?
                ORDER BY lease_until ASC LIMIT ?
                """;
        List<RelayAssignment> out = new ArrayList<>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(mapAssignment(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load relay assignments", e);
        }
        return out;
    }

    public int activeAssignmentCount(String messageId) {
        String sql = "SELECT COUNT(*) FROM relay_assignments WHERE message_id = ? AND status = 'STORE_ACKED' AND lease_until > ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.setLong(2, System.currentTimeMillis());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count relay assignments", e);
        }
    }

    public Set<String> assignedRelayPeers(String messageId) {
        Set<String> peers = new HashSet<>();
        String sql = """
                SELECT relay_peer FROM relay_assignments
                WHERE message_id = ? AND status NOT IN ('DELIVERED', 'UNREACHABLE')
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, messageId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) peers.add(rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read relay assignment peers", e);
        }
        return peers;
    }

    public void markAssignmentUnreachable(String messageId, String relayPeer, int generation, String reason) {
        markRelayCooldown(relayPeer, reason);
        String sql = """
                INSERT INTO relay_assignments
                (message_id, relay_peer, generation, role, status, assigned_at, lease_until)
                VALUES (?, ?, ?, 'BACKUP', 'UNREACHABLE', ?, ?)
                ON CONFLICT(message_id, relay_peer, generation) DO UPDATE SET status = 'UNREACHABLE'
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, messageId);
            ps.setString(2, relayPeer);
            ps.setInt(3, Math.max(1, generation));
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark relay unreachable", e);
        }
    }

    public boolean isRelayInCooldown(String relayPeer) {
        cleanupCooldowns();
        String sql = "SELECT 1 FROM relay_peer_cooldowns WHERE relay_peer = ? AND cooldown_until > ? LIMIT 1";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, relayPeer);
            ps.setLong(2, System.currentTimeMillis());
            return ps.executeQuery().next();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check relay cooldown", e);
        }
    }

    public void markRelayCooldown(String relayPeer, String reason) {
        if (relayPeer == null || relayPeer.isBlank()) return;
        String sql = """
                INSERT INTO relay_peer_cooldowns (relay_peer, failed_at, cooldown_until, reason)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(relay_peer) DO UPDATE SET
                failed_at = excluded.failed_at, cooldown_until = excluded.cooldown_until, reason = excluded.reason
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, relayPeer);
            ps.setLong(2, now);
            ps.setLong(3, now + Constants.RELAY_COOLDOWN_MS);
            ps.setString(4, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save relay cooldown", e);
        }
    }

    public void cleanupCooldowns() {
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "DELETE FROM relay_peer_cooldowns WHERE cooldown_until <= ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cleanup relay cooldowns", e);
        }
    }

    public void supersedeExpiredAssignments(long now) {
        String sql = "UPDATE relay_assignments SET status = 'LEASE_EXPIRED' WHERE status = 'STORE_ACKED' AND lease_until <= ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to supersede relay assignments", e);
        }
    }

    public void markDelivered(String messageId, String payloadHash) {
        long now = System.currentTimeMillis();
        addTombstone(messageId, payloadHash, "DELIVERED");
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "UPDATE relay_messages SET status = 'DELIVERED', delivered_at = ? WHERE message_id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark relay message delivered", e);
        }
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "UPDATE relay_assignments SET status = 'DELIVERED', last_ack_at = ? WHERE message_id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark relay assignment delivered", e);
        }
    }

    public void markSuperseded(String messageId) {
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "UPDATE relay_messages SET status = 'SUPERSEDED', next_retry_at = ? WHERE message_id = ? AND status = 'STORED'")) {
            ps.setLong(1, System.currentTimeMillis() + Constants.RELAY_SUPERSEDED_GRACE_MS);
            ps.setString(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark relay message superseded", e);
        }
    }

    public void addTombstone(String messageId, String payloadHash, String finalStatus) {
        String sql = """
                INSERT INTO relay_tombstones (message_id, payload_hash, final_status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(message_id) DO UPDATE SET
                final_status = excluded.final_status, expires_at = MAX(relay_tombstones.expires_at, excluded.expires_at)
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, messageId);
            ps.setString(2, payloadHash);
            ps.setString(3, finalStatus);
            ps.setLong(4, now);
            ps.setLong(5, now + Constants.RELAY_TOMBSTONE_TTL_MS);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save relay tombstone", e);
        }
    }

    public List<RelayMessage> dueForForward(int limit) {
        expireRelayMessages();
        String sql = """
                SELECT * FROM relay_messages
                WHERE status = 'STORED' AND next_retry_at <= ? AND expires_at > ?
                  AND (role = 'PRIMARY' OR lease_until <= ?)
                ORDER BY next_retry_at ASC LIMIT ?
                """;
        List<RelayMessage> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setInt(4, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(mapMessage(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load relay forwards", e);
        }
        return out;
    }

    public List<RelayMessage> pendingForReceiver(String receiver, int limit) {
        expireRelayMessages();
        String sql = """
                SELECT * FROM relay_messages
                WHERE receiver = ? AND status IN ('STORED', 'SUPERSEDED') AND expires_at > ?
                ORDER BY stored_at ASC LIMIT ?
                """;
        List<RelayMessage> out = new ArrayList<>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, receiver);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(mapMessage(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to pull relay messages", e);
        }
        return out;
    }

    public void markForwardAttempt(String messageId, boolean delivered) {
        if (delivered) {
            markDelivered(messageId, payloadHash(messageId));
            return;
        }
        String sql = """
                UPDATE relay_messages
                SET retry_count = retry_count + 1, last_attempt_at = ?, next_retry_at = ?
                WHERE message_id = ?
                """;
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            int retryCount = retryCount(messageId) + 1;
            long delay = Math.min(Constants.RELAY_FORWARD_BACKOFF_MAX_MS,
                    Constants.RELAY_FORWARD_INITIAL_DELAY_MS * (1L << Math.min(4, retryCount)));
            ps.setLong(1, now);
            ps.setLong(2, now + delay);
            ps.setString(3, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark relay forward attempt", e);
        }
    }

    private int retryCount(String messageId) throws SQLException {
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "SELECT retry_count FROM relay_messages WHERE message_id = ?")) {
            ps.setString(1, messageId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void expireRelayMessages() {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "UPDATE relay_messages SET status = 'EXPIRED' WHERE status = 'STORED' AND expires_at <= ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to expire relay messages", e);
        }
    }

    public void expireTombstones() {
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "DELETE FROM relay_tombstones WHERE expires_at <= ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to expire relay tombstones", e);
        }
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        expireRelayMessages();
        expireTombstones();
        cleanupCooldowns();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "DELETE FROM relay_messages WHERE status = 'DELIVERED' AND delivered_at > 0 AND delivered_at <= ?")) {
            ps.setLong(1, now - Constants.RELAY_DELIVERED_CLEANUP_MS);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cleanup delivered relay messages", e);
        }
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "DELETE FROM relay_messages WHERE status IN ('EXPIRED','TOMBSTONED') AND expires_at <= ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cleanup expired relay messages", e);
        }
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "DELETE FROM relay_messages WHERE status = 'SUPERSEDED' AND next_retry_at <= ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cleanup relay messages", e);
        }
    }

    private RelayAssignment mapAssignment(ResultSet rs) throws SQLException {
        RelayAssignment a = new RelayAssignment();
        a.messageId = rs.getString("message_id");
        a.relayPeer = rs.getString("relay_peer");
        a.generation = rs.getInt("generation");
        a.role = rs.getString("role");
        a.status = rs.getString("status");
        a.leaseUntil = rs.getLong("lease_until");
        return a;
    }

    private RelayMessage mapMessage(ResultSet rs) throws SQLException {
        RelayMessage m = new RelayMessage();
        m.messageId = rs.getString("message_id");
        m.sender = rs.getString("sender");
        m.receiver = rs.getString("receiver");
        m.payloadJson = rs.getString("payload_json");
        m.payloadHash = rs.getString("payload_hash");
        m.generation = rs.getInt("generation");
        m.role = rs.getString("role");
        m.status = rs.getString("status");
        m.leaseUntil = rs.getLong("lease_until");
        m.expiresAt = rs.getLong("expires_at");
        m.retryCount = rs.getInt("retry_count");
        return m;
    }
}
