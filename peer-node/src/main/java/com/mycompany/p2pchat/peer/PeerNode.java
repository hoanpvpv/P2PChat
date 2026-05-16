package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.database.MessageRepository;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
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
    private volatile boolean running = false;
    private volatile boolean heartbeatStarted = false;

    public PeerNode(int port, int webPort) {
        this.port = port;
        this.webPort = webPort;

        this.peerManager = new PeerManager("peer-" + port);
        this.peerManager.setBootstrapHost("localhost");
        this.peerManager.setBootstrapPort(com.mycompany.p2pchat.utils.Constants.DEFAULT_BOOTSTRAP_PORT);
        this.peerManager.setLocalUsername("");
        this.peerManager.setLocalHost("localhost");
        this.peerManager.setLocalPort(port);
        this.peerManager.setWebPort(webPort);
        this.peerManager.markBootstrapRegistrationFailure("Peer has not been connected to a bootstrap server yet");

        this.peerServer = new PeerServer(port, peerManager);
        this.peerClient = new PeerClient(peerManager);
        this.webServer = new WebServer(webPort, peerManager, peerClient, this);
        this.peerServer.setWebServer(webServer);
    }

    public void start() {
        running = true;
        peerServer.start();
        webServer.start();

        System.out.println("=== P2PChat Peer ===");
        System.out.println("Peer server listening on port " + port);
        System.out.println("Web UI: http://localhost:" + webPort);
        System.out.println("Open the web UI and enter username, host, bootstrap host, and bootstrap port to connect.");
        System.out.println("Type /help for available commands after connecting.\n");

        startCli();
    }

    public synchronized boolean connectToBootstrap(String username, String host, String bootstrapHost, int bootstrapPort) {
        peerManager.setLocalUsername(username);
        peerManager.setLocalHost(host);
        peerManager.setBootstrapHost(bootstrapHost);
        peerManager.setBootstrapPort(bootstrapPort);
        peerManager.clearKnownPeers();

        boolean registered = registerWithBootstrap();
        if (registered && !heartbeatStarted) {
            startHeartbeat();
        }
        return registered;
    }

    private boolean registerWithBootstrap() {
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message registerMsg = ProtocolHandler.createRegister(
                    peerManager.getLocalUsername(),
                    peerManager.getLocalHost(),
                    port);
            out.println(JsonUtil.toJson(registerMsg));
            out.flush();

            String responseLine = in.readLine();
            if (responseLine != null) {
                Message response = JsonUtil.fromJson(responseLine.trim());
                if ("REGISTER_ACK".equals(response.getType())) {
                    peerManager.parsePeerList(response.getContent());
                    peerManager.markBootstrapRegistrationSuccess();
                    System.out.println("[BOOTSTRAP] Registered. Online peers: " +
                            peerManager.getOnlinePeers().stream().map(PeerInfo::getUsername).toList());
                    return true;
                }
            }
        } catch (IOException e) {
            peerManager.markBootstrapRegistrationFailure(e.getMessage());
            logger.severe("Failed to register with bootstrap: " + e.getMessage());
            return false;
        }
        peerManager.markBootstrapRegistrationFailure("Bootstrap server rejected registration");
        return false;
    }

    private void startHeartbeat() {
        heartbeatStarted = true;
        Thread heartbeatThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(com.mycompany.p2pchat.utils.Constants.HEARTBEAT_INTERVAL);
                    if (peerManager.isRegisteredToBootstrap()) {
                        sendHeartbeat();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "heartbeat-thread");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void sendHeartbeat() {
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message heartbeat = ProtocolHandler.createHeartbeat(peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(heartbeat));
            out.flush();

            String line = in.readLine();
            if (line != null) {
                Message response = JsonUtil.fromJson(line.trim());
                if ("HEARTBEAT_ACK".equals(response.getType()) && response.getContent() != null && !response.getContent().isEmpty()) {
                    peerManager.parsePeerList(response.getContent());
                }
                peerManager.markBootstrapRegistrationSuccess();
            }
        } catch (IOException e) {
            logger.fine("Heartbeat failed: " + e.getMessage());
            peerManager.markBootstrapRegistrationFailure(e.getMessage());
        }
    }

    private void startCli() {
        Scanner scanner = new Scanner(System.in);
        while (running) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            processCommand(input);
        }
        scanner.close();
    }

    private void processCommand(String input) {
        if (input.startsWith("/")) {
            handleSlashCommand(input);
        } else {
            System.out.println("Unknown command. Type /help for available commands.");
        }
    }

    private void handleSlashCommand(String input) {
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
                discoverPeers();
                break;
            case "/msg":
                if (parts.length < 2) {
                    System.out.println("Usage: /msg <peer> <message>");
                    break;
                }
                sendDirectMsg(parts[1]);
                break;
            case "/group":
                handleGroupCommand(parts.length > 1 ? parts[1] : "");
                break;
            case "/broadcast":
                if (parts.length < 2) {
                    System.out.println("Usage: /broadcast <message>");
                    break;
                }
                peerClient.sendBroadcast(username, parts[1]);
                break;
            case "/history":
                if (parts.length < 2) {
                    System.out.println("Usage: /history <peer>");
                    break;
                }
                showHistory(parts[1].trim());
                break;
            case "/exit":
                shutdown();
                break;
            default:
                System.out.println("Unknown command: " + cmd + ". Type /help.");
        }
    }

    private void printHelp() {
        System.out.println("=== Available Commands ===");
        System.out.println("  Connect from the Web UI before using chat commands.");
        System.out.println("  /help                     - Show this help");
        System.out.println("  /peers                    - List online peers");
        System.out.println("  /discover                 - Refresh peer list from bootstrap");
        System.out.println("  /msg <peer> <message>     - Send direct message");
        System.out.println("  /broadcast <message>      - Send to all peers");
        System.out.println("  /group create <name>      - Create group");
        System.out.println("  /group add <name> <peer>  - Add peer to group");
        System.out.println("  /group list               - List groups");
        System.out.println("  /group msg <name> <msg>   - Send group message");
        System.out.println("  /history <peer>           - Show chat history");
        System.out.println("  /exit                     - Exit");
    }

    private void listPeers() {
        var peers = peerManager.getOnlinePeers();
        if (peers.isEmpty()) {
            System.out.println("No peers online.");
            return;
        }
        System.out.println("=== Online Peers ===");
        for (PeerInfo p : peers) {
            System.out.println("  " + p);
        }
    }

    private void discoverPeers() {
        if (!requireRegistration()) return;
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            Message discover = ProtocolHandler.createDiscover(peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(discover));
            out.flush();

            String line = in.readLine();
            if (line != null) {
                Message response = JsonUtil.fromJson(line.trim());
                if ("PEER_LIST".equals(response.getType())) {
                    peerManager.parsePeerList(response.getContent());
                    System.out.println("[DISCOVER] Updated peer list:");
                    listPeers();
                }
            }
        } catch (IOException e) {
            System.out.println("[ERROR] Discovery failed: " + e.getMessage());
        }
    }

    private void sendDirectMsg(String args) {
        if (!requireRegistration()) return;
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("Usage: /msg <peer> <message>");
            return;
        }
        String receiver = parts[0];
        String content = parts[1];
        boolean sent = peerClient.sendDirectMessage(peerManager.getLocalUsername(), receiver, content);
        if (sent) {
            System.out.println("[SENT] -> " + receiver + ": " + content);
        }
    }

    private void handleGroupCommand(String args) {
        if (!requireRegistration()) return;
        String[] parts = args.split("\\s+");
        if (parts.length < 1) {
            System.out.println("Usage: /group <create|add|list|msg> ...");
            return;
        }
        String subCmd = parts[0].toLowerCase();
        switch (subCmd) {
            case "create":
                if (parts.length < 2) { System.out.println("Usage: /group create <name>"); return; }
                peerManager.createGroup(parts[1]);
                break;
            case "add":
                if (parts.length < 3) { System.out.println("Usage: /group add <name> <peer>"); return; }
                peerManager.addToGroup(parts[1], parts[2]);
                break;
            case "list":
                peerManager.listGroups();
                break;
            case "msg":
                if (parts.length < 3) { System.out.println("Usage: /group msg <name> <message>"); return; }
                String groupName = parts[1];
                String content = parts[2];
                peerClient.sendGroupMessage(peerManager.getLocalUsername(), groupName, content);
                System.out.println("[GROUP-SENT] [" + groupName + "]: " + content);
                break;
            default:
                System.out.println("Unknown group command: " + subCmd);
        }
    }

    private void showHistory(String peerName) {
        MessageRepository repo = peerManager.getMessageRepository();
        var messages = repo.getChatHistory(peerManager.getLocalUsername(), peerName);
        if (messages.isEmpty()) {
            System.out.println("No chat history with " + peerName);
            return;
        }
        System.out.println("=== Chat history with " + peerName + " ===");
        for (Message msg : messages) {
            String time = com.mycompany.p2pchat.utils.TimeUtil.formatTimestamp(msg.getTimestamp());
            String direction = msg.getSender().equals(peerManager.getLocalUsername()) ? "you -> " + msg.getReceiver() : msg.getSender() + " -> you";
            System.out.println("  [" + time + "] " + direction + ": " + msg.getContent());
        }
    }

    private void notifyBootstrapLeave() {
        if (!peerManager.isRegisteredToBootstrap()) {
            return;
        }
        try (Socket socket = new Socket(peerManager.getBootstrapHost(), peerManager.getBootstrapPort())) {
            socket.setSoTimeout(2000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message leaveMsg = ProtocolHandler.createPeerLeave(peerManager.getLocalUsername());
            out.println(JsonUtil.toJson(leaveMsg));
            out.flush();
        } catch (IOException e) {
            // ignore
        }
    }

    private boolean requireRegistration() {
        if (peerManager.isRegisteredToBootstrap()) {
            return true;
        }
        System.out.println("[WARN] This peer is not connected yet. Open the Web UI and complete the connection form first.");
        return false;
    }

    public void shutdown() {
        running = false;
        System.out.println("\nShutting down peer...");
        notifyBootstrapLeave();
        webServer.stop();
        peerServer.stop();
        peerClient.shutdown();
        peerManager.shutdown();
        System.out.println("Goodbye!");
        System.exit(0);
    }
}
