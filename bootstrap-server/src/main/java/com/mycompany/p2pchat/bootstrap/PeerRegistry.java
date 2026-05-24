package com.mycompany.p2pchat.bootstrap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PeerRegistry {

    private static final Logger logger = LoggerUtil.getLogger(PeerRegistry.class.getName());
    private static final Gson gson = new Gson();
    private static final Type PEER_LIST_TYPE = new TypeToken<List<PeerInfo>>() {}.getType();
    private final Path registryFile;
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> offlineMessages = new ConcurrentHashMap<>();

    public PeerRegistry() {
        String dataDir = System.getenv().getOrDefault("BOOTSTRAP_DATA_DIR", "/app/data");
        this.registryFile = Path.of(dataDir, "registry.json");
        loadPeers();
    }

    public boolean register(PeerInfo peer) {
        boolean alreadyKnown = peers.containsKey(peer.getUsername());
        if (alreadyKnown) logger.warning("Replacing peer registration: " + peer.getUsername());
        peer.setOnline(true);
        peer.setLastHeartbeat(System.currentTimeMillis());
        peers.put(peer.getUsername(), peer);
        logger.info("Peer registered: " + peer);
        savePeers();
        return !alreadyKnown;
    }

    public void unregister(String username) {
        PeerInfo peer = peers.get(username);
        if (peer != null) {
            peer.setOnline(false);
            logger.info("Peer marked offline: " + username);
            savePeers();
        }
    }

    public void updateHeartbeat(String username) {
        PeerInfo peer = peers.get(username);
        if (peer != null) {
            peer.setLastHeartbeat(System.currentTimeMillis());
            peer.setOnline(true);
            savePeers();
        }
    }

    public List<PeerInfo> getOnlinePeers() {
        return peers.values().stream()
                .filter(PeerInfo::isOnline)
                .collect(Collectors.toList());
    }

    public List<PeerInfo> getAllPeers() {
        return new ArrayList<>(peers.values());
    }

    public PeerInfo getPeer(String username) {
        return peers.get(username);
    }

    public boolean contains(String username) {
        return peers.containsKey(username);
    }

    public List<String> checkDeadPeers(long timeout) {
        long now = System.currentTimeMillis();
        List<String> deadPeers = new ArrayList<>();
        for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
            if (entry.getValue().isOnline() && now - entry.getValue().getLastHeartbeat() > timeout) {
                entry.getValue().setOnline(false);
                deadPeers.add(entry.getKey());
                logger.warning("Peer timeout: " + entry.getKey());
            }
        }
        if (!deadPeers.isEmpty()) savePeers();
        return deadPeers;
    }

    public void removeDeadPeer(String username) {
        PeerInfo peer = peers.get(username);
        if (peer != null) {
            peer.setOnline(false);
            savePeers();
        }
    }

    public void storeOfflineMessage(String receiver, Message message) {
        offlineMessages.computeIfAbsent(receiver, k -> Collections.synchronizedList(new ArrayList<>())).add(message);
        logger.info("Stored offline message for " + receiver);
    }

    public List<Message> getOfflineMessages(String username) {
        List<Message> messages = offlineMessages.remove(username);
        return messages != null ? messages : new ArrayList<>();
    }

    public String getPeerListJson() {
        List<PeerInfo> online = getOnlinePeers();
        return online.stream()
                .map(this::peerListEntry)
                .collect(Collectors.joining(","));
    }

    private String peerListEntry(PeerInfo p) {
        String entry = p.getUsername() + "@" + p.getHost() + ":" + p.getPort();
        if (p.getKeyId() != null && !p.getKeyId().isBlank()
                && p.getPublicKey() != null && !p.getPublicKey().isBlank()) {
            entry += "|" + p.getKeyId() + "|" + p.getPublicKey();
        }
        return entry;
    }

    public int getOnlinePeerCount() {
        return (int) peers.values().stream()
                .filter(PeerInfo::isOnline)
                .count();
    }

    public int getAllPeerCount() {
        return peers.size();
    }

    public int getOfflineMessageCount() {
        int total = 0;
        for (List<Message> messages : offlineMessages.values()) {
            synchronized (messages) {
                total += messages.size();
            }
        }
        return total;
    }

    private synchronized void loadPeers() {
        if (!Files.exists(registryFile)) return;
        try {
            String json = Files.readString(registryFile, StandardCharsets.UTF_8);
            List<PeerInfo> loaded = gson.fromJson(json, PEER_LIST_TYPE);
            if (loaded == null) return;
            for (PeerInfo peer : loaded) {
                if (peer.getUsername() == null || peer.getUsername().isBlank()) continue;
                peer.setOnline(false);
                peers.put(peer.getUsername(), peer);
            }
            logger.info("Loaded " + peers.size() + " peer registrations from " + registryFile);
        } catch (Exception e) {
            logger.warning("Failed to load bootstrap registry: " + e.getMessage());
        }
    }

    private synchronized void savePeers() {
        try {
            Files.createDirectories(registryFile.getParent());
            Files.writeString(
                    registryFile,
                    gson.toJson(new ArrayList<>(peers.values())),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("Failed to save bootstrap registry: " + e.getMessage());
        }
    }
}
