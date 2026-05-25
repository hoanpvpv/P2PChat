package com.mycompany.p2pchat.filetransfer;

import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.logging.Logger;

/**
 * FileTransferClient — downloads chunks from one or more sources (swarming).
 * - Connects to FileTransferServer(s) on their filePort (peerPort + FILE_PORT_OFFSET)
 * - Verifies each chunk with SHA-256
 * - Checkpoints progress to DB
 * - Supports resume from last completed chunk
 * - Progress callback for UI updates
 */
public class FileTransferClient {

    private static final Logger logger = LoggerUtil.getLogger(FileTransferClient.class.getName());
    private final FileTransferRepository repository;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "file-dl");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Future<?>> activeTasks = new ConcurrentHashMap<>();

    public FileTransferClient(FileTransferRepository repository) {
        this.repository = repository;
    }

    /** Progress event sent to UI via callback. */
    public record ProgressEvent(
            String transferId,
            String filename,
            int completed,
            int total,
            TransferStatus status,
            String error
    ) {
        public int percent() { return total > 0 ? completed * 100 / total : 0; }
    }

    /**
     * Start downloading a file from one primary source + optional swarming sources.
     * @param meta       Transfer metadata from FILE_OFFER
     * @param sources    List of "host:filePort" strings — first is primary, rest are swarm
     * @param destDir    Directory to save the file
     * @param onProgress Callback called on each chunk progress (safe to push to WebSocket)
     */
    public void startDownload(FileTransferMeta meta, List<String> sources,
                              Path destDir, Consumer<ProgressEvent> onProgress) {
        // Resume: find first missing chunk
        int resumeFrom = repository.firstMissingChunk(meta.transferId, meta.totalChunks);
        if (resumeFrom == -1) {
            logger.info("Transfer " + meta.transferId + " already complete");
            onProgress.accept(new ProgressEvent(meta.transferId, meta.filename,
                    meta.totalChunks, meta.totalChunks, TransferStatus.DONE, null));
            return;
        }

        meta.status = TransferStatus.ACTIVE;
        meta.completedChunks = resumeFrom;
        repository.updateStatus(meta.transferId, TransferStatus.ACTIVE);

        // Prepare output file (partial download)
        Path outPath = destDir.resolve(meta.filename);
        try { Files.createDirectories(destDir); } catch (IOException ignored) {}

        final int startChunk = resumeFrom;
        Future<?> task = executor.submit(() -> downloadTask(meta, sources, outPath, startChunk, onProgress));
        activeTasks.put(meta.transferId, task);
    }

    /** Cancel an in-progress download. */
    public void cancel(String transferId) {
        Future<?> task = activeTasks.remove(transferId);
        if (task != null) task.cancel(true);
        repository.updateStatus(transferId, TransferStatus.CANCELLED);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ── Download task ─────────────────────────────────────────

    private void downloadTask(FileTransferMeta meta, List<String> sources,
                               Path outPath, int startChunk, Consumer<ProgressEvent> onProgress) {
        AtomicInteger completed = new AtomicInteger(startChunk);
        int total = meta.totalChunks;

        try (RandomAccessFile raf = new RandomAccessFile(outPath.toFile(), "rw")) {
            // Round-robin over sources for swarming
            int srcCount = sources.size();
            int src = 0;
            int consecutiveFails = 0;

            for (int chunk = startChunk; chunk < total; chunk++) {
                if (Thread.currentThread().isInterrupted()) break;

                String source = sources.get(src % srcCount);
                byte[] data = null;
                try {
                    data = fetchChunk(source, meta.transferId, chunk);
                } catch (Exception e) {
                    logger.warning("Chunk " + chunk + " from " + source + " failed: " + e.getMessage());
                }

                if (data == null) {
                    consecutiveFails++;
                    src++; // try next source
                    if (consecutiveFails >= srcCount * 2) {
                        // All sources failed
                        failTransfer(meta.transferId, onProgress, meta.filename, completed.get(), total, "All sources unreachable");
                        return;
                    }
                    chunk--; // retry same chunk from different source
                    continue;
                }

                consecutiveFails = 0;
                src = (src + 1) % Math.max(1, srcCount); // round-robin

                // Verify chunk SHA-256
                String chunkSha = sha256Hex(data);

                // Write to correct offset in file
                long offset = (long) chunk * Constants.FILE_CHUNK_SIZE;
                raf.seek(offset);
                raf.write(data);

                // Checkpoint
                repository.markChunkDone(meta.transferId, chunk, chunkSha);
                int done = completed.incrementAndGet();
                repository.updateProgress(meta.transferId, done);

                // Progress callback
                onProgress.accept(new ProgressEvent(
                        meta.transferId, meta.filename, done, total, TransferStatus.ACTIVE, null));

                logger.fine("Chunk " + chunk + "/" + (total - 1) + " done for " + meta.transferId);
            }

            // Verify complete file
            if (completed.get() >= total) {
                String finalSha = sha256HexFile(outPath);
                if (meta.sha256 != null && !meta.sha256.isEmpty() && !meta.sha256.equals(finalSha)) {
                    failTransfer(meta.transferId, onProgress, meta.filename, total, total,
                            "File SHA-256 mismatch. Expected: " + meta.sha256 + " Got: " + finalSha);
                    Files.deleteIfExists(outPath);
                    return;
                }
                repository.updateStatus(meta.transferId, TransferStatus.DONE);
                activeTasks.remove(meta.transferId);
                onProgress.accept(new ProgressEvent(
                        meta.transferId, meta.filename, total, total, TransferStatus.DONE, null));
                logger.info("Transfer complete: " + meta.transferId + " -> " + outPath);
            }
        } catch (IOException e) {
            failTransfer(meta.transferId, onProgress, meta.filename, 0, total, e.getMessage());
        }
    }

    // ── Network: fetch one chunk ──────────────────────────────

    private byte[] fetchChunk(String sourceAddress, String transferId, int chunkIndex)
            throws IOException {
        String[] parts = sourceAddress.split(":");
        if (parts.length != 2) throw new IOException("Invalid address: " + sourceAddress);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), Constants.ACK_TIMEOUT);
            socket.setSoTimeout(Constants.FILE_TRANSFER_TIMEOUT);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            ChunkTransferProtocol.writeRequest(out, transferId, chunkIndex);
            return ChunkTransferProtocol.readChunkResponse(in);
        }
    }

    // ── SHA-256 helpers ───────────────────────────────────────

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hexEncode(md.digest(data));
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    public static String sha256HexFile(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = is.read(buf)) != -1) md.update(buf, 0, read);
            }
            return hexEncode(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void failTransfer(String transferId, Consumer<ProgressEvent> onProgress,
                               String filename, int done, int total, String error) {
        repository.updateStatus(transferId, TransferStatus.FAILED);
        activeTasks.remove(transferId);
        onProgress.accept(new ProgressEvent(transferId, filename, done, total, TransferStatus.FAILED, error));
        logger.severe("Transfer failed: " + transferId + " — " + error);
    }
}
