package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.database.DatabaseManager;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Bootstrap Cache — top 5 recently seen peers.
 * Lazy-updated on every successful send/receive.
 * Used as fallback when Bootstrap is unreachable.
 */
public class RecentPeersCache {

    private static final Logger logger = LoggerUtil.getLogger(RecentPeersCache.class.getName());
    private final DatabaseManager dbManager;

    public RecentPeersCache(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public static class RecentPeer {
        public final String username;
        public final String address;
        public final long lastSeen;

        public RecentPeer(String username, String address, long lastSeen) {
            this.username = username;
            this.address = address;
            this.lastSeen = lastSeen;
        }
    }

    /** Upsert peer on successful communication. */
    public void upsert(String username, String address) {
        String sql = "INSERT INTO recent_peers (username, address, last_seen, success_count) " +
                     "VALUES (?, ?, ?, 1) " +
                     "ON CONFLICT(username) DO UPDATE SET address=excluded.address, " +
                     "last_seen=excluded.last_seen, success_count=success_count+1";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, address);
            pstmt.setLong(3, System.currentTimeMillis());
            pstmt.executeUpdate();
            pruneToMax();
        } catch (SQLException e) {
            logger.fine("RecentPeersCache upsert failed: " + e.getMessage());
        }
    }

    /** Get cached address for username (may be null if not cached). */
    public String getAddress(String username) {
        String sql = "SELECT address FROM recent_peers WHERE username = ?";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("address");
        } catch (SQLException e) {
            logger.fine("RecentPeersCache lookup failed: " + e.getMessage());
        }
        return null;
    }

    /** Get all top-5 recent peers ordered by last_seen DESC. */
    public List<RecentPeer> getAll() {
        List<RecentPeer> result = new ArrayList<>();
        String sql = "SELECT username, address, last_seen FROM recent_peers ORDER BY last_seen DESC LIMIT ?";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, Constants.RECENT_PEERS_MAX);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(new RecentPeer(
                        rs.getString("username"),
                        rs.getString("address"),
                        rs.getLong("last_seen")));
            }
        } catch (SQLException e) {
            logger.fine("RecentPeersCache getAll failed: " + e.getMessage());
        }
        return result;
    }

    /** Keep only top-RECENT_PEERS_MAX entries. */
    private void pruneToMax() throws SQLException {
        String sql = "DELETE FROM recent_peers WHERE username NOT IN " +
                     "(SELECT username FROM recent_peers ORDER BY last_seen DESC LIMIT ?)";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, Constants.RECENT_PEERS_MAX);
            pstmt.executeUpdate();
        }
    }
}
