package com.mycompany.p2pchat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GroupInfo — source of truth for group state, stored at Coordinators.
 * Coordinators are NOT stored in GroupInfo; they are computed from members + groupId via HRW.
 */
public class GroupInfo {
    private String groupId;
    private String groupName;
    private String owner;              // address, always = members[0]
    private List<String> members;      // ordered by join time, owner at index 0
    private long version;              // Lamport clock, incremented on every state change
    private long createdAt;
    private String groupMode;          // "OPEN" or "RESTRICTED"

    public GroupInfo() {
        this.members = new ArrayList<>();
        this.version = 0;
        this.createdAt = System.currentTimeMillis();
        this.groupMode = "OPEN";
    }

    public GroupInfo(String groupName, String owner) {
        this();
        this.groupId = "grp-" + UUID.randomUUID().toString().substring(0, 8);
        this.groupName = groupName;
        this.owner = owner;
        this.members.add(owner);
        this.version = 1;
    }

    public static GroupInfo create(String groupName, String ownerAddress, List<String> memberAddresses) {
        GroupInfo info = new GroupInfo();
        info.groupId = "grp-" + UUID.randomUUID().toString().substring(0, 8);
        info.groupName = groupName;
        info.owner = ownerAddress;
        info.members = new ArrayList<>();
        info.members.add(ownerAddress);  // owner always at index 0
        for (String m : memberAddresses) {
            if (!m.equals(ownerAddress) && !info.members.contains(m)) {
                info.members.add(m);
            }
        }
        info.version = 1;
        info.createdAt = System.currentTimeMillis();
        info.groupMode = "OPEN";
        return info;
    }

    // Getters and setters
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public List<String> getMembers() { return members; }
    public void setMembers(List<String> members) { this.members = members; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public String getGroupMode() { return groupMode; }
    public void setGroupMode(String groupMode) { this.groupMode = groupMode; }

    public void incrementVersion() { this.version++; }

    public boolean hasMember(String address) {
        return members.contains(address);
    }

    public void addMember(String address) {
        if (!members.contains(address)) {
            members.add(address);
        }
    }

    public void removeMember(String address) {
        members.remove(address);
    }

    /**
     * Deep copy for gossip/sync operations.
     */
    public GroupInfo copy() {
        GroupInfo copy = new GroupInfo();
        copy.groupId = this.groupId;
        copy.groupName = this.groupName;
        copy.owner = this.owner;
        copy.members = new ArrayList<>(this.members);
        copy.version = this.version;
        copy.createdAt = this.createdAt;
        copy.groupMode = this.groupMode;
        return copy;
    }

    @Override
    public String toString() {
        return "GroupInfo{" + groupId + ", name=" + groupName + ", owner=" + owner +
               ", members=" + members + ", v=" + version + ", mode=" + groupMode + "}";
    }
}
