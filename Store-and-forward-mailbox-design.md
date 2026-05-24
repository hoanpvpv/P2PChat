# Store-and-Forward Mailbox Server Design

> Trạng thái: tài liệu này phản ánh code thực tế trên branch `update/ui` (ngày 2026-05-23, lần cập nhật sau khi thêm group store-and-forward).
> Phần nào chưa implement đều được đánh dấu `ROADMAP`.

## 1. Mục tiêu

Tách cơ chế offline message khỏi Bootstrap Server và đặt trong một module `mailbox-server` riêng.

Mục tiêu đã đạt:

- **Direct message** ưu tiên P2P khi receiver online (`PeerClient.sendDirectMessageStatus`); direct fail → store mailbox; mailbox down → local outbox retry exponential backoff.
- **Group message** direct fanout cho online member; offline member → mailbox lưu 1 envelope cho cả nhóm kèm snapshot member list; ack per-member; tất cả acked → mailbox tự xóa.
- **Peer-to-peer gossip** cho group history: khi peer A nhận `PEER_JOIN(B)` và B cùng group, A tự động gửi digest `messageId[]` → B request missing → A relay. Hoạt động được cả khi mailbox down.
- Mailbox chịu được crash bằng SQLite WAL + commit trước khi trả `STORE_ACK` + idempotent theo `messageId`/`payloadHash`.

Bootstrap hiện tại vẫn còn code cũ `STORE_MESSAGE` + `offlineMessages` in-memory (xem `PeerRegistry.storeOfflineMessage`). Peer client đã không dùng path này nữa; coi như legacy cần dọn.

Ngoài phạm vi giai đoạn đầu:

- Relay peer replication (chưa có); peer-gossip giảm reliance vào mailbox nhưng không replace fully.
- Mailbox cluster/quorum.
- Quota và scheduled cleanup background (hiện chỉ expire-on-pull).
- Signed PULL/DELIVERY_ACK + signed gossip relay (demo mode, peer trust nhau).
- Direct delivery receipt ngược từ mailbox về sender (xem mục 16 — known gap).
- E2EE cho group message (hiện plaintext trong mailbox).

## 2. Thành phần

### Peer Node (`peer-node`)

Trách nhiệm:

- Ghi message vào bảng local outbox `outbound_messages` trước khi gửi network (`OutboxRepository.savePending`).
- Lưu `/app/data` bằng persistent volume; mount từ host `data/peers/<username>` (xem `run.sh`).
- Thử gửi direct P2P tới receiver (`PeerClient.sendDirectMessageStatus`).
- Direct ACK → `markDelivered`. Direct fail → `markDirectRetryable` + push xuống mailbox.
- Khi store mailbox thành công → `markStoredMailbox`.
- Khi peer online lại (PEER_JOIN từ bootstrap), trigger `retryOutboxDeliveryForReceiver` để direct replay.
- Pull mailbox sau register/heartbeat thành công (`PeerClient.pullMailboxMessages`).
- Deduplicate incoming bằng `messageId` ở local DB.
- Gửi `DELIVERY_ACK` về mailbox sau khi lưu local.

E2EE identity key lưu ở `data/e2ee_private.pkcs8` + `data/e2ee_public.x509` (xem `E2EECrypto.loadOrCreate`). Public key + keyId được gửi kèm mỗi `REGISTER`.

### Bootstrap Server (`bootstrap-server`)

Trách nhiệm đang chạy:

- `REGISTER` (kèm `host:port|keyId|publicKey` trong content).
- `REGISTER_ACK` (trả về peer list JSON).
- `HEARTBEAT` / `HEARTBEAT_ACK`.
- `DISCOVER` / `PEER_LIST`.
- `PEER_LEAVE` (graceful) + broadcast `PEER_LEAVE`.
- `RESOLVE_MAILBOX` → trả plain string `mailboxHost:mailboxPort`.
- Broadcast `PEER_JOIN` tới các peer khác khi register lần đầu.
- Lưu peer registry in-memory (`PeerRegistry`), bao gồm `publicKey` + `keyId`.

Phần legacy chưa xóa:

- `STORE_MESSAGE` + `offlineMessages` Map<String, List<Message>> → on register trả qua `sendOfflineMessages`. Peer hiện tại không còn gọi `STORE_MESSAGE` vào bootstrap; code cần dọn.

### Mailbox Server (`mailbox-server`)

Trách nhiệm:

- TCP server trên port `9100` (`MailboxServer`).
- Nhận `STORE_MESSAGE`, validate envelope, persist vào SQLite, commit trước khi trả `STORE_ACK`.
- Phân biệt `STORED` / `DUPLICATE` / `CONFLICT` (`MailboxRepository.StoreResult`).
- `PULL_MESSAGES(receiver, limit)` → `PULL_RESPONSE` (list envelope đã `status = STORED`).
- `DELIVERY_ACK` → mark `DELIVERED` + set `delivered_at`.
- `expireOldMessages` chạy piggyback mỗi lần PULL: mark `EXPIRED` các row vượt TTL.
- TTL mặc định 7 ngày cho direct, scale theo member count cho group (xem mục 12.1).
- Không có scheduler background, không có quota.
- Không có auth/signature → demo mode.

## 3. Protocol

### Bootstrap Protocol (`bootstrap-server/.../protocol/MessageType.java`)

```text
REGISTER
REGISTER_ACK
HEARTBEAT
HEARTBEAT_ACK
DISCOVER
PEER_LIST
PEER_JOIN          (broadcast từ bootstrap)
PEER_LEAVE
RESOLVE_MAILBOX
RESOLVE_MAILBOX_ACK
STORE_MESSAGE      (legacy, peer không còn dùng)
ERROR
```

Format `REGISTER.content`:

```text
host:port|keyId|publicKeyBase64
```

`keyId` + `publicKey` là optional ở protocol nhưng peer luôn gửi kèm ở build hiện tại.

`RESOLVE_MAILBOX_ACK.content`: plain string `host:port` (chưa kèm public key).

### Mailbox Protocol (`mailbox-server/.../MessageType.java`)

```text
STORE_MESSAGE
STORE_ACK
PULL_MESSAGES
PULL_RESPONSE
DELIVERY_ACK
ERROR
```

`ROADMAP` (chưa implement):

```text
MAILBOX_QUOTA_EXCEEDED
MAILBOX_MESSAGE_EXPIRED   (mailbox hiện chỉ mark EXPIRED nhưng không đẩy notify ngược về sender)
DELIVERY_RECEIPT          (để báo sender biết B đã pull thành công — direct msg)
```

### Peer-to-Peer Group History Gossip (`peer-node/.../protocol/MessageType.java`)

3 message type mới cho phép peer online relay tin group cho peer vừa online lại, kể cả khi mailbox down:

```text
GROUP_HISTORY_DIGEST     -- A -> B: "trong group X tôi có các messageId [...]"
GROUP_HISTORY_REQUEST    -- B -> A: "vui lòng gửi lại các messageId [...] tôi chưa có"
GROUP_HISTORY_RESPONSE   -- A -> B: "đây là messages đầy đủ"
```

Trigger: peer A nhận `PEER_JOIN(B)` từ bootstrap; nếu B là member của group nào đó trong group cache của A, sau 2s A mở connection mới tới B và gửi digest của 24h gần nhất. B so với local DB, mở connection mới tới A gửi REQUEST các id thiếu. A mở connection mới gửi RESPONSE đầy đủ Message objects. B save vào DB + tự gửi `DELIVERY_ACK` về mailbox cho mỗi msg đã nhận (để mailbox đếm đủ ack → mark DELIVERED).

3 message type đi qua 3 socket độc lập (fire-and-forget) thay vì giữ 1 socket xuyên suốt, để tránh deadlock và socket timeout.

### STORE_ACK payload

Trả về trong `WireMessage.content`:

```json
{
  "messageId": "...",
  "status": "STORED" | "DUPLICATE" | "CONFLICT"
}
```

Trường hợp CONFLICT (cùng `messageId` nhưng khác `payloadHash`) được trả dưới dạng `ERROR` với message `ERROR_CONFLICT: messageId exists with different payloadHash`.

## 4. Message Envelope

Class `MailboxEnvelope` (mailbox-server) và class tương đương ở peer-node đều có các field:

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
  "schemaVersion": 1,
  "groupId": null,
  "groupMembers": null
}
```

Với **group message**:

- `receiver` là chuỗi rỗng (`""`).
- `groupId` set theo group của tin nhắn.
- `groupMembers` là CSV username các member offline tại thời điểm gửi (snapshot). Các member online đã nhận direct sẽ KHÔNG nằm trong list này.
- `algorithm` = `PLAINTEXT-DEMO` (group msg hiện chưa E2EE; payloadCiphertext là JSON plaintext).

Trường `algorithm` mặc định `PLAINTEXT-DEMO` nếu sender không gửi (xem `applyDefaults`). Direct message hiện tại luôn set `RSA-OAEP-256+A256GCM`.

`payloadCiphertext` là chuỗi JSON `EncryptedPayload`:

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

`payloadHash`: SHA-256 hex của `payloadJson` plaintext trước khi encrypt (để idempotency check).

## 5. End-to-End Encryption

### 5.1. Algorithm thực tế

`E2EECrypto` sử dụng **RSA-OAEP-2048 + AES-256-GCM** (hybrid), không phải X25519 như thiết kế ban đầu:

```text
1. Random AES-256 content key.
2. Random 12-byte IV.
3. AES/GCM/NoPadding (tag 128 bit) encrypt plaintext payload.
4. RSA/ECB/OAEPWithSHA-256AndMGF1Padding wrap content key bằng receiver public key.
5. Gói JSON: {iv, wrappedKey, ciphertext, senderKeyId, receiverKeyId, algorithm}.
```

Cho phép tự nâng cấp lên X25519/Double-Ratchet sau (chỉ cần đổi `ALGORITHM` và `parsePublicKey`).

### 5.2. Identity key lưu ở đâu

Peer:

```text
data/e2ee_private.pkcs8   (RSA private key, base64 PKCS#8)
data/e2ee_public.x509     (RSA public key, base64 X.509)
```

Không mã hóa at-rest. Không có bảng SQL `peer_identity` / `known_peer_keys`. Public key của peer khác được nhận thông qua bootstrap (`REGISTER`, `PEER_JOIN`, `PEER_LIST`) và lưu in-memory ở `PeerInfo` của peer registry local.

### 5.3. Key trust

Hiện không có kiểm tra `KEY_CHANGED`. Peer chấp nhận public key mới nhất từ bootstrap. TOFU + fingerprint warning là `ROADMAP`.

### 5.4. Authentication mailbox API

Không có. `STORE_MESSAGE`, `PULL_MESSAGES`, `DELIVERY_ACK` đều không signed; bất kỳ ai biết username đều pull được. Demo mode.

`ROADMAP`: signed request (private key sender/receiver) khi key directory đủ tin cậy.

## 6. Luồng Gửi Message

### 6.1. Direct Message

```text
1. App gọi PeerClient.sendDirectMessageDetailed(sender, receiver, content).
2. OutboxRepository.savePending -> state = PENDING_LOCAL.
3. Lookup receiver online từ PeerManager.
   - Offline -> storeMailbox(...).
4. encryptDirectMessage cho direct send (RSA-OAEP wrap + AES-GCM).
5. markDirectInFlight -> sendWithRetry (3 lần x ACK_TIMEOUT).
   - ACK -> markDelivered, return "DELIVERED_DIRECT".
   - Fail -> markDirectRetryable + storeMailbox.
6. storeMailbox:
   a. Kiểm tra circuit breaker (3 failure -> open 30s).
   b. markMailboxInFlight.
   c. MailboxClient.store gửi STORE_MESSAGE.
   d. ACK -> markStoredMailbox(host, port), return "STORED_MAILBOX".
   e. Fail -> markMailboxRetryable + tăng counter circuit, return "QUEUED_LOCAL".
7. notifyOutboxState -> WS event OUTBOX_UPDATE để UI cập nhật badge trạng thái.
```

Background:

```text
- PeerClient.retryMailboxOutbox: query OutboxRepository.dueForMailboxRetry, gửi lại mailbox.
- PeerClient.retryOutboxDelivery: query dueForDeliveryRetry, thử direct trước rồi mailbox.
- Trên PEER_JOIN(receiver), gọi retryOutboxDeliveryForReceiver(receiver) trong thread "outbox-replay-<sender>".
```

### 6.2. Group Message

```text
1. App gọi PeerClient.sendGroupMessage(sender, groupId, content).
2. messageRepository.saveMessage (lưu local cho sender).
   -> Sender LUÔN có msg trong local DB, UI render ngay dù không ai khác online.
3. Loop qua members trong group cache:
   - Skip self.
   - sendSingle(message, memberHost, memberPort).
   - Nếu fail: thêm username vào missedMemberUsernames + lazyRepair(groupId).
4. Nếu missedMemberUsernames không rỗng:
   - Build envelope plaintext: groupId + groupMembers (CSV username).
   - TTL tính bằng groupTtlMillis(totalGroupSize) — xem section 12.1.
   - MailboxClient.storeGroup -> STORE_MESSAGE tới mailbox.
   - Mailbox lưu 1 row duy nhất cho cả group.
5. Không có outbox per-member: group message không dùng outbound_messages bảng
   (vì sender đã forget once delivered/stored). Không có retry-direct như direct msg;
   thay vào đó peer-relay gossip (section 6.3) lo khoản recovery.
```

UI sender side (`App.jsx handleSend` cho group): sau khi `await sendGroupMessage` resolve, optimistic append message vào state `messages` với `Date.now()` timestamp. UI render bubble ngay, không chờ server reply. Group msg hiện tại không có `deliveryState` badge (chưa track per-member ack ở sender side).

### 6.3. Peer-to-Peer Group History Gossip (anti-mailbox-dependency)

```text
1. Peer A nhận PEER_JOIN(B) từ bootstrap.
2. handlePeerJoin spawn thread "gossip-<B>", sleep 2s.
3. gossipGroupHistoryTo(B, host, port):
   - Loop tất cả group trong GroupCache của A.
   - Nếu B là member: lấy messageId trong 24h gần nhất từ DB local A.
   - sendToPeer(host, port, GROUP_HISTORY_DIGEST{groupId, messageIds[]}).
4. B nhận DIGEST -> handleGroupHistoryDigest:
   - Tính missing = remoteIds \ messageExists.
   - Nếu missing không rỗng: mở connection mới đến A, gửi GROUP_HISTORY_REQUEST.
5. A nhận REQUEST -> handleGroupHistoryRequest:
   - getMessagesByIds(requestedIds).
   - Mở connection mới đến B, gửi GROUP_HISTORY_RESPONSE{messages[]}.
6. B nhận RESPONSE -> handleGroupHistoryResponse:
   - Với mỗi message: skip nếu messageExists, saveMessage, broadcastWs.
   - gossipAckToMailbox(messageId): gửi DELIVERY_ACK cho mailbox để mailbox đếm ack.
```

3 phase đi qua 3 socket độc lập (fire-and-forget). Không giữ 1 socket xuyên suốt vì:

- Tăng timeout nếu peer đang xử lý task khác.
- Có thể deadlock nếu cả 2 bên muốn gửi trước khi nhận.
- 3 socket cho phép dispatch song song nhiều group/peer.

Gossip hoạt động **độc lập với mailbox**: kể cả mailbox đã chết, nếu A và B cùng online và A đã có tin nhóm, B sẽ nhận được qua gossip.

## 7. Luồng Receiver Online

### 7.1. Direct + Group Pull

```text
1. Peer register -> REGISTER_ACK.
2. Sau REGISTER_ACK và ở periodic heartbeat, peer gọi MailboxClient.pull(100).
3. Mailbox trả 2 nhóm envelope (xem section 9.2):
   a. Direct: receiver = me.
   b. Group: group_id != NULL, me trong group_members CSV, chưa có row trong group_delivery_acks(message_id, me).
4. Với mỗi envelope:
   - decryptEnvelope(payloadCiphertext) (direct: RSA + AES-GCM; group: plaintext passthrough).
   - messageExists(messageId)? Skip duplicate để UI.
   - messageRepository.saveMessage (idempotent ở DB).
   - mailboxClient.deliveryAck(messageId):
     * Direct -> mailbox mark offline_messages.status = DELIVERED.
     * Group -> mailbox insert vào group_delivery_acks; nếu count(acks) >= |group_members| -> mark DELIVERED.
   - PeerServer.broadcastIncoming -> WS event DIRECT_MESSAGE / GROUP_MESSAGE -> UI auto refresh.
```

Nếu B crash sau pull trước DELIVERY_ACK:

- Direct: mailbox vẫn giữ STORED. Lần sau B online sẽ pull lại, dedupe theo `messageId`, ACK lại.
- Group: tương tự. group_delivery_acks chưa có (message_id, B). Pull lại sẽ thấy row vẫn match.

## 8. Xử Lý Mailbox Crash

Idempotency cốt lõi:

```text
STORE_MESSAGE mới:
  - messageId chưa tồn tại -> insert, commit, STORE_ACK { status: STORED }.
  - messageId đã tồn tại + payload_hash giống -> STORE_ACK { status: DUPLICATE }.
  - messageId đã tồn tại + payload_hash khác -> ERROR_CONFLICT, không ghi đè.

DELIVERY_ACK (direct):
  - status STORED hoặc DELIVERED -> mark DELIVERED, updated_count > 0.
  - không khớp -> trả { status: NOT_FOUND } (vẫn là STORE_ACK message type).

DELIVERY_ACK (group, khi message có group_id != NULL):
  - INSERT OR IGNORE vào group_delivery_acks(message_id, member, delivered_at).
  - Đếm COUNT(*) trong group_delivery_acks WHERE message_id = ?.
  - Nếu count >= |split(group_members, ',')| -> UPDATE offline_messages SET status='DELIVERED'.
```

`STORE_ACK` chỉ được trả sau khi `executeUpdate` thành công. SQLite WAL bật (`PRAGMA journal_mode=WAL`, `busy_timeout=5000`).

## 9. Mailbox Persistence

### 9.1. Schema

`mailbox-server/src/main/resources/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS offline_messages (
    message_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    receiver TEXT NOT NULL,        -- "" cho group message
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
    group_id TEXT,                  -- NULL = direct, != NULL = group
    group_members TEXT              -- CSV username members offline tại thời điểm gửi
);

-- Per-member ack tracking cho group msg.
-- Direct msg KHÔNG dùng bảng này (chỉ mark thẳng status='DELIVERED').
CREATE TABLE IF NOT EXISTS group_delivery_acks (
    message_id TEXT NOT NULL,
    member TEXT NOT NULL,
    delivered_at BIGINT NOT NULL,
    PRIMARY KEY (message_id, member)
);

CREATE INDEX IF NOT EXISTS idx_offline_receiver_status
    ON offline_messages(receiver, status, mailbox_stored_at);

CREATE INDEX IF NOT EXISTS idx_offline_expires
    ON offline_messages(expires_at, status);

CREATE INDEX IF NOT EXISTS idx_offline_group
    ON offline_messages(group_id, status);

CREATE INDEX IF NOT EXISTS idx_group_acks_msg
    ON group_delivery_acks(message_id);
```

Schema có `IF NOT EXISTS` ở mức table. Khi nâng cấp (vd thêm column mới), cần xóa `data/mailbox/` hoặc viết ALTER TABLE migration thủ công. Hiện tại không có migration framework.

Không có bảng `mailbox_quota` (chưa implement quota).

### 9.2. Pull query cho group

```sql
-- Group messages where receiver in snapshot AND chưa ack
SELECT om.*
FROM offline_messages om
LEFT JOIN group_delivery_acks ga
  ON ga.message_id = om.message_id AND ga.member = :receiver
WHERE om.group_id IS NOT NULL
  AND om.status = 'STORED'
  AND om.expires_at > :now
  AND ga.member IS NULL                                -- chưa ack
  AND ',' || om.group_members || ',' LIKE :pattern    -- ',USERNAME,'
ORDER BY om.mailbox_stored_at ASC
LIMIT :n;
```

Mailbox không trả envelope cho member không có trong snapshot (dù group cache của peer có list rộng hơn).

## 10. State Machine

### Sender local outbox (`outbound_messages` ở peer DB)

```text
PENDING_LOCAL       -- vừa insert
DIRECT_IN_FLIGHT    -- đang try direct P2P
MAILBOX_IN_FLIGHT   -- đang gửi STORE_MESSAGE
STORED_MAILBOX      -- mailbox trả STORE_ACK
DELIVERED           -- direct ACK thành công (qua direct replay sau khi B online)
FAILED_RETRYABLE    -- vẫn còn retry, thêm attempt_count + next_retry_at
DEAD_LETTER         -- vượt OUTBOX_MAX_ATTEMPTS (=12)
```

Không có `FAILED_EXPIRED` riêng; sender chỉ có `DEAD_LETTER`.

Tham số retry (`Constants`):

```text
OUTBOX_MAX_ATTEMPTS         = 12
OUTBOX_BACKOFF_BASE_MS      = 5_000        (5s)
OUTBOX_BACKOFF_MAX_MS       = 900_000      (15 phút)
MAILBOX_CIRCUIT_FAILURE_THRESHOLD = 3
MAILBOX_CIRCUIT_OPEN_MS     = 30_000
```

Backoff: `base * 2^(attempt-1)` cap ở `MAX_MS`, plus jitter <= capped/4.

Failure code (cột `failure_code`):

```text
ERR_DIRECT_SEND     -- direct P2P fail
ERR_MAILBOX_SEND    -- mailbox fail / circuit open
ERR_RETRYABLE       -- error chung khác
```

### Mailbox row status (`offline_messages.status`)

```text
STORED       -- vừa insert hoặc chưa được ACK đầy đủ
DELIVERED    -- direct: nhận DELIVERY_ACK
             -- group: ack count >= |group_members|
EXPIRED      -- expireOldMessages set khi expires_at <= now
```

Không có `DELETED`; row vẫn giữ lại sau khi DELIVERED để idempotency (chưa có cleanup background).

### Group ack tracking (`group_delivery_acks`)

Không phải state machine — chỉ là log "ai đã ack chưa". Cột:

```text
message_id  -- FK tới offline_messages
member      -- username của thành viên đã nhận
delivered_at
```

Group msg được coi là DELIVERED khi `COUNT(group_delivery_acks WHERE message_id = X) >= |split(group_members)|`. INSERT OR IGNORE để tránh ack 2 lần từ cùng 1 member.

Group không có outbox bên sender: sender chỉ gửi mailbox 1 lần rồi quên. Không có retry per-member. Lý do: sender đã forget; retry/recovery thực tế xảy ra qua **peer-gossip** khi member online (xem section 6.3).

### Receiver local

Không duy trì state machine riêng. Dedupe bằng `messageRepository.messageExists(messageId)` trước khi saveMessage; ACK luôn gửi sau khi save thành công.

## 11. Ordering Cho Offline Messages — `ROADMAP`

Schema đã có `sender_seq`, `last_seen_message_id`, `offline_batch_id` nhưng chưa được fill / chưa được sử dụng trong render. UI hiện tại chỉ sort theo `timestamp`.

## 12. TTL Và Quota

### 12.1. TTL hiện tại

**Direct message**: 7 ngày cố định (`MailboxClient.DEFAULT_TTL_MS = 7d`).

**Group message**: TTL scale theo số member trong group, công thức:

```
ttl = max(12h, 7d / log2(max(2, N)))
```

N là tổng số member trong group cache tại thời điểm gửi (không phải chỉ số offline). Ý tưởng: nhóm càng to thì xác suất ít nhất 1 người online để gossip cho rest càng cao — mailbox không cần giữ lâu bằng case 1-1.

```
N=2:   7.0d    (giống direct, không có lợi thế gossip)
N=3:   4.4d
N=5:   3.0d
N=10:  2.1d
N=20:  1.6d
N=100: 1.05d
N=1000: 0.7d  -> floor 12h
```

Implementation: `MailboxClient.groupTtlMillis(int totalMembers)`. Sender set `envelope.expiresAt` trước khi gửi STORE_MESSAGE; mailbox respect giá trị này (chỉ default 7d nếu envelope không set).

### 12.2. Cleanup và Quota

- Cleanup: `expireOldMessages` chạy piggyback mỗi PULL_MESSAGES. STORED quá TTL → EXPIRED.
- Không có background scheduler. Row EXPIRED/DELIVERED không được xóa khỏi DB.

`ROADMAP`:

```text
- Background scheduler 5 phút / 1 giờ cleanup row EXPIRED + DELIVERED > retention.
- Quota per receiver (max_bytes, max_messages).
- MAILBOX_QUOTA_EXCEEDED response cho STORE_MESSAGE khi vượt quota.
- Adaptive TTL shortening sau ack đầu tiên (xem section 16.2 — Option C bị defer).
```

### 12.3. Trade-off của TTL group ngắn

**Lợi ích**:
- Mailbox storage giảm ~3-4x cho nhóm trung bình (5-10 member).
- Auto expire sớm → bản cleanup không quá tải khi nhiều group đông.
- Phù hợp với giả thuyết "có peer-relay nên không cần mailbox giữ lâu".

**Rủi ro**:
- Nếu **tất cả** member offline cùng 1 thời gian dài (vd công tác, kỳ nghỉ), tin có thể hết hạn trước khi ai đó online lại. Floor 12h cho độ safety tối thiểu nhưng không tuyệt đối an toàn với nhóm rất đông (TTL chỉ 12h).
- Edge case "đến lễ, cả bạn bè không dùng mạng vài ngày" → tin nhóm dễ mất hơn tin 1-1.

Mitigation cho production: cho phép override per-group (vd "VIP group" giữ TTL 7d), hoặc fallback signaling "msg gần hết hạn, ai online?".

## 13. Implementation Plan vs Trạng Thái

| Phase | Nội dung | Trạng thái |
|---|---|---|
| 1 | Mailbox server skeleton + TCP 9100 + Docker | DONE |
| 2 | SQLite persistence + idempotent STORE_MESSAGE + STORE_ACK sau commit | DONE |
| 3 | PULL_MESSAGES + DELIVERY_ACK idempotent | DONE |
| 4 | RESOLVE_MAILBOX ở bootstrap | DONE (chưa kèm pubkey receiver) |
| 5 | Peer outbox durable + retry worker + circuit breaker | DONE |
| 6 | Peer pull on register / heartbeat | DONE |
| 7 | TTL + scheduled cleanup + quota | PARTIAL (TTL=7d expire-on-pull, không scheduler, không quota) |
| 8 | Signed STORE/PULL/ACK | TODO (demo mode) |
| 9 | Key directory ở bootstrap | DONE phần REGISTER kèm pubkey/keyId; chưa có `KEY_CHANGED` detection |
| 10 | E2EE payload encryption (direct) | DONE bằng RSA-OAEP-2048 + AES-256-GCM (chưa phải X25519) |
| 11 | Offline ordering metadata + UI render | TODO |
| 12 | Delivery receipt back to sender qua mailbox (direct) | TODO (xem mục 16) |
| 13 | **Group store-and-forward** (mailbox + ack tracking + peer gossip) | **DONE** |
| 14 | E2EE payload cho group (shared sender key) | TODO (group hiện plaintext) |
| 15 | Periodic gossip (not only PEER_JOIN trigger) | TODO |
| 16 | **Group TTL scale theo member count** (7d / log2 N) | **DONE** |
| 17 | Adaptive TTL shortening sau ack đầu tiên | TODO (defer vì cần signed relay trước) |

## 14. Acceptance Criteria

Đã pass trên docker test (xem WORKLOG / phiên chat 2026-05-23):

**Direct message:**

- A gửi B khi B offline → mailbox store → A nhận STORE_ACK → outbox = STORED_MAILBOX.
- B online lại pull được message từ mailbox. B ACK → mailbox row = DELIVERED.
- Mailbox down + B offline: A trạng thái `FAILED_RETRYABLE` với `ERR_MAILBOX_SEND`. Khi mailbox / B back, retry worker đưa message lên.
- Khi chỉ B online lại (mailbox vẫn down), A trigger direct replay trên PEER_JOIN → A outbox = DELIVERED.
- Duplicate STORE_MESSAGE cùng `payloadHash` → STORE_ACK { status: DUPLICATE }, không tạo row trùng.
- Mailbox restart (container recreate với volume) → data còn nguyên, message status giữ nguyên.

**Group message (added 2026-05-23):**

- Group 3 member, 2 offline. Sender gửi tin → mailbox lưu 1 row với `group_id`, `group_members='gbob,gcharlie'`, status=STORED.
- 1 member online lại → pull mailbox → nhận tin. Mailbox tạo row `group_delivery_acks(message_id, gbob)`. offline_messages status vẫn STORED.
- Member thứ 2 online → pull mailbox → ack. Mailbox tạo row thứ 2. Count(acks)=2 >= |members|=2 → mailbox auto promote status=DELIVERED.
- **Mailbox DOWN scenario**: stop mailbox sau khi 1 member đã pull. Member cuối online → bootstrap broadcast PEER_JOIN → peer đã có tin gửi `GROUP_HISTORY_DIGEST` → member cuối gửi REQUEST → nhận RESPONSE → save local. Khi mailbox up lại, member cuối gửi DELIVERY_ACK → mailbox mark DELIVERED.
- **Group TTL scale**: gửi msg vào group N=3, mailbox row có `expires_at - mailbox_stored_at = 4.42d`, đúng công thức `7d / log2(3)`. Với N=2 thì TTL=7d (giống direct).
- **Solo sender UI**: chỉ sender online trong nhóm (2 member khác offline). Sender gửi msg → API trả `sent:false` (không delivered direct) NHƯNG msg vẫn lưu local DB sender (`saveMessage` chạy trước fanout) và frontend optimistic append → sender thấy msg của mình trong UI ngay. Verify qua `/api/group-history/<gid>` từ sender thấy msg mới gửi.

Chưa pass / chưa có automated test:

- Mailbox crash giữa commit và ACK (cấu hình restart loop).
- Quota.
- Signed request / signed gossip relay.
- TTL expiry 7 ngày (chưa có clock-skew test).
- Member rejoin group sau khi msg đã gửi (snapshot lỗi — member có được tin lịch sử không).
- Gossip flood prevention khi nhóm >100 thành viên.

## 15. Ranh Giới Thiết Kế

- Không có replication mailbox: mất disk sau STORE_ACK → mất message.
- Volume bắt buộc: `data/peers/<username>` và `data/mailbox` mount từ host. Nếu chạy container không mount, DB mất khi recreate.
- Demo mode không auth: bất kỳ ai biết username có thể pull message.
- E2EE algorithm hiện tại RSA-OAEP-2048; đối tác tấn công có khả năng break RSA-2048 trong tương lai gần thì cần nâng cấp.
- Bootstrap compromise → có thể cấp public key giả (chưa có KEY_CHANGED warning).
- Receiver mất `data/e2ee_private.pkcs8` → tất cả offline ciphertext không giải được.

## 16. Known Gaps

### 16.1. Direct delivery receipt ngược (chưa fix)

Xác nhận thực nghiệm ngày 2026-05-23:

```text
Setup:
- A=fti, B=ftj, mailbox up.
- Stop B. A gửi msg -> A outbox = STORED_MAILBOX, mailbox row = STORED.
- Stop A (chặn direct replay).
- Start B -> B pull thành công, mailbox row = DELIVERED.
- Stop B (chặn direct replay khi A boot lại).
- Start A.

Quan sát:
- Mailbox: row đã DELIVERED (delivered_at != null).
- A outbox: state = FAILED_RETRYABLE, lastError = "Direct replay failed".
  A vẫn retry direct send cho message mà B đã nhận từ đời.

Kết luận:
- Hiện không có kênh mailbox -> sender để báo "B đã pull".
- A chỉ chuyển STORED_MAILBOX -> DELIVERED khi direct replay thành công
  (B online + connect được).
```

Đề xuất sửa (chọn 1 trong 2):

```text
Option A: Mailbox push receipt.
- Thêm MessageType DELIVERY_RECEIPT (mailbox -> sender).
- Khi mailbox nhận DELIVERY_ACK từ receiver, enqueue receipt cho sender
  (lưu cùng schema status = STORED_FOR_SENDER).
- Sender pull receipts mỗi lần register/heartbeat:
  PULL_RECEIPTS(sender) -> list messageId đã DELIVERED.
- Sender gọi outboxRepository.markDelivered cho mỗi messageId.

Option B: ACK-on-retry.
- Khi sender retry STORE_MESSAGE (vì chưa tẩy outbox STORED_MAILBOX),
  mailbox trả response mới: STORE_ACK { status: ALREADY_DELIVERED }.
- Sender markDelivered, dừng retry vòng.
- Đơn giản hơn, không cần protocol mới ngược chiều.
- Hạn chế: chỉ giải quyết được khi sender chủ động retry; nếu sender
  đã ở STORED_MAILBOX và không retry nữa thì vẫn không biết.

Option A trịnh trọng hơn nhưng đúng bản chất receipt; Option B là quick win
kết hợp trong giai đoạn reliability hardening.
```

### 16.2. Group message — gaps còn lại

Đã fix store-and-forward + peer gossip, nhưng còn các vấn đề sau:

**Không có signature relay.**
Peer A gossip group msg cho peer B. B trust hoàn toàn content của A. Nếu A độc hại, A có thể:
- Sửa nội dung tin nhắn (B tưởng là message từ sender).
- Inject tin giả như thể là từ sender.
- Báo thật là "không có tin gì" dù A đã nhận.

Fix: sender ký message bằng RSA-PSS hoặc HMAC với group key. Relayer không sửa được payload, B verify trước khi save. Cột `senderPublicKey` đã có trong envelope → có thể dùng để verify.

**Gossip phụ thuộc PEER_JOIN, không periodic.**
Nếu A và B cùng online từ trước và A nhận tin mới sau đó, B sẽ KHÔNG được gossip nếu B không reconnect. Hiện B chỉ nhận tin mới qua direct fanout của sender (nếu sender online + biết B) hoặc qua mailbox pull (nếu B chưa online lúc gửi).

Edge case: A online và có tin trong group, B online và chưa có tin, cả 2 đều online nhưng không có PEER_JOIN mới → B sẽ không learn từ A. B chỉ có thể pull mailbox (nếu sender đã store).

Fix: periodic gossip mỗi N giây (vd 60s), hoặc trigger sau một khoảng không có tin mới.

**Membership snapshot frozen.**
Member D join sau khi tin đã gửi (snapshot không có D). Mailbox không trả tin đó cho D khi D pull (vì D không trong `group_members`). Peer-gossip cũng phụ thuộc digest 24h gần nhất — nếu D là member mới, A vẫn sẽ digest cho D nhưng D có nên nhận tin lịch sử trong khi D chưa phải member lúc gửi?

Quyết định hiện tại: D **vẫn nhận** vì gossip handler không check membership-at-time-of-send. Đây giống Telegram chứ không giống Signal.

Fix nếu muốn strict: thêm `created_at` của group msg vào envelope và `joined_at` ở membership cache. Gossip skip tin trước joined_at của receiver.

**Group msg plaintext trong mailbox.**
Mailbox nhìn thấy full payload group msg. Có thể đọc và correlate. Direct msg đã encrypt; group thì không.

Fix: shared group key (Signal sender key). Sender encrypt payload bằng group key, relayer + mailbox chỉ thấy ciphertext. Member nào biết group key mới decrypt được. Khi member kick/leave, rotate group key.
