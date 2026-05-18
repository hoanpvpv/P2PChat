package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;
import java.util.logging.Logger;

public class PeerNode {

    private static final Logger logger = LoggerUtil.getLogger(PeerNode.class.getName());
    private final int port;
    private final int webPort;
    private final PeerManager peerManager;
    private final PeerServer peerServer;
    private final PeerClient peerClient;
    private final WebServer webServer;
    private final CoordinatorManager coordinatorManager;
    private final LazyRepairManager lazyRepairManager;
    private volatile boolean running = false;
    private volatile boolean heartbeatStarted = false;

    public PeerNode(int port, int webPort) {
        this.port = port;
        this.webPort = webPort;

        this.peerManager = new PeerManager("peer-" + port);
        this.peerManager.setBootstrapHost("localhost");
        this.peerManager.setBootstrapPort(Constants.DEFAULT_BOOTSTRAP_PORT);
        this.peerManager.setLocalHost("localhost");
        this.peerManager.setLocalPort(port);
        this.peerManager.setWebPort(webPort);
        this.peerManager.setLocalUsername("");
        this.peerManager.markBootstrapRegistrationFailure("Not connected yet");

        // Wire DHT-lite components
        String myAddress = "localhost:" + port;  // temporary, updated after registration
        this.coordinatorManager = new CoordinatorManager(myAddress, peerManager);
        this.lazyRepairManager = new LazyRepairManager(peerManager.getGroupCache(), myAddress);
        this.peerManager.setLazyRepairManager(lazyRepairManager);

        this.peerServer = new PeerServer(port, peerManager);
        this.peerClient = new PeerClient(peerManager);
        this.webServer = new WebServer(webPort, peerManager, peerClient, this);

        // Wire cross-references
        this.peerServer.setWebServer(webServer);
        this.peerServer.setCoordinatorManager(coordinatorManager);
        this.webServer.setCoordinatorManager(coordinatorManager);
        this.coordinatorManager.setWebServer(webServer);
        this.peerManager.getFileTransferManager().setWebServer(webServer);
    }

    public void setConfig(String username, String host, String bootstrap) {
        if (username != null && !username.isEmpty()) this.peerManager.setLocalUsername(username);
        if (host != null && !host.isEmpty()) this.peerManager.setLocalHost(host);
        if (bootstrap != null && !bootstrap.isEmpty()) {
            String[] parts = bootstrap.split(":");
            this.peerManager.setBootstrapHost(parts[0]);
            if (parts.length > 1) {
                this.peerManager.setBootstrapPort(Integer.parseInt(parts[1]));
            }
        }
    }

    public void start() {
        running = true;
        peerServer.start();
        webServer.start();
        coordinatorManager.start();
        peerManager.getFileTransferManager().start();

        System.out.println("=== P2PChat Peer ===");
        System.out.println("Peer server on port " + port);
        System.out.println("Web UI: http://localhost:" + webPort);
        System.out.println("Open the Web UI and connect to bootstrap server.");
        System.out.println("Type /help for CLI commands.\n");

        startCli();
    }

    public synchronized boolean connectToBootstrap(String username, String host,
                                                    String bootstrapHost, int bootstrapPort) {
        peerManager.setLocalUsername(username);
        peerManager.setLocalHost(host);
        peerManager.setBootstrapHost(bootstrapHost);
        peerManager.setBootstrapPort(bootstrapPort);
        peerManager.clearKnownPeers();

        boolean registered = registerWithBootstrap();
        if (registered && !heartbeatStarted) {
            startHeartbeat();
            // Trigger Repair 3: restart — resync all cached groups
            lazyRepairManager.repairAllOnRestart();
        }
        return registered;
    }

    private boolean registerWithBootstrap() {
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message reg = ProtocolHandler.createRegister(
                    peerManager.getLocalUsername(), peerManager.getLocalHost(), port);
            out.println(JsonUtil.toJson(reg));
            out.flush();

            String line = in.readLine();
            if (line != null) {
                Message resp = JsonUtil.fromJson(line.trim());
                if ("REGISTER_ACK".equals(resp.getType())) {
                    peerManager.parsePeerList(resp.getContent());
                    peerManager.markBootstrapRegistrationSuccess();
                    System.out.println("[BOOTSTRAP] Registered. Peers: " +
                            peerManager.getOnlinePeers().stream().map(PeerInfo::getUsername).toList());
                    return true;
                }
            }
        } catch (IOException e) {
            peerManager.markBootstrapRegistrationFailure("Connection error: " + e.getMessage());
            logger.severe("Registration failed: " + e.getMessage());
            return false;
        }
        peerManager.markBootstrapRegistrationFailure("Bootstrap rejected registration (invalid response)");
        return false;
    }

    private void startHeartbeat() {
        heartbeatStarted = true;
        Thread hb = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(Constants.HEARTBEAT_INTERVAL);
                    if (peerManager.isRegisteredToBootstrap()) sendHeartbeat();
                } catch (InterruptedException e) { break; }
            }
        }, "heartbeat-thread");
        hb.setDaemon(true);
        hb.start();
    }

    private void sendHeartbeat() {
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message hb = ProtocolHandler.createHeartbeat(peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(hb));
            out.flush();

            String line = in.readLine();
            if (line != null) {
                Message resp = JsonUtil.fromJson(line.trim());
                if ("HEARTBEAT_ACK".equals(resp.getType()) &&
                    resp.getContent() != null && !resp.getContent().isEmpty()) {
                    peerManager.parsePeerList(resp.getContent());
                }
                peerManager.markHeartbeatSuccess();  // may trigger Repair 4
            }
        } catch (IOException e) {
            logger.fine("Heartbeat failed: " + e.getMessage());
            peerManager.markBootstrapRegistrationFailure(e.getMessage());
        }
    }

    // ─────────────── CLI ───────────────

    private void startCli() {
        Scanner scanner = new Scanner(System.in);
        while (running) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) processCommand(input);
        }
    }

    private void processCommand(String input) {
        if (!input.startsWith("/")) { System.out.println("Unknown command. Type /help."); return; }
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "/help"      -> printHelp();
            case "/peers"     -> listPeers();
            case "/discover"  -> { if (requireReg()) discoverCli(); }
            case "/msg"       -> { if (requireReg() && parts.length > 1) sendDirectCli(parts[1]); }
            case "/group"     -> { if (requireReg()) handleGroupCli(parts.length > 1 ? parts[1] : ""); }
            case "/broadcast" -> { if (requireReg() && parts.length > 1) peerClient.sendBroadcast(peerManager.getLocalUsername(), parts[1]); }
            case "/history"   -> { if (parts.length > 1) showHistory(parts[1].trim()); }
            case "/exit"      -> shutdown();
            default           -> System.out.println("Unknown command: " + cmd + ". Type /help.");
        }
    }

    private void printHelp() {
        System.out.println("""
=== P2PChat Commands ===
  /help                          - This help
  /peers                         - List online peers
  /discover                      - Refresh peer list
  /msg <peer> <message>          - Send direct message
  /broadcast <message>           - Broadcast to all
  /group create <name> [members] - Create group
  /group list                    - List groups
  /group msg <groupId> <msg>     - Send group message
  /history <peer>                - Chat history
  /exit                          - Exit""");
    }

    private void listPeers() {
        var peers = peerManager.getOnlinePeers();
        if (peers.isEmpty()) { System.out.println("No peers online."); return; }
        System.out.println("=== Online Peers ===");
        peers.forEach(p -> System.out.println("  " + p));
    }

    private void discoverCli() {
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            Message discover = ProtocolHandler.createDiscover(peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(discover));
            out.flush();
            String line = in.readLine();
            if (line != null) {
                Message resp = JsonUtil.fromJson(line.trim());
                if ("PEER_LIST".equals(resp.getType())) {
                    peerManager.parsePeerList(resp.getContent());
                    listPeers();
                }
            }
        } catch (IOException e) {
            System.out.println("[ERROR] Discover failed: " + e.getMessage());
        }
    }

    private void sendDirectCli(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) { System.out.println("Usage: /msg <peer> <message>"); return; }
        peerClient.sendDirectMessage(peerManager.getLocalUsername(), parts[0], parts[1]);
    }

    private void handleGroupCli(String args) {
        String[] parts = args.split("\\s+", 3);
        if (parts.length < 1) { System.out.println("Usage: /group <create|list|msg> ..."); return; }
        switch (parts[0].toLowerCase()) {
            case "create" -> {
                if (parts.length < 2) { System.out.println("Usage: /group create <name>"); return; }
                System.out.println("Use the Web UI to create groups with DHT-lite coordinator.");
            }
            case "list" -> {
                peerManager.getGroupCache().getAllEntries().forEach(e ->
                    System.out.println("  [" + e.getGroupId() + "] " + e.getGroupName() +
                                       " members=" + e.getMembers().size()));
            }
            case "msg" -> {
                if (parts.length < 3) { System.out.println("Usage: /group msg <groupId> <message>"); return; }
                peerClient.sendGroupMessage(peerManager.getLocalUsername(), parts[1], parts[2]);
            }
            default -> System.out.println("Unknown group command.");
        }
    }

    private void showHistory(String peerName) {
        var msgs = peerManager.getMessageRepository().getChatHistory(peerManager.getLocalUsername(), peerName);
        if (msgs.isEmpty()) { System.out.println("No history with " + peerName); return; }
        System.out.println("=== Chat with " + peerName + " ===");
        msgs.forEach(m -> System.out.printf("  [%s] %s -> %s: %s%n",
                com.mycompany.p2pchat.utils.TimeUtil.formatTimestamp(m.getTimestamp()),
                m.getSender(), m.getReceiver(), m.getContent()));
    }

    private boolean requireReg() {
        if (peerManager.isRegisteredToBootstrap()) return true;
        System.out.println("[WARN] Not connected. Use the Web UI to connect first.");
        return false;
    }

    private void notifyBootstrapLeave() {
        if (!peerManager.isRegisteredToBootstrap()) return;
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(2000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(JsonUtil.toJson(ProtocolHandler.createPeerLeave(peerManager.getLocalUsername())));
        } catch (IOException ignored) {}
    }

    public void shutdown() {
        running = false;
        System.out.println("\nShutting down...");
        notifyBootstrapLeave();
        peerManager.getFileTransferManager().stop();
        coordinatorManager.stop();
        webServer.stop();
        peerServer.stop();
        peerClient.shutdown();
        peerManager.shutdown();
        System.out.println("Goodbye!");
        System.exit(0);
    }
}
