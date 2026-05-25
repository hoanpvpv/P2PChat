package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.model.GroupInfo;
import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.model.PeerInfo;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

/**
 * LazyRepairManager — Repair Plane.
 * Handles cache synchronization when stale data is detected.
 * 4 triggers: TCP fail, CACHE_STALE, peer restart, heartbeat resumed.
 */
public class LazyRepairManager {

    private static final Logger logger = LoggerUtil.getLogger(LazyRepairManager.class.getName());
    private final GroupCache groupCache;
    private final PeerManager peerManager;
    private final String myAddress;

    public LazyRepairManager(PeerManager peerManager, String myAddress) {
        this.groupCache = peerManager.getGroupCache();
        this.peerManager = peerManager;
        this.myAddress = myAddress;
    }

    /**
     * Trigger repair for a specific group.
     * Tries C1 → C2 → C3 with timeout.
     */
    public void repair(String groupId) {
        GroupCache.GroupCacheEntry entry = groupCache.get(groupId);
        if (entry == null) {
            logger.fine("No cache entry for " + groupId + " — skipping repair");
            return;
        }

        List<String> coords = entry.getCoordinators();
        if (coords == null || coords.isEmpty()) {
            logger.warning("No coordinators cached for " + groupId);
            return;
        }

        for (String coord : coords) {
            if (coord.equals(myAddress)) continue; // don't ask ourselves
            try {
                Message resyncReq = Message.builder()
                        .type(MessageType.GROUP_RESYNC_REQ.name())
                        .messageId(ProtocolHandler.generateMessageId())
                        .sender(myAddress)
                        .groupId(groupId)
                        .cacheVersion(entry.getLocalVersion())
                        .timestamp(System.currentTimeMillis())
                        .build();

                Message response = sendAndReceive(coord, resyncReq);
                if (response != null && MessageType.GROUP_RESYNC_RESP.name().equals(response.getType())) {
                    if (response.getGroupInfo() != null) {
                        groupCache.updateFromGroupInfo(response.getGroupInfo());
                        logger.info("Repair successful for " + groupId + " from " + coord +
                                   " v=" + response.getGroupInfo().getVersion());
                    } else {
                        logger.fine("Group " + groupId + " already up-to-date");
                    }
                    return; // success
                }
            } catch (Exception e) {
                logger.fine("Repair from " + coord + " failed: " + e.getMessage());
            }
        }
        logger.warning("All coordinators unreachable for group " + groupId);
    }

    /**
     * Trigger 3: Repair all groups on peer restart.
     */
    public void repairAllOnRestart() {
        logger.info("Repairing all groups on restart...");
        for (String groupId : groupCache.getAllGroupIds()) {
            repair(groupId);
        }
    }

    /**
     * Trigger 4: Repair all groups when heartbeat resumes after long outage.
     */
    public void repairOnHeartbeatResumed() {
        logger.info("Heartbeat resumed — resyncing all groups...");
        for (String groupId : groupCache.getAllGroupIds()) {
            repair(groupId);
        }
    }

    /**
     * Send TCP message and wait for response.
     */
    private Message sendAndReceive(String usernameOrAddress, Message msg) {
        String address = usernameOrAddress;
        if (!address.contains(":")) {
            PeerInfo p = peerManager.getPeer(usernameOrAddress);
            if (p != null) address = p.getAddress();
            else address = peerManager.getRecentPeersCache().getAddress(usernameOrAddress);
        }
        if (address == null || !address.contains(":")) return null;

        String[] parts = address.split(":");
        if (parts.length != 2) return null;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), Constants.COORDINATOR_TIMEOUT);
            socket.setSoTimeout(Constants.COORDINATOR_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(JsonUtil.toJson(msg));
            out.flush();

            String responseLine = in.readLine();
            if (responseLine != null && !responseLine.trim().isEmpty()) {
                return JsonUtil.fromJson(responseLine.trim());
            }
        } catch (Exception e) {
            logger.fine("sendAndReceive to " + address + " failed: " + e.getMessage());
        }
        return null;
    }
}
