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
    online INTEGER DEFAULT 0
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
