package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.database.DatabaseManager;
import com.mycompany.p2pchat.database.MessageRepository;
import com.mycompany.p2pchat.model.ChatGroup;
import static com.mycompany.p2pchat.utils.Constants.HEARTBEAT_RESUME_THRESHOLD;
import com.mycompany.p2pchat.model.PeerInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PeerManager {

    private final Map<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();
    private final Map<String, ChatGroup> chatGroups = new ConcurrentHashMap<>();  // legacy, kept for CLI compat
    private final DatabaseManager dbManager;
    private final MessageRepository messageRepository;

    // DHT-lite components
    private final GroupCache groupCache;
    private final LamportClock lamportClock;
    private final RecentPeersCache recentPeersCache;
    private LazyRepairManager lazyRepairManager;

    // Peer identity
    private String bootstrapHost;
    private int bootstrapPort;
    private String localUsername;
    private String localHost;
    private int localPort;
    private int webPort;
    private volatile boolean registeredToBootstrap;
    private volatile String lastBootstrapError;
    private volatile long lastHeartbeatSuccess = 0;

    public PeerManager(String dbName) {
        this.dbManager = new DatabaseManager(dbName);
        this.dbManager.init();
        this.messageRepository = new MessageRepository(dbManager);
        this.groupCache = new GroupCache();
        this.lamportClock = new LamportClock();
        this.recentPeersCache = new RecentPeersCache(dbManager);
    }

    // ==================== Peer State ====================

    public String getLocalAddress() {
        return localHost + ":" + localPort;
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
        if (peer != null) peer.setOnline(false);
    }

    public PeerInfo getPeer(String username) {
        return knownPeers.get(username);
    }

    public List<PeerInfo> getOnlinePeers() {
        return knownPeers.values().stream().filter(PeerInfo::isOnline).toList();
    }

    public List<PeerInfo> getAllKnownPeers() {
        return new ArrayList<>(knownPeers.values());
    }

    public void parsePeerList(String peerListStr) {
        knownPeers.values().forEach(peer -> peer.setOnline(false));
        if (peerListStr == null || peerListStr.isEmpty()) return;
        for (String entry : peerListStr.split(",")) {
            if (entry.trim().isEmpty()) continue;
            String[] parts = entry.split("@");
            if (parts.length != 2) continue;
            String username = parts[0].trim();
            String[] addr = parts[1].split(":");
            if (addr.length != 2) continue;
            int port;
            try { port = Integer.parseInt(addr[1].trim()); } catch (NumberFormatException e) { continue; }
            if (!username.equals(localUsername)) {
                addKnownPeer(new PeerInfo(username, addr[0].trim(), port));
            }
        }
    }

    // ==================== Legacy Group Support (CLI) ====================

    public void createGroup(String groupName) {
        chatGroups.computeIfAbsent(groupName, n -> new ChatGroup(n, localUsername));
    }

    public void addToGroup(String groupName, String username) {
        ChatGroup g = chatGroups.get(groupName);
        if (g != null) g.addMember(username);
    }

    public ChatGroup getChatGroup(String groupName) {
        return chatGroups.get(groupName);
    }

    public Map<String, ChatGroup> getAllGroups() {
        return new HashMap<>(chatGroups);
    }

    public void listGroups() {
        chatGroups.forEach((name, g) -> System.out.println("  " + name + ": " + g.getMembers()));
    }

    // ==================== Heartbeat tracking ====================

    public void markHeartbeatSuccess() {
        boolean wasDown = !registeredToBootstrap ||
                (System.currentTimeMillis() - lastHeartbeatSuccess > HEARTBEAT_RESUME_THRESHOLD);
        this.registeredToBootstrap = true;
        this.lastBootstrapError = null;
        long prev = lastHeartbeatSuccess;
        this.lastHeartbeatSuccess = System.currentTimeMillis();
        // Trigger 4: heartbeat resumed after >10s outage
        if (wasDown && prev > 0 && lazyRepairManager != null) {
            lazyRepairManager.repairOnHeartbeatResumed();
        }
    }

    public void markBootstrapRegistrationSuccess() {
        markHeartbeatSuccess();
    }

    public void markBootstrapRegistrationFailure(String error) {
        this.registeredToBootstrap = false;
        this.lastBootstrapError = error;
    }

    public void clearKnownPeers() {
        knownPeers.clear();
    }

    // ==================== Getters/Setters ====================

    public GroupCache getGroupCache() { return groupCache; }
    public LamportClock getLamportClock() { return lamportClock; }
    public RecentPeersCache getRecentPeersCache() { return recentPeersCache; }

    public LazyRepairManager getLazyRepairManager() { return lazyRepairManager; }
    public void setLazyRepairManager(LazyRepairManager m) { this.lazyRepairManager = m; }

    public MessageRepository getMessageRepository() { return messageRepository; }
    public DatabaseManager getDbManager() { return dbManager; }

    public String getBootstrapHost() { return bootstrapHost; }
    public void setBootstrapHost(String h) { this.bootstrapHost = h; }

    public int getBootstrapPort() { return bootstrapPort; }
    public void setBootstrapPort(int p) { this.bootstrapPort = p; }

    public String getLocalUsername() { return localUsername; }
    public void setLocalUsername(String u) { this.localUsername = u; }

    public String getLocalHost() { return localHost; }
    public void setLocalHost(String h) { this.localHost = h; }

    public int getLocalPort() { return localPort; }
    public void setLocalPort(int p) { this.localPort = p; }

    public int getWebPort() { return webPort; }
    public void setWebPort(int p) { this.webPort = p; }

    public boolean isRegisteredToBootstrap() { return registeredToBootstrap; }
    public String getLastBootstrapError() { return lastBootstrapError; }

    public void shutdown() { dbManager.close(); }
}
