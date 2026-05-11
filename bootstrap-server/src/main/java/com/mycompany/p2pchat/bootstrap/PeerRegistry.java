package com.mycompany.p2pchat.bootstrap;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PeerRegistry {

    private static final Logger logger = LoggerUtil.getLogger(PeerRegistry.class.getName());
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> offlineMessages = new ConcurrentHashMap<>();

    public boolean register(PeerInfo peer) {
        if (peers.containsKey(peer.getUsername())) {
            logger.warning("Peer already registered: " + peer.getUsername());
            return false;
        }
        peer.setOnline(true);
        peer.setLastHeartbeat(System.currentTimeMillis());
        peers.put(peer.getUsername(), peer);
        logger.info("Peer registered: " + peer);
        return true;
    }

    public void unregister(String username) {
        PeerInfo peer = peers.remove(username);
        if (peer != null) {
            logger.info("Peer unregistered: " + username);
        }
    }

    public void updateHeartbeat(String username) {
        PeerInfo peer = peers.get(username);
        if (peer != null) {
            peer.setLastHeartbeat(System.currentTimeMillis());
            peer.setOnline(true);
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
            if (now - entry.getValue().getLastHeartbeat() > timeout) {
                entry.getValue().setOnline(false);
                deadPeers.add(entry.getKey());
                logger.warning("Peer timeout: " + entry.getKey());
            }
        }
        return deadPeers;
    }

    public void removeDeadPeer(String username) {
        peers.remove(username);
    }

    public void storeOfflineMessage(String receiver, Message message) {
        offlineMessages.computeIfAbsent(receiver, k -> new ArrayList<>()).add(message);
        logger.info("Stored offline message for " + receiver);
    }

    public List<Message> getOfflineMessages(String username) {
        List<Message> messages = offlineMessages.remove(username);
        return messages != null ? messages : new ArrayList<>();
    }

    public String getPeerListJson() {
        List<PeerInfo> online = getOnlinePeers();
        return online.stream()
                .map(p -> p.getUsername() + "@" + p.getHost() + ":" + p.getPort())
                .collect(Collectors.joining(","));
    }
}
