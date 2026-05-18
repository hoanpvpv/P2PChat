package com.mycompany.p2pchat.utils;

public class Constants {
    // Bootstrap
    public static final int DEFAULT_BOOTSTRAP_PORT = 8080;
    public static final int DEFAULT_PEER_PORT = 5001;
    public static final int DEFAULT_WEB_PORT = 3000;

    // Heartbeat
    public static final int HEARTBEAT_INTERVAL = 5000;
    public static final int HEARTBEAT_TIMEOUT = 15000;

    // Messaging
    public static final int ACK_TIMEOUT = 5000;
    public static final int MAX_RETRIES = 3;
    public static final int RECONNECT_INTERVAL = 3000;
    public static final String DELIMITER = "\n";

    // Database
    public static final String DB_EXTENSION = ".db";
    public static final String DATA_DIR = "data";

    // DHT-lite Coordinator
    public static final int COORDINATOR_K = 3;
    public static final int GOSSIP_INTERVAL = 5000;           // 5s gossip cycle
    public static final int GOSSIP_DEAD_CYCLES = 3;           // 3 missed cycles = dead
    public static final int COORDINATOR_TIMEOUT = 2000;       // 2s timeout per coordinator lookup
    public static final int LAMPORT_BUFFER_MS = 200;          // 200ms buffer window for message ordering
    public static final int KICK_GRACE_WINDOW_MS = 3000;      // 3s grace window after GROUP_KICKED
    public static final int HEARTBEAT_RESUME_THRESHOLD = 10000; // 10s threshold for heartbeat resume resync

    // File Transfer
    public static final int FILE_PORT_OFFSET = 1000;          // filePort = peerPort + 1000
    public static final int FILE_CHUNK_SIZE = 65536;           // 64 KB
    public static final long MAX_FILE_SIZE = 104857600L;       // 100 MB
    public static final int FILE_TRANSFER_TIMEOUT = 30000;     // 30s timeout per chunk

    // Bootstrap Cache
    public static final int RECENT_PEERS_MAX = 5;
}
