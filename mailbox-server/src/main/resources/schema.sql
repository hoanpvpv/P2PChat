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
    delivered_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_offline_receiver_status
ON offline_messages(receiver, status, mailbox_stored_at);

CREATE INDEX IF NOT EXISTS idx_offline_expires
ON offline_messages(expires_at, status);
