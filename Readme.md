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
| Maven | - | Build & quản lý dependency |
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

## 9. Hướng dẫn chạy

### 9.1. Yêu cầu

- Docker đã cài đặt

### 9.2. Chạy bằng `run.sh` (Khuyến nghị)

```bash
# Build images (chạy 1 lần, ~5 phút đầu tiên)
./run.sh build

# Khởi động bootstrap + 2 peer mặc định (alice, bob)
./run.sh start

# Hoặc khởi động peer riêng lẻ với tên bất kỳ (port ngẫu nhiên)
./run.sh peer alice
./run.sh peer bob
./run.sh peer charlie

# Xem danh sách peer đang chạy + URL Web UI
./run.sh list

# Xem log
./run.sh logs alice     # log peer alice
./run.sh logs bob       # log peer bob
./run.sh logs s         # log bootstrap server

# Dừng tất cả
./run.sh stop

# Khởi động lại
./run.sh restart
```

### 9.3. Chạy thủ công bằng Docker

```bash
# Build
docker build -t p2pchat-bootstrap -f bootstrap-server/Dockerfile bootstrap-server/
docker build -f Dockerfile -t p2pchat-peer .

# Tạo network
docker network create p2p-net

# Start bootstrap
docker run -d --name bootstrap --network p2p-net -p 8080:8080 p2pchat-bootstrap

# Start peer (thay port tùy ý)
docker run -d --name peer-alice --network p2p-net \
  -p 5001:5001 -p 3000:3000 p2pchat-peer \
  --username alice --host peer-alice --port 5001 --bootstrap bootstrap:8080 --web 3000

docker run -d --name peer-bob --network p2p-net \
  -p 5002:5002 -p 3001:3001 p2pchat-peer \
  --username bob --host peer-bob --port 5002 --bootstrap bootstrap:8080 --web 3001
```

### 9.4. CLI Chat (trong container)

```bash
docker attach peer-alice
# Sau đó gõ:
# /peers                    - Hiển thị danh sách peer online
# /msg bob Xin chào!        - Gửi tin nhắn trực tiếp
# /group create group1      - Tạo nhóm chat
# /group add group1 bob     - Thêm peer vào nhóm
# /group msg group1 Hello   - Gửi tin nhắn nhóm
# /broadcast Hello all!     - Phát tin nhắn toàn mạng
# /history bob              - Xem lịch sử chat
# /discover                 - Refresh peer list
# /exit                     - Thoát
```

### 9.5. CLI Flags

| Flag | Mặc định | Mô tả |
|---|---|---|
| `--username` | `peer` | Tên hiển thị |
| `--port` | `5001` | Port TCP P2P |
| `--host` | `localhost` | Hostname/IP quảng bá |
| `--bootstrap` | `localhost:8080` | Địa chỉ bootstrap server |
| `--web` | `3000` | Port Web UI (HTTP + WebSocket) |

## 10. Chức năng đã triển khai

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

## 11. Tác giả

Đồ án môn Hệ thống Phân tán.
