package com.mycompany.p2pchat.protocol;

public enum MessageType {
    // Bootstrap protocol
    REGISTER,
    REGISTER_ACK,
    REGISTER_NACK,
    PEER_LIST,
    PEER_JOIN,
    PEER_LEAVE,
    HEARTBEAT,
    HEARTBEAT_ACK,
    DISCOVER,
    OFFLINE_MESSAGE,
    STORE_MESSAGE,

    // P2P messaging
    DIRECT_MESSAGE,
    GROUP_MESSAGE,
    BROADCAST,
    TYPING,
    ACK,

    // DHT-lite Coordinator protocol (Control Plane)
    COORD_INIT,
    COORD_INIT_ACK,
    COORD_GOSSIP,
    COORD_GOSSIP_ACK,
    COORD_RESIGN,
    GROUP_ADD,
    GROUP_LEAVE,
    GROUP_KICK,
    GROUP_DISBAND,
    GROUP_JOINED,
    GROUP_UPDATED,
    GROUP_KICKED,
    GROUP_DISBANDED,
    GROUP_ADD_REJECTED,

    // Repair Plane
    GROUP_RESYNC_REQ,
    GROUP_RESYNC_RESP,
    CACHE_STALE,
    GROUP_GET,

    // File Transfer
    FILE_OFFER,
    FILE_ACCEPT,
    FILE_REJECT,
    FILE_DONE,
    FILE_ACK,
    FILE_GROUP_OFFER,
    FILE_HAVE,
    FILE_HAVE_REQ,
    FILE_HAVE_RESP,
    FILE_RESUME,

    // Peer lookup relay (Bootstrap Cache fallback)
    PEER_LOOKUP_REQ,
    PEER_LOOKUP_RESP,

    // Legacy
    CREATE_GROUP,
    ADD_TO_GROUP,
    ERROR
}
