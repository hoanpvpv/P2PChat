package com.mycompany.p2pchat.filetransfer;

/**
 * Metadata for a file transfer, stored in SQLite and exchanged in FILE_OFFER.
 */
public class FileTransferMeta {
    public String transferId;
    public String filename;
    public long fileSize;
    public String sha256;          // SHA-256 of the entire file
    public String sender;          // sender address host:port
    public String receiver;        // receiver address (null for group)
    public String groupId;         // non-null for group file
    public TransferStatus status;
    public int totalChunks;
    public int completedChunks;
    public long createdAt;
    public int filePort;           // sender's file transfer port

    public FileTransferMeta() {
        this.status = TransferStatus.PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    /** Percentage complete 0–100. */
    public int progressPercent() {
        if (totalChunks == 0) return 0;
        return (int) ((completedChunks * 100L) / totalChunks);
    }

    public boolean isComplete() {
        return completedChunks >= totalChunks && totalChunks > 0;
    }

    @Override
    public String toString() {
        return "Transfer{" + transferId + " " + filename + " " + progressPercent() + "% " + status + "}";
    }
}
