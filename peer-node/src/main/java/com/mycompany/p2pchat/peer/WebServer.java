package com.mycompany.p2pchat.peer;

import com.google.gson.Gson;
import com.mycompany.p2pchat.database.MessageRepository;
import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class WebServer {

    private static final Logger logger = LoggerUtil.getLogger(WebServer.class.getName());
    private static final Gson gson = new Gson();

    private final PeerManager peerManager;
    private final PeerClient peerClient;
    private final PeerNode peerNode;
    private final int port;
    private Javalin app;
    private final List<io.javalin.websocket.WsContext> wsClients = new CopyOnWriteArrayList<>();
    private CoordinatorManager coordinatorManager;

    public WebServer(int port, PeerManager peerManager, PeerClient peerClient, PeerNode peerNode) {
        this.port = port;
        this.peerManager = peerManager;
        this.peerClient = peerClient;
        this.peerNode = peerNode;
    }

    public void setCoordinatorManager(CoordinatorManager cm) { this.coordinatorManager = cm; }

    public void start() {
        app = Javalin.create(config -> {
            config.staticFiles.add("/static", Location.CLASSPATH);
        });

        app.exception(Exception.class, (e, ctx) -> {
            logger.severe("API error: " + e.getMessage());
            ctx.status(500).contentType("application/json")
               .result(gson.toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error")));
        });

        // WebSocket
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> { wsClients.add(ctx); logger.fine("WS client connected"); });
            ws.onClose(ctx -> { wsClients.remove(ctx); logger.fine("WS client disconnected"); });
        });

        registerRoutes();
        app.start(port);
        logger.info("WebServer started on port " + port);
    }

    public void stop() { if (app != null) app.stop(); }

    public void broadcastToWeb(String type, Object data) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("data", data);
        String json = gson.toJson(event);
        wsClients.forEach(c -> { try { c.send(json); } catch (Exception ignored) {} });
    }

    private void registerRoutes() {

        // ── Info ──
        app.get("/api/info", ctx -> {
            Map<String, Object> info = new HashMap<>();
            info.put("username", peerManager.getLocalUsername());
            info.put("host", peerManager.getLocalHost());
            info.put("peerPort", peerManager.getLocalPort());
            info.put("webPort", peerManager.getWebPort());
            info.put("bootstrapHost", peerManager.getBootstrapHost());
            info.put("bootstrapPort", peerManager.getBootstrapPort());
            info.put("mailboxHost", peerManager.getMailboxHost());
            info.put("mailboxPort", peerManager.getMailboxPort());
            info.put("address", peerManager.getLocalAddress());
            info.put("initialized", peerManager.hasLocalIdentity());
            info.put("registered", peerManager.isRegisteredToBootstrap());
            info.put("bootstrapConnected", peerManager.isRegisteredToBootstrap());
            info.put("lastBootstrapError", peerManager.getLastBootstrapError());
            ctx.contentType("application/json").result(gson.toJson(info));
        });

        app.get("/health", ctx -> {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "UP");
            health.put("initialized", peerManager.hasLocalIdentity());
            health.put("registered", peerManager.isRegisteredToBootstrap());
            health.put("bootstrapConnected", peerManager.isRegisteredToBootstrap());
            health.put("username", peerManager.getLocalUsername());
            health.put("address", peerManager.getLocalAddress());
            health.put("mailbox", peerManager.getMailboxHost() + ":" + peerManager.getMailboxPort());
            ctx.contentType("application/json").result(gson.toJson(health));
        });

        // ── Peers ──
        // Trả về tất cả peer đã từng biết (kèm cờ online) + những đối tác đã từng chat
        // trong DB local. Giữ peer trong sidebar kể cả khi họ off để xem lại lịch sử.
        app.get("/api/peers", ctx -> {
            Map<String, PeerInfo> merged = new LinkedHashMap<>();
            for (PeerInfo p : peerManager.getAllKnownPeers()) merged.put(p.getUsername(), p);
            String me = peerManager.getLocalUsername();
            for (String partner : peerManager.getMessageRepository().getDirectChatPartners(me)) {
                if (partner == null || partner.isBlank() || partner.equals(me)) continue;
                if (!merged.containsKey(partner)) {
                    PeerInfo stub = new PeerInfo(partner, "", 0);
                    stub.setOnline(false);
                    merged.put(partner, stub);
                }
            }
            ctx.contentType("application/json").result(gson.toJson(merged.values()));
        });

        app.get("/api/discover", ctx -> {
            if (!peerManager.isRegisteredToBootstrap()) {
                ctx.status(400).result(gson.toJson(Map.of("error", "Not connected to bootstrap")));
                return;
            }
            discoverPeers();
            ctx.contentType("application/json").result(gson.toJson(peerManager.getOnlinePeers()));
        });

        // ── Messages ──
        app.get("/api/history/{peerName}", ctx -> {
            String peerName = ctx.pathParam("peerName");
            List<Message> msgs = peerManager.getMessageRepository()
                    .getChatHistory(peerManager.getLocalUsername(), peerName);
            ctx.contentType("application/json").result(gson.toJson(msgs));
        });

        app.get("/api/group-history/{groupId}", ctx -> {
            String groupId = ctx.pathParam("groupId");
            GroupCache.GroupCacheEntry entry = peerManager.getGroupCache().get(groupId);
            String groupName = entry != null ? entry.getGroupName() : groupId;
            List<Message> msgs = peerManager.getMessageRepository().getGroupHistory(groupName);
            ctx.contentType("application/json").result(gson.toJson(msgs));
        });

        app.get("/api/broadcast-history", ctx -> {
            List<Message> msgs = peerManager.getMessageRepository().getBroadcastHistory();
            ctx.contentType("application/json").result(gson.toJson(msgs));
        });

        app.get("/api/outbox", ctx -> ctx.contentType("application/json")
                .result(gson.toJson(peerManager.getOutboxRepository().recent(100))));

        // Simulate user pulling the plug: kill JVM -> container exits with non-zero status.
        // Volume `/app/data` is untouched, so history/outbox/keys survive a `docker start`.
        app.post("/api/shutdown", ctx -> {
            ctx.contentType("application/json").result(gson.toJson(Map.of(
                    "shuttingDown", true,
                    "username", peerManager.getLocalUsername())));
            new Thread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                Runtime.getRuntime().halt(137);
            }, "peer-shutdown").start();
        });

        app.post("/api/msg", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String receiver = body.get("receiver"), content = body.get("content");
            if (receiver == null || content == null) { ctx.status(400).result("Missing receiver or content"); return; }
            PeerClient.SendResult result = peerClient.sendDirectMessageDetailed(
                    peerManager.getLocalUsername(), receiver, content);
            ctx.contentType("application/json").result(gson.toJson(Map.of(
                    "sent", "DELIVERED_DIRECT".equals(result.status),
                    "status", result.status,
                    "messageId", result.messageId,
                    "timestamp", result.timestamp)));
        });

        app.get("/api/auto-detect-host", ctx -> {
            Map<String, Object> result = new HashMap<>();
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new InetSocketAddress(peerManager.getBootstrapHost(), peerManager.getBootstrapPort()), 2000);
                s.setSoTimeout(2000);
                String localIp = s.getLocalAddress().getHostAddress();
                result.put("host", localIp);
                result.put("status", "bootstrap_reachable");
            } catch (Exception e) {
                result.put("host", "localhost");
                result.put("status", "bootstrap_unreachable");
            }
            ctx.contentType("application/json").result(gson.toJson(result));
        });

        app.post("/api/init", ctx -> {
            ctx.status(410).contentType("application/json").result(gson.toJson(Map.of(
                    "error", "Peer setup from this Web UI is disabled. Use the launcher on http://localhost:9200.")));
        });

        app.post("/api/broadcast", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String content = body.get("content");
            if (content == null) { ctx.status(400).result("Missing content"); return; }
            peerClient.sendBroadcast(peerManager.getLocalUsername(), content);
            ctx.contentType("application/json").result(gson.toJson(Map.of("sent", true)));
        });

        app.post("/api/typing", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String receiver = body.get("receiver");
            String groupId = body.get("groupId");
            
            if (receiver != null) {
                PeerInfo peer = peerManager.getPeer(receiver);
                if (peer != null) {
                    try {
                        peerClient.sendSignal(peer.getHost(), peer.getPort(), MessageType.TYPING.name(), "", "");
                    } catch (Exception ignored) {}
                }
            } else if (groupId != null) {
                GroupCache.GroupCacheEntry entry = peerManager.getGroupCache().get(groupId);
                if (entry != null) {
                    for (String addr : entry.getMembers()) {
                        if (addr.equals(peerManager.getLocalAddress())) continue;
                        String[] parts = addr.split(":");
                        if (parts.length == 2) {
                            try {
                                peerClient.sendSignal(parts[0], Integer.parseInt(parts[1]), MessageType.TYPING.name(), groupId, "");
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            ctx.contentType("application/json").result(gson.toJson(Map.of("sent", true)));
        });



        // ── Groups — New DHT-lite API ──
        app.get("/api/group/list", ctx -> {
            List<Map<String, Object>> groups = new ArrayList<>();
            // FIX: Only return ACTIVE groups — skip LEAVING entries (e.g. after kick/disband grace window)
            // so that refreshData() on the client doesn't re-add groups the user was removed from.
            for (GroupCache.GroupCacheEntry e : peerManager.getGroupCache().getActiveGroups()) {
                Map<String, Object> g = new HashMap<>();
                g.put("groupId", e.getGroupId());
                g.put("groupName", e.getGroupName());
                g.put("owner", e.getOwner());
                g.put("members", e.getMembers());
                g.put("coordinators", e.getCoordinators());
                g.put("version", e.getLocalVersion());
                g.put("groupMode", e.getGroupMode());
                g.put("groupState", e.getGroupState());
                groups.add(g);
            }
            ctx.contentType("application/json").result(gson.toJson(groups));
        });

        app.get("/api/group/{groupId}", ctx -> {
            String groupId = ctx.pathParam("groupId");
            GroupCache.GroupCacheEntry entry = peerManager.getGroupCache().get(groupId);
            if (entry == null) { ctx.status(404).result(gson.toJson(Map.of("error", "Group not found"))); return; }
            Map<String, Object> g = new HashMap<>();
            g.put("groupId", entry.getGroupId());
            g.put("groupName", entry.getGroupName());
            g.put("owner", entry.getOwner());
            g.put("members", entry.getMembers());
            g.put("coordinators", entry.getCoordinators());
            g.put("version", entry.getLocalVersion());
            g.put("groupMode", entry.getGroupMode());
            g.put("groupState", entry.getGroupState());
            ctx.contentType("application/json").result(gson.toJson(g));
        });

        app.post("/api/group/create", ctx -> {
            Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
            String groupName = getString(body, "groupName");
            List<String> memberUsernames = body.get("members") instanceof List<?> l
                    ? l.stream().map(Object::toString).toList() : List.of();
            String groupMode = getString(body, "groupMode");
            if (groupMode.isEmpty()) groupMode = "OPEN";

            if (groupName.isEmpty()) { ctx.status(400).result(gson.toJson(Map.of("error", "Missing groupName"))); return; }
            if (memberUsernames.size() < 2) { ctx.status(400).result(gson.toJson(Map.of("error", "Group must have at least 3 members (including you)"))); return; }

            // Resolve member addresses
            List<String> memberAddresses = new ArrayList<>();
            memberAddresses.add(peerManager.getLocalAddress());
            List<String> missingMembers = new ArrayList<>();
            List<String> unreachableMembers = new ArrayList<>();
            for (String uname : memberUsernames) {
                PeerInfo peer = peerManager.getPeer(uname);
                if (peer != null && peer.isOnline()) {
                    if (!canConnectAddress(peer.getAddress())) {
                        unreachableMembers.add(uname + "@" + peer.getAddress());
                        continue;
                    }
                    memberAddresses.add(peer.getAddress());
                    peerManager.getRecentPeersCache().upsert(uname, peer.getAddress());
                } else {
                    missingMembers.add(uname);
                }
            }
            if (!missingMembers.isEmpty() || !unreachableMembers.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Some group members are not direct-reachable");
                error.put("missingOrOffline", missingMembers);
                error.put("unreachable", unreachableMembers);
                ctx.status(409).contentType("application/json").result(gson.toJson(error));
                return;
            }
            if (memberAddresses.size() != memberUsernames.size() + 1) {
                ctx.status(409).result(gson.toJson(Map.of("error", "Group member resolution failed")));
                return;
            }

            // Create GroupInfo
            GroupInfo info = GroupInfo.create(groupName, peerManager.getLocalAddress(), memberAddresses);
            info.setGroupMode(groupMode);

            // Determine coordinators via HRW
            List<String> coords = HRWHash.topK(info.getMembers(), info.getGroupId(), Constants.COORDINATOR_K);

            // If we are a coordinator — manage locally
            if (coords.contains(peerManager.getLocalAddress()) && coordinatorManager != null) {
                coordinatorManager.manageGroup(info);
            }

            // Create SYSTEM message for group creation
            Message sysMsg = Message.builder()
                    .type(MessageType.SYSTEM.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender("SYSTEM")
                    .groupId(info.getGroupId())
                    .groupName(info.getGroupName())
                    .content("Nhóm được tạo bởi " + peerManager.getLocalUsername() + " và " + (info.getMembers().size() - 1) + " thành viên khác.")
                    .timestamp(System.currentTimeMillis())
                    .build();
            peerManager.getMessageRepository().saveMessage(sysMsg);

            // COORD_INIT to other coordinators
            for (String coord : coords) {
                if (!coord.equals(peerManager.getLocalAddress())) {
                    sendCoordInit(coord, info);
                }
            }

            // GROUP_JOINED to all members
            for (String addr : info.getMembers()) {
                if (!addr.equals(peerManager.getLocalAddress())) {
                    sendGroupJoined(addr, info, coords);
                }
            }

            // Update local cache
            peerManager.getGroupCache().updateFromGroupInfo(info);
            broadcastToWeb("GROUP_JOINED", groupInfoToWsMap(info, coords));

            Map<String, Object> resp = new HashMap<>();
            resp.put("groupId", info.getGroupId());
            resp.put("groupName", info.getGroupName());
            resp.put("members", info.getMembers());
            resp.put("coordinators", coords);
            ctx.contentType("application/json").result(gson.toJson(resp));
        });

        app.post("/api/group/add", ctx -> {
            Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
            String groupId = getString(body, "groupId");
            String username = getString(body, "username");
            if (groupId.isEmpty() || username.isEmpty()) {
                ctx.status(400).result(gson.toJson(Map.of("error", "Missing groupId or username"))); return;
            }
            GroupCache.GroupCacheEntry entry = peerManager.getGroupCache().get(groupId);
            if (entry == null) { ctx.status(404).result(gson.toJson(Map.of("error", "Group not found"))); return; }
            PeerInfo peer = peerManager.getPeer(username);
            if (peer == null) { ctx.status(404).result(gson.toJson(Map.of("error", "Peer not found"))); return; }
            if (!peer.isOnline() || !canConnectAddress(peer.getAddress())) {
                ctx.status(409).result(gson.toJson(Map.of(
                        "error", "Peer is not direct-reachable",
                        "peer", username,
                        "address", peer.getAddress())));
                return;
            }
            peerManager.getRecentPeersCache().upsert(username, peer.getAddress());

            Message addMsg = Message.builder()
                    .type(MessageType.GROUP_ADD.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .requestId(UUID.randomUUID().toString())
                    .sender(peerManager.getLocalAddress())
                    .groupId(groupId)
                    .requester(peerManager.getLocalAddress())
                    .newMember(peer.getAddress())
                    .build();

            Message response = peerClient.sendToCoordinator(groupId, addMsg);

            // FALLBACK: coordinator offline → tự cập nhật GroupCache local và thông báo
            // cho các thành viên offline qua mailbox. Người được thêm (đang online) nhận
            // GROUP_JOINED trực tiếp.
            if (MessageType.ERROR.name().equals(response.getType())) {
                logger.warning("Coordinator unreachable for GROUP_ADD " + groupId
                        + " — applying locally and notifying via mailbox/direct");

                // Build danh sách members mới (thêm người mới vào)
                List<String> newMembers = new java.util.ArrayList<>(entry.getMembers());
                if (!newMembers.contains(peer.getAddress())) {
                    newMembers.add(peer.getAddress());
                }
                List<String> coords = HRWHash.topK(newMembers, groupId, Constants.COORDINATOR_K);

                // Cập nhật GroupCache local
                com.mycompany.p2pchat.model.GroupInfo updatedInfo =
                        new com.mycompany.p2pchat.model.GroupInfo();
                updatedInfo.setGroupId(groupId);
                updatedInfo.setGroupName(entry.getGroupName());
                updatedInfo.setOwner(entry.getOwner());
                updatedInfo.setMembers(newMembers);
                updatedInfo.setVersion(entry.getLocalVersion() + 1);
                updatedInfo.setGroupMode(entry.getGroupMode());
                peerManager.getGroupCache().updateFromGroupInfo(updatedInfo);

                // Gửi GROUP_JOINED trực tiếp cho người mới (đang online)
                sendGroupJoined(peer.getAddress(), updatedInfo, coords);
                logger.info("[ADD-FALLBACK] Sent GROUP_JOINED directly to " + username);

                // Thông báo cho các thành viên offline qua mailbox
                storeControlEventToMailbox(
                        peerManager.getGroupCache().get(groupId),
                        groupId, MessageType.GROUP_UPDATED.name(),
                        peer.getAddress()); // exclude người vừa thêm (đã nhận trực tiếp)

                ctx.contentType("application/json").result(gson.toJson(Map.of(
                        "type", "GROUP_UPDATED_LOCAL",
                        "members", newMembers)));
                return;
            }

            ctx.contentType("application/json").result(gson.toJson(Map.of(
                    "type", response.getType(),
                    "members", response.getMembers() != null ? response.getMembers() : List.of())));
        });


        app.post("/api/group/kick", ctx -> {
            Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
            String groupId = getString(body, "groupId");
            String target = getString(body, "target");
            if (groupId.isEmpty() || target.isEmpty()) {
                ctx.status(400).result(gson.toJson(Map.of("error", "Missing groupId or target"))); return;
            }
            PeerInfo targetPeer = peerManager.getPeer(target);
            String targetAddr = targetPeer != null ? targetPeer.getAddress() : target;
            if (!canConnectAddress(targetAddr)) {
                ctx.status(409).result(gson.toJson(Map.of(
                        "error", "Target is not direct-reachable; kick notification would be lost",
                        "target", target,
                        "address", targetAddr)));
                return;
            }

            Message kickMsg = Message.builder()
                    .type(MessageType.GROUP_KICK.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalAddress())
                    .groupId(groupId)
                    .target(targetAddr)
                    .build();
            Message response = peerClient.sendToCoordinator(groupId, kickMsg);
            ctx.contentType("application/json").result(gson.toJson(Map.of("type", response.getType())));
        });

        app.post("/api/group/leave", ctx -> {
            Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
            String groupId = getString(body, "groupId");
            String newOwner = getString(body, "newOwner");
            if (groupId.isEmpty()) { ctx.status(400).result(gson.toJson(Map.of("error", "Missing groupId"))); return; }

            GroupCache.GroupCacheEntry entry = peerManager.getGroupCache().get(groupId);

            Message leaveMsg = Message.builder()
                    .type(MessageType.GROUP_LEAVE.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalAddress())
                    .groupId(groupId)
                    .newOwner(newOwner.isEmpty() ? null : newOwner)
                    .build();
            Message response = peerClient.sendToCoordinator(groupId, leaveMsg);

            // FALLBACK: nếu coordinator không online → gửi GROUP_LEAVE thẳng đến
            // toàn bộ thành viên còn lại để họ tự cập nhật GroupCache local.
            // Không cần coordinator authority — người rời nhóm tự gửi trực tiếp.
            if (MessageType.ERROR.name().equals(response.getType()) && entry != null) {
                logger.warning("All coordinators unreachable for leave " + groupId
                        + " — broadcasting leave directly to members");
                broadcastLeaveDirectly(entry, groupId, peerManager.getLocalAddress());
            }

            peerManager.getGroupCache().remove(groupId);
            broadcastToWeb("GROUP_DISBANDED", Map.of("groupId", groupId));
            ctx.contentType("application/json").result(gson.toJson(Map.of("type", response.getType())));
        });


        app.post("/api/group/disband", ctx -> {
            Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
            String groupId = getString(body, "groupId");
            if (groupId.isEmpty()) { ctx.status(400).result(gson.toJson(Map.of("error", "Missing groupId"))); return; }

            GroupCache.GroupCacheEntry disbandEntry = peerManager.getGroupCache().get(groupId);

            Message disbandMsg = Message.builder()
                    .type(MessageType.GROUP_DISBAND.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalAddress())
                    .groupId(groupId)
                    .build();
            Message response = peerClient.sendToCoordinator(groupId, disbandMsg);

            // FALLBACK: coordinator offline → gửi GROUP_DISBANDED qua Mailbox cho mọi thành viên.
            // Khi họ online lại và pull mailbox, sẽ nhận được thông báo và xóa nhóm khỏi cache.
            if (MessageType.ERROR.name().equals(response.getType()) && disbandEntry != null) {
                logger.warning("All coordinators unreachable for disband " + groupId
                        + " — storing GROUP_DISBANDED in mailbox for all members");
                storeControlEventToMailbox(disbandEntry, groupId,
                        MessageType.GROUP_DISBANDED.name(), null);
            }

            peerManager.getGroupCache().remove(groupId);
            broadcastToWeb("GROUP_DISBANDED", Map.of("groupId", groupId));
            ctx.contentType("application/json").result(gson.toJson(Map.of("type", response.getType())));
        });

        app.post("/api/group/msg", ctx -> {
            Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
            String groupId = getString(body, "groupId");
            String content = getString(body, "content");
            if (groupId.isEmpty() || content.isEmpty()) {
                ctx.status(400).result(gson.toJson(Map.of("error", "Missing groupId or content"))); return;
            }
            if (peerManager.getGroupCache().get(groupId) == null) {
                ctx.status(404).result(gson.toJson(Map.of("error", "Group not found")));
                return;
            }
            new Thread(() -> {
                try {
                    peerClient.sendGroupMessage(peerManager.getLocalUsername(), groupId, content);
                } catch (Exception e) {
                    logger.warning("Async group send failed: " + e.getMessage());
                }
            }, "group-send-" + groupId).start();
            ctx.contentType("application/json").result(gson.toJson(Map.of("accepted", true)));
        });

        // ── Legacy group API (for backward compat) ──
        app.get("/api/groups", ctx -> ctx.contentType("application/json")
                .result(gson.toJson(peerManager.getAllGroups().values())));

        app.post("/api/group/create-legacy", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String groupName = body.get("groupName");
            if (groupName == null) { ctx.status(400).result("Missing groupName"); return; }
            peerManager.createGroup(groupName);
            ctx.contentType("application/json").result(gson.toJson(Map.of("created", true)));
        });

        // ── File Transfer API ──
        app.get("/api/file/transfers", ctx -> {
            var list = peerManager.getFileTransferManager().getAllTransfers();
            ctx.contentType("application/json").result(gson.toJson(list));
        });

        app.post("/api/file/offer", ctx -> {
            // Support multipart upload from React UI
            var uploadedFile = ctx.uploadedFile("file");
            String receiver = ctx.formParam("receiver");
            String groupId = ctx.formParam("groupId");

            if (uploadedFile == null) {
                ctx.status(400).result(gson.toJson(Map.of("error", "No file uploaded")));
                return;
            }

            // Save uploaded file temporarily to Downloads folder
            java.nio.file.Path dir = peerManager.getFileTransferManager().getDownloadDir();
            java.nio.file.Path tempFile = dir.resolve(uploadedFile.filename());
            try (var in = uploadedFile.content(); var out = java.nio.file.Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }

            String transferId;
            if (groupId != null && !groupId.isEmpty()) {
                transferId = peerManager.getFileTransferManager().offerFileToGroup(tempFile, groupId);
            } else if (receiver != null && !receiver.isEmpty()) {
                transferId = peerManager.getFileTransferManager().offerFile(tempFile, receiver);
            } else {
                ctx.status(400).result(gson.toJson(Map.of("error", "Missing receiver or groupId")));
                return;
            }
            ctx.contentType("application/json").result(gson.toJson(Map.of("transferId", transferId)));
        });

        app.post("/api/file/accept", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String transferId = body.get("transferId");
            peerManager.getFileTransferManager().acceptOffer(transferId);
            ctx.contentType("application/json").result(gson.toJson(Map.of("accepted", true)));
        });

        app.post("/api/file/reject", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String transferId = body.get("transferId");
            String reason = body.getOrDefault("reason", "Rejected by user");
            peerManager.getFileTransferManager().rejectOffer(transferId, reason);
            ctx.contentType("application/json").result(gson.toJson(Map.of("rejected", true)));
        });

        app.post("/api/file/cancel", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String transferId = body.get("transferId");
            peerManager.getFileTransferManager().cancelOutbound(transferId);
            ctx.contentType("application/json").result(gson.toJson(Map.of("cancelled", true)));
        });

        app.get("/api/file/download/{transferId}", ctx -> {
            String transferId = ctx.pathParam("transferId");
            var meta = peerManager.getFileTransferManager().getTransfer(transferId);
            if (meta == null) {
                ctx.status(404).result("Transfer not found");
                return;
            }
            if (!"DONE".equals(meta.status.name())) {
                ctx.status(400).result("File is not fully downloaded yet");
                return;
            }
            java.nio.file.Path file = peerManager.getFileTransferManager().getDownloadDir().resolve(meta.filename);
            if (!java.nio.file.Files.exists(file)) {
                ctx.status(404).result("File not found on disk");
                return;
            }
            ctx.header("Content-Disposition", "attachment; filename=\"" + meta.filename + "\"");
            ctx.contentType("application/octet-stream");
            ctx.result(java.nio.file.Files.newInputStream(file));
        });
    }

    // ─────────────────── Helpers ───────────────────

    private void discoverPeers() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerManager.getBootstrapHost(), peerManager.getBootstrapPort()), 3000);
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            Message discover = ProtocolHandler.createDiscover(peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(discover));
            out.flush();
            String line = in.readLine();
            if (line != null) {
                Message resp = JsonUtil.fromJson(line.trim());
                if (resp != null && "PEER_LIST".equals(resp.getType())) {
                    peerManager.parsePeerList(resp.getContent());
                }
            }
        } catch (Exception e) {
            logger.warning("Discover failed: " + e.getMessage());
        }
    }

    private boolean canConnectAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String[] parts = address.split(":");
        if (parts.length != 2) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), Constants.COORDINATOR_TIMEOUT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendCoordInit(String address, GroupInfo info) {
        String[] parts = address.split(":");
        if (parts.length != 2) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), Constants.COORDINATOR_TIMEOUT);
            socket.setSoTimeout(Constants.COORDINATOR_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message initMsg = Message.builder()
                    .type(MessageType.COORD_INIT.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalAddress())
                    .groupId(info.getGroupId())
                    .groupInfo(info)
                    .build();
            out.println(JsonUtil.toJson(initMsg));
        } catch (Exception e) {
            logger.fine("COORD_INIT to " + address + " failed: " + e.getMessage());
        }
    }

    private void sendGroupJoined(String address, GroupInfo info, List<String> coords) {
        String[] parts = address.split(":");
        if (parts.length != 2) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), Constants.COORDINATOR_TIMEOUT);
            socket.setSoTimeout(Constants.COORDINATOR_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message joined = Message.builder()
                    .type(MessageType.GROUP_JOINED.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalAddress())
                    .groupId(info.getGroupId())
                    .groupName(info.getGroupName())
                    .members(new ArrayList<>(info.getMembers()))
                    .coordinators(coords)
                    .version(info.getVersion())
                    .groupMode(info.getGroupMode())
                    .chatHistory(peerManager.getMessageRepository().getGroupHistory(info.getGroupId()))
                    .build();
            out.println(JsonUtil.toJson(joined));
        } catch (Exception e) {
            logger.fine("GROUP_JOINED to " + address + " failed: " + e.getMessage());
        }
    }

    private Map<String, Object> groupInfoToWsMap(GroupInfo info, List<String> coords) {
        Map<String, Object> map = new HashMap<>();
        map.put("groupId", info.getGroupId());
        map.put("groupName", info.getGroupName());
        map.put("owner", info.getOwner());
        map.put("members", info.getMembers());
        map.put("coordinators", coords);
        map.put("version", info.getVersion());
        map.put("groupMode", info.getGroupMode());
        return map;
    }

    /**
     * Fallback khi tất cả coordinator offline: gửi GROUP_LEAVE trực tiếp đến
     * từng thành viên còn online. Mỗi peer nhận sẽ tự xoá leaverAddress khỏi
     * GroupCache local và cập nhật UI (handleGroupUpdated → GROUP_UPDATED WS event).
     *
     * Đây là "eventual consistency" mức P2P — không có coordinator authority,
     * nhưng đảm bảo thành viên online thấy được người đã rời nhóm.
     * Khi coordinator nào đó online lại, gossip/repair sẽ sync lại state chính xác.
     */
    /**
     * Fallback khi tất cả coordinator offline: gửi GROUP_LEAVE trực tiếp đến
     * từng thành viên còn online qua TCP. Nếu TCP cũng fail (member offline),
     * lưu vào Mailbox → khi member online lại pull mailbox sẽ nhận được.
     */
    private void broadcastLeaveDirectly(GroupCache.GroupCacheEntry entry,
                                        String groupId, String leaverAddress) {
        List<String> newMembers = entry.getMembers().stream()
                .filter(m -> !m.equals(leaverAddress))
                .collect(java.util.stream.Collectors.toList());

        Message notify = Message.builder()
                .type(MessageType.GROUP_LEAVE.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(leaverAddress)
                .groupId(groupId)
                .groupName(entry.getGroupName())
                .members(newMembers)         // danh sách sau khi trừ người rời
                .changeType("LEAVE")
                .affected(leaverAddress)
                .timestamp(System.currentTimeMillis())
                .build();

        String payloadJson = new com.google.gson.Gson().toJson(notify);
        String payloadHash = sha256Hex(payloadJson);
        List<String> offlineMembers = new java.util.ArrayList<>();

        for (String member : entry.getMembers()) {
            if (member.equals(leaverAddress)) continue;
            String[] parts = member.split(":");
            if (parts.length != 2) continue;
            boolean tcpSent = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])),
                        Constants.COORDINATOR_TIMEOUT);
                socket.setSoTimeout(Constants.COORDINATOR_TIMEOUT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println(JsonUtil.toJson(notify));
                tcpSent = true;
                logger.info("[LEAVE] Direct TCP notified: " + member);
            } catch (Exception e) {
                logger.fine("[LEAVE] TCP fail for " + member + " — will store in mailbox");
            }

            if (!tcpSent) {
                // Resolve username để mailbox biết deliver cho ai
                String username = resolveUsernameFromAddress(member);
                if (username != null && !username.isBlank()) {
                    offlineMembers.add(username);
                }
            }
        }

        // Lưu vào Mailbox cho những member không online
        if (!offlineMembers.isEmpty()) {
            try {
                boolean stored = peerClient.getMailboxClient().storeGroup(
                        notify, payloadJson, payloadHash,
                        groupId, offlineMembers, entry.getMembers().size());
                if (stored) {
                    logger.info("[LEAVE] Stored GROUP_LEAVE in mailbox for offline members: " + offlineMembers);
                }
            } catch (Exception e) {
                logger.warning("[LEAVE] Failed to store GROUP_LEAVE in mailbox: " + e.getMessage());
            }
        }
    }

    /**
     * Lưu một control-plane event (GROUP_DISBANDED, GROUP_KICKED) vào Mailbox
     * cho tất cả thành viên của nhóm (trừ người gửi).
     */
    private void storeControlEventToMailbox(GroupCache.GroupCacheEntry entry,
                                            String groupId, String eventType,
                                            String excludeAddress) {
        Message notify = Message.builder()
                .type(eventType)
                .messageId(ProtocolHandler.generateMessageId())
                .sender(peerManager.getLocalAddress())
                .groupId(groupId)
                .groupName(entry.getGroupName())
                .timestamp(System.currentTimeMillis())
                .build();

        String payloadJson = new com.google.gson.Gson().toJson(notify);
        String payloadHash = sha256Hex(payloadJson);

        List<String> targetUsernames = new java.util.ArrayList<>();
        for (String member : entry.getMembers()) {
            if (member.equals(excludeAddress) || member.equals(peerManager.getLocalAddress())) continue;
            String username = resolveUsernameFromAddress(member);
            if (username != null && !username.isBlank()) {
                targetUsernames.add(username);
            }
        }

        if (!targetUsernames.isEmpty()) {
            try {
                boolean stored = peerClient.getMailboxClient().storeGroup(
                        notify, payloadJson, payloadHash,
                        groupId, targetUsernames, entry.getMembers().size());
                if (stored) {
                    logger.info("[MAILBOX] Stored " + eventType + " for offline members: " + targetUsernames);
                }
            } catch (Exception e) {
                logger.warning("[MAILBOX] Failed to store " + eventType + ": " + e.getMessage());
            }
        }
    }

    /** Resolve username từ host:port address bằng cách tìm trong known peers. */
    private String resolveUsernameFromAddress(String address) {
        for (var p : peerManager.getAllKnownPeers()) {
            if (address.equals(p.getHost() + ":" + p.getPort())) return p.getUsername();
        }
        return null;
    }

    private String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String getString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private int getInt(Map<String, Object> body, String key, int def) {
        Object v = body.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) try { return Integer.parseInt(v.toString()); } catch (NumberFormatException ignored) {}
        return def;
    }
}
