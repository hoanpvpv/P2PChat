# P2PChat - Hệ thống Chat Ngang hàng (Peer-to-Peer Chat System)

## 1. Giới thiệu

P2PChat là một hệ thống chat ngang hàng (P2P) được xây dựng bằng Java, cho phép nhiều người dùng trao đổi tin nhắn trực tiếp qua mạng mà không phụ thuộc hoàn toàn vào server trung tâm. Mỗi peer vừa đóng vai trò client vừa là server. Hệ thống bao gồm giao diện Web (React) và CLI.

## 2. Kiến trúc hệ thống

```
┌─────────────────────────────────────────────────────────┐
│                   Bootstrap Server                       │
│            (Peer Discovery & Registry)                   │
│                  Port: 8080                              │
└────────┬──────────┬──────────┬──────────┬───────────────┘
         │          │          │          │
    ┌────▼───┐ ┌───▼────┐ ┌──▼───┐ ┌───▼────┐
    │ Peer A │ │ Peer B │ │Peer C│ │ Peer D │
    │ :random│ │ :random│ │:rand │ │ :random│
    │ Web:UI │ │ Web:UI │ │Web:UI│ │ Web:UI │
    └────┬───┘ └───┬────┘ └──┬───┘ └───┬────┘
         │         │         │         │
         └─────────┴────┬────┴─────────┘
                        │
              P2P TCP Direct Connection
```

### Các thành phần chính

| Thành phần | Vai trò |
|---|---|
| **Bootstrap Server** | Đăng ký peer, cung cấp danh sách peer online, hỗ trợ peer discovery, lưu offline messages |
| **Peer Node** | Vừa là client gửi tin nhắn, vừa là server nhận tin nhắn, kèm Web UI (Javalin + React) |
| **Database (SQLite)** | Lưu trữ lịch sử tin nhắn cục bộ tại mỗi peer (WAL mode cho thread safety) |
| **Web UI (React)** | Giao diện chat trên trình duyệt, kết nối qua REST API + WebSocket |

## 3. Cấu trúc dự án

```
P2PChat/
├── Dockerfile                 # Multi-stage build: React → Maven → JRE
├── .dockerignore
├── build.sh                   # Script build thủ công (không dùng Docker)
├── run.sh                     # Script quản lý Docker (build/start/stop/peer/list)
├── Readme.md
├── WORKLOG.md
│
├── bootstrap-server/          # Bootstrap Server
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/mycompany/p2pchat/
│       ├── bootstrap/
│       │   ├── BootstrapApp.java             # Entry point
│       │   ├── BootstrapServer.java          # Server chính
│       │   ├── ClientHandler.java            # Xử lý kết nối từ peer
│       │   └── PeerRegistry.java             # Quản lý danh sách peer
│       ├── model/
│       ├── protocol/
│       └── utils/
│
├── peer-node/                 # Peer Node
│   ├── Dockerfile             # Simple Java-only build (không kèm React)
│   ├── pom.xml                # Maven + Javalin + SQLite + Gson + SLF4J
│   └── src/main/java/com/mycompany/p2pchat/
│       ├── peer/
│       │   ├── PeerApp.java                  # Entry point, parse CLI args
│       │   ├── PeerNode.java                 # Lớp chính: khởi động, CLI, heartbeat
│       │   ├── PeerServer.java               # TCP server nhận tin nhắn
│       │   ├── PeerClient.java               # TCP client gửi tin nhắn, retry, ACK
│       │   ├── PeerManager.java              # Quản lý peer list, groups, database
│       │   └── WebServer.java                # Javalin HTTP/WS server cho Web UI
│       ├── database/
│       │   ├── DatabaseManager.java           # Quản lý SQLite (WAL mode)
│       │   ├── DatabaseInitializer.java       # Khởi tạo schema
│       │   └── MessageRepository.java         # CRUD tin nhắn, lịch sử chat
│       ├── model/
│       ├── protocol/
│       └── utils/
│
└── peer-web/                  # React Web UI
    ├── package.json
    └── src/
        ├── api.js                            # REST API + WebSocket client
        ├── App.jsx                           # Main component
        ├── App.css                           # Styles
        └── components/
            ├── Sidebar.jsx                   # Peer list, group list, add member
            ├── ChatArea.jsx                  # Message display
            └── MessageInput.jsx              # Message input
```

## 4. Giao thức trao đổi thông điệp

### 4.1. Định dạng message

Mọi message được truyền qua TCP dưới dạng JSON (newline-delimited):

```json
{
  "type": "DIRECT_MESSAGE",
  "sender": "peerA",
  "receiver": "peerB",
  "content": "Xin chào!",
  "timestamp": 1715376000000,
  "messageId": "uuid-string"
}
```

### 4.2. Các loại message (MessageType)

| Type | Hướng | Mô tả |
|---|---|---|
| `REGISTER` | Peer → Bootstrap | Đăng ký peer mới vào mạng |
| `REGISTER_ACK` | Bootstrap → Peer | Xác nhận đăng ký + peer list |
| `PEER_LIST` | Bootstrap → Peer | Danh sách peer đang online |
| `PEER_JOIN` | Bootstrap → All Peers | Thông báo peer mới tham gia |
| `PEER_LEAVE` | Peer → Bootstrap | Thông báo peer rời mạng |
| `DIRECT_MESSAGE` | Peer → Peer | Tin nhắn trực tiếp |
| `GROUP_MESSAGE` | Peer → Peers | Tin nhắn nhóm |
| `BROADCAST` | Peer → All Peers | Tin nhắn phát tới toàn mạng |
| `ACK` | Peer → Peer | Xác nhận đã nhận tin nhắn |
| `HEARTBEAT` | Peer → Bootstrap | Kiểm tra kết nối còn sống (5s) |
| `HEARTBEAT_ACK` | Bootstrap → Peer | Xác nhận heartbeat + peer list |
| `DISCOVER` | Peer → Bootstrap | Yêu cầu cập nhật danh sách peer |
| `OFFLINE_MESSAGE` | Bootstrap → Peer | Chuyển tiếp tin nhắn lưu trữ |
| `STORE_MESSAGE` | Peer → Bootstrap | Lưu tin nhắn cho peer offline |

### 4.3. Sequence Diagram - Peer tham gia mạng

```
Peer A                    Bootstrap Server                  Peer B, C, ...
  │                              │                              │
  │──── REGISTER ───────────────>│                              │
  │                              │──── PEER_JOIN ──────────────>│
  │<─── REGISTER_ACK ───────────│                              │
  │<─── PEER_LIST ──────────────│                              │
  │                              │                              │
  │──── HEARTBEAT (5s) ────────>│                              │
  │<─── HEARTBEAT_ACK + list ───│                              │
```

### 4.4. Sequence Diagram - Chat trực tiếp

```
Peer A                    Peer B
  │                         │
  │──── DIRECT_MESSAGE ────>│
  │                         │ (save to SQLite)
  │<─── ACK ────────────────│
  │                         │
```

## 5. Cơ chế Peer Discovery

1. **Bootstrap-based discovery**: Peer mới gửi `REGISTER` đến Bootstrap Server để nhận danh sách peer hiện tại.
2. **Heartbeat**: Peer gửi `HEARTBEAT` định kỳ (5s) đến Bootstrap để duy trì trạng thái online.
3. **Push notification**: Khi peer mới tham gia hoặc rời mạng, Bootstrap chủ động đẩy `PEER_JOIN`/`PEER_LEAVE` đến tất cả peer đang online.
4. **On-demand discovery**: Peer có thể gửi `DISCOVER` bất kỳ lúc nào để cập nhật danh sách peer.

## 6. Truyền tin đáng tin cậy (Reliability)

- **ACK mechanism**: Mọi tin nhắn (DIRECT_MESSAGE, GROUP_MESSAGE) đều yêu cầu ACK từ người nhận. Timeout 5s, retry tối đa 3 lần.
- **Offline message store**: Nếu peer đích offline, tin nhắn được lưu tại Bootstrap. Khi peer online lại, Bootstrap chuyển tiếp tin nhắn lưu trữ.
- **Local persistence**: Mọi tin nhắn (gửi/nhận) đều được lưu vào SQLite ngay lập tức, kể cả khi gửi thất bại.

## 7. Công nghệ sử dụng

| Công nghệ | Phiên bản | Mục đích |
|---|---|---|
| Java | 17+ | Ngôn ngữ lập trình chính |
| Java TCP Socket | - | Giao tiếp mạng giữa các peer |
| Javalin | 6.1.3 | HTTP/WebSocket server cho Web UI |
| React | 18 | Frontend Web UI |
| SQLite | 3.45.1 | Lưu trữ cục bộ tại mỗi peer (WAL mode) |
| Gson | 2.10.1 | Serialize/Deserialize JSON |
| Maven | 3.9+ | Build & quản lý dependency |
| Docker | - | Containerized deployment |

## 8. Web UI API

| Method | Route | Mô tả |
|---|---|---|
| GET | `/api/info` | Thông tin peer hiện tại |
| GET | `/api/peers` | Danh sách peer online |
| GET | `/api/discover` | Refresh peer list từ bootstrap |
| GET | `/api/history/{peerName}` | Lịch sử chat trực tiếp |
| GET | `/api/group-history/{groupName}` | Lịch sử chat nhóm |
| GET | `/api/groups` | Danh sách nhóm |
| POST | `/api/msg` | Gửi tin nhắn trực tiếp |
| POST | `/api/broadcast` | Phát tin nhắn toàn mạng |
| POST | `/api/group/create` | Tạo nhóm |
| POST | `/api/group/add` | Thêm peer vào nhóm |
| POST | `/api/group/msg` | Gửi tin nhắn nhóm |
| WS | `/ws` | WebSocket realtime events |

## 9. Hướng dẫn cài đặt và chạy

### 9.1. Yêu cầu hệ thống

#### Cách 1 — Dùng Docker (khuyến nghị)

| Yêu cầu | Ghi chú |
|---|---|
| **Docker** | >= 20.x |
| **Docker Compose** (tùy chọn) | Không bắt buộc, dự án dùng script `run.sh` |

#### Cách 2 — Chạy thủ công (không dùng Docker)

| Yêu cầu | Phiên bản | Cài đặt |
|---|---|---|
| **Java JDK** | 17+ | `sudo apt install openjdk-17-jdk` (Ubuntu/Debian) hoặc tải từ [Adoptium](https://adoptium.net/) |
| **Maven** | 3.9+ | `sudo apt install maven` hoặc tải từ [maven.apache.org](https://maven.apache.org/download.cgi) |
| **Node.js + npm** | 18+ / 9+ | `sudo apt install nodejs npm` hoặc dùng [nvm](https://github.com/nvm-sh/nvm) |

Kiểm tra phiên bản:

```bash
java -version      # >= 17
mvn -version       # >= 3.9
node -v            # >= 18
npm -v             # >= 9
```

---

### 9.2. Cách 1: Chạy bằng Docker (Khuyến nghị)

#### Bước 1: Clone repository

```bash
git clone <repo-url> P2PChat
cd P2PChat
```

#### Bước 2: Build Docker images

```bash
chmod +x run.sh
./run.sh build
```

Lệnh này sẽ build 2 images:
- `p2pchat-bootstrap` — Bootstrap Server
- `p2pchat-peer` — Peer Node (kèm React Web UI được build sẵn)

Lần đầu build sẽ mất khoảng 3-5 phút để download dependencies.

#### Bước 3: Khởi động hệ thống

```bash
# Khởi động bootstrap server + 2 peer mặc định (alice, bob)
./run.sh start

# Hoặc chỉ định tên peer
./run.sh start alice bob

# Hoặc thêm peer mới bất kỳ lúc nào
./run.sh peer charlie
./run.sh peer dave
```

#### Bước 4: Truy cập Web UI

```bash
# Xem danh sách peer đang chạy + URL Web UI
./run.sh list
```

Mở trình duyệt tại URL hiển thị, ví dụ:
- Peer alice: `http://localhost:30001` (port ngẫu nhiên)
- Peer bob: `http://localhost:30002` (port ngẫu nhiên)

#### Bước 5: Chat!

Trong Web UI của mỗi peer:
1. Sidebar trái hiển thị danh sách peer online và nhóm
2. Click vào tên peer để chat trực tiếp
3. Dùng nút tạo nhóm và thêm member
4. Nhập tin nhắn và gửi

#### Các lệnh quản lý khác

```bash
./run.sh list               # Xem danh sách peer + URL Web UI
./run.sh logs alice         # Xem log peer alice
./run.sh logs bob           # Xem log peer bob
./run.sh logs s             # Xem log bootstrap server
./run.sh stop               # Dừng tất cả container
./run.sh restart            # Khởi động lại tất cả
```

---

### 9.3. Cách 2: Chạy thủ công (Local, không dùng Docker)

#### Bước 1: Clone repository

```bash
git clone <repo-url> P2PChat
cd P2PChat
```

#### Bước 2: Build React Frontend

```bash
cd peer-web
npm install
npm run build
```

Kết quả: thư mục `peer-web/build/` chứa static files.

#### Bước 3: Copy React build vào Peer Node resources

```bash
cd /path/to/P2PChat
rm -rf peer-node/src/main/resources/static
cp -r peer-web/build peer-node/src/main/resources/static
```

#### Bước 4: Build Bootstrap Server

```bash
cd /path/to/P2PChat/bootstrap-server
mvn clean package -DskipTests
```

Kết quả: `bootstrap-server/target/bootstrap-server.jar`

#### Bước 5: Build Peer Node

```bash
cd /path/to/P2PChat/peer-node
mvn clean package -DskipTests
```

Kết quả: `peer-node/target/peer-node.jar`

> **Tip:** Có thể dùng script `build.sh` ở root để tự động hóa Bước 2–5:
> ```bash
> chmod +x build.sh
> ./build.sh
> ```

#### Bước 6: Khởi động Bootstrap Server

```bash
cd /path/to/P2PChat
java -jar bootstrap-server/target/bootstrap-server.jar
```

Bootstrap server chạy mặc định trên port `9000`, và dashboard web admin chạy trên `http://localhost:9001`. Giữ terminal này mở.

#### Bước 7: Khởi động Peer Node

Mở terminal mới cho mỗi peer:

```bash
# Terminal 2 — Peer alice
java -jar peer-node/target/peer-node.jar \
  --port 5001 \
  --web 3000

# Terminal 3 — Peer bob
java -jar peer-node/target/peer-node.jar \
  --port 5002 \
  --web 3001

# Terminal 4 — Peer charlie
java -jar peer-node/target/peer-node.jar \
  --port 5003 \
  --web 3002
```

Sau khi peer chạy, mở Web UI của peer và nhập:
- `username`
- `host`
- `bootstrap host`
- `bootstrap port` (mặc định `9000`)

Rồi bấm `Connect Peer` để đăng ký vào mạng.

#### Bước 8: Truy cập Web UI

Mở trình duyệt:
- Peer alice: `http://localhost:3000`
- Peer bob: `http://localhost:3001`
- Peer charlie: `http://localhost:3002`

---

### 9.4. CLI Chat (trong terminal peer)

Nếu chạy thủ công (không Docker), CLI chat có sẵn ngay trong terminal chạy peer. Gõ các lệnh sau:

| Lệnh | Mô tả |
|---|---|
| `/peers` | Hiển thị danh sách peer online |
| `/msg <tên> <nội dung>` | Gửi tin nhắn trực tiếp |
| `/group create <tên nhóm>` | Tạo nhóm chat |
| `/group add <tên nhóm> <tên peer>` | Thêm peer vào nhóm |
| `/group msg <tên nhóm> <nội dung>` | Gửi tin nhắn nhóm |
| `/broadcast <nội dung>` | Phát tin nhắn toàn mạng |
| `/history <tên>` | Xem lịch sử chat |
| `/discover` | Refresh peer list |
| `/exit` | Thoát |

Nếu chạy bằng Docker, dùng `docker attach` để vào CLI:

```bash
docker attach peer-alice
# Gõ lệnh CLI như trên
# Thoát attach: Ctrl+P, Ctrl+Q (không dùng Ctrl+C sẽ dừng container)
```

### 9.5. CLI Flags

| Flag | Mặc định | Mô tả |
|---|---|---|
| `--port` | `5001` | Port TCP P2P |
| `--web` | `3000` | Port Web UI (HTTP + WebSocket) |

---

### 9.6. Chạy thủ công bằng Docker (không dùng run.sh)

```bash
# Build images
docker build -t p2pchat-bootstrap -f bootstrap-server/Dockerfile bootstrap-server/
docker build -f Dockerfile -t p2pchat-peer .

# Tạo network
docker network create p2p-net

# Start bootstrap
docker run -d --name bootstrap --network p2p-net -p 8080:8080 p2pchat-bootstrap

# Start peer
docker run -d --name peer-alice --network p2p-net \
  -p 5001:5001 -p 3000:3000 p2pchat-peer \
  --username alice --host peer-alice --port 5001 --bootstrap bootstrap:8080 --web 3000

docker run -d --name peer-bob --network p2p-net \
  -p 5002:5002 -p 3001:3001 p2pchat-peer \
  --username bob --host peer-bob --port 5002 --bootstrap bootstrap:8080 --web 3001
```

## 10. Xử lý lỗi thường gặp

| Lỗi | Nguyên nhân | Cách fix |
|---|---|---|
| `java: command not found` | Chưa cài Java 17+ | Cài JDK 17 và thêm vào PATH |
| `mvn: command not found` | Chưa cài Maven | Cài Maven 3.9+ |
| `npm: command not found` | Chưa cài Node.js | Cài Node.js 18+ |
| `Port 8080 already in use` | Bootstrap server port bị chiếm | Dừng process chiếm port hoặc đổi port |
| `Port 5001 already in use` | Peer port bị chiếm | Dùng port khác qua `--port` |
| `Connection refused` | Peer không kết nối được Bootstrap | Kiểm tra Bootstrap đang chạy và `--bootstrap` đúng địa chỉ |
| `Web UI không load` | Chưa build React hoặc chưa copy static files | Chạy lại `build.sh` hoặc build thủ công Bước 2–3 |
| Docker build chậm | Lần đầu download dependencies | Bình thường, chờ 3-5 phút |

## 11. Chức năng đã triển khai

- [x] Bootstrap-based peer discovery
- [x] Heartbeat + dead peer detection
- [x] Chat trực tiếp (DIRECT_MESSAGE)
- [x] Chat nhóm (GROUP_MESSAGE)
- [x] Broadcast message trong toàn mạng
- [x] Store-and-forward messaging khi peer offline
- [x] ACK + retry (3 lần, timeout 5s)
- [x] SQLite lưu lịch sử tin nhắn (WAL mode)
- [x] Giao diện Web UI (React + Javalin)
- [x] REST API + WebSocket realtime
- [x] Thêm member vào group qua Web UI
- [x] Docker containerized deployment
- [x] Script `run.sh` quản lý peer với port ngẫu nhiên


## 11. Churn Test Tự Động

Dự án có sẵn script PowerShell `churn-test.ps1` để mô phỏng churn:
- peer tham gia mạng
- peer crash/rời mạng đột ngột
- peer quay lại mạng

#### Cách chạy nhanh

Chạy từ thư mục gốc `P2PChat`:

```powershell
powershell -ExecutionPolicy Bypass -File .\churn-test.ps1
```

Script sẽ tự:
- khởi động `bootstrap-server`
- khởi động nhiều `peer-node`
- gọi API `/api/register` để peer tự kết nối vào bootstrap
- lặp các vòng crash/join lại
- ghi log vào thư mục `churn-logs`

#### Ví dụ có tham số

```powershell
powershell -ExecutionPolicy Bypass -File .\churn-test.ps1 `
  -PeerCount 6 `
  -InitialPeers 3 `
  -TotalRounds 10 `
  -RoundIntervalSec 5
```

Ý nghĩa tham số:

| Tham số | Mô tả |
|---|---|
| `-PeerCount` | Tổng số peer dùng trong bài test |
| `-InitialPeers` | Số peer khởi động ban đầu |
| `-TotalRounds` | Số vòng churn |
| `-RoundIntervalSec` | Thời gian nghỉ giữa các vòng |
| `-BootstrapPort` | Port bootstrap server |
| `-DashboardPort` | Port dashboard bootstrap |
| `-BasePeerPort` | Port bắt đầu cho peer TCP |
| `-BaseWebPort` | Port bắt đầu cho Web UI peer |
| `-StartBootstrap:$false` | Dùng bootstrap đã chạy sẵn thay vì để script tự mở |
| `-KeepBootstrapRunning` | Giữ bootstrap chạy sau khi test xong |
| `-KeepPeersRunning` | Giữ peer chạy sau khi test xong |

#### Quan sát kết quả ở đâu

- Dashboard bootstrap: `http://localhost:9001`
- Bảng `Known Peers`
- Bảng `Realtime Event Log`
- Log file trong thư mục `churn-logs`
## 12. Tác giả

Đồ án môn Hệ thống Phân tán.
