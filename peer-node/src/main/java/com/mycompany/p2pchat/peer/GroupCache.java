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
        logger.info("GroupCache updated: " + info.getGroupId() + " v=" + info.getVersion());
    }

    public GroupCacheEntry get(String groupId) {
        return cache.get(groupId);
    }

    public void put(String groupId, GroupCacheEntry entry) {
        cache.put(groupId, entry);
    }

    public void remove(String groupId) {
        cache.remove(groupId);
        logger.info("GroupCache removed: " + groupId);
    }

    public void setState(String groupId, String state) {
        GroupCacheEntry entry = cache.get(groupId);
        if (entry != null) {
            entry.setGroupState(state);
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
