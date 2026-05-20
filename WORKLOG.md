# P2PChat - Nhật ký làm việc

## Phiên 1: Xây dựng nền tảng (11/05/2026)

### Việc đã hoàn thành

- Tạo README.md mô tả kiến trúc, giao thức, sequence diagram
- Tạo toàn bộ source code Java (17+ file): model, protocol, database, bootstrap, peer, utils
- Tạo resources: `application.properties`, `schema.sql`
- Cấu hình Maven shade plugin tạo fat JAR
- Build thành công

### Fix 3 bug nghiêm trọng sau test

| Bug | Fix |
|---|---|
| Peer bị xóa ngay sau REGISTER | Bỏ cleanup unregister, chỉ xóa khi heartbeat timeout hoặc PEER_LEAVE |
| Heartbeat không cập nhật peer list | HEARTBEAT_ACK kèm peer list string |
| Peer /exit không báo Bootstrap | Thêm `notifyBootstrapLeave()` gửi PEER_LEAVE |

---

## Phiên 2: Thêm Web UI + Docker (11/05/2026)

### Việc đã hoàn thành

- Tạo React app (`peer-web/`) với đầy đủ components: Sidebar, ChatArea, MessageInput
- Tạo `WebServer.java` (Javalin): REST API + WebSocket + static file serving
- Cập nhật `PeerNode.java` tích hợp WebServer
- Cập nhật `PeerServer.java` push events đến WebServer
- Cập nhật `PeerApp.java` thêm flag `--web`
- Cập nhật `pom.xml` thêm Javalin + SLF4J dependencies
- Tạo root `Dockerfile` multi-stage build: React → Maven → JRE
- Tạo `run.sh` script quản lý Docker

### Fix bug

| Bug | Nguyên nhân | Fix |
|---|---|---|
| `npm ci` fail (no package-lock.json) | Dockerfile dùng `npm ci` cần lockfile | Đổi thành `npm install` |
| Docker image chứa sai JAR | Cache cũ, build bootstrap thay vì peer | Rebuild `--no-cache` |
| Web UI trắng, API trả 500 | `ctx.json()` dùng Jackson, shade plugin gây xung đột | Đổi sang `ctx.result(gson.toJson(...))` |
| WebSocket connect/disconnect loop | `activeChat` trong useEffect deps + reconnect không kiểm soát | Dùng ref cho activeChat, thêm `stopReconnect()` |

---

## Phiên 3: Cải thiện trải nghiệm (11/05/2026)

### Việc đã hoàn thành

- Cập nhật `run.sh` hỗ trợ peer chạy port ngẫu nhiên, chỉ cần nhập username
- Thêm lệnh `./run.sh peer <name>`, `./run.sh list`
- Thêm UI thêm member vào group (dropdown + nút +)
- Thêm CSS cho group members panel

---

## Phiên 4: Fix SQLite persistence (11/05/2026)

### 6 bug đã sửa

| Bug | File | Fix |
|---|---|---|
| Gửi tin nhắn chỉ lưu DB khi nhận được ACK | `PeerClient.java` | Lưu vào DB ngay khi gửi |
| Broadcast không lưu DB (gửi + nhận) | `PeerClient.java` + `PeerServer.java` | Thêm `saveMessage()` cả 2 bên |
| Offline message nhận không lưu DB | `PeerServer.java` | Thêm `saveMessage()` |
| SQL `OR`/`AND` sai precedence trong `getChatHistory` | `MessageRepository.java` | Thêm ngoặc bao `OR` clause |
| Thread-unsafe DB connection | `DatabaseManager.java` | Thêm `PRAGMA journal_mode=WAL` |
| Duplicate `saveMessage` trong `sendWithRetry` | `PeerClient.java` | Bỏ save trong sendWithRetry (đã save trước) |

---

## Phiên 5: Cải thiện trải nghiệm UI/UX và đồng bộ nhóm (11/05/2026)

### Việc đã hoàn thành

- Đồng bộ đầy đủ dữ liệu nhóm (thông tin + lịch sử chat) khi thêm member mới, đảm bảo member mới thấy tin nhắn hệ thống báo hiệu lúc mới tạo nhóm
- Thêm tin nhắn SYSTEM để thông báo mỗi khi group được tạo thành công
- Cập nhật giao diện:
  - Bỏ text-align center cho broadcast message để hiển thị tự nhiên hơn giống tin nhắn chat thông thường
  - Khắc phục lỗi network khiến các máy trạm không hiển thị thống nhất số lượng nhóm và thành viên nhóm

---

## Trạng thái hiện tại

### Hoạt động tốt
- Bootstrap Server khởi động OK
- Peer đăng ký, heartbeat, discovery OK
- Chat trực tiếp, group, broadcast OK
- Offline message store-and-forward OK
- SQLite lưu lịch sử tất cả loại tin nhắn OK
- Web UI hiển thị peer, group, chat OK
- WebSocket realtime update OK
- Docker deployment OK
- Port ngẫu nhiên qua `run.sh peer <name>` OK

### Cấu trúc file hiện tại

```
P2PChat/
├── Dockerfile
├── .dockerignore
├── run.sh
├── Readme.md
├── WORKLOG.md
├── bootstrap-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/mycompany/p2pchat/
│       ├── bootstrap/ (BootstrapApp, BootstrapServer, ClientHandler, PeerRegistry)
│       ├── model/ (Message, PeerInfo, ChatGroup)
│       ├── protocol/ (MessageType, JsonUtil, ProtocolHandler)
│       └── utils/ (Constants, LoggerUtil, TimeUtil)
├── peer-node/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/mycompany/p2pchat/
│       ├── peer/ (PeerApp, PeerNode, PeerServer, PeerClient, PeerManager, WebServer)
│       ├── database/ (DatabaseManager, DatabaseInitializer, MessageRepository)
│       ├── model/ (Message, PeerInfo, ChatGroup)
│       ├── protocol/ (MessageType, JsonUtil, ProtocolHandler)
│       └── utils/ (Constants, LoggerUtil, TimeUtil)
└── peer-web/
    ├── package.json
    └── src/ (api.js, App.jsx, App.css, components/)
```
