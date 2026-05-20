package com.mycompany.p2pchat.model;

import java.util.List;
import java.util.Map;

public class Message {
    private String type;
    private String messageId;
    private String sender;
    private String receiver;
    private String groupName;
    private String content;
    private long timestamp;

    // DHT-lite group fields
    private String groupId;
    private long lamportClock;
    private long cacheVersion;
    private String requestId;         // idempotency key for coordinator requests
    private GroupInfo groupInfo;      // for COORD_INIT, COORD_GOSSIP, GROUP_RESYNC_RESP
    private List<String> members;    // for GROUP_JOINED, GROUP_UPDATED
    private List<String> coordinators; // for GROUP_JOINED, GROUP_UPDATED
    private long version;            // group version for GROUP_UPDATED
    private String changeType;       // "ADD", "KICK", "LEAVE", "COORD_CHANGE" for GROUP_UPDATED
    private String affected;         // affected member address for GROUP_UPDATED
    private String requester;        // who requested the action
    private List<Message> chatHistory; // to sync history for new members
    private String newMember;        // for GROUP_ADD
    private String target;           // for GROUP_KICK
    private String newOwner;         // for GROUP_LEAVE (owner case)
    private String groupMode;        // "OPEN" | "RESTRICTED"
    private String reason;           // for GROUP_ADD_REJECTED, FILE_REJECT

    // File transfer fields
    private String filename;
    private long fileSize;
    private String sha256;
    private int filePort;
    private String transferId;
    private int totalChunks;
    private int resumeChunkIndex;
    private List<Integer> chunkIndexes; // for FILE_HAVE, FILE_HAVE_RESP

    public Message() {
        this.timestamp = System.currentTimeMillis();
    }

    public static Builder builder() {
        return new Builder();
    }

    // Original getters/setters
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

    // DHT-lite getters/setters
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public long getLamportClock() { return lamportClock; }
    public void setLamportClock(long lamportClock) { this.lamportClock = lamportClock; }

    public long getCacheVersion() { return cacheVersion; }
    public void setCacheVersion(long cacheVersion) { this.cacheVersion = cacheVersion; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public GroupInfo getGroupInfo() { return groupInfo; }
    public void setGroupInfo(GroupInfo groupInfo) { this.groupInfo = groupInfo; }

    public List<String> getMembers() { return members; }
    public void setMembers(List<String> members) { this.members = members; }

    public List<String> getCoordinators() { return coordinators; }
    public void setCoordinators(List<String> coordinators) { this.coordinators = coordinators; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }

    public String getAffected() { return affected; }
    public void setAffected(String affected) { this.affected = affected; }

    public String getRequester() { return requester; }
    public void setRequester(String requester) { this.requester = requester; }

    public List<Message> getChatHistory() { return chatHistory; }
    public void setChatHistory(List<Message> chatHistory) { this.chatHistory = chatHistory; }

    public String getNewMember() { return newMember; }
    public void setNewMember(String newMember) { this.newMember = newMember; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getNewOwner() { return newOwner; }
    public void setNewOwner(String newOwner) { this.newOwner = newOwner; }

    public String getGroupMode() { return groupMode; }
    public void setGroupMode(String groupMode) { this.groupMode = groupMode; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    // File transfer getters/setters
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public int getFilePort() { return filePort; }
    public void setFilePort(int filePort) { this.filePort = filePort; }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getResumeChunkIndex() { return resumeChunkIndex; }
    public void setResumeChunkIndex(int resumeChunkIndex) { this.resumeChunkIndex = resumeChunkIndex; }

    public List<Integer> getChunkIndexes() { return chunkIndexes; }
    public void setChunkIndexes(List<Integer> chunkIndexes) { this.chunkIndexes = chunkIndexes; }

    public static class Builder {
        private final Message message = new Message();

        public Builder type(String type) { message.setType(type); return this; }
        public Builder messageId(String messageId) { message.setMessageId(messageId); return this; }
        public Builder sender(String sender) { message.setSender(sender); return this; }
        public Builder receiver(String receiver) { message.setReceiver(receiver); return this; }
        public Builder groupName(String groupName) { message.setGroupName(groupName); return this; }
        public Builder content(String content) { message.setContent(content); return this; }
        public Builder timestamp(long timestamp) { message.setTimestamp(timestamp); return this; }
        public Builder groupId(String groupId) { message.setGroupId(groupId); return this; }
        public Builder lamportClock(long clock) { message.setLamportClock(clock); return this; }
        public Builder cacheVersion(long v) { message.setCacheVersion(v); return this; }
        public Builder requestId(String id) { message.setRequestId(id); return this; }
        public Builder groupInfo(GroupInfo info) { message.setGroupInfo(info); return this; }
        public Builder members(List<String> m) { message.setMembers(m); return this; }
        public Builder coordinators(List<String> c) { message.setCoordinators(c); return this; }
        public Builder version(long v) { message.setVersion(v); return this; }
        public Builder changeType(String ct) { message.setChangeType(ct); return this; }
        public Builder affected(String a) { message.setAffected(a); return this; }
        public Builder requester(String r) { message.setRequester(r); return this; }
        public Builder chatHistory(List<Message> ch) { message.setChatHistory(ch); return this; }
        public Builder newMember(String nm) { message.setNewMember(nm); return this; }
        public Builder target(String t) { message.setTarget(t); return this; }
        public Builder newOwner(String no) { message.setNewOwner(no); return this; }
        public Builder groupMode(String gm) { message.setGroupMode(gm); return this; }
        public Builder reason(String r) { message.setReason(r); return this; }
        public Builder filename(String f) { message.setFilename(f); return this; }
        public Builder fileSize(long s) { message.setFileSize(s); return this; }
        public Builder sha256(String h) { message.setSha256(h); return this; }
        public Builder filePort(int p) { message.setFilePort(p); return this; }
        public Builder transferId(String id) { message.setTransferId(id); return this; }
        public Builder totalChunks(int tc) { message.setTotalChunks(tc); return this; }
        public Builder resumeChunkIndex(int ri) { message.setResumeChunkIndex(ri); return this; }
        public Builder chunkIndexes(List<Integer> ci) { message.setChunkIndexes(ci); return this; }
        public Message build() { return message; }
    }

    @Override
    public String toString() {
        return "Message{type=" + type + ", from=" + sender + ", to=" + receiver + ", content=" + content + "}";
    }
}
