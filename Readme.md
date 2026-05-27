# P2PChat - Hệ thống Chat Ngang hàng

P2PChat là hệ thống chat peer-to-peer viết bằng Java 17, có Web UI React, lưu dữ liệu cục bộ bằng SQLite và hỗ trợ nhắn tin trực tiếp, nhóm, broadcast, gửi file, store-and-forward qua mailbox server và relay peer trung gian khi peer offline.

Mỗi peer vừa là client gửi tin, vừa là TCP server nhận tin. Bootstrap server chỉ giữ vai trò discovery/registry và điều phối endpoint mailbox; dữ liệu offline được tách sang `mailbox-server`.

## Hướng dẫn kết nối qua mạng Internet (Dùng Tailscale)

Hệ thống P2PChat yêu cầu các máy tính (Peers) phải có thể giao tiếp trực tiếp (direct-reachable) với nhau qua TCP. Khi các máy ở các mạng nội bộ (LAN/WiFi) khác nhau, chúng ta cần một mạng riêng ảo (VPN) để kết nối chúng. **Tailscale** là giải pháp dễ dàng và tối ưu nhất cho việc này.

### Bước 1: Cài đặt và thiết lập mạng Tailscale

1. **Cài đặt Tailscale:**
   - Yêu cầu tất cả người dùng tải và cài đặt Tailscale từ trang chủ: [https://tailscale.com/download](https://tailscale.com/download).
   - Mở ứng dụng và đăng nhập.

2. **Thiết lập Mạng lưới (Tailnet):**
   - Sẽ có **một người làm Quản trị mạng (Tạo Tailnet)**. Người này đăng nhập vào [Tailscale Admin Console](https://login.tailscale.com/admin/users).
   - Tại tab **Users**, chọn **Invite Users** và nhập email của những người bạn muốn mời tham gia mạng chat (Node Khác).
   - Những người được mời cần kiểm tra email, bấm chấp nhận lời mời và đăng nhập vào Tailscale.
   - *Lưu ý quan trọng:* KHÔNG dùng tính năng "Share machine". Hãy mời người dùng gia nhập vào cùng một Tailnet (mạng) để đảm bảo tất cả mọi người đều có thể thấy nhau (rất quan trọng khi chat nhóm 3+ người).

3. **Lấy địa chỉ IP Tailscale:**
   - Sau khi kết nối thành công, mỗi người chạy lệnh sau trong Terminal/Command Prompt để lấy IP của mình:
     ```bash
     tailscale ip -4
     ```
   - Ví dụ bạn sẽ nhận được một địa chỉ như: `100.100.x.y`.

4. **Kiểm tra thông mạng:**
   - Trên Node Tham Gia, thử ping đến Node Khởi Tạo Mạng: `ping <IP_Tailscale_Node_Khoi_Tao>`
   - Đảm bảo có tín hiệu trả về.

### Bước 2: Tải và Khởi chạy Dự án

Đảm bảo bạn đã cài đặt Docker và Git trên máy tính.

Trên tất cả các máy tính (cả Node Khởi Tạo và Node Tham Gia), mở Terminal và chạy lệnh sau:
```bash
git clone https://github.com/hoanpvpv/P2PChat.git
cd P2PChat
./run.sh build
./run.sh launcher
```

Lúc này, Launcher UI (Giao diện cấu hình ban đầu) sẽ mở tại địa chỉ: **`http://localhost:9200`**

### Bước 3: Cấu hình và Đăng nhập

Sẽ có 2 vai trò cấu hình khác nhau tùy thuộc vào việc bạn là Node Khởi Tạo (chạy Bootstrap & Mailbox) hay Node Tham Gia (chỉ kết nối P2P):

#### Vai trò 1: Node Khởi Tạo (Bootstrap/Mailbox Node)
Người thiết lập mạng sẽ làm Node Khởi Tạo.
Tại giao diện `http://localhost:9200`, nhập như sau:
- **Username**: `ten_cua_ban`
- **IP máy này**: `Nhập IP Tailscale của bạn (ví dụ: 100.100.x.y)`
- **Bootstrap server**: *(Để trống)*
- **Mailbox server**: *(Để trống)*
- **Web port**: *(Để trống để tự động chọn)*

Bấm **Register and start peer**. Hệ thống sẽ tự động khởi tạo Bootstrap Server, Mailbox Server, và Peer Chat của bạn. Bấm vào link `http://localhost:<port>` hiện ra để vào ứng dụng Chat.

#### Vai trò 2: Node Tham Gia (Peer Node)
Tại giao diện `http://localhost:9200`, nhập như sau:
- **Username**: `ten_cua_ban`
- **IP máy này**: *(Để trống - hệ thống sẽ tự động phát hiện IP Tailscale của bạn)*
- **Bootstrap server**: `Nhập IP Tailscale của Node Khởi Tạo:9000` (ví dụ: `100.100.x.y:9000`)
- **Mailbox server**: *(Để trống - hệ thống sẽ tự hỏi Bootstrap)*
- **Web port**: *(Để trống để tự động chọn)*

Bấm **Register and start peer**. Sau đó bấm vào link hiện ra để vào ứng dụng Chat.

---
**💡 Mẹo nhỏ:**
Nếu bạn vô tình đóng trình duyệt, không cần phải cấu hình lại! Chỉ cần chạy lại `./run.sh launcher`, truy cập `http://localhost:9200` và bấm nút **Open** ở mục "Continue with existing peer" để mở lại cửa sổ chat của bạn.

## Kiến trúc

```text
┌──────────────────────────────┐       ┌──────────────────────────────┐
│ Bootstrap Server             │       │ Mailbox Server                │
│ TCP registry: 9000           │<----->│ Store-and-forward TCP: 9100   │
│ Dashboard HTTP: 9001         │       │ SQLite: data/mailbox.db       │
└──────────────┬───────────────┘       └──────────────┬───────────────┘
               │                                      │
               │ register/discover/heartbeat          │ store/pull/ack offline
               │                                      │
    ┌──────────▼──────────┐             ┌─────────────▼─────────┐
    │ Peer alice           │  P2P TCP    │ Peer bob               │
    │ Web UI: 33143        │<----------->│ Web UI: 33144          │
    │ Peer TCP: 34143      │ direct msg  │ Peer TCP: 34144        │
    │ File TCP: 35143      │ file chunks │ File TCP: 35144        │
    │ SQLite local DB      │             │ SQLite local DB        │
    └──────────────────────┘             └───────────────────────┘
```

### Thành phần chính

| Thành phần | Vai trò |
|---|---|
| `bootstrap-server` | Đăng ký peer, heartbeat, peer discovery, dashboard theo dõi trạng thái, trả endpoint mailbox qua `RESOLVE_MAILBOX`. |
| `mailbox-server` | Lưu tin offline bằng SQLite, hỗ trợ `STORE_MESSAGE`, `PULL_MESSAGES`, `DELIVERY_ACK`, TTL 7 ngày, ack từng member cho group message. |
| `peer-node` | Peer TCP server/client, Web API Javalin, CLI, SQLite local, DHT-lite group coordinator, outbox retry, relay store-and-forward fallback, E2EE cho direct/offline payload, file transfer. |
| `peer-web` | React Web UI cho chat, group management, broadcast, file transfer, trạng thái outbox và realtime WebSocket. |

## Cấu trúc dự án

```text
P2PChat/
├── run.sh                         # Build/start/stop Docker stack, tạo peers-index.html
├── build.sh                       # Build React + 3 Maven modules cho local run
├── Dockerfile                     # Multi-stage image cho peer-node kèm React build
├── peers-index.html               # Trang link nhanh tới các peer đang chạy
├── bootstrap-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/mycompany/p2pchat/
│       ├── bootstrap/             # BootstrapApp, BootstrapServer, dashboard, registry
│       ├── model/
│       ├── protocol/
│       └── utils/
├── mailbox-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/mycompany/p2pchat/mailbox/
│       └── resources/schema.sql
├── peer-node/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/mycompany/p2pchat/
│       │   ├── peer/              # PeerNode, PeerClient, PeerServer, mailbox, E2EE, coordinator
│       │   ├── database/          # SQLite repositories: messages, outbox, relay, file transfers
│       │   ├── filetransfer/      # Offer/accept/chunk transfer/resume
│       │   ├── model/
│       │   ├── protocol/
│       │   └── utils/
│       └── resources/
│           ├── schema.sql
│           └── static/            # React build được copy vào đây
└── peer-web/
    ├── package.json
    └── src/
        ├── api.js
        ├── App.jsx
        ├── App.css
        └── components/
```

## Tính năng

- Peer discovery qua bootstrap server.
- Heartbeat mỗi 5 giây và phát hiện peer mất kết nối.
- Direct message P2P qua TCP, ACK timeout 5 giây, retry tối đa 3 lần.
- Store-and-forward qua mailbox server khi peer offline hoặc direct send thất bại.
- Relay store-and-forward qua peer trung gian khi mailbox không khả dụng.
- Durable outbox tại peer, retry nền, backoff, circuit breaker khi mailbox lỗi, relay rotation khi peer trung gian churn.
- E2EE payload cho direct/offline message bằng khóa identity của peer.
- Pull mailbox định kỳ và delivery ack sau khi lưu local thành công.
- Chat nhóm DHT-lite: coordinator HRW top-K, gossip, add/kick/leave/disband, repair/resync cache.
- Group offline message qua mailbox với snapshot member và per-member delivery ack.
- Broadcast message và lịch sử broadcast.
- File transfer trực tiếp: offer/accept/reject/cancel, chunk 64 KB, SHA-256, resume/checkpoint, giới hạn 100 MB.
- SQLite local cho message history, group cache, known peers, recent peers, outbox, relay store, file transfer.
- Web UI React + Javalin REST API + WebSocket realtime.
- Bootstrap dashboard tại port 9001 khi chạy bằng `run.sh`.
- Docker scripts để khởi động nhiều peer nhanh và sinh trang `peers-index.html`.

## Công nghệ

| Công nghệ | Phiên bản | Mục đích |
|---|---:|---|
| Java | 17+ | Backend TCP, HTTP, storage logic |
| Maven Shade Plugin | 3.5.1 | Build fat jar |
| Javalin | 6.1.3 | HTTP/WebSocket server cho peer Web UI |
| React | 18.2 | Frontend |
| react-scripts | 5.0.1 | Build React app |
| SQLite JDBC | 3.45.1.0 | Local DB và mailbox DB |
| Gson | 2.10.1 | JSON protocol |
| SLF4J Simple | 2.0.9 | Logging |
| Docker | 20+ | Containerized run |

## Giao thức

Message TCP dùng JSON newline-delimited.

```json
{
  "type": "DIRECT_MESSAGE",
  "sender": "alice",
  "receiver": "bob",
  "content": "Xin chào!",
  "timestamp": 1715376000000,
  "messageId": "uuid-string"
}
```

### Nhóm message type chính

| Nhóm | Message type |
|---|---|
| Bootstrap/discovery | `REGISTER`, `REGISTER_ACK`, `REGISTER_NACK`, `PEER_LIST`, `PEER_JOIN`, `PEER_LEAVE`, `HEARTBEAT`, `HEARTBEAT_ACK`, `DISCOVER`, `RESOLVE_MAILBOX`, `RESOLVE_MAILBOX_ACK` |
| Mailbox/offline | `STORE_MESSAGE`, `STORE_ACK`, `PULL_MESSAGES`, `PULL_RESPONSE`, `DELIVERY_ACK`, `MAILBOX_QUOTA_EXCEEDED`, `MAILBOX_MESSAGE_EXPIRED` |
| Relay store-and-forward | `RELAY_STORE`, `RELAY_STORE_ACK`, `RELAY_FORWARD`, `RELAY_DELIVERY_ACK`, `RELAY_DELIVERED_NOTICE`, `RELAY_TOMBSTONE`, `RELAY_PULL`, `RELAY_PULL_RESPONSE`, `RELAY_SUPERSEDE` |
| Chat | `DIRECT_MESSAGE`, `GROUP_MESSAGE`, `BROADCAST`, `TYPING`, `ACK`, `SYSTEM` |
| Group coordinator | `COORD_INIT`, `COORD_GOSSIP`, `GROUP_ADD`, `GROUP_LEAVE`, `GROUP_KICK`, `GROUP_DISBAND`, `GROUP_JOINED`, `GROUP_UPDATED`, `GROUP_KICKED`, `GROUP_DISBANDED` |
| Repair/history | `GROUP_RESYNC_REQ`, `GROUP_RESYNC_RESP`, `CACHE_STALE`, `GROUP_GET`, `GROUP_HISTORY_DIGEST`, `GROUP_HISTORY_REQUEST`, `GROUP_HISTORY_RESPONSE` |
| File transfer | `FILE_OFFER`, `FILE_ACCEPT`, `FILE_REJECT`, `FILE_DONE`, `FILE_ACK`, `FILE_GROUP_OFFER`, `FILE_HAVE`, `FILE_HAVE_REQ`, `FILE_HAVE_RESP`, `FILE_RESUME` |
| Fallback/legacy | `PEER_LOOKUP_REQ`, `PEER_LOOKUP_RESP`, `CREATE_GROUP`, `ADD_TO_GROUP`, `ERROR` |

## Web API của peer

| Method | Route | Mô tả |
|---|---|---|
| GET | `/health` | Health check peer. |
| GET | `/api/info` | Thông tin peer, bootstrap, mailbox, trạng thái đăng ký. |
| GET | `/api/auto-detect-host` | Tự phát hiện local host khi kết nối bootstrap. |
| POST | `/api/init` | Khởi tạo peer từ Web UI với `username`, `peerPort`. |
| GET | `/api/peers` | Danh sách peer đã biết, gồm peer offline có lịch sử chat. |
| GET | `/api/discover` | Refresh peer list từ bootstrap. |
| GET | `/api/history/{peerName}` | Lịch sử direct chat. |
| GET | `/api/group-history/{groupId}` | Lịch sử group chat. |
| GET | `/api/broadcast-history` | Lịch sử broadcast. |
| GET | `/api/outbox` | 100 outbound message gần nhất và trạng thái retry/delivery. |
| POST | `/api/msg` | Gửi direct message. |
| POST | `/api/broadcast` | Gửi broadcast. |
| POST | `/api/typing` | Gửi typing signal tới peer/group. |
| GET | `/api/group/list` | Danh sách group DHT-lite trong cache local. |
| GET | `/api/group/{groupId}` | Chi tiết group. |
| POST | `/api/group/create` | Tạo group, chọn member và `groupMode`. |
| POST | `/api/group/add` | Thêm member qua coordinator. |
| POST | `/api/group/kick` | Kick member qua coordinator. |
| POST | `/api/group/leave` | Rời group. |
| POST | `/api/group/disband` | Giải tán group. |
| POST | `/api/group/msg` | Gửi group message. |
| GET | `/api/groups` | Legacy group API. |
| POST | `/api/group/create-legacy` | Legacy create group API. |
| GET | `/api/file/transfers` | Danh sách file transfer. |
| POST | `/api/file/offer` | Upload file và offer tới peer hoặc group. |
| POST | `/api/file/accept` | Chấp nhận file offer. |
| POST | `/api/file/reject` | Từ chối file offer. |
| POST | `/api/file/cancel` | Hủy outbound transfer. |
| GET | `/api/file/download/{transferId}` | Tải file đã nhận xong. |
| POST | `/api/shutdown` | Mô phỏng peer bị tắt đột ngột. |
| WS | `/ws` | Realtime events: peer join/leave, messages, group updates, outbox, file progress. |

## Cài đặt

### Yêu cầu

| Cách chạy | Yêu cầu |
|---|---|
| Docker | Docker 20+ |
| Local | Java JDK 17+, Maven 3.9+ hoặc Maven wrapper, Node.js 18+, npm 9+ |

Kiểm tra nhanh:

```bash
java -version
mvn -version
node -v
npm -v
docker --version
```

## Chạy bằng Docker

### Build image

```bash
chmod +x run.sh
./run.sh build
```

Lệnh này build 3 image:

- `p2pchat-mailbox`
- `p2pchat-bootstrap`
- `p2pchat-peer`

### Khởi động hệ thống

```bash
./run.sh start
```

`start` hiện khởi động mailbox, bootstrap và các peer mặc định:

| Peer | Web UI | Peer TCP | File TCP |
|---|---|---:|---:|
| `alice` | `http://localhost:33143` | 34143 | 35143 |
| `bob` | `http://localhost:33144` | 34144 | 35144 |
| `duc` | `http://localhost:12345` | 13345 | 14345 |
| `hoang` | `http://localhost:12346` | 13346 | 14346 |
| `hoan` | `http://localhost:12349` | 13349 | 14349 |

Hạ tầng mặc định khi dùng `run.sh`:

| Service | URL/port |
|---|---|
| Bootstrap TCP | `localhost:9000` |
| Bootstrap dashboard | `http://localhost:9001` |
| Mailbox TCP | `localhost:9100` |
| Peer index | `peers-index.html` ở root repo |

### Thêm peer

#### Đăng ký peer bằng giao diện

Sau khi build image, chạy launcher trên máy host:

```bash
./run.sh launcher
```

Mở:

```text
http://localhost:9200
```

Nhập `username`, có thể bỏ trống `web port` để launcher tự chọn port còn trống. Máy làm bootstrap/mailbox thì nhập `IP máy này` là IP VPN/public của máy host và để trống `Bootstrap server`; launcher sẽ tự tạo bootstrap/mailbox. Máy tham gia thì chỉ cần nhập `Bootstrap server`, ví dụ `100.64.1.10:9000`; ô `IP máy này` để trống để launcher tự detect.

Launcher chỉ bind `127.0.0.1:9200` và chỉ chấp nhận username gồm chữ, số, `_`, `-`.

#### Tạo peer bằng command

```bash
./run.sh peer charlie
./run.sh peer dave 33150
```

Nếu không truyền web port, script tự chọn port ngẫu nhiên, riêng `alice` và `bob` có port cố định. Peer port = web port + 1000, file port = peer port + 1000.

### Lệnh quản lý

```bash
./run.sh list               # Liệt kê peer đang chạy và URL
./run.sh urls               # Cập nhật peers-index.html rồi in danh sách URL
./run.sh launcher           # Mở giao diện đăng ký peer mới
./run.sh logs alice         # Follow log peer alice
./run.sh logs s             # Follow log bootstrap
./run.sh logs all           # Follow log các peer
./run.sh stop               # Dừng bootstrap, mailbox và tất cả peer
./run.sh restart            # Stop rồi start lại stack mặc định
```

### Tạo mạng và tham gia bằng Command Line (Dành cho Advanced Users)

Nếu không muốn dùng giao diện Launcher (port 9200), bạn có thể chạy bằng CLI:

**1. Khởi tạo hạ tầng (Node Khởi Tạo):**
```bash
./run.sh build
./run.sh infra <IP_Tailscale_node_khoi_tao>
# Khởi động Bootstrap + Mailbox
```

**2. Tạo Peer cho Node Khởi Tạo:**
```bash
./run.sh join <ten_node_khoi_tao> <IP_Tailscale_node_khoi_tao> <IP_Tailscale_node_khoi_tao>:9000 33143
```

**3. Tạo Peer cho Node Tham Gia (gia nhập vào mạng):**
```bash
./run.sh join <ten_node_tham_gia> <IP_Tailscale_node_tham_gia> <IP_Tailscale_node_khoi_tao>:9000 33144
```

*Cú pháp:* `./run.sh join <username> <ip_chạy_peer> <ip_bootstrap:port> <web_port>`
*Lưu ý:* `ip_chạy_peer` phải là IP mà máy khác có thể truy cập được (như IP Tailscale), không dùng tên Docker Container (như `peer-alice`) nếu chạy 2 máy khác mạng.

### Thông tin mạng bổ sung

Các port cần thông được qua VPN/firewall (nếu tự thiết lập thủ công):

| Port | Vai trò |
|---:|---|
| `9000` | Bootstrap TCP trên máy bạn |
| `9001` | Bootstrap dashboard trên máy bạn |
| `9100` | Mailbox TCP trên máy bạn |
| `webPort + 1000` | Peer TCP của mỗi máy |
| `webPort + 2000` | File transfer TCP của mỗi máy |

Dữ liệu Docker được mount vào:

- `data/mailbox` cho mailbox.
- `data/peers/<username>` cho từng peer.

## Chạy local không dùng Docker

Build toàn bộ:

```bash
chmod +x build.sh
./build.sh
```

Hoặc build thủ công:

```bash
cd peer-web
npm install
npm run build

cd ..
rm -rf peer-node/src/main/resources/static
cp -r peer-web/build peer-node/src/main/resources/static

cd bootstrap-server && ./mvnw clean package -DskipTests || mvn clean package -DskipTests
cd ../mailbox-server && ./mvnw clean package -DskipTests || mvn clean package -DskipTests
cd ../peer-node && ./mvnw clean package -DskipTests || mvn clean package -DskipTests
```

Khởi động 4 terminal:

```bash
# Terminal 1: mailbox
java -jar mailbox-server/target/mailbox-server.jar \
  --port 9100 \
  --db data/mailbox.db
```

```bash
# Terminal 2: bootstrap + dashboard
java -jar bootstrap-server/target/bootstrap-server.jar \
  --port 9000 \
  --dashboard-port 9001 \
  --mailbox localhost:9100
```

```bash
# Terminal 3: alice
java -jar peer-node/target/peer-node.jar \
  --username alice \
  --host localhost \
  --port 34143 \
  --bootstrap localhost:9000 \
  --mailbox localhost:9100 \
  --web 33143
```

```bash
# Terminal 4: bob
java -jar peer-node/target/peer-node.jar \
  --username bob \
  --host localhost \
  --port 34144 \
  --bootstrap localhost:9000 \
  --mailbox localhost:9100 \
  --web 33144
```

Mở Web UI:

- `http://localhost:33143` cho alice.
- `http://localhost:33144` cho bob.
- `http://localhost:9001` cho bootstrap dashboard.

## CLI peer

Khi chạy local, terminal peer có CLI:

| Lệnh | Mô tả |
|---|---|
| `/help` | Hiển thị lệnh. |
| `/peers` | Danh sách peer online. |
| `/discover` | Refresh peer list. |
| `/msg <peer> <message>` | Gửi direct message. |
| `/broadcast <message>` | Gửi broadcast. |
| `/group list` | Liệt kê group trong cache local. |
| `/group msg <groupId> <message>` | Gửi group message. |
| `/history <peer>` | Xem lịch sử direct chat. |
| `/exit` | Thoát peer. |

Tạo group DHT-lite nên thực hiện qua Web UI vì UI truyền đủ member list và group metadata.

Nếu chạy bằng Docker:

```bash
docker attach peer-alice
# Thoát attach mà không dừng container: Ctrl+P, Ctrl+Q
```

## CLI flags

### Bootstrap server

| Flag | Mặc định | Mô tả |
|---|---:|---|
| `--port` | `8080` | TCP port bootstrap nếu chạy trực tiếp không truyền flag. `run.sh` dùng `9000`. |
| `--dashboard-port` | `8081` | HTTP dashboard nếu chạy trực tiếp không truyền flag. `run.sh` dùng `9001`. |
| `--mailbox` | `localhost:9100` | Endpoint mailbox mà bootstrap trả cho peer. |

### Mailbox server

| Flag | Mặc định | Mô tả |
|---|---:|---|
| `--port` | `9100` | TCP port mailbox. |
| `--db` | `data/mailbox.db` | Đường dẫn SQLite DB. |

### Peer node

| Flag | Mặc định | Mô tả |
|---|---:|---|
| `--username` | rỗng | Tên peer. Nếu bỏ trống có thể init từ Web UI. |
| `--host` | `localhost` | Host/IP peer quảng bá cho peer khác. |
| `--port` | `5001` | TCP port nhận P2P message. |
| `--bootstrap` | `localhost:8080` | Bootstrap endpoint. |
| `--mailbox` | `localhost:9100` | Mailbox endpoint. Có thể được resolve lại từ bootstrap. |
| `--web` | `3000` | HTTP/WebSocket port Web UI. |

## Luồng hoạt động chính

### Peer online

```text
Peer -> Bootstrap: REGISTER(username, host, port, keyId, publicKey)
Bootstrap -> Peer: REGISTER_ACK(peer list)
Bootstrap -> Others: PEER_JOIN
Peer -> Bootstrap: HEARTBEAT mỗi 5 giây
Bootstrap -> Peer: HEARTBEAT_ACK(peer list)
```

### Direct message

```text
Sender lưu message + outbox PENDING
Sender mã hóa payload cho receiver
Sender -> Receiver: DIRECT_MESSAGE
Receiver giải mã, lưu SQLite, trả ACK
Sender đánh dấu DELIVERED_DIRECT
```

Nếu receiver offline hoặc direct send lỗi:

```text
Sender -> Mailbox: STORE_MESSAGE(encrypted envelope)
Mailbox -> Sender: STORE_ACK
Sender đánh dấu STORED_MAILBOX
Receiver online/poll -> Mailbox: PULL_MESSAGES
Receiver giải mã, lưu SQLite, gửi DELIVERY_ACK
Mailbox đánh dấu DELIVERED
```

Nếu mailbox không khả dụng, sender dùng relay store-and-forward qua các peer trung gian:

```text
Sender gửi direct thất bại
Sender gửi Mailbox thất bại hoặc mailbox circuit đang mở
Sender chọn tối đa 4 relay peer online, loại trừ sender và receiver
Sender -> Relay: RELAY_STORE(encrypted direct payload)
Relay -> Sender: RELAY_STORE_ACK
Sender đánh dấu STORED_RELAY, vẫn giữ bản gốc trong local outbox
```

Relay peer lưu payload đã mã hóa và chỉ forward về receiver gốc:

```text
Relay -> Receiver: RELAY_FORWARD(encrypted direct payload)
Receiver dedup theo message_id, giải mã/lưu nếu chưa có
Receiver -> Relay: RELAY_DELIVERY_ACK
Relay -> Sender: RELAY_DELIVERED_NOTICE
Sender đánh dấu DELIVERED_VIA_RELAY và broadcast RELAY_TOMBSTONE
```

Receiver cũng chủ động pull khi online:

```text
Receiver -> peers online: RELAY_PULL
Relay -> Receiver: RELAY_PULL_RESPONSE
Receiver gom theo message_id, xử lý một lần, ACK tất cả relay trả bản trùng
```

Relay fallback dùng lease ngắn để phù hợp churn cao: 4 relay mỗi tin, 1 primary + 3 backup, lease 2 phút, rotation mỗi 60 giây, cooldown relay fail 3 phút, tombstone TTL 24 giờ. Relay cũ nhận `RELAY_SUPERSEDE` khi hết lease để dừng chủ động forward nhưng không làm mất bản sao trước khi relay mới lưu thành công.

### File transfer

```text
Sender upload/chọn file trong Web UI
Sender -> Receiver: FILE_OFFER(metadata, sha256, filePort)
Receiver accept
Receiver -> Sender: FILE_ACCEPT
Receiver tải chunk qua file TCP port = peerPort + 1000
Receiver verify SHA-256, cập nhật progress qua WebSocket
```

## Dữ liệu lưu trữ

Peer SQLite (`data/peers/<username>` khi chạy Docker) gồm:

- `messages`: lịch sử direct/group/broadcast/system/file offer.
- `known_peers`: peer đã biết và trạng thái online.
- `group_cache`: metadata group DHT-lite.
- `recent_peers`: bootstrap cache fallback.
- `outbound_messages`: durable outbox cho retry và mailbox.
- `relay_assignments`: relay peer mà sender đã giao lưu hộ, gồm role, generation, lease và trạng thái.
- `relay_messages`: encrypted payload mà peer trung gian đang lưu/forward hộ receiver.
- `relay_tombstones`: dấu vết delivered/expired để chặn relay cũ gửi lại tin đã giao.
- `relay_peer_cooldowns`: peer relay vừa fail, tạm thời không chọn lại trong 3 phút.
- `file_transfers`, `file_chunks`: metadata transfer và checkpoint chunk.

Mailbox SQLite gồm:

- `offline_messages`: encrypted envelope, TTL, trạng thái `STORED`/`DELIVERED`/`EXPIRED`.
- `group_delivery_acks`: ack theo từng member cho group message.

## Docker thủ công

```bash
docker build -t p2pchat-mailbox -f mailbox-server/Dockerfile mailbox-server/
docker build -t p2pchat-bootstrap -f bootstrap-server/Dockerfile bootstrap-server/
docker build -t p2pchat-peer -f Dockerfile .

docker network create p2p-net

docker run -d --name mailbox --network p2p-net \
  -p 9100:9100 \
  -v "$(pwd)/data/mailbox:/app/data" \
  p2pchat-mailbox --port 9100

docker run -d --name bootstrap --network p2p-net \
  -p 9000:9000 -p 9001:9001 \
  p2pchat-bootstrap \
  --port 9000 --dashboard-port 9001 --mailbox mailbox:9100

docker run -d --name peer-alice --network p2p-net \
  -p 34143:34143 -p 35143:35143 -p 33143:33143 \
  -v "$(pwd)/data/peers/alice:/app/data" \
  p2pchat-peer \
  --username alice --host peer-alice --port 34143 \
  --bootstrap bootstrap:9000 --mailbox mailbox:9100 --web 33143
```

## Lỗi thường gặp

| Lỗi | Nguyên nhân | Cách xử lý |
|---|---|---|
| `java: command not found` | Chưa cài JDK | Cài Java 17+ và thêm vào PATH. |
| `mvn: command not found` | Chưa cài Maven | Dùng Maven wrapper nếu có hoặc cài Maven. |
| `npm: command not found` | Chưa cài Node/npm | Cài Node.js 18+ và npm. |
| Port bị chiếm | Port bootstrap/peer/web/file đang dùng | Đổi port hoặc dừng process/container cũ. |
| Web UI không load | Chưa build React vào `peer-node/src/main/resources/static` | Chạy `./build.sh`. |
| Peer không đăng ký được | Sai bootstrap endpoint hoặc bootstrap chưa chạy | Kiểm tra `--bootstrap`, firewall, container network. |
| Tin offline không tới | Mailbox chưa chạy/sai endpoint và không còn relay online đủ public key | Kiểm tra `--mailbox`, dashboard, log mailbox, peer list và outbox state `STORED_RELAY`/`FAILED_RETRYABLE`. |
| Tin relay bị gửi trùng | Relay cũ quay lại hoặc nhiều relay cùng giữ bản sao | Receiver dedup bằng `message_id`; kiểm tra tombstone/relay cleanup nếu UI vẫn hiện trùng. |
| File transfer lỗi | Chưa expose file port hoặc peer không reachable | Mở port `peerPort + 1000` và kiểm tra host quảng bá. |
| Docker build chậm | Lần đầu tải dependency Maven/npm | Bình thường, chờ build hoàn tất. |

## Tác giả
Nhóm 13 - Lớp 01 - Các hệ thống phân tán
