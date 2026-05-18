package com.mycompany.p2pchat.filetransfer;

import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * FileTransferServer — listens on peerPort + FILE_PORT_OFFSET.
 * Serves file chunks to requesting peers via binary protocol.
 * Supports multiple concurrent transfers (swarming source).
 */
public class FileTransferServer {

    private static final Logger logger = LoggerUtil.getLogger(FileTransferServer.class.getName());
    private final int filePort;
    private final Map<String, ActiveTransfer> activeTransfers = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "file-server-worker");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    /** Tracks file + metadata for an active outbound transfer. */
    public static class ActiveTransfer {
        public final FileTransferMeta meta;
        public final Path filePath;

        public ActiveTransfer(FileTransferMeta meta, Path filePath) {
            this.meta = meta;
            this.filePath = filePath;
        }
    }

    public FileTransferServer(int peerPort) {
        this.filePort = peerPort + Constants.FILE_PORT_OFFSET;
    }

    public int getFilePort() { return filePort; }

    public void start() {
        try {
            serverSocket = new ServerSocket(filePort);
            running = true;
            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        pool.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running) logger.fine("FileTransferServer accept: " + e.getMessage());
                    }
                }
            }, "file-server-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            logger.info("FileTransferServer started on port " + filePort);
        } catch (IOException e) {
            logger.severe("Failed to start FileTransferServer: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdown();
    }

    /** Register a file to be served. */
    public void registerTransfer(FileTransferMeta meta, Path filePath) {
        activeTransfers.put(meta.transferId, new ActiveTransfer(meta, filePath));
        logger.info("Registered transfer: " + meta.transferId + " -> " + filePath);
    }

    /** Deregister when transfer is done/failed. */
    public void deregisterTransfer(String transferId) {
        activeTransfers.remove(transferId);
    }

    public boolean hasTransfer(String transferId) {
        return activeTransfers.containsKey(transferId);
    }

    // ── Connection handler ──────────────────────────────────

    private void handleClient(Socket socket) {
        try (socket) {
            socket.setSoTimeout(Constants.FILE_TRANSFER_TIMEOUT);
            DataInputStream in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // Serve all requests on this connection (multiple chunks per connection)
            while (!socket.isClosed()) {
                ChunkTransferProtocol.ChunkRequest req;
                try {
                    req = ChunkTransferProtocol.readRequest(in);
                } catch (EOFException | SocketException e) {
                    break; // client closed
                }

                ActiveTransfer transfer = activeTransfers.get(req.transferId());
                if (transfer == null) {
                    ChunkTransferProtocol.writeNotFound(out);
                    continue;
                }

                try {
                    byte[] chunk = readChunk(transfer.filePath, req.chunkIndex());
                    ChunkTransferProtocol.writeChunkOk(out, chunk);
                    logger.fine("Served chunk " + req.chunkIndex() + " of " + req.transferId());
                } catch (IOException e) {
                    logger.warning("Error reading chunk: " + e.getMessage());
                    ChunkTransferProtocol.writeError(out);
                }
            }
        } catch (IOException e) {
            logger.fine("Client disconnected: " + e.getMessage());
        }
    }

    /** Read a specific chunk from a file on disk. */
    private byte[] readChunk(Path filePath, int chunkIndex) throws IOException {
        long offset = (long) chunkIndex * Constants.FILE_CHUNK_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek(offset);
            long remaining = raf.length() - offset;
            int size = (int) Math.min(remaining, Constants.FILE_CHUNK_SIZE);
            byte[] data = new byte[size];
            raf.readFully(data);
            return data;
        }
    }
}
