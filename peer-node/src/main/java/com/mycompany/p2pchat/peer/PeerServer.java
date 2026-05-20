package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;
import com.mycompany.p2pchat.utils.TimeUtil;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class PeerServer {

    private static final Logger logger = LoggerUtil.getLogger(PeerServer.class.getName());
    private final int port;
    private final PeerManager peerManager;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private WebServer webServer;
    private CoordinatorManager coordinatorManager;

    // Lamport buffer: groupId → scheduled messages
    private final Map<String, List<Message>> lamportBuffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService bufferFlusher = Executors.newSingleThreadScheduledExecutor();

    // Grace window scheduler for LEAVING state
    private final ScheduledExecutorService graceScheduler = Executors.newSingleThreadScheduledExecutor();

    public PeerServer(int port, PeerManager peerManager) {
        this.port = port;
        this.peerManager = peerManager;
    }

    public void setWebServer(WebServer webServer) { this.webServer = webServer; }
    public void setCoordinatorManager(CoordinatorManager cm) { this.coordinatorManager = cm; }

    public void start() {
        running = true;
        try {
            serverSocket = new ServerSocket(port);
            logger.info("PeerServer listening on port " + port);
            threadPool.execute(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        threadPool.execute(() -> handleConnection(client));
                    } catch (IOException e) {
                        if (running) logger.fine("Accept error: " + e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            logger.severe("Failed to start PeerServer: " + e.getMessage());
        }
    }

    private void handleConnection(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                Message msg = JsonUtil.fromJson(line.trim());
                if (msg != null) processMessage(msg, socket);
            }
        } catch (IOException e) {
            logger.fine("Connection closed: " + socket.getRemoteSocketAddress());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void processMessageFromLocal(Message msg) {
        processMessage(msg, null);
    }

    private void processMessage(Message msg, Socket socket) {
        String type = msg.getType();
        logger.fine("Received [" + type + "] from " + msg.getSender());

        try {
            switch (MessageType.valueOf(type)) {
                // ── P2P messaging ──
                case DIRECT_MESSAGE  -> { handleDirectMessage(msg); sendAck(msg, socket); }
                case GROUP_MESSAGE   -> handleGroupMessage(msg, socket);
                case BROADCAST       -> { handleBroadcast(msg); sendAck(msg, socket); }
                case TYPING          -> handleTyping(msg);
                case OFFLINE_MESSAGE -> { handleOfflineMessage(msg); }

                // ── Peer events from Bootstrap ──
                case PEER_JOIN  -> handlePeerJoin(msg);
                case PEER_LEAVE -> handlePeerLeave(msg);
                case ACK        -> logger.fine("ACK for: " + msg.getContent());

                // ── Control Plane: Coordinator protocol ──
                case COORD_INIT      -> handleCoordInit(msg, socket);
                case COORD_GOSSIP    -> handleCoordGossip(msg);
                case COORD_RESIGN    -> handleCoordResign(msg);
                case GROUP_ADD       -> handleGroupAdd(msg, socket);
                case GROUP_KICK      -> handleGroupKick(msg, socket);
                case GROUP_LEAVE     -> handleGroupLeave(msg, socket);
                case GROUP_DISBAND   -> handleGroupDisband(msg, socket);
                case GROUP_JOINED    -> handleGroupJoined(msg);
                case GROUP_UPDATED   -> handleGroupUpdated(msg);
                case GROUP_KICKED    -> handleGroupKicked(msg);
                case GROUP_DISBANDED -> handleGroupDisbanded(msg);

                // ── Repair Plane ──
                case GROUP_RESYNC_REQ -> handleResyncReq(msg, socket);
                case CACHE_STALE      -> handleCacheStale(msg);
                case GROUP_GET        -> handleGroupGet(msg, socket);

                // ── Peer lookup relay ──
                case PEER_LOOKUP_REQ  -> handlePeerLookupReq(msg, socket);

                // ── File Transfer ──
                case FILE_OFFER  -> peerManager.getFileTransferManager().handleFileOffer(msg);
                case FILE_ACCEPT -> peerManager.getFileTransferManager().handleFileAccept(msg);
                case FILE_REJECT -> peerManager.getFileTransferManager().handleFileReject(msg);
                case FILE_DONE   -> peerManager.getFileTransferManager().handleFileDone(msg);

                default -> logger.fine("Unhandled message type: " + type);
            }
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown message type: " + type);
        } catch (Exception e) {
            logger.severe("Error processing message [" + type + "]: " + e.getMessage());
        }
    }

    // ─────────────────────── P2P Messaging ───────────────────────

    private void handleDirectMessage(Message msg) {
        System.out.printf("%n[%s] %s -> you: %s%n> ",
                TimeUtil.formatTimestamp(msg.getTimestamp()), msg.getSender(), msg.getContent());
        peerManager.getMessageRepository().saveMessage(msg);
        // Update recent peers cache
        var peer = peerManager.getPeer(msg.getSender());
        if (peer != null) peerManager.getRecentPeersCache().upsert(msg.getSender(), peer.getAddress());
        broadcastWs("DIRECT_MESSAGE", messageToMap(msg));
    }

    private void handleGroupMessage(Message msg, Socket socket) {
        String groupId = msg.getGroupId();
        GroupCache.GroupCacheEntry entry = peerManager.getGroupCache().get(groupId);

        // Update Lamport clock
        peerManager.getLamportClock().receive(msg.getLamportClock());

        // Version mismatch check
        if (entry != null && msg.getCacheVersion() > 0 && msg.getCacheVersion() < entry.getLocalVersion()) {
            // Sender has older cache — send CACHE_STALE back
            sendCacheStale(msg.getSender(), groupId, entry.getLocalVersion(), socket);
        }

        // If in LEAVING state — store but don't ACK
        if (entry != null && "LEAVING".equals(entry.getGroupState())) {
            peerManager.getMessageRepository().saveMessage(msg);
            broadcastWs("GROUP_MESSAGE", messageToMap(msg));
            return; // no ACK
        }

        // Buffer window: hold 200ms, sort by lamport clock before pushing to UI
        lamportBuffers.computeIfAbsent(groupId, k -> Collections.synchronizedList(new ArrayList<>())).add(msg);
        bufferFlusher.schedule(() -> flushLamportBuffer(groupId), Constants.LAMPORT_BUFFER_MS, TimeUnit.MILLISECONDS);

        sendAck(msg, socket);
    }

    private void flushLamportBuffer(String groupId) {
        List<Message> buffer = lamportBuffers.remove(groupId);
        if (buffer == null) return;
        synchronized (buffer) {
            buffer.sort(Comparator.comparingLong(Message::getLamportClock)
                        .thenComparing(m -> m.getSender() != null ? m.getSender() : ""));
            for (Message m : buffer) {
                peerManager.getMessageRepository().saveMessage(m);
                System.out.printf("%n[%s] [%s] %s: %s%n> ",
                        TimeUtil.formatTimestamp(m.getTimestamp()),
                        m.getGroupName() != null ? m.getGroupName() : groupId,
                        m.getSender(), m.getContent());
                broadcastWs("GROUP_MESSAGE", messageToMap(m));
            }
        }
    }

    private void handleBroadcast(Message msg) {
        System.out.printf("%n[%s] [BROADCAST] %s: %s%n> ",
                TimeUtil.formatTimestamp(msg.getTimestamp()), msg.getSender(), msg.getContent());
        peerManager.getMessageRepository().saveMessage(msg);
        broadcastWs("BROADCAST", messageToMap(msg));
    }

    private void handleTyping(Message msg) {
        broadcastWs("TYPING", messageToMap(msg));
    }

    private void handleOfflineMessage(Message msg) {
        System.out.printf("%n[%s] [OFFLINE] %s: %s%n> ",
                TimeUtil.formatTimestamp(msg.getTimestamp()), msg.getSender(), msg.getContent());
        peerManager.getMessageRepository().saveMessage(msg);
        broadcastWs("OFFLINE_MESSAGE", messageToMap(msg));
    }

    // ─────────────────────── Peer Events ───────────────────────

    private void handlePeerJoin(Message msg) {
        String[] parts = msg.getContent().split(":");
        if (parts.length == 2) {
            PeerInfo peer = new PeerInfo(msg.getSender(), parts[0], Integer.parseInt(parts[1]));
            peerManager.addKnownPeer(peer);
            System.out.printf("%n[SYSTEM] %s joined the network.%n> ", msg.getSender());
            Map<String, Object> data = new HashMap<>();
            data.put("username", msg.getSender());
            data.put("host", parts[0]);
            data.put("port", Integer.parseInt(parts[1]));
            broadcastWs("PEER_JOIN", data);
        }
    }

    private void handlePeerLeave(Message msg) {
        peerManager.removeKnownPeer(msg.getSender());
        System.out.printf("%n[SYSTEM] %s left the network.%n> ", msg.getSender());
        broadcastWs("PEER_LEAVE", Map.of("username", msg.getSender()));
    }

    // ─────────────────────── Control Plane ───────────────────────

    private void handleCoordInit(Message msg, Socket socket) {
        GroupInfo info = msg.getGroupInfo();
        if (info == null) return;

        // DEDUP: If we already manage this group at equal or higher version,
        // ignore the duplicate COORD_INIT (race between C1 and C3 both promoting us).
        if (coordinatorManager != null) {
            GroupInfo existing = coordinatorManager.getManagedGroup(info.getGroupId());
            if (existing != null && existing.getVersion() >= info.getVersion()) {
                logger.info("COORD_INIT dedup: already managing " + info.getGroupId()
                        + " v=" + existing.getVersion() + ", ignoring v=" + info.getVersion());
                sendRawAck(socket, msg.getMessageId(), "COORD_INIT_ACK");
                return;
            }
            coordinatorManager.manageGroup(info);
        }
        peerManager.getGroupCache().updateFromGroupInfo(info);
        sendRawAck(socket, msg.getMessageId(), "COORD_INIT_ACK");
        logger.info("Became coordinator for group: " + info.getGroupId());
        broadcastWs("GROUP_UPDATED", groupInfoToMap(info, "COORD_CHANGE", null));
    }

    private void handleCoordGossip(Message msg) {
        if (coordinatorManager != null) {
            coordinatorManager.handleCoordGossip(msg);
        }
        // Also update local cache
        GroupInfo remoteInfo = msg.getGroupInfo();
        if (remoteInfo != null) {
            GroupCache.GroupCacheEntry local = peerManager.getGroupCache().get(remoteInfo.getGroupId());
            if (local == null || remoteInfo.getVersion() > local.getLocalVersion()) {
                peerManager.getGroupCache().updateFromGroupInfo(remoteInfo);
            }
        }
    }

    private void handleCoordResign(Message msg) {
        String groupId = msg.getGroupId();
        if (coordinatorManager != null && coordinatorManager.isManaging(groupId)) {
            coordinatorManager.unmanageGroup(groupId);
            logger.info("Coordinator resigned for group: " + groupId);
        }
    }

    private void handleGroupAdd(Message msg, Socket socket) {
        if (coordinatorManager == null || !coordinatorManager.isManaging(msg.getGroupId())) {
            sendError(socket, "Not coordinator for: " + msg.getGroupId());
            return;
        }
        Message response = coordinatorManager.handleGroupAdd(msg);
        sendResponse(socket, response);
        if (MessageType.GROUP_UPDATED.name().equals(response.getType())) {
            // FIX: Sync GroupCache from managedGroups so handleGroupUpdated can find the entry.
            // Without this, when duc (C3) handles GROUP_ADD itself, its own GroupCache
            // is never updated and the system message ("peer-X đã tham gia nhóm") is never shown.
            GroupInfo managed = coordinatorManager.getManagedGroup(msg.getGroupId());
            if (managed != null) {
                peerManager.getGroupCache().updateFromGroupInfo(managed);
            }
            // Route through processMessageFromLocal so handleGroupUpdated() is invoked.
            // This synthesizes the system chat message AND calls broadcastWs(GROUP_UPDATED).
            processMessageFromLocal(response);
        }
    }

    private void handleGroupKick(Message msg, Socket socket) {
        if (coordinatorManager == null || !coordinatorManager.isManaging(msg.getGroupId())) {
            sendError(socket, "Not coordinator for: " + msg.getGroupId());
            return;
        }
        Message response = coordinatorManager.handleGroupKick(msg);
        sendResponse(socket, response);
        if (MessageType.GROUP_UPDATED.name().equals(response.getType())) {
            // FIX: Same as handleGroupAdd — sync GroupCache then route through full pipeline.
            GroupInfo managed = coordinatorManager.getManagedGroup(msg.getGroupId());
            if (managed != null) {
                peerManager.getGroupCache().updateFromGroupInfo(managed);
            }
            processMessageFromLocal(response);
        }
    }

    private void handleGroupLeave(Message msg, Socket socket) {
        if (coordinatorManager == null || !coordinatorManager.isManaging(msg.getGroupId())) {
            sendError(socket, "Not coordinator for: " + msg.getGroupId());
            return;
        }
        Message response = coordinatorManager.handleGroupLeave(msg);
        sendResponse(socket, response);
        if (MessageType.GROUP_UPDATED.name().equals(response.getType())) {
            // FIX: Same as handleGroupAdd — sync GroupCache then route through full pipeline.
            // Note: if the leaver was duc itself, GroupCache.remove() is handled by
            // handleGroupUpdated via the LEAVE changeType path on the remote side;
            // locally, CoordinatorManager.handleGroupLeave() calls unmanageGroup() if needed.
            GroupInfo managed = coordinatorManager.getManagedGroup(msg.getGroupId());
            if (managed != null) {
                peerManager.getGroupCache().updateFromGroupInfo(managed);
            }
            processMessageFromLocal(response);
        }
    }

    private void handleGroupDisband(Message msg, Socket socket) {
        if (coordinatorManager == null || !coordinatorManager.isManaging(msg.getGroupId())) {
            sendError(socket, "Not coordinator for: " + msg.getGroupId());
            return;
        }
        coordinatorManager.handleGroupDisband(msg);
        broadcastWs("GROUP_DISBANDED", Map.of("groupId", msg.getGroupId()));
    }

    private void handleGroupJoined(Message msg) {
        GroupInfo info = new GroupInfo();
        info.setGroupId(msg.getGroupId());
        info.setGroupName(msg.getGroupName());
        info.setMembers(msg.getMembers() != null ? msg.getMembers() : new ArrayList<>());
        info.setOwner(msg.getMembers() != null && !msg.getMembers().isEmpty() ? msg.getMembers().get(0) : "");
        info.setVersion(msg.getVersion());
        info.setGroupMode(msg.getGroupMode() != null ? msg.getGroupMode() : "OPEN");
        peerManager.getGroupCache().updateFromGroupInfo(info);
        logger.info("Joined group: " + msg.getGroupId() + " (" + msg.getGroupName() + ")");

        if (msg.getChatHistory() != null) {
            for (Message histMsg : msg.getChatHistory()) {
                peerManager.getMessageRepository().saveMessage(histMsg);
            }
        }

        broadcastWs("GROUP_JOINED", groupInfoToMap(info, null, null));
    }

    private void handleGroupUpdated(Message msg) {
        GroupCache.GroupCacheEntry entry = peerManager.getGroupCache().get(msg.getGroupId());
        if (entry == null) return;
        if (msg.getMembers() != null) entry.setMembers(new ArrayList<>(msg.getMembers()));
        if (msg.getCoordinators() != null) entry.setCoordinators(new ArrayList<>(msg.getCoordinators()));
        if (msg.getVersion() > 0) entry.setLocalVersion(msg.getVersion());
        if (msg.getGroupMode() != null) entry.setGroupMode(msg.getGroupMode());
        entry.setLastUpdated(System.currentTimeMillis());

        // FIX #5: If this peer is a coordinator for this group, sync the control-plane
        // managedGroups so it doesn't diverge from the data-plane GroupCache.
        if (coordinatorManager != null && coordinatorManager.isManaging(msg.getGroupId())) {
            GroupInfo managed = coordinatorManager.getManagedGroup(msg.getGroupId());
            if (managed != null && msg.getVersion() > managed.getVersion()) {
                if (msg.getMembers() != null) managed.setMembers(new ArrayList<>(msg.getMembers()));
                managed.setVersion(msg.getVersion());
                if (msg.getGroupMode() != null) managed.setGroupMode(msg.getGroupMode());
                logger.info("CoordinatorManager synced from GROUP_UPDATED for " + msg.getGroupId()
                        + " v=" + msg.getVersion());
            }
        }

        String change = msg.getChangeType();
        String affected = msg.getAffected();
        if (change != null && affected != null && !affected.isEmpty()) {
            String content = "";
            switch (change) {
                case "ADD": content = affected + " đã tham gia nhóm."; break;
                case "KICK": content = affected + " đã bị xóa khỏi nhóm."; break;
                case "LEAVE": content = affected + " đã rời nhóm."; break;
                case "OWNER_LEAVE": content = affected + " (Chủ phòng) đã rời nhóm."; break;
            }
            if (!content.isEmpty()) {
                Message sysMsg = Message.builder()
                        .type("SYSTEM")
                        .messageId("sys-" + UUID.randomUUID().toString())
                        .sender("SYSTEM")
                        .groupId(msg.getGroupId())
                        .groupName(entry.getGroupName())
                        .content(content)
                        .timestamp(System.currentTimeMillis())
                        .build();
                peerManager.getMessageRepository().saveMessage(sysMsg);
                broadcastWs("GROUP_MESSAGE", messageToMap(sysMsg));
            }
        }

        broadcastWs("GROUP_UPDATED", messageToMapFull(msg));
    }

    private void handleGroupKicked(Message msg) {
        String groupId = msg.getGroupId();
        // Grace window: state = LEAVING, continue receiving for 3s
        peerManager.getGroupCache().setState(groupId, "LEAVING");
        broadcastWs("GROUP_KICKED", Map.of(
                "groupId", groupId != null ? groupId : "",
                "message", "Bạn đã bị xóa khỏi nhóm này"));
        // After grace window, remove from cache
        graceScheduler.schedule(() -> {
            peerManager.getGroupCache().remove(groupId);
            broadcastWs("GROUP_REMOVED", Map.of("groupId", groupId != null ? groupId : ""));
        }, Constants.KICK_GRACE_WINDOW_MS, TimeUnit.MILLISECONDS);
    }

    private void handleGroupDisbanded(Message msg) {
        String groupId = msg.getGroupId();
        peerManager.getGroupCache().remove(groupId);
        broadcastWs("GROUP_DISBANDED", Map.of("groupId", groupId != null ? groupId : ""));
    }

    // ─────────────────────── Repair Plane ───────────────────────

    private void handleResyncReq(Message msg, Socket socket) {
        if (coordinatorManager == null || !coordinatorManager.isManaging(msg.getGroupId())) {
            sendError(socket, "Not coordinator for: " + msg.getGroupId());
            return;
        }
        Message response = coordinatorManager.handleResyncReq(msg);
        sendResponse(socket, response);
    }

    private void handleCacheStale(Message msg) {
        String groupId = msg.getGroupId();
        logger.info("CACHE_STALE received for " + groupId + " — triggering repair");
        LazyRepairManager repair = peerManager.getLazyRepairManager();
        if (repair != null) {
            threadPool.execute(() -> repair.repair(groupId));
        }
    }

    private void handleGroupGet(Message msg, Socket socket) {
        // Not implemented in full — coordinator returns all groups for requesting peer
        sendError(socket, "GROUP_GET not yet fully implemented");
    }

    private void handlePeerLookupReq(Message msg, Socket socket) {
        String targetUsername = msg.getContent();
        // Check our known peers list
        PeerInfo peer = peerManager.getPeer(targetUsername);
        if (peer != null && peer.isOnline()) {
            Message resp = Message.builder()
                    .type(MessageType.PEER_LOOKUP_RESP.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalUsername())
                    .content(peer.getAddress())
                    .build();
            sendResponse(socket, resp);
        } else {
            sendError(socket, "Peer not found: " + targetUsername);
        }
    }

    private void sendCacheStale(String senderAddress, String groupId, long currentVersion, Socket socket) {
        try {
            Message stale = Message.builder()
                    .type(MessageType.CACHE_STALE.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalAddress())
                    .groupId(groupId)
                    .cacheVersion(currentVersion)
                    .build();
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(JsonUtil.toJson(stale));
        } catch (IOException e) {
            logger.fine("Failed to send CACHE_STALE: " + e.getMessage());
        }
    }

    // ─────────────────────── Helpers ───────────────────────

    private void sendAck(Message original, Socket socket) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message ack = ProtocolHandler.createAck(original.getMessageId(), peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(ack));
        } catch (IOException e) {
            logger.fine("Failed to send ACK: " + e.getMessage());
        }
    }

    private void sendRawAck(Socket socket, String originalId, String ackType) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message ack = Message.builder()
                    .type(ackType)
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalUsername())
                    .content(originalId)
                    .build();
            out.println(JsonUtil.toJson(ack));
        } catch (IOException e) {
            logger.fine("Failed to send " + ackType);
        }
    }

    private void sendResponse(Socket socket, Message response) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(JsonUtil.toJson(response));
        } catch (IOException e) {
            logger.fine("Failed to send response: " + e.getMessage());
        }
    }

    private void sendError(Socket socket, String error) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message err = Message.builder()
                    .type(MessageType.ERROR.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .content(error).build();
            out.println(JsonUtil.toJson(err));
        } catch (IOException e) {
            logger.fine("Failed to send error response");
        }
    }

    private void broadcastWs(String type, Object data) {
        if (webServer != null) webServer.broadcastToWeb(type, data);
    }

    private Map<String, Object> messageToMap(Message msg) {
        Map<String, Object> map = new HashMap<>();
        map.put("messageId", msg.getMessageId());
        map.put("sender", msg.getSender());
        map.put("receiver", msg.getReceiver());
        map.put("groupName", msg.getGroupName());
        map.put("groupId", msg.getGroupId());
        map.put("content", msg.getContent());
        map.put("type", msg.getType());
        map.put("timestamp", msg.getTimestamp());
        map.put("lamportClock", msg.getLamportClock());
        return map;
    }

    private Map<String, Object> messageToMapFull(Message msg) {
        Map<String, Object> map = messageToMap(msg);
        map.put("members", msg.getMembers());
        map.put("coordinators", msg.getCoordinators());
        map.put("version", msg.getVersion());
        map.put("changeType", msg.getChangeType());
        map.put("affected", msg.getAffected());
        map.put("groupMode", msg.getGroupMode());
        return map;
    }

    private Map<String, Object> groupInfoToMap(GroupInfo info, String changeType, String affected) {
        Map<String, Object> map = new HashMap<>();
        map.put("groupId", info.getGroupId());
        map.put("groupName", info.getGroupName());
        map.put("owner", info.getOwner());
        map.put("members", info.getMembers());
        map.put("coordinators", HRWHash.topK(info.getMembers(), info.getGroupId(), Constants.COORDINATOR_K));
        map.put("version", info.getVersion());
        map.put("groupMode", info.getGroupMode());
        if (changeType != null) map.put("changeType", changeType);
        if (affected != null) map.put("affected", affected);
        return map;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            threadPool.shutdown();
            bufferFlusher.shutdown();
            graceScheduler.shutdown();
        } catch (IOException e) {
            logger.severe("Error stopping PeerServer: " + e.getMessage());
        }
    }
}
