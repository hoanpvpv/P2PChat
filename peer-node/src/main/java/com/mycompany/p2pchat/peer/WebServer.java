package com.mycompany.p2pchat.peer;

import com.google.gson.Gson;
import com.mycompany.p2pchat.database.MessageRepository;
import com.mycompany.p2pchat.model.ChatGroup;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.utils.LoggerUtil;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public WebServer(int port, PeerManager peerManager, PeerClient peerClient, PeerNode peerNode) {
        this.port = port;
        this.peerManager = peerManager;
        this.peerClient = peerClient;
        this.peerNode = peerNode;
    }

    public void start() {
        app = Javalin.create(config -> {
            config.staticFiles.add("/static", Location.CLASSPATH);
        });

        app.exception(Exception.class, (e, ctx) -> {
            logger.severe("API error: " + e.getMessage());
            ctx.status(500);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error")));
        });

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                wsClients.add(ctx);
                logger.info("WebSocket client connected");
            });
            ws.onClose(ctx -> {
                wsClients.remove(ctx);
                logger.info("WebSocket client disconnected");
            });
            ws.onMessage(ctx -> {
                String msg = ctx.message();
                logger.fine("WebSocket received: " + msg);
            });
        });

        registerApiRoutes();

        app.start(port);
        logger.info("Web server started on port " + port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    public void broadcastToWeb(String type, Object data) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("data", data);
        String json = gson.toJson(event);
        for (io.javalin.websocket.WsContext client : wsClients) {
            client.send(json);
        }
    }

    private void registerApiRoutes() {
        app.get("/api/info", ctx -> {
            Map<String, Object> info = new HashMap<>();
            info.put("username", peerManager.getLocalUsername());
            info.put("host", peerManager.getLocalHost());
            info.put("peerPort", peerManager.getLocalPort());
            info.put("webPort", peerManager.getWebPort());
            info.put("bootstrapHost", peerManager.getBootstrapHost());
            info.put("bootstrapPort", peerManager.getBootstrapPort());
            info.put("bootstrap", peerManager.getBootstrapHost() + ":" + peerManager.getBootstrapPort());
            info.put("registered", peerManager.isRegisteredToBootstrap());
            info.put("lastBootstrapError", peerManager.getLastBootstrapError());
            ctx.contentType("application/json");
            ctx.result(gson.toJson(info));
        });

        app.get("/api/peers", ctx -> {
            List<PeerInfo> peers = peerManager.getOnlinePeers();
            ctx.contentType("application/json");
            ctx.result(gson.toJson(peers));
        });

        app.get("/api/discover", ctx -> {
            if (!peerManager.isRegisteredToBootstrap()) {
                ctx.status(400).result(gson.toJson(Map.of("error", "Peer is not connected to bootstrap")));
                return;
            }
            try (java.net.Socket socket = new java.net.Socket(
                    peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
                socket.setSoTimeout(3000);
                java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true);
                java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream()));

                Message discover = com.mycompany.p2pchat.protocol.ProtocolHandler.createDiscover(peerManager.getLocalUsername());
                out.println(JsonUtil.toJson(discover));
                out.flush();

                String line = in.readLine();
                if (line != null) {
                    Message response = JsonUtil.fromJson(line.trim());
                    if ("PEER_LIST".equals(response.getType())) {
                        peerManager.parsePeerList(response.getContent());
                    }
                }
            } catch (Exception e) {
                logger.warning("Discover failed: " + e.getMessage());
            }
            ctx.contentType("application/json");
            ctx.result(gson.toJson(peerManager.getOnlinePeers()));
        });

        app.get("/api/history/{peerName}", ctx -> {
            String peerName = ctx.pathParam("peerName");
            MessageRepository repo = peerManager.getMessageRepository();
            List<Message> messages = repo.getChatHistory(peerManager.getLocalUsername(), peerName);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(messages));
        });

        app.get("/api/group-history/{groupName}", ctx -> {
            String groupName = ctx.pathParam("groupName");
            MessageRepository repo = peerManager.getMessageRepository();
            List<Message> messages = repo.getGroupHistory(groupName);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(messages));
        });

        app.get("/api/groups", ctx -> {
            Map<String, ChatGroup> groups = peerManager.getAllGroups();
            ctx.contentType("application/json");
            ctx.result(gson.toJson(groups.values()));
        });

        app.post("/api/msg", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String receiver = body.get("receiver");
            String content = body.get("content");
            if (receiver == null || content == null) {
                ctx.status(400).result("Missing receiver or content");
                return;
            }
            boolean sent = peerClient.sendDirectMessage(peerManager.getLocalUsername(), receiver, content);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(Map.of("sent", String.valueOf(sent))));
        });

        app.get("/api/auto-detect-host", ctx -> {
            Map<String, Object> result = new HashMap<>();
            try (java.net.Socket s = new java.net.Socket(
                    peerManager.getBootstrapHost(),
                    peerManager.getBootstrapPort())) {
                s.setSoTimeout(2000);
                String localIp = s.getLocalAddress().getHostAddress();
                result.put("host", localIp);
                result.put("status", "bootstrap_reachable");
            } catch (Exception e) {
                result.put("host", "localhost");
                result.put("status", "bootstrap_unreachable");
            }
            ctx.contentType("application/json");
            ctx.result(gson.toJson(result));
        });

        app.post("/api/init", ctx -> {
            Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
            String username = body.get("username") != null ? body.get("username").toString().trim() : "";
            Number peerPortValue = body.get("peerPort") instanceof Number ? (Number) body.get("peerPort") : null;
            int peerPort = peerPortValue != null ? peerPortValue.intValue() : 0;

            if (username.isEmpty()) {
                ctx.status(400).result(gson.toJson(Map.of("error", "Missing username")));
                return;
            }
            if (peerPort <= 0 || peerPort > 65535) {
                ctx.status(400).result(gson.toJson(Map.of("error", "Invalid peer port")));
                return;
            }

            peerNode.initPeer(peerPort, username);

            Map<String, Object> response = new HashMap<>();
            response.put("initialized", true);
            response.put("username", peerManager.getLocalUsername());
            response.put("peerPort", peerManager.getLocalPort());
            response.put("registered", peerManager.isRegisteredToBootstrap());
            response.put("lastBootstrapError", peerManager.getLastBootstrapError());
            ctx.contentType("application/json");
            ctx.result(gson.toJson(response));
        });

        app.post("/api/broadcast", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String content = body.get("content");
            if (content == null) {
                ctx.status(400).result("Missing content");
                return;
            }
            peerClient.sendBroadcast(peerManager.getLocalUsername(), content);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(Map.of("sent", "true")));
        });

        app.post("/api/group/create", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String groupName = body.get("groupName");
            if (groupName == null) {
                ctx.status(400).result("Missing groupName");
                return;
            }
            peerManager.createGroup(groupName);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(Map.of("created", "true")));
        });

        app.post("/api/group/add", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String groupName = body.get("groupName");
            String username = body.get("username");
            if (groupName == null || username == null) {
                ctx.status(400).result("Missing groupName or username");
                return;
            }
            peerManager.addToGroup(groupName, username);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(Map.of("added", "true")));
        });

        app.post("/api/group/msg", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String groupName = body.get("groupName");
            String content = body.get("content");
            if (groupName == null || content == null) {
                ctx.status(400).result("Missing groupName or content");
                return;
            }
            peerClient.sendGroupMessage(peerManager.getLocalUsername(), groupName, content);
            ctx.contentType("application/json");
            ctx.result(gson.toJson(Map.of("sent", "true")));
        });
    }
}
