# P2PChat - Hệ thống Chat Ngang hàng

P2PChat là hệ thống chat peer-to-peer viết bằng Java 17, có Web UI React, lưu dữ liệu cục bộ bằng SQLite và hỗ trợ nhắn tin trực tiếp, nhóm, broadcast, gửi file, store-and-forward qua mailbox server khi peer offline.

Mỗi peer vừa là client gửi tin, vừa là TCP server nhận tin. Bootstrap server chỉ giữ vai trò discovery/registry và điều phối endpoint mailbox; dữ liệu offline được tách sang `mailbox-server`.

## Hướng dẫn sử dụng nhanh

### 1. Chuẩn bị mạng

Cả hai máy cần cùng version project và đã cài Docker. Nếu hai máy không cùng LAN/WiFi, dùng Tailscale để lấy IP riêng của từng máy.

Cả hai máy phải nằm trong cùng Tailscale tailnet, hoặc máy host phải được share cho bạn bè:

- Nếu tự test bằng nhiều thiết bị của bạn: đăng nhập cùng tài khoản Tailscale trên các thiết bị.
- Nếu bạn bè dùng tài khoản riêng: mời email của bạn ấy vào tailnet tại `https://login.tailscale.com/admin/users`, hoặc share riêng máy host tại `https://login.tailscale.com/admin/machines`.
- Sau khi tham gia Tailscale, mỗi người chạy `tailscale ip -4` để lấy IP máy của mình.

Kiểm tra từ máy bạn bè tới máy host:

```bash
ping <IP_Tailscale_may_host>
nc -vz <IP_Tailscale_may_host> 9000
```

Ví dụ:

```bash
ping 100.64.1.10
nc -vz 100.64.1.10 9000
```

Nếu `nc` báo `succeeded` thì máy bạn bè kết nối được tới bootstrap.

### 2. Build project

Ví dụ:

```text
Máy host: 100.64.1.10
Máy bạn: 100.64.1.20
```

Build image trên mỗi máy:

```bash
./run.sh build
```

### 3. Máy host tạo mạng chat

Trên máy sẽ chạy bootstrap + mailbox:

```bash
./run.sh launcher
```

Mở:

```text
http://localhost:9200
```

Nhập form:

```text
Username: you
IP máy này: 100.64.1.10
Bootstrap server: để trống
Mailbox server: để trống
Web port: để trống
```

Sau khi bấm `Register and start peer`, mở link Web UI mà launcher hiện ra.

### 4. Máy còn lại tham gia

Trên máy bạn bè:

```bash
./run.sh launcher
```

Mở:

```text
http://localhost:9200
```

Nhập form:

```text
Username: friend
IP máy này: để trống
Bootstrap server: 100.64.1.10:9000
Mailbox server: để trống
Web port: để trống
```

Sau khi bấm `Register and start peer`, mở link Web UI mà launcher hiện ra.

Nếu đã từng tạo peer và lỡ tắt tab trình duyệt, chỉ cần chạy lại:

```bash
./run.sh launcher
```

Mở `http://localhost:9200`, bấm `Open` ở phần `Continue` để vào lại đúng peer. Nếu container peer đang stopped, nút đó sẽ là `Start`.

### 5. Chat

Trong Web UI, bấm refresh/discover nếu chưa thấy peer, chọn tên peer còn lại rồi nhắn tin.

Ghi nhớ:

- `IP máy này` là tùy chọn nâng cao. Máy tham gia nên để trống; launcher sẽ tự detect IP dùng để kết nối tới bootstrap.
- Máy host nên nhập `IP máy này` nếu muốn tự chạy bootstrap/mailbox cho máy khác truy cập.
- Máy host để trống `Bootstrap server`.
- Máy tham gia nhập `Bootstrap server` là `IP máy host:9000`.
- `Web port` có thể để trống để launcher tự chọn.

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
| `peer-node` | Peer TCP server/client, Web API Javalin, CLI, SQLite local, DHT-lite group coordinator, outbox retry, E2EE cho direct/offline payload, file transfer. |
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
│       │   ├── database/          # SQLite repositories: messages, outbox, file transfers
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
- Durable outbox tại peer, retry nền, backoff, circuit breaker khi mailbox lỗi.
- E2EE payload cho direct/offline message bằng khóa identity của peer.
- Pull mailbox định kỳ và delivery ack sau khi lưu local thành công.
- Chat nhóm DHT-lite: coordinator HRW top-K, gossip, add/kick/leave/disband, repair/resync cache.
- Group offline message qua mailbox với snapshot member và per-member delivery ack.
- Broadcast message và lịch sử broadcast.
- File transfer trực tiếp: offer/accept/reject/cancel, chunk 64 KB, SHA-256, resume/checkpoint, giới hạn 100 MB.
- SQLite local cho message history, group cache, known peers, recent peers, outbox, file transfer.
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

### Chạy 2 máy không cùng LAN/WiFi

Khuyến nghị dùng Tailscale hoặc ZeroTier để mỗi máy có một IP riêng có thể truy cập lẫn nhau. Ví dụ:

```text
Máy bạn:      100.64.1.10
Máy bạn bạn: 100.64.1.20
```

Máy bạn làm bootstrap + mailbox bằng giao diện:

```bash
./run.sh build
./run.sh launcher
```

Mở `http://localhost:9200`, nhập:

```text
Username: you
IP máy này: 100.64.1.10
Bootstrap server: để trống
Mailbox server: để trống
Web port: 33143
```

Máy bạn bạn tham gia bằng giao diện:

```bash
./run.sh build
./run.sh launcher
```

Mở `http://localhost:9200`, nhập:

```text
Username: friend
IP máy này: để trống
Bootstrap server: 100.64.1.10:9000
Mailbox server: để trống
Web port: 33144
```

Tóm tắt cho người bạn muốn tham gia:

```bash
cd P2PChat
./run.sh build
./run.sh launcher
```

Sau đó mở `http://localhost:9200` và nhập:

```text
Username: tên của bạn ấy
IP máy này: để trống
Bootstrap server: IP Tailscale máy chủ:9000
Mailbox server: để trống
Web port: để trống
```

Sau khi bấm `Register and start peer`, launcher sẽ hiện link Web UI dạng `http://localhost:<port>`. Bạn ấy bấm link đó để vào ứng dụng chat.

Cách command tương đương nếu cần:

```bash
./run.sh build
./run.sh infra 100.64.1.10
./run.sh join you 100.64.1.10 100.64.1.10:9000 33143
./run.sh join friend 100.64.1.20 100.64.1.10:9000 33144
```

Sau đó mỗi người mở Web UI local của mình:

```text
Máy bạn:      http://localhost:33143
Máy bạn bạn: http://localhost:33144
```

Điểm quan trọng: tham số thứ hai của `join` là IP/hostname mà máy còn lại truy cập được tới peer đó. Không dùng tên Docker container cho trường hợp khác mạng.

Các port cần thông được qua VPN/firewall:

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
| Tin offline không tới | Mailbox chưa chạy hoặc sai endpoint | Kiểm tra `--mailbox`, dashboard, log mailbox. |
| File transfer lỗi | Chưa expose file port hoặc peer không reachable | Mở port `peerPort + 1000` và kiểm tra host quảng bá. |
| Docker build chậm | Lần đầu tải dependency Maven/npm | Bình thường, chờ build hoàn tất. |

## Tài liệu liên quan

- `Bootstrap-server.md`
- `Store-and-forward-mailbox-design.md`
- `Workflow_P2PChat.md`
- `Suggestion_P2Pmessaging.md`
- `P2PChat_issues_to_fix.md`
- `WORKLOG.md`

## Tác giả

Đồ án môn Hệ thống Phân tán.
