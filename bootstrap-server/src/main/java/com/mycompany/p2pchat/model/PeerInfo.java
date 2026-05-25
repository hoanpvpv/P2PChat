package com.mycompany.p2pchat.model;

public class PeerInfo {
    private String username;
    private String host;
    private int port;
    private boolean online;
    private long lastHeartbeat;
    private String publicKey;
    private String keyId;

    public PeerInfo() {}

    public PeerInfo(String username, String host, int port) {
        this.username = username;
        this.host = host;
        this.port = port;
        this.online = true;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public long getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getAddress() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return username + "@" + getAddress() + (online ? " [online]" : " [offline]");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerInfo peerInfo = (PeerInfo) o;
        return username.equals(peerInfo.username);
    }

    @Override
    public int hashCode() {
        return username.hashCode();
    }
}
