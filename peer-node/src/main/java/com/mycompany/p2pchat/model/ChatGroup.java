package com.mycompany.p2pchat.model;

import java.util.ArrayList;
import java.util.List;

public class ChatGroup {
    private String groupName;
    private String owner;
    private List<String> members;

    public ChatGroup() {
        this.members = new ArrayList<>();
    }

    public ChatGroup(String groupName, String owner) {
        this.groupName = groupName;
        this.owner = owner;
        this.members = new ArrayList<>();
        this.members.add(owner);
    }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public List<String> getMembers() { return members; }
    public void setMembers(List<String> members) { this.members = members; }

    public void addMember(String username) {
        if (!members.contains(username)) {
            members.add(username);
        }
    }

    public void removeMember(String username) {
        members.remove(username);
    }

    public boolean hasMember(String username) {
        return members.contains(username);
    }

    @Override
    public String toString() {
        return "ChatGroup{" + groupName + ", members=" + members + "}";
    }
}
