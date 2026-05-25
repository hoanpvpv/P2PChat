package com.mycompany.p2pchat.filetransfer;

import java.io.*;
import java.nio.ByteBuffer;

/**
 * Binary chunk transfer protocol over TCP.
 *
 * Request frame (client → server):
 *   [8 bytes transferId len][transferId bytes][4 bytes chunkIndex]
 *
 * Response frame (server → client):
 *   [4 bytes status: 0=OK, 1=NOT_FOUND, 2=ERROR]
 *   [4 bytes chunkSize]       -- only if status==0
 *   [chunkSize bytes data]    -- only if status==0
 */
public class ChunkTransferProtocol {

    public static final int STATUS_OK        = 0;
    public static final int STATUS_NOT_FOUND = 1;
    public static final int STATUS_ERROR     = 2;

    // ── Client side ───────────────────────────────────────────

    /** Write a chunk request to a stream. */
    public static void writeRequest(DataOutputStream out, String transferId, int chunkIndex)
            throws IOException {
        byte[] idBytes = transferId.getBytes("UTF-8");
        out.writeInt(idBytes.length);
        out.write(idBytes);
        out.writeInt(chunkIndex);
        out.flush();
    }

    /** Read chunk data from server response. Returns null on NOT_FOUND or ERROR. */
    public static byte[] readChunkResponse(DataInputStream in) throws IOException {
        int status = in.readInt();
        if (status == STATUS_OK) {
            int size = in.readInt();
            byte[] data = new byte[size];
            in.readFully(data);
            return data;
        }
        return null; // NOT_FOUND or ERROR
    }

    // ── Server side ───────────────────────────────────────────

    /** Read a chunk request from a stream. */
    public static ChunkRequest readRequest(DataInputStream in) throws IOException {
        int idLen = in.readInt();
        byte[] idBytes = new byte[idLen];
        in.readFully(idBytes);
        String transferId = new String(idBytes, "UTF-8");
        int chunkIndex = in.readInt();
        return new ChunkRequest(transferId, chunkIndex);
    }

    /** Write chunk data to response stream. */
    public static void writeChunkOk(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(STATUS_OK);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    public static void writeNotFound(DataOutputStream out) throws IOException {
        out.writeInt(STATUS_NOT_FOUND);
        out.flush();
    }

    public static void writeError(DataOutputStream out) throws IOException {
        out.writeInt(STATUS_ERROR);
        out.flush();
    }

    public record ChunkRequest(String transferId, int chunkIndex) {}
}
