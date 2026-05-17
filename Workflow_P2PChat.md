# Workflow — P2PChat Implementation Tasks

> Tài liệu hướng dẫn cài đặt code, dựa theo báo cáo `Suggestion_P2Pmessaging.md`.  
> Mỗi task có file cần tạo/sửa, workflow cụ thể, và dependency rõ ràng.

---

## Phase 1: Group Chat — DHT-lite Coordinator

### Task 1.1: HRWHash.java

**File:** `peer-node/src/main/java/.../HRWHash.java`

```java
// Input: List<String> members, String groupId, int k
// Output: List<String> coordinators (top-k theo score giảm dần)

public static List<String> topK(List<String> members, String groupId, int k) {
    return members.stream()
        .sorted((a, b) -> Long.compare(
            score(groupId, b),   // giảm dần
            score(groupId, a)))
        .limit(k)
        .collect(Collectors.toList());
}

private static long score(String groupId, String address) {
    byte[] hash = SHA256(groupId + "|" + address);
    return bytesToLong(hash, 0, 8);  // 8 byte đầu → long
}
```

**Test:** Cùng input → cùng output trên mọi peer. Thêm/xóa 1 member → chỉ 0–1 slot thay đổi.

---

### Task 1.2: GroupInfo model

**File:** `peer-node/src/main/java/.../model/GroupInfo.java`

```java
public class GroupInfo {
    String groupId;        // "grp-" + UUID 8 hex
    String groupName;
    String owner;          // address, luôn = members[0]
    List<String> members;  // thứ tự join, owner ở index 0
    long version;          // Lamport clock, tăng mỗi thay đổi
    long createdAt;
    String groupMode;      // "OPEN" | "RESTRICTED"
}
```

**SQLite schema bổ sung:**
```sql
CREATE TABLE group_cache (
    group_id TEXT PRIMARY KEY,
    group_name TEXT,
    owner TEXT,
    members TEXT,          -- JSON array
    coordinators TEXT,     -- JSON array (tính từ HRW, lưu cache)
    version INTEGER,
    group_mode TEXT DEFAULT 'OPEN',
    last_updated INTEGER
);
```

---

### Task 1.3: CoordinatorManager.java

**File:** `peer-node/src/main/java/.../CoordinatorManager.java`

**Chức năng:**
- Giữ `Map<String, GroupInfo>` cho các group mà peer này là coordinator
- Gossip timer 5s: gửi `COORD_GOSSIP` đến 2 coordinator còn lại
- Xử lý: GROUP_ADD, GROUP_KICK, GROUP_LEAVE, GROUP_DISBAND
- Conflict resolution khi nhận COORD_GOSSIP:
  ```
  V_remote > V_local  → nhận bản remote
  V_remote == V_local → tiebreak: hash(remote) > hash(local) → remote thắng
  V_remote < V_local  → gửi lại bản local về remote
  ```
- Dead coordinator detection: 3 chu kỳ không nhận gossip (15s) → trigger re-election

**Gossip timer workflow:**
```
ScheduledExecutorService.scheduleAtFixedRate(() -> {
    for (GroupInfo group : managedGroups.values()) {
        List<String> coords = HRWHash.topK(group.members, group.groupId, 3);
        for (String c : coords) {
            if (!c.equals(myAddress)) {
                sendCoordGossip(c, group);
            }
        }
    }
}, 5, 5, TimeUnit.SECONDS);
```

---

### Task 1.4: GroupCache.java

**File:** `peer-node/src/main/java/.../GroupCache.java`

**Chức năng:**
- `Map<String, GroupCacheEntry>` tại mỗi peer (Data Plane)
- Mỗi entry: `{ members[], coordinators[], localVersion, lastUpdated }`
- Persist vào SQLite bảng `group_cache`
- Load từ SQLite khi peer restart

```java
public class GroupCacheEntry {
    List<String> members;
    List<String> coordinators;  // = HRWHash.topK(members, groupId, 3)
    long localVersion;
    long lastUpdated;
    String groupState;  // "ACTIVE" | "LEAVING" (cho grace window 3s khi bị kick)
}
```

---

### Task 1.5: LazyRepairManager.java

**File:** `peer-node/src/main/java/.../LazyRepairManager.java`

**4 triggers → GROUP_RESYNC_REQ:**

1. **TCP fail khi gửi** — `PeerClient.sendGroupMessage()` catch IOException → gọi `repair(groupId)`
2. **Nhận CACHE_STALE** — handler trong PeerServer → gọi `repair(groupId)`
3. **Peer restart** — load cache từ SQLite → cho mỗi group: gọi `repair(groupId)`
4. **Heartbeat resumed** — hook vào event heartbeat khôi phục sau >10s mất kết nối → cho mỗi group: gọi `repair(groupId)`

```java
public void repair(String groupId) {
    GroupCacheEntry cache = groupCache.get(groupId);
    List<String> coords = cache.coordinators;
    // Thử C1 → C2 → C3 (timeout 2s mỗi cái)
    for (String c : coords) {
        try {
            GroupInfo fresh = sendResyncReq(c, groupId, cache.localVersion);
            if (fresh != null) {
                groupCache.update(groupId, fresh);
                return;
            }
        } catch (Exception e) { continue; }
    }
}
```

---

### Task 1.6: PeerServer handlers mới

**File:** `peer-node/src/main/java/.../PeerServer.java` — thêm handler cho message types mới

**Message handler routing:**
```java
case "COORD_INIT":       handleCoordInit(msg);       break;
case "COORD_INIT_ACK":   handleCoordInitAck(msg);    break;
case "COORD_GOSSIP":     handleCoordGossip(msg);     break;
case "COORD_GOSSIP_ACK": handleCoordGossipAck(msg);  break;
case "COORD_RESIGN":     handleCoordResign(msg);     break;
case "GROUP_ADD":        handleGroupAdd(msg);         break;
case "GROUP_LEAVE":      handleGroupLeave(msg);       break;
case "GROUP_KICK":       handleGroupKick(msg);        break;
case "GROUP_DISBAND":    handleGroupDisband(msg);     break;
case "GROUP_JOINED":     handleGroupJoined(msg);      break;
case "GROUP_UPDATED":    handleGroupUpdated(msg);     break;
case "GROUP_KICKED":     handleGroupKicked(msg);      break;
case "GROUP_DISBANDED":  handleGroupDisbanded(msg);   break;
case "GROUP_MESSAGE":    handleGroupMessage(msg);     break;
case "GROUP_RESYNC_REQ": handleResyncReq(msg);        break;
case "GROUP_RESYNC_RESP":handleResyncResp(msg);       break;
case "CACHE_STALE":      handleCacheStale(msg);       break;
case "GROUP_GET":        handleGroupGet(msg);         break;
case "GROUP_ADD_REJECTED": handleGroupAddRejected(msg); break;
```

**handleGroupKicked — Grace window 3s:**
```java
void handleGroupKicked(Message msg) {
    String groupId = msg.getGroupId();
    groupCache.setState(groupId, "LEAVING");
    // UI banner: "Bạn đã bị xóa khỏi nhóm này"
    webSocket.send("GROUP_KICKED", groupId);
    
    scheduler.schedule(() -> {
        groupCache.remove(groupId);
        // Dừng lắng nghe hoàn toàn
    }, 3, TimeUnit.SECONDS);
}
```

**handleGroupMessage — Lamport Buffer Window:**
```java
void handleGroupMessage(Message msg) {
    String groupId = msg.getGroupId();
    long clock = msg.getLamportClock();
    
    // Update local clock
    logicalClock.set(Math.max(logicalClock.get(), clock) + 1);
    
    // Check group state — nếu LEAVING thì lưu SQLite nhưng không ACK
    GroupCacheEntry cache = groupCache.get(groupId);
    if ("LEAVING".equals(cache.getGroupState())) {
        db.saveMessage(msg);  // lưu để user đọc
        return;               // không ACK, không reply
    }
    
    // Check version mismatch
    if (msg.getCacheVersion() < cache.localVersion) {
        sendCacheStale(msg.getSender(), groupId, cache.localVersion);
    }
    
    // Buffer window: đưa vào buffer, chờ 200ms rồi flush
    messageBuffer.add(groupId, msg);
    messageBuffer.scheduleFlush(groupId, 200, TimeUnit.MILLISECONDS);
}
```

---

### Task 1.7: PeerClient — sendToCoordinator với fallback + idempotency

**File:** `peer-node/src/main/java/.../PeerClient.java`

```java
// Gửi request đến coordinator với fallback C1→C2→C3 và hard cancel
public Message sendToCoordinator(String groupId, Message request) {
    String requestId = UUID.randomUUID().toString();
    request.setRequestId(requestId);
    
    List<String> coords = groupCache.get(groupId).coordinators;
    Set<String> abandoned = ConcurrentHashMap.newKeySet();
    
    for (String c : coords) {
        try {
            CompletableFuture<Message> future = sendAsync(c, request);
            Message response = future.get(5, TimeUnit.SECONDS);
            return response;  // ACK received trong 5s
        } catch (TimeoutException e) {
            abandoned.add(requestId + ":" + c);  // ABANDON cho coordinator này
            // Tiếp tục với coordinator tiếp theo, cùng requestId
        }
    }
    throw new CoordinatorUnreachableException(groupId);
}
```

---

### Task 1.8: WebServer — REST API cho group

**File:** `peer-node/src/main/java/.../WebServer.java`

**Endpoints mới:**
```
POST /api/group/create   { groupName, members[], groupMode? }
POST /api/group/add      { groupId, username }
POST /api/group/kick     { groupId, target }
POST /api/group/leave    { groupId, newOwner? }
POST /api/group/disband  { groupId }
POST /api/group/msg      { groupId, content }
GET  /api/group/list     → tất cả group peer tham gia
GET  /api/group/{id}     → GroupInfo chi tiết
```

**Luồng POST /api/group/create:**
1. Validate: ≥2 member khác, tất cả online (check Bootstrap)
2. Tạo GroupInfo: groupId = "grp-" + UUID, version=1, members = [self, ...selected]
3. Tính HRW → C1, C2, C3
4. Nếu self là coordinator → lưu vào CoordinatorManager
5. Gửi COORD_INIT đến C2, C3 (nếu self là C1)
6. Gửi GROUP_JOINED đến tất cả member
7. Lưu vào local groupCache
8. Return groupId

---

### Task 1.9: Lamport Clock + MessageBuffer

**File:** `peer-node/src/main/java/.../LamportClock.java`

```java
public class LamportClock {
    private final AtomicLong clock = new AtomicLong(0);
    
    public long tick() { return clock.incrementAndGet(); }
    public long receive(long remote) {
        return clock.updateAndGet(local -> Math.max(local, remote) + 1);
    }
    public long get() { return clock.get(); }
}
```

**File:** `peer-node/src/main/java/.../MessageBuffer.java`

```java
// Per-group buffer, flush sau 200ms
public class MessageBuffer {
    Map<String, List<Message>> buffers = new ConcurrentHashMap<>();
    ScheduledExecutorService scheduler;
    
    public void add(String groupId, Message msg) {
        buffers.computeIfAbsent(groupId, k -> new CopyOnWriteArrayList<>()).add(msg);
    }
    
    public void scheduleFlush(String groupId, long delay, TimeUnit unit) {
        scheduler.schedule(() -> flush(groupId), delay, unit);
    }
    
    private void flush(String groupId) {
        List<Message> msgs = buffers.remove(groupId);
        if (msgs == null) return;
        msgs.sort(Comparator.comparingLong(Message::getLamportClock)
                            .thenComparing(Message::getSender));
        for (Message m : msgs) {
            db.saveMessage(m);
            webSocket.send("GROUP_MESSAGE", m);
        }
    }
}
```

---

## Phase 2: File Transfer

### Task 2.1: FileTransferServer.java

**File:** `peer-node/src/main/java/.../FileTransferServer.java`

- Lắng nghe trên port `peerPort + 1000`
- Accept kết nối TCP, phục vụ file theo chunk 64KB
- Thêm endpoint `/chunk/{index}` cho swarming

```java
// Serve file theo chunk
ServerSocket server = new ServerSocket(peerPort + 1000);
while (running) {
    Socket client = server.accept();
    executor.submit(() -> serveFile(client));
}

void serveFile(Socket client) {
    // Đọc request: transferId, chunkIndex (optional cho resume/swarming)
    // Seek đến chunk offset = chunkIndex * 64KB
    // Gửi từng chunk 64KB + SHA-256 per chunk
    // Gửi FILE_DONE khi hết
}
```

---

### Task 2.2: FileTransferClient.java

**File:** `peer-node/src/main/java/.../FileTransferClient.java`

```java
public void download(String host, int filePort, String transferId, int resumeChunk) {
    Socket socket = new Socket(host, filePort);
    // Gửi: { transferId, resumeChunkIndex }
    // Nhận chunk loop:
    //   - Đọc 64KB
    //   - Verify SHA-256 per chunk
    //   - Lưu vào file + update SQLite: chunk_index, status=OK
    //   - Push WebSocket: FILE_PROGRESS
    // Cuối cùng: verify SHA-256 toàn file → FILE_ACK
}
```

**SQLite schema — chunk tracking:**
```sql
CREATE TABLE file_chunks (
    transfer_id TEXT,
    chunk_index INTEGER,
    sha256_chunk TEXT,
    status TEXT,           -- 'OK' | 'PARTIAL'
    PRIMARY KEY (transfer_id, chunk_index)
);
```

**Resume logic:**
```java
// Tìm chunk cuối cùng đã OK
int resumeFrom = db.getLastOKChunk(transferId) + 1;
download(host, filePort, transferId, resumeFrom);
```

---

### Task 2.3: FileTransferManager.java

**File:** `peer-node/src/main/java/.../FileTransferManager.java`

```java
public class FileTransferManager {
    ConcurrentHashMap<String, TransferState> activeTransfers;
    
    // Tạo transfer mới (sender side)
    public String createTransfer(String filename, String recipient) {
        String transferId = UUID.randomUUID().toString();
        // Validate file size <= MAX_FILE_SIZE
        // Khởi động FileTransferServer nếu chưa chạy
        // Gửi FILE_OFFER
        return transferId;
    }
    
    // Accept transfer (receiver side)  
    public void acceptTransfer(String transferId, String senderHost, int filePort) {
        executor.submit(() -> {
            new FileTransferClient().download(senderHost, filePort, transferId, 0);
        });
    }
}
```

---

### Task 2.4: Group File Transfer — Swarming

**Workflow gửi file vào group:**

1. Sender chia file → N chunk, tính SHA-256 toàn file
2. Gửi `FILE_GROUP_OFFER { groupId, filename, totalChunks, sha256, filePort, transferId }` đến mỗi member
3. Mỗi member accept → kết nối vào sender, tải chunk
4. Sau khi tải một batch chunk → broadcast `FILE_HAVE { transferId, chunkIndexes[] }` đến group
5. Peer khác thấy FILE_HAVE → có thể tải chunk từ peer này thay vì sender

**FILE_HAVE_REQ workflow (late-joiner):**
```
Eve join muộn, nhận FILE_GROUP_OFFER nhưng không biết ai có chunk nào:
  → Eve broadcast FILE_HAVE_REQ { transferId }
  → Mỗi peer có chunk → reply FILE_HAVE_RESP { transferId, chunkIndexes[], filePort }
  → Eve xây dựng chunk map: { peer → chunkIndexes[] }
  → Eve tải chunk từ nhiều peer song song (round-robin hoặc load balance)
```

**Handler trong PeerServer:**
```java
case "FILE_HAVE":      handleFileHave(msg);      break;
case "FILE_HAVE_REQ":  handleFileHaveReq(msg);   break;
case "FILE_HAVE_RESP": handleFileHaveResp(msg);  break;
case "FILE_RESUME":    handleFileResume(msg);     break;

case "FILE_GROUP_OFFER": handleFileGroupOffer(msg); break;
```

**SQLite — lưu chunk availability cho swarming:**
```sql
-- Peer tự biết mình có chunk nào để serve
CREATE TABLE my_file_chunks (
    transfer_id TEXT,
    chunk_index INTEGER,
    file_path TEXT,          -- local path đến file đang tải
    PRIMARY KEY (transfer_id, chunk_index)
);
```

---

## Phase 3: Bootstrap Cache

### Task 3.1: RecentPeersCache

**File:** `peer-node/src/main/java/.../RecentPeersCache.java`

**SQLite schema:**
```sql
CREATE TABLE recent_peers (
    username TEXT PRIMARY KEY,
    address TEXT,
    last_seen INTEGER,
    success_count INTEGER DEFAULT 1
);
-- Chỉ giữ top 5 theo last_seen DESC
```

**Cập nhật timing:** Gọi `upsert(username, address)` tại:
- `PeerClient.sendDirectMessage()` — sau khi gửi thành công
- `PeerServer.handleDirectMessage()` — khi nhận message thành công

**Cleanup:** Sau mỗi upsert, `DELETE FROM recent_peers WHERE username NOT IN (SELECT username FROM recent_peers ORDER BY last_seen DESC LIMIT 5)`

**Lookup fallback:**
```java
public String lookupAddress(String username) {
    // 1. Check recent_peers cache
    String cached = db.getRecentPeerAddress(username);
    if (cached != null) return cached;
    
    // 2. Ask Bootstrap
    try {
        return bootstrap.resolve(username);
    } catch (Exception e) {
        // 3. Bootstrap die → ask top 5 peers
        for (RecentPeer peer : db.getRecentPeers()) {
            try {
                String addr = askPeerForAddress(peer.address, username);
                if (addr != null) return addr;
            } catch (Exception ignored) {}
        }
    }
    // 4. Không tìm được
    throw new PeerNotFoundException(username);
}
```

**Message type mới (optional):**
```
PEER_LOOKUP_REQ  { targetUsername }       → hỏi peer khác
PEER_LOOKUP_RESP { targetUsername, address } → trả về
```

---

## Phase 4: Permission Tiering

### Task 4.1: OPEN / RESTRICTED group mode

**Sửa GroupInfo:** Thêm `groupMode: "OPEN" | "RESTRICTED"`

**Sửa CoordinatorManager.handleGroupAdd():**
```java
void handleGroupAdd(Message msg) {
    GroupInfo group = managedGroups.get(msg.getGroupId());
    String requester = msg.getRequester();
    
    if ("RESTRICTED".equals(group.getGroupMode())) {
        boolean isOwner = requester.equals(group.getOwner());
        boolean isCoord = HRWHash.topK(group.getMembers(), group.getGroupId(), 3)
                                 .contains(requester);
        if (!isOwner && !isCoord) {
            sendReject(requester, group.getGroupId(), "RESTRICTED");
            return;
        }
    }
    
    // Proceed with add...
}
```

**Sửa WebServer POST /api/group/create:** Thêm field `groupMode` (default "OPEN")

**Sửa React UI:** Thêm toggle "Chế độ nhóm" trong form tạo nhóm và trong group settings (chỉ owner thấy)

---

## Phase 5: React UI Updates

### Task 5.1: Group chat UI

- Sidebar: hiển thị group list từ `GET /api/group/list`
- Badge: 👑 owner, ● coordinator, không badge = member
- Group header: tên nhóm, số thành viên, nút "Rời nhóm" / "Kick" (owner only)
- Member list panel: realtime update qua WebSocket `GROUP_UPDATED`
- Tạo nhóm dialog: chọn ≥2 member online, toggle OPEN/RESTRICTED

### Task 5.2: File transfer UI

- Nút 📎 trong MessageInput (chỉ peer/group chat)
- File preview: tên + size trước khi gửi
- Message bubble file: progress bar + tải về button
- Toast FILE_OFFER: accept/reject
- WebSocket events: FILE_OFFER, FILE_PROGRESS, FILE_DONE, FILE_ERROR

### Task 5.3: Message status indicators

- ⏳ Đang gửi (trước khi nhận ACK)
- ✓ Đã gửi (sau ACK)
- 🔴 Thất bại
- ✓✓ Read receipt (khi nhận READ_RECEIPT)

### Task 5.4: Group kicked banner

- Khi nhận `GROUP_KICKED` qua WebSocket:
  - Hiển thị banner đỏ "Bạn đã bị xóa khỏi nhóm này"
  - Disable input (không cho gửi)
  - Sau 3s: chuyển về chat list, xóa group khỏi sidebar

---

## Phase 6: Messaging Enhancements

### Task 6.1: Typing indicator

- Gửi `TYPING { sender, recipient/groupId }` khi user đang gõ
- Debounce 3s: nếu không gõ thêm → dừng gửi
- Không lưu SQLite
- UI: hiển thị "alice đang gõ..." dưới input

### Task 6.2: Broadcast UI

- Mục "# Broadcast" cố định trong sidebar
- Click → chế độ broadcast, lịch sử broadcast riêng
- Gửi = broadcast đến tất cả online peer

---

## Dependency Graph

```
Task 1.1 (HRWHash)
  ↓
Task 1.2 (GroupInfo) ─────────────────────┐
  ↓                                        ↓
Task 1.3 (CoordinatorManager)         Task 1.4 (GroupCache)
  ↓                                        ↓
Task 1.5 (LazyRepairManager) ←────────────┘
  ↓
Task 1.6 (PeerServer handlers)
  ↓
Task 1.7 (PeerClient fallback + idempotency)
  ↓
Task 1.8 (WebServer REST API)
  ↓
Task 1.9 (LamportClock + MessageBuffer)
  ↓
Task 2.1–2.4 (File Transfer) ← có thể song song với Phase 1
  ↓
Task 3.1 (Bootstrap Cache) ← độc lập
  ↓
Task 4.1 (Permission) ← phụ thuộc CoordinatorManager
  ↓
Task 5.1–5.4 (React UI) ← sau khi backend xong
  ↓
Task 6.1–6.2 (Messaging) ← độc lập
```

---

## Checklist tổng hợp

- [ ] HRWHash.java — deterministic coordinator selection
- [ ] GroupInfo.java — model + SQLite schema
- [ ] CoordinatorManager.java — control plane + gossip
- [ ] GroupCache.java — data plane + persist
- [ ] LazyRepairManager.java — 4 triggers repair
- [ ] PeerServer handlers — 20+ message types mới
- [ ] PeerClient — sendToCoordinator fallback + idempotency
- [ ] WebServer — REST API group endpoints
- [ ] LamportClock + MessageBuffer — ordering + 200ms buffer
- [ ] FileTransferServer — serve chunks, resume support
- [ ] FileTransferClient — download + chunk checkpoint
- [ ] FileTransferManager — orchestrate transfers
- [ ] Group swarming — FILE_HAVE, FILE_HAVE_REQ/RESP
- [ ] RecentPeersCache — top 5 peers + fallback lookup
- [ ] OPEN/RESTRICTED permission — CoordinatorManager check
- [ ] React UI — group chat, file transfer, status, kicked banner
- [ ] Typing indicator + Broadcast UI
