CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    receiver TEXT,
    group_name TEXT,
    content TEXT NOT NULL,
    type TEXT NOT NULL,
    timestamp BIGINT NOT NULL,
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
