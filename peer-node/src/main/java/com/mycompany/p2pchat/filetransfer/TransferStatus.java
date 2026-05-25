package com.mycompany.p2pchat.filetransfer;

public enum TransferStatus {
    PENDING,      // offer sent, waiting for accept
    ACTIVE,       // transfer in progress
    PAUSED,       // paused / waiting for resume
    DONE,         // all chunks received and verified
    FAILED,       // integrity check failed or error
    REJECTED,     // receiver rejected
    CANCELLED     // sender cancelled
}
