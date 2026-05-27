# BÁO CÁO BÀI TẬP LỚN

**Đề tài:** Xây dựng hệ thống chat ngang hàng (P2P Chat)

**Nhóm:** Nhóm 13 — Lớp 01 — Các hệ thống phân tán

---

# CHƯƠNG I. GIỚI THIỆU VỀ HỆ THỐNG CHAT P2P

## 1.1. Đặt vấn đề

Nhắn tin trực tuyến là nhu cầu thiết yếu trong thời đại số. Các ứng dụng chat phổ biến hiện nay như Facebook Messenger, Zalo, Telegram đều hoạt động theo mô hình **client-server** — tức mọi tin nhắn đều phải đi qua máy chủ trung tâm của nhà cung cấp dịch vụ. Mô hình này có một số hạn chế đáng chú ý:

- **Phụ thuộc vào server trung tâm:** Nếu server gặp sự cố hoặc bị tấn công, toàn bộ hệ thống bị gián đoạn. Không có server trung tâm, người dùng không thể liên lạc.
- **Vấn đề quyền riêng tư:** Nhà cung cấp dịch vụ có thể đọc, lưu trữ và phân tích nội dung tin nhắn của người dùng.
- **Chi phí vận hành cao:** Cần duy trì hạ tầng server quy mô lớn để phục vụ hàng triệu người dùng đồng thời.
- **Khó khăn khi mở rộng:** Băng thông và khả năng xử lý của server trung tâm là điểm thắt cổ chai khi lượng người dùng tăng đột biến.

Để khắc phục các hạn chế trên, mô hình **peer-to-peer (P2P)** được đề xuất và ứng dụng rộng rãi trong nhiều lĩnh vực như chia sẻ file (BitTorrent), giao tiếc VoIP (Skype phiên bản cũ). Trong mô hình P2P, mỗi người dùng (peer) vừa là **client** gửi tin nhắn, vừa là **server** nhận tin nhắn từ người khác. Dữ liệu được truyền trực tiếp giữa các peer mà không phải qua bất kỳ máy chủ trung tâm nào. Điều này mang lại tính phân cấp, khả năng chịu lỗi cao hơn và giảm phụ thuộc vào hạ tầng tập trung.

Tuy nhiên, xây dựng một hệ thống chat P2P hoàn chỉnh đặt ra nhiều thách thức thực tế: làm sao để peer mới biết các peer khác trong mạng, làm sao để gửi tin nhắn khi người nhận đang offline, làm sao để quản lý nhóm chat hiệu quả, và làm sao để truyền file trực tiếp giữa các peer. **Hệ thống P2PChat** được xây dựng nhằm giải quyết các thách thức này, tạo ra một ứng dụng chat ngang hàng thực sự với đầy đủ tính năng nhắn tin cá nhân, nhóm, broadcast và chuyển file.

## 1.2. Mục tiêu đề tài

### 1.2.1. Mục tiêu tổng quát

Xây dựng một hệ thống chat ngang hàng (peer-to-peer) hoàn chỉnh, cho phép người dùng giao tiếp trực tiếp với nhau qua mạng Internet mà không phụ thuộc vào máy chủ trung tâm cho việc truyền tin nhắn, đảm bảo tính riêng tư, độ tin cậy và khả năng hoạt động ngay cả khi một số peer trong mạng bị ngắt kết nối.

### 1.2.2. Mục tiêu cụ thể

1. **Xây dựng Bootstrap Server** làm điểm vào mạng, cho phép peer mới đăng ký, khám phá các peer đang hoạt động, và theo dõi trạng thái online/offline của từng peer thông qua cơ chế heartbeat.

2. **Triển khai cơ chế nhắn tin trực tiếp P2P** qua giao thức TCP, đảm bảo tin nhắn được gửi trực tiếp từ peer này đến peer khác với cơ chế xác nhận (ACK) và retry khi thất bại.

3. **Hỗ trợ tin nhắn offline** thông qua Mailbox Server, cho phép lưu trữ tin nhắn tạm thời khi người nhận không online và tự động gửi lại khi người nhận trở lại hoạt động.

4. **Xây dựng tính năng chat nhóm** dựa trên mô hình DHT-lite với thuật toán HRW (Highest Random Weight) để chọn coordinator, đảm bảo quản lý nhóm hiệu quả ngay cả khi một số thành viên offline.

5. **Cung cấp tính năng broadcast** cho phép gửi tin nhắn đến tất cả peer đang online trong mạng một cách nhanh chóng.

6. **Hỗ trợ chuyển file trực tiếp** giữa các peer với cơ chế chunk, kiểm tra SHA-256 và khả năng resume sau gián đoạn kết nối.

7. **Triển khai mã hóa đầu cuối (E2EE)** cho nội dung tin nhắn, đảm bảo chỉ người gửi và người nhận có thể đọc được nội dung.

8. **Xây dựng giao diện web (Web UI)** thân thiện với người dùng, hỗ trợ realtime qua WebSocket, cho phép quản lý peer, nhóm chat, gửi tin nhắn và file.

9. **Triển khai hệ thống bằng Docker** để dễ dàng khởi chạy, quản lý và mở rộng nhiều peer cùng lúc.

10. **Hỗ trợ kết nối qua Internet** bằng Tailscale VPN, cho phép các peer ở các mạng nội bộ khác nhau có thể giao tiếp P2P trực tiếp.

## 1.3. Phạm vi và phương pháp thực hiện

### 1.3.1. Phạm vi đề tài

**Phạm vi không gian:**

Đề tài được thực hiện trong phạm vi phòng thí nghiệm hoặc môi trường mạng cục bộ (LAN), có thể mở rộng ra mạng Internet thông qua Tailscale VPN. Hệ thống được triển khai và thử nghiệm trên các máy tính cá nhân sử dụng hệ điều hành Windows và Linux (WSL2), sử dụng Docker để container hóa các thành phần. Các peer có thể hoạt động trên cùng một mạng LAN hoặc ở các mạng LAN khác nhau thông qua mạng riêng ảo Tailscale, miễn là các máy có thể kết nối TCP đến nhau.

**Phạm vi thời gian:**

Đề tài được thực hiện trong khuôn khổ môn học Các hệ thống phân tán, với thời gian thực hiện tập trung trong học kỳ theo kế hoạch của môn học. Hệ thống được xây dựng và kiểm thử trong giai đoạn này, đảm bảo các chức năng cốt lõi hoạt động ổn định. Sau khi kết thúc môn học, hệ thống có thể được mở rộng và cải tiến thêm nhưng không nằm trong phạm vi báo cáo này.

### 1.3.2. Phương pháp thực hiện

**Phương pháp phân tích và thiết kế:**

Đề tài sử dụng phương pháp phân tích **top-down** kết hợp **bottom-up**:

- **Top-down:** Phân tích yêu cầu hệ thống từ góc nhìn người dùng, xác định các chức năng cần có, sau đó phân rã thành các thành phần kiến trúc và giao thức tương tác.
- **Bottom-up:** Xây dựng từng thành phần độc lập (Bootstrap Server, Mailbox Server, Peer Node, Web UI), kiểm thử riêng lẻ, sau đó tích hợp lại thành hệ thống hoàn chỉnh.

Thiết kế hệ thống tuân theo nguyên tắc **modular** — mỗi thành phần (bootstrap, mailbox, peer) có nhiệm vụ rõ ràng, giao tiếp qua giao thức đã định nghĩa, có thể hoạt động độc lập và dễ dàng thay thế, mở rộng.

**Công cụ và môi trường phát triển:**

- **Java 17 + Maven** cho backend (Bootstrap, Mailbox, Peer)
- **React 18 + npm** cho frontend web
- **Docker** để container hóa và triển khai
- **Tailscale** để tạo mạng VPN cho kết nối P2P qua Internet
- **SQLite** cho lưu trữ cục bộ tại mỗi peer và tại Mailbox Server
- **Git** để quản lý mã nguồn

**Quy trình phát triển:**

1. **Nghiên cứu lý thuyết** — Tìm hiểu mô hình P2P, giao thức TCP, DHT, heartbeat, store-and-forward.
2. **Thiết kế kiến trúc** — Xác định các thành phần, giao thức truyền thông, cấu trúc dữ liệu.
3. **Xây dựng từng thành phần** — Bootstrap Server → Mailbox Server → Peer Node → Web UI.
4. **Kiểm thử từng thành phần** và tích hợp.
5. **Triển khai và đánh giá** — Chạy thực tế nhiều peer, kiểm tra các kịch bản churn, offline, group chat.
6. **Viết báo cáo và đánh giá** kết quả.

---

# CHƯƠNG II. CƠ SỞ LÝ THUYẾT CỦA HỆ THỐNG CHAT P2P

## 2.1. Mạng ngang hàng (Peer-to-Peer Network)

### 2.1.1. Khái niệm

Mạng ngang hàng (Peer-to-Peer — P2P) là mô hình kiến trúc phân tán trong đó mỗi nút (peer) vừa đóng vai trò **client** gửi yêu cầu, vừa đóng vai trò **server** phục vụ yêu cầu từ các nút khác. Không có máy chủ trung tâm duy nhất điều khiển toàn bộ hệ thống.

So với mô hình client-server truyền thống, P2P có các đặc điểm nổi bật:

- **Phân cấp:** Mỗi peer có quyền hạn ngang nhau, không có điểm thất bại đơn lẻ (single point of failure) duy nhất.
- **Khả năng mở rộng tự nhiên:** Khi thêm peer mới, cả tài nguyên lẫn năng lực xử lý của mạng đều tăng.
- **Khả năng chịu lỗi:** Dữ liệu được sao chép trên nhiều peer; một peer下线 không ảnh hưởng đến toàn mạng.
- **Tiết kiệm chi phí hạ tầng:** Không cần đầu tư máy chủ tập trung quy mô lớn.

### 2.1.2. Phân loại mạng P2P

| Loại | Mô tả | Ứng dụng điển hình |
|------|--------|---------------------|
| **Pure P2P** | Không có server trung tâm; peer tìm nhau hoàn toàn qua giao thức phân tán (gossip, DHT). | Gnutella, BitTorrent early version |
| **Hybrid P2P** | Có server hỗ trợ một số chức năng (đăng ký, tìm kiếm) nhưng dữ liệu chat gửi trực tiếp P2P. | Skype (legacy), hệ thống này |
| **Structured P2P** | Sử dụng DHT (Distributed Hash Table) để ánh xạ khóa → peer chịu trách nhiệm. | Chord, Kademlia, Tapestry |
| **Unstructured P2P** | Peer kết nối ngẫu nhiên; tìm kiếm bằng flooding hoặc random walk. | Gnutella |

Hệ thống P2PChat thiên về mô hình **Hybrid P2P**: Bootstrap Server đóng vai trò điểm vào mạng và hỗ trợ peer discovery, nhưng toàn bộ dữ liệu chat được truyền trực tiếp giữa các peer qua TCP.

### 2.1.3. Bảng so sánh Client-Server và P2P

| Tiêu chí | Client-Server | P2P |
|----------|--------------|-----|
| Kiến trúc | Tập trung | Phân tán |
| Máy chủ trung tâm | Bắt buộc | Không bắt buộc (hybrid) |
| Điểm thất bại | 1 máy chủ | Nhiều điểm |
| Chi phí vận hành | Cao (cần server mạnh) | Thấp (tài nguyên phân tán) |
| Bảo mật tin nhắn | Server kiểm soát hoàn toàn | Peer tự quản lý E2EE |
| Khả năng mở rộng | Giới hạn băng thông server | Tăng theo số peer |

## 2.2. Giao thức TCP trong truyền thông P2P

### 2.2.1. TCP Socket

TCP (Transmission Control Protocol) là giao thức truyền tin **hướng kết nối**, đáng tin cậy, đảm bảo thứ tự và toàn vẹn dữ liệu. Trong hệ thống P2P, mỗi peer mở một **ServerSocket** để lắng nghe kết nối đến và sử dụng **Socket** để kết nối đến peer khác.

Ưu điểm của TCP cho chat P2P:
- Đáng tin cậy: cơ chế ACK đảm bảo tin nhắn được nhận.
- Hỗ trợ streaming liên tục (phù hợp cho file transfer).
- Kiến trúc đơn giản, dễ triển khai.

### 2.2.2. Định dạng message trong hệ thống

Mỗi message trong hệ thống được truyền dưới dạng một dòng JSON theo quy tắc **newline-delimited** (mỗi message kết thúc bằng ký tự xuống dòng `\n`). Cấu trúc chung của một message gồm các trường:

- **type** — loại message, xác định hành động cần thực hiện (ví dụ: `DIRECT_MESSAGE`, `HEARTBEAT`, `REGISTER`).
- **messageId** — chuỗi UUID v4 định danh duy nhất mỗi message.
- **sender** — username của người gửi.
- **receiver** — username người nhận (null đối với broadcast).
- **groupName** — tên nhóm nếu là message nhóm (null đối với direct).
- **content** — nội dung tin nhắn, có thể được mã hóa E2EE.
- **timestamp** — thời gian gửi dưới dạng epoch milliseconds.

Ưu điểm của định dạng này:
- Dễ parse, dễ debug.
- Không cần định nghĩa schema cứng như Protobuf/Thrift.
- Tương thích với mọi ngôn ngữ lập trình có thư viện JSON.

### 2.2.3. Cổng giao tiếp (Ports)

Mỗi peer sử dụng nhiều TCP port cho các mục đích khác nhau:

| Cổng | Vai trò |
|------|---------|
| `webPort` | HTTP/WebSocket cho Web UI (Javalin) |
| `peerPort = webPort + 1000` | Peer TCP server nhận tin nhắn P2P |
| `filePort = peerPort + 1000` | TCP server nhận chunk file transfer |

## 2.3. Cơ chế đăng ký và khám phá peer

### 2.3.1. Bootstrap Server

Trong mạng P2P thuần túy, mỗi peer cần biết ít nhất một peer khác để tham gia mạng. **Bootstrap Server** giải quyết vấn đề "cold start" này bằng cách đóng vai trò **điểm vào duy nhất** cho tất cả peer:

- **Đăng ký (Register):** Peer gửi `REGISTER` kèm `username`, `host:port`, `publicKey` để tham gia mạng. Bootstrap lưu thông tin vào registry.
- **Peer Discovery:** Cung cấp danh sách peer online cho peer mới hoặc khi có yêu cầu qua `DISCOVER`.
- **Heartbeat:** Theo dõi trạng thái sống/chết của mỗi peer qua cơ chế keep-alive.
- **Điều phối mailbox:** Trả endpoint mailbox server qua `RESOLVE_MAILBOX`.

> **Lưu ý quan trọng:** Bootstrap Server **KHÔNG** truyền nội dung chat. Mọi tin nhắn chat được gửi trực tiếp peer-to-peer qua TCP. Bootstrap chỉ quản lý metadata và điều phối.

### 2.3.2. Luồng đăng ký (Register Flow)

```
Peer Alice                     Bootstrap Server               Peer Bob (đã online)
    |                                 |                               |
    |---- REGISTER ------------------->|                               |
    |     (host:port|keyId|pk)        |                               |
    |                                 |                               |
    |                                 |---- PEER_JOIN -------------->|
    |                                 |     (Alice tham gia)          |
    |<--- REGISTER_ACK ----------------|                               |
    |     (peer list JSON)            |                               |
    |                                 |                               |
    |<--- OFFLINE_MESSAGE* -----------|  (nếu có tin nhắn chờ)       |
```

### 2.3.3. NAT Detection và Host Override

Khi peer chạy trong Docker container, IP quảng báo (advertised) thường là Docker bridge (172.16–31.x.x), không thể truy cập từ bên ngoài. Bootstrap Server phát hiện và ghi đè bằng **remote IP thực** từ socket kết nối khi:

- Remote IP nằm trong dải Tailscale (100.64–127.x.x) và advertised IP khác remote IP.
- Advertised IP nằm trong Docker bridge (172.16–31.x.x) hoặc Tailscale.

## 2.4. Heartbeat và phát hiện peer chết (Dead-Peer Detection)

### 2.4.1. Cơ chế Heartbeat

Heartbeat là cơ chế keep-alive giúp Bootstrap Server theo dõi trạng thái sống của mỗi peer:

- **Tần suất:** Peer gửi `HEARTBEAT` mỗi **5 giây** tới Bootstrap.
- **Timeout:** Peer không heartbeat trong **45 giây** (9 chu kỳ liên tiếp) sẽ bị coi là dead.
- **HEARTBEAT_ACK:** Bootstrap xác nhận và gửi kèm danh sách peer mới nhất.

### 2.4.2. Phát hiện peer chết

Bootstrap Server chạy một **ScheduledExecutorService** kiểm tra định kỳ mỗi 5 giây:

```
checkDeadPeers(timeout=45000ms):
  Với mỗi peer online:
    Nếu now - lastHeartbeat > 45000ms:
        → registry.removeDeadPeer(username)
        → broadcast PEER_LEAVE đến tất cả peer online
```

### 2.4.3. So sánh Leave graceful và Crash

| Hành vi | Graceful (PEER_LEAVE) | Crash (timeout) |
|---------|---------------------|----------------|
| Peer gửi thông báo | Có | Không |
| Bootstrap xóa ngay | Có | Không (chờ timeout) |
| Broadcast PEER_LEAVE | Ngay lập tức | Sau 45s |
| Peer khác phát hiện | < 1s | 5 – 45s |

## 2.5. Cơ chế Store-and-Forward và Mailbox Server

### 2.5.1. Bài toán tin nhắn offline

Khi người nhận không online, gửi trực tiếp TCP sẽ thất bại. Giải pháp: **Mailbox Server** — một server trung tâm lưu tin nhắn tạm thời.

### 2.5.2. Luồng hoạt động

```
1. Sender gửi DIRECT_MESSAGE đến Receiver
   → TCP thất bại sau 3 lần retry
2. Sender gửi STORE_MESSAGE đến Mailbox Server
   → Mailbox lưu: offline_messages["receiver"] += encrypted_msg
   → Mailbox gửi STORE_ACK
3. Receiver online, gửi PULL_MESSAGES đến Mailbox
   → Mailbox trả tất cả tin nhắn chờ
4. Receiver gửi DELIVERY_ACK
   → Mailbox đánh dấu DELIVERED
```

### 2.5.3. Đặc điểm Mailbox Server

- **TTL 7 ngày:** Tin nhắn hết hạn sau 7 ngày nếu không được nhận.
- **Mã hóa đầu cuối (E2EE):** Payload được mã hóa bằng khóa identity của người nhận; Mailbox chỉ lưu blob không đọc được nội dung.
- **Group message ack:** Mỗi thành viên gửi delivery ack riêng; Mailbox theo dõi trạng thái per-member.

## 2.6. Chat nhóm DHT-Lite

### 2.6.1. Mô hình DHT-Lite

DHT (Distributed Hash Table) là cấu trúc dữ liệu phân tán ánh xạ **khóa → giá trị**. **DHT-Lite** là phiên bản đơn giản hóa, phù hợp cho hệ thống chat nhóm quy mô nhỏ/vừa.

Trong hệ thống này, mỗi group có **coordinator** (peer tạo group hoặc được bầu chọn theo thuật toán HRW — Highest Random Weight):

- **HRW (Rendezvous Hashing):** Chọn coordinator dựa trên hash(groupId + peerId), đảm bảo phân bố đều và nhất quán khi thành viên thay đổi.
- **Top-K replicas:** Nhiều peer có thể giữ bản sao group metadata để tăng độ bền.

### 2.6.2. Các thao tác nhóm

| Thao tác | Mô tả |
|---------|--------|
| `COORD_INIT` | Khởi tạo group metadata tại coordinator |
| `GROUP_ADD` | Thêm member qua coordinator |
| `GROUP_LEAVE` | Member tự rời group |
| `GROUP_KICK` | Coordinator kick member |
| `GROUP_DISBAND` | Giải tán group |
| `GROUP_GOSSIP` | Peer lan truyền group metadata đến các peer liên quan |

### 2.6.3. Group Resync và Repair

Khi group metadata thay đổi (thêm/kick member), coordinator gửi thông báo `GROUP_UPDATED` hoặc `GROUP_KICKED`/`GROUP_DISBANDED` đến tất cả member. Peer offline khi thay đổi xảy ra có thể yêu cầu `GROUP_RESYNC_REQ` để đồng bộ lại.

## 2.7. Chuyển giao file trong P2P

### 2.7.1. Quy trình chuyển file

```
1. Sender chọn file trong Web UI
2. Sender tính SHA-256 hash của toàn bộ file
3. Sender gửi FILE_OFFER đến Receiver/Group:
   {
     "fileName": "document.pdf",
     "fileSize": 1048576,
     "sha256": "abc123...",
     "filePort": 35143,
     "transferId": "uuid"
   }
4. Receiver chấp nhận → FILE_ACCEPT hoặc từ chối → FILE_REJECT
5. Nếu chấp nhận:
   - Receiver kết nối TCP đến filePort của Sender
   - Receiver tải file theo chunk 64 KB
   - Mỗi chunk verify bằng SHA-256
   - Progress cập nhật realtime qua WebSocket
6. Hoàn tất → FILE_DONE → FILE_ACK
```

### 2.7.2. Đặc điểm kỹ thuật

- **Chunk size:** 64 KB mỗi chunk.
- **Giới hạn:** Tối đa 100 MB mỗi file.
- **Resume:** Lưu checkpoint chunk cuối đã nhận; nếu kết nối bị gián đoạn, receiver gửi `FILE_RESUME` để tiếp tục từ chunk checkpoint.
- **File group offer:** Một file có thể được gửi đồng thời đến nhiều receiver qua group chat.

## 2.8. Mã hóa đầu cuối (End-to-End Encryption — E2EE)

### 2.8.1. Mục đích

E2EE đảm bảo **chỉ người gửi và người nhận** có thể đọc nội dung tin nhắn. Ngay cả Mailbox Server cũng không thể giải mã vì payload được mã hóa trước khi gửi.

### 2.8.2. Cơ chế

- Mỗi peer có một cặp **khóa identity** (public key + private key).
- Public key được đăng ký với Bootstrap Server cùng peer info.
- Khi gửi tin nhắn, sender mã hóa payload bằng public key của receiver.
- Receiver giải mã bằng private key của mình.

## 2.9. Công nghệ sử dụng

| Công nghệ | Phiên bản | Mục đích |
|-----------|----------|----------|
| Java | 17+ | Backend TCP, HTTP, storage logic |
| Maven Shade Plugin | 3.5.1 | Build fat JAR |
| Javalin | 6.1.3 | HTTP/WebSocket server cho Web UI |
| React | 18.2 | Frontend Web UI |
| SQLite JDBC | 3.45.1.0 | Database cục bộ peer & mailbox |
| Gson | 2.10.1 | JSON serialization/deserialization |
| Docker | 20+ | Container hóa các thành phần |

---

# CHƯƠNG III. PHÂN TÍCH VÀ THIẾT KẾ HỆ THỐNG CHAT P2P

## 3.1. Kiến trúc tổng thể

### 3.1.1. Mô hình Hybrid P2P với Bootstrap Server

Hệ thống P2PChat sử dụng mô hình **Hybrid P2P** — kết hợp ưu điểm của mạng ngang hàng thuần túy (dữ liệu chat truyền trực tiếp P2P, không qua server trung tâm) với một số thành phần tập trung nhẹ để hỗ trợ vận hành.

Trong mô hình này, **Bootstrap Server** đóng vai trò điểm vào mạng (entry point) duy nhất cho mỗi peer. Khi một peer mới tham gia, nó chỉ cần kết nối đến Bootstrap Server để:

- Đăng ký thông tin (username, host, port, public key)
- Nhận danh sách các peer đang online
- Được thông báo khi có peer mới tham gia hoặc rời khỏi mạng
- Biết địa chỉ của Mailbox Server

Sau khi có danh sách peer, mọi giao tiếp chat (tin nhắn trực tiếp, nhóm, broadcast, chuyển file) đều được truyền **trực tiếp** giữa các peer qua TCP, không qua Bootstrap Server hay bất kỳ server trung tâm nào. Điều này đảm bảo:

- **Tính riêng tư:** Không server trung tâm nào đọc được nội dung chat
- **Khả năng chịu lỗi:** Nếu Bootstrap Server tạm thời không khả dụng, các peer đã đăng ký vẫn có thể chat trực tiếp với nhau
- **Giảm tải:** Bootstrap Server không phải xử lý lưu lượng chat

### 3.1.2. Các thành phần và vai trò

Hệ thống gồm 4 thành phần chính, mỗi thành phần có vai trò riêng biệt:

| Thành phần | Vai trò | Giao tiếp | Lưu trữ |
|------------|---------|-----------|---------|
| **Bootstrap Server** | Điểm vào mạng, đăng ký peer, heartbeat, peer discovery, broadcast sự kiện, dashboard giám sát | TCP `:9000` (peer), HTTP `:9001` (dashboard) | `registry.json` (PeerRegistry) |
| **Mailbox Server** | Lưu tin nhắn offline khi người nhận không online, gửi lại khi người nhận online, theo dõi delivery ack per-member cho group message | TCP `:9100` | SQLite `mailbox.db` |
| **Peer Node** | Mỗi người dùng chạy một peer — nhận và gửi tin nhắn P2P, điều phối group chat, truyền file, mã hóa E2EE, retry outbox | 3 TCP port: web (Javalin), peer (P2P), file | SQLite tại `data/peers/<username>` |
| **Peer Web (Frontend)** | Giao diện người dùng trên trình duyệt, kết nối WebSocket realtime | WebSocket → Peer Node | Không |

### 3.1.3. Luồng xử lý tổng quát

Luồng xử lý của hệ thống khi một peer gửi tin nhắn đến peer khác như sau:

**Bước 1 — Peer tham gia mạng:**
Peer mới kết nối TCP đến Bootstrap Server (port 9000), gửi `REGISTER` kèm thông tin. Bootstrap xác nhận bằng `REGISTER_ACK`, gửi danh sách peer online và gửi thông báo `PEER_JOIN` đến các peer khác.

**Bước 2 — Gửi tin nhắn:**
Sender tìm endpoint của receiver trong danh sách peer, mở socket TCP trực tiếp đến receiver (port peerPort), gửi `DIRECT_MESSAGE` đã mã hóa E2EE. Receiver nhận, giải mã, gửi `ACK`. Tin nhắn được lưu vào SQLite.

**Bước 3 — Xử lý offline:**
Nếu TCP gửi thất bại sau 3 lần retry, sender gửi `STORE_MESSAGE` đến Mailbox Server. Mailbox lưu và trả `STORE_ACK`. Khi receiver online, nó gửi `PULL_MESSAGES` đến Mailbox, nhận các tin nhắn chờ, và gửi `DELIVERY_ACK`.

**Bước 4 — Chat nhóm:**
Sender gửi `GROUP_MESSAGE` đến tất cả thành viên trực tiếp qua P2P. Coordinator (được chọn bằng HRW) quản lý metadata nhóm. Tin nhắn nhóm offline được gửi qua Mailbox cho thành viên không online.

## 3.2. Giao thức trao đổi thông điệp

### 3.2.1. Cấu trúc thông điệp

Tất cả thông điệp trong hệ thống được truyền dưới dạng **JSON newline-delimited** qua kết nối TCP. Mỗi thông điệp là một dòng JSON kết thúc bằng ký tự xuống dòng `\n`. Các trường chung:

| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `type` | String | Có | Loại thông điệp, xác định hành động cần xử lý |
| `messageId` | String (UUID v4) | Có | Định danh duy nhất toàn cục cho mỗi thông điệp |
| `sender` | String | Có | Username của người gửi |
| `receiver` | String | Có* | Username người nhận (null khi là broadcast) |
| `groupId` | String | Có* | ID nhóm nếu là thông điệp nhóm (null khi là direct) |
| `content` | String | Có* | Nội dung thông điệp, có thể đã mã hóa E2EE |
| `timestamp` | Long | Có | Thời gian gửi (epoch milliseconds) |

*(* = tùy loại thông điệp, có thể null)*

Ưu điểm của định dạng JSON newline-delimited:
- Parse đơn giản, debug dễ dàng với bất kỳ công cụ nào đọc được JSON
- Không cần schema cứng như Protobuf/Thrift
- Tương thích với mọi ngôn ngữ lập trình
- Đọc streaming được: mỗi dòng là một thông điệp độc lập

### 3.2.2. Các loại thông điệp và ý nghĩa

Hệ thống định nghĩa **hơn 40 loại thông điệp**, chia thành 6 nhóm:

**Nhóm 1: Bootstrap và Peer Discovery**

| Loại thông điệp | Hướng | Mô tả |
|-----------------|-------|-------|
| `REGISTER` | Peer → Bootstrap | Peer gửi thông tin đăng ký: username, host, peerPort, publicKey, keyId |
| `REGISTER_ACK` | Bootstrap → Peer | Bootstrap xác nhận đăng ký thành công, gửi kèm danh sách peer online |
| `REGISTER_NACK` | Bootstrap → Peer | Bootstrap từ chối đăng ký (username đã tồn tại hoặc lỗi khác) |
| `PEER_JOIN` | Bootstrap → Peer | Thông báo có peer mới tham gia mạng |
| `PEER_LEAVE` | Bootstrap → Peer | Thông báo peer rời khỏi mạng (graceful hoặc do timeout) |
| `HEARTBEAT` | Peer → Bootstrap | Peer gửi keep-alive định kỳ mỗi 5 giây |
| `HEARTBEAT_ACK` | Bootstrap → Peer | Bootstrap xác nhận heartbeat, gửi kèm danh sách peer mới nhất |
| `DISCOVER` | Peer → Bootstrap | Yêu cầu danh sách đầy đủ các peer online |
| `PEER_LIST` | Bootstrap → Peer | Danh sách tất cả peer online |
| `PEER_OFFLINE` | Bootstrap → Peer | Thông báo peer cụ thể vừa chuyển sang offline |
| `RESOLVE_MAILBOX` | Peer → Bootstrap | Yêu cầu endpoint của Mailbox Server |
| `RESOLVE_MAILBOX_ACK` | Bootstrap → Peer | Trả về địa chỉ Mailbox Server (host:port) |

**Nhóm 2: Tin nhắn Chat**

| Loại thông điệp | Hướng | Mô tả |
|-----------------|-------|-------|
| `DIRECT_MESSAGE` | Peer ↔ Peer | Tin nhắn trực tiếp giữa hai peer, content đã mã hóa E2EE |
| `BROADCAST` | Peer → tất cả Peer online | Tin nhắn gửi đến mọi peer đang online |
| `ACK` | Peer → Peer | Xác nhận đã nhận được một thông điệp |
| `TYPING` | Peer → Peer | Tín hiệu người dùng đang nhập tin |
| `SYSTEM` | Peer → Peer | Tin nhắn hệ thống (thông báo trạng thái) |

**Nhóm 3: Nhóm Chat — Control Plane**

| Loại thông điệp | Hướng | Mô tả |
|-----------------|-------|-------|
| `GROUP_CREATE` | Peer → Bootstrap | Yêu cầu tạo nhóm mới |
| `COORD_INIT` | Peer (creator) → tất cả peer liên quan | Khởi tạo metadata nhóm tại các coordinator |
| `COORD_GOSSIP` | Coordinator ↔ Coordinator | Gossip định kỳ giữa các coordinator để đồng bộ metadata |
| `GROUP_JOINED` | Coordinator → peer mới | Thông báo peer đã được thêm vào nhóm |
| `GROUP_ADD` | Peer → Coordinator | Yêu cầu thêm thành viên vào nhóm |
| `GROUP_LEAVE` | Peer → Coordinator + các member | Peer tự rời nhóm |
| `GROUP_KICK` | Coordinator → peer bị kick | Thông báo peer bị loại khỏi nhóm |
| `GROUP_UPDATED` | Coordinator → tất cả member | Thông báo thành viên nhóm thay đổi |
| `GROUP_DISBAND` | Coordinator → tất cả member | Giải tán nhóm |
| `GROUP_DISBANDED` | Coordinator → tất cả member | Xác nhận nhóm đã giải tán |

**Nhóm 4: Nhóm Chat — Data Plane**

| Loại thông điệp | Hướng | Mô tả |
|-----------------|-------|-------|
| `GROUP_MESSAGE` | Sender → tất cả member | Tin nhắn gửi trong nhóm, kèm Lamport timestamp |
| `GROUP_RESYNC_REQ` | Peer → Coordinator | Yêu cầu đồng bộ lại group metadata khi online trở lại |
| `GROUP_RESYNC_RESP` | Coordinator → Peer | Trả về GroupInfo đầy đủ |
| `CACHE_STALE` | Peer → Peer | Thông báo group cache của peer đã lỗi thời |
| `GROUP_HISTORY_DIGEST` | Peer → Peer | Danh sách ID các tin nhắn nhóm đã nhận |
| `GROUP_HISTORY_REQUEST` | Peer → Peer | Yêu cầu gửi lại tin nhắn nhóm cụ thể |
| `GROUP_HISTORY_RESPONSE` | Peer → Peer | Trả về nội dung các tin nhắn nhóm được yêu cầu |

**Nhóm 5: Mailbox và Offline**

| Loại thông điệp | Hướng | Mô tả |
|-----------------|-------|-------|
| `STORE_MESSAGE` | Peer → Mailbox | Gửi tin nhắn offline đến Mailbox Server |
| `STORE_ACK` | Mailbox → Peer | Xác nhận đã lưu tin nhắn offline |
| `STORE_NACK` | Mailbox → Peer | Mailbox từ chối lưu (quota exceeded, lỗi khác) |
| `PULL_MESSAGES` | Peer → Mailbox | Yêu cầu lấy tất cả tin nhắn offline đang chờ |
| `PULL_RESPONSE` | Mailbox → Peer | Trả về danh sách tin nhắn offline |
| `DELIVERY_ACK` | Peer → Mailbox | Xác nhận đã nhận được tin nhắn offline |
| `MAILBOX_MESSAGE_EXPIRED` | Mailbox → Peer | Thông báo một tin nhắn đã hết hạn |

**Nhóm 6: Truyền File**

| Loại thông điệp | Hướng | Mô tả |
|-----------------|-------|-------|
| `FILE_OFFER` | Peer → Peer/Group | Sender gửi offer chứa filename, kích thước, SHA-256, transferId, filePort |
| `FILE_ACCEPT` | Peer → Peer | Receiver chấp nhận nhận file |
| `FILE_REJECT` | Peer → Peer | Receiver từ chối nhận file |
| `FILE_DONE` | Peer → Peer | Sender thông báo đã gửi xong toàn bộ file |
| `FILE_ACK` | Peer → Peer | Receiver xác nhận đã nhận đủ dữ liệu và kiểm tra SHA-256 thành công |
| `FILE_RESUME` | Peer → Peer | Receiver yêu cầu tiếp tục từ chunk đã checkpoint |
| `FILE_CANCEL` | Peer → Peer | Một trong hai bên hủy transfer |
| `FILE_PROGRESS` | Peer → Peer | Thông báo tiến độ transfer qua WebSocket |

### 3.2.3. Quy trình trao đổi

**Quy trình 1: Đăng ký Peer**

```
Peer Alice              Bootstrap Server            Peer Bob (đã online)
    │                         │                          │
    │── REGISTER ────────────►│                          │
    │   (username,            │                          │
    │    host, port,          │                          │
    │    publicKey, keyId)    │                          │
    │                         │                          │
    │                         │── PEER_JOIN (Alice) ────►│
    │                         │                          │
    │◄── REGISTER_ACK ────────│                          │
    │   (peerList: [Bob])     │                          │
    │                         │                          │
    │◄── OFFLINE_MESSAGE* ────│  (nếu có tin nhắn chờ) │
    │   (các tin nhắn offline │                          │
    │    gửi cho Alice)       │                          │
```

**Quy trình 2: Gửi Direct Message (receiver online)**

```
Sender                           Receiver
   │                                 │
   │── DIRECT_MESSAGE (encrypted) ──►│
   │   messageId: uuid,              │
   │   content: [E2EE encrypted]     │
   │                                 │
   │◄── ACK ─────────────────────────│
   │   messageId: uuid (đã nhận)    │
```

**Quy trình 3: Gửi Direct Message (receiver offline — fallback)**

```
Sender               Mailbox Server           Receiver
   │                     │                       │
   │── STORE_MESSAGE ───►│                       │
   │   (encrypted blob,  │                       │
   │    receiver, ttl)   │                       │
   │◄── STORE_ACK ───────│                       │
   │                     │                       │
   │                     │◄── PULL_MESSAGES ─────│ (khi online trở lại)
   │                     │── PULL_RESPONSE ─────►│ (gửi các tin nhắn chờ)
   │                     │◄── DELIVERY_ACK ─────│
```

**Quy trình 4: Tạo và gửi tin nhắn nhóm**

```
Creator           Bootstrap     Coordinator        Members
   │                 │              │               │
   │── GROUP_CREATE ─►│              │               │
   │ (tính HRW)      │              │               │
   │                 │── COORD_INIT ───────────────►│
   │                 │── GROUP_JOINED ─────────────►│
   │                 │              │               │
   │── GROUP_MESSAGE ──────────────────────────────────►│
   │ (gửi trực tiếp  │              │               │
   │  P2P đến mỗi   │              │               │
   │  member)        │              │               │
```

## 3.3. Cơ chế Peer Discovery

### 3.3.1. Mô hình Khám phá tập trung

Để một peer mới có thể tham gia mạng P2P, nó cần biết ít nhất một peer đang hoạt động. Trong hệ thống này, **Bootstrap Server** đóng vai trò điểm vào mạng tập trung — mỗi peer khi khởi động đều kết nối TCP trực tiếp đến Bootstrap Server để đăng ký.

`PeerRegistry` bên trong Bootstrap Server lưu trữ thông tin của mỗi peer:

```
peers: Map<String, PeerInfo>
  └── username → PeerInfo(host, peerPort, publicKey, keyId, lastHeartbeat, online)

offlineMessages: Map<String, List<Message>>
  └── receiver → [tin nhắn offline đang chờ giao]
```

`PeerRegistry` được lưu bền vững ra file `registry.json` sau mỗi thao tác thay đổi (đăng ký, hủy đăng ký), và được đọc lại khi Bootstrap Server khởi động lại. Nhờ đó, trạng thái mạng không bị mất khi Bootstrap Server restart.

Khi một peer gửi `REGISTER`, Bootstrap Server xử lý theo các bước:
1. Kiểm tra username đã tồn tại chưa → từ chối nếu trùng
2. Phát hiện NAT: so sánh IP quảng bá (advertised) với IP thực từ socket kết nối. Nếu remote IP thuộc dải Docker bridge (172.16–31.x.x) hoặc Tailscale (100.64–127.x.x) và khác advertised IP, ghi đè advertised IP bằng remote IP
3. Lưu thông tin peer vào registry
4. Nếu có tin nhắn offline cho peer → gửi kèm `REGISTER_ACK`
5. Broadcast `PEER_JOIN` đến tất cả peer online khác
6. Trả `REGISTER_ACK` kèm danh sách peer online

### 3.3.2. Lan truyền cấu trúc mạng kiểu đẩy

Sau khi một peer mới đăng ký thành công, Bootstrap Server **đẩy** thông tin về peer mới này đến tất cả peer đang online qua thông điệp `PEER_JOIN`. Tương tự, khi một peer rời khỏi mạng (graceful `PEER_LEAVE` hoặc do dead-peer detection), Bootstrap Server đẩy `PEER_LEAVE` đến tất cả peer còn lại.

Cơ chế đẩy này đảm bảo mỗi peer online luôn có danh sách peer mới nhất mà không cần chủ động hỏi. Danh sách này được gửi kèm trong mọi `HEARTBEAT_ACK` từ Bootstrap Server, nên peer luôn nhận bản cập nhật định kỳ mà không cần gửi `DISCOVER`.

Ngoài ra, `HEARTBEAT_ACK` cũng gửi kèm danh sách peer mới nhất, tạo cơ hội để peer cập nhật danh sách mà không cần yêu cầu riêng.

### 3.3.3. Bộ đệm định tuyến và Khả năng tự phục hồi

**Bộ đệm định tuyến (Routing Buffer):**
Mỗi peer lưu trong `known_peers` table: username, host, port, trạng thái online, last_seen. Danh sách này đóng vai trò bộ đệm định tuyến cục bộ — cho phép peer biết cách kết nối đến một peer khác mà không cần hỏi Bootstrap mỗi lần.

**Recent Peers Cache:**
Khi kết nối đến Bootstrap Server thất bại (ví dụ Bootstrap tạm thời không khả dụng), peer sử dụng **Recent Peers Cache** — lưu thông tin 5 peer gần nhất đã kết nối thành công. Peer có thể khởi tạo kết nối trực tiếp đến các peer trong cache nếu cần.

**Khả năng tự phục hồi:**
- Nếu Bootstrap Server restart: peer tự đăng ký lại qua `REGISTER`
- Nếu một peer khác restart: peer gửi heartbeat và nhận `PEER_JOIN` từ Bootstrap
- Nếu kết nối trực tiếp P2P thất bại: peer tự động thử lại qua outbox retry

## 3.4. Cơ chế chat nhóm

### 3.4.1. Bầu cử phi tập trung bằng Rendezvous Hashing

Mỗi nhóm chat trong hệ thống có **K = 3 coordinator** được chọn bằng thuật toán **HRW (Highest Random Weight)** — còn gọi là Rendezvous Hashing. HRW đảm bảo rằng với cùng một tập thành viên và cùng một groupId, tất cả peer đều tính ra cùng một tập hợp K coordinator mà không cần trao đổi.

Công thức HRW cho mỗi peer trong nhóm:

```
hash_i = SHA256(groupId + peerId)
Chọn K peer có hash_i lớn nhất làm coordinator
```

Ví dụ: Nhóm có thành viên {alice, bob, duc, hoang, hoan} và groupId = "group-X":
- Tính hash cho mỗi thành viên: hash("group-X" + "alice"), hash("group-X" + "bob"), ...
- Sắp xếp giảm dần theo hash
- 3 peer có hash lớn nhất → coordinator

**Tính chất quan trọng của HRW:**
- **Nhất quán:** Khi thành viên thay đổi (thêm/kick), chỉ một số nhỏ thành viên thay đổi vai trò coordinator
- **Phân bố đều:** Không có coordinator "quá tải" vì hash là pseudorandom
- **Không cần bầu chọn:** Không cần trao đổi message để bầu coordinator — mỗi peer tự tính được

Khi một peer tạo nhóm mới, nó tự động trở thành một trong các coordinator (vì HRW tính trên toàn bộ thành viên bao gồm cả creator). Coordinator lưu trữ metadata nhóm (danh sách thành viên, thời gian tạo, thời gian cập nhật cuối) trong `group_cache` table.

### 3.4.2. Giao thức Gossip và Giải quyết xung đột

Để các coordinator đồng bộ metadata nhóm, hệ thống sử dụng **giao thức Gossip** — mỗi coordinator định kỳ chọn ngẫu nhiên một coordinator khác để trao đổi metadata.

Chu kỳ gossip: **5 giây**

Trong mỗi chu kỳ, mỗi coordinator:
1. Gửi `COORD_GOSSIP` đến tất cả các coordinator khác trong nhóm
2. `COORD_GOSSIP` chứa vector version của metadata: `{version, members, updatedAt}`
3. Nếu nhận được version mới hơn → cập nhật local group_cache
4. Nếu không nhận được gossip từ một coordinator trong **3 chu kỳ liên tiếp** (15 giây) → coordinator đó bị coi là dead

**Giải quyết xung đột:**
Khi có xung đột (hai coordinator cùng thay đổi metadata cùng lúc), hệ thống dùng timestamp `updatedAt` để phân giải — metadata có `updatedAt` lớn hơn (mới hơn) được ưu tiên. Nếu timestamp bằng nhau, dùng username của coordinator để phân thứ tự (tie-breaker).

### 3.4.3. Cơ chế Cập nhật Thành viên và Vai trò của Coordinator

**Thêm thành viên:**
1. Một thành viên gửi `GROUP_ADD` đến coordinator
2. Coordinator cập nhật danh sách thành viên trong group_cache
3. Coordinator gửi `COORD_GOSSIP` để đồng bộ với các coordinator khác
4. Coordinator gửi `GROUP_JOINED` đến peer mới
5. Coordinator gửi `GROUP_UPDATED` đến tất cả thành viên cũ

**Kick thành viên:**
1. Coordinator (hoặc creator) gửi `GROUP_KICK` đến peer bị kick
2. Coordinator cập nhật danh sách, gửi `COORD_GOSSIP`
3. Coordinator gửi `GROUP_UPDATED` đến các thành viên còn lại
4. Peer bị kick nhận `GROUP_KICKED`, xóa group khỏi local cache

**Rời nhóm:**
1. Peer gửi `GROUP_LEAVE` đến coordinator và tất cả thành viên
2. Nếu leaver là coordinator duy nhất còn lại → chuyển quyền coordinator cho thành viên có HRW rank cao nhất
3. Coordinator cập nhật danh sách, gửi `GROUP_UPDATED`

**Giải tán nhóm:**
1. Creator/Coordinator gửi `GROUP_DISBAND` đến tất cả thành viên
2. Tất cả peer xóa group khỏi local cache
3. Coordinator xóa group metadata

### 3.4.5. Nhất quán cuối trong Control Plane

Hệ thống chat nhóm đạt **eventual consistency** trong control plane (metadata nhóm) nhờ:

- **Gossip định kỳ:** Mỗi 5 giây, các coordinator trao đổi metadata. Sau vài vòng gossip, tất cả coordinator có cùng một bản metadata.
- **Monotonic clock:** Mỗi peer có `LamportClock` cục bộ, tăng mỗi khi nhận hoặc gửi message. Lamport timestamp đảm bảo thứ tự nhất quán giữa các peer.
- **200ms buffer window:** Khi nhận tin nhắn nhóm, peer đưa vào buffer 200ms, sắp xếp theo Lamport timestamp trước khi hiển thị. Điều này xử lý tin nhắn đến không đúng thứ tự do network latency khác nhau.
- **Self-healing:** Khi một peer online trở lại sau thời gian dài offline, `LazyRepairManager` phát hiện `CACHE_STALE` và gửi `GROUP_RESYNC_REQ` đến coordinator để lấy lại metadata và tin nhắn nhóm bịmiss.

## 3.5. Cơ chế truyền file

### 3.5.1. Giao thức Thỏa thuận kênh truyền

Trước khi truyền dữ liệu file, sender và receiver phải thỏa thuận qua các bước:

**Bước 1 — Offer (Sender gửi):**
Sender gửi `FILE_OFFER` qua P2P TCP (peer port) chứa:
- `transferId`: UUID định danh phiên truyền
- `fileName`: tên file
- `fileSize`: kích thước bytes
- `sha256`: hash SHA-256 của toàn file (để xác minh toàn vẹn)
- `filePort`: TCP port mà sender lắng nghe để nhận kết nối file transfer
- `direction`: "SENDING" hoặc "RECEIVING" (hướng gửi đến nhóm)
- `peer`: username người nhận (hoặc groupId nếu gửi nhóm)

**Bước 2 — Accept/Reject (Receiver phản hồi):**
Receiver kiểm tra fileSize (giới hạn 100 MB), hiển thị thông báo cho người dùng. Người dùng chọn:
- Chấp nhận → gửi `FILE_ACCEPT`
- Từ chối → gửi `FILE_REJECT`, phiên truyền kết thúc

**Bước 3 — Kết nối kênh dữ liệu:**
Nếu receiver chấp nhận, nó mở socket TCP đến `filePort` của sender (port = peerPort + 1000), bắt đầu nhận dữ liệu.

### 3.5.2. Khởi tạo Luồng nhị phân và Cắt mảnh

Dữ liệu file được chia thành các **chunk 64 KB** và truyền tuần tự qua kênh file TCP:

```
┌────────────┬──────────┬──────────┬──────────┐
│  4 bytes   │ 64 KB     │  4 bytes  │ 64 KB    │ ... → EOF
│ chunk_len  │ chunk_1   │ chunk_len │ chunk_2  │
└────────────┴──────────┴──────────┴──────────┘
```

Mỗi chunk được gửi theo định dạng:
1. **Độ dài chunk** (4 bytes, big-endian integer): cho biết số bytes của chunk tiếp theo
2. **Dữ liệu chunk**: 64 KB cuối cùng có thể nhỏ hơn 64 KB

Sau chunk cuối cùng, sender gửi chunk độ dài 0 (`writeInt(0)`) để báo hiệu kết thúc file.

Sender tính toán SHA-256 hash trên toàn bộ file trước khi truyền (đã gửi trong `FILE_OFFER`). Sau khi truyền xong, sender gửi `FILE_DONE`. Receiver tính lại SHA-256 trên file đã nhận và so sánh với giá trị trong `FILE_OFFER`. Nếu khớp → gửi `FILE_ACK`. Nếu sai → báo lỗi, transfer coi như thất bại.

**Giới hạn kỹ thuật:**

| Thông số | Giá trị |
|----------|---------|
| Kích thước chunk | 64 KB (65536 bytes) |
| Giới hạn file | 100 MB |
| Số chunk tối đa | ~1600 chunk cho file 100 MB |
| Xác minh toàn vẹn | SHA-256 toàn bộ file |

### 3.5.3. Cơ chế Phục hồi đứt gãy

Trong quá trình truyền, kết nối TCP có thể bị gián đoạn (mất mạng, peer crash). Hệ thống hỗ trợ **resume** — tiếp tục truyền từ chunk cuối cùng đã nhận thay vì truyền lại từ đầu.

Checkpoint được lưu vào bảng `file_chunks` trong SQLite:
- `transfer_id`: ID của phiên truyền
- `chunk_number`: số thứ tự chunk đã nhận
- `received`: boolean, true nếu chunk đã nhận đầy đủ

Khi receiver muốn resume:
1. Mở lại socket TCP đến filePort của sender
2. Gửi `FILE_RESUME` chứa: `transferId`, `chunkNumber` (chunk cuối đã nhận thành công)
3. Sender bắt đầu gửi từ chunk tiếp theo sau `chunkNumber`
4. Receiver bỏ qua các chunk đã nhận (dựa vào checkpoint)

### 3.5.4. Xác thức Toàn vẹn và Đóng kết nối

**Xác thực toàn vẹn:**
SHA-256 hash của toàn file được tính tại sender trước khi truyền (hoặc streaming hash). Hash này được gửi trong `FILE_OFFER`. Sau khi nhận đủ dữ liệu, receiver tính lại SHA-256 và so sánh:
- Khớp → `FILE_ACK`, transfer hoàn tất, progress = 100%
- Sai → báo lỗi, file không được lưu

**Đóng kết nối:**
Luồng đóng kết nối chuẩn:
1. Sender gửi chunk độ dài 0 (EOF marker)
2. Sender gửi `FILE_DONE`
3. Receiver xác minh SHA-256
4. Receiver gửi `FILE_ACK` hoặc báo lỗi
5. Cả hai đóng socket

Trường hợp hủy:
- Một trong hai bên gửi `FILE_CANCEL` → đóng socket, xóa checkpoint

## 3.6. Cơ chế Store-and-Forward và Mailbox Server

### 3.6.1. Bài toán tin nhắn offline

Trong mạng P2P, khi người nhận không online, gửi trực tiếp TCP sẽ thất bại. Nếu không có cơ chế lưu trữ, tin nhắn sẽ bị mất hoàn toàn. **Mailbox Server** giải quyết vấn đề này bằng cách đóng vai trò kho lưu trữ tạm thời cho các tin nhắn offline.

### 3.6.2. Kiến trúc Mailbox Server

Mailbox Server là một TCP server độc lập (port 9100), sử dụng SQLite làm cơ sở dữ liệu. Nó hoạt động hoàn toàn stateless trên mỗi kết nối — mỗi thông điệp được xử lý độc lập, không có session state giữa các request.

**Cơ sở dữ liệu Mailbox Server:**

Bảng `offline_messages` lưu các tin nhắn offline với các trường:
- `id` (PK): messageId
- `receiver`: username người nhận
- `encrypted_payload`: blob E2EE (Mailbox không đọc được nội dung)
- `sender`: username người gửi
- `timestamp`: thời gian gửi gốc
- `ttl`: Unix timestamp hết hạn (= timestamp + 7 ngày)
- `status`: STORED → DELIVERED hoặc EXPIRED
- `group_id`: null cho direct message, groupId cho group message

Bảng `group_delivery_acks` theo dõi delivery status per-member cho group messages:
- `message_id + username` (PK kép): message và thành viên
- `delivered_at`: thời gian member gửi DELIVERY_ACK

### 3.6.3. Luồng Store-and-Forward chi tiết

**Gửi tin offline (direct):**
1. Sender gửi `DIRECT_MESSAGE` đến receiver → TCP thất bại sau 3 retry
2. Sender gửi `STORE_MESSAGE` đến Mailbox: `{receiver, encryptedPayload, sender, timestamp, groupId=null}`
3. Mailbox lưu vào `offline_messages` với status STORED, ttl = timestamp + 7 ngày → trả `STORE_ACK`
4. Sender cập nhật outbox status = STORED_MAILBOX

**Kéo tin offline (receiver online):**
1. Receiver gửi `PULL_MESSAGES` đến Mailbox: `{receiver, limit=100}`
2. Mailbox trả `PULL_RESPONSE` chứa danh sách tin nhắn direct và group message đang STORED cho receiver
3. Receiver giải mã E2EE, hiển thị tin nhắn, gửi `DELIVERY_ACK` cho từng tin
4. Mailbox cập nhật status = DELIVERED

**Group message offline:**
1. Khi sender gửi `GROUP_MESSAGE` đến thành viên offline → mỗi thành viên offline được gửi một `STORE_MESSAGE` riêng đến Mailbox
2. Mailbox lưu với `group_id` và `status = STORED`
3. Khi thành viên online, gửi `PULL_MESSAGES` → nhận tất cả group message chưa DELIVERY_ACK
4. Thành viên gửi `DELIVERY_ACK` → Mailbox ghi vào `group_delivery_acks`

### 3.6.4. TTL và Dọn dẹp

Mailbox Server có một scheduled task chạy kiểm tra định kỳ: với mỗi tin nhắn trong `offline_messages`, nếu `now > ttl`, cập nhật status = EXPIRED và thông báo `MAILBOX_MESSAGE_EXPIRED` cho receiver (nếu online). Các tin nhắn EXPIRED được giữ trong database để phục vụ mục đích log/đánh giá, có thể dọn dẹp bằng job riêng.

---

# CHƯƠNG IV. TRIỂN KHAI HỆ THỐNG

## 4.1. Môi trường và công nghệ sử dụng

| Công nghệ | Phiên bản | Vai trò |
|-----------|-----------|---------|
| Java | 17+ | Backend: Bootstrap, Mailbox, Peer |
| Maven (Shade Plugin) | 3.9+ | Build fat JAR cho mỗi module |
| Javalin | 6.1.3 | HTTP server + WebSocket trong Peer Node |
| React | 18.2 | Frontend Web UI |
| SQLite JDBC | 3.45.1.0 | Database cục bộ tại mỗi peer và Mailbox |
| Gson | 2.10.1 | JSON serialization/deserialization |
| Docker | 20+ | Container hóa toàn bộ hệ thống |
| Tailscale | Mới nhất | VPN cho kết nối P2P qua Internet |
| Python | 3.x | Peer launcher HTTP server (port 9200) |

**Yêu cầu hệ thống:**
- Java JDK 17+
- Maven 3.9+
- Node.js 18+, npm 9+
- Docker 20+
- Windows 10/11 hoặc Linux (WSL2)

## 4.2. Cấu trúc mã nguồn và phân chia module

```
P2PChat/
├── run.sh                         # Script điều phối: build, start, stop, peer management
├── build.sh                       # Build React + 3 Maven modules cục bộ
├── Dockerfile                     # Multi-stage image cho peer-node
├── peer-launcher.py               # Python HTTP server đăng ký peer tại :9200
├── peers-index.html               # Trang tổng hợp link đến các peer đang chạy
│
├── bootstrap-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/mycompany/p2pchat/
│       ├── bootstrap/
│       │     ├── BootstrapApp.java           # Entry point, CLI argument parsing
│       │     ├── BootstrapServer.java         # TCP server, vòng lặp accept, broadcast
│       │     ├── ClientHandler.java           # Xử lý mỗi kết nối peer
│       │     ├── PeerRegistry.java            # In-memory map + JSON persistence
│       │     ├── BootstrapDashboardServer.java # HTTP dashboard :9001
│       │     └── BootstrapEventLog.java       # Thread-safe event log
│       ├── model/
│       │     ├── Message.java
│       │     └── PeerInfo.java
│       ├── protocol/
│       │     ├── ProtocolHandler.java
│       │     └── JsonUtil.java
│       └── utils/
│             ├── Constants.java
│             └── LoggerUtil.java
│
├── mailbox-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/mycompany/p2pchat/mailbox/
│       │     ├── MailboxApp.java
│       │     ├── MailboxServer.java
│       │     ├── MailboxRepository.java
│       │     ├── MailboxDatabase.java
│       │     └── MailboxEnvelope.java
│       └── resources/schema.sql
│
├── peer-node/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/mycompany/p2pchat/
│       │   ├── peer/
│       │   │     ├── PeerApp.java             # Entry point
│       │   │     ├── PeerNode.java             # Điều phối peer lifecycle
│       │   │     ├── PeerServer.java           # ServerSocket P2P
│       │   │     ├── PeerClient.java           # TCP client gửi tin nhắn
│       │   │     ├── WebServer.java            # Javalin HTTP + WebSocket
│       │   │     ├── E2EECrypto.java          # RSA E2EE
│       │   │     ├── CoordinatorManager.java   # DHT-lite coordinator + gossip
│       │   │     ├── GroupCache.java           # DHT-lite data plane cache
│       │   │     ├── LazyRepairManager.java    # Sửa chữa nhóm khi online lại
│       │   │     ├── HRWHash.java             # Highest Random Weight hashing
│       │   │     ├── LamportClock.java        # Logical clock
│       │   │     ├── MailboxClient.java        # Client-side mailbox operations
│       │   │     └── OutboxManager.java        # Durable retry + circuit breaker
│       │   ├── database/
│       │   │     ├── DatabaseManager.java
│       │   │     ├── MessageRepository.java
│       │   │     └── OutboxRepository.java
│       │   ├── filetransfer/
│       │   │     ├── FileTransferManager.java
│       │   │     ├── ChunkTransferProtocol.java
│       │   │     ├── FileTransferServer.java
│       │   │     └── FileTransferClient.java
│       │   ├── model/
│       │   ├── protocol/
│       │   └── utils/
│       └── resources/
│           ├── schema.sql
│           └── static/                         # React build output
│
└── peer-web/
    ├── package.json
    └── src/
        ├── api.js
        ├── App.jsx
        └── components/
```

## 4.3. Triển khai Bootstrap Server

### 4.3.1. Khởi tạo và vòng lặp kết nối

`BootstrapApp` là entry point, nhận các tham số dòng lệnh: `--port` (mặc định 9000), `--dashboard-port` (mặc định 9001), `--mailbox` (host và port của Mailbox Server). Sau khi khởi tạo `BootstrapServer` và `BootstrapDashboardServer`, cả hai được start đồng thời.

`BootstrapServer` tạo `ServerSocket` lắng nghe trên port đã chỉ định. Vòng lặp chính dùng `serverSocket.accept()` để chờ kết nối từ peer. Mỗi kết nối được giao cho một `ClientHandler` chạy trong thread riêng từ thread pool (CachedThreadPool). Thread pool cho phép xử lý đồng thời nhiều peer mà không giới hạn cứng số lượng.

### 4.3.2. Quản lý Peer Registry và Dead Peer Detection

`PeerRegistry` sử dụng `ConcurrentHashMap` để lưu trữ thông tin peer: key là username, value là `PeerInfo` chứa host, peerPort, publicKey, keyId, lastHeartbeat (epoch milliseconds), online flag. Thao tác trên map là thread-safe, phù hợp với nhiều `ClientHandler` cùng truy cập đồng thời.

Một `ScheduledExecutorService` chạy tác vụ dead-peer detection mỗi 5 giây. Tác vụ duyệt qua toàn bộ peer trong registry, kiểm tra nếu `now - lastHeartbeat > 45000ms` (9 chu kỳ heartbeat) thì peer bị coi là dead. Peer dead bị xóa khỏi registry, và `broadcastPeerLeave` được gọi để thông báo cho tất cả peer còn lại.

`PeerRegistry` tự động lưu ra file `registry.json` sau mỗi thao tác thay đổi (register, unregister, heartbeat update) và đọc lại khi Bootstrap Server khởi động. Điều này đảm bảo trạng thái mạng được bảo toàn qua các lần restart.

### 4.3.3. Cơ chế Broadcast sự kiện

Khi có sự kiện `PEER_JOIN` hoặc `PEER_LEAVE`, `BootstrapServer` gọi hàm `broadcastToAll()`. Hàm này duyệt qua toàn bộ peer online trong registry, bỏ qua peer nguồn của sự kiện, và với mỗi peer còn lại: mở socket TCP, gửi thông điệp JSON, đóng socket. Việc mở/đóng socket riêng cho mỗi broadcast đảm bảo tính độc lập — một peer không phản hồi không ảnh hưởng đến các peer khác.

## 4.4. Triển khai Peer Node

### 4.4.1. Khởi tạo và đăng ký với Bootstrap

`PeerApp` là entry point, nhận các tham số: `--username`, `--host` (advertised IP), `--port` (peer TCP port), `--bootstrap` (host:port), `--mailbox` (host:port), `--web` (web UI port). Peer tính toán `peerPort = web + 1000` và `filePort = peerPort + 1000`.

Khi `PeerNode.start()` được gọi, thứ tự khởi tạo:
1. Mở `PeerServer` (ServerSocket) trên peerPort để lắng nghe kết nối P2P đến
2. Khởi tạo `E2EECrypto` — tạo hoặc tải cặp khóa RSA từ file
3. Khởi tạo `PeerManager` — kết nối SQLite, khởi tạo schema nếu chưa có
4. Khởi tạo `PeerClient`, `MailboxClient`, `CoordinatorManager`, `LazyRepairManager`, `FileTransferManager`
5. Khởi tạo `WebServer` (Javalin) trên web port, đăng ký REST routes và WebSocket handler
6. Gửi `RESOLVE_MAILBOX` đến Bootstrap Server để lấy endpoint Mailbox
7. Gửi `REGISTER` đến Bootstrap Server kèm username, host, peerPort, publicKey
8. Nhận `REGISTER_ACK` với danh sách peer online
9. Khởi động các scheduler nền (heartbeat, outbox retry, mailbox pull, coordinator gossip)
10. Gọi `LazyRepairManager.repairAllOnRestart()` để đồng bộ group state sau khi restart

### 4.4.2. Heartbeat và tự phục hồi kết nối

Heartbeat được gửi qua một `ScheduledExecutorService` riêng, thực hiện mỗi 5 giây. Mỗi lần gửi heartbeat, peer mở socket TCP đến Bootstrap Server, gửi `HEARTBEAT` kèm username, và đóng socket ngay sau khi nhận `HEARTBEAT_ACK`. Nếu gửi thất bại (IOException), peer thử lại ngay trong cùng chu kỳ (tối đa 1 lần retry).

Nếu heartbeat thất bại liên tiếp, peer vẫn tiếp tục hoạt động bình thường trong vai trò peer-to-peer — nó vẫn có thể nhận và gửi tin nhắn trực tiếp với các peer khác mà không cần Bootstrap. Khi kết nối Bootstrap được khôi phục, peer tự đăng ký lại qua `REGISTER` và tiếp tục heartbeat.

### 4.4.3. Chế độ Offline — Cache-first fallback

Khi kết nối Bootstrap Server thất bại hoàn toàn, peer chuyển sang **cache-first fallback mode**:
1. Sử dụng `RecentPeersCache` — lưu 5 peer gần nhất đã kết nối thành công
2. Peer vẫn có thể chat trực tiếp với các peer trong cache nếu biết endpoint
3. `known_peers` table trong SQLite lưu trữ thông tin peer đã biết, cho phép gửi tin mà không cần Bootstrap
4. Outbox tiếp tục retry các tin nhắn đang chờ

## 4.5. Triển khai Chat trực tiếp

### 4.5.1. Luồng gửi trực tiếp qua TCP

Khi người dùng gửi một tin nhắn trong Web UI, `PeerNode` nhận request qua REST API `/api/msg`, thực hiện:
1. Tạo UUID cho messageId, gắn timestamp, serialize thành JSON
2. Lưu vào `outbound_messages` table với status = PENDING
3. Lưu vào `messages` table trong local database
4. Tìm endpoint của receiver trong `known_peers` (hoặc gửi `DISCOVER` nếu chưa biết)
5. Mở socket TCP đến receiver:port, gửi `DIRECT_MESSAGE` JSON
6. Nếu nhận `ACK` trong 6 giây → cập nhật outbox = DELIVERED_DIRECT
7. Nếu thất bại sau 2 retry → chuyển bước 8
8. Gọi `MailboxClient.store()` để lưu vào Mailbox Server → cập nhật outbox = STORED_MAILBOX

### 4.5.2. Mã hóa đầu cuối E2EE

Mỗi peer tạo một cặp khóa RSA 2048-bit khi khởi động lần đầu. Public key được đăng ký với Bootstrap Server trong thông điệp `REGISTER`. Khi gửi tin nhắn, `E2EECrypto.encrypt(plaintext, receiverPublicKey)` mã hóa nội dung bằng public key của người nhận. Khi nhận tin nhắn, `E2EECrypto.decrypt(ciphertext, myPrivateKey)` giải mã. Điều này đảm bảo Mailbox Server và Bootstrap Server không thể đọc nội dung tin nhắn.

### 4.5.3. Fallback qua Mailbox khi peer offline

Outbox retry loop chạy trong nền: với mỗi tin nhắn status = PENDING:
- Nếu retryCount >= MAX_RETRIES (12 lần) → gọi `mailboxClient.store()`, đổi status = STORED_MAILBOX
- Nếu retryCount < MAX_RETRIES → thử gửi lại trực tiếp qua TCP, tăng retryCount, cập nhật lastRetry

Nếu Mailbox Server liên tục thất bại (3 lần liên tiếp), **circuit breaker** mở, tạm dừng gửi qua Mailbox trong 30 giây để tránh tắc nghẽn. Sau 30 giây, circuit breaker thử lại một lần — nếu thành công thì đóng lại, nếu thất bại thì mở tiếp 30 giây.

## 4.6. Triển khai Chat nhóm

### 4.6.1. Tạo nhóm và bầu Coordinator bằng HRW Hash

Khi người dùng tạo nhóm qua Web UI (`/api/group/create`), `PeerNode` xử lý:
1. Tạo UUID cho groupId, lưu tên nhóm và danh sách thành viên ban đầu
2. Tính K=3 coordinator bằng `HRWHash.topK(members, groupId, K=3)`
3. Tạo `GroupInfo` với coordinator = top-1, replicas = top-2 và top-3, lưu vào `group_cache` table
4. Gửi `COORD_INIT` đến tất cả K coordinator (bao gồm cả creator)
5. Gửi `GROUP_JOINED` đến mỗi thành viên ban đầu

### 4.6.2. Gossip đồng bộ giữa các Coordinator

`CoordinatorManager` chạy một gossip scheduler riêng: mỗi 5 giây, mỗi coordinator gửi `COORD_GOSSIP` đến tất cả coordinator khác trong nhóm. `COORD_GOSSIP` chứa: `{groupId, version, members (JSON array), updatedAt}`. Khi nhận `COORD_GOSSIP`, coordinator so sánh version:
- Nếu version nhận > version local → cập nhật local group_cache
- Nếu bằng nhau → không làm gì
- Nếu version nhận < version local → bỏ qua

Nếu một coordinator không nhận được gossip trong 3 chu kỳ liên tiếp (15 giây), nó bị coi là dead và các coordinator còn lại cập nhật danh sách.

### 4.6.3. Multicast tin nhắn và Lamport Buffer

Khi gửi tin nhắn nhóm, sender:
1. Tăng LamportClock cục bộ, gắn Lamport timestamp vào message
2. Gửi `GROUP_MESSAGE` đến từng thành viên trong nhóm qua P2P TCP riêng (multicast)
3. Thành viên offline → lưu vào Mailbox qua `STORE_MESSAGE`

Khi nhận `GROUP_MESSAGE`, peer:
1. Tăng LamportClock nếu timestamp nhận > local clock
2. Đưa vào **Lamport Buffer** (200ms)
3. Sau khi buffer xong, sắp xếp theo Lamport timestamp tăng dần
4. Hiển thị tin nhắn đã sắp xếp trong giao diện

Buffer 200ms xử lý trường hợp các tin nhắn đến không đúng thứ tự do network latency khác nhau đến từng thành viên.

### 4.6.4. Fallback nhóm qua Mailbox cho thành viên offline

Khi sender gửi `GROUP_MESSAGE` đến một thành viên mà TCP thất bại (peer offline), thay vì lưu một tin nhắn group chung, hệ thống gửi `STORE_MESSAGE` riêng cho mỗi thành viên offline. Điều này cho phép Mailbox Server theo dõi delivery status per-member qua `group_delivery_acks` table. Mỗi thành viên khi online trở lại sẽ nhận đầy đủ các tin nhắn nhóm bị miss.

## 4.7. Triển khai Truyền file

### 4.7.1. Thỏa thuận kênh truyền

Khi người dùng chọn file trong Web UI và nhấn gửi, frontend gọi `/api/file/offer` kèm multipart file data. Backend:
1. Kiểm tra kích thước file (giới hạn 100 MB)
2. Tính SHA-256 hash của toàn file
3. Khởi tạo `FileTransferManager` với transferId, filename, size, hash, direction, peer
4. Mở `FileTransferServer` (ServerSocket) trên filePort để lắng nghe
5. Gửi `FILE_OFFER` đến receiver qua P2P TCP peer port
6. Receiver hiển thị thông báo trong giao diện, chờ người dùng Accept/Reject

### 4.7.2. Truyền nhị phân qua FileTransfer

Khi receiver chấp nhận, `FileTransferClient` mở socket TCP đến filePort của sender. Sender đọc file thành chunk 64 KB, gửi mỗi chunk: ghi 4 bytes độ dài + dữ liệu chunk. Sau chunk cuối cùng, gửi chunk độ dài 0 (EOF marker). Sender gửi `FILE_DONE`. Receiver kiểm tra SHA-256, gửi `FILE_ACK` hoặc báo lỗi.

Progress được tính: `(bytesReceived / fileSize) * 100%`. Mỗi lần cập nhật progress, `FileTransferManager` gửi `FILE_PROGRESS` qua WebSocket đến frontend để cập nhật progress bar realtime.

### 4.7.3. Phục hồi đứt gãy

Khi kết nối file bị gián đoạn:
1. Receiver kiểm tra `file_chunks` table — xác định chunk cuối đã nhận thành công
2. Receiver mở lại socket đến filePort
3. Gửi `FILE_RESUME` với transferId và chunk cuối đã nhận
4. Sender bắt đầu gửi từ chunk tiếp theo
5. Receiver bỏ qua các chunk đã có trong checkpoint

## 4.8. Triển khai Mailbox Server và Outbox Engine

### 4.8.1. Kiến trúc Mailbox Server

Mailbox Server chạy một `ServerSocket` trên port 9100. Vòng lặp `accept()` tương tự Bootstrap Server. Mỗi kết nối được xử lý bởi một thread riêng, đọc từng dòng JSON và dispatch đến handler tương ứng. Các thao tác xử lý:

- `STORE_MESSAGE`: Validate MailboxEnvelope, lưu vào SQLite `offline_messages`, trả `STORE_ACK`
- `PULL_MESSAGES`: Query SQLite, trả `PULL_RESPONSE` (tối đa 100 tin nhắn mỗi lần)
- `DELIVERY_ACK`: Cập nhật status = DELIVERED trong `offline_messages`, ghi vào `group_delivery_acks`

### 4.8.2. Outbox State Machine và Exponential Backoff

Outbox manager sử dụng **state machine** cho mỗi outbound message:

```
PENDING ──(retry < MAX)──► PENDING (retryCount++)
    │
    └──(retry >= MAX)────► STORED_MAILBOX
                              │
                         (receiver pulls,
                          sends DELIVERY_ACK)
                              │
                         DELIVERED_MAILBOX
```

Exponential backoff: thời gian chờ trước mỗi retry tăng dần:
- Retry 1-3: 5 giây
- Retry 4-6: 30 giây
- Retry 7-9: 2 phút
- Retry 10-12: 10 phút

Sau retry thứ 12, message được chuyển sang mailbox. Điều này đảm bảo không spam khi receiver offline trong thời gian dài.

### 4.8.3. Circuit Breaker cho Mailbox

Khi Mailbox Server không phản hồi (3 lần liên tiếp), circuit breaker chuyển sang trạng thái OPEN:
- Tất cả `STORE_MESSAGE` được tạm dừng trong 30 giây
- Tin nhắn tiếp theo vẫn ở trạng thái PENDING trong outbox
- Sau 30 giây, một lần thử "half-open" được thực hiện
- Nếu thành công → circuit đóng, hoạt động bình thường
- Nếu thất bại → circuit mở tiếp 30 giây

### 4.8.4. Mailbox Retry Worker và Pull-on-Reconnect

Mỗi peer chạy một `MailboxClient` với retry worker:
- Mỗi 10 giây, gửi `PULL_MESSAGES` đến Mailbox Server
- Nếu có tin nhắn offline: nhận `PULL_RESPONSE`, giải mã E2EE, gửi `DELIVERY_ACK`, hiển thị trong UI
- Mailbox retry worker cũng chạy: kiểm tra các message status = STORED_MAILBOX trong outbox, thử gửi lại trực tiếp qua TCP

## 4.9. Cơ chế xử lý lỗi và tự phục hồi

### 4.9.1. Phát hiện peer ngắt kết nối

Có hai cách phát hiện peer ngắt kết nối:
- **Graceful leave:** Peer gửi `PEER_LEAVE` → Bootstrap xóa ngay, broadcast ngay lập tức
- **Crash/force kill:** Peer không gửi `PEER_LEAVE` → Bootstrap chờ 45 giây không heartbeat → dead-peer detection kích hoạt → xóa và broadcast

### 4.9.2. LazyRepairManager — Đồng bộ cache nhóm

`LazyRepairManager` chạy khi peer online trở lại sau thời gian dài offline. Khi peer restart, nó kiểm tra `group_cache` — nếu `updatedAt` của một nhóm cách thời điểm hiện tại quá lâu, peer gửi `GROUP_RESYNC_REQ` đến coordinator để nhận `GROUP_RESYNC_RESP` chứa metadata đầy đủ. Đồng thời, peer gửi `GROUP_HISTORY_DIGEST` đến các peer trong nhóm để xác định tin nhắn nào bị miss, sau đó gửi `GROUP_HISTORY_REQUEST` để nhận lại.

### 4.9.3. Self-promote Coordinator khi C1 sập

Khi một coordinator bị coi là dead (3 chu kỳ gossip không nhận được), các coordinator còn lại tự động cập nhật danh sách coordinator trong `group_cache`. Nếu coordinator #1 (HRW rank cao nhất) sập và creator/owner của nhóm vẫn online và có dữ liệu group cache cục bộ, creator có thể tự động thăng cấp (self-promote) nếu rank HRW của nó trong tập thành viên còn lại đủ cao để trở thành một trong top-K coordinator.

### 4.9.4. Fallback khi Bootstrap / Mailbox Server sập

- **Bootstrap Server sập:** Peer đã đăng ký vẫn chat P2P trực tiếp được bình thường. Không thể đăng ký peer mới hoặc nhận thông báo peer join/leave. Peer tự dùng `known_peers` table và `RecentPeersCache` để duy trì kết nối.
- **Mailbox Server sập:** Peer không thể gửi/đọc tin offline. Outbox ở trạng thái PENDING, tiếp tục retry trực tiếp. Circuit breaker mở, không gửi qua Mailbox. Khi Mailbox online trở lại, peer tự động kết nối lại và tiếp tục hoạt động.

## 4.10. Triển khai giao diện Web

### 4.10.1. Kiến trúc React và WebSocket real-time

React 18 là frontend của hệ thống. Cấu trúc component:

| Component | Vai trò |
|-----------|---------|
| `App.jsx` | Component root, quản lý routing, WebSocket connection |
| `api.js` | Gọi REST API đến peer-node |
| `Sidebar.jsx` | Danh sách peer online/offline, danh sách nhóm |
| `ChatArea.jsx` | Khu vực hiển thị tin nhắn |
| `MessageInput.jsx` | Ô nhập tin nhắn, gửi typing signal |
| `FileTransfers.jsx` | Quản lý file transfer, progress bar |
| `OutboxView.jsx` | Trạng thái outbox (PENDING, DELIVERED, STORED_MAILBOX) |

**WebSocket real-time:** `App.jsx` mở WebSocket đến `ws://host:port/ws`. Khi nhận message từ server, dispatch theo type: `PEER_JOIN`/`PEER_LEAVE` cập nhật danh sách peer, `DIRECT_MESSAGE` hiển thị tin nhắn, `GROUP_MESSAGE` hiển thị tin nhắn nhóm, `FILE_PROGRESS` cập nhật progress bar, `TYPING` hiển thị trạng thái đang nhập, `OUTBOX_UPDATE` cập nhật trạng thái gửi.

### 4.10.2. Đóng gói và triển khai bằng Docker

**React build:** `peer-web/` chạy `npm install && npm run build`, output vào `peer-web/build/`.

**Multi-stage Dockerfile (peer-node):**
- Stage 1 (build-web): Node 20 Alpine, copy source, `npm install && npm run build`
- Stage 2 (build-java): Maven 3.9 + Eclipse Temurin 17, copy source, `mvn clean package -DskipTests`, copy React build vào `src/main/resources/static/`
- Stage 3 (runtime): Eclipse Temurin 17 JRE Alpine, copy JAR từ stage 2, `ENTRYPOINT java -jar peer-node.jar`

**Docker networking:** Tất cả container chạy trên Docker bridge network `p2p-net`, cho phép container DNS resolution (vd: `bootstrap`, `mailbox`, `peer-alice`). Peer trong container kết nối đến Bootstrap qua `bootstrap:9000`, đến Mailbox qua `mailbox:9100`.

---

# CHƯƠNG V. THỬ NGHIỆM HỆ THỐNG & ĐÁNH GIÁ

### 3.1.1. Yêu cầu chức năng

1. **Đăng ký và tham gia mạng P2P:** Peer có thể đăng ký với Bootstrap Server, nhận danh sách peer online.
2. **Nhắn tin trực tiếp (Direct Message):** Gửi/nhận tin nhắn trực tiếp giữa hai peer qua TCP.
3. **Nhắn tin nhóm (Group Chat):** Tạo nhóm chat, thêm/kick member, gửi tin nhắn nhóm.
4. **Broadcast:** Gửi tin nhắn đến tất cả peer online trong mạng.
5. **Tin nhắn offline:** Khi người nhận offline, tin nhắn được lưu tạm ở Mailbox Server và gửi lại khi người nhận online.
6. **Chuyển file:** Gửi file trực tiếp đến peer hoặc nhóm.
7. **Giao diện Web:** Giao diện người dùng trên nền tảng web, realtime qua WebSocket.
8. **Dashboard giám sát:** Trang web theo dõi trạng thái Bootstrap Server và các peer.

### 3.1.2. Yêu cầu phi chức năng

- **Độ tin cậy:** Tin nhắn phải được xác nhận (ACK); retry khi gửi thất bại.
- **Bảo mật:** E2EE cho payload tin nhắn.
- **Khả năng mở rộng:** Hỗ trợ nhiều peer, nhiều nhóm.
- **Khả năng tương thích mạng:** Kết nối qua Internet bằng Tailscale VPN.
- **Khả năng chịu lỗi:** Peer crash không làm sập toàn hệ thống.

## 3.2. Sơ đồ kiến trúc hệ thống

### 3.2.1. Tổng quan kiến trúc

```
┌──────────────────────────────┐       ┌──────────────────────────────┐
│ Bootstrap Server             │       │ Mailbox Server               │
│ TCP :9000                    │◄─────►│ TCP :9100                    │
│ Dashboard HTTP :9001         │       │ SQLite: mailbox.db           │
└──────────────┬───────────────┘       └──────────────┬───────────────┘
               │                                      │
               │ register/discover/heartbeat          │ store/pull/ack
               │                                      │
    ┌──────────▼──────────┐         ┌───────────────▼─────────┐
    │ Peer alice           │  P2P TCP  │ Peer bob                 │
    │ Web UI: 33143        │◄────────►│ Web UI: 33144            │
    │ Peer TCP: 34143      │  direct   │ Peer TCP: 34144          │
    │ File TCP: 35143      │  msg/file │ File TCP: 35144          │
    │ SQLite local         │           │ SQLite local             │
    └──────────────────────┘           └───────────────────────────┘
```

### 3.2.2. Các thành phần chính

| Thành phần | Vai trò |
|-----------|---------|
| `bootstrap-server` | Đăng ký peer, heartbeat, peer discovery, dashboard, trả endpoint mailbox |
| `mailbox-server` | Lưu tin offline, TTL 7 ngày, delivery ack per-member cho group message |
| `peer-node` | Peer TCP server/client, Web API, SQLite local, group coordinator, outbox retry, E2EE, file transfer |
| `peer-web` | React Web UI cho chat, group management, broadcast, file transfer |

## 3.3. Thiết kế chi tiết từng thành phần

### 3.3.1. Bootstrap Server

**Vị trí trong kiến trúc:** Thành phần hạ tầng trung tâm, chạy trước các peer.

**Các module chính:**

| Module | Mô tả |
|--------|--------|
| `BootstrapApp` | Entry point, khởi tạo server & dashboard |
| `BootstrapServer` | TCP server chính, vòng lặp accept, broadcast |
| `PeerRegistry` | ConcurrentHashMap lưu trạng thái peer + offline messages |
| `ClientHandler` | Xử lý từng kết nối TCP trong thread riêng |
| `BootstrapDashboardServer` | HTTP server (JDK built-in) phục vụ dashboard |
| `BootstrapEventLog` | Thread-safe event log giới hạn 200 mục |
| `MessageType` | Enum tất cả message type |
| `ProtocolHandler` | Factory tạo message JSON |

**Cấu trúc dữ liệu `PeerRegistry`:**

`PeerRegistry` lưu trữ thông tin peer bằng `ConcurrentHashMap<String, PeerInfo>` với khóa là username, ánh xạ đến đối tượng `PeerInfo` chứa host, port, public key, trạng thái online, và timestamp heartbeat cuối. Ngoài ra, một `ConcurrentHashMap<String, List<Message>>` lưu các offline message đang chờ giao cho từng receiver. Toàn bộ registry được tự động lưu ra file JSON tại `registry.json` sau mỗi thao tác thay đổi (register, unregister, heartbeat update), và được đọc lại khi Bootstrap Server khởi động lại.

### 3.3.2. Mailbox Server

**Vị trí trong kiến trúc:** Thành phần hạ tầng, chạy song song với Bootstrap Server.

**Cơ sở dữ liệu (SQLite):**

| Bảng | Mô tả |
|------|--------|
| `offline_messages` | Tin nhắn offline: encrypted envelope, TTL, trạng thái DELIVERED/EXPIRED |
| `group_delivery_acks` | Delivery ack theo từng member cho group message |

**Message types hỗ trợ:** `STORE_MESSAGE`, `STORE_ACK`, `PULL_MESSAGES`, `PULL_RESPONSE`, `DELIVERY_ACK`, `MAILBOX_QUOTA_EXCEEDED`, `MAILBOX_MESSAGE_EXPIRED`.

### 3.3.3. Peer Node

**Vị trí trong kiến trúc:** Thành phần cốt lõi, mỗi người dùng chạy một peer.

**Các module chính:**

| Module | Mô tả |
|--------|--------|
| `PeerNode` | Điều phối toàn bộ hoạt động của peer |
| `PeerClient` | Kết nối TCP đến peer/server khác |
| `PeerServer` | ServerSocket lắng nghe kết nối đến |
| `E2EEHandler` | Mã hóa/giải mã E2EE payload |
| `CoordinatorHandler` | Xử lý vai trò coordinator nhóm DHT-lite |
| `MailboxClient` | Giao tiếp với Mailbox Server |
| `FileTransferManager` | Quản lý offer/accept/chunk/resume file transfer |

**Cơ sở dữ liệu (SQLite tại `data/peers/<username>`):**

| Bảng | Mô tả |
|------|--------|
| `messages` | Lịch sử direct/group/broadcast/system/file offer |
| `known_peers` | Peer đã biết và trạng thái online |
| `group_cache` | Metadata group DHT-lite |
| `recent_peers` | Bootstrap cache fallback |
| `outbound_messages` | Durable outbox cho retry và mailbox |
| `file_transfers` | Metadata transfer |
| `file_chunks` | Checkpoint chunk đã nhận |

### 3.3.4. Peer Web (Frontend)

**Vị trí trong kiến trúc:** Giao diện người dùng, chạy trên trình duyệt.

**Framework:** React 18.2 + Javalin WebSocket cho realtime.

**Các tính năng giao diện:**
- Danh sách peer online/offline.
- Gửi direct message với typing indicator.
- Quản lý nhóm chat (tạo, thêm, kick, rời, giải tán).
- Broadcast message.
- Gửi/nhận file với progress bar.
- Lịch sử chat (direct, group, broadcast).
- Outbox view với trạng thái retry/delivery.
- Realtime update qua WebSocket.

## 3.4. Thiết kế giao thức truyền thông

### 3.4.1. Bảng tổng hợp Message Type

| Nhóm | Message type |
|------|-------------|
| Bootstrap/discovery | `REGISTER`, `REGISTER_ACK`, `REGISTER_NACK`, `PEER_LIST`, `PEER_JOIN`, `PEER_LEAVE`, `HEARTBEAT`, `HEARTBEAT_ACK`, `DISCOVER`, `RESOLVE_MAILBOX`, `RESOLVE_MAILBOX_ACK` |
| Mailbox/offline | `STORE_MESSAGE`, `STORE_ACK`, `PULL_MESSAGES`, `PULL_RESPONSE`, `DELIVERY_ACK`, `MAILBOX_QUOTA_EXCEEDED`, `MAILBOX_MESSAGE_EXPIRED` |
| Chat | `DIRECT_MESSAGE`, `GROUP_MESSAGE`, `BROADCAST`, `TYPING`, `ACK`, `SYSTEM` |
| Group coordinator | `COORD_INIT`, `COORD_GOSSIP`, `GROUP_ADD`, `GROUP_LEAVE`, `GROUP_KICK`, `GROUP_DISBAND`, `GROUP_JOINED`, `GROUP_UPDATED`, `GROUP_KICKED`, `GROUP_DISBANDED` |
| Repair/history | `GROUP_RESYNC_REQ`, `GROUP_RESYNC_RESP`, `CACHE_STALE`, `GROUP_GET`, `GROUP_HISTORY_DIGEST`, `GROUP_HISTORY_REQUEST`, `GROUP_HISTORY_RESPONSE` |
| File transfer | `FILE_OFFER`, `FILE_ACCEPT`, `FILE_REJECT`, `FILE_DONE`, `FILE_ACK`, `FILE_GROUP_OFFER`, `FILE_HAVE`, `FILE_HAVE_REQ`, `FILE_HAVE_RESP`, `FILE_RESUME` |

### 3.4.2. Thiết kế REST API (Peer Web)

| Method | Route | Mô tả |
|--------|-------|--------|
| GET | `/health` | Health check |
| GET | `/api/info` | Thông tin peer, bootstrap, mailbox |
| POST | `/api/init` | Khởi tạo peer |
| GET | `/api/peers` | Danh sách peer đã biết |
| GET | `/api/discover` | Refresh peer list |
| GET | `/api/history/{peerName}` | Lịch sử direct chat |
| GET | `/api/group-history/{groupId}` | Lịch sử group chat |
| GET | `/api/broadcast-history` | Lịch sử broadcast |
| GET | `/api/outbox` | 100 outbound message gần nhất |
| POST | `/api/msg` | Gửi direct message |
| POST | `/api/broadcast` | Gửi broadcast |
| POST | `/api/typing` | Gửi typing signal |
| GET | `/api/group/list` | Danh sách group DHT-lite |
| POST | `/api/group/create` | Tạo group |
| POST | `/api/group/add` | Thêm member |
| POST | `/api/group/kick` | Kick member |
| POST | `/api/group/leave` | Rời group |
| POST | `/api/group/disband` | Giải tán group |
| POST | `/api/group/msg` | Gửi group message |
| POST | `/api/file/offer` | Upload & offer file |
| POST | `/api/file/accept` | Chấp nhận file |
| POST | `/api/file/reject` | Từ chối file |
| POST | `/api/file/cancel` | Hủy transfer |
| GET | `/api/file/download/{transferId}` | Tải file |
| WS | `/ws` | Realtime events |

### 3.4.3. Bootstrap Dashboard API

| Method | Route | Mô tả |
|--------|-------|--------|
| GET | `/` | Trang dashboard HTML |
| GET | `/api/status` | JSON trạng thái server |
| GET | `/api/peers` | JSON danh sách peer |
| GET | `/api/events` | JSON 200 sự kiện gần nhất |

## 3.5. Sơ đồ luồng hoạt động chính

### 3.5.1. Luồng đăng ký peer

```
Bootstrap Server                         Peer
     │                                     │
     │◄──────── REGISTER (host:port|pk) ──│
     │                                     │
     │──── REGISTER_ACK (peer list) ──────►│
     │──── OFFLINE_MESSAGE(s) (nếu có) ───►│
     │                                     │
     │──── PEER_JOIN ─────────────────────►│ (broadcast đến các peer khác)
```

### 3.5.2. Luồng gửi direct message

```
Sender                                    Receiver
   │                                         │
   │── DIRECT_MESSAGE (encrypted) ──────────►│
   │                                         │
   │◄── ACK ─────────────────────────────────│
```

**Fallback khi receiver offline:**

```
Sender              Mailbox Server          Receiver
   │                      │                    │
   │── STORE_MESSAGE ────►│                    │
   │◄── STORE_ACK ────────│                    │
   │                      │                    │
   │                      │◄── PULL_MESSAGES ──│ (khi online)
   │                      │── PULL_RESPONSE ──►│
   │                      │◄── DELIVERY_ACK ──│
```

### 3.5.3. Luồng tạo group

```
Creator                Bootstrap              Members
   │                        │                    │
   │── GROUP_CREATE ────────►│                    │
   │                        │                    │
   │ (tính HRW coordinator) │                    │
   │                        │                    │
   │── COORD_INIT ─────────────────────────────────►│
   │── GROUP_JOINED ─────────────────────────────────►│
```

## 3.6. Sơ đồ lớp (Class Diagram)

```
BootstrapApp
  └── BootstrapServer
        ├── PeerRegistry
        │     ├── peers: Map<String, PeerInfo>
        │     └── offlineMessages: Map<String, List<Message>>
        ├── BootstrapEventLog
        ├── ScheduledExecutorService (dead-peer detector)
        └── ClientHandler (1 thread / kết nối peer)

BootstrapDashboardServer
      └── HttpHandler (/, /api/status, /api/peers, /api/events)

MailboxServer
      └── MailboxDatabase (SQLite)

PeerNode
      ├── PeerServer (ServerSocket, nhận tin nhắn P2P)
      ├── PeerClient (gửi tin nhắn P2P)
      ├── BootstrapClient (kết nối Bootstrap)
      ├── MailboxClient (store/pull/ack offline)
      ├── E2EEHandler (mã hóa/giải mã payload)
      ├── CoordinatorManager (DHT-lite: coordinator, gossip)
      ├── LazyRepairManager (sửa chữa nhóm khi online lại)
      ├── HRWHash (chọn top-K coordinator)
      ├── LamportClock (đồng bộ thứ tự tin nhắn nhóm)
      ├── FileTransferManager (offer/accept/chunk/resume)
      ├── OutboxManager (retry + circuit breaker mailbox)
      ├── DatabaseManager (SQLite repositories)
      └── WebServer (Javalin HTTP + WebSocket → React UI)
```

## 3.7. Thiết kế cơ sở dữ liệu

### 3.7.1. Peer SQLite Schema

Cơ sở dữ liệu SQLite tại mỗi peer lưu trữ toàn bộ dữ liệu cục bộ:

| Bảng | Mô tả |
|------|--------|
| `messages` | Lưu tất cả tin nhắn: direct, group, broadcast, system, file offer. Các cột: id, type, sender, receiver, content (encrypted), timestamp, status |
| `known_peers` | Lưu peer đã biết: username (khóa chính), host, port, online flag, last_seen timestamp |
| `chat_groups` | Nhóm chat đơn giản (legacy từ CLI): group_id, name, members, created_at |
| `group_cache` | Metadata nhóm DHT-lite: group_id (khóa chính), name, coordinator, members (JSON array), created_at, updated_at |
| `recent_peers` | Bootstrap fallback cache: username, host, port, last_seen (giới hạn 5 peer gần nhất) |
| `outbound_messages` | Durable outbox: id, receiver, type, content, status (PENDING/STORED_MAILBOX/DELIVERED_DIRECT/DELIVERED_MAILBOX), retry_count, created_at, last_retry |
| `file_transfers` | Metadata file transfer: transfer_id, file_name, file_size, sha256, direction (SENDING/RECEIVING), status (OFFERED/ACCEPTED/IN_PROGRESS/COMPLETED/CANCELLED/REJECTED), peer, progress, created_at |
| `file_chunks` | Checkpoint chunk: transfer_id, chunk_number, received (boolean), lưu trạng thái chunk đã nhận để resume |

### 3.7.2. Mailbox SQLite Schema

Cơ sở dữ liệu Mailbox Server lưu trữ các tin nhắn offline:

| Bảng | Mô tả |
|------|--------|
| `offline_messages` | Tin nhắn offline: id (khóa chính), receiver, encrypted_payload (blob E2EE), sender, timestamp, ttl (Unix timestamp hết hạn 7 ngày), status (STORED/DELIVERED/EXPIRED), group_id (null cho direct) |
| `group_delivery_acks` | Delivery ack per-member cho group message: message_id + username (khóa chính kép), delivered_at timestamp |

## 3.8. Cấu hình mạng qua Tailscale

### 3.8.1. Vấn đề NAT/Firewall

Khi các máy tính ở mạng nội bộ (LAN/WiFi) khác nhau, chúng không thể giao tiếp trực tiếp qua TCP vì NAT. Giải pháp: dùng **Tailscale** — mạng riêng ảo (VPN) tạo kết nối P2P giữa các máy.

### 3.8.2. Quy trình thiết lập

1. Tất cả người dùng cài Tailscale, đăng nhập cùng một Tailnet.
2. Một người làm **Quản trị mạng** (Tạo Tailnet), mời những người khác qua email.
3. Mỗi người chạy `tailscale ip -4` để lấy IP Tailscale (dải 100.x.x.x).
4. Người khởi tạo mạng chạy Bootstrap + Mailbox + Peer.
5. Người tham gia chỉ cần nhập IP Bootstrap của người khởi tạo.

---

# CHƯƠNG V. THỬ NGHIỆM HỆ THỐNG & ĐÁNH GIÁ

```
P2PChat/
├── run.sh                         # Build/start/stop Docker stack
├── build.sh                       # Build React + 3 Maven modules
├── Dockerfile                     # Multi-stage image cho peer-node
├── peers-index.html               # Trang link nhanh tới các peer
│
├── bootstrap-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/mycompany/p2pchat/
│       ├── bootstrap/             # BootstrapApp, BootstrapServer, registry
│       ├── model/
│       ├── protocol/
│       └── utils/
│
├── mailbox-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/mycompany/p2pchat/mailbox/
│       └── resources/schema.sql
│
├── peer-node/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/mycompany/p2pchat/
│       │   ├── peer/              # PeerNode, PeerClient, PeerServer
│       │   ├── database/          # SQLite repositories
│       │   ├── filetransfer/      # File transfer logic
│       │   ├── model/
│       │   ├── protocol/
│       │   └── utils/
│       └── resources/
│           ├── schema.sql
│           └── static/            # React build
│
└── peer-web/
    ├── package.json
    └── src/
        ├── api.js
        ├── App.jsx
        ├── App.css
        └── components/
```

## 4.2. Môi trường và công cụ phát triển

### 4.2.1. Yêu cầu hệ thống

| Thành phần | Phiên bản tối thiểu |
|------------|---------------------|
| Java JDK | 17+ |
| Maven | 3.9+ |
| Node.js | 18+ |
| npm | 9+ |
| Docker | 20+ |

### 4.2.2. Thiết lập môi trường

```bash
# Kiểm tra
java -version
mvn -version
node -v
npm -v
docker --version
```

## 4.3. Triển khai Bootstrap Server

### 4.3.1. Build

```bash
cd bootstrap-server
mvn clean package -DskipTests
```

Build tạo file `bootstrap-server/target/bootstrap-server.jar`.

### 4.3.2. Khởi chạy

```bash
java -jar bootstrap-server/target/bootstrap-server.jar \
  --port 9000 \
  --dashboard-port 9001 \
  --mailbox localhost 9100
```

### 4.3.3. Triển khai Docker

```bash
docker build -t p2pchat-bootstrap -f bootstrap-server/Dockerfile bootstrap-server/

docker run -d --name bootstrap \
  -p 9000:9000 -p 9001:9001 \
  p2pchat-bootstrap \
  --port 9000 --dashboard-port 9001 --mailbox mailbox:9100
```

## 4.4. Triển khai Mailbox Server

### 4.4.1. Build

```bash
cd mailbox-server
mvn clean package -DskipTests
```

### 4.4.2. Khởi chạy

```bash
java -jar mailbox-server/target/mailbox-server.jar \
  --port 9100 \
  --db data/mailbox.db
```

### 4.4.3. Triển khai Docker

```bash
docker build -t p2pchat-mailbox -f mailbox-server/Dockerfile mailbox-server/

docker run -d --name mailbox \
  -p 9100:9100 \
  -v "$(pwd)/data/mailbox:/app/data" \
  p2pchat-mailbox --port 9100
```

## 4.5. Triển khai Peer Node

### 4.5.1. Build React frontend

```bash
cd peer-web
npm install
npm run build
```

Copy React build vào resources static:

```bash
rm -rf peer-node/src/main/resources/static
cp -r peer-web/build peer-node/src/main/resources/static
```

### 4.5.2. Build Java backend

```bash
cd peer-node
mvn clean package -DskipTests
```

### 4.5.3. Khởi chạy peer

```bash
java -jar peer-node/target/peer-node.jar \
  --username alice \
  --host localhost \
  --port 34143 \
  --bootstrap localhost:9000 \
  --mailbox localhost:9100 \
  --web 33143
```

### 4.5.4. Triển khai Docker

```bash
docker build -t p2pchat-peer -f Dockerfile .

docker run -d --name peer-alice \
  -p 34143:34143 -p 35143:35143 -p 33143:33143 \
  -v "$(pwd)/data/peers/alice:/app/data" \
  p2pchat-peer \
  --username alice --host peer-alice --port 34143 \
  --bootstrap bootstrap:9000 --mailbox mailbox:9100 --web 33143
```

## 4.6. Quản lý hệ thống bằng run.sh

Script `run.sh` cung cấp các lệnh quản lý Docker stack:

| Lệnh | Mô tả |
|------|--------|
| `./run.sh build` | Build tất cả Docker images |
| `./run.sh start` | Khởi động mailbox, bootstrap và 5 peer mặc định |
| `./run.sh stop` | Dừng toàn bộ container |
| `./run.sh restart` | Stop rồi start lại |
| `./run.sh peer <name> [port]` | Tạo peer mới |
| `./run.sh launcher` | Mở giao diện đăng ký peer tại http://localhost:9200 |
| `./run.sh logs <peer>` | Xem log của peer |
| `./run.sh urls` | Cập nhật peers-index.html |

### 4.6.1. Các peer mặc định khi dùng run.sh

| Peer | Web UI | Peer TCP | File TCP |
|------|--------|----------|----------|
| `alice` | http://localhost:33143 | 34143 | 35143 |
| `bob` | http://localhost:33144 | 34144 | 35144 |
| `duc` | http://localhost:12345 | 13345 | 14345 |
| `hoang` | http://localhost:12346 | 13346 | 14346 |
| `hoan` | http://localhost:12349 | 13349 | 14349 |

### 4.6.2. Hạ tầng mặc định

| Service | URL/Port |
|---------|----------|
| Bootstrap TCP | localhost:9000 |
| Bootstrap Dashboard | http://localhost:9001 |
| Mailbox TCP | localhost:9100 |
| Peer Launcher | http://localhost:9200 |

## 4.7. Triển khai trên Internet qua Tailscale

### 4.7.1. Thiết lập Tailscale (Node Khởi Tạo)

```bash
# 1. Cài Tailscale trên tất cả máy
# 2. Tạo Tailnet, mời thành viên

# 3. Lấy IP Tailscale
tailscale ip -4
# → 100.100.x.y

# 4. Build Docker images
./run.sh build

# 5. Khởi động hạ tầng
./run.sh infra 100.100.x.y

# 6. Tạo peer cho node khởi tạo
./run.sh join alice 100.100.x.y 100.100.x.y:9000 33143
```

### 4.7.2. Tham gia mạng (Node Tham Gia)

```bash
./run.sh build
./run.sh launcher
# Mở http://localhost:9200
# Nhập: Username, Bootstrap server: 100.100.x.y:9000
# Bỏ trống IP máy này để tự detect
```

## 4.8. Các module chính

### 4.8.1. BootstrapServer (server chính)

BootstrapServer chịu trách nhiệm tiếp nhận kết nối TCP từ các peer. Vòng lặp chính sử dụng `ServerSocket.accept()` để chờ kết nối, và mỗi kết nối được giao cho một `ClientHandler` chạy trong thread riêng từ thread pool. Bên cạnh đó, một `ScheduledExecutorService` chạy tác vụ dead-peer detection định kỳ: mỗi 5 giây kiểm tra `PeerRegistry`, nếu một peer không heartbeat trong 45 giây, nó bị xóa khỏi registry và `broadcastPeerLeave` được gọi để thông báo cho tất cả peer online biết peer đó đã offline. Hàm `broadcastToAll` duyệt qua toàn bộ peer online, bỏ qua peer gửi, và mở socket TCP đến từng peer để gửi message.

### 4.8.2. ClientHandler (xử lý kết nối peer)

Mỗi `ClientHandler` chạy trong một thread riêng, liên tục đọc các dòng JSON từ socket. Mỗi dòng được parse thành object `Message` và dispatch đến handler tương ứng. Khi nhận `REGISTER`, handler parse username và peer info, phát hiện NAT, gọi `registry.register()`, gửi `REGISTER_ACK` kèm peer list, giao các offline message đang chờ, và nếu là peer mới thì broadcast `PEER_JOIN`. Khi nhận `HEARTBEAT`, handler cập nhật timestamp heartbeat, gửi `HEARTBEAT_ACK` kèm peer list mới nhất. `STORE_MESSAGE` được lưu vào `offlineMessages`, `DISCOVER` trả về `PEER_LIST`, và `RESOLVE_MAILBOX` trả về endpoint mailbox.

### 4.8.3. PeerNode (peer chính)

Sau khi `PeerNode` được khởi tạo, nó tạo `PeerManager` để quản lý trạng thái và kết nối SQLite, khởi tạo bộ mã hóa E2EE, tạo `PeerClient` và `WebServer` (Javalin). Khi gọi `start()`, peer mở `PeerServer` (ServerSocket) để lắng nghe kết nối P2P đến, đăng ký với Bootstrap Server, resolve mailbox endpoint, và khởi động các scheduler: heartbeat thread gửi `HEARTBEAT` mỗi 5 giây, mailbox retry worker kiểm tra và kéo tin offline định kỳ, outbox retry thread xử lý các message đang chờ, và coordinator gossip thread cho group chat.

### 4.8.4. OutboxManager (retry bền vững)

`OutboxManager` đảm bảo không tin nhắn nào bị mất khi gửi. Mỗi message trước khi gửi được lưu vào bảng `outbound_messages` với trạng thái `PENDING`. Retry loop chạy nền: với mỗi message `PENDING`, nếu số lần retry đã đạt ngưỡng tối đa, message được chuyển sang mailbox server qua `MailboxClient.store()` và trạng thái đổi thành `STORED_MAILBOX`; nếu chưa đạt ngưỡng, hệ thống thử gửi lại trực tiếp qua TCP, tăng `retryCount` và cập nhật `lastRetry`. Nếu mailbox liên tục thất bại (ngưỡng 3 lần), circuit breaker mở và tạm dừng gửi qua mailbox trong 30 giây để tránh tắc nghẽn.

### 4.8.5. FileTransferManager

Luồng chuyển file gồm ba giai đoạn: offer, accept/reject, và transfer. Ở giai đoạn offer, sender gửi `FILE_OFFER` chứa filename, kích thước, SHA-256 hash, và file port. Receiver phản hồi `FILE_ACCEPT` hoặc `FILE_REJECT`. Nếu chấp nhận, receiver kết nối TCP đến file port của sender và bắt đầu nhận dữ liệu theo chunk 64 KB. Mỗi chunk được gửi kèm độ dài (4 bytes integer) để receiver biết khi nào chunk kết thúc. Tổng SHA-256 được xác minh ở cuối. Progress được cập nhật realtime qua WebSocket. Checkpoint được lưu sau mỗi chunk; nếu kết nối bị gián đoạn, receiver gửi `FILE_RESUME` để tiếp tục từ chunk cuối cùng đã nhận.

## 4.9. Web UI (React)

### 4.9.1. Cấu trúc component

```
peer-web/src/
├── api.js                  # Gọi REST API đến peer-node
├── App.jsx                # Component chính, routing
├── App.css                # Styles
└── components/
    ├── ChatWindow.jsx     # Cửa sổ chat
    ├── PeerList.jsx       # Danh sách peer online/offline
    ├── GroupPanel.jsx     # Quản lý nhóm chat
    ├── FileTransfer.jsx   # Gửi/nhận file
    ├── OutboxView.jsx     # Trạng thái outbox
    └── TypingIndicator.jsx # Typing indicator
```

### 4.9.2. WebSocket realtime

React UI kết nối đến peer qua WebSocket tại endpoint `/ws`. Khi nhận message từ server, ứng dụng parse JSON và xử lý theo loại: `PEER_JOIN`/`PEER_LEAVE` cập nhật danh sách peer, `DIRECT_MESSAGE` hiển thị tin nhắn, `GROUP_MESSAGE` hiển thị tin nhắn nhóm, `FILE_PROGRESS` cập nhật progress bar, `TYPING` hiển thị trạng thái đang nhập, và `OUTBOX_UPDATE` cập nhật trạng thái gửi. Mọi sự kiện đều được xử lý bất đồng bộ, đảm bảo giao diện phản hồi realtime mà không block UI.

## 4.10. Lưu trữ dữ liệu

### 4.10.1. Docker volume mounts

| Container | Volume mount | Nội dung |
|-----------|-------------|----------|
| `mailbox` | `data/mailbox` | SQLite mailbox database |
| `peer-*` | `data/peers/<username>` | SQLite peer database |

### 4.10.2. Persistence

- **PeerRegistry:** Tự động lưu/đọc `registry.json` sau mỗi register/unregister.
- **Outbox:** Durable outbox đảm bảo tin nhắn không bị mất khi peer crash.
- **File transfer:** Checkpoint chunk cho phép resume sau gián đoạn.

---

# CHƯƠNG V. THỬ NGHIỆM HỆ THỐNG & ĐÁNH GIÁ

## 5.1. Môi trường thử nghiệm

### 5.1.1. Phần cứng và phần mềm

| Thành phần | Phiên bản |
|-----------|----------|
| Hệ điều hành | Windows 10/11, Linux (WSL2) |
| Java JDK | 17 |
| Docker | 20+ |
| Tailscale | Mới nhất |
| Trình duyệt | Chrome/Edge/Firefox |

### 5.1.2. Cấu hình mạng thử nghiệm

- **Local LAN:** Các peer chạy trên cùng mạng LAN, kết nối qua Docker bridge.
- **Tailscale VPN:** Peer ở các mạng LAN khác nhau kết nối qua Tailscale VPN.

## 5.2. Các kịch bản thử nghiệm

### 5.2.1. Kịch bản 1: Đăng ký và peer discovery

| Bước | Thao tác | Kết quả mong đợi |
|------|---------|------------------|
| 1 | Khởi động Bootstrap Server | Bootstrap chạy, dashboard tại http://localhost:9001 |
| 2 | Khởi động peer alice | alice đăng ký thành công, hiển thị trong dashboard |
| 3 | Khởi động peer bob | bob đăng ký, alice nhận PEER_JOIN |
| 4 | Alice gọi `/discover` | Nhận danh sách bob trong peer list |
| 5 | Dashboard refresh | Hiển thị 2 peer online |

### 5.2.2. Kịch bản 2: Nhắn tin trực tiếp (Direct Message)

| Bước | Thao tác | Kết quả mong đợi |
|------|---------|------------------|
| 1 | alice gửi tin cho bob | Tin nhắn gửi qua TCP trực tiếp |
| 2 | bob nhận tin | Tin hiển thị realtime trong chat window |
| 3 | bob gửi ACK | alice nhận ACK, trạng thái outbox = DELIVERED |
| 4 | bob đóng ứng dụng | bob offline trong dashboard |
| 5 | alice gửi tin cho bob | TCP thất bại sau 3 retry |
| 6 | alice gửi STORE_MESSAGE đến Mailbox | STORE_ACK, outbox = STORED_MAILBOX |
| 7 | bob khởi động lại | Bob online, nhận PULL_RESPONSE từ Mailbox |
| 8 | Bob gửi DELIVERY_ACK | Mailbox đánh dấu DELIVERED |

### 5.2.3. Kịch bản 3: Chat nhóm (Group Chat)

| Bước | Thao tác | Kết quả mong đợi |
|------|---------|------------------|
| 1 | alice tạo group với bob, duc | Group được tạo, coordinator = alice (HRW) |
| 2 | alice gửi tin nhắn nhóm | Tin nhắn gửi đến tất cả member |
| 3 | duc offline, bob online | bob nhận tin, duc nhận khi online |
| 4 | alice kick duc khỏi group | duc nhận GROUP_KICKED, không nhận tin nhóm tiếp |
| 5 | alice gửi GROUP_DISBAND | Group giải tán, tất cả member nhận GROUP_DISBANDED |

### 5.2.4. Kịch bản 4: Broadcast

| Bước | Thao tác | Kết quả mong đợi |
|------|---------|------------------|
| 1 | alice gửi broadcast "Chào mọi người" | Tin gửi đến tất cả peer online |
| 2 | Peer offline không nhận | Tin không được lưu offline (broadcast chỉ online) |
| 3 | Xem lịch sử broadcast | Lịch sử hiển thị đầy đủ |

### 5.2.5. Kịch bản 5: Chuyển file

| Bước | Thao tác | Kết quả mong đợi |
|------|---------|------------------|
| 1 | alice chọn file, nhấn gửi đến bob | FILE_OFFER gửi, bob thấy thông báo |
| 2 | bob chấp nhận | FILE_ACCEPT, transfer bắt đầu |
| 3 | Xem progress | Progress bar realtime cập nhật |
| 4 | Bob từ chối | FILE_REJECT, transfer hủy |
| 5 | Gửi file 100+ MB | Vượt giới hạn → báo lỗi |

### 5.2.6. Kịch bản 6: Peer churn (crash/leave)

| Bước | Thao tác | Kết quả mong đợi |
|------|---------|------------------|
| 1 | peer04 graceful shutdown | PEER_LEAVE gửi, Bootstrap broadcast ngay |
| 2 | peer02 crash (force kill) | Không LEAVE gửi |
| 3 | Chờ 45 giây | Bootstrap phát hiện peer02 dead, broadcast PEER_LEAVE |
| 4 | Kiểm tra dashboard | peer02, peer04 hiển thị offline |

## 5.3. Kết quả thử nghiệm

### 5.3.1. Peer Discovery

| Tiêu chí | Kết quả |
|---------|---------|
| Thời gian đăng ký | < 500ms |
| Thời gian peer join broadcast | < 1s |
| Số lượng peer tối đa thử nghiệm | 5 |
| NAT detection | Hoạt động đúng với Docker bridge IP |

### 5.3.2. Direct Message

| Tiêu chí | Kết quả |
|---------|---------|
| Latency (cùng LAN) | < 100ms |
| ACK timeout | 5 giây |
| Retry tối đa | 3 lần |
| Outbox persistence | Tin không bị mất khi peer restart |

### 5.3.3. Offline Message

| Tiêu chí | Kết quả |
|---------|---------|
| Tin nhắn offline được lưu | Có |
| Delivery khi online trở lại | Có |
| TTL hoạt động | 7 ngày |
| Group message delivery ack | Hoạt động |

### 5.3.4. Group Chat

| Tiêu chí | Kết quả |
|---------|---------|
| Tạo group | Thành công |
| HRW coordinator election | Hoạt động |
| Thêm/kick member | Hoạt động |
| Offline member nhận tin | Có (qua mailbox) |
| Resync khi online lại | Hoạt động |

### 5.3.5. File Transfer

| Tiêu chí | Kết quả |
|---------|---------|
| Chunk size | 64 KB |
| SHA-256 verification | Hoạt động |
| Progress realtime | Hoạt động |
| Giới hạn 100 MB | Có |
| File group offer | Hoạt động |

### 5.3.6. Churn Test (churn-test.ps1)

Kịch bản mô phỏng churn test (PowerShell):

```bash
powershell -ExecutionPolicy Bypass -File churn-test.ps1 -PeerCount 6 -TotalRounds 12
```

Round 1–4: Start 6 peer (join bootstrap).
Round 5: Crash peer02, peer04 (force kill).
Round 6–7: Bootstrap phát hiện dead → broadcast PEER_LEAVE.
Round 8: Start peer02 lại → join, broadcast PEER_JOIN.
Round 9–12: Lặp lại churn.

**Kết quả:** Bootstrap phát hiện dead peer sau 45 giây, broadcast PEER_LEAVE đến tất cả peer online, dashboard cập nhật trạng thái chính xác.

## 5.4. Đánh giá

### 5.4.1. Ưu điểm

1. **Kiến trúc phân tán rõ ràng:** Bootstrap chỉ điều phối, dữ liệu chat P2P trực tiếp.
2. **Độ tin cậy cao:** Durable outbox + ACK + retry đảm bảo tin nhắn được gửi.
3. **Store-and-forward hiệu quả:** Tin offline được lưu và gửi lại tự động.
4. **Bảo mật:** E2EE cho payload tin nhắn; Mailbox không đọc được nội dung.
5. **Hỗ trợ nhiều tính năng:** Direct, group, broadcast, file transfer.
6. **DHT-lite nhóm:** HRW coordinator phân bố đều, gossip đồng bộ metadata.
7. **Docker hóa:** Dễ triển khai, quản lý nhiều peer.
8. **Dashboard giám sát:** Theo dõi trạng thái Bootstrap và peer realtime.
9. **Giao diện Web đẹp:** React UI với WebSocket realtime.
10. **Kết nối Internet:** Hỗ trợ Tailscale VPN cho kết nối xuyên mạng.

### 5.4.2. Hạn chế

1. **Điểm thất bại một phần:** Bootstrap Server và Mailbox Server là thành phần tập trung; nếu một trong hai chết, peer không thể đăng ký mới hoặc gửi tin offline. (Peer đã đăng ký vẫn chat P2P trực tiếp được.)
2. **Không có mã hóa E2EE thực sự:** Chỉ mã hóa payload, chưa có handshake khóa (Diffie-Hellman), chưa có perfect forward secrecy.
3. **Không có xác thực:** Không có cơ chế đăng nhập/mật khẩu; username có thể bị mạo danh.
4. **File transfer giới hạn 100 MB:** Không hỗ trợ file lớn.
5. **Group resync chưa tối ưu:** Khi group lớn, gossip có thể tạo nhiều message trùng lặp.
6. **Không có load balancing:** Khi số peer tăng lên, Bootstrap trở thành nút thắt cổ chai.

### 5.4.3. So sánh với hệ thống tương tự

| Tiêu chí | P2PChat (hệ thống này) | Signal | Discord | Telegram |
|-----------|----------------------|--------|---------|---------|
| Mô hình | Hybrid P2P | Hybrid P2P | Client-Server | Client-Server |
| E2EE | Có (payload) | Toàn diện | Tùy chọn | Tùy chọn |
| Tin offline | Có (Mailbox) | Có | Có | Có |
| Group chat | Có (DHT-lite) | Có | Có | Có |
| File transfer | Có (P2P) | Có | Có | Có |
| Giao diện | Web (React) | App | Web/App | Web/App |
| Chạy local | Có (Docker) | Không | Không | Không |
| Mã nguồn mở | Có | 1 phần | Không | Không |

---

# CHƯƠNG VI. KẾT LUẬN

## 6.1. Tổng kết kết quả đạt được

Trong quá trình thực hiện đề tài, nhóm đã xây dựng thành công một **hệ thống chat ngang hàng (P2P Chat)** hoàn chỉnh với các thành phần:

1. **Bootstrap Server** — Quản lý đăng ký peer, heartbeat, peer discovery, broadcast sự kiện, dashboard giám sát, và điều phối mailbox endpoint.

2. **Mailbox Server** — Lưu trữ tin nhắn offline bằng SQLite, hỗ trợ TTL 7 ngày và delivery ack per-member cho group message.

3. **Peer Node** — Mỗi người dùng là một peer độc lập với:
   - TCP server nhận tin nhắn P2P trực tiếp.
   - Web API Javalin + WebSocket realtime.
   - Durable outbox với retry và circuit breaker.
   - E2EE payload mã hóa cho direct/offline message.
   - Coordinator DHT-lite cho group chat.
   - File transfer trực tiếp với chunking, SHA-256, và resume.

4. **Peer Web (Frontend)** — Giao diện React với đầy đủ tính năng chat, quản lý nhóm, broadcast, chuyển file, và trạng thái outbox.

5. **Docker deployment** — Toàn bộ hệ thống container hóa với script quản lý `run.sh`, hỗ trợ chạy nhiều peer và kết nối qua Internet bằng Tailscale VPN.

## 6.2. Các vấn đề đã giải quyết

| Vấn đề | Giải pháp |
|--------|----------|
| Peer cần biết peer khác để tham gia mạng | Bootstrap Server làm điểm vào duy nhất |
| NAT/Firewall chặn kết nối P2P | Tailscale VPN + NAT detection tại Bootstrap |
| Peer offline không nhận tin | Store-and-forward qua Mailbox Server |
| Tin nhắn có thể bị mất khi peer crash | Durable outbox với retry |
| Gửi tin thất bại (receiver offline) | Tự động chuyển sang mailbox sau 3 retry |
| Peer ngắt kết nối không báo | Heartbeat + dead-peer detection (45s timeout) |
| Mã nguồn không thể đọc trên Mailbox | E2EE payload bằng public key người nhận |
| Khó quản lý nhiều peer | Docker + run.sh + launcher UI |
| Nhiều peer ở mạng khác nhau | Tailscale Tailnet, NAT override |

## 6.3. Hướng phát triển tiếp theo

1. **Xác thực người dùng:** Thêm cơ chế đăng nhập, chữ ký số để chống mạo danh username.
2. **E2EE toàn diện:** Triển khai Double Ratchet hoặc Signal Protocol cho perfect forward secrecy.
3. **Structured P2P (DHT):** Thay thế Bootstrap Server bằng Kademlia/Chord để loại bỏ điểm thất bại tập trung.
4. **File lớn:** Hỗ trợ file > 100 MB bằng cách chia nhỏ chunk và transfer song song.
5. **Voice/video call:** Mở rộng TCP → UDP/RTP cho truyền voice/video P2P.
6. **Đa bootstrap server:** Nhiều bootstrap để tăng độ sẵn sàng.
7. **Tối ưu group resync:** Cải thiện cơ chế gossip để giảm duplicate message.

## 6.4. Bài học kinh nghiệm

1. **Thiết kế giao thức trước:** Định nghĩa message type và luồng tương tác rõ ràng giúp triển khai nhanh và ít lỗi.
2. **Outbox pattern rất quan trọng:** Durable outbox là chìa khóa để đạt được độ tin cậy cao trong hệ thống phân tán.
3. **NAT detection phức tạp hơn dự kiến:** Cần xử lý nhiều trường hợp: Docker bridge, Tailscale, router NAT.
4. **Docker networking cần cẩn thận:** Port mapping và container network cần chính xác để P2P hoạt động đúng.
5. **WebSocket cho realtime:** Kết hợp REST API (thao tác) + WebSocket (realtime) là mô hình hiệu quả cho chat.

---

**Nhóm thực hiện:** Nhóm 13 — Lớp 01 — Các hệ thống phân tán
