package com.mycompany.p2pchat.model;

public class Message {
    private String type;
    private String messageId;
    private String sender;
    private String receiver;
    private String groupName;
    private String content;
    private long timestamp;
    private String encryptionAlgorithm;
    private String senderKeyId;
    private String receiverKeyId;

    public Message() {
        this.timestamp = System.currentTimeMillis();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }

    public String getSenderKeyId() { return senderKeyId; }
    public void setSenderKeyId(String senderKeyId) { this.senderKeyId = senderKeyId; }

    public String getReceiverKeyId() { return receiverKeyId; }
    public void setReceiverKeyId(String receiverKeyId) { this.receiverKeyId = receiverKeyId; }

    public static class Builder {
        private final Message message = new Message();

        public Builder type(String type) { message.setType(type); return this; }
        public Builder messageId(String messageId) { message.setMessageId(messageId); return this; }
        public Builder sender(String sender) { message.setSender(sender); return this; }
        public Builder receiver(String receiver) { message.setReceiver(receiver); return this; }
        public Builder groupName(String groupName) { message.setGroupName(groupName); return this; }
        public Builder content(String content) { message.setContent(content); return this; }
        public Builder timestamp(long timestamp) { message.setTimestamp(timestamp); return this; }
        public Builder encryptionAlgorithm(String a) { message.setEncryptionAlgorithm(a); return this; }
        public Builder senderKeyId(String id) { message.setSenderKeyId(id); return this; }
        public Builder receiverKeyId(String id) { message.setReceiverKeyId(id); return this; }
        public Message build() { return message; }
    }

    @Override
    public String toString() {
        return "Message{type=" + type + ", from=" + sender + ", to=" + receiver + ", content=" + content + "}";
    }
}
