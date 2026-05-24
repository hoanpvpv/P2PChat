package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Logger;

public class PeerNode {

    private static final Logger logger = LoggerUtil.getLogger(PeerNode.class.getName());
    private int port;
    private final int webPort;
    private PeerManager peerManager;
    private PeerServer peerServer;
    private final PeerClient peerClient;
    private WebServer webServer;
    private CoordinatorManager coordinatorManager;
    private LazyRepairManager lazyRepairManager;
    private final E2EECrypto e2eeCrypto;
    private volatile boolean running = false;
    private volatile boolean heartbeatStarted = false;
    private volatile boolean mailboxRetryStarted = false;

    public PeerNode(int webPort) {
        this.port = com.mycompany.p2pchat.utils.Constants.DEFAULT_PEER_PORT;
        this.webPort = webPort;

        this.peerManager = new PeerManager("peer-" + this.port);
        this.peerManager.setBootstrapHost("localhost");
        this.peerManager.setBootstrapPort(Constants.DEFAULT_BOOTSTRAP_PORT);
        this.peerManager.setLocalHost("localhost");
        this.peerManager.setLocalPort(this.port);
        this.peerManager.setWebPort(this.webPort);
        this.peerManager.setLocalUsername("");
        this.peerManager.markBootstrapRegistrationFailure("Peer has not been connected to a bootstrap server yet");
        this.e2eeCrypto = E2EECrypto.loadOrCreate();
        this.peerManager.setLocalPublicKey(e2eeCrypto.publicKeyBase64());
        this.peerManager.setLocalKeyId(e2eeCrypto.keyId());

        // We defer starting CoordinatorManager and LazyRepairManager until initPeer()
        // But we can initialize them with a dummy address for now to avoid nulls
        String myAddress = "localhost:" + this.port;
        this.coordinatorManager = new CoordinatorManager(myAddress, peerManager);
        this.peerManager.setCoordinatorManager(this.coordinatorManager);
        this.lazyRepairManager = new LazyRepairManager(peerManager.getGroupCache(), myAddress);
        this.peerManager.setLazyRepairManager(lazyRepairManager);

        this.peerServer = null;
        this.peerClient = new PeerClient(peerManager);
        this.webServer = new WebServer(webPort, peerManager, peerClient, this);
        // Wire cross-references
        this.webServer.setCoordinatorManager(coordinatorManager);
        this.coordinatorManager.setWebServer(webServer);
        this.peerManager.getFileTransferManager().setWebServer(webServer);
    }

    public void setConfig(String username, String host, int peerPort, String bootstrap) {
        if (username != null && !username.isEmpty()) this.peerManager.setLocalUsername(username);
        if (host != null && !host.isEmpty()) this.peerManager.setLocalHost(host);
        if (peerPort > 0) {
            this.port = peerPort;
            this.peerManager.setLocalPort(peerPort);
        }
        if (bootstrap != null && !bootstrap.isEmpty()) {
            String[] parts = bootstrap.split(":");
            this.peerManager.setBootstrapHost(parts[0]);
            if (parts.length > 1) {
                this.peerManager.setBootstrapPort(Integer.parseInt(parts[1]));
            }
        }
    }

    public void setMailboxConfig(String mailbox) {
        if (mailbox == null || mailbox.isEmpty()) return;
        String[] parts = mailbox.split(":");
        peerManager.setMailboxHost(parts[0]);
        if (parts.length > 1) {
            peerManager.setMailboxPort(Integer.parseInt(parts[1]));
        }
    }

    public void start() {
        running = true;
        webServer.start();
        coordinatorManager.start();
        startMailboxRetryWorker();

        if (peerManager.getLocalUsername() != null && !peerManager.getLocalUsername().isBlank()
                && peerManager.getLocalPort() > 0) {
            initPeer(peerManager.getLocalPort(), peerManager.getLocalUsername());
        }

        System.out.println("=== P2PChat Peer ===");
        System.out.println("Web UI: http://localhost:" + webPort);
        System.out.println("Open the web UI to set up your peer.");
        System.out.println("Type /help for available commands after connecting.\n");

        startCli();
    }

    public synchronized void initPeer(int peerPort, String username) {
        this.port = peerPort;
        this.peerManager.setLocalPort(peerPort);
        this.peerManager.setLocalUsername(username);
        this.peerManager.clearKnownPeers();
        
        String myAddress = peerManager.getLocalHost() + ":" + peerPort;

        if (this.peerServer != null) {
            this.peerServer.stop();
        }
        
        if (this.coordinatorManager != null) {
            this.coordinatorManager.stop();
        }

        this.coordinatorManager = new CoordinatorManager(myAddress, peerManager);
        this.coordinatorManager.setWebServer(webServer);
        this.peerManager.setCoordinatorManager(this.coordinatorManager);
        this.coordinatorManager.start();

        this.lazyRepairManager = new LazyRepairManager(peerManager.getGroupCache(), myAddress);
        this.peerManager.setLazyRepairManager(lazyRepairManager);

        this.peerServer = new PeerServer(peerPort, peerManager);
        this.peerServer.setWebServer(webServer);
        this.peerServer.setCoordinatorManager(this.coordinatorManager);
        this.peerServer.setPeerClient(peerClient);
        this.peerManager.setPeerServer(this.peerServer);
        this.peerServer.start();
        peerManager.getFileTransferManager().restartForCurrentPeerPort();

        this.webServer.setCoordinatorManager(this.coordinatorManager);
        peerManager.setLocalUsername(username);
        peerManager.clearKnownPeers();

        boolean registered = registerWithBootstrap();
        if (registered && !heartbeatStarted) {
            peerClient.resolveMailboxFromBootstrap();
            pullMailboxMessagesToWeb();
            startHeartbeat();
            // Trigger Repair 3: restart — resync all cached groups
            lazyRepairManager.repairAllOnRestart();
        }
    }

    private boolean registerWithBootstrap() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerManager.getBootstrapHost(), peerManager.getBootstrapPort()), 3000);
            socket.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message reg = ProtocolHandler.createRegister(
                    peerManager.getLocalUsername(), peerManager.getLocalHost(), port,
                    peerManager.getLocalKeyId(), peerManager.getLocalPublicKey());
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

    private void startMailboxRetryWorker() {
        if (mailboxRetryStarted) return;
        mailboxRetryStarted = true;
        Thread retry = new Thread(() -> {
            int tick = 0;
            while (running) {
                try {
                    Thread.sleep(5000);
                    tick++;
                    // Re-resolve mailbox endpoint every ~30s so peers recover if the
                    // bootstrap-advertised endpoint changes (e.g. infra reconfigured,
                    // or a remote peer was given a Docker-DNS-only address and the
                    // bootstrap later started advertising a host-reachable IP).
                    if (tick % 6 == 0 && peerManager.isRegisteredToBootstrap()) {
                        peerClient.resolveMailboxFromBootstrap();
                    }
                    peerClient.retryMailboxOutbox();
                    peerClient.retryOutboxDelivery();
                    peerManager.getOutboxRepository().cleanupDeliveredOlderThan(
                            System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
                    if (peerManager.isRegisteredToBootstrap()) {
                        pullMailboxMessagesToWeb();
                        // Promote STORED_MAILBOX → DELIVERED for messages the receiver
                        // has now pulled, so the sender UI stops showing them as pending.
                        peerClient.pollMailboxDeliveries();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.fine("Mailbox retry worker error: " + e.getMessage());
                }
            }
        }, "mailbox-retry-thread");
        retry.setDaemon(true);
        retry.start();
    }

    private void pullMailboxMessagesToWeb() {
        for (Message message : peerClient.pullMailboxMessages()) {
            String eventType = "DIRECT_MESSAGE";
            if ("BROADCAST".equals(message.getType())) eventType = "BROADCAST";
            if ("GROUP_MESSAGE".equals(message.getType())) eventType = "GROUP_MESSAGE";
            webServer.broadcastToWeb(eventType, messageToMap(message));
        }
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
        return map;
    }

    private void sendHeartbeat() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerManager.getBootstrapHost(), peerManager.getBootstrapPort()), 3000);
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message hb = ProtocolHandler.createHeartbeat(peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(hb));
            out.flush();

            String line = in.readLine();
            if (line != null) {
                Message resp = JsonUtil.fromJson(line.trim());
                if ("REGISTER_NACK".equals(resp.getType())) {
                    logger.warning("Bootstrap lost this peer registration; registering again");
                    peerManager.markBootstrapRegistrationFailure("Bootstrap requested re-register");
                    if (registerWithBootstrap()) {
                        peerClient.resolveMailboxFromBootstrap();
                        pullMailboxMessagesToWeb();
                        lazyRepairManager.repairAllOnRestart();
                    }
                    return;
                }
                if ("HEARTBEAT_ACK".equals(resp.getType())) {
                    if (resp.getContent() != null) {
                        peerManager.parsePeerList(resp.getContent());
                    }
                    peerManager.markHeartbeatSuccess();  // may trigger Repair 4
                }
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
            case "/help":
                printHelp();
                break;
            case "/peers":
                listPeers();
                break;
            case "/discover":
                if (requireReg()) discoverCli();
                break;
            case "/msg":
                if (requireReg() && parts.length > 1) sendDirectCli(parts[1]);
                break;
            case "/group":
                if (requireReg()) handleGroupCli(parts.length > 1 ? parts[1] : "");
                break;
            case "/broadcast":
                if (requireReg() && parts.length > 1) peerClient.sendBroadcast(peerManager.getLocalUsername(), parts[1]);
                break;
            case "/history":
                if (parts.length > 1) showHistory(parts[1].trim());
                break;
            case "/exit":
                shutdown();
                break;
            default:
                System.out.println("Unknown command: " + cmd + ". Type /help.");
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerManager.getBootstrapHost(), peerManager.getBootstrapPort()), 2000);
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
        if (peerServer != null) {
            peerServer.stop();
        }
        peerClient.shutdown();
        peerManager.shutdown();
        System.out.println("Goodbye!");
        System.exit(0);
    }
}
