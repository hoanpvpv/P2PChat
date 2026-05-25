package com.mycompany.p2pchat.peer;

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
    public String algorithm = "PLAINTEXT-DEMO";
    public String nonce;
    public int schemaVersion = 1;
    public String groupId;
    public String groupMembers;
}
