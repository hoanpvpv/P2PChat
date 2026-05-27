# Bootstrap Server — Quản lý mạng P2P & Peer Discovery

---

## Mục lục

1. [Tổng quan](#1-tổng-quan)
2. [Kiến trúc Bootstrap Server](#2-kiến-trúc-bootstrap-server)
3. [Entry Point — `BootstrapApp.java`](#3-entry-point--bootstrapappjava)
4. [Server chính — `BootstrapServer.java`](#4-server-chính--bootstrapserversjava)
5. [Quản lý peer — `PeerRegistry.java`](#5-quản-lý-peer--peerregistryjava)
6. [Xử lý kết nối — `ClientHandler.java`](#6-xử-lý-kết-nối--clienthandlerjava)
7. [Dashboard HTTP — `BootstrapDashboardServer.java`](#7-dashboard-http--bootstrapdashboardserverjava)
8. [Ghi log sự kiện — `BootstrapEventLog.java`](#8-ghi-log-sự-kiện--bootstrapeventlogjava)
9. [Giao thức truyền thông & MessageType](#9-giao-thức-truyền-thông--messagetype)
10. [Peer Discovery — Luồng hoạt động chi tiết](#10-peer-discovery--luồng-hoạt-động-chi-tiết)
11. [Heartbeat & Dead-Peer Detection](#11-heartbeat--dead-peer-detection)
12. [Quản lý trạng thái Online/Offline](#12-quản-lý-trạng-thái-onlineoffline)
13. [Mô phỏng Churn — `churn-test.ps1`](#13-mô-phỏng-churn--churn-testps1)
14. [Hằng số cấu hình](#14-hằng-số-cấu-hình)
15. [Sơ đồ luồng tổng hợp](#15-sơ-đồ-luồng-tổng-hợp)

---

## 1. Tổng quan

### Vai trò của Bootstrap Server

Trong mạng P2P thuần túy (pure P2P), mỗi peer cần biết ít nhất một peer khác để tham gia mạng. **Bootstrap Server** đóng vai trò điểm vào (entry point) duy nhất cho tất cả peer:

- **Đăng ký (Register):** Tiếp nhận yêu cầu đăng ký từ peer, lưu trạng thái.
- **Peer Discovery:** Cung cấp danh sách peer online cho peer mới hoặc peer yêu cầu.
- **Heartbeat:** Theo dõi trạng thái sống/chết của peer qua cơ chế keep-alive.
- **Offline Message:** Lưu tin nhắn tạm thời cho peer offline, giao lại khi peer online.
- **Broadcast sự kiện:** Thông báo `PEER_JOIN`/`PEER_LEAVE` đến toàn mạng.

> **Lưu ý quan trọng:** Bootstrap Server **KHÔNG** truyền nội dung chat. Mọi tin nhắn chat được gửi trực tiếp peer-to-peer qua TCP. Bootstrap chỉ quản lý metadata và điều phối.

### Mô hình tương tác tổng quát

```
┌──────────────────────────────────────────────────────┐
│                  Bootstrap Server                      │
│                 TCP :8080  |  HTTP :8081               │
│           PeerRegistry + BootstrapEventLog            │
└──────┬───────────┬───────────┬───────────┬────────────┘
       │           │           │           │
   REGISTER    HEARTBEAT    DISCOVER  STORE_MESSAGE
       │           │           │           │
  ┌────▼───┐  ┌───▼────┐  ┌──▼────┐  ┌──▼──────────┐
  │ Alice  │  │  Bob   │  │ Carol │  │ Offline Msg  │
  │ :5001  │  │ :5002  │  │ :5003 │  │    Queue     │
  └────┬───┘  └────┬───┘  └───┬───┘  └──────────────┘
       │            │          │
       └────────────┴──────────┘
            P2P TCP trực tiếp (DIRECT_MESSAGE)
```

---

## 2. Kiến trúc Bootstrap Server

### 2.1 Các thành phần chính

| Thành phần | Kiểu | Mô tả |
|---|---|---|
| `BootstrapApp` | Class | Entry point, khởi tạo server & dashboard |
| `BootstrapServer` | Class | TCP server chính, vòng lặp accept, broadcast |
| `PeerRegistry` | Class | ConcurrentHashMap lưu trạng thái peer + tin nhắn offline |
| `ClientHandler` | Class (Runnable) | Xử lý từng kết nối peer trong thread riêng |
| `BootstrapDashboardServer` | Class | HTTP server (JDK built-in) phục vụ dashboard |
| `BootstrapEventLog` | Class | Thread-safe event log (ConcurrentLinkedDeque) |
| `MessageType` | Enum | Danh sách tất cả message type |
| `ProtocolHandler` | Class | Factory method tạo message JSON |
| `Constants` | Class | Hằng số cấu hình |

### 2.2 Sơ đồ class

```
BootstrapApp
  ├── BootstrapServer
  │     ├── PeerRegistry          (ConcurrentHashMap<String, PeerInfo>)
  │     ├── BootstrapEventLog     (ConcurrentLinkedDeque<EventEntry>)
  │     ├── ScheduledExecutor     (dead-peer detector, chạy định kỳ)
  │     └── ClientHandler (1 instance / kết nối peer, mỗi cái 1 thread)
  │           └── ProtocolHandler / JsonUtil
  │
  └── BootstrapDashboardServer    (com.sun.net.httpserver.HttpServer)
        └── Endpoints:
              GET /                → static/index.html
              GET /api/status      → JSON trạng thái server
              GET /api/peers       → JSON danh sách peer
              GET /api/events      → JSON 200 sự kiện gần nhất
```

---

## 3. Entry Point — `BootstrapApp.java`

```java
int bootstrapPort = 8080;      // mặc định, ghi đè qua --port
int dashboardPort = 8081;      // mặc định, ghi đè qua --dashboard-port
```

Thứ tự khởi động:

```
1. BootstrapDashboardServer.start()  → HTTP dashboard (port 8081)
2. BootstrapServer.start()           → TCP server (port 8080)
```

Tham số CLI:

| Tham số | Mặc định | Mô tả |
|---|---|---|
| `--port <n>` | `8080` | TCP port bootstrap |
| `--dashboard-port <n>` | `8081` | HTTP port dashboard |
| `--mailbox-host <h>` | `localhost` | Host mailbox server |
| `--mailbox-port <n>` | `9100` | Port mailbox server |

---

## 4. Server chính — `BootstrapServer.java`

### 4.1 Thuộc tính

```java
ServerSocket          serverSocket     // TCP lắng nghe kết nối peer
ExecutorService       threadPool       // newCachedThreadPool — mỗi peer 1 thread
PeerRegistry          registry         // Lưu trạng thái tất cả peer
BootstrapEventLog     eventLog         // Ghi lịch sử sự kiện
volatile boolean      running          // Flag dừng server
ScheduledExecutorService (local)       // Dead-peer detector
```

### 4.2 Vòng lặp accept

```java
while (running) {
    Socket clientSocket = serverSocket.accept();          // chờ peer kết nối
    threadPool.execute(new ClientHandler(                // tạo thread xử lý
        clientSocket, registry, this
    ));
}
```

### 4.3 Dead-Peer Detector

Chạy trong `ScheduledExecutorService` riêng, kiểm tra định kỳ:

```
Chu kỳ: 5 giây (bằng HEARTBEAT_INTERVAL)

→ registry.checkDeadPeers(HEARTBEAT_TIMEOUT = 45000ms)
     Nếu now - lastHeartbeat > 45000ms:
         1. registry.removeDeadPeer(username)
         2. eventLog.warn("HEARTBEAT_TIMEOUT", username, ...)
         3. broadcastPeerLeave(username)  → gửi PEER_LEAVE đến tất cả peer online
```

### 4.4 Broadcast

`broadcastToAll(Message, excludePeer)` — gửi message đến **tất cả** peer online, ngoại trừ `excludePeer`:

```java
for (PeerInfo peer : registry.getOnlinePeers()) {
    if (peer.getUsername().equals(excludePeer)) continue;
    try (Socket s = new Socket(peer.getHost(), peer.getPort())) {
        // gửi JSON message
    } catch (IOException e) {
        // log lỗi, không block
    }
}
```

---

## 5. Quản lý peer — `PeerRegistry.java`

### 5.1 Cấu trúc dữ liệu

```java
ConcurrentHashMap<String, PeerInfo>         peers             // peer đã đăng ký
ConcurrentHashMap<String, List<Message>>   offlineMessages   // tin chờ giao
Path                                        registryFile      // lưu JSON (bền vững)
```

> `PeerInfo` chứa: `username`, `host`, `port`, `keyId`, `publicKey`, `online`, `lastHeartbeat`.

### 5.2 Các thao tác chính

| Phương thức | Mô tả |
|---|---|
| `register(PeerInfo)` | Thêm/cập nhật peer, đặt `online=true`, cập nhật `lastHeartbeat` |
| `unregister(username)` | Đánh dấu `online=false` (không xóa) |
| `updateHeartbeat(username)` | Cập nhật `lastHeartbeat = now()` |
| `updateAddress(username, host, port)` | Cập nhật địa chỉ + heartbeat |
| `getOnlinePeers()` | Trả list peer có `online=true` |
| `getAllPeers()` | Trả list tất cả peer (kể cả offline) |
| `checkDeadPeers(timeout)` | Trả list username có `now - lastHeartbeat > timeout` |
| `removeDeadPeer(username)` | Đánh dấu peer offline |
| `storeOfflineMessage(receiver, msg)` | Lưu message vào queue của receiver |
| `getOfflineMessages(username)` | Lấy & xóa queue (atomic) |
| `getPeerListJson()` | Chuỗi `"alice@host:port|keyId|pk|online,bob@host:port||online"` |

### 5.3 Định dạng Peer List (gửi về peer)

```
username@host:port[|keyId|publicKey]|online|offline
```

Ví dụ:

```
alice@192.168.1.10:5001|key123|MIGfMA0...|online
bob@192.168.1.11:5002||online
```

### 5.4 Persistence (lưu JSON)

`PeerRegistry` tự động lưu/đọc `registry.json` mỗi khi có thay đổi:

```
loadPeers()  — gọi trong constructor (khi server khởi động lại, load lại peer cũ → offline)
savePeers()  — gọi sau mỗi register / unregister / heartbeat update
```

---

## 6. Xử lý kết nối — `ClientHandler.java`

`ClientHandler` implement `Runnable`, chạy trong thread riêng cho mỗi kết nối TCP.

### 6.1 Vòng lặp đọc message

```java
while ((line = in.readLine()) != null) {
    Message message = JsonUtil.fromJson(line.trim());
    handleMessage(message);
}
```

### 6.2 Xử lý theo MessageType

#### `REGISTER` — Peer tham gia mạng

```
1. Parse content = "host:port[|keyId|publicKey]"
2. Detect NAT: nếu advertised host = Docker bridge IP (172.16–31.x.x)
               hoặc Tailscale IP (100.64–127.x.x)
               → ghi đè bằng remote IP thực từ socket
3. registry.register(peerInfo)
4. Gửi REGISTER_ACK kèm peer list hiện tại
5. Giao offline messages (nếu có)
6. Nếu peer mới (chưa tồn tại) → broadcast PEER_JOIN
   Nếu re-register → log REGISTER_REPLACE, KHÔNG broadcast
```

#### `HEARTBEAT` — Keep-alive

```
1. Kiểm tra peer đã đăng ký chưa
   → Nếu chưa: gửi REGISTER_NACK
2. Cập nhật lastHeartbeat + host (nếu NAT detected)
3. Gửi HEARTBEAT_ACK kèm peer list mới nhất
```

#### `DISCOVER` — Yêu cầu danh sách peer

```
1. Gửi PEER_LIST chứa toàn bộ peer online
```

#### `STORE_MESSAGE` — Lưu offline message

```
1. registry.storeOfflineMessage(receiver, message)
2. Gửi ACK
```

#### `PEER_LEAVE` — Peer rời mạng graceful

```
1. registry.unregister(username)
2. broadcastPeerLeave(username)
```

#### `RESOLVE_MAILBOX` — Trả mailbox endpoint

```
1. Gửi RESOLVE_MAILBOX_ACK với content = "mailboxHost:mailboxPort"
```

### 6.3 NAT Detection (Host Override)

```
shouldPreferRemoteHost(advertisedHost, remoteHost):
  → remoteHost là Tailscale IP (100.64–127.x.x)?
      AND advertisedHost ≠ remoteHost?
      AND advertisedHost là Docker bridge (172.16–31.x.x) HOẶC Tailscale?
      → TRUE: ghi đè advertised = remote

Tailscale range:   100.64.0.0/10  (100.64 – 100.127)
Docker bridge:     172.16.0.0/12  (172.16 – 172.31)
```

---

## 7. Dashboard HTTP — `BootstrapDashboardServer.java`

Dùng `com.sun.net.httpserver.HttpServer` (JDK built-in, không cần dependency thêm).

### 7.1 Các endpoint

| Method | Route | Trả về |
|---|---|---|
| `GET` | `/` | `static/index.html` — giao diện dashboard |
| `GET` | `/api/status` | JSON trạng thái server |
| `GET` | `/api/peers` | JSON array toàn bộ `PeerInfo` |
| `GET` | `/api/events` | JSON array 200 sự kiện gần nhất |

### 7.2 JSON `/api/status`

```json
{
  "serverHost": "192.168.1.100",
  "bootstrapPort": 8080,
  "dashboardPort": 8081,
  "mailboxHost": "localhost",
  "mailboxPort": 9100,
  "running": true,
  "onlinePeerCount": 4,
  "knownPeerCount": 6,
  "offlineMessageCount": 2
}
```

### 7.3 Dashboard UI

Dashboard tự refresh mỗi **3 giây** bằng `setInterval` phía browser. Hiển thị:

- Thông tin server (host, port, trạng thái)
- Bảng danh sách peer: username, host, port, online/offline, lastHeartbeat
- Bảng event log thời gian thực: timestamp, level (INFO/WARN/ERROR), type, peer, detail

---

## 8. Ghi log sự kiện — `BootstrapEventLog.java`

### 8.1 Cấu trúc

```java
ConcurrentLinkedDeque<EventEntry>  // thread-safe, FIFO giới hạn 200 mục
MAX_EVENTS = 200

record EventEntry(
    String timestamp,   // ISO-8601 (Instant.now())
    String level,        // INFO | WARN | ERROR
    String type,         // PEER_JOIN, HEARTBEAT_TIMEOUT, ...
    String peer,         // username liên quan
    String detail        // mô tả chi tiết
)
```

### 8.2 Các loại event được ghi

| Level | Type | Trigger |
|---|---|---|
| INFO | `SERVER_START` | Bootstrap server khởi động |
| INFO | `SERVER_STOP` | Bootstrap server dừng |
| INFO | `REGISTER` | Peer mới đăng ký thành công |
| WARN | `REGISTER_REPLACE` | Peer re-register (username đã tồn tại) |
| WARN | `REGISTER_INVALID` | REGISTER sai định dạng |
| INFO | `HEARTBEAT` | Heartbeat được xác nhận |
| WARN | `HEARTBEAT_UNKNOWN` | Heartbeat từ peer chưa đăng ký |
| WARN | `HEARTBEAT_TIMEOUT` | Peer không heartbeat quá timeout |
| INFO | `PEER_JOIN` | Peer mới broadcast đến các peer khác |
| INFO | `PEER_LEAVE` | Peer rời mạng |
| INFO | `PEER_REMOVED` | Dead peer bị xóa khỏi registry |
| INFO | `OFFLINE_DELIVERY` | Giao offline message thành công |
| INFO | `STORE_MESSAGE` | Lưu offline message |
| WARN | `BROADCAST_FAILED` | Gửi broadcast đến peer thất bại |
| ERROR | `ACCEPT_ERROR` | Lỗi chấp nhận kết nối |
| ERROR | `SERVER_START_FAILED` | Lỗi khởi động server |

---

## 9. Giao thức truyền thông — MessageType

### 9.1 Tất cả MessageType

| Type | Hướng | Mô tả |
|---|---|---|
| `REGISTER` | Peer → Bootstrap | Đăng ký tham gia mạng |
| `REGISTER_ACK` | Bootstrap → Peer | Xác nhận + gửi peer list |
| `REGISTER_NACK` | Bootstrap → Peer | Từ chối (peer chưa đăng ký) |
| `PEER_LIST` | Bootstrap → Peer | Danh sách peer online (đáp DISCOVER) |
| `PEER_JOIN` | Bootstrap → All | Thông báo peer mới tham gia |
| `PEER_LEAVE` | Bootstrap → All | Thông báo peer rời mạng |
| `HEARTBEAT` | Peer → Bootstrap | Keep-alive (mỗi 5s) |
| `HEARTBEAT_ACK` | Bootstrap → Peer | Xác nhận heartbeat + peer list mới |
| `DISCOVER` | Peer → Bootstrap | Yêu cầu danh sách peer |
| `DIRECT_MESSAGE` | Peer → Peer | Tin nhắn trực tiếp |
| `GROUP_MESSAGE` | Peer → Peers | Tin nhắn nhóm |
| `BROADCAST` | Peer → All Peers | Broadcast toàn mạng |
| `ACK` | Peer → Peer | Xác nhận nhận tin |
| `STORE_MESSAGE` | Peer → Bootstrap | Lưu tin cho peer offline |
| `OFFLINE_MESSAGE` | Bootstrap → Peer | Giao tin lưu khi peer online lại |
| `RESOLVE_MAILBOX` | Peer → Bootstrap | Yêu cầu mailbox endpoint |
| `RESOLVE_MAILBOX_ACK` | Bootstrap → Peer | Trả mailbox host:port |
| `CREATE_GROUP` | (reserved) | Tạo nhóm |
| `ADD_TO_GROUP` | (reserved) | Thêm member vào nhóm |
| `ERROR` | Any | Thông báo lỗi |

### 9.2 Định dạng JSON Message

```json
{
  "type": "HEARTBEAT",
  "messageId": "uuid-v4",
  "sender": "alice",
  "receiver": "bootstrap",
  "groupName": null,
  "content": "",
  "timestamp": 1715376000000
}
```

---

## 10. Peer Discovery — Luồng hoạt động chi tiết

### 10.1 Peer mới tham gia mạng

```
Peer Alice                  Bootstrap Server              Peer Bob (đã online)
    |                              |                              |
    |──── REGISTER ───────────────>|                              |
    |     (host:port|keyId|pk)     |                              |
    |                              |                              |
    |                              |──── PEER_JOIN ──────────────>|
    |                              |     (Alice tham gia)         |
    |<── REGISTER_ACK ─────────────|                              |
    |     (peer list JSON)         |                              |
    |                              |                              |
    |<── OFFLINE_MESSAGE* ─────────|  (nếu có tin nhắn chờ)      |
    |     (tin nhắn offline gửi    |                              |
    |      trong cùng socket)      |                              |
```

### 10.2 Peer yêu cầu refresh danh sách (DISCOVER)

```
Peer Carol                  Bootstrap Server
    |                              |
    |──── DISCOVER ───────────────>|
    |<── PEER_LIST ────────────────|
          (alice@host:5001|online,
           bob@host:5002|online)
```

Peer tự động nhận peer list mới mỗi lần heartbeat được ACK.

### 10.3 Peer rời mạng graceful

```
Peer Bob                    Bootstrap Server              Peer Alice / Carol
    |──── PEER_LEAVE ─────────────>|                              |
    |                              |──── PEER_LEAVE ─────────────>|
    |                              |     (Bob rời mạng)           |
```

### 10.4 Peer crash (không graceful)

```
Bootstrap Server (mỗi 5s)
  → checkDeadPeers(timeout=45000ms)
  → Peer X: now - lastHeartbeat > 45000ms
  → removeDeadPeer("X")     (đánh dấu offline)
  → broadcastPeerLeave("X") → tất cả peer online
```

---

## 11. Heartbeat & Dead-Peer Detection

### 11.1 Cơ chế Heartbeat

```
Mục đích:
  - Giữ kết nối "sống" trong registry của bootstrap
  - Phát hiện peer crash/ngắt mạng không thông báo
  - Cập nhật peer list định kỳ cho tất cả peer

Tần suất:     Mỗi 5 giây (HEARTBEAT_INTERVAL)
Timeout:      45 giây (HEARTBEAT_TIMEOUT) — peer bị coi dead
```

### 11.2 Luồng Heartbeat

```
Peer Side (PeerNode):
  while (running) {
      Thread.sleep(5000);
      send HEARTBEAT → Bootstrap
      receive HEARTBEAT_ACK + peerList
      update knownPeers(peerList)
  }

Bootstrap Side:
  1. Nhận HEARTBEAT từ peer
  2. Cập nhật lastHeartbeat
  3. Gửi HEARTBEAT_ACK kèm peerList mới
```

### 11.3 Dead-Peer Detection (Bootstrap Server side)

```
Scheduled task (mỗi 5s):
  1. Lấy danh sách peer online từ registry
  2. Với mỗi peer: nếu now - lastHeartbeat > 45000ms
       → peer offline
       → registry.removeDeadPeer(username)
       → eventLog.warn("HEARTBEAT_TIMEOUT", username)
       → broadcast PEER_LEAVE đến tất cả peer còn lại
```

### 11.4 Timeout ngưỡng

```
HEARTBEAT_INTERVAL = 5s    → peer gửi heartbeat mỗi 5 giây
HEARTBEAT_TIMEOUT  = 45s   → bootstrap chờ tối đa 45s không heartbeat
                              = 9 lần heartbeat bị miss → mới coi là dead

Ratio: 45s / 5s = 9 heartbeat cycles
→ Hệ thống chịu được 8 heartbeat miss liên tiếp trước khi declare dead
```

### 11.5 So sánh Leave graceful vs. Crash

| Hành vi | Graceful (PEER_LEAVE) | Crash (timeout) |
|---|---|---|
| Peer gửi thông báo | Có | Không |
| Bootstrap xóa ngay | Có | Không (chờ timeout) |
| Broadcast PEER_LEAVE | Có (ngay lập tức) | Có (sau timeout 45s) |
| Peer khác phát hiện | < 1s | 5 – 45s |

---

## 12. Quản lý trạng thái Online/Offline

### 12.1 Trạng thái của một Peer

```
OFFLINE (khởi động)
  │
  │── REGISTER thành công
  ▼
ONLINE (đang kết nối)
  │
  ├── PEER_LEAVE ──────────────────────────────> OFFLINE (graceful)
  │
  ├── HEARTBEAT_TIMEOUT (>45s không heartbeat) ─> OFFLINE (crash)
  │
  └── DISCONNECT (socket close, không LEAVE) ────> OFFLINE (sau timeout)
```

### 12.2 Offline Message Flow

```
Alice gửi tin cho Bob (Bob đang offline):

1. Alice ── DIRECT_MESSAGE ──> Bob (TCP thất bại sau 3 retry)
2. Alice ── STORE_MESSAGE ───> Bootstrap
                           Bootstrap lưu: offlineMessages["bob"] += msg
3. Bob khởi động lại, ── REGISTER ─────────────────> Bootstrap
4. Bootstrap ── REGISTER_ACK ───────────────────> Bob
5. Bootstrap ── OFFLINE_MESSAGE ─────────────────> Bob (trong cùng socket)
                    (lặp qua tất cả msg trong queue["bob"],
                     sau đó xóa queue)
```

### 12.3 Peer List state machine (Client side)

```
knownPeers: ConcurrentHashMap<String, PeerInfo>

Nhận PEER_LIST (từ REGISTER_ACK / HEARTBEAT_ACK / PEER_JOIN / PEER_LEAVE):
  1. Đặt tất cả peer hiện tại → online = false
  2. Parse peer list string
  3. Với mỗi peer trong list:
       → Thêm / cập nhật trong knownPeers (trừ bản thân)
       → online = true
```

---



## 14. Hằng số cấu hình

File: `bootstrap-server/src/main/java/com/mycompany/p2pchat/utils/Constants.java`

| Hằng số | Giá trị | Mô tả |
|---|---|---|
| `DEFAULT_BOOTSTRAP_PORT` | `8080` | TCP port bootstrap server |
| `DEFAULT_DASHBOARD_PORT` | `8081` | HTTP port dashboard |
| `DEFAULT_PEER_PORT` | `5001` | TCP port peer mặc định |
| `HEARTBEAT_INTERVAL` | `5000 ms` | Peer gửi heartbeat mỗi 5 giây |
| `HEARTBEAT_TIMEOUT` | `45000 ms` | Peer bị coi dead sau 45 giây |
| `ACK_TIMEOUT` | `5000 ms` | Timeout chờ ACK từ peer nhận |
| `MAX_RETRIES` | `3` | Số lần retry gửi message |
| `RECONNECT_INTERVAL` | `3000 ms` | Khoảng cách giữa các retry |
| `DELIMITER` | `\n` | Ký tự phân cách message (newline-delimited JSON) |
| `DB_EXTENSION` | `.db` | Đuôi file SQLite |

---

## 15. Sơ đồ luồng tổng hợp

### 15.1 Luồng Register đầy đủ

```
Peer                                    Bootstrap                          Peers khác
 |                                          |                                  |
 |---- REGISTER ---------------------------->|                                  |
 |     content: "host:port|keyId|pk"        |                                  |
 |                                          |                                  |
 |                                          |-- shouldPreferRemoteHost? ------->|
 |                                          |   (NAT / Docker / Tailscale)     |
 |                                          |                                  |
 |                                          |-- registry.register(peer) ------>|
 |                                          |   peers.put(username, peer)     |
 |                                          |   peers[username].online = true   |
 |                                          |                                  |
 |<--- REGISTER_ACK ------------------------|                                  |
 |     content: peerListJson                |                                  |
 |                                          |                                  |
 |<--- OFFLINE_MESSAGE(s) ------------------|  (nếu có msg trong queue)         |
 |     (iterated from offlineMessages)      |                                  |
 |                                          |                                  |
 |                                          |-- broadcastPeerJoin(newPeer) --->|
 |                                          |   PEER_JOIN → mỗi peer online     |
```

### 15.2 Luồng Churn trọn vòng đời

```
Round N:
  ┌─ CRASH ─────────────────────────────────────────────┐
  │  Stop-Process peer02, peer04 (force)                │
  │  → OS kill process → socket close không LEAVE        │
  └──────────────────────────────────────────────────────┘
        │
        │ (Bootstrap mỗi 5s kiểm tra heartbeat)
        │
  ┌─ TIMEOUT (45s sau crash) ───────────────────────────┐
  │  checkDeadPeers(45000) → peer02, peer04 dead         │
  │  removeDeadPeer(peer02)                             │
  │  removeDeadPeer(peer04)                              │
  │  broadcastPeerLeave(peer02) ──> Alice, Bob, Carol    │
  │  broadcastPeerLeave(peer04) ──> Alice, Bob, Carol    │
  └──────────────────────────────────────────────────────┘
        │
        │ (Peer mới start ở bước JOIN)
        │
  ┌─ JOIN ───────────────────────────────────────────────┐
  │  Start peer02.jar (TCP :5002, Web :3001)            │
  │  Wait /api/info ready (healthcheck)                  │
  │  POST /api/register → Bootstrap                     │
  │  Bootstrap ── REGISTER_ACK ──> peer02               │
  │  Bootstrap ── PEER_JOIN ────> Alice, Bob, Carol      │
  └──────────────────────────────────────────────────────┘
```

---

## Phụ lục: Cách khởi động

### Build

```bash
cd bootstrap-server
mvn clean package -DskipTests
```

### Chạy Bootstrap Server

```bash
java -jar target/bootstrap-server.jar
# hoặc tùy chỉnh:
java -jar target/bootstrap-server.jar --port 8080 --dashboard-port 8081
```

### Dashboard

```
http://localhost:8081
```

### Chạy Peer

```bash
java -jar peer-node.jar --port 5001 --web 3000
```

### Chạy Churn Test

```bash
powershell -ExecutionPolicy Bypass -File churn-test.ps1 -PeerCount 6 -TotalRounds 12
```
