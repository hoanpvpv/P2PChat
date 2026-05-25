package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.database.DatabaseManager;
import com.mycompany.p2pchat.database.MessageRepository;
import com.mycompany.p2pchat.model.ChatGroup;
import static com.mycompany.p2pchat.utils.Constants.HEARTBEAT_RESUME_THRESHOLD;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PeerManager {

    private static final Logger logger = LoggerUtil.getLogger(PeerManager.class.getName());

    private final Map<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();
    private final Map<String, ChatGroup> chatGroups = new ConcurrentHashMap<>();  // legacy, kept for CLI compat
    private final DatabaseManager dbManager;
    private final MessageRepository messageRepository;

    // DHT-lite components
    private final GroupCache groupCache;
    private final LamportClock lamportClock;
    private final RecentPeersCache recentPeersCache;
    private final com.mycompany.p2pchat.database.OutboxRepository outboxRepository;
    private LazyRepairManager lazyRepairManager;
    private final com.mycompany.p2pchat.filetransfer.FileTransferManager fileTransferManager;
    private CoordinatorManager coordinatorManager;
    private PeerServer peerServer;

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
    private String mailboxHost = com.mycompany.p2pchat.utils.Constants.DEFAULT_MAILBOX_HOST;
    private int mailboxPort = com.mycompany.p2pchat.utils.Constants.DEFAULT_MAILBOX_PORT;
    private String localPublicKey;
    private String localKeyId;

    public PeerManager(String dbName) {
        this.dbManager = new DatabaseManager(dbName);
        this.dbManager.init();
        this.messageRepository = new MessageRepository(dbManager);
        this.groupCache = new GroupCache(dbManager);
        this.lamportClock = new LamportClock();
        this.recentPeersCache = new RecentPeersCache(dbManager);
        this.outboxRepository = new com.mycompany.p2pchat.database.OutboxRepository(dbManager);
        this.fileTransferManager = new com.mycompany.p2pchat.filetransfer.FileTransferManager(this);
        loadKnownPeersFromDb();
    }

    // ==================== Peer State ====================

    public String getLocalAddress() {
        return localHost + ":" + localPort;
    }

    public void addKnownPeer(PeerInfo peer) {
        if (peer == null || peer.getUsername() == null || peer.getUsername().isBlank()) return;
        if (peer.getUsername().equals(localUsername)) return;
        PeerInfo existing = knownPeers.get(peer.getUsername());
        if (existing != null) {
            if (!(isDockerBridgeIp(peer.getHost()) && isTailscaleIp(existing.getHost()))) {
                existing.setHost(peer.getHost());
            }
            existing.setPort(peer.getPort());
            existing.setOnline(peer.isOnline());
            existing.setLastHeartbeat(System.currentTimeMillis());
            if (!isBlank(peer.getPublicKey())) existing.setPublicKey(peer.getPublicKey());
            if (!isBlank(peer.getKeyId())) existing.setKeyId(peer.getKeyId());
            persistKnownPeer(existing);
        } else {
            peer.setLastHeartbeat(System.currentTimeMillis());
            knownPeers.put(peer.getUsername(), peer);
            persistKnownPeer(peer);
        }
    }

    public void learnReachablePeerHost(String username, String observedHost) {
        if (username == null || username.isBlank() || username.equals(localUsername)) return;
        if (!isTailscaleIp(observedHost)) return;
        PeerInfo existing = knownPeers.get(username);
        if (existing == null || observedHost.equals(existing.getHost())) return;
        if (isDockerBridgeIp(existing.getHost()) || existing.getHost() == null || existing.getHost().isBlank()) {
            existing.setHost(observedHost);
            existing.setOnline(true);
            existing.setLastHeartbeat(System.currentTimeMillis());
            persistKnownPeer(existing);
        }
    }

    public void removeKnownPeer(String username) {
        PeerInfo peer = knownPeers.get(username);
        if (peer != null) {
            peer.setOnline(false);
            persistKnownPeer(peer);
        } else if (username != null && !username.isBlank()) {
            markKnownPeerOffline(username);
        }
    }

    public PeerInfo getPeer(String username) {
        return knownPeers.get(username);
    }

    public String resolveUsername(String address) {
        if (address == null || address.isEmpty()) return "";
        if (address.equals(getLocalAddress())) return getLocalUsername();
        for (PeerInfo p : knownPeers.values()) {
            if (address.equals(p.getAddress())) return p.getUsername();
        }
        for (var p : getRecentPeersCache().getAll()) {
            if (address.equals(p.address)) return p.username;
        }
        return address;
    }

    public java.util.List<String> mapToUsernames(java.util.List<String> addresses) {
        if (addresses == null) return new ArrayList<>();
        return addresses.stream().map(this::resolveUsername).collect(java.util.stream.Collectors.toList());
    }

    public List<PeerInfo> getOnlinePeers() {
        return knownPeers.values().stream().filter(PeerInfo::isOnline).toList();
    }

    public List<PeerInfo> getAllKnownPeers() {
        return new ArrayList<>(knownPeers.values());
    }

    public void parsePeerList(String peerListStr) {
        knownPeers.values().forEach(peer -> {
            peer.setOnline(false);
            persistKnownPeer(peer);
        });
        if (peerListStr == null || peerListStr.isEmpty()) return;
        for (String entry : peerListStr.split(",")) {
            if (entry.trim().isEmpty()) continue;
            String[] parts = entry.split("@");
            if (parts.length != 2) continue;
            String username = parts[0].trim();
            String[] keyParts = parts[1].split("\\|", -1);
            String[] addr = keyParts[0].split(":");
            if (addr.length != 2) continue;
            int port;
            try { port = Integer.parseInt(addr[1].trim()); } catch (NumberFormatException e) { continue; }
            if (!username.equals(localUsername)) {
                PeerInfo peer = new PeerInfo(username, addr[0].trim(), port);
                if (keyParts.length > 1 && !keyParts[1].isBlank()) peer.setKeyId(keyParts[1]);
                if (keyParts.length > 2 && !keyParts[2].isBlank()) peer.setPublicKey(keyParts[2]);
                if (keyParts.length > 3 && "offline".equalsIgnoreCase(keyParts[3].trim())) peer.setOnline(false);
                addKnownPeer(peer);
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

    public void markBootstrapTransientFailure(String error) {
        this.lastBootstrapError = error;
    }

    public boolean hasLocalIdentity() {
        return localUsername != null && !localUsername.isBlank() && localPort > 0;
    }

    public void clearKnownPeers() {
        knownPeers.clear();
        loadKnownPeersFromDb();
    }

    /**
     * Called when bootstrap is unreachable. Loads all previously-seen peers from
     * local SQLite and marks them all as offline. This lets the UI display chat
     * history and peer list without requiring bootstrap to be up.
     */
    public void loadPeersFromCache() {
        knownPeers.clear();
        loadKnownPeersFromDb();
        // Mark all as offline since we can't confirm their status without bootstrap
        knownPeers.values().forEach(p -> p.setOnline(false));
        logger.info("[CACHE] Loaded " + knownPeers.size() + " peers from local DB (all marked offline).");
    }

    private void loadKnownPeersFromDb() {
        String sql = "SELECT username, host, port, online, key_id, public_key, last_seen FROM known_peers";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String username = rs.getString("username");
                if (username == null || username.isBlank() || username.equals(localUsername)) continue;
                PeerInfo peer = new PeerInfo(username, rs.getString("host"), rs.getInt("port"));
                peer.setOnline(rs.getInt("online") == 1);
                peer.setKeyId(rs.getString("key_id"));
                peer.setPublicKey(rs.getString("public_key"));
                peer.setLastHeartbeat(rs.getLong("last_seen"));
                knownPeers.put(username, peer);
            }
        } catch (SQLException e) {
            logger.fine("Load known peers failed: " + e.getMessage());
        }
    }

    private void persistKnownPeer(PeerInfo peer) {
        if (peer == null || peer.getUsername() == null || peer.getUsername().isBlank()) return;
        if (peer.getUsername().equals(localUsername)) return;
        String sql = "INSERT INTO known_peers (username, host, port, online, key_id, public_key, last_seen) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(username) DO UPDATE SET " +
                "host=excluded.host, port=excluded.port, online=excluded.online, " +
                "key_id=COALESCE(NULLIF(excluded.key_id, ''), known_peers.key_id), " +
                "public_key=COALESCE(NULLIF(excluded.public_key, ''), known_peers.public_key), " +
                "last_seen=excluded.last_seen";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, peer.getUsername());
            pstmt.setString(2, peer.getHost() == null ? "" : peer.getHost());
            pstmt.setInt(3, peer.getPort());
            pstmt.setInt(4, peer.isOnline() ? 1 : 0);
            pstmt.setString(5, peer.getKeyId() == null ? "" : peer.getKeyId());
            pstmt.setString(6, peer.getPublicKey() == null ? "" : peer.getPublicKey());
            pstmt.setLong(7, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.fine("Persist known peer failed for " + peer.getUsername() + ": " + e.getMessage());
        }
    }

    private void markKnownPeerOffline(String username) {
        String sql = "UPDATE known_peers SET online = 0, last_seen = ? WHERE username = ?";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setLong(1, System.currentTimeMillis());
            pstmt.setString(2, username);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.fine("Mark known peer offline failed for " + username + ": " + e.getMessage());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isTailscaleIp(String value) {
        byte[] bytes = parseIpv4(value);
        if (bytes == null) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    private boolean isDockerBridgeIp(String value) {
        byte[] bytes = parseIpv4(value);
        if (bytes == null) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 172 && second >= 16 && second <= 31;
    }

    private byte[] parseIpv4(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(value);
            byte[] bytes = address.getAddress();
            return bytes.length == 4 ? bytes : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Getters/Setters ====================

    public GroupCache getGroupCache() { return groupCache; }
    public LamportClock getLamportClock() { return lamportClock; }
    public RecentPeersCache getRecentPeersCache() { return recentPeersCache; }

    public LazyRepairManager getLazyRepairManager() { return lazyRepairManager; }
    public void setLazyRepairManager(LazyRepairManager m) { this.lazyRepairManager = m; }

    public CoordinatorManager getCoordinatorManager() { return coordinatorManager; }
    public void setCoordinatorManager(CoordinatorManager cm) { this.coordinatorManager = cm; }

    public PeerServer getPeerServer() { return peerServer; }
    public void setPeerServer(PeerServer ps) { this.peerServer = ps; }

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
    public com.mycompany.p2pchat.filetransfer.FileTransferManager getFileTransferManager() { return fileTransferManager; }
    public com.mycompany.p2pchat.database.OutboxRepository getOutboxRepository() { return outboxRepository; }

    public String getMailboxHost() { return mailboxHost; }
    public void setMailboxHost(String mailboxHost) { this.mailboxHost = mailboxHost; }

    public int getMailboxPort() { return mailboxPort; }
    public void setMailboxPort(int mailboxPort) { this.mailboxPort = mailboxPort; }

    public String getLocalPublicKey() { return localPublicKey; }
    public void setLocalPublicKey(String localPublicKey) { this.localPublicKey = localPublicKey; }

    public String getLocalKeyId() { return localKeyId; }
    public void setLocalKeyId(String localKeyId) { this.localKeyId = localKeyId; }

    public void shutdown() { dbManager.close(); }
}
