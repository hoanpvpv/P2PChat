package com.mycompany.p2pchat.protocol;

public enum MessageType {
    REGISTER,
    REGISTER_ACK,
    REGISTER_NACK,
    PEER_LIST,
    PEER_JOIN,
    PEER_LEAVE,
    DIRECT_MESSAGE,
    GROUP_MESSAGE,
    BROADCAST,
    ACK,
    HEARTBEAT,
    HEARTBEAT_ACK,
    DISCOVER,
    OFFLINE_MESSAGE,
    STORE_MESSAGE,
    CREATE_GROUP,
    ADD_TO_GROUP,
    ERROR
}
