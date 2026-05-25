package com.mycompany.p2pchat.filetransfer;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.peer.PeerManager;
import com.mycompany.p2pchat.peer.WebServer;
import com.mycompany.p2pchat.protocol.JsonUtil;
import com.mycompany.p2pchat.protocol.MessageType;
import com.mycompany.p2pchat.protocol.ProtocolHandler;
import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * FileTransferManager — orchestrates offer/accept/reject/send flow.
 * Wires FileTransferServer + FileTransferClient together.
 */
public class FileTransferManager {

    private static final Logger logger = LoggerUtil.getLogger(FileTransferManager.class.getName());
    private static final Path DOWNLOAD_DIR = Path.of("data", "downloads");

    private final PeerManager peerManager;
    private FileTransferServer fileServer;
    private final FileTransferClient fileClient;
    private final FileTransferRepository repository;
    private WebServer webServer;

    // transferId → meta (sender-side pending offers)
    private final Map<String, FileTransferMeta> pendingOffers = new ConcurrentHashMap<>();

    public FileTransferManager(PeerManager peerManager) {
        this.peerManager = peerManager;
        this.repository = new FileTransferRepository(peerManager.getDbManager());
        this.fileServer = new FileTransferServer(peerManager.getLocalPort());
        this.fileClient = new FileTransferClient(repository);

        try { Files.createDirectories(DOWNLOAD_DIR); } catch (IOException ignored) {}
    }

    public void setWebServer(WebServer ws) { this.webServer = ws; }

    public void start() {
        int expectedFilePort = peerManager.getLocalPort() + Constants.FILE_PORT_OFFSET;
        if (fileServer == null || fileServer.getFilePort() != expectedFilePort) {
            if (fileServer != null) fileServer.stop();
            fileServer = new FileTransferServer(peerManager.getLocalPort());
        }
        fileServer.start();
        logger.info("FileTransferManager started, filePort=" + fileServer.getFilePort());
    }

    public void restartForCurrentPeerPort() {
        if (fileServer != null) fileServer.stop();
        fileServer = new FileTransferServer(peerManager.getLocalPort());
        start();
    }

    public void stop() {
        fileServer.stop();
        fileClient.shutdown();
    }

    public int getFilePort() { return fileServer.getFilePort(); }

    // ── Sender: initiate offer ────────────────────────────────

    public String offerFile(Path filePath, String receiverUsername) throws IOException {
        var peer = peerManager.getPeer(receiverUsername);
        if (peer == null) throw new IOException("Peer not found: " + receiverUsername);

        FileTransferMeta meta = buildMeta(filePath, receiverUsername, null);
        repository.saveTransfer(meta);
        fileServer.registerTransfer(meta, filePath);
        pendingOffers.put(meta.transferId, meta);

        Message offer = buildOfferMessage(meta, receiverUsername);
        peerManager.getMessageRepository().saveMessage(offer);

        sendFileOffer(peer.getHost(), peer.getPort(), offer);
        logger.info("FILE_OFFER sent to " + receiverUsername + " transferId=" + meta.transferId);
        broadcastTransferEvent("FILE_OFFER_SENT", meta);
        return meta.transferId;
    }

    /**
     * Send a FILE_OFFER to all members of a group.
     */
    public String offerFileToGroup(Path filePath, String groupId) throws IOException {
        var cache = peerManager.getGroupCache().get(groupId);
        if (cache == null) throw new IOException("Group not found: " + groupId);

        FileTransferMeta meta = buildMeta(filePath, null, groupId);
        repository.saveTransfer(meta);
        fileServer.registerTransfer(meta, filePath);
        pendingOffers.put(meta.transferId, meta);

        Message offer = buildOfferMessage(meta, null);
        peerManager.getMessageRepository().saveMessage(offer);

        String myUname = peerManager.getLocalUsername();
        for (String uname : cache.getMembers()) {
            if (uname.equals(myUname)) continue;
            var peer = peerManager.getPeer(uname);
            if (peer != null) {
                try { sendFileOffer(peer.getHost(), peer.getPort(), offer); } catch (Exception e) {
                    logger.fine("Failed to offer file to " + uname + ": " + e.getMessage());
                }
            }
        }
        broadcastTransferEvent("FILE_OFFER_SENT", meta);
        return meta.transferId;
    }

    private FileTransferMeta buildMeta(Path filePath, String receiver, String groupId) throws IOException {
        long fileSize = Files.size(filePath);
        if (fileSize > Constants.MAX_FILE_SIZE) {
            throw new IOException("File too large: " + fileSize + " > " + Constants.MAX_FILE_SIZE);
        }
        int totalChunks = (int) Math.ceil((double) fileSize / Constants.FILE_CHUNK_SIZE);
        String sha256 = FileTransferClient.sha256HexFile(filePath);

        FileTransferMeta meta = new FileTransferMeta();
        meta.transferId = UUID.randomUUID().toString();
        meta.filename   = filePath.getFileName().toString();
        meta.fileSize   = fileSize;
        meta.sha256     = sha256;
        meta.sender     = peerManager.getLocalUsername();
        meta.receiver   = receiver;
        meta.groupId    = groupId;
        meta.totalChunks = totalChunks;
        meta.filePort   = fileServer.getFilePort();
        return meta;
    }

    private Message buildOfferMessage(FileTransferMeta meta, String actualReceiverUsername) {
        Map<String, Object> fileData = new HashMap<>();
        fileData.put("transferId", meta.transferId);
        fileData.put("filename", meta.filename);
        fileData.put("fileSize", meta.fileSize);
        fileData.put("sha256", meta.sha256);
        fileData.put("filePort", meta.filePort);
        fileData.put("totalChunks", meta.totalChunks);
        String contentJson = JsonUtil.toJson(fileData);

        String senderUsername = peerManager.getLocalUsername();
        String groupName = null;
        if (meta.groupId != null) {
            var cache = peerManager.getGroupCache().get(meta.groupId);
            if (cache != null) groupName = cache.getGroupName();
        }

        return Message.builder()
                .type(MessageType.FILE_OFFER.name())
                .messageId(ProtocolHandler.generateMessageId())
                .sender(senderUsername) // save as username for DB!
                .receiver(actualReceiverUsername) // save as username for DB!
                .content(contentJson)
                .transferId(meta.transferId)
                .filename(meta.filename)
                .fileSize(meta.fileSize)
                .sha256(meta.sha256)
                .filePort(meta.filePort)
                .totalChunks(meta.totalChunks)
                .groupId(meta.groupId)
                .groupName(groupName)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private void sendFileOffer(String host, int port, Message offer) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Constants.ACK_TIMEOUT);
            socket.setSoTimeout(Constants.ACK_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(JsonUtil.toJson(offer));
        }
    }

    // ── Receiver: handle incoming offer ──────────────────────

    /**
     * Called by PeerServer when FILE_OFFER received.
     * Broadcasts to UI and waits for user to accept/reject via API.
     */
    public void handleFileOffer(Message msg) {
        FileTransferMeta meta = new FileTransferMeta();
        meta.transferId  = msg.getTransferId();
        meta.filename    = msg.getFilename();
        meta.fileSize    = msg.getFileSize();
        meta.sha256      = msg.getSha256();
        meta.sender      = msg.getSender();
        meta.receiver    = peerManager.getLocalUsername();
        meta.groupId     = msg.getGroupId();
        meta.totalChunks = msg.getTotalChunks();
        meta.filePort    = msg.getFilePort();
        meta.status      = TransferStatus.PENDING;

        repository.saveTransfer(meta);
        pendingOffers.put(meta.transferId, meta);
        peerManager.getMessageRepository().saveMessage(msg);

        logger.info("FILE_OFFER received: " + meta.transferId + " " + meta.filename +
                " from " + meta.sender);

        // Notify UI — user must accept/reject
        broadcastTransferEvent("FILE_OFFER_RECEIVED", meta);
    }

    /**
     * Accept a file offer — start downloading.
     */
    public void acceptOffer(String transferId) throws IOException {
        FileTransferMeta meta = pendingOffers.get(transferId);
        if (meta == null) meta = repository.getTransfer(transferId);
        if (meta == null) throw new IOException("Unknown transfer: " + transferId);

        // Send FILE_ACCEPT back to sender
        int resumeFrom = repository.firstMissingChunk(transferId, meta.totalChunks);
        if (resumeFrom < 0) resumeFrom = 0;

        var senderPeer = peerManager.getPeer(meta.sender);
        if (senderPeer == null) throw new IOException("Sender not found in registry: " + meta.sender);
        
        String senderHost = senderPeer.getHost();
        int senderPort = senderPeer.getPort();

        sendSignal(senderHost, senderPort, MessageType.FILE_ACCEPT.name(), transferId, resumeFrom);

        // Build file server address (senderHost:filePort)
        String fileSource = senderHost + ":" + meta.filePort;
        List<String> sources = List.of(fileSource);

        final FileTransferMeta finalMeta = meta;
        fileClient.startDownload(meta, sources, DOWNLOAD_DIR, event -> {
            // Update DB
            if (event.status() == TransferStatus.DONE || event.status() == TransferStatus.FAILED) {
                repository.updateStatus(event.transferId(), event.status());
                pendingOffers.remove(event.transferId());
            }
            // Push progress to UI via WebSocket
            Map<String, Object> data = new HashMap<>();
            data.put("transferId", event.transferId());
            data.put("filename",   event.filename());
            data.put("completed",  event.completed());
            data.put("total",      event.total());
            data.put("percent",    event.percent());
            data.put("status",     event.status().name());
            if (event.error() != null) data.put("error", event.error());
            broadcastWs("FILE_PROGRESS", data);
        });

        logger.info("Accepted transfer " + transferId + ", downloading from " + fileSource);
    }

    /**
     * Reject a file offer.
     */
    public void rejectOffer(String transferId, String reason) {
        FileTransferMeta meta = pendingOffers.remove(transferId);
        if (meta == null) return;
        repository.updateStatus(transferId, TransferStatus.REJECTED);

        var senderPeer = peerManager.getPeer(meta.sender);
        if (senderPeer != null) {
            try {
                sendSignal(senderPeer.getHost(), senderPeer.getPort(),
                        MessageType.FILE_REJECT.name(), transferId, 0);
            } catch (IOException e) {
                logger.fine("Failed to send FILE_REJECT: " + e.getMessage());
            }
        }
        broadcastTransferEvent("FILE_REJECTED", meta);
    }

    /**
     * Cancel an active outbound transfer.
     */
    public void cancelOutbound(String transferId) {
        fileServer.deregisterTransfer(transferId);
        pendingOffers.remove(transferId);
        repository.updateStatus(transferId, TransferStatus.CANCELLED);
        Map<String, Object> data = Map.of("transferId", transferId, "status", "CANCELLED");
        broadcastWs("FILE_PROGRESS", data);
    }

    // ── Sender receives FILE_ACCEPT / FILE_REJECT ─────────────

    public void handleFileAccept(Message msg) {
        String tid = msg.getTransferId();
        logger.info("FILE_ACCEPT received for " + tid + " from " + msg.getSender());
        repository.updateStatus(tid, TransferStatus.ACTIVE);
        broadcastWs("FILE_ACCEPTED", Map.of("transferId", tid, "by", msg.getSender()));
        // Actual chunk serving is passive — FileTransferServer handles incoming connections
    }

    public void handleFileReject(Message msg) {
        String tid = msg.getTransferId();
        fileServer.deregisterTransfer(tid);
        pendingOffers.remove(tid);
        repository.updateStatus(tid, TransferStatus.REJECTED);
        broadcastWs("FILE_REJECTED", Map.of("transferId", tid, "by", msg.getSender(),
                "reason", msg.getReason() != null ? msg.getReason() : ""));
    }

    public void handleFileDone(Message msg) {
        String tid = msg.getTransferId();
        fileServer.deregisterTransfer(tid);
        pendingOffers.remove(tid);
        repository.updateStatus(tid, TransferStatus.DONE);
        broadcastWs("FILE_DONE", Map.of("transferId", tid));
    }

    // ── Getters ───────────────────────────────────────────────

    public List<FileTransferMeta> getAllTransfers() { return repository.getAllTransfers(); }

    public FileTransferMeta getTransfer(String transferId) { return repository.getTransfer(transferId); }

    public Path getDownloadDir() { return DOWNLOAD_DIR; }

    // ── Helpers ───────────────────────────────────────────────

    private void sendSignal(String host, int port, String type, String transferId, int resumeFrom) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Message msg = Message.builder()
                    .type(type)
                    .messageId(ProtocolHandler.generateMessageId())
                    .sender(peerManager.getLocalUsername())
                    .transferId(transferId)
                    .resumeChunkIndex(resumeFrom)
                    .timestamp(System.currentTimeMillis())
                    .build();
            out.println(JsonUtil.toJson(msg));
        }
    }

    private void broadcastTransferEvent(String wsType, FileTransferMeta meta) {
        Map<String, Object> data = new HashMap<>();
        data.put("transferId",   meta.transferId);
        data.put("filename",     meta.filename);
        data.put("fileSize",     meta.fileSize);
        data.put("sha256",       meta.sha256);
        data.put("sender",       meta.sender);
        data.put("receiver",     meta.receiver);
        data.put("groupId",      meta.groupId);
        data.put("totalChunks",  meta.totalChunks);
        data.put("completedChunks", meta.completedChunks);
        data.put("status",       meta.status.name());
        broadcastWs(wsType, data);
    }

    private void broadcastWs(String type, Object data) {
        if (webServer != null) webServer.broadcastToWeb(type, data);
    }
}
