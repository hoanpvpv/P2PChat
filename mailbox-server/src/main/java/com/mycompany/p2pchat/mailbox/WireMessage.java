package com.mycompany.p2pchat.mailbox;

public class WireMessage {
    private String type;
    private String messageId;
    private String sender;
    private String receiver;
    private String content;
    private long timestamp = System.currentTimeMillis();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public static WireMessage of(String type, String sender, String receiver, String content) {
        WireMessage msg = new WireMessage();
        msg.setType(type);
        msg.setMessageId(java.util.UUID.randomUUID().toString());
        msg.setSender(sender);
        msg.setReceiver(receiver);
        msg.setContent(content);
        return msg;
    }
}
