package com.mycompany.p2pchat.database;

import com.mycompany.p2pchat.model.Message;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MessageRepository {

    private static final java.util.logging.Logger logger = LoggerUtil.getLogger(MessageRepository.class.getName());
    private final DatabaseManager dbManager;

    public MessageRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public boolean saveMessage(Message msg) {
        if (messageExists(msg.getMessageId())) {
            return false;
        }
        String sql = "INSERT INTO messages (message_id, sender, receiver, group_name, content, type, timestamp, delivered) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, msg.getMessageId());
            pstmt.setString(2, msg.getSender());
            pstmt.setString(3, msg.getReceiver());
            pstmt.setString(4, msg.getGroupName());
            pstmt.setString(5, msg.getContent());
            pstmt.setString(6, msg.getType());
            pstmt.setLong(7, msg.getTimestamp());
            pstmt.setInt(8, 1);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.severe("Failed to save message: " + e.getMessage());
            return false;
        }
    }

    public List<String> getRecentGroupMessageIds(String groupName, long sinceMillis) {
        List<String> ids = new ArrayList<>();
        String sql = "SELECT message_id FROM messages WHERE group_name = ? AND type = 'GROUP_MESSAGE' AND timestamp >= ? ORDER BY timestamp ASC";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, groupName);
            ps.setLong(2, sinceMillis);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getString("message_id"));
        } catch (SQLException e) {
            logger.severe("Failed to load recent group msg ids: " + e.getMessage());
        }
        return ids;
    }

    public List<Message> getMessagesByIds(List<String> ids) {
        List<Message> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return out;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT * FROM messages WHERE message_id IN (" + placeholders + ")";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(mapResultSetToMessage(rs));
        } catch (SQLException e) {
            logger.severe("Failed to load messages by ids: " + e.getMessage());
        }
        return out;
    }

    public List<String> getDirectChatPartners(String me) {
        List<String> partners = new ArrayList<>();
        if (me == null || me.isBlank()) return partners;
        String sql = "SELECT DISTINCT CASE WHEN sender = ? THEN receiver ELSE sender END AS partner " +
                "FROM messages WHERE type = 'DIRECT_MESSAGE' AND (sender = ? OR receiver = ?)";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, me);
            ps.setString(2, me);
            ps.setString(3, me);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String partner = rs.getString("partner");
                if (partner != null && !partner.isBlank()) partners.add(partner);
            }
        } catch (SQLException e) {
            logger.severe("Failed to load chat partners: " + e.getMessage());
        }
        return partners;
    }

    public boolean messageExists(String messageId) {
        if (messageId == null || messageId.isBlank()) return false;
        String sql = "SELECT 1 FROM messages WHERE message_id = ? LIMIT 1";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, messageId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logger.severe("Failed to check message existence: " + e.getMessage());
            return false;
        }
    }

    public List<Message> getChatHistory(String user1, String user2) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE " +
                "((sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?)) " +
                "AND type IN ('DIRECT_MESSAGE', 'FILE_OFFER') ORDER BY timestamp ASC";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, user1);
            pstmt.setString(2, user2);
            pstmt.setString(3, user2);
            pstmt.setString(4, user1);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                messages.add(mapResultSetToMessage(rs));
            }
        } catch (SQLException e) {
            logger.severe("Failed to get chat history: " + e.getMessage());
        }
        return messages;
    }

    public List<Message> getGroupHistory(String groupName) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE group_name = ? AND type IN ('GROUP_MESSAGE', 'FILE_OFFER', 'SYSTEM') ORDER BY timestamp ASC";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, groupName);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                messages.add(mapResultSetToMessage(rs));
            }
        } catch (SQLException e) {
            logger.severe("Failed to get group history: " + e.getMessage());
        }
        return messages;
    }

    public List<Message> getBroadcastHistory() {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE type = 'BROADCAST' ORDER BY timestamp ASC";
        try (PreparedStatement pstmt = dbManager.getConnection().prepareStatement(sql)) {
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                messages.add(mapResultSetToMessage(rs));
            }
        } catch (SQLException e) {
            logger.severe("Failed to get broadcast history: " + e.getMessage());
        }
        return messages;
    }

    private Message mapResultSetToMessage(ResultSet rs) throws SQLException {
        return Message.builder()
                .messageId(rs.getString("message_id"))
                .sender(rs.getString("sender"))
                .receiver(rs.getString("receiver"))
                .groupName(rs.getString("group_name"))
                .content(rs.getString("content"))
                .type(rs.getString("type"))
                .timestamp(rs.getLong("timestamp"))
                .build();
    }
}
