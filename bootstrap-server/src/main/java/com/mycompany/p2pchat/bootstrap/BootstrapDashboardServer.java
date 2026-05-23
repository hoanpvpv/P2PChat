package com.mycompany.p2pchat.bootstrap;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class BootstrapDashboardServer {

    private static final Logger logger = LoggerUtil.getLogger(BootstrapDashboardServer.class.getName());
    private static final Gson gson = new Gson();

    private final int port;
    private final BootstrapServer bootstrapServer;
    private HttpServer httpServer;

    public BootstrapDashboardServer(int port, BootstrapServer bootstrapServer) {
        this.port = port;
        this.bootstrapServer = bootstrapServer;
    }

    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/", this::handleIndex);
            httpServer.createContext("/api/status", this::handleStatus);
            httpServer.createContext("/api/peers", this::handlePeers);
            httpServer.createContext("/api/events", this::handleEvents);
            httpServer.setExecutor(null);
            httpServer.start();
            logger.info("Bootstrap Dashboard started on port " + port);
            System.out.println("=== Bootstrap Dashboard running on http://localhost:" + port + " ===");
        } catch (IOException e) {
            logger.severe("Failed to start dashboard server: " + e.getMessage());
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }

        try (InputStream input = getClass().getResourceAsStream("/static/index.html")) {
            if (input == null) {
                sendText(exchange, 404, "Dashboard asset not found", "text/plain; charset=utf-8");
                return;
            }
            byte[] body = input.readAllBytes();
            sendBytes(exchange, 200, body, "text/html; charset=utf-8");
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }

        PeerRegistry registry = bootstrapServer.getRegistry();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverHost", resolveServerHost());
        payload.put("bootstrapPort", bootstrapServer.getPort());
        payload.put("dashboardPort", port);
        payload.put("mailboxHost", bootstrapServer.getMailboxHost());
        payload.put("mailboxPort", bootstrapServer.getMailboxPort());
        payload.put("running", bootstrapServer.isRunning());
        payload.put("onlinePeerCount", registry.getOnlinePeerCount());
        payload.put("knownPeerCount", registry.getAllPeerCount());
        payload.put("offlineMessageCount", registry.getOfflineMessageCount());
        sendJson(exchange, payload);
    }

    private void handlePeers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }

        List<PeerInfo> peers = bootstrapServer.getRegistry().getAllPeers();
        sendJson(exchange, peers);
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }
        sendJson(exchange, bootstrapServer.getEventLog().snapshot());
    }

    private void sendJson(HttpExchange exchange, Object payload) throws IOException {
        byte[] body = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        sendBytes(exchange, 200, body, "application/json; charset=utf-8");
    }

    private void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        sendBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private String resolveServerHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
