# Đề xuất nâng cấp P2PChat

> **Đánh giá nhanh trạng thái hiện tại:**  
> ✅ Chat trực tiếp (DIRECT_MESSAGE + ACK + retry 3 lần)  
> ✅ Chat nhóm (GROUP_MESSAGE gửi trực tiếp P2P dựa trên danh sách thành viên trong groupCache)  
> ✅ Đồng bộ trạng thái nhóm phân tán (DHT-lite Coordinator Model qua Rendezvous Hashing - HRW)  
> ✅ Đồng bộ logic Control/Data/Repair plane cho Chat nhóm  
> ✅ Truyền tải File 1-1 & Nhóm (Mô hình pull trực tiếp, chunk checkpoint, resume và xác thực SHA-256)  
> ✅ Broadcast toàn mạng  
> ✅ Heartbeat + dead peer detection  
> ✅ Offline message store-and-forward  
> ✅ SQLite lưu lịch sử tin nhắn và trạng thái file transfers  
> ✅ Web UI React + REST + WebSocket realtime  
> ✅ Bootstrap Dashboard  
> ❌ P2P Swarming trong truyền file Nhóm (Mới định nghĩa các message types, chưa triển khai swarming logic thực tế)  
> ❌ Typing indicator / read receipt  

---

## 1. Group Chat — DHT-lite Distributed Coordinator Model

### 1.0. Vị trí trong không gian thiết kế

```
Centralized      Bootstrap-Registry     DHT-lite (mới)         Kademlia
   Server         (thiết kế cũ)          (đề xuất)            (BitTorrent)
     │                  │                    │                     │
 1 server lưu     Bootstrap giữ         K=3 coordinator       XOR routing
  tất cả state     GroupInfo            chọn bằng HRW Hash     k-bucket
                   SPOF cho group       deterministic          O(log N) hop
     ↑                  ↑                    ↑                     ↑
 Centralized       Semi-P2P            P2P + deterministic      Full P2P
 SPOF              Bootstrap SPOF       No SPOF                  Phức tạp
```

**Tại sao DHT-lite?**
- Bootstrap **không còn lưu GroupInfo** → loại bỏ SPOF cho group
- Coordinator chọn **deterministic** qua Rendezvous Hash → không ngẫu nhiên
- Coordinator tự bầu lại khi peer chết → self-healing
- Đủ học thuật để demo, đủ đơn giản để implement trong ~1 tuần

---

### 1.1. Coordinator Selection — Rendezvous Hashing (HRW)

> **Học thuật:** Rendezvous Hashing / Highest Random Weight (HRW) — Thaler & Ravishankar, 1996.  
> Cơ chế phân tán key-value không cần trung tâm, dùng rộng rãi trong CDN và distributed cache.

#### Tại sao chọn Rendezvous Hashing?

Có 3 cách phổ biến để chọn coordinator từ danh sách member:

| Tiêu chí | Random (ngẫu nhiên) | Sort theo tên (alphabetical) | Rendezvous Hashing (HRW) |
|---|---|---|---|
| **Deterministic** | ❌ Mỗi peer chọn khác nhau → cần đồng thuận | ✅ Cùng list → cùng kết quả | ✅ Cùng list → cùng kết quả |
| **Phân bố đều** | ⚠️ Không đảm bảo — phụ thuộc seed | ❌ Luôn thiên vị tên đầu bảng chữ cái (alice luôn là C1) | ✅ SHA-256 phân bố uniform |
| **Minimal disruption** | ❌ Khi member thay đổi, toàn bộ coordinator có thể bị xáo trộn | ⚠️ Thêm "adam" → đẩy tất cả xuống 1 slot | ✅ Chỉ slot bị ảnh hưởng thay đổi |
| **Không cần trung tâm** | ❌ Cần Bootstrap hoặc leader gán | ✅ Tự tính local | ✅ Tự tính local |
| **Per-group diversity** | ⚠️ Random nên có thể trùng | ❌ Cùng group nào alice cũng rank 1 | ✅ Hash kèm groupId → mỗi nhóm khác coordinator |
| **Học thuật** | Không có nền tảng lý thuyết | Quá đơn giản, không giải quyết bài toán phân tán | Được chứng minh trong paper 1996, dùng trong CDN (Akamai) |

**Tại sao không Random?**
- Mỗi peer tự random → chọn ra coordinator khác nhau → **không đồng thuận**. Cần thêm leader election hoặc Bootstrap gán → phức tạp hóa hệ thống và tạo thêm SPOF.
- Khi member join/leave, random lại toàn bộ → coordinator thay đổi không kiểm soát → mất GroupInfo đang giữ.

**Tại sao không Sort theo tên?**
- Peer có tên đứng đầu alphabet (ví dụ "alice") sẽ **luôn là coordinator mọi nhóm** → quá tải 1 node.
- Thêm member tên đứng trước → toàn bộ coordinator set xáo trộn. Ví dụ: thêm "adam" vào nhóm có "alice", "bob", "carol" → adam chiếm rank 1, tất cả bị đẩy xuống.
- Không có tính per-group diversity: nhóm A và nhóm B có cùng 5 người → cùng 3 coordinator → tập trung tải.

**Rendezvous Hashing giải quyết tất cả:**
- `SHA256(groupId + "|" + address)` tạo score **khác nhau theo từng nhóm** → cùng 5 người nhưng mỗi nhóm có coordinator set khác.
- Deterministic: mọi peer tính ra cùng kết quả mà không cần giao tiếp.
- Minimal disruption: thêm/xóa 1 member chỉ ảnh hưởng 1 slot (nếu member đó là coordinator).

#### Nguyên lý:

```
Với mỗi member m trong nhóm có groupId G:
  score(m, G) = long( SHA256( G + "|" + m.address ).substring(0,8) )

Sort members giảm dần theo score → top K=3 = Coordinator Set (C1, C2, C3)
```

**Tính chất quan trọng:**
- **Deterministic:** Mọi peer tự tính được Coordinator Set từ member list — không cần bỏ phiếu, không cần đồng thuận
- **Minimal disruption:** Khi 1 coordinator rời nhóm, chỉ slot đó thay đổi (member rank 4 lên rank 3)
- **No Bootstrap needed:** GroupInfo lưu tại coordinator, không phải Bootstrap

```
Ví dụ: nhóm "devteam" (groupId: "grp-a3f2b1c4"), 5 thành viên:

  Member            HRW Score       Vai trò
  alice@:5001  →   0xF2A3C8B1...   Coordinator C1  ←── rank 1
  dave@:5004   →   0xD891FA22...   Coordinator C2  ←── rank 2
  carol@:5003  →   0xB4C27D09...   Coordinator C3  ←── rank 3
  bob@:5002    →   0x7F110E44...   Member thường   ←── rank 4 (reserve)
  eve@:5005    →   0x3A82C591...   Member thường   ←── rank 5 (reserve)
```

---

### 1.2. Cấu trúc GroupInfo — lưu tại Coordinator, không tại Bootstrap

```
GroupInfo {
  groupId      : "grp-a3f2b1c4"       // UUID 8 ký tự hex, prefix "grp-"
  groupName    : "devteam"
  owner        : "alice@localhost:5001" // quyền admin; độc lập với coordinator
  members      : List<String>           // thứ tự join, owner luôn ở index 0
  version      : long                   // Lamport clock — tăng mỗi khi state thay đổi
  createdAt    : long
}

// coordinators KHÔNG lưu trong GroupInfo — được tính lại từ members + groupId
// Mỗi peer tự tính: List<String> coordinators = HRWHash.topK(members, groupId, K=3)
```

**Địa chỉ trong members[] là gì?** → `host:port` là **LAN IP** của máy (ví dụ `192.168.1.5:5001`). Hệ thống hoạt động trong **cùng LAN hoặc cùng máy** (multi-port). Khác LAN/NAT cần VPN — ngoài scope demo.

---

### 1.2b. Ba tầng kiến trúc — Control / Data / Repair

```
(A) CONTROL PLANE — Coordinator (source of truth)
     │  Chịu trách nhiệm tuyệt đối về membership
     │  Xử lý: GROUP_ADD, GROUP_KICK, GROUP_LEAVE, GROUP_DISBAND
     │  Tăng version mỗi khi thay đổi
     │  Gossip với 2 coordinator còn lại để fault-tolerant
     ▼
(B) DATA PLANE — Local Cache tại mỗi peer (optimization)
     │  Lưu: { members[], coordinators[], localVersion }
     │  Dùng để gửi GROUP_MESSAGE NGAY mà không cần hỏi coordinator
     │  localVersion theo dõi độ tươi của cache
     ▼
(C) LAZY REPAIR — Tự động sửa khi phát hiện sai
     │  Trigger: TCP fail khi gửi | version mismatch | peer restart
     │  Action: GROUP_RESYNC_REQ → coordinator → trả về GroupInfo mới
     │  Sau resync: gửi lại tin hoặc bỏ qua (best-effort)
```

**Tại sao không proactive sync?**  
Thay vì push GROUP_UPDATED đến mọi peer mỗi khi có thay đổi (tốn băng thông, phức tạp), hệ thống chỉ sync khi thực sự cần. Cache cũ vẫn dùng được cho đến khi phát hiện sai lệch — đây là mô hình **eventual consistency**.

| Plane | Khi nào dùng | Ai thực hiện |
|---|---|---|
| Control | Thay đổi membership | Coordinator |
| Data | Gửi message (99% trường hợp) | Mỗi peer, đọc cache local |
| Repair | Cache stale / send fail / restart | Peer tự trigger, hỏi coordinator |

---

### 1.3. Gossip Protocol giữa Coordinators

Coordinators đồng bộ GroupInfo định kỳ và ngay khi có thay đổi:

```
C1 ──[COORD_GOSSIP {groupId, version, GroupInfo}]──► C2   (mỗi 5s)
C2 ──[COORD_GOSSIP {groupId, version, GroupInfo}]──► C3   (mỗi 5s)
C3 ──[COORD_GOSSIP {groupId, version, GroupInfo}]──► C1   (mỗi 5s)
```

**Khi có thay đổi (add/kick/leave):** Coordinator nhận request → update local → tăng `version` → **gossip ngay** đến 2 coordinator còn lại (không đợi timer 5s).

**Conflict resolution:**
```
Nhận COORD_GOSSIP với version V_remote:
  V_remote > V_local  → nhận bản remote (remote thắng)
  V_remote == V_local → tiebreak: hash(GroupInfo_remote) > hash(GroupInfo_local) → remote thắng
  V_remote < V_local  → bỏ qua; gửi lại bản local về remote để đồng bộ ngược
```

**Gossip cũng là heartbeat:** Nếu không nhận được COORD_GOSSIP trong **3 chu kỳ (15s)** → coordinator kia bị coi là dead → trigger re-election.

---

### 1.4. Lookup — Hỏi Coordinator với Fallback

Mỗi peer cache coordinator list từ lần nhận GroupInfo gần nhất:

```java
// Local cache tại mỗi peer — Data Plane
Map<String, GroupCache> groupCache = {
  "grp-a3f2b1c4" → {
    members      : ["bob@:5002", "carol@:5003", "dave@:5004"],  // để gửi message
    coordinators : ["alice@:5001", "dave@:5004", "carol@:5003"], // tính từ HRW
    localVersion : 7,        // version của bản cache này
    lastUpdated  : timestamp // để biết cache có quá cũ không
  }
}
```

**Lookup flow (khi cần GroupInfo mới nhất):**
```
[Normal path — xảy ra gần như 100% thời gian]
1. Thử C1 (alice@:5001)   → online → trả về GroupInfo ngay  ✓
2. C1 timeout (2s)        → thử C2 (dave@:5004)             ✓
3. C2 cũng timeout        → thử C3 (carol@:5003)             ✓

[Emergency fallback — chỉ khi cả 3 coordinator cùng offline]
4. Cả 3 timeout           → hỏi bất kỳ member online nào trong local cache
                            → member đó forward request đến coordinator mà họ biết
                            → (P2P relay nhẹ, không phải Bootstrap)
5. Vẫn không được         → group unreachable; trigger re-election nếu là coordinator
```

> **Tại sao giữ bước 4?** Đây là điểm khác biệt quan trọng so với Bootstrap-Registry: trong mô hình cũ, bước 4 = group chết. Trong DHT-lite, mọi member đều có thể trở thành "lookup relay" tạm thời — đây là tính P2P thực sự. Trên thực tế bước 4–5 cực hiếm xảy ra với K=3.

---

### 1.5. Luồng tạo nhóm

```
[Alice] POST /api/group/create { groupName: "devteam", members: ["bob","carol",...] }
   ↓
PeerNode kiểm tra:
  - Phải chọn ít nhất 2 member khác (tổng >= 3, không giới hạn trên)
  - TẤT CẢ member được chọn phải đang ONLINE (kiểm tra qua Bootstrap peer list)
  - Nếu có member offline: báo lỗi UI "bob hiện offline, hãy bỏ chọn hoặc đợi họ online"
Lấy địa chỉ TCP của các member từ Bootstrap (peer discovery bình thường)
   ↓
1. Alice tạo GroupInfo locally:
     groupId   = UUID() → "grp-a3f2b1c4"
     members   = ["alice@:5001","bob@:5002","carol@:5003","dave@:5004"]
     version   = 1
     Tính HRW → C1=alice, C2=dave, C3=carol
   ↓
2. Alice (là C1) lưu GroupInfo local; khởi động CoordinatorManager cho group này
   ↓
3. Alice → TCP → C2 (dave): COORD_INIT { GroupInfo }
   Alice → TCP → C3 (carol): COORD_INIT { GroupInfo }
   Dave, Carol xác nhận COORD_INIT_ACK → bắt đầu Gossip timer
   ↓
4. Alice → TCP → mỗi member (bob, carol, dave):
     GROUP_JOINED { groupId, groupName, members[], coordinators[] }
   ↓
5. Mỗi member nhận GROUP_JOINED → lưu vào groupCache local
   ↓
6. Alice UI: nhóm tạo thành công
```

> **Bootstrap không tham gia bất kỳ bước nào.** Chỉ dùng để resolve địa chỉ TCP của member ở bước đầu.

---

### 1.6. Luồng gửi tin nhắn nhóm — Data Plane + Lazy Repair

**Happy path (Data Plane — cache hit, ~99% trường hợp):**
```
[Alice] POST /api/group/msg { groupId, content: "Hello!" }
   ↓
PeerNode đọc local cache: members = [bob@:5002, carol@:5003, dave@:5004], localVersion=7
   ↓ (thread pool, gửi SONG SONG — không qua coordinator)
Alice → TCP :5002 → Bob:   GROUP_MESSAGE { groupId, sender, content, lamportClock, cacheVersion:7 }
Alice → TCP :5003 → Carol: GROUP_MESSAGE { ... cacheVersion:7 }   (đồng thời)
Alice → TCP :5004 → Dave:  GROUP_MESSAGE { ... cacheVersion:7 }   (đồng thời)
   ↓
Mỗi peer nhận → kiểm tra cacheVersion → lưu SQLite → push WebSocket → ACK
```

**Lazy Repair — Trigger 1: TCP fail khi gửi:**
```
Alice gửi đến Dave → Connection refused (Dave đã rời nhóm nhưng cache chưa cập nhật)
   ↓
Alice: "fail gửi đến dave@:5004" → trigger GROUP_RESYNC_REQ
   ↓
Alice → C1: GROUP_RESYNC_REQ { groupId, myVersion: 7 }
C1 → Alice: GROUP_RESYNC_RESP { GroupInfo version:9, members:[...] }  // dave đã bị remove
   ↓
Alice cập nhật cache localVersion=9, gửi lại tin đến members mới (không có dave)
```

**Lazy Repair — Trigger 2: Version mismatch khi nhận:**
```
Bob nhận GROUP_MESSAGE { ..., cacheVersion: 5 } — trong khi Bob có localVersion: 9
   ↓ (Alice đang dùng cache cũ)
Bob: "alice có version cũ hơn tôi" → gửi CACHE_STALE { groupId, currentVersion: 9 } về Alice
   ↓
Alice nhận CACHE_STALE → trigger GROUP_RESYNC_REQ đến coordinator
→ cập nhật cache → lần sau gửi đúng
```

**Lazy Repair — Trigger 3: Peer restart / online lại:**
```
Alice restart → đọc cache từ SQLite (localVersion=7)
   ↓
Alice → C1: GROUP_RESYNC_REQ { groupId, myVersion: 7 }
C1 → Alice: GROUP_RESYNC_RESP { GroupInfo version:9 }  // hoặc "bạn đã up-to-date"
   ↓
Alice cập nhật cache, sẵn sàng gửi/nhận
```

**Lazy Repair — Trigger 4: Heartbeat khôi phục sau mất kết nối:**

**Lý do:** Trigger 3 chỉ cover restart (process kill + start lại). Trường hợp **mất mạng tạm thời** (Wi-Fi đứt 30s rồi nối lại) — peer không restart nhưng cache có thể stale vì bỏ lỡ GROUP_UPDATED trong khoảng mất kết nối. Trigger này bù cho gap đó.

```
Alice mất heartbeat đến Bootstrap > 10s → heartbeat khôi phục lại:
   ↓
LazyRepairManager hook vào event "heartbeat resumed":
   → Gửi GROUP_RESYNC_REQ cho TẤT CẢ group trong groupCache
   → Không phân biệt restart hay mất mạng tạm thời
   ↓
Nếu có group nào version đã tăng trong khoảng mất kết nối → cập nhật
Nếu đã up-to-date → coordinator trả "upToDate: true" → không làm gì thêm
```

> **Đơn giản:** Hook vào sự kiện "heartbeat resumed" đã có sẵn trong hệ thống, không cần component mới. Chi phí chỉ là N lần gọi RESYNC (N = số group peer tham gia), thường rất nhỏ.

> **Coordinator KHÔNG relay message.** Tin nhắn vẫn P2P trực tiếp.  
> Coordinator chỉ phục vụ RESYNC khi được hỏi — không proactive push mọi lúc.

---

### 1.7. Xử lý đồng thời — Lamport Timestamp + Buffer Window

**Vấn đề:** Alice và Bob gửi tin gần như đồng thời → Carol nhận thứ tự ngẫu nhiên.

**Giải pháp — Lamport Logical Clock:**
- Mỗi peer giữ `AtomicLong logicalClock`
- Khi gửi: `clock++`, đính kèm `lamportClock` vào message
- Khi nhận: `clock = max(localClock, receivedClock) + 1`
- UI sort theo `(lamportClock, senderName)` → tất cả peer thấy **cùng một thứ tự**

`ConcurrentLinkedQueue` per group tại PeerServer để buffer và sort trước khi push WebSocket.

#### Lamport Buffer Window — Giải quyết message đến không theo thứ tự

**Tình huống cụ thể:** Carol nhận tin của Alice (clock=10) nhưng chưa nhận tin của Bob (clock=9). Nếu render ngay, thứ tự sẽ sai. Nếu chờ mãi, UI bị đơ.

**Lý do cần buffer:** Lamport clock chỉ đảm bảo **causal ordering** — nếu A gửi trước B thì clock(A) < clock(B). Nhưng network latency khiến tin clock=10 đến trước clock=9. Buffer 200ms cho phép "chờ" các tin đang trên đường truyền.

**Cơ chế:**
```
Mỗi peer giữ một MessageBuffer per group:

  Nhận message M với lamportClock = L:
    → Đưa vào buffer, KHÔNG render ngay
    → Set timer 200ms cho L
    
  Sau 200ms (hoặc khi nhận đủ):
    → Sort buffer theo (lamportClock ASC, senderName ASC)
    → Render tất cả message có clock ≤ L ra UI
    → Xóa khỏi buffer
    
  Nếu sau 200ms vẫn có "gap" (có clock=10 nhưng thiếu clock=9):
    → Render luôn theo thứ tự hiện có
    → Đánh dấu gap bằng dấu gạch nhỏ trên UI (optional)
```

> **200ms** là đủ để bù network jitter trong LAN (thường < 1ms) mà người dùng không cảm nhận được độ trễ. Không cần cơ chế phức tạp hơn.

---

### 1.8. Luồng thêm thành viên

```
[Alice hoặc Bob] POST /api/group/add { groupId, username: "eve" }
   ↓
Peer → C1 (hoặc C2 nếu C1 offline): GROUP_ADD { groupId, requester, newMember: "eve@:5005" }
   ↓
C1 xử lý:
  1. Thêm eve vào members[]
  2. version++
  3. Kiểm tra idempotency (requestId) — nếu đã xử lý request trùng → trả kết quả cũ, không tăng version lần 2
  4. Tính lại HRW với members mới:
       → Nếu eve score > C3 hiện tại: eve trở thành C3, C3 cũ xuống member thường
         - Gửi COORD_INIT { GroupInfo } đến eve (nếu online)
         - Gửi COORD_RESIGN { groupId } đến C3 cũ
       → Nếu không thay đổi coordinator: tiếp tục bình thường
  5. Gửi GROUP_JOINED đến Eve TRƯỚC (retry 3 lần nếu fail):
       → Tại sao phải gửi trước? Nếu GROUP_UPDATED đến Eve trước GROUP_JOINED,
         Eve chưa có group trong cache → bỏ qua GROUP_UPDATED → mất đồng bộ.
       → Nếu cả 3 lần retry đều fail? Gossip sẽ tự lan truyền GroupInfo mới tới Eve
         thông qua các Coordinator khác (eventual consistency).
  6. Gossip GroupInfo mới đến C2, C3 ngay lập tức
  7. Gửi GROUP_UPDATED { groupId, members[], coordinators[], version } đến TẤT CẢ member đang online
       (trừ Eve — Eve đã nhận GROUP_JOINED ở bước 5)
   ↓
Mỗi member cập nhật local cache; Eve (khi online) bắt đầu nhận tin nhóm
```

> **Thêm member offline có được không?** Có — coordinator vẫn cập nhật GroupInfo và gossip bình thường. Chỉ việc notify Eve bị delay đến khi Eve online. Trong thời gian Eve offline, các member khác gửi GROUP_MESSAGE sẽ bỏ qua Eve (best-effort).

#### C1 Timeout — Hard Cancel Signal

**Lý do:** Khi peer gửi GROUP_ADD đến C1 nhưng C1 không ACK trong thời gian heartbeat window (5s), không thể biết chắc C1 đã xử lý hay chưa. Nếu chỉ retry sang C2 mà không cancel → C1 xử lý muộn → member bị add 2 lần hoặc state inconsistent. Cần cơ chế **hard cancel + idempotency**.

```
Peer gửi GROUP_ADD → C1:
  - Tạo requestId = UUID (idempotency key)
  - Gửi TCP đến C1, set deadline = now + 5s
  - Nếu nhận ACK từ C1 trước 5s → done ✓
  - Sau 5s: CANCEL tín hiệu này (đánh dấu requestId = ABANDONED)
      → Nếu C1 trả lời muộn sau đó → kiểm tra requestId:
           ABANDONED → bỏ qua hoàn toàn, không xử lý
      → Gửi lại đến C2 với cùng requestId
  - C2 nhận: kiểm tra requestId → chưa xử lý → thực thi
                                 → đã xử lý (C1 thực ra đã làm) → trả về kết quả có sẵn
```

> **Idempotency key (requestId)** giải quyết luôn cả trường hợp C1 thực ra đã xử lý xong nhưng ACK bị mất — C2 nhận request trùng, nhận ra đã có kết quả (qua Gossip), trả về luôn thay vì làm lại. Không cần distributed lock, không tốn tài nguyên.

---

### 1.9. Luồng Kick thành viên (chỉ owner)

```
[Alice - owner] POST /api/group/kick { groupId, target: "carol" }
   ↓
Peer → C1: GROUP_KICK { groupId, owner: "alice@:5001", target: "carol@:5003" }
   ↓
C1 kiểm tra: requester == owner (members[0])  →  nếu không: từ chối
   ↓
C1 xử lý:
  1. Xóa carol khỏi members[]
  2. version++
  3. Tính lại HRW:
       Carol là C3? → member rank 4 (bob@:5002) lên làm C3 mới
         - Gửi COORD_INIT { GroupInfo } đến Bob
         - Carol sẽ nhận GROUP_KICKED → tự rút, không cần COORD_RESIGN
       Carol là member thường? → Coordinator Set không đổi
  4. Gossip GroupInfo mới đến coordinators còn lại ngay lập tức
  5. Gửi GROUP_KICKED { groupId } đến Carol
  6. Gửi GROUP_UPDATED { groupId, members[], coordinators[], version,
                         changeType:"KICK", affected:"carol" } đến tất cả member còn lại
   ↓
Carol nhận GROUP_KICKED → xóa group khỏi local cache, dừng nhận tin
Các member còn lại cập nhật local cache; Bob (nếu lên C3) khởi động CoordinatorManager
```

#### Race Condition — Carol nhận tin sau khi bị Kick

**Lý do:** Sau khi nhận GROUP_KICKED, Carol không dừng lắng nghe ngay lập tức. Tin nhắn nhóm đang trên đường truyền (đã gửi trước lệnh kick) vẫn có thể đến. Nếu Carol xóa group ngay lập tức → tin nhắn bị mất, UX kém.

```
Carol nhận GROUP_KICKED:
  → Đánh dấu group state = "LEAVING" (không phải xóa ngay)
  → Tiếp tục nhận GROUP_MESSAGE trong window 3s
  → Sau 3s: xóa group khỏi cache, dừng lắng nghe hoàn toàn
  
Trong window 3s:
  → Carol nhận message → lưu SQLite bình thường (người dùng vẫn đọc được)
  → Carol KHÔNG gửi ACK, KHÔNG reply
  → UI hiển thị banner: "Bạn đã bị xóa khỏi nhóm này"
```

> Người dùng thấy tin nhắn cuối cùng trước khi bị kick, UX tốt hơn so với bị cắt đột ngột. Chi phí chỉ là 1 trường `state` và 1 timer 3s.

---

### 1.10. Luồng rời nhóm (Leave)

**Case A — Member thường rời nhóm:**

```
[Bob] POST /api/group/leave { groupId }
   ↓
Bob → C1: GROUP_LEAVE { groupId, username: "bob@:5002" }
   ↓
C1 xử lý:
  1. Xóa bob khỏi members[]
  2. version++
  3. Tính lại HRW → nếu bob là coordinator: xem Case B
  4. Gossip → GROUP_UPDATED đến tất cả member còn lại
   ↓
Bob xóa group khỏi local cache
```

**Case B — Coordinator tự nguyện rời nhóm:**

```
[Dave - C2] POST /api/group/leave { groupId }
   ↓
Dave → C1: GROUP_LEAVE { groupId, username: "dave@:5004" }
   ↓
C1 xử lý:
  1. Xóa dave khỏi members[]
  2. version++
  3. Tính lại HRW (không có dave):
       Member rank 4 (bob@:5002) → score so sánh → lên làm C2 mới
       Gửi COORD_INIT { GroupInfo } đến Bob → Bob xác nhận COORD_INIT_ACK
  4. Gossip GroupInfo mới đến C3 và Bob (C2 mới)
  5. Gửi GROUP_UPDATED đến tất cả member
   ↓
Dave xóa group khỏi local cache, dừng Gossip timer
Bob nhận COORD_INIT → khởi động CoordinatorManager
```

**Case C — Owner rời nhóm:**

```
[Alice - owner] POST /api/group/leave { groupId, newOwner: "bob" }
   ↓  (owner phải chỉ định người thay; nếu không chỉ định: member rank cao nhất non-coordinator)
C1 xử lý:
  1. Swap owner: members[0] = "bob@:5002"; alice dời xuống cuối hoặc xóa
  2. version++
  3. Tính lại HRW với members mới (không có alice)
       → Bob có thể thay alice làm C1 nếu score rank 1
  4. Gossip → GROUP_UPDATED toàn bộ
   ↓
Alice nhận GROUP_UPDATED → rời nhóm (xóa local cache)
Bob trở thành owner mới; nếu Bob là C1 mới: khởi động CoordinatorManager
```

---

### 1.11. Coordinator Failure — Phát hiện & Tự động bầu lại

**Phát hiện (Gossip-based heartbeat):**

```
Tình huống: C2 (dave) crash đột ngột

C1 (alice) và C3 (carol) không nhận COORD_GOSSIP từ dave trong 3 chu kỳ (15s)
  → Cả hai độc lập đánh dấu dave = "suspected dead"
  → Cả hai độc lập tính lại HRW từ members[] còn lại:
       Xếp hạng: alice(rank1), carol(rank3), bob(rank4), eve(rank5)  [bỏ dave]
       → Cần thay C2: thử promote rank 4 (bob) trước
          bob ONLINE?  → Có → bob lên làm C2
          bob OFFLINE? → Thử rank 5 (eve)
          eve ONLINE?  → Có → eve lên làm C2
          ...(tiếp tục đến khi tìm được người online)
  → Deterministic: C1 và C3 tính ra CÙNG THỨ TỰ ƯU TIÊN, không cần đồng thuận
   ↓
C1 gửi COORD_INIT { GroupInfo (version++) } đến Bob (hoặc người được chọn)
Bob xác nhận COORD_INIT_ACK → bắt đầu tham gia Gossip
C1 Gossip GroupInfo mới đến C3 và Bob
C1 gửi GROUP_UPDATED { changeType:"COORD_CHANGE" } đến tất cả member
```

> **Lưu ý:** Chỉ promote peer **đang online** (reachable qua TCP). Nếu cả rank 4, 5 đều offline → tạm thời chạy với 2 coordinator, đợi member online rồi promote sau.

> **Chống duplicate COORD_INIT (Race condition):**
> Khi C1 và C3 cùng phát hiện C2 chết, cả hai đều gửi `COORD_INIT` đến Bob (rank 4). Nếu không có dedup, Bob nhận 2 lần `COORD_INIT` cho cùng group → có thể lệch state hoặc gossip chồng chéo.
>
> | Giải pháp | Không có dedup | Có version-based dedup (hiện tại) |
> |---|---|---|
> | Bob nhận COORD_INIT lần 1 (v=9) | Chấp nhận, khởi động Coordinator | Chấp nhận, lưu v=9 |
> | Bob nhận COORD_INIT lần 2 (v=9) | Chấp nhận LẦN NỮA, state bị reset | So sánh v=9 >= v=9 → **bỏ qua**, ACK bình thường |
> | Kết quả | Coordinator khởi tạo 2 lần, gossip chồng chéo | Coordinator chỉ khởi tạo 1 lần, an toàn |
>
> **Cách hoạt động:** Peer nhận `COORD_INIT` kiểm tra `existing.getVersion() >= incoming.getVersion()`. Nếu đã manage group ở version bằng hoặc cao hơn → bỏ qua, chỉ gửi ACK. Không cần epoch phức tạp — version number từ Lamport clock đủ để phân biệt.

**Dave online lại sau:**
```
Dave gửi COORD_GOSSIP → nhận phản hồi với version cao hơn của mình
Dave nhận ra mình đã bị thay thế → fetch GroupInfo mới từ C1
Dave trở thành member thường, dừng CoordinatorManager
```

**Trường hợp cực đoan — chỉ còn 1 coordinator online:**
```
C1 (alice) là coordinator duy nhất còn sống:
  Alice tính HRW từ members còn active → chọn top-2 có thể reach được
  Alice gửi COORD_INIT đến 2 member rank cao nhất còn online
  Group vẫn hoạt động với Coordinator Set mới
```

**Tóm tắt cơ chế bầu lại:**

| Bước | Hành động | Ai thực hiện |
|---|---|---|
| 1. Detect | Gossip timeout 3 lần (15s) | Coordinator còn lại |
| 2. Compute | Tính HRW mới (loại dead node) | Độc lập, deterministic |
| 3. Promote | Gửi COORD_INIT đến member rank tiếp theo | Coordinator còn lại có rank thấp nhất |
| 4. Confirm | COORD_INIT_ACK, bắt đầu Gossip | Member mới được promote |
| 5. Propagate | Gossip + GROUP_UPDATED | Coordinator |

---

### 1.12. File Transfer trong Group — P2P Swarming

**Lý do nâng cấp:** Mô hình Pull cũ — mọi member đều kết nối về sender duy nhất — tạo bottleneck trên upload bandwidth của sender. Tham khảo BitTorrent nhưng đơn giản hóa: **người đã tải xong một phần cũng serve lại phần đó cho người khác**, giảm tải cho sender gốc.

**Nguyên lý:** Sender chia file thành N chunk (64KB/chunk). Các peer tải chunk từ sender hoặc từ nhau. Coordinator không tham gia — thuần P2P data plane.

```
Giai đoạn 1 — Sender Alice phân phối:
  Alice chia file thành N chunk (64KB mỗi chunk)
  Alice → mỗi member: FILE_GROUP_OFFER { groupId, filename, totalChunks: N, 
                                           sha256, filePort:6001, transferId }

Giai đoạn 2 — Swarm hình thành:
  Bob  tải chunk 0–15  từ Alice  (online trước)
  Carol tải chunk 16–31 từ Alice (song song)
  Dave  tải chunk 32–47 từ Alice
  
  Bob hoàn thành chunk 0–15 → broadcast FILE_HAVE { transferId, chunkIndexes: [0..15] }
  → Eve (join muộn hơn) thấy Bob có chunk 0–15 → tải từ Bob thay vì Alice
  → Alice chỉ phải serve phần còn lại cho người chưa có

Giai đoạn 3 — Alice rời đi sớm (optional):
  Khi Alice đã gửi hết N chunk cho ít nhất 1 peer
  → Alice có thể đóng FileTransferServer
  → Các peer còn lại trao đổi chunk với nhau
```

**FILE_HAVE_REQ — Giải quyết peer join muộn:**

**Lý do cần thêm:** `FILE_HAVE` broadcast chỉ thông báo "tôi có chunk X" tại thời điểm broadcast. Nếu Eve join muộn và không nhận được FILE_HAVE của Bob (vì broadcast đã qua), Eve không biết Bob có chunk nào.

```
Eve muốn tải file nhưng không biết ai có chunk nào:
  → Eve gửi FILE_HAVE_REQ { transferId } broadcast đến group
  → Các peer reply: FILE_HAVE_RESP { transferId, chunkIndexes: [...], filePort }
  → Eve biết ai có gì → chọn peer gần nhất / có nhiều chunk nhất để tải
```

> Một message nhỏ, giải quyết trọn vẹn vấn đề discovery cho late-joiner.

**Tái sử dụng trong hệ thống hiện có:**
- `FileTransferServer` đã có → thêm `/chunk/{index}` endpoint
- `FILE_HAVE` là message type mới, rất nhẹ (chỉ list chunk index)
- Coordinator không tham gia — thuần P2P data plane, nhất quán với thiết kế hiện tại
- SQLite bảng `file_chunks` lưu chunk nào đã có để biết mình có thể serve gì

> **Kết quả:** Alice gửi file cho 10 người nhưng chỉ cần upload ~10–20% dữ liệu, phần còn lại tự lan tỏa trong swarm.

---

### 1.13. MessageType mới (DHT-lite)

**Control Plane — Coordinator protocol:**

| Type | Hướng | Nội dung |
|---|---|---|
| `COORD_INIT` | C1 → C2/C3 | `{GroupInfo, version}` — mời làm coordinator |
| `COORD_INIT_ACK` | C2/C3 → C1 | `{groupId, ok}` |
| `COORD_GOSSIP` | Ci → Cj | `{groupId, version, GroupInfo}` — sync + heartbeat |
| `COORD_GOSSIP_ACK` | Cj → Ci | `{groupId, version}` — confirm hoặc gửi bản mới hơn |
| `COORD_RESIGN` | Ci → Cj | `{groupId}` — thông báo không còn là coordinator |
| `GROUP_ADD` | Peer → Coordinator | `{groupId, requester, newMember}` |
| `GROUP_LEAVE` | Peer → Coordinator | `{groupId, username, newOwner?}` |
| `GROUP_KICK` | Owner → Coordinator | `{groupId, owner, target}` |
| `GROUP_DISBAND` | Owner → Coordinator | `{groupId, owner}` |
| `GROUP_JOINED` | Coordinator → NewMember | `{groupId, groupName, members[], coordinators[], version}` |
| `GROUP_UPDATED` | Coordinator → AllMembers | `{groupId, members[], coordinators[], version, changeType, affected}` |
| `GROUP_KICKED` | Coordinator → KickedPeer | `{groupId}` |
| `GROUP_DISBANDED` | Coordinator → AllMembers | `{groupId}` |

**Data Plane — P2P messaging & file swarming:**

| Type | Hướng | Nội dung |
|---|---|---|
| `GROUP_MESSAGE` | Peer → Peer (multicast) | `{groupId, sender, content, lamportClock, timestamp, cacheVersion}` |
| `FILE_GROUP_OFFER` | Peer → Each Member | `{groupId, filename, totalChunks, sha256, filePort, transferId}` |
| `FILE_HAVE` | Peer → Group (broadcast) | `{transferId, chunkIndexes[]}` — thông báo chunk đã tải xong |
| `FILE_HAVE_REQ` | Peer → Group (broadcast) | `{transferId}` — hỏi ai đang có chunk nào |
| `FILE_HAVE_RESP` | Peer → Peer | `{transferId, chunkIndexes[], filePort}` — reply cho FILE_HAVE_REQ |
| `FILE_RESUME` | Receiver → Sender | `{transferId, resumeChunkIndex}` — resume từ chunk cụ thể |

**Repair Plane — Lazy sync:**

| Type | Hướng | Nội dung |
|---|---|---|
| `GROUP_RESYNC_REQ` | Peer → Coordinator | `{groupId, myVersion}` — yêu cầu đồng bộ khi cache stale |
| `GROUP_RESYNC_RESP` | Coordinator → Peer | `{groupId, GroupInfo}` hoặc `{upToDate: true}` |
| `CACHE_STALE` | Peer → Peer | `{groupId, currentVersion}` — thông báo sender đang dùng cache cũ |
| `GROUP_GET` | Peer → Coordinator | `{username}` — lấy tất cả group khi peer online lại |
| `GROUP_ADD_REJECTED` | Coordinator → Peer | `{groupId, reason}` — từ chối thêm member (RESTRICTED mode) |

---

### 1.14. Phân quyền thành viên — Phân tầng OPEN / RESTRICTED

**Lý do cần phân tầng:** Bảng phân quyền hiện tại cho phép Member thường thêm người, nhưng luồng lại phải đi qua Coordinator. Thay vì bỏ quyền này, phân tầng rõ hơn bằng 2 chế độ group — **OPEN** và **RESTRICTED** — chọn khi tạo nhóm.

| Hành động | Owner (members[0]) | Coordinator (C1–C3) | Member thường (OPEN) | Member thường (RESTRICTED) |
|---|---|---|---|---|
| Gửi tin nhắn | ✅ | ✅ | ✅ | ✅ |
| Gửi file vào nhóm | ✅ | ✅ | ✅ | ✅ |
| Thêm thành viên | ✅ | ✅ | ✅ | ❌ |
| Rời nhóm | ✅ (chỉ định owner mới) | ✅ | ✅ | ✅ |
| Kick thành viên | ✅ | ❌ | ❌ | ❌ |
| Chuyển quyền owner | ✅ | ❌ | ❌ | ❌ |
| Giải tán nhóm | ✅ | ❌ | ❌ | ❌ |
| Xử lý GROUP_ADD/KICK/LEAVE | — | ✅ | ❌ | ❌ |
| Gossip GroupInfo | — | ✅ | ❌ | ❌ |

**Cơ chế enforce:**

```
OPEN group (mặc định):
  Mọi member đều có thể gửi GROUP_ADD đến Coordinator
  → Coordinator kiểm tra: requester có trong members[]? → có → xử lý bình thường

RESTRICTED group (owner bật khi tạo hoặc chuyển đổi sau):
  Chỉ owner và coordinator mới thêm được
  → Coordinator kiểm tra: requester là owner hoặc coordinator?
     → Không phải → từ chối, trả lỗi về peer: GROUP_ADD_REJECTED { reason: "RESTRICTED" }
```

> **Coordinator là nơi enforce quyền, không phải nơi gán quyền.** Mọi member đều có thể gửi GROUP_ADD request — nhưng Coordinator quyết định chấp nhận hay không dựa trên `groupMode` và `role` của người gửi. Nhất quán với luồng 1.8, không cần thay đổi giao thức message.

**GroupInfo bổ sung:** Thêm trường `groupMode: "OPEN" | "RESTRICTED"` vào GroupInfo (mục 1.2).

---

### 1.15. Thay đổi theo component

**Bootstrap Server — giảm tải:**
- **Xóa** `GroupRegistry.java` — Bootstrap không còn lưu GroupInfo
- **Giữ nguyên** peer discovery (resolve địa chỉ TCP của peer theo username)
- `BootstrapDashboardServer.java` — `GET /api/groups` có thể query coordinator peers (optional)

**Peer Node — thêm mới:**
- `HRWHash.java` — tính Rendezvous Hash: `topK(members, groupId, k)`
- `CoordinatorManager.java` — **Control Plane**: giữ GroupInfo, Gossip timer, heartbeat, re-election
- `GroupCache.java` — **Data Plane**: `{ members[], coordinators[], localVersion, lastUpdated }` — persist SQLite
- `LazyRepairManager.java` — **Repair Plane**: xử lý TCP fail, nhận CACHE_STALE, gửi GROUP_RESYNC_REQ
- `PeerManager.groupCache` → Map local cache mời
- `WebServer.java` — `/api/group/*` forward đến C1 (với fallback C1→C2→C3)
- `PeerServer.java` — handler cho `COORD_*`, `GROUP_*`, `CACHE_STALE`, `GROUP_RESYNC_*`
- `PeerClient.java` — `sendGroupMessage()` gửi kèm `cacheVersion`; nếu fail → trigger repair; `sendToCoordinator()` fallback

**Peer Web (React) — giữ theme, thêm nhỏ:**
- **Badge phân cấp trong member list:**
  - `👑` màu vàng `#FFD700` → Owner (chỉ 1 người)
  - Dot `●` màu accent xanh `#4fc3f7` → Coordinator (C1–C3, không phải owner)
  - Không badge → Member thường (màu text mặc định)
- Member list cập nhật realtime qua WebSocket khi nhận `GROUP_UPDATED`
- Nút "Rời nhóm", "Chuyển quyền" trong group header
- Chỉ owner thấy nút "Kick"
- Khi tạo nhóm: bắt buộc chọn đúng 2 member (UI disable nút Create nếu chưa đủ); chỉ hiện member đang online trong dropdown

---

## 2. File Transfer (Truyền tải File 1-1 & Nhóm)

Hệ thống P2PChat đã triển khai hoàn thiện cơ chế truyền tải File trực tiếp giữa các Peer (Peer-to-Peer) cho cả hội thoại cá nhân (1-1) và hội thoại nhóm (Group). Cơ chế hoạt động dựa trên mô hình **Pull Model** kết hợp với **Chunk-level Checkpointing** để hỗ trợ tạm dừng/khôi phục (resume) khi gặp sự cố đường truyền.

---

### 2.1. Giới hạn & Cấu hình kích thước file
* **Mặc định:** Giới hạn tối đa là **100 MB** (`Constants.MAX_FILE_SIZE = 104857600L`).
* **Đơn vị phân mảnh (Chunk size):** File được chia thành các mảnh cố định kích thước **64 KB** (`Constants.FILE_CHUNK_SIZE = 65536`).
* **Quản lý dữ liệu:** Metadata được lưu vào cơ sở dữ liệu SQLite tại các bảng `file_transfers` và `file_chunks`, dữ liệu nhị phân của file tải xuống được lưu trong thư mục `data/downloads/`.

---

### 2.2. Giao thức nhị phân Chunk-level (ChunkTransferProtocol)
Để truyền dữ liệu file một cách tối ưu và tránh overhead của JSON/Text protocol, luồng dữ liệu file sử dụng socket TCP nhị phân thuần:

1. **Request Frame (Client -> Server):**
   * `[4 bytes integer]` : Độ dài của chuỗi `transferId` (mã UTF-8).
   * `[N bytes bytes]`   : Giá trị chuỗi `transferId`.
   * `[4 bytes integer]` : Chỉ số chunk cần tải (`chunkIndex`, bắt đầu từ `0`).

2. **Response Frame (Server -> Client):**
   * `[4 bytes integer]` : Trạng thái phản hồi (`0 = OK`, `1 = NOT_FOUND`, `2 = ERROR`).
   * `[4 bytes integer]` : Độ dài dữ liệu chunk nhị phân (chỉ có khi trạng thái là `0 = OK`).
   * `[M bytes bytes]`   : Dữ liệu nhị phân thực tế của chunk (thường là 64KB, mảnh cuối có thể nhỏ hơn).

---

### 2.3. Quy trình Truyền File Cá nhân 1-1 (Direct File Transfer)

```
Sender (Alice)                                    Receiver (Bob)
  |                                                     |
  |-- (1) API: /api/file/offer ------------------------>|
  |   (Khởi động FileTransferServer tại filePort)       |
  |                                                     |
  |-- (2) TCP: FILE_OFFER ----------------------------->|
  |   {transferId, filename, fileSize, sha256,          |
  |    filePort = peerPort + 1000, totalChunks}         |
  |                                                     |
  |                               (Lưu DB file_transfers|
  |                                WebSocket báo UI)    |
  |                                                     |
  |                               (3) API: /api/file/accept/
  |                               Tìm chunk thiếu       |
  |                               đầu tiên từ DB        |
  |                                                     |
  |<-- (4) TCP: FILE_ACCEPT {transferId, resumeIdx} ----|
  |                                                     |
  |                               (Start download task  |
  |                                song song các chunk) |
  |                                                     |
  |<-- (5) TCP (filePort): Chunk Request [chunkIndex] --|
  |-- (6) TCP (filePort): Chunk Response --------------->|
  |   [STATUS_OK, size, binary_data]                    |
  |                                                     |
  |                               (Xác thực SHA-256 chunk
  |                                Ghi file ở offset đúng
  |                                Lưu DB file_chunks)   |
  |                                                     |
  |                               (Đạt 100% -> check SHA)
  |                                                     |
  |<-- (7) TCP: FILE_DONE {transferId} -----------------|
```

#### Chi tiết các bước thực hiện:
1. **Khởi tạo Offer:** Người gửi (Alice) tải file qua API REST. `FileTransferManager` sinh `transferId` (UUID) và tính toán số lượng chunk. Đồng thời, `FileTransferServer` (chạy song song tại port `peerPort + 1000`) đăng ký phục vụ file này. Alice gửi bản tin điều khiển `FILE_OFFER` chứa metadata đến Bob qua cổng TCP chính.
2. **Xác nhận Acceptance:** Khi Bob bấm "Chấp nhận" trên giao diện React Web UI, backend của Bob sẽ gọi API chấp nhận. Hệ thống kiểm tra trong SQLite xem file này trước đó đã được tải một phần nào chưa thông qua `FileTransferRepository.firstMissingChunk`. Vị trí chunk bị thiếu đầu tiên (`resumeChunkIndex`) sẽ được gửi ngược lại cho Alice qua bản tin `FILE_ACCEPT`.
3. **Truyền tải và Ghi dữ liệu:** Client của Bob bắt đầu một tiến trình kết nối trực tiếp đến cổng `filePort` của Alice. Bob tuần tự kéo các chunk thông qua `ChunkTransferProtocol`.
   * Mỗi chunk nhận về được hash SHA-256 để so sánh tính toàn vẹn (tính năng bảo mật).
   * Chunk hợp lệ được ghi trực tiếp vào đúng file trên ổ đĩa tại vị trí offset: `chunkIndex * 64KB`.
   * Trạng thái chunk được ghi nhận vào bảng `file_chunks` và cập nhật tiến trình vào bảng `file_transfers`.
4. **Kết thúc và Kiểm tra:** Khi tải đủ 100% các mảnh, Bob tính toán lại toàn bộ mã SHA-256 của file tải về và đối chiếu với mã SHA-256 trong `FILE_OFFER` gốc. Nếu khớp, trạng thái chuyển thành `DONE` và Bob gửi `FILE_DONE` đến Alice để kết thúc phiên truyền tải.

---

### 2.4. Quy trình Truyền File Nhóm (Group File Transfer)

Hiện tại, cơ chế truyền file nhóm được thiết kế tối giản hóa và vận hành trực tiếp trên luồng P2P Data Plane mà không cần sự can thiệp của Coordinator (Coordinator chỉ quản lý Membership/Control Plane).

```
Group Sender (Alice)          Member (Bob)           Member (Carol)
        |                          |                       |
        |-- (1) API: offer file ---|                       |
        |   (groupId chỉ định)     |                       |
        |                          |                       |
        |-- (2) TCP: FILE_OFFER -->|                       |
        |   {..., groupId}         |                       |
        |--------------------------|---------------------->|
        |                          |   (FILE_OFFER)        |
        |                          |                       |
        |                          |-- (3) Click accept -->|
        |                          |   Tải từ Alice        |
        |<-- (4) TCP (filePort): Chunk Requests -----------|
        |-- (5) TCP (filePort): Chunk Responses ---------->|
        |                                                  |
        |                                                  |-- (3) Click accept
        |                                                  |   Tải từ Alice
        |<-- (4) TCP (filePort): Chunk Requests -----------|
        |-- (5) TCP (filePort): Chunk Responses ---------->|
```

#### Cơ chế vận hành thực tế:
1. **Phân phối Offer (Multicast):** Thay vì gửi cho 1 người, khi Alice chia sẻ file vào nhóm, `FileTransferManager` sẽ đọc `GroupCache` local để lấy danh sách toàn bộ thành viên đang online trong nhóm. Alice thực hiện gửi bản tin điều khiển `FILE_OFFER` (chứa thêm trường `groupId`) tới từng thành viên đó thông qua kết nối TCP.
2. **Hiển thị giao diện:** Mỗi thành viên nhận được `FILE_OFFER` sẽ tự động ghi nhận vào SQLite local và hiển thị bong bóng thông báo file trong khung chat nhóm của mình.
3. **Tải file song song (Direct Multi-Pull):** Mỗi thành viên khi bấm nhận file sẽ chủ động khởi tạo tiến trình `FileTransferClient` của riêng họ, kết nối trực tiếp đến cổng `filePort` của Alice và kéo dữ liệu.
4. **So sánh với đề xuất Swarming (BitTorrent-like):**
   * *Hiện tại:* Các thành viên trong nhóm đều kéo trực tiếp các chunk từ một nguồn duy nhất là Alice (Sender).
   * *Đề xuất Swarming (Tương lai):* Các thành viên đã hoàn thành tải một số chunk có thể broadcast trạng thái `FILE_HAVE` cho nhóm, từ đó các thành viên khác có thể chia nhau tải chéo các chunk từ nhau (Ví dụ: Carol tải chunk 0-50 từ Alice, chunk 51-100 từ Bob đã tải xong trước đó), giúp tối ưu hóa băng thông upload của Alice. Các message types `FILE_HAVE`, `FILE_HAVE_REQ`, `FILE_HAVE_RESP` đã được thiết kế sẵn trong enum hệ thống để chuẩn bị cho nâng cấp này.

---

### 2.5. Cơ chế Khôi phục tải lỗi (File Resume & Checkpointing)
* **Checkpoint tại biên Chunk (Chunk Boundary):** Để tránh hỏng dữ liệu khi mất mạng đột ngột, hệ thống không lưu trữ tiến trình theo dung lượng byte ngẫu nhiên mà lưu theo chỉ số mảnh (`chunk_index`). Dữ liệu chỉ được đánh dấu thành công (`DONE`) trong SQLite sau khi đã ghi thành công mảnh 64KB hoàn chỉnh lên đĩa và kiểm tra SHA-256 của mảnh đó khớp.
* **Tự động tiếp tục khi kết nối lại:** Khi người dùng bấm nhận lại một file đang tải dở hoặc khởi động lại ứng dụng, Client sẽ gửi yêu cầu khôi phục kèm tham số `resumeChunkIndex = firstMissingChunk()`. File socket phía Sender sẽ tự động dịch chuyển con trỏ đọc (`RandomAccessFile.seek`) tới vị trí byte tương ứng và tiếp tục đẩy các mảnh tiếp theo mà không cần gửi lại từ đầu file.

---

## 3. Bootstrap Cache — Lưu "Top 5 Recent Peers"

**Lý do:** Bootstrap Server là điểm tra cứu duy nhất cho peer discovery. Khi Bootstrap die, peer mới hoặc peer restart không thể tìm được địa chỉ của bất kỳ ai. Cache top 5 peer giao tiếp gần nhất tạo **fallback tự nhiên** — những người bạn thực sự hay liên lạc chính là những người có khả năng cao nhất đang online và có thể giúp relay lookup.

**Peer cache local (SQLite bảng `recent_peers`):**

```
alice → [bob:5002 (last:2min), carol:5003 (last:5min), 
         dave:5004 (last:1hr), eve:5005 (last:2hr), frank:5006 (last:3hr)]

Cập nhật: mỗi khi gửi/nhận message thành công từ peer X → upsert X, 
           giữ top 5 theo lastSeen DESC
```

**Luồng lookup khi cần địa chỉ peer Y:**

```
1. Kiểm tra recent_peers cache local → có Y? → dùng luôn (không hỏi Bootstrap)
2. Không có → hỏi Bootstrap (bình thường)
3. Bootstrap die → hỏi lần lượt 5 peer trong cache: 
     "Bạn có biết địa chỉ của Y không?" (P2P relay nhẹ)
4. Vẫn không tìm được → báo lỗi UI "Không thể kết nối tới Y"
```

> **Điểm mấu chốt:** Cache này tự nhiên có giá trị cao vì top 5 recent = những người bạn thực sự hay liên lạc. Không cần lưu nhiều hơn. Không tốn tài nguyên đáng kể — chỉ 5 dòng SQLite, cập nhật lazy mỗi khi có giao tiếp thành công.

---

## 4. Web UI — File Transfer (giữ theme hiện tại)

> Giữ nguyên palette (`#1a1a2e`, `#16213e`, `#0f3460`, accent `#e94560`). Chỉ thêm element mới.

### 4.1. MessageInput — nút đính kèm file

`[📎] [input text] [Send] [Broadcast]`

- Nút 📎 chỉ hiện với chat type `peer` hoặc `group`
- Hiện preview tên file + size, kiểm tra size phía client

### 4.2. Message bubble — file

```
┌─────────────────────────────────┐
│ 📄 report.pdf  ·  2.4 MB       │
│ [████████░░░░] 62%  Đang tải   │  ← khi đang download
│                     [Tải về]   │  ← sau khi xong (bên nhận)
└─────────────────────────────────┘
```

### 4.3. Toast thông báo FILE_OFFER / FILE_GROUP_OFFER

```
┌─────────────────────────────────┐
│ 📥 alice gửi: report.pdf (2.4MB)│
│      [Chấp nhận]   [Từ chối]   │
└─────────────────────────────────┘
```

### 4.4. Sidebar — badge unread

Badge đỏ `#e94560` bên cạnh tên peer/group khi có tin chưa đọc.

---

## 5. Messaging — Các nâng cấp nhỏ

### 5.1. Typing indicator

`TYPING` message — gửi khi đang gõ, tự ngừng sau 3s. Không lưu SQLite.

### 5.2. Message delivery status

- ⏳ Đang gửi  
- ✓ Đã gửi (ACK)  
- 🔴 Thất bại (lưu offline nếu 1-1)

### 5.3. Read receipt

`READ_RECEIPT {reader, lastMessageId}` — hiển thị tick đôi ✓✓ bên nhận.

### 5.4. Broadcast UI riêng

Mục **"# Broadcast"** cố định trong Sidebar. Click → chế độ broadcast, lịch sử broadcast.

---

## 6. Thứ tự triển khai

| Ưu tiên | Tính năng | Độ phức tạp | Ước tính |
|---|---|---|---|
| 🔴 Cao | Group chat — DHT-lite Coordinator + HRW Hash | Cao | 3–4 ngày |
| 🔴 Cao | File Transfer 1-1 (backend + chunk resume) | Cao | 2–3 ngày |
| 🔴 Cao | File Transfer Group — P2P Swarming | Cao | 2–3 ngày |
| 🔴 Cao | File Transfer UI | Trung bình | 1–2 ngày |
| 🟡 Trung | Lamport timestamp + Buffer Window 200ms | Thấp | 0.5 ngày |
| 🟡 Trung | Bootstrap Cache (top 5 recent peers) | Thấp | 0.5 ngày |
| 🟡 Trung | C1 Timeout Hard Cancel + Idempotency | Trung bình | 1 ngày |
| 🟡 Trung | Reconnect Resync (heartbeat resumed trigger) | Thấp | 0.5 ngày |
| 🟡 Trung | Race Condition — LEAVING grace window | Thấp | 0.5 ngày |
| 🟡 Trung | Phân quyền OPEN/RESTRICTED group | Thấp | 0.5 ngày |
| 🟡 Trung | Group member list realtime UI | Thấp | 0.5 ngày |
| 🟡 Trung | Message delivery status | Thấp | 0.5 ngày |
| 🟡 Trung | Typing indicator | Thấp | 0.5 ngày |
| 🟢 Thấp | Broadcast UI riêng | Thấp | 0.5 ngày |
| 🟢 Thấp | Read receipt | Trung bình | 1 ngày |

---

## 7. Những gì KHÔNG cần thay đổi

- **Bootstrap core (heartbeat, peer discovery, offline-store 1-1):** Giữ nguyên, chỉ thêm GroupRegistry.
- **Cách gửi GROUP_MESSAGE:** Vẫn peer-to-peer trực tiếp (PeerClient multicast), Bootstrap không relay message.
- **Offline message store:** Chỉ áp dụng cho text 1-1. Group message và file: best-effort.
- **SQLite schema messages:** Thêm bảng `file_transfers` riêng.
- **Color theme & layout:** Giữ nguyên, chỉ thêm CSS classes mới.
- **Churn test script:** Không cần thay đổi.

 
 
## 8. Quản lý lưu trữ và Đảm bảo tính toàn vẹn (Persistence & Retention)

Trong hệ thống P2PChat, việc lưu trữ tin nhắn và file được phân chia rõ ràng để đảm bảo hiệu năng và không phụ thuộc vào một máy chủ trung tâm nào. Tất cả dữ liệu đều được lưu trữ hoàn toàn tại máy của từng người dùng (Local Storage), cụ thể:

### 8.1. Tin nhắn cá nhân (Direct Messages) & Tin nhắn Broadcast
- **Lưu trữ cục bộ:** Mọi tin nhắn (gửi đi và nhận về) đều được lưu vào cơ sở dữ liệu SQLite tại máy người dùng (bảng messages). 
- **Đảm bảo nhận tin khi Offline (Store-and-forward):** Nếu người nhận đang offline, tin nhắn sẽ được gửi tạm vào **Offline Store** trên Bootstrap Server. Ngay khi người nhận online trở lại, Bootstrap sẽ đẩy (deliver) các tin nhắn này về, và máy người nhận sẽ lập tức lưu vào SQLite. Cơ chế này đảm bảo không mất tin nhắn cá nhân dù 2 bên không online cùng lúc.

### 8.2. Tin nhắn Nhóm (Group Messages)
- **Lưu trữ phân tán P2P:** Tin nhắn nhóm cũng được lưu trực tiếp vào bảng messages (với trường groupId hoặc groupName) trên SQLite của từng thành viên trong nhóm nhận được tin đó.
- **Đồng bộ hóa trạng thái nhóm (Group State Resync):** Cơ chế `GROUP_RESYNC_REQ / GROUP_RESYNC_RESP` chỉ đồng bộ **trạng thái thành viên** (membership, version, owner, coordinators) — **không đồng bộ lịch sử tin nhắn**. Lý do:

  | Tiêu chí | Resync membership (hiện tại) | Resync cả message history |
  |---|---|---|
  | **Dung lượng** | Rất nhỏ (~1KB GroupInfo) | Có thể rất lớn (hàng trăm tin nhắn) |
  | **Phức tạp** | Chỉ cần so sánh version number | Cần tracking lastSeenMessageId, pagination, dedup |
  | **Coordinator tải** | Không đáng kể | Phải cache + phục vụ message history cho nhiều peer |
  | **Phù hợp** | ✅ P2P thuần, nhẹ, eventual consistency | ❌ Quá tải Coordinator, tiến tới mô hình server |

  Tin nhắn nhóm là **best-effort delivery**: nếu peer offline lúc tin gửi đi, tin đó sẽ không được khôi phục tự động. Tuy nhiên, khi thành viên mới được thêm vào nhóm, Coordinator gửi kèm `chatHistory` (lịch sử tin gần nhất từ DB local của coordinator) trong bản tin `GROUP_JOINED`, giúp thành viên mới không bị "trống" hội thoại hoàn toàn.

### 8.3. Truyền File (File Transfers)
- **Không lưu qua server trung gian:** Toàn bộ file truyền tải (1-1 hoặc vào nhóm) là P2P hoàn toàn. Dữ liệu file không lưu trên SQLite mà được lưu trực tiếp dưới dạng các tệp tin nhị phân vào thư mục tải về (ví dụ: thư mục downloads/ ở phía peer).
- **Lưu metadata:** Bảng ile_transfers trong SQLite chỉ lưu trữ siêu dữ liệu (metadata) của file như 	ransferId, ilename, size, sha256, status và đường dẫn file đã lưu (savedPath). 
- **Chunk-level Checkpointing (Khôi phục tải lỗi):** Trong quá trình truyền file, trạng thái từng phần (chunk) được ghi nhận trong bảng ile_chunks. Nếu mất kết nối giữa chừng, hệ thống sẽ sử dụng dữ liệu này để biết chính xác cần tải tiếp từ chunk nào (FILE_RESUME) thay vì phải tải lại toàn bộ. Cơ chế P2P Swarming trong nhóm còn giúp thành viên có thể tải file từ nhiều nguồn khác nhau, đảm bảo file luôn có thể tải được miễn là có ai đó trong nhóm đang giữ bản sao.

> **Tổng kết:** Mô hình lưu trữ Local-first kết hợp SQLite cho text metadata, hệ thống file system cho binary data, cùng cơ chế Store-and-forward (cho 1-1) và Lazy Repair / P2P Swarming (cho group/file) đảm bảo P2PChat hoạt động bền bỉ, không mất dữ liệu ngay cả trong môi trường mạng thiếu ổn định (high churn).

---

## 4. Cải tiến UI và Đồng bộ luồng (Cập nhật mới)

### 4.1. Hủy bỏ Typing Indicator (Bong bóng soạn tin)

**Tại sao lại phải hủy bỏ Typing Indicator trong mô hình này? Nếu không làm thì sao?**
- **Tại sao:** Việc duy trì trạng thái "đang gõ" yêu cầu hệ thống phải trao đổi thông điệp với độ trễ cực thấp (low-latency) và tần suất cao. Trong mô hình DHT-lite, việc một Peer gửi tín hiệu `TYPING` tới toàn bộ nhóm có thể gây ra spam mạng lớn. Hơn nữa, với Eventual Consistency, các tín hiệu này không đảm bảo thứ tự và dễ bị lạc, dẫn đến UI bị "kẹt" trạng thái gõ.
- **Nếu không làm:** Giao diện người dùng sẽ thường xuyên hiển thị sai trạng thái (người khác đã dừng gõ nhưng bong bóng vẫn hiện), và mạng P2P phải chịu tải không cần thiết cho một tính năng không đảm bảo tính nhất quán.

**Bảng so sánh: Typing Indicator (P2P vs Server-Client)**
| Tiêu chí | Server-Client (Centralized) | P2P DHT-lite |
|---|---|---|
| **Mức độ phức tạp** | Thấp (Server quản lý state và push xuống client) | Cao (Mỗi peer phải tự multicast tới N peer khác) |
| **Băng thông** | Tối ưu (Chỉ 1 kết nối WS tới server) | Tốn kém (N-1 kết nối TCP cho mỗi tín hiệu) |
| **Tính chính xác** | Cao (Đồng bộ real-time) | Thấp (Dễ mất gói tin, thứ tự lộn xộn) |

### 4.2. Hiển thị Huy hiệu Coordinator (Chữ 'c')

**Tại sao phải thêm huy hiệu 'c' (Coordinator) bên cạnh Owner?**
- **Tại sao:** Coordinator đóng vai trò quan trọng trong việc duy trì GroupInfo (Control Plane). Người dùng cần biết ai đang giữ vai trò này để có cái nhìn minh bạch về mạng lưới. Nếu Owner (chủ phòng) đồng thời là Coordinator, việc hiển thị cả vương miện (👑) và chữ 'c' giúp xác định rõ hai vai trò độc lập: Quyền quản trị (Owner) và Vai trò hạ tầng mạng (Coordinator).
- **Nếu không làm:** Người dùng (và Developer khi debug) sẽ không biết peer nào đang chịu trách nhiệm lưu trữ và phân phối trạng thái nhóm, gây khó khăn trong việc hiểu tính chất phân tán của hệ thống.

### 4.3. Fix lỗi mất tin nhắn SYSTEM (Thông báo Join/Kick/Leave)

**Tại sao tin nhắn thông báo (Join/Kick) đôi khi không hiển thị hoặc bị thiếu đoạn giữa?**
- **Lý do gốc:** 
  1. **Lỗi truy vấn SQL:** Khi một peer (hoặc người mới join) kéo lịch sử từ DB của Coordinator (`getGroupHistory`), hàm SQL chỉ lấy các tin có type là `GROUP_MESSAGE` và `FILE_OFFER`, bỏ quên `SYSTEM`.
  2. **Lỗi Local Routing:** Khi một peer *là coordinator* thực hiện lệnh (ví dụ Kick), lệnh được gửi qua API nhưng lại bị bypass (không kích hoạt luồng WS cục bộ) dẫn tới người thực hiện hành động không thấy thông báo của chính mình ngay lập tức.
- **Tại sao phải sửa theo hướng Route Local và Update SQL? Nếu không làm thì sao?**
  - Cần thêm `SYSTEM` vào SQL query để đảm bảo lịch sử (State) là nguồn thật duy nhất. 
  - Cần đồng bộ `PeerServer` và `CoordinatorManager` nội bộ bằng memory (không qua TCP) để giao diện local tự cập nhật.
  - **Nếu không làm:** Trải nghiệm người dùng sẽ bị đứt gãy. Peer D bị kích rồi vào lại sẽ không thấy dòng chữ "D bị kích" (do D phải kéo history từ Coordinator nhưng Coordinator lại giấu tin nhắn `SYSTEM`), gây hoang mang về dòng thời gian của nhóm.
