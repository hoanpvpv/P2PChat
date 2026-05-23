# Store-and-Forward Mailbox Server Design

> Trang thai: tai lieu nay phan anh code thuc te tren branch `update/ui` (ngay 2026-05-23).
> Phan nao chua implement deu duoc danh dau `ROADMAP`.

## 1. Muc tieu

Tach co che offline message khoi Bootstrap Server va dat trong mot module `mailbox-server` rieng.

Muc tieu da dat:

- Peer uu tien gui truc tiep P2P khi receiver online (`PeerClient.sendDirectMessageStatus`).
- Neu direct send fail, sender store ciphertext vao mailbox.
- Neu mailbox down, sender khong mat message: message nam trong local outbox va retry worker thu lai theo exponential backoff.
- Mailbox chiu duoc crash bang SQLite WAL + commit truoc khi tra `STORE_ACK` + idempotent theo `messageId`/`payloadHash`.

Bootstrap hien tai van con code cu `STORE_MESSAGE` + `offlineMessages` in-memory (xem `PeerRegistry.storeOfflineMessage`). Peer client da khong dung path nay nua; coi nhu legacy can don.

Ngoai pham vi giai doan dau:

- Relay peer replication.
- Mailbox cluster/quorum.
- Quota va scheduled cleanup background (hien chi expire-on-pull).
- Signed PULL/DELIVERY_ACK request (demo mode, khong auth).
- Delivery receipt nguoc tu mailbox ve sender (xem muc 16 - known gap).

## 2. Thanh phan

### Peer Node (`peer-node`)

Trach nhiem:

- Ghi message vao bang local outbox `outbound_messages` truoc khi gui network (`OutboxRepository.savePending`).
- Luu `/app/data` bang persistent volume; mount tu host `data/peers/<username>` (xem `run.sh`).
- Thu gui direct P2P toi receiver (`PeerClient.sendDirectMessageStatus`).
- Direct ACK -> `markDelivered`. Direct fail -> `markDirectRetryable` + push xuong mailbox.
- Khi store mailbox thanh cong -> `markStoredMailbox`.
- Khi peer online lai (PEER_JOIN tu bootstrap), trigger `retryOutboxDeliveryForReceiver` de direct replay.
- Pull mailbox sau register/heartbeat thanh cong (`PeerClient.pullMailboxMessages`).
- Deduplicate incoming bang `messageId` o local DB.
- Gui `DELIVERY_ACK` ve mailbox sau khi luu local.

E2EE identity key luu o `data/e2ee_private.pkcs8` + `data/e2ee_public.x509` (xem `E2EECrypto.loadOrCreate`). Public key + keyId duoc gui kem moi `REGISTER`.

### Bootstrap Server (`bootstrap-server`)

Trach nhiem dang chay:

- `REGISTER` (kem `host:port|keyId|publicKey` trong content).
- `REGISTER_ACK` (tra ve peer list JSON).
- `HEARTBEAT` / `HEARTBEAT_ACK`.
- `DISCOVER` / `PEER_LIST`.
- `PEER_LEAVE` (graceful) + broadcast `PEER_LEAVE`.
- `RESOLVE_MAILBOX` -> tra plain string `mailboxHost:mailboxPort`.
- Broadcast `PEER_JOIN` toi cac peer khac khi register lan dau.
- Luu peer registry in-memory (`PeerRegistry`), bao gom `publicKey` + `keyId`.

Phan legacy chua xoa:

- `STORE_MESSAGE` + `offlineMessages` Map<String, List<Message>> -> on register tra qua `sendOfflineMessages`. Peer hien tai khong con goi `STORE_MESSAGE` vao bootstrap; code can don.

### Mailbox Server (`mailbox-server`)

Trach nhiem:

- TCP server tren port `9100` (`MailboxServer`).
- Nhan `STORE_MESSAGE`, validate envelope, persist vao SQLite, commit truoc khi tra `STORE_ACK`.
- Phan biet `STORED` / `DUPLICATE` / `CONFLICT` (`MailboxRepository.StoreResult`).
- `PULL_MESSAGES(receiver, limit)` -> `PULL_RESPONSE` (list envelope da `status = STORED`).
- `DELIVERY_ACK` -> mark `DELIVERED` + set `delivered_at`.
- `expireOldMessages` chay piggyback moi lan PULL: mark `EXPIRED` cac row vuot TTL.
- TTL mac dinh 7 ngay (`MailboxServer.DEFAULT_TTL_MS`).
- Khong co scheduler background, khong co quota.
- Khong co auth/signature -> demo mode.

## 3. Protocol

### Bootstrap Protocol (`bootstrap-server/.../protocol/MessageType.java`)

```text
REGISTER
REGISTER_ACK
HEARTBEAT
HEARTBEAT_ACK
DISCOVER
PEER_LIST
PEER_JOIN          (broadcast tu bootstrap)
PEER_LEAVE
RESOLVE_MAILBOX
RESOLVE_MAILBOX_ACK
STORE_MESSAGE      (legacy, peer khong con dung)
ERROR
```

Format `REGISTER.content`:

```text
host:port|keyId|publicKeyBase64
```

`keyId` + `publicKey` la optional o protocol nhung peer luon gui kem o build hien tai.

`RESOLVE_MAILBOX_ACK.content`: plain string `host:port` (chua kem public key).

### Mailbox Protocol (`mailbox-server/.../MessageType.java`)

```text
STORE_MESSAGE
STORE_ACK
PULL_MESSAGES
PULL_RESPONSE
DELIVERY_ACK
ERROR
```

`ROADMAP` (chua implement):

```text
MAILBOX_QUOTA_EXCEEDED
MAILBOX_MESSAGE_EXPIRED   (mailbox hien chi mark EXPIRED nhung khong day notify nguoc ve sender)
DELIVERY_RECEIPT          (de bao sender biet B da pull thanh cong)
```

### STORE_ACK payload

Tra ve trong `WireMessage.content`:

```json
{
  "messageId": "...",
  "status": "STORED" | "DUPLICATE" | "CONFLICT"
}
```

Truong hop CONFLICT (cung `messageId` nhung khac `payloadHash`) duoc tra duoi dang `ERROR` voi message `ERROR_CONFLICT: messageId exists with different payloadHash`.

## 4. Message Envelope

Class `MailboxEnvelope` (mailbox-server) va class tuong duong o peer-node deu co cac field:

```json
{
  "messageId": "uuid",
  "conversationId": "alice:bob",
  "sender": "alice",
  "receiver": "bob",
  "type": "DIRECT_MESSAGE",
  "payloadCiphertext": "...",
  "payloadHash": "sha256-hex...",
  "clientCreatedAt": 1779536592538,
  "mailboxStoredAt": 1779536593000,
  "expiresAt": 1780141392538,
  "senderSeq": 0,
  "lastSeenMessageId": null,
  "offlineBatchId": null,
  "senderPublicKey": "base64 X.509 RSA pubkey",
  "senderKeyId": "16-byte hex prefix SHA-256(pubkey)",
  "receiverKeyId": "...",
  "algorithm": "RSA-OAEP-256+A256GCM",
  "nonce": null,
  "schemaVersion": 1
}
```

Truong `algorithm` mac dinh `PLAINTEXT-DEMO` neu sender khong gui (xem `applyDefaults`). Trong code hien tai, peer luon set `RSA-OAEP-256+A256GCM`.

`payloadCiphertext` la chuoi JSON `EncryptedPayload`:

```json
{
  "version": 1,
  "algorithm": "RSA-OAEP-256+A256GCM",
  "senderKeyId": "...",
  "receiverKeyId": "...",
  "iv": "base64 12-byte",
  "wrappedKey": "base64 RSA-OAEP-wrapped AES-256 key",
  "ciphertext": "base64 AES-256-GCM ciphertext (tag included)"
}
```

`payloadHash`: SHA-256 hex cua `payloadJson` plaintext truoc khi encrypt (de idempotency check).

## 5. End-to-End Encryption

### 5.1. Algorithm thuc te

`E2EECrypto` su dung **RSA-OAEP-2048 + AES-256-GCM** (hybrid), khong phai X25519 nhu thiet ke ban dau:

```text
1. Random AES-256 content key.
2. Random 12-byte IV.
3. AES/GCM/NoPadding (tag 128 bit) encrypt plaintext payload.
4. RSA/ECB/OAEPWithSHA-256AndMGF1Padding wrap content key bang receiver public key.
5. Goi JSON: {iv, wrappedKey, ciphertext, senderKeyId, receiverKeyId, algorithm}.
```

Cho phep tu nang cap len X25519/Double-Ratchet sau (chi can doi `ALGORITHM` va `parsePublicKey`).

### 5.2. Identity key luu o dau

Peer:

```text
data/e2ee_private.pkcs8   (RSA private key, base64 PKCS#8)
data/e2ee_public.x509     (RSA public key, base64 X.509)
```

Khong ma hoa at-rest. Khong co bang SQL `peer_identity` / `known_peer_keys`. Public key cua peer khac duoc nhan thong qua bootstrap (`REGISTER`, `PEER_JOIN`, `PEER_LIST`) va luu in-memory o `PeerInfo` cua peer registry local.

### 5.3. Key trust

Hien khong co kiem tra `KEY_CHANGED`. Peer chap nhan public key moi nhat tu bootstrap. TOFU + fingerprint warning la `ROADMAP`.

### 5.4. Authentication mailbox API

Khong co. `STORE_MESSAGE`, `PULL_MESSAGES`, `DELIVERY_ACK` deu khong signed; bat ky ai biet username deu pull duoc. Demo mode.

`ROADMAP`: signed request (private key sender/receiver) khi key directory du tin cay.

## 6. Luong Gui Message

```text
1. App goi PeerClient.sendDirectMessageStatus(sender, receiver, content).
2. OutboxRepository.savePending -> state = PENDING_LOCAL.
3. Lookup receiver online tu PeerManager.
   - Offline -> storeMailbox(...).
4. encryptDirectMessage cho direct send (RSA-OAEP wrap + AES-GCM).
5. markDirectInFlight -> sendWithRetry (3 lan x ACK_TIMEOUT).
   - ACK -> markDelivered, return "DELIVERED_DIRECT".
   - Fail -> markDirectRetryable + storeMailbox.
6. storeMailbox:
   a. Kiem tra circuit breaker (3 failure -> open 30s).
   b. markMailboxInFlight.
   c. MailboxClient.store gui STORE_MESSAGE.
   d. ACK -> markStoredMailbox(host, port), return "STORED_MAILBOX".
   e. Fail -> markMailboxRetryable + tang counter circuit, return "QUEUED_LOCAL".
```

Background:

```text
- PeerClient.retryMailboxOutbox: query OutboxRepository.dueForMailboxRetry, gui lai mailbox.
- PeerClient.retryOutboxDelivery: query dueForDeliveryRetry, thu direct truoc roi mailbox.
- Tren PEER_JOIN(receiver), goi retryOutboxDeliveryForReceiver(receiver) trong thread "outbox-replay-<sender>".
```

## 7. Luong Receiver Online

```text
1. Peer register -> REGISTER_ACK.
2. Sau REGISTER_ACK va o periodic heartbeat, peer goi MailboxClient.pull(100).
3. PULL_RESPONSE tra list envelope status = STORED.
4. With moi envelope:
   - decryptEnvelope(payloadCiphertext) bang private key local.
   - messageExists(messageId)? Skip duplicate de UI.
   - messageRepository.saveMessage (idempotent o DB).
   - mailboxClient.deliveryAck(messageId) -> mailbox mark DELIVERED.
5. Push notification len UI qua WebSocket.
```

Neu B crash sau pull truoc DELIVERY_ACK:

- Mailbox van giu status `STORED`.
- Lan sau B online se pull lai, dedupe theo `messageId`, ACK lai.

## 8. Xu Ly Mailbox Crash

Idempotency cot loi:

```text
STORE_MESSAGE moi:
  - messageId chua ton tai -> insert, commit, STORE_ACK { status: STORED }.
  - messageId da ton tai + payload_hash giong -> STORE_ACK { status: DUPLICATE }.
  - messageId da ton tai + payload_hash khac -> ERROR_CONFLICT, khong ghi de.

DELIVERY_ACK:
  - status STORED hoac DELIVERED -> mark DELIVERED, updated_count > 0.
  - khong khop -> tra { status: NOT_FOUND } (van la STORE_ACK message type).
```

`STORE_ACK` chi duoc tra sau khi `executeUpdate` thanh cong. SQLite WAL bat (`PRAGMA journal_mode=WAL`, `busy_timeout=5000`).

## 9. Mailbox Persistence

`mailbox-server/src/main/resources/schema.sql`:

```sql
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
```

Khong co bang `mailbox_quota` (chua implement quota).

## 10. State Machine

### Sender local outbox (`outbound_messages` o peer DB)

```text
PENDING_LOCAL       -- vua insert
DIRECT_IN_FLIGHT    -- dang try direct P2P
MAILBOX_IN_FLIGHT   -- dang gui STORE_MESSAGE
STORED_MAILBOX      -- mailbox tra STORE_ACK
DELIVERED           -- direct ACK thanh cong (qua direct replay sau khi B online)
FAILED_RETRYABLE    -- van con retry, them attempt_count + next_retry_at
DEAD_LETTER         -- vuot OUTBOX_MAX_ATTEMPTS (=12)
```

Khong co `FAILED_EXPIRED` rieng; sender chi co `DEAD_LETTER`.

Tham so retry (`Constants`):

```text
OUTBOX_MAX_ATTEMPTS         = 12
OUTBOX_BACKOFF_BASE_MS      = 5_000        (5s)
OUTBOX_BACKOFF_MAX_MS       = 900_000      (15 phut)
MAILBOX_CIRCUIT_FAILURE_THRESHOLD = 3
MAILBOX_CIRCUIT_OPEN_MS     = 30_000
```

Backoff: `base * 2^(attempt-1)` cap o `MAX_MS`, plus jitter <= capped/4.

Failure code (cot `failure_code`):

```text
ERR_DIRECT_SEND     -- direct P2P fail
ERR_MAILBOX_SEND    -- mailbox fail / circuit open
ERR_RETRYABLE       -- error chung khac
```

### Mailbox row status (`offline_messages.status`)

```text
STORED       -- vua insert hoac chua duoc ACK
DELIVERED    -- nhan DELIVERY_ACK
EXPIRED      -- expireOldMessages set khi expires_at <= now
```

Khong co `DELETED`; row van giu lai sau khi DELIVERED de idempotency (chua co cleanup background).

### Receiver local

Khong duy tri state machine rieng. Dedupe bang `messageRepository.messageExists(messageId)` truoc khi saveMessage; ACK luon gui sau khi save thanh cong.

## 11. Ordering Cho Offline Messages — `ROADMAP`

Schema da co `sender_seq`, `last_seen_message_id`, `offline_batch_id` nhung chua duoc fill / chua duoc su dung trong render. UI hien tai chi sort theo `timestamp`.

## 12. TTL Va Quota

Hien tai:

- TTL: 7 ngay hard-coded (`MailboxServer.DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000`).
- Cleanup: `expireOldMessages` chay piggyback moi PULL_MESSAGES. STORED qua TTL -> EXPIRED.
- Khong co background scheduler. Row EXPIRED/DELIVERED khong duoc xoa khoi DB.

`ROADMAP`:

```text
- Background scheduler 5 phut / 1 gio cleanup row EXPIRED + DELIVERED > retention.
- Quota per receiver (max_bytes, max_messages).
- MAILBOX_QUOTA_EXCEEDED response cho STORE_MESSAGE khi vuot quota.
```

## 13. Implementation Plan vs Trang Thai

| Phase | Noi dung | Trang thai |
|---|---|---|
| 1 | Mailbox server skeleton + TCP 9100 + Docker | DONE |
| 2 | SQLite persistence + idempotent STORE_MESSAGE + STORE_ACK sau commit | DONE |
| 3 | PULL_MESSAGES + DELIVERY_ACK idempotent | DONE |
| 4 | RESOLVE_MAILBOX o bootstrap | DONE (chua kem pubkey receiver) |
| 5 | Peer outbox durable + retry worker + circuit breaker | DONE |
| 6 | Peer pull on register / heartbeat | DONE |
| 7 | TTL + scheduled cleanup + quota | PARTIAL (TTL=7d expire-on-pull, khong scheduler, khong quota) |
| 8 | Signed STORE/PULL/ACK | TODO (demo mode) |
| 9 | Key directory o bootstrap | DONE phan REGISTER kem pubkey/keyId; chua co `KEY_CHANGED` detection |
| 10 | E2EE payload encryption | DONE bang RSA-OAEP-2048 + AES-256-GCM (chua phai X25519) |
| 11 | Offline ordering metadata + UI render | TODO |
| 12 | Delivery receipt back to sender qua mailbox | TODO (xem muc 16) |

## 14. Acceptance Criteria

Da pass tren docker test (xem WORKLOG / phien chat 2026-05-23):

- A gui B khi B offline -> mailbox store -> A nhan STORE_ACK -> outbox = STORED_MAILBOX.
- B online lai pull duoc message tu mailbox. B ACK -> mailbox row = DELIVERED.
- Mailbox down + B offline: A trang thai `FAILED_RETRYABLE` voi `ERR_MAILBOX_SEND`. Khi mailbox / B back, retry worker dua message len.
- Khi chi B online lai (mailbox van down), A trigger direct replay tren PEER_JOIN -> A outbox = DELIVERED.
- Duplicate STORE_MESSAGE cung `payloadHash` -> STORE_ACK { status: DUPLICATE }, khong tao row trung.
- Mailbox restart (container recreate voi volume) -> data con nguyen, message status giu nguyen.

Chua pass / chua co automated test:

- Mailbox crash giua commit va ACK (cau hinh restart loop).
- Quota.
- Signed request.
- TTL expiry 7 ngay (chua co clock-skew test).

## 15. Ranh Gioi Thiet Ke

- Khong co replication mailbox: mat disk sau STORE_ACK -> mat message.
- Volume bat buoc: `data/peers/<username>` va `data/mailbox` mount tu host. Neu chay container khong mount, DB mat khi recreate.
- Demo mode khong auth: bat ky ai biet username co the pull message.
- E2EE algorithm hien tai RSA-OAEP-2048; doi tac tan cong co kha nang break RSA-2048 trong tuong lai gan thi can nang cap.
- Bootstrap compromise -> co the cap public key gia (chua co KEY_CHANGED warning).
- Receiver mat `data/e2ee_private.pkcs8` -> tat ca offline ciphertext khong giai duoc.

## 16. Known Gap — Delivery Receipt Nguoc

Xac nhan thuc nghiem ngay 2026-05-23:

```text
Setup:
- A=fti, B=ftj, mailbox up.
- Stop B. A gui msg -> A outbox = STORED_MAILBOX, mailbox row = STORED.
- Stop A (chan direct replay).
- Start B -> B pull thanh cong, mailbox row = DELIVERED.
- Stop B (chan direct replay khi A boot lai).
- Start A.

Quan sat:
- Mailbox: row da DELIVERED (delivered_at != null).
- A outbox: state = FAILED_RETRYABLE, lastError = "Direct replay failed".
  A van retry direct send cho message ma B da nhan tu doi.

Ket luan:
- Hien khong co kenh mailbox -> sender de bao "B da pull".
- A chi chuyen STORED_MAILBOX -> DELIVERED khi direct replay thanh cong
  (B online + connect duoc).
```

De xuat sua (chon 1 trong 2):

```text
Option A: Mailbox push receipt.
- Them MessageType DELIVERY_RECEIPT (mailbox -> sender).
- Khi mailbox nhan DELIVERY_ACK tu receiver, enqueue receipt cho sender
  (luu cung schema status = STORED_FOR_SENDER).
- Sender pull receipts moi lan register/heartbeat:
  PULL_RECEIPTS(sender) -> list messageId da DELIVERED.
- Sender goi outboxRepository.markDelivered cho moi messageId.

Option B: ACK-on-retry.
- Khi sender retry STORE_MESSAGE (vi chua tay outbox STORED_MAILBOX),
  mailbox tra response moi: STORE_ACK { status: ALREADY_DELIVERED }.
- Sender markDelivered, dung retry vong.
- Don gian hon, khong can protocol moi nguoc chieu.
- Han che: chi giai quyet duoc khi sender chu dong retry; neu sender
  da o STORED_MAILBOX va khong retry nua thi van khong biet.

Option A trie hon nhung dung ban chat receipt; Option B la quick win
ket hop trong giai doan reliability hardening.
```
