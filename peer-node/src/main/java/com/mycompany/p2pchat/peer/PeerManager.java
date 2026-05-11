package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.database.DatabaseManager;
import com.mycompany.p2pchat.database.MessageRepository;
import com.mycompany.p2pchat.model.ChatGroup;
import com.mycompany.p2pchat.model.PeerInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

public class PeerManager {

    private final Map<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();
    private final Map<String, ChatGroup> chatGroups = new ConcurrentHashMap<>();
    private final DatabaseManager dbManager;
    private final MessageRepository messageRepository;
    private String bootstrapHost;
    private int bootstrapPort;
    private String localUsername;

    public PeerManager(String dbName) {
        this.dbManager = new DatabaseManager(dbName);
        this.dbManager.init();
        this.messageRepository = new MessageRepository(dbManager);
    }

    public void addKnownPeer(PeerInfo peer) {
        PeerInfo existing = knownPeers.get(peer.getUsername());
        if (existing != null) {
            existing.setHost(peer.getHost());
            existing.setPort(peer.getPort());
            existing.setOnline(true);
            existing.setLastHeartbeat(System.currentTimeMillis());
        } else {
            knownPeers.put(peer.getUsername(), peer);
        }
    }

    public void removeKnownPeer(String username) {
        PeerInfo peer = knownPeers.get(username);
        if (peer != null) {
            peer.setOnline(false);
        }
    }

    public PeerInfo getPeer(String username) {
        return knownPeers.get(username);
    }

    public List<PeerInfo> getOnlinePeers() {
        return knownPeers.values().stream()
                .filter(PeerInfo::isOnline)
                .toList();
    }

    public List<PeerInfo> getAllKnownPeers() {
        return new ArrayList<>(knownPeers.values());
    }

    public void parsePeerList(String peerListStr) {
        if (peerListStr == null || peerListStr.isEmpty()) return;
        String[] entries = peerListStr.split(",");
        for (String entry : entries) {
            if (entry.trim().isEmpty()) continue;
            String[] parts = entry.split("@");
            if (parts.length != 2) continue;
            String username = parts[0].trim();
            String[] addr = parts[1].split(":");
            if (addr.length != 2) continue;
            String host = addr[0].trim();
            int port = Integer.parseInt(addr[1].trim());
            if (!username.equals(localUsername)) {
                PeerInfo peer = new PeerInfo(username, host, port);
                addKnownPeer(peer);
            }
        }
    }

    public void createGroup(String groupName) {
        if (chatGroups.containsKey(groupName)) {
            System.out.println("[ERROR] Group already exists: " + groupName);
            return;
        }
        ChatGroup group = new ChatGroup(groupName, localUsername);
        chatGroups.put(groupName, group);
        System.out.println("[GROUP] Created group: " + groupName);
    }

    public void addToGroup(String groupName, String username) {
        ChatGroup group = chatGroups.get(groupName);
        if (group == null) {
            System.out.println("[ERROR] Group not found: " + groupName);
            return;
        }
        if (!knownPeers.containsKey(username)) {
            System.out.println("[ERROR] Peer not found: " + username);
            return;
        }
        group.addMember(username);
        System.out.println("[GROUP] Added " + username + " to " + groupName);
    }

    public ChatGroup getChatGroup(String groupName) {
        return chatGroups.get(groupName);
    }

    public Map<String, ChatGroup> getAllGroups() {
        return new HashMap<>(chatGroups);
    }

    public void listGroups() {
        if (chatGroups.isEmpty()) {
            System.out.println("No groups.");
            return;
        }
        for (ChatGroup group : chatGroups.values()) {
            System.out.println("  " + group.getGroupName() + ": " + group.getMembers());
        }
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    public DatabaseManager getDbManager() {
        return dbManager;
    }

    public String getBootstrapHost() { return bootstrapHost; }
    public void setBootstrapHost(String bootstrapHost) { this.bootstrapHost = bootstrapHost; }

    public int getBootstrapPort() { return bootstrapPort; }
    public void setBootstrapPort(int bootstrapPort) { this.bootstrapPort = bootstrapPort; }

    public String getLocalUsername() { return localUsername; }
    public void setLocalUsername(String localUsername) { this.localUsername = localUsername; }

    public void shutdown() {
        dbManager.close();
    }
}
