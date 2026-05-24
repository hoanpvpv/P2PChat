package com.mycompany.p2pchat.mailbox;

public enum MessageType {
    STORE_MESSAGE,
    STORE_ACK,
    PULL_MESSAGES,
    PULL_RESPONSE,
    DELIVERY_ACK,
    CHECK_DELIVERY,
    CHECK_DELIVERY_RESPONSE,
    ERROR
}
