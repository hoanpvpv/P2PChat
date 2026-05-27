CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    receiver TEXT,
    group_name TEXT,
    group_id TEXT,
    content TEXT NOT NULL,
    type TEXT NOT NULL,
    timestamp BIGINT NOT NULL,
    lamport_clock BIGINT DEFAULT 0,
    delivered INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS chat_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_name TEXT NOT NULL UNIQUE,
    owner TEXT NOT NULL,
    members TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS known_peers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    online INTEGER DEFAULT 0,
    key_id TEXT,
    public_key TEXT,
    last_seen BIGINT DEFAULT 0
);

-- DHT-lite group cache (Data Plane)
CREATE TABLE IF NOT EXISTS group_cache (
    group_id TEXT PRIMARY KEY,
    group_name TEXT NOT NULL,
    owner TEXT NOT NULL,
    members TEXT NOT NULL,
    coordinators TEXT NOT NULL,
    version INTEGER DEFAULT 1,
    group_mode TEXT DEFAULT 'OPEN',
    group_state TEXT DEFAULT 'ACTIVE',
    last_updated BIGINT NOT NULL
);

-- Bootstrap Cache — top 5 recent peers
CREATE TABLE IF NOT EXISTS recent_peers (
    username TEXT PRIMARY KEY,
    address TEXT NOT NULL,
    last_seen BIGINT NOT NULL,
    success_count INTEGER DEFAULT 1
);

-- File transfer tracking
CREATE TABLE IF NOT EXISTS file_transfers (
    transfer_id TEXT PRIMARY KEY,
    filename TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 TEXT,
    sender TEXT NOT NULL,
    receiver TEXT,
    group_id TEXT,
    status TEXT DEFAULT 'PENDING',
    total_chunks INTEGER DEFAULT 0,
    completed_chunks INTEGER DEFAULT 0,
    created_at BIGINT NOT NULL
);

-- Chunk-level checkpointing for file resume
CREATE TABLE IF NOT EXISTS file_chunks (
    transfer_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    sha256_chunk TEXT,
    status TEXT DEFAULT 'PARTIAL',
    PRIMARY KEY (transfer_id, chunk_index)
);

-- Durable sender outbox for store-and-forward retry
CREATE TABLE IF NOT EXISTS outbound_messages (
    message_id TEXT PRIMARY KEY,
    receiver TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    state TEXT NOT NULL,
    attempt_count INTEGER DEFAULT 0,
    direct_attempt_count INTEGER DEFAULT 0,
    mailbox_attempt_count INTEGER DEFAULT 0,
    next_retry_at BIGINT DEFAULT 0,
    last_attempt_at BIGINT DEFAULT 0,
    first_failure_at BIGINT DEFAULT 0,
    failure_code TEXT,
    last_error TEXT,
    mailbox_host TEXT,
    mailbox_port INTEGER,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- Relay store-and-forward fallback when mailbox is unavailable.
CREATE TABLE IF NOT EXISTS relay_assignments (
    message_id TEXT NOT NULL,
    relay_peer TEXT NOT NULL,
    generation INTEGER NOT NULL,
    role TEXT NOT NULL,
    status TEXT NOT NULL,
    assigned_at BIGINT NOT NULL,
    lease_until BIGINT NOT NULL,
    last_ack_at BIGINT DEFAULT 0,
    PRIMARY KEY (message_id, relay_peer, generation)
);

CREATE INDEX IF NOT EXISTS idx_relay_assignments_message
ON relay_assignments(message_id, status, lease_until);

CREATE TABLE IF NOT EXISTS relay_messages (
    message_id TEXT PRIMARY KEY,
    sender TEXT NOT NULL,
    receiver TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    generation INTEGER DEFAULT 1,
    role TEXT DEFAULT 'BACKUP',
    status TEXT NOT NULL,
    stored_at BIGINT NOT NULL,
    lease_until BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    delivered_at BIGINT DEFAULT 0,
    retry_count INTEGER DEFAULT 0,
    next_retry_at BIGINT DEFAULT 0,
    last_attempt_at BIGINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_relay_messages_due
ON relay_messages(status, next_retry_at, expires_at);

CREATE INDEX IF NOT EXISTS idx_relay_messages_receiver
ON relay_messages(receiver, status, expires_at);

CREATE TABLE IF NOT EXISTS relay_tombstones (
    message_id TEXT PRIMARY KEY,
    payload_hash TEXT,
    final_status TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_relay_tombstones_expires
ON relay_tombstones(expires_at);

CREATE TABLE IF NOT EXISTS relay_peer_cooldowns (
    relay_peer TEXT PRIMARY KEY,
    failed_at BIGINT NOT NULL,
    cooldown_until BIGINT NOT NULL,
    reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_relay_cooldowns_until
ON relay_peer_cooldowns(cooldown_until);
