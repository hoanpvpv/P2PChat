package com.mycompany.p2pchat.protocol;

import com.mycompany.p2pchat.model.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;

public class ProtocolHandler {

    public static String generateMessageId() {
        return UUID.randomUUID().toString();
    }

    public static void sendMessage(Socket socket, Message message) throws IOException {
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        String json = JsonUtil.toJson(message);
        out.println(json);
        out.flush();
    }

    public static Message receiveMessage(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line = in.readLine();
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        return JsonUtil.fromJson(line.trim());
    }

    public static Message receiveMessage(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        return JsonUtil.fromJson(line.trim());
    }

    public static Message createRegister(String username, String host, int port) {
        return createRegister(username, host, port, null, null);
    }

    public static Message createRegister(String username, String host, int port, String keyId, String publicKey) {
        String content = host + ":" + port;
        if (keyId != null && !keyId.isBlank() && publicKey != null && !publicKey.isBlank()) {
            content += "|" + keyId + "|" + publicKey;
        }
        return Message.builder()
                .type(MessageType.REGISTER.name())
                .messageId(generateMessageId())
                .sender(username)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createRegisterAck(String peerList) {
        return Message.builder()
                .type(MessageType.REGISTER_ACK.name())
                .messageId(generateMessageId())
                .content(peerList)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createDirectMessage(String sender, String receiver, String content) {
        return Message.builder()
                .type(MessageType.DIRECT_MESSAGE.name())
                .messageId(generateMessageId())
                .sender(sender)
                .receiver(receiver)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createGroupMessage(String sender, String groupName, String content) {
        return Message.builder()
                .type(MessageType.GROUP_MESSAGE.name())
                .messageId(generateMessageId())
                .sender(sender)
                .groupName(groupName)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createBroadcast(String sender, String content) {
        return Message.builder()
                .type(MessageType.BROADCAST.name())
                .messageId(generateMessageId())
                .sender(sender)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createAck(String originalMessageId, String sender) {
        return Message.builder()
                .type(MessageType.ACK.name())
                .messageId(generateMessageId())
                .sender(sender)
                .content(originalMessageId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createHeartbeat(String username) {
        return Message.builder()
                .type(MessageType.HEARTBEAT.name())
                .messageId(generateMessageId())
                .sender(username)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createHeartbeatAck() {
        return Message.builder()
                .type(MessageType.HEARTBEAT_ACK.name())
                .messageId(generateMessageId())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createPeerList(String peerListJson) {
        return Message.builder()
                .type(MessageType.PEER_LIST.name())
                .messageId(generateMessageId())
                .content(peerListJson)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createPeerJoin(String username, String host, int port) {
        return createPeerJoin(username, host, port, null, null);
    }

    public static Message createPeerJoin(String username, String host, int port, String keyId, String publicKey) {
        String content = host + ":" + port;
        if (keyId != null && !keyId.isBlank() && publicKey != null && !publicKey.isBlank()) {
            content += "|" + keyId + "|" + publicKey;
        }
        return Message.builder()
                .type(MessageType.PEER_JOIN.name())
                .messageId(generateMessageId())
                .sender(username)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createPeerLeave(String username) {
        return Message.builder()
                .type(MessageType.PEER_LEAVE.name())
                .messageId(generateMessageId())
                .sender(username)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createDiscover(String username) {
        return Message.builder()
                .type(MessageType.DISCOVER.name())
                .messageId(generateMessageId())
                .sender(username)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createStoreMessage(String sender, String receiver, String content) {
        return Message.builder()
                .type(MessageType.STORE_MESSAGE.name())
                .messageId(generateMessageId())
                .sender(sender)
                .receiver(receiver)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createOfflineMessage(String sender, String content) {
        return Message.builder()
                .type(MessageType.OFFLINE_MESSAGE.name())
                .messageId(generateMessageId())
                .sender(sender)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static Message createError(String errorMessage) {
        return Message.builder()
                .type(MessageType.ERROR.name())
                .messageId(generateMessageId())
                .content(errorMessage)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
