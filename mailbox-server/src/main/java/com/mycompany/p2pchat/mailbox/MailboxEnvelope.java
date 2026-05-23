package com.mycompany.p2pchat.mailbox;

public class MailboxEnvelope {
    public String messageId;
    public String conversationId;
    public String sender;
    public String receiver;
    public String type;
    public String payloadCiphertext;
    public String payloadHash;
    public long clientCreatedAt;
    public long mailboxStoredAt;
    public long expiresAt;
    public long senderSeq;
    public String lastSeenMessageId;
    public String offlineBatchId;
    public String senderPublicKey;
    public String senderKeyId;
    public String receiverKeyId;
    public String algorithm;
    public String nonce;
    public int schemaVersion = 1;
    public String groupId;
    public String groupMembers;   // CSV danh sách member tại thời điểm gửi

    public void applyDefaults(long ttlMillis) {
        long now = System.currentTimeMillis();
        if (mailboxStoredAt <= 0) mailboxStoredAt = now;
        if (clientCreatedAt <= 0) clientCreatedAt = now;
        if (expiresAt <= 0) expiresAt = now + ttlMillis;
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = sender + ":" + receiver;
        }
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = "PLAINTEXT-DEMO";
        }
    }
}
