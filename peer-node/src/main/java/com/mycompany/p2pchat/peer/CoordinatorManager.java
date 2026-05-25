package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * CoordinatorManager — Control Plane.
 * Manages GroupInfo for groups where this peer is a coordinator.
 * Handles gossip, re-election, and membership changes.
 */
public class CoordinatorManager {

    private static final Logger logger = LoggerUtil.getLogger(CoordinatorManager.class.getName());

    private final Map<String, GroupInfo> managedGroups = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> lastGossipReceived = new ConcurrentHashMap<>();
    private final Set<String> processedRequestIds = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService gossipScheduler = Executors.newSingleThreadScheduledExecutor();
    private final String myAddress;
    private final PeerManager peerManager;
    private WebServer webServer;
    private volatile boolean running = false;

    /**
     * Pending control-plane operations that failed because all coordinators
     * were offline. Retried on every gossip cycle until successful.
     */
    private final java.util.Queue<PendingControlOp> pendingOps = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public record PendingControlOp(Message request, List<String> coordinators, long enqueuedAt) {}

    public CoordinatorManager(String myAddress, PeerManager peerManager) {
        this.myAddress = myAddress;
        this.peerManager = peerManager;
    }

    public void setWebServer(WebServer webServer) {
        this.webServer = webServer;
    }

    public void start() {
        running = true;
        gossipScheduler.scheduleAtFixedRate(this::gossipAll,
            Constants.GOSSIP_INTERVAL, Constants.GOSSIP_INTERVAL, TimeUnit.MILLISECONDS);
        logger.info("CoordinatorManager started for " + myAddress);
    }

    public void stop() {
        running = false;
        gossipScheduler.shutdown();
    }

    /**
     * Start managing a group (this peer became coordinator).
     */
    public void manageGroup(GroupInfo info) {
        managedGroups.put(info.getGroupId(), info);
        lastGossipReceived.put(info.getGroupId(), new ConcurrentHashMap<>());
        logger.info("Now managing group: " + info.getGroupId() + " (" + info.getGroupName() + ")");
    }

    /**
     * Stop managing a group (coordinator resigned or was replaced).
     */
    public void unmanageGroup(String groupId) {
        managedGroups.remove(groupId);
        lastGossipReceived.remove(groupId);
        logger.info("Stopped managing group: " + groupId);
    }

    public boolean isManaging(String groupId) {
        return managedGroups.containsKey(groupId);
    }

    public GroupInfo getManagedGroup(String groupId) {
        return managedGroups.get(groupId);
    }

    public Collection<GroupInfo> getAllManagedGroups() {
        return managedGroups.values();
    }

    // ==================== Gossip ====================

    private void gossipAll() {
        if (!running) return;
        for (GroupInfo group : managedGroups.values()) {
            List<String> coords = HRWHash.topK(group.getMembers(), group.getGroupId(), Constants.COORDINATOR_K);
            for (String coord : coords) {
                if (!coord.equals(myAddress)) {
                    sendGossip(coord, group);
                    checkGossipTimeout(group.getGroupId(), coord);
                }
            }
        }
    }

    private void sendGossip(String targetAddress, GroupInfo group) {
        try {
            Message gossipMsg = Message.builder()
                    .type(MessageType.COORD_GOSSIP.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(myAddress)
                    .groupId(group.getGroupId())
                    .version(group.getVersion())
                    .groupInfo(group)
                    .timestamp(System.currentTimeMillis())
                    .build();
            sendTcpMessage(targetAddress, gossipMsg);
        } catch (Exception e) {
            logger.fine("Gossip to " + targetAddress + " failed: " + e.getMessage());
        }
    }

    private void checkGossipTimeout(String groupId, String coordAddress) {
        Map<String, Long> timestamps = lastGossipReceived.get(groupId);
        if (timestamps == null) return;
        
        Long lastSeen = timestamps.get(coordAddress);
        if (lastSeen == null) return;
        
        long elapsed = System.currentTimeMillis() - lastSeen;
        if (elapsed > Constants.GOSSIP_INTERVAL * Constants.GOSSIP_DEAD_CYCLES) {
            logger.warning("Coordinator " + coordAddress + " suspected dead for group " + groupId);
            // Trigger re-election would go here
        }
    }

    /**
     * Handle incoming gossip from another coordinator.
     */
    public void handleCoordGossip(Message msg) {
        String groupId = msg.getGroupId();
        GroupInfo remoteInfo = msg.getGroupInfo();
        
        // Track gossip receipt
        lastGossipReceived.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                .put(msg.getSender(), System.currentTimeMillis());
        
        GroupInfo localInfo = managedGroups.get(groupId);
        if (localInfo == null) {
            // We're not managing this group — accept it
            managedGroups.put(groupId, remoteInfo.copy());
            return;
        }
        
        if (remoteInfo.getVersion() > localInfo.getVersion()) {
            // Remote wins — newer version
            managedGroups.put(groupId, remoteInfo.copy());
            logger.info("Gossip: accepted remote v=" + remoteInfo.getVersion() + " for " + groupId);
        } else if (remoteInfo.getVersion() == localInfo.getVersion()) {
            // Tiebreak by hash
            if (hashGroupInfo(remoteInfo) > hashGroupInfo(localInfo)) {
                managedGroups.put(groupId, remoteInfo.copy());
            }
        }
        // If remote < local: ignore, send back our version
    }

    // ==================== Membership Changes ====================

    /**
     * Handle GROUP_ADD request — add member to group.
     */
    public Message handleGroupAdd(Message msg) {
        String groupId = msg.getGroupId();
        GroupInfo group = managedGroups.get(groupId);
        if (group == null) {
            return createError("Group not found: " + groupId);
        }

        if (msg.getRequestId() != null && processedRequestIds.contains(msg.getRequestId())) {
            logger.info("Duplicate request " + msg.getRequestId() + " — returning existing result");
            return createGroupUpdatedMessage(group, null, null);
        }

        // Check permissions (OPEN vs RESTRICTED)
        String requester = msg.getRequester();
        if ("RESTRICTED".equals(group.getGroupMode())) {
            boolean isOwner = requester.equals(group.getOwner());
            boolean isCoord = HRWHash.topK(group.getMembers(), groupId, Constants.COORDINATOR_K).contains(requester);
            if (!isOwner && !isCoord) {
                return Message.builder()
                        .type(MessageType.GROUP_ADD_REJECTED.name())
                        .messageId(ProtocolHandler.generateMessageId())
                        .sender(myAddress)
                        .groupId(groupId)
                        .reason("RESTRICTED")
                        .build();
            }
        }

        String newMember = msg.getNewMember();
        if (group.hasMember(newMember)) {
            return createError("Already a member: " + newMember);
        }

        // Add member
        group.addMember(newMember);
        group.incrementVersion();

        if (msg.getRequestId() != null) {
            processedRequestIds.add(msg.getRequestId());
        }

        // Recalculate coordinators
        List<String> newCoords = HRWHash.topK(group.getMembers(), groupId, Constants.COORDINATOR_K);

        // FIX #1: Send GROUP_JOINED to new member FIRST (before GROUP_UPDATED)
        // so that when GROUP_UPDATED arrives, the new member already has the group in cache.
        sendGroupJoined(newMember, group, newCoords);

        // Gossip immediately to other coordinators
        gossipImmediate(group);

        // Notify all existing members (excluding new member who already got GROUP_JOINED)
        broadcastGroupUpdated(group, "ADD", newMember);

        logger.info("Added " + newMember + " to group " + groupId + " v=" + group.getVersion());
        return createGroupUpdatedMessage(group, "ADD", newMember);
    }

    /**
     * Handle GROUP_KICK request — remove member (owner only).
     */
    public Message handleGroupKick(Message msg) {
        String groupId = msg.getGroupId();
        GroupInfo group = managedGroups.get(groupId);
        if (group == null) return createError("Group not found");

        // Only owner can kick
        String owner = msg.getSender();
        if (!owner.equals(group.getOwner())) {
            return createError("Only owner can kick members");
        }

        String target = msg.getTarget();
        if (!group.hasMember(target)) {
            return createError("Not a member: " + target);
        }

        group.removeMember(target);
        group.incrementVersion();

        gossipImmediate(group);
        broadcastGroupUpdated(group, "KICK", target);

        // Send GROUP_KICKED to target
        sendGroupKicked(target, groupId);

        logger.info("Kicked " + target + " from group " + groupId);
        return createGroupUpdatedMessage(group, "KICK", target);
    }

    /**
     * Handle GROUP_LEAVE request.
     */
    public Message handleGroupLeave(Message msg) {
        String groupId = msg.getGroupId();
        GroupInfo group = managedGroups.get(groupId);
        if (group == null) return createError("Group not found");

        String leaver = msg.getSender();
        
        // If owner is leaving, transfer ownership
        if (leaver.equals(group.getOwner())) {
            String newOwner = msg.getNewOwner();
            if (newOwner == null || newOwner.isEmpty()) {
                // Pick next member
                newOwner = group.getMembers().size() > 1 ? group.getMembers().get(1) : null;
            }
            if (newOwner != null) {
                group.setOwner(newOwner);
                // Move new owner to index 0
                group.getMembers().remove(newOwner);
                group.getMembers().add(0, newOwner);
            }
            group.removeMember(leaver);
            group.incrementVersion();
            gossipImmediate(group);
            broadcastGroupUpdated(group, "OWNER_LEAVE", leaver);
            logger.info("Owner " + leaver + " left, passed owner to " + newOwner);
            return createGroupUpdatedMessage(group, "OWNER_LEAVE", leaver);
        }

        group.removeMember(leaver);
        group.incrementVersion();

        // If leaver was coordinator, someone else will be promoted via HRW
        gossipImmediate(group);
        broadcastGroupUpdated(group, "LEAVE", leaver);

        // If this coordinator left and is no longer in members
        if (leaver.equals(myAddress)) {
            unmanageGroup(groupId);
        }

        logger.info(leaver + " left group " + groupId);
        return createGroupUpdatedMessage(group, "LEAVE", leaver);
    }

    /**
     * Handle GROUP_DISBAND request (owner only).
     */
    public Message handleGroupDisband(Message msg) {
        String groupId = msg.getGroupId();
        GroupInfo group = managedGroups.get(groupId);
        if (group == null) return createError("Group not found");

        if (!msg.getSender().equals(group.getOwner())) {
            return createError("Only owner can disband group");
        }

        // Notify all members
        for (String member : group.getMembers()) {
            if (!member.equals(myAddress)) {
                try {
                    Message disbanded = Message.builder()
                            .type(MessageType.GROUP_DISBANDED.name())
                            .messageId(ProtocolHandler.generateMessageId())
                            .sender(myAddress)
                            .groupId(groupId)
                            .build();
                    sendTcpMessage(member, disbanded);
                } catch (Exception e) {
                    logger.fine("Failed to notify " + member + " of disband");
                }
            }
        }

        unmanageGroup(groupId);
        logger.info("Group " + groupId + " disbanded");
        return Message.builder().type(MessageType.ACK.name())
                .messageId(ProtocolHandler.generateMessageId())
                .content("disbanded").build();
    }

    /**
     * Handle GROUP_RESYNC_REQ — peer requesting fresh GroupInfo.
     */
    public Message handleResyncReq(Message msg) {
        String groupId = msg.getGroupId();
        GroupInfo group = managedGroups.get(groupId);
        if (group == null) {
            return createError("Group not found: " + groupId);
        }

        long peerVersion = msg.getCacheVersion();
        if (peerVersion >= group.getVersion()) {
            return Message.builder()
                    .type(MessageType.GROUP_RESYNC_RESP.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(myAddress)
                    .groupId(groupId)
                    .content("upToDate")
                    .build();
        }

        return Message.builder()
                .type(MessageType.GROUP_RESYNC_RESP.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(myAddress)
                .groupId(groupId)
                .groupInfo(group)
                .version(group.getVersion())
                .build();
    }

    /**
     * Handle GROUP_GET — peer requesting all groups it belongs to.
     */
    public List<GroupInfo> handleGroupGet(String username) {
        List<GroupInfo> result = new ArrayList<>();
        for (GroupInfo group : managedGroups.values()) {
            // Check if username (as address) is a member
            if (group.getMembers().stream().anyMatch(m -> m.contains(username))) {
                result.add(group);
            }
        }
        return result;
    }

    // ==================== Helper Methods ====================

    private void gossipImmediate(GroupInfo group) {
        List<String> coords = HRWHash.topK(group.getMembers(), group.getGroupId(), Constants.COORDINATOR_K);
        for (String coord : coords) {
            if (!coord.equals(myAddress)) {
                sendGossip(coord, group);
            }
        }
    }

    private void broadcastGroupUpdated(GroupInfo group, String changeType, String affected) {
        List<String> coords = HRWHash.topK(group.getMembers(), group.getGroupId(), Constants.COORDINATOR_K);
        for (String member : group.getMembers()) {
            if (!member.equals(myAddress)) {
                try {
                    Message updated = Message.builder()
                            .type(MessageType.GROUP_UPDATED.name())
                            .messageId(ProtocolHandler.generateMessageId())
                            .sender(myAddress)
                            .groupId(group.getGroupId())
                            .groupName(group.getGroupName())
                            .members(new ArrayList<>(group.getMembers()))
                            .coordinators(coords)
                            .version(group.getVersion())
                            .changeType(changeType)
                            .affected(affected)
                            .groupMode(group.getGroupMode())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    sendTcpMessage(member, updated);
                } catch (Exception e) {
                    logger.fine("Failed to send GROUP_UPDATED to " + member);
                }
            }
        }
    }

    private void sendGroupJoined(String newMember, GroupInfo group, List<String> coordinators) {
        Message joined = Message.builder()
                .type(MessageType.GROUP_JOINED.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(myAddress)
                .groupId(group.getGroupId())
                .groupName(group.getGroupName())
                .members(new ArrayList<>(group.getMembers()))
                .coordinators(coordinators)
                .version(group.getVersion())
                .groupMode(group.getGroupMode())
                .chatHistory(peerManager.getMessageRepository().getGroupHistory(group.getGroupId()))
                .timestamp(System.currentTimeMillis())
                .build();

        // FIX #2: Retry up to 3 times so transient TCP failures don't silently drop GROUP_JOINED
        boolean delivered = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                sendTcpMessage(newMember, joined);
                delivered = true;
                logger.info("GROUP_JOINED delivered to " + newMember + " on attempt " + attempt);
                break;
            } catch (Exception e) {
                logger.warning("GROUP_JOINED attempt " + attempt + "/3 failed for " + newMember + ": " + e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(300L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        if (!delivered) {
            // FIX #4: Fallback — gossip will eventually propagate the updated GroupInfo
            // (M4 will pick it up via COORD_GOSSIP → handleCoordGossip → updateFromGroupInfo)
            logger.warning("GROUP_JOINED could not be delivered to " + newMember
                    + " after 3 attempts. Gossip will eventually sync the state.");
        }
    }

    private void sendGroupKicked(String target, String groupId) {
        try {
            Message kicked = Message.builder()
                    .type(MessageType.GROUP_KICKED.name())
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(myAddress)
                    .groupId(groupId)
                    .timestamp(System.currentTimeMillis())
                    .build();
            sendTcpMessage(target, kicked);
        } catch (Exception e) {
            logger.fine("Failed to send GROUP_KICKED to " + target);
        }
    }

    private Message createGroupUpdatedMessage(GroupInfo group, String changeType, String affected) {
        List<String> coords = HRWHash.topK(group.getMembers(), group.getGroupId(), Constants.COORDINATOR_K);
        return Message.builder()
                .type(MessageType.GROUP_UPDATED.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(myAddress)
                .groupId(group.getGroupId())
                .groupName(group.getGroupName())
                .members(new ArrayList<>(group.getMembers()))
                .coordinators(coords)
                .version(group.getVersion())
                .groupMode(group.getGroupMode())
                .changeType(changeType)
                .affected(affected)
                .build();
    }

    private Message createError(String error) {
        return Message.builder()
                .type(MessageType.ERROR.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(myAddress)
                .content(error)
                .build();
    }

    private void sendTcpMessage(String address, Message msg) {
        String[] parts = address.split(":");
        if (parts.length != 2) {
            logger.warning("Invalid address format (expected host:port): " + address);
            return;
        }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Constants.COORDINATOR_TIMEOUT);
            socket.setSoTimeout(Constants.COORDINATOR_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(JsonUtil.toJson(msg));
            out.flush();
        } catch (Exception e) {
            // FIX #3: Upgrade from FINE (silent) to WARNING so failures are visible in logs
            logger.warning("TCP send [" + msg.getType() + "] to " + address + " failed: " + e.getMessage());
            // Re-throw so callers (e.g. sendGroupJoined retry loop) can catch it
            throw new RuntimeException("TCP send failed to " + address, e);
        }
    }

    private long hashGroupInfo(GroupInfo info) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String data = info.getGroupId() + info.getVersion() + info.getMembers().toString();
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            return value;
        } catch (Exception e) {
            return 0;
        }
    }
}
