package com.mycompany.p2pchat.filetransfer;

import com.mycompany.p2pchat.database.DatabaseManager;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * SQLite persistence for file transfers and chunk checkpointing.
 */
public class FileTransferRepository {

    private static final Logger logger = LoggerUtil.getLogger(FileTransferRepository.class.getName());
    private final DatabaseManager dbManager;

    public FileTransferRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    // ── Transfer ──────────────────────────────────────────────

    public void saveTransfer(FileTransferMeta meta) {
        String sql = "INSERT OR REPLACE INTO file_transfers " +
                "(transfer_id, filename, file_size, sha256, sender, receiver, group_id, " +
                " status, total_chunks, completed_chunks, created_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, meta.transferId);
            ps.setString(2, meta.filename);
            ps.setLong(3, meta.fileSize);
            ps.setString(4, meta.sha256);
            ps.setString(5, meta.sender);
            ps.setString(6, meta.receiver);
            ps.setString(7, meta.groupId);
            ps.setString(8, meta.status.name());
            ps.setInt(9, meta.totalChunks);
            ps.setInt(10, meta.completedChunks);
            ps.setLong(11, meta.createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("saveTransfer failed: " + e.getMessage());
        }
    }

    public void updateStatus(String transferId, TransferStatus status) {
        String sql = "UPDATE file_transfers SET status=? WHERE transfer_id=?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, transferId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("updateStatus failed: " + e.getMessage());
        }
    }

    public void updateProgress(String transferId, int completedChunks) {
        String sql = "UPDATE file_transfers SET completed_chunks=? WHERE transfer_id=?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setInt(1, completedChunks);
            ps.setString(2, transferId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("updateProgress failed: " + e.getMessage());
        }
    }

    public FileTransferMeta getTransfer(String transferId) {
        String sql = "SELECT * FROM file_transfers WHERE transfer_id=?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, transferId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapTransfer(rs);
        } catch (SQLException e) {
            logger.severe("getTransfer failed: " + e.getMessage());
        }
        return null;
    }

    public List<FileTransferMeta> getAllTransfers() {
        List<FileTransferMeta> list = new ArrayList<>();
        String sql = "SELECT * FROM file_transfers ORDER BY created_at DESC";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapTransfer(rs));
        } catch (SQLException e) {
            logger.severe("getAllTransfers failed: " + e.getMessage());
        }
        return list;
    }

    private FileTransferMeta mapTransfer(ResultSet rs) throws SQLException {
        FileTransferMeta m = new FileTransferMeta();
        m.transferId = rs.getString("transfer_id");
        m.filename = rs.getString("filename");
        m.fileSize = rs.getLong("file_size");
        m.sha256 = rs.getString("sha256");
        m.sender = rs.getString("sender");
        m.receiver = rs.getString("receiver");
        m.groupId = rs.getString("group_id");
        m.status = TransferStatus.valueOf(rs.getString("status"));
        m.totalChunks = rs.getInt("total_chunks");
        m.completedChunks = rs.getInt("completed_chunks");
        m.createdAt = rs.getLong("created_at");
        return m;
    }

    // ── Chunks ────────────────────────────────────────────────

    public void markChunkDone(String transferId, int chunkIndex, String sha256chunk) {
        String sql = "INSERT OR REPLACE INTO file_chunks (transfer_id, chunk_index, sha256_chunk, status) " +
                     "VALUES (?, ?, ?, 'DONE')";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, transferId);
            ps.setInt(2, chunkIndex);
            ps.setString(3, sha256chunk);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("markChunkDone failed: " + e.getMessage());
        }
    }

    /** Returns the first missing chunk index (for resume). -1 if all done. */
    public int firstMissingChunk(String transferId, int totalChunks) {
        String sql = "SELECT chunk_index FROM file_chunks WHERE transfer_id=? AND status='DONE' ORDER BY chunk_index ASC";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, transferId);
            ResultSet rs = ps.executeQuery();
            int expected = 0;
            while (rs.next()) {
                int idx = rs.getInt("chunk_index");
                if (idx != expected) return expected;
                expected++;
            }
            if (expected < totalChunks) return expected;
        } catch (SQLException e) {
            logger.severe("firstMissingChunk failed: " + e.getMessage());
        }
        return -1;
    }

    public List<Integer> getCompletedChunkIndexes(String transferId) {
        List<Integer> list = new ArrayList<>();
        String sql = "SELECT chunk_index FROM file_chunks WHERE transfer_id=? AND status='DONE' ORDER BY chunk_index ASC";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, transferId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(rs.getInt("chunk_index"));
        } catch (SQLException e) {
            logger.severe("getCompletedChunkIndexes failed: " + e.getMessage());
        }
        return list;
    }
}
