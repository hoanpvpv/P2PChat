package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * GroupCache — Data Plane cache at each peer.
 * Stores local copy of group membership for fast message sending.
 * Persisted to SQLite for restart recovery.
 */
public class GroupCache {

    private static final Logger logger = LoggerUtil.getLogger(GroupCache.class.getName());
    private final Map<String, GroupCacheEntry> cache = new ConcurrentHashMap<>();
    private final com.mycompany.p2pchat.database.DatabaseManager dbManager;

    public GroupCache(com.mycompany.p2pchat.database.DatabaseManager dbManager) {
        this.dbManager = dbManager;
        loadFromDb();
    }

    private void loadFromDb() {
        if (dbManager == null) return;
        String sql = "SELECT * FROM group_cache";
        try (java.sql.Connection conn = dbManager.getConnection();
             java.sql.PreparedStatement pstmt = conn.prepareStatement(sql);
             java.sql.ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                GroupCacheEntry e = new GroupCacheEntry();
                e.setGroupId(rs.getString("group_id"));
                e.setGroupName(rs.getString("group_name"));
                e.setOwner(rs.getString("owner"));
                e.setMembers(new ArrayList<>(Arrays.asList(rs.getString("members").split(","))));
                e.setCoordinators(new ArrayList<>(Arrays.asList(rs.getString("coordinators").split(","))));
                e.setLocalVersion(rs.getLong("version"));
                e.setGroupMode(rs.getString("group_mode"));
                e.setGroupState(rs.getString("group_state"));
                e.setLastUpdated(rs.getLong("last_updated"));
                cache.put(e.getGroupId(), e);
            }
            logger.info("Loaded " + cache.size() + " groups from db");
        } catch (Exception e) {
            logger.fine("Failed to load group cache from db: " + e.getMessage());
        }
    }

    private void persist(GroupCacheEntry e) {
        if (dbManager == null) return;
        String sql = "INSERT OR REPLACE INTO group_cache (group_id, group_name, owner, members, coordinators, version, group_mode, group_state, last_updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (java.sql.Connection conn = dbManager.getConnection();
             java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, e.getGroupId());
            pstmt.setString(2, e.getGroupName());
            pstmt.setString(3, e.getOwner());
            pstmt.setString(4, String.join(",", e.getMembers()));
            pstmt.setString(5, String.join(",", e.getCoordinators()));
            pstmt.setLong(6, e.getLocalVersion());
            pstmt.setString(7, e.getGroupMode());
            pstmt.setString(8, e.getGroupState());
            pstmt.setLong(9, e.getLastUpdated());
            pstmt.executeUpdate();
        } catch (Exception ex) {
            logger.fine("Failed to persist group cache: " + ex.getMessage());
        }
    }

    private void deleteFromDb(String groupId) {
        if (dbManager == null) return;
        String sql = "DELETE FROM group_cache WHERE group_id = ?";
        try (java.sql.Connection conn = dbManager.getConnection();
             java.sql.PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, groupId);
            pstmt.executeUpdate();
        } catch (Exception ex) {
            logger.fine("Failed to delete group cache: " + ex.getMessage());
        }
    }

    public static class GroupCacheEntry {
        private String groupId;
        private String groupName;
        private String owner;
        private List<String> members;
        private List<String> coordinators;
        private long localVersion;
        private long lastUpdated;
        private String groupState;  // "ACTIVE" | "LEAVING"
        private String groupMode;   // "OPEN" | "RESTRICTED"

        public GroupCacheEntry() {
            this.groupState = "ACTIVE";
            this.groupMode = "OPEN";
            this.lastUpdated = System.currentTimeMillis();
        }

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }

        public String getGroupName() { return groupName; }
        public void setGroupName(String groupName) { this.groupName = groupName; }

        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }

        public List<String> getMembers() { return members; }
        public void setMembers(List<String> members) { this.members = members; }

        public List<String> getCoordinators() { return coordinators; }
        public void setCoordinators(List<String> coordinators) { this.coordinators = coordinators; }

        public long getLocalVersion() { return localVersion; }
        public void setLocalVersion(long localVersion) { this.localVersion = localVersion; }

        public long getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }

        public String getGroupState() { return groupState; }
        public void setGroupState(String groupState) { this.groupState = groupState; }

        public String getGroupMode() { return groupMode; }
        public void setGroupMode(String groupMode) { this.groupMode = groupMode; }
    }

    /**
     * Update cache from a GroupInfo (received from coordinator or gossip).
     */
    public void updateFromGroupInfo(GroupInfo info) {
        GroupCacheEntry entry = cache.computeIfAbsent(info.getGroupId(), k -> new GroupCacheEntry());
        entry.setGroupId(info.getGroupId());
        entry.setGroupName(info.getGroupName());
        entry.setOwner(info.getOwner());
        entry.setMembers(new ArrayList<>(info.getMembers()));
        entry.setCoordinators(HRWHash.topK(info.getMembers(), info.getGroupId(), Constants.COORDINATOR_K));
        entry.setLocalVersion(info.getVersion());
        entry.setLastUpdated(System.currentTimeMillis());
        entry.setGroupMode(info.getGroupMode());
        entry.setGroupState("ACTIVE");
        persist(entry);
        logger.info("GroupCache updated: " + info.getGroupId() + " v=" + info.getVersion());
    }

    public GroupCacheEntry get(String groupId) {
        return cache.get(groupId);
    }

    public void put(String groupId, GroupCacheEntry entry) {
        cache.put(groupId, entry);
        persist(entry);
    }

    public void remove(String groupId) {
        cache.remove(groupId);
        deleteFromDb(groupId);
        logger.info("GroupCache removed: " + groupId);
    }

    public void setState(String groupId, String state) {
        GroupCacheEntry entry = cache.get(groupId);
        if (entry != null) {
            entry.setGroupState(state);
            persist(entry);
        }
    }

    public Collection<GroupCacheEntry> getAllEntries() {
        return cache.values();
    }

    public Set<String> getAllGroupIds() {
        return cache.keySet();
    }

    public boolean containsGroup(String groupId) {
        return cache.containsKey(groupId);
    }

    public int size() {
        return cache.size();
    }

    /**
     * Get all groups where state is ACTIVE.
     */
    public List<GroupCacheEntry> getActiveGroups() {
        return cache.values().stream()
                .filter(e -> "ACTIVE".equals(e.getGroupState()))
                .toList();
    }
}
