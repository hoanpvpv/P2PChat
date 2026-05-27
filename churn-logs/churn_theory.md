# Mô phỏng Churn trong Mạng Ngang Hàng (P2P) — Lý thuyết & Thực hành

---

## 1. Churn là gì?

**Churn** (tiếng Anh: "xáo trộn") là hiện tượng các nút (peer/node) trong mạng ngang hàng **liên tục tham gia và rời mạng** một cách không dự đoán được.

Đây là đặc trưng bất biến của mọi mạng P2P thực tế:

```
Thực tế người dùng:
  Alice: bật máy lúc 8h, tắt lúc 12h, bật lại lúc 14h
  Bob:   mất điện đột ngột lúc 9h30, bật lại lúc 10h
  Duc:   mạng WiFi chập chờn, liên tục mất kết nối
  Hoang: tắt máy đúng cách lúc 22h
```

```
Góc nhìn của hệ thống:
  8:00  alice JOIN
  9:00  bob JOIN
  9:30  bob CRASH (không báo trước)
  10:00 bob JOIN lại
  12:00 alice LEAVE (có báo trước)
  14:00 alice JOIN lại
  22:00 hoang LEAVE (có báo trước)
```

**Mô phỏng churn** = tái tạo có kiểm soát hành vi vào/ra liên tục này để kiểm tra hệ thống có chịu đựng được không.

---

## 2. Các thành phần chính của Churn Simulation

### 2.1 Nút (Peer/Node)

Mỗi nút có 2 trạng thái cơ bản:

```
        join()                  leave() / crash()
OFFLINE ──────────► ONLINE ─────────────────────► OFFLINE
   ▲                                                  │
   └──────────────────────────────────────────────────┘
                     (chu kỳ lặp lại)
```

Nút được phân thành 2 loại theo hành vi:

| Loại | Đặc điểm | Ví dụ |
|---|---|---|
| **Stable Node** (nút bền vững) | Online lâu, ít rời mạng | Server, máy tính văn phòng |
| **Transient Node** (nút tạm thời) | Vào/ra thường xuyên | Điện thoại di động, laptop |

### 2.2 Thời gian sống (Session Time & Downtime)

```
Timeline của 1 peer:

│◄─── session ───►│◄─ downtime ─►│◄─── session ───►│◄─ downtime ─►│
│    (online)      │   (offline)   │    (online)      │   (offline)   │
├─────────────────┤───────────────┤─────────────────┤───────────────►
                                                                  thời gian
```

Thời gian này không cố định mà tuân theo **phân phối xác suất**:

| Phân phối | Dùng để mô hình | Đặc điểm |
|---|---|---|
| **Exponential** | Session time cơ bản | Memoryless — không nhớ đã online bao lâu |
| **Pareto** | Session time thực tế | Một số peer online rất lâu, nhiều peer online rất ngắn |
| **Poisson** | Số lần rời mạng trong 1 khoảng thời gian | Phù hợp với sự kiện ngẫu nhiên |

**Trong churn-sim.py của P2PChat** dùng mô hình đơn giản nhất: xác suất đồng đều mỗi chu kỳ.

```python
# Mỗi 15 giây (1 chu kỳ), với mỗi peer:
if peer.is_online():
    if random() < p_leave:   # p_leave = 0.3 → 30% rời mạng
        peer.leave()
else:
    if random() < p_join:    # p_join  = 0.5 → 50% join lại
        peer.join()
```

### 2.3 Hai loại rời mạng

```
┌─────────────────────────────┬─────────────────────────────────┐
│   GRACEFUL LEAVE            │   UNGRACEFUL CRASH              │
│   (Rời mạng có thông báo)   │   (Crash đột ngột)              │
├─────────────────────────────┼─────────────────────────────────┤
│ docker stop peer-alice      │ docker kill peer-alice          │
│       │                     │       │                         │
│       ▼                     │       ▼                         │
│   SIGTERM → JVM chạy        │   SIGKILL → JVM chết ngay       │
│   shutdown():               │   (không làm gì được)           │
│   - Gửi PEER_LEAVE          │                                 │
│   - Đóng socket, DB         │                                 │
│   - System.exit(0)          │                                 │
│       │                     │       │                         │
│       ▼                     │       ▼                         │
│   Bootstrap biết NGAY       │   Bootstrap KHÔNG biết          │
│   Detection time: <1s       │   Detection time: ~15s          │
│                             │   (phải chờ heartbeat timeout)  │
├─────────────────────────────┼─────────────────────────────────┤
│ Ví dụ thực tế:              │ Ví dụ thực tế:                  │
│ - Đóng app đúng cách        │ - Mất điện đột ngột             │
│ - Tắt máy bình thường       │ - Ứng dụng bị crash             │
│ - Bấm nút Đăng xuất         │ - Mạng đứt đột ngột             │
└─────────────────────────────┴─────────────────────────────────┘
```

---

## 3. Các bước mô phỏng Churn

### Bước 1 — Khởi tạo mạng

Tạo N nút ban đầu, thiết lập kết nối với Bootstrap (tracker):

```
Trong P2PChat:
  ./run.sh start
       │
       ├─ docker run peer-alice  → REGISTER → Bootstrap
       ├─ docker run peer-bob    → REGISTER → Bootstrap
       ├─ docker run peer-duc    → REGISTER → Bootstrap
       ├─ docker run peer-hoang  → REGISTER → Bootstrap
       └─ docker run peer-hoan   → REGISTER → Bootstrap

Bootstrap lưu: {alice: online, bob: online, duc: online, ...}
```

### Bước 2 — Thiết lập thông số Churn

```
Tỷ lệ churn = số lần join + leave / (tổng thời gian × số peer)

Ví dụ: 16 sự kiện / (170s × 5 peer) = 0.019 sự kiện/giây/peer
```

Trong `churn-sim.py`:

```bash
python3 churn-sim.py \
    --duration 180      \   # Tổng thời gian: 3 phút
    --interval 15       \   # Chu kỳ kiểm tra: 15 giây
    --p-leave 0.3       \   # 30% peer online sẽ rời mạng mỗi chu kỳ
    --p-join 0.5        \   # 50% peer offline sẽ join lại mỗi chu kỳ
    --crash-ratio 0.3       # 30% lần rời là crash, 70% là graceful
```

### Bước 3 — Mô phỏng sự kiện

Tại mỗi chu kỳ, thuật toán xác định nút nào vào/ra:

```
Chu kỳ 1 (t=0s):
  Snapshot trạng thái: alice✓ bob✓ duc✓ hoang✓ hoan✓

  Với alice (ONLINE):
    roll = random() = 0.12 < p_leave(0.3) → LEAVE
    roll = random() = 0.45 > crash_ratio(0.3) → graceful
    docker stop peer-alice → PEER_LEAVE → Bootstrap

  Với bob (ONLINE):
    roll = random() = 0.67 > p_leave(0.3) → giữ nguyên

  Với duc (ONLINE):
    roll = random() = 0.25 < p_leave(0.3) → LEAVE
    roll = random() = 0.11 < crash_ratio(0.3) → crash!
    docker kill peer-duc → SIGKILL

Chu kỳ 2 (t=15s):
  Snapshot: alice✗ bob✓ duc✗ hoang✓ hoan✓

  Với alice (OFFLINE):
    roll = random() = 0.31 < p_join(0.5) → JOIN
    docker start peer-alice → REGISTER → Bootstrap

  Với duc (OFFLINE):
    roll = random() = 0.73 > p_join(0.5) → vẫn offline (bỏ lỡ chu kỳ này)
```

### Bước 4 — Phục hồi (Recovery)

Khi một nút join lại, hệ thống thực hiện tự động:

```
docker start peer-bob
       │
       ▼
JVM khởi động
       │
       ├─ REGISTER → Bootstrap          [Đăng ký lại, nhận peer list]
       │
       ├─ RESOLVE_MAILBOX → Bootstrap   [Lấy địa chỉ Mailbox]
       │
       ├─ PULL_MESSAGES → Mailbox       [Lấy tin nhắn tồn đọng]
       │      └─ Giải mã E2EE
       │      └─ Lưu SQLite local
       │      └─ DELIVERY_ACK → Mailbox
       │
       ├─ repairAllOnRestart()          [Đồng bộ group state]
       │      └─ GROUP_RESYNC_REQ → Coordinator
       │
       └─ startHeartbeat()              [Bắt đầu heartbeat 5s/lần]
```

### Bước 5 — Đo lường kết quả

Log được ghi vào `churn-logs/churn_YYYYMMDD_HHMMSS.csv`:

```
timestamp,peer,event,method,elapsed_ms,note
2026-05-26T21:48:59,duc,LEAVE,graceful,1559,ok
2026-05-26T21:49:16,duc,JOIN,start,865,ok
2026-05-26T21:49:55,alice,CRASH,kill,43,ok
2026-05-26T21:50:12,alice,JOIN,start,512,ok
```

---

## 4. Mục đích mô phỏng — Đánh giá hệ thống ở những mặt nào?

### 4.1 Đánh giá độ ổn định (Availability)

**Câu hỏi**: Hệ thống có còn hoạt động khi tỷ lệ churn cao không?

```
Kịch bản test:
  alice gửi 10 tin nhắn cho bob trong lúc bob đang offline
         │
         ▼
  Nếu Delivery Rate = 100% → store-and-forward hoạt động ✓
  Nếu Delivery Rate < 100% → có tin bị mất → hệ thống yếu ✗
```

**Trong P2PChat**: Mailbox server lưu tin khi peer offline, peer pull khi join lại → Delivery Rate cao.

### 4.2 Đánh giá thời gian phát hiện lỗi (Failure Detection Time)

**Câu hỏi**: Hệ thống mất bao lâu để biết một nút đã chết?

```
Thời gian phát hiện = heartbeat_interval × miss_threshold
                    = 5s × 3
                    = 15 giây (với crash)

Trong 15 giây đó:
  - Peer khác vẫn cố gửi tin trực tiếp → FAIL
  - Hệ thống chưa chuyển sang dùng mailbox
  - Trải nghiệm người dùng xấu
```

Đây là **trade-off** cổ điển trong hệ thống phân tán:

```
heartbeat_interval nhỏ → Phát hiện nhanh, nhưng tốn băng thông
heartbeat_interval lớn → Tiết kiệm băng thông, nhưng phát hiện chậm
```

### 4.3 Đánh giá khả năng hồi phục (Recovery)

**Câu hỏi**: Sau khi join lại, nút có đồng bộ đủ dữ liệu không?

```
bob offline 30 phút
       │ trong thời gian đó:
       │  - alice gửi 5 tin nhắn trực tiếp → Mailbox lưu
       │  - nhóm chat có 2 thành viên mới  → GroupInfo thay đổi
       │
       ▼
bob join lại:
  ✓ Pull mailbox → nhận 5 tin của alice
  ✓ LazyRepair   → đồng bộ GroupInfo mới
  ✓ Heartbeat    → Bootstrap biết bob online

Recovery hoàn tất: ~3 giây
```

### 4.4 Đánh giá tính nhất quán (Consistency)

**Câu hỏi**: Khi mạng liên tục thay đổi, dữ liệu có bị sai không?

```
Tình huống:
  charlie rời nhóm trong lúc alice offline
       │
       ▼
  alice join lại:
    - Cache cũ: charlie vẫn trong nhóm
    - Thực tế:  charlie đã rời

  P2PChat giải quyết bằng:
    LazyRepairManager.repairAllOnRestart()
    → Hỏi Coordinator: "GroupInfo hiện tại là gì?"
    → Cập nhật cache local
    → alice thấy đúng danh sách thành viên
```

### 4.5 Đánh giá khả năng chịu tải (Stress)

**Câu hỏi**: Khi nhiều nút crash cùng lúc, hệ thống có bị quá tải không?

```
Wave mode: 3/5 peer crash đồng loạt
       │
       ▼
  Bootstrap: xử lý 3 heartbeat timeout cùng lúc
  Mailbox:   nhận nhiều STORE_MESSAGE cùng lúc
  Còn lại:   nhận nhiều PEER_TIMEOUT event cùng lúc

  Đo: elapsed_ms của các lệnh Docker
  Nếu elapsed_ms tăng vọt → hệ thống bị nghẽn
  Nếu elapsed_ms ổn định  → hệ thống chịu tải tốt
```

---

## 5. Công cụ sử dụng trong P2PChat

| Công cụ | Vai trò |
|---|---|
| **Docker** | Mỗi peer = 1 container → `docker kill/stop/start` = crash/leave/join |
| **churn-sim.py** | Script Python điều phối sự kiện, ghi log CSV |
| **churn-monitor.py** | Terminal dashboard tổng hợp từ Docker + Bootstrap API |
| **Bootstrap /api/events** | Event log real-time: REGISTER, PEER_LEAVE, TIMEOUT |
| **churn-logs/*.csv** | Lưu trữ toàn bộ sự kiện để phân tích sau |

---

## 6. Kết quả đánh giá P2PChat qua Churn Simulation

| Tiêu chí | Kết quả đo được | Nhận xét |
|---|---|---|
| **Delivery Rate** | ~100% (qua mailbox) | Tin nhắn không bị mất khi peer offline |
| **Failure Detection — Graceful** | < 1 giây | Bootstrap nhận PEER_LEAVE ngay |
| **Failure Detection — Crash** | ~15 giây | Phụ thuộc heartbeat interval × 3 |
| **Recovery Time** | ~3 giây | JVM boot + REGISTER + PULL |
| **Consistency** | ✓ sau LazyRepair | Group state đồng bộ khi join lại |
| **Stress (wave crash)** | elapsed_ms ổn định | Hệ thống không bị nghẽn |

### Điểm còn hạn chế

| Hạn chế | Nguyên nhân | Hướng cải thiện |
|---|---|---|
| Crash detection 15s | Heartbeat interval = 5s, threshold = 3 | Giảm interval (tốn băng thông) |
| Group mailbox chưa E2EE | Chưa có shared group key | Implement Sender Key protocol |
| Churn log chưa có metric tự động | Script đơn giản | Tích hợp Prometheus + Grafana |

---

## 7. Tóm tắt

```
Mô phỏng Churn
      │
      ├─ Tái tạo: peer vào/ra liên tục (docker kill/stop/start)
      │
      ├─ Đo lường:
      │     ├─ Delivery Rate     → tin có đến được không?
      │     ├─ Detection Time    → phát hiện lỗi bao lâu?
      │     ├─ Recovery Time     → hồi phục mất bao lâu?
      │     └─ Consistency       → dữ liệu có đúng không?
      │
      └─ Kết luận: Hệ thống P2PChat duy trì hoạt động
                   ổn định dưới điều kiện churn cao
                   nhờ Mailbox, Heartbeat, LazyRepair
```
