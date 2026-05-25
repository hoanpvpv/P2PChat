# P2PChat — Tài liệu kỹ thuật toàn hệ thống

---

## 1. Tổng quan kiến trúc

P2PChat là hệ thống chat ngang hàng (P2P) viết bằng Java 17. Mỗi peer vừa là **client** (gửi tin) vừa là **server** (nhận tin). Bootstrap Server đóng vai trò **trung tâm điều phối** — không truyền nội dung chat, chỉ quản lý địa chỉ peer, heartbeat và offline message.

```
                  ┌─────────────────────────────────┐
                  │         Bootstrap Server         │
                  │   TCP :9000   |  HTTP :9001      │
                  │   PeerRegistry + EventLog         │
                  └──┬──────┬──────┬──────┬──────────┘
                     │      │      │      │
               REGISTER  HEARTBEAT  DISCOVER  STORE_MSG
                     │      │      │      │
          ┌──────────▼──┐  ┌▼──────────┐  ┌▼──────────┐
          │  Peer Alice  │  │  Peer Bob │  │ Peer Carol │
          │  TCP :5001   │  │  TCP:5002 │  │  TCP:5003  │
          │  Web :3000   │  │  Web:3001 │  │  Web:3002  │
          └──────┬───────┘  └─────┬─────┘  └─────┬──────┘
                 │                │               │
                 └────────────────┴───────────────┘
                       P2P TCP trực tiếp (DIRECT_MESSAGE)
```

### Thành phần

| Module | Ngôn ngữ | Vai trò |
|---|---|---|
| `bootstrap-server` | Java 17 + Gson | Đăng ký peer, discovery, offline store, dashboard |
| `peer-node` | Java 17 + Javalin + SQLite | TCP server/client + REST API + WebSocket |
| `peer-web` | React 18 | Giao diện chat trên trình duyệt |

---

## 2. Bootstrap Server — Chi tiết

### 2.1. Entry point: `BootstrapApp.java`

```java
int port          = Constants.DEFAULT_BOOTSTRAP_PORT;  // 9000
int dashboardPort = Constants.DEFAULT_DASHBOARD_PORT;  // 9001
```

Phân tích tham số CLI:
- `--port <n>` — đổi port TCP (mặc định 9000)
- `--dashboard-port <n>` — đổi port HTTP dashboard (mặc định 9001)

Khởi động thứ tự:
1. `BootstrapDashboardServer.start()` — HTTP dashboard lên trước
2. `BootstrapServer.start()` — TCP server vòng lặp accept

### 2.2. `BootstrapServer.java` — Server chính

| Thành phần | Kiểu | Mô tả |
|---|---|---|
| `ServerSocket` | TCP | Lắng nghe kết nối từ peer trên port 9000 |
| `ExecutorService` | `newCachedThreadPool` | Mỗi peer kết nối = 1 thread riêng |
| `PeerRegistry` | in-memory | Lưu trạng thái tất cả peer |
| `BootstrapEventLog` | in-memory deque | Ghi lịch sử sự kiện (tối đa 200) |
| Dead-peer detector | `ScheduledExecutorService` | Chạy mỗi 5s, kiểm tra heartbeat timeout |

**Vòng lặp chính:**
```
while (running) {
    Socket client = serverSocket.accept();
    threadPool.execute(new ClientHandler(client, registry, this));
}
```

**Dead-peer detector:**
```
scheduleAtFixedRate(
    period = HEARTBEAT_INTERVAL = 5000ms
)
→ registry.checkDeadPeers(HEARTBEAT_TIMEOUT = 15000ms)
→ broadcastPeerLeave(username)
```

### 2.3. `PeerRegistry.java` — Quản lý trạng thái peer

```
ConcurrentHashMap<String, PeerInfo>         peers          — peer đang hoạt động
ConcurrentHashMap<String, List<Message>>    offlineMessages — tin nhắn chờ giao
```

**Các thao tác chính:**

| Phương thức | Hành vi |
|---|---|
| `register(PeerInfo)` | Thêm peer, đặt `online=true`, cập nhật `lastHeartbeat` |
| `unregister(username)` | Xóa peer khỏi map |
| `updateHeartbeat(username)` | Cập nhật `lastHeartbeat = now()` |
| `checkDeadPeers(timeout)` | Trả list peer có `now - lastHeartbeat > timeout` |
| `storeOfflineMessage(receiver, msg)` | Lưu tin vào queue của receiver |
| `getOfflineMessages(username)` | Lấy & xóa toàn bộ queue (giao khi peer online lại) |
| `getPeerListJson()` | `"alice@localhost:5001,bob@localhost:5002"` |

### 2.4. `ClientHandler.java` — Xử lý lệnh từ peer

Chạy trong thread riêng. Đọc JSON message từng dòng (`readLine()`) và dispatch:

| MessageType nhận | Hành vi xử lý |
|---|---|
| `REGISTER` | Parse `host:port`, gọi `registry.register()`, gửi `REGISTER_ACK` + peer list, giao offline messages, broadcast `PEER_JOIN` đến tất cả |
| `HEARTBEAT` | Cập nhật `lastHeartbeat`, gửi `HEARTBEAT_ACK` kèm peer list mới nhất |
| `DISCOVER` | Gửi `PEER_LIST` chứa toàn bộ peer online |
| `STORE_MESSAGE` | Lưu tin nhắn vào `offlineMessages[receiver]`, gửi `ACK` |
| `PEER_LEAVE` | Xóa peer, broadcast `PEER_LEAVE` đến tất cả |

**Luồng REGISTER chi tiết:**
```
1. Parse content = "localhost:5001"
2. Nếu username đã tồn tại → unregister trước (replace)
3. registry.register(peerInfo)
4. Gửi REGISTER_ACK với danh sách peer hiện tại
5. Giao offline messages (nếu có)
6. Nếu peer mới (không phải replace) → broadcastPeerJoin
```

### 2.5. `BootstrapDashboardServer.java` — HTTP Admin Dashboard

Sử dụng `com.sun.net.httpserver.HttpServer` (JDK built-in, không cần dependency).

| Endpoint | Mô tả |
|---|---|
| `GET /` | Trả về `static/index.html` (dashboard UI) |
| `GET /api/status` | JSON: host, ports, online count, offline msg count, running |
| `GET /api/peers` | JSON array toàn bộ `PeerInfo` |
| `GET /api/events` | JSON array 200 sự kiện gần nhất |

Dashboard tự động refresh mỗi **3 giây** (setInterval phía browser).

### 2.6. `BootstrapEventLog.java` — Hệ thống ghi log sự kiện

```java
ConcurrentLinkedDeque<EventEntry>  // thread-safe, LIFO
MAX_EVENTS = 200
```

Mỗi EventEntry: `{timestamp, level, type, peer, detail}`

Các level: `INFO`, `WARN`, `ERROR`

Các loại event ghi nhận:
- `SERVER_START / SERVER_STOP`
- `PEER_REMOVED / PEER_JOIN / PEER_LEAVE`
- `HEARTBEAT_TIMEOUT`
- `REGISTER / REGISTER_REPLACE / REGISTER_INVALID`
- `OFFLINE_DELIVERY / STORE_MESSAGE`
- `BROADCAST_FAILED`
- `MESSAGE_<TYPE>` — mỗi message nhận được

---

## 3. Hằng số cấu hình (`Constants.java`)

| Hằng số | Giá trị | Mô tả |
|---|---|---|
| `DEFAULT_BOOTSTRAP_PORT` | `9000` | Port TCP bootstrap |
| `DEFAULT_DASHBOARD_PORT` | `9001` | Port HTTP dashboard |
| `DEFAULT_PEER_PORT` | `5001` | Port TCP peer mặc định |
| `DEFAULT_WEB_PORT` | `3000` | Port Web UI peer mặc định |
| `HEARTBEAT_INTERVAL` | `5000 ms` | Peer gửi heartbeat mỗi 5 giây |
| `HEARTBEAT_TIMEOUT` | `15000 ms` | Peer bị coi dead nếu không heartbeat 15 giây |
| `ACK_TIMEOUT` | `5000 ms` | Timeout chờ ACK từ peer nhận |
| `MAX_RETRIES` | `3` | Số lần retry gửi message |
| `RECONNECT_INTERVAL` | `3000 ms` | (dự phòng) |
| `DELIMITER` | `\n` | Ký tự phân cách message (newline-delimited JSON) |
| `DB_EXTENSION` | `.db` | Đuôi file SQLite |

---

## 4. Giao thức truyền thông

### 4.1. Định dạng message (JSON over TCP, newline-delimited)

```json
{
  "type": "DIRECT_MESSAGE",
  "messageId": "uuid-v4",
  "sender": "alice",
  "receiver": "bob",
  "groupName": null,
  "content": "Xin chào!",
  "timestamp": 1715376000000
}
```

### 4.2. Tất cả MessageType

| Type | Hướng | Mô tả |
|---|---|---|
| `REGISTER` | Peer → Bootstrap | Đăng ký tham gia mạng |
| `REGISTER_ACK` | Bootstrap → Peer | Xác nhận + gửi peer list |
| `REGISTER_NACK` | Bootstrap → Peer | Từ chối đăng ký |
| `PEER_LIST` | Bootstrap → Peer | Danh sách peer online (đáp DISCOVER) |
| `PEER_JOIN` | Bootstrap → All | Thông báo peer mới vào mạng |
| `PEER_LEAVE` | Peer → Bootstrap / Bootstrap → All | Peer rời mạng |
| `HEARTBEAT` | Peer → Bootstrap | Keep-alive (mỗi 5s) |
| `HEARTBEAT_ACK` | Bootstrap → Peer | Xác nhận heartbeat + peer list mới |
| `DISCOVER` | Peer → Bootstrap | Yêu cầu danh sách peer |
| `DIRECT_MESSAGE` | Peer → Peer | Tin nhắn trực tiếp |
| `GROUP_MESSAGE` | Peer → Peers | Tin nhắn nhóm |
| `BROADCAST` | Peer → All Peers | Broadcast toàn mạng |
| `ACK` | Peer → Peer | Xác nhận nhận tin |
| `STORE_MESSAGE` | Peer → Bootstrap | Lưu tin cho peer offline |
| `OFFLINE_MESSAGE` | Bootstrap → Peer | Giao tin lưu khi peer online lại |
| `CREATE_GROUP` | (reserved) | Tạo nhóm |
| `ADD_TO_GROUP` | (reserved) | Thêm member vào nhóm |
| `ERROR` | Any | Thông báo lỗi |

---

## 5. Luồng hoạt động hệ thống

### 5.1. Peer tham gia mạng

```
Peer                     Bootstrap                  Other Peers
 |                           |                           |
 |------ REGISTER ---------->|                           |
 |       (host:port)         |                           |
 |                           |------- PEER_JOIN -------->|
 |<----- REGISTER_ACK -------|  (broadcast tới tất cả)  |
 |       (peer list)         |                           |
 |<----- OFFLINE_MSG* -------|  (nếu có tin nhắn tồn)  |
 |                           |                           |
 |------ HEARTBEAT (5s) ---->|                           |
 |<----- HEARTBEAT_ACK ------|                           |
 |       (peer list mới)     |                           |
```

### 5.2. Chat trực tiếp (Peer-to-Peer)

```
Alice                              Bob
  |                                 |
  |------- DIRECT_MESSAGE --------->|
  |        (TCP :5002)              | save SQLite
  |<------ ACK --------------------|
  |                                 |
```

Nếu Bob **offline** (TCP thất bại sau 3 retry):

```
Alice                    Bootstrap
  |                          |
  |---- STORE_MESSAGE ------>|  lưu vào offlineMessages[bob]
  |<--- ACK -----------------|
  |                          |
  ... (Bob online lại) ...   |
Bob                          |
  |---- REGISTER ----------->|
  |<--- REGISTER_ACK --------|
  |<--- OFFLINE_MESSAGE* ----|  (giao các tin đã lưu)
```

### 5.3. Peer rời mạng — graceful

```
Peer                     Bootstrap               Other Peers
 |------- PEER_LEAVE ------>|                        |
 |                          |------ PEER_LEAVE ------>|
 |                          |  (broadcast)            |
```

### 5.4. Peer crash — dead detection

```
Bootstrap (scheduler mỗi 5s)
  → checkDeadPeers(timeout=15s)
  → peer X: now - lastHeartbeat > 15000ms
  → removeDeadPeer("X")
  → broadcastPeerLeave("X")  → tất cả peer online
```

---

## 6. Peer Node — Chi tiết

### 6.1. `PeerApp.java` — Entry point

```bash
java -jar peer-node.jar --port 5001 --web 3000
```

### 6.2. `PeerNode.java` — Lớp chủ

Khởi tạo và kết nối:
```java
peerServer = new PeerServer(port, peerManager);   // TCP server nhận tin
peerClient = new PeerClient(peerManager);           // TCP client gửi tin
webServer  = new WebServer(webPort, ...);           // HTTP+WS cho browser
```

Khi `connectToBootstrap(username, host, bootstrapHost, bootstrapPort)`:
1. Gửi `REGISTER` đến bootstrap
2. Nhận `REGISTER_ACK` → parse peer list
3. Nhận offline messages (giao qua socket cùng phiên)
4. Nếu thành công → khởi động heartbeat thread

**Heartbeat thread (daemon):**
- Ngủ 5000ms giữa mỗi lần
- Gửi `HEARTBEAT` → nhận `HEARTBEAT_ACK` + peer list mới
- Nếu bootstrap không phản hồi → đánh dấu `registeredToBootstrap = false`

### 6.3. `PeerServer.java` — Nhận tin từ peer khác

Lắng nghe trên TCP port, thread pool, dispatch theo MessageType:

| Type nhận | Xử lý |
|---|---|
| `DIRECT_MESSAGE` | In ra console, lưu SQLite, push WebSocket, gửi ACK |
| `GROUP_MESSAGE` | In ra console, lưu SQLite, push WebSocket, gửi ACK |
| `BROADCAST` | In ra console, lưu SQLite, push WebSocket, gửi ACK |
| `PEER_JOIN` | Thêm peer mới vào `knownPeers`, push WebSocket |
| `PEER_LEAVE` | Đánh dấu peer offline, push WebSocket |
| `OFFLINE_MESSAGE` | Lưu SQLite, push WebSocket |
| `ACK` | Log nhận ACK |

### 6.4. `PeerClient.java` — Gửi tin đến peer khác

**Gửi trực tiếp với retry:**
```
for attempt = 1..3:
    sendSingle(message, peer)  — mở TCP, gửi JSON, đọc ACK
    if ACK nhận được trong 5s → return true
    else → retry
after 3 fail → storeOfflineMessage(bootstrap)
```

**Gửi broadcast:** Không retry, gửi đến tất cả online peers.

### 6.5. `WebServer.java` — HTTP + WebSocket (Javalin)

| Method | Route | Mô tả |
|---|---|---|
| GET | `/api/info` | Thông tin peer: username, host, port, bootstrap, registered |
| GET | `/api/peers` | Danh sách peer online |
| GET | `/api/discover` | Gọi bootstrap DISCOVER, cập nhật peer list |
| GET | `/api/history/{peer}` | Lịch sử chat 1-1 từ SQLite |
| GET | `/api/group-history/{group}` | Lịch sử chat nhóm |
| GET | `/api/groups` | Danh sách nhóm |
| POST | `/api/register` | Kết nối vào bootstrap (từ Web UI) |
| POST | `/api/msg` | Gửi DIRECT_MESSAGE |
| POST | `/api/broadcast` | Gửi BROADCAST |
| POST | `/api/group/create` | Tạo nhóm |
| POST | `/api/group/add` | Thêm peer vào nhóm |
| POST | `/api/group/msg` | Gửi GROUP_MESSAGE |
| WS | `/ws` | Nhận realtime events (DIRECT_MESSAGE, PEER_JOIN, ...) |

### 6.6. `PeerManager.java` — State manager của peer

```
ConcurrentHashMap<String, PeerInfo>    knownPeers   — peers đã biết
ConcurrentHashMap<String, ChatGroup>   chatGroups   — nhóm chat
DatabaseManager                        dbManager
MessageRepository                      messageRepo
```

`parsePeerList(str)` — parse `"alice@localhost:5001,bob@localhost:5002"`:
- Đặt tất cả peer hiện tại `online=false`
- Thêm/cập nhật từng peer trong list mới (trừ bản thân)

### 6.7. Database (SQLite — WAL mode)

- File: `data/peer-<port>.db` (tạo tự động)
- `DatabaseInitializer` tạo bảng `messages`
- `MessageRepository` cung cấp: `saveMessage()`, `getChatHistory()`, `getGroupHistory()`

---

## 7. Cấu hình Churn Test (`churn-test.ps1`)

Script PowerShell mô phỏng **churn** — tình huống peer liên tục tham gia/rời mạng đột ngột để kiểm tra tính bền vững của hệ thống.

### 7.1. Tham số

| Tham số | Mặc định | Mô tả |
|---|---|---|
| `-PeerCount` | `6` | Tổng số peer trong pool |
| `-InitialPeers` | `3` | Số peer khởi động ở round đầu |
| `-TotalRounds` | `12` | Số vòng churn |
| `-RoundIntervalSec` | `6` | Thời gian nghỉ giữa các round |
| `-BootstrapPort` | `9000` | Port TCP bootstrap |
| `-DashboardPort` | `9001` | Port HTTP dashboard |
| `-BasePeerPort` | `5001` | Port TCP bắt đầu cho peer |
| `-BaseWebPort` | `3000` | Port Web UI bắt đầu cho peer |
| `-StartBootstrap` | `$true` | Script tự khởi động bootstrap |
| `-KeepBootstrapRunning` | off | Giữ bootstrap sau khi test |
| `-KeepPeersRunning` | off | Giữ peers sau khi test |

### 7.2. Cách chạy

```powershell
# Mặc định (cần build jar trước)
powershell -ExecutionPolicy Bypass -File .\churn-test.ps1

# Tùy chỉnh
powershell -ExecutionPolicy Bypass -File .\churn-test.ps1 `
  -PeerCount 6 -InitialPeers 3 -TotalRounds 10 -RoundIntervalSec 5
```

### 7.3. Logic một round churn

```
Round N:
  1. Lấy danh sách active peers và inactive peers
  2. CRASH: Nếu active > 1 → kill ngẫu nhiên 1–3 peer (Stop-Process -Force)
  3. Chờ RoundIntervalSec / 2
  4. JOIN: Nếu có inactive peer → start ngẫu nhiên 1–3 peer
     - Start JVM process (peer-node.jar)
     - Đợi Web UI sẵn sàng (HTTP /api/info)
     - POST /api/register → kết nối vào bootstrap
  5. In tóm tắt (active/inactive)
  6. Chờ RoundIntervalSec
```

**Crash** ở đây là **kill process đột ngột** (không graceful) — peer bị xóa khỏi registry sau 15s qua heartbeat timeout.

### 7.4. Port allocation

Peer thứ `i` (i=1..PeerCount):
- TCP port = `BasePeerPort + i - 1` (ví dụ: peer01=5001, peer02=5002...)
- Web port = `BaseWebPort + i - 1` (ví dụ: peer01=3000, peer02=3001...)

### 7.5. Log output

- Thư mục: `churn-logs/`
- Bootstrap: `bootstrap-<timestamp>.out.log` / `.err.log`
- Mỗi peer: `peer0N-<timestamp>.out.log` / `.err.log`

### 7.6. Quan sát kết quả churn

Mở **Bootstrap Dashboard**: `http://localhost:9001`

- **Known Peers table**: Xem peer nào online/offline, lastHeartbeat
- **Realtime Event Log**: Xem `PEER_JOIN`, `PEER_LEAVE`, `HEARTBEAT_TIMEOUT`, `PEER_REMOVED` theo thời gian thực (refresh 3s)

---

## 8. Hướng dẫn khởi động

### 8.1. Yêu cầu

| Công cụ | Phiên bản |
|---|---|
| Java JDK | 17+ |
| Maven | 3.9+ |
| Node.js | 18+ (chỉ cần build React) |
| Docker | 20+ (nếu dùng Docker) |

### 8.2. Cách 1 — Thủ công (Windows/Linux)

```bash
# Bước 1: Build React UI
cd peer-web && npm install && npm run build
cp -r build ../peer-node/src/main/resources/static

# Bước 2: Build Bootstrap Server
cd ../bootstrap-server && mvn clean package -DskipTests

# Bước 3: Build Peer Node
cd ../peer-node && mvn clean package -DskipTests

# Bước 4: Khởi động Bootstrap Server
java -jar bootstrap-server/target/bootstrap-server.jar
# hoặc tùy chỉnh port:
java -jar bootstrap-server/target/bootstrap-server.jar --port 9000 --dashboard-port 9001

# Bước 5: Khởi động Peer (mỗi peer 1 terminal)
java -jar peer-node/target/peer-node.jar --port 5001 --web 3000
java -jar peer-node/target/peer-node.jar --port 5002 --web 3001
java -jar peer-node/target/peer-node.jar --port 5003 --web 3002
```

Sau khi peer chạy, mở `http://localhost:3000` → điền `username`, `host`, `bootstrapHost`, `bootstrapPort` → bấm **Connect**.

### 8.3. Cách 2 — Docker (Linux/WSL)

```bash
# Build images
chmod +x run.sh && ./run.sh build

# Khởi động hệ thống (alice + bob)
./run.sh start

# Thêm peer bất kỳ lúc nào
./run.sh peer charlie

# Xem danh sách peer + URL
./run.sh list

# Xem log
./run.sh logs alice
./run.sh logs s        # log bootstrap

# Dừng tất cả
./run.sh stop
```

### 8.4. Chạy Churn Test

```powershell
# Bước 0: Build jars trước
cd bootstrap-server; mvn clean package -DskipTests
cd ..\peer-node; mvn clean package -DskipTests; cd ..

# Bước 1: Chạy churn test
powershell -ExecutionPolicy Bypass -File .\churn-test.ps1 `
  -PeerCount 6 -InitialPeers 3 -TotalRounds 12 -RoundIntervalSec 6

# Bước 2: Theo dõi dashboard
# Mở trình duyệt: http://localhost:9001
```

---

## 9. Sơ đồ class chính

```
BootstrapApp
  └── BootstrapServer
        ├── PeerRegistry          (ConcurrentHashMap)
        ├── BootstrapEventLog     (ConcurrentLinkedDeque)
        └── ClientHandler (per connection thread)
              └── ProtocolHandler / JsonUtil

  └── BootstrapDashboardServer    (com.sun.net.httpserver)
        └── /api/status, /api/peers, /api/events


PeerApp
  └── PeerNode
        ├── PeerManager
        │     ├── ConcurrentHashMap<PeerInfo>
        │     ├── ConcurrentHashMap<ChatGroup>
        │     └── MessageRepository → DatabaseManager (SQLite)
        ├── PeerServer   (TCP accept loop)
        ├── PeerClient   (TCP connect + retry + ACK)
        └── WebServer    (Javalin: HTTP REST + WebSocket)
```

---

## 10. Tóm tắt tham số quan trọng

| Tham số | Mặc định | Ghi chú |
|---|---|---|
| Bootstrap TCP port | `9000` | `--port` |
| Dashboard HTTP port | `9001` | `--dashboard-port` |
| Peer TCP port | `5001` | `--port` |
| Peer Web port | `3000` | `--web` |
| Heartbeat gửi mỗi | `5s` | `HEARTBEAT_INTERVAL` |
| Timeout peer dead | `15s` | `HEARTBEAT_TIMEOUT` |
| Timeout chờ ACK | `5s` | `ACK_TIMEOUT` |
| Số lần retry | `3` | `MAX_RETRIES` |
| Event log giữ | `200` mục | `MAX_EVENTS` |
| Dashboard refresh | `3s` | JavaScript setInterval |
