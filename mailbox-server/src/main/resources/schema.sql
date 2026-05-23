CREATE TABLE IF NOT EXISTS offline_messages (
    message_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    receiver TEXT NOT NULL,
    message_type TEXT NOT NULL,
    payload_ciphertext TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    client_created_at BIGINT NOT NULL,
    mailbox_stored_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    sender_seq BIGINT DEFAULT 0,
    last_seen_message_id TEXT,
    offline_batch_id TEXT,
    sender_public_key TEXT,
    sender_key_id TEXT,
    receiver_key_id TEXT,
    algorithm TEXT,
    nonce TEXT,
    schema_version INTEGER DEFAULT 1,
    status TEXT NOT NULL,
    delivered_at BIGINT,
    group_id TEXT,
    group_members TEXT
);

CREATE INDEX IF NOT EXISTS idx_offline_receiver_status
ON offline_messages(receiver, status, mailbox_stored_at);

CREATE INDEX IF NOT EXISTS idx_offline_expires
ON offline_messages(expires_at, status);

CREATE INDEX IF NOT EXISTS idx_offline_group
ON offline_messages(group_id, status);

-- Per-member ack tracking cho group message.
-- Mỗi group message có 1 row trong offline_messages, N row trong group_delivery_acks (mỗi member 1 row khi họ ACK).
CREATE TABLE IF NOT EXISTS group_delivery_acks (
    message_id TEXT NOT NULL,
    member TEXT NOT NULL,
    delivered_at BIGINT NOT NULL,
    PRIMARY KEY (message_id, member)
);

CREATE INDEX IF NOT EXISTS idx_group_acks_msg ON group_delivery_acks(message_id);
