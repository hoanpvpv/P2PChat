# Kịch bản Demo Mô phỏng Churn — P2PChat

## 1. Giới thiệu (1–2 phút)

### Churn là gì?

Trong hệ thống P2P thực tế, các peer **liên tục tham gia và rời mạng** một cách không dự đoán được:

- Người dùng **mất điện, mạng đứt đột ngột** → *ungraceful crash*
- Người dùng **bật lại máy** → *join*

Script `churn-sim.py` mô phỏng hai hành vi này bằng Docker: `docker kill` để crash và `docker start` để join lại.

### Mục tiêu đánh giá

| Tiêu chí | Câu hỏi kiểm tra |
|---|---|
| **Availability** | Tin nhắn có đến được khi peer offline không? |
| **Failure Detection** | Hệ thống phát hiện peer chết trong bao lâu? |
| **Recovery** | Peer join lại có nhận được tin tồn đọng không? |
| **Broadcast resilience** | Broadcast có bỏ qua peer offline không? |

---

## 2. Chuẩn bị môi trường

### Cấu trúc hệ thống demo

```
Bootstrap :9000  ←→  Mailbox :9100
       │
       ├── peer-alice  (web :33143, p2p :34143)
       ├── peer-bob    (web :33144, p2p :34144)
       ├── peer-duc    (web :12345, p2p :13345)
       ├── peer-hoang  (web :12346, p2p :13346)
       └── peer-hoan   (web :12349, p2p :13349)
```

### Lệnh khởi động

```bash
# Build image (1 lần duy nhất)
./run.sh build

# Khởi động toàn bộ hệ thống
./run.sh start

# Mở 3 terminal song song:
# Terminal 1 — Monitor real-time
python3 churn-monitor.py --interval 1

# Terminal 2 — Chạy mô phỏng
python3 churn-sim.py --mode scenario

# Terminal 3 — Log Bootstrap
./run.sh logs s
```

### Mở trình duyệt

- **Bootstrap Dashboard**: http://localhost:9001
- **Web UI alice**: http://localhost:33143
- **Web UI bob**: http://localhost:33144

---

## 3. Ba chế độ mô phỏng

### Chế độ Scenario — Kịch bản cố định

Chạy kịch bản 8 bước, có gửi tin nhắn thật qua REST API:

```bash
python3 churn-sim.py --mode scenario
```

**Timeline 40 giây:**

```
t=  0s  Baseline — dam bao tat ca peer online
t=  5s  bob CRASH ungraceful
t= 10s  alice gui tin cho bob (store-and-forward)
t= 15s  duc CRASH ungraceful
t= 20s  alice BROADCAST khi co peer offline
t= 25s  bob JOIN lai — pull mailbox
t= 30s  duc JOIN lai
t= 35s  Snapshot cuoi
```

---

### Chế độ Wave — Sóng crash đồng loạt

Chạy 4 đợt cố định trong thời gian tùy chọn:

```bash
python3 churn-sim.py --mode wave --duration 120
```

**Timeline:**

```
t=  0%  Baseline — tat ca online
t= 25%  50% peer CRASH dot ngot       ← quan sat monitor
t= 50%  Peer crash JOIN lai            ← quan sat recovery
t= 75%  1/3 peer con lai CRASH tiep   ← tang churn
t=100%  Ket thuc, in thong ke
```

---

### Chế độ Random — JOIN ngẫu nhiên

Peer offline có xác suất join lại mỗi chu kỳ:

```bash
python3 churn-sim.py --mode random \
    --duration 180 \
    --interval 15 \
    --p-join 0.5
```

**Tham số:**

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `--duration` | 180s | Tổng thời gian chạy |
| `--interval` | 15s | Khoảng cách giữa mỗi chu kỳ |
| `--p-join` | 0.5 | Xác suất peer offline JOIN mỗi chu kỳ |

**Trong khi chờ, đọc log CSV đang sinh ra:**

```bash
# Windows
type churn-logs\churn_*.csv
```

---

## 4. Chi tiết kịch bản Scenario

### Bước 1 — Baseline (t = 0s)

```
for p in peers:
    if not is_running(p):
        docker start peer-{p}
sleep(5)
```

**Mục đích**: Khởi tạo trạng thái sạch — tất cả 5 peer online.
**Kỳ vọng**: Bootstrap nhận REGISTER từ alice, bob, duc, hoang, hoan.

---

### Bước 2 — Bob CRASH (t = 5s)

```
docker kill peer-bob     # SIGKILL
```

**Mục đích**: Mô phỏng mất điện, mạng đứt đột ngột.
**Mechanism**: `docker kill` gửi SIGKILL, không cho phép graceful shutdown.
**Bootstrap phản ứng**: Bob tiếp tục "online" trong danh sách ~9-15s cho đến khi heartbeat timeout.
**Kỳ vọng**: Sau 9-15s, bob `online = false` trong `/api/peers`.

---

### Bước 3 — Alice gửi tin cho Bob (t = 10s)

```
POST http://localhost:33143/api/msg
{
  "receiver": "bob",
  "content": "Chao bob, ban co nhan duoc khong?"
}
```

**Mục đích**: Test **store-and-forward**.
**Mechanism**: Bob đang offline, nhưng tin được lưu vào **mailbox** của bob trên Bootstrap server.
**Kỳ vọng**: API trả `STORED_MAILBOX`, tin được ghi vào mailbox.

---

### Bước 4 — Duc CRASH (t = 15s)

```
docker kill peer-duc
```

**Mục đích**: Test hệ thống xử lý **nhiều peer crash cùng lúc**.
**Kỳ vọng**: Bootstrap xử lý được 2 crash (bob đã crash ở bước 2) mà không bị block.

---

### Bước 5 — Alice BROADCAST (t = 20s)

```
POST http://localhost:33143/api/broadcast
{
  "content": "Broadcast test khi co peer offline"
}
```

**Mục đích**: Test **broadcast khi có peer offline**.
**Tình huống**: alice, hoang, hoan online | bob, duc offline.
**Kỳ vọng**: Tin broadcast **chỉ gửi đến** alice, hoang, hoan — bob, duc (offline) không nhận được.

---

### Bước 6 — Bob JOIN lại (t = 25s)

```
docker start peer-bob
```

**Mục đích**: Test **mailbox pull on join**.
**Mechanism**: Bob join → Bootstrap gửi danh sách offline messages cho bob.
**Kỳ vọng**: Bob nhận được tin alice đã gửi ở **bước 3**.

---

### Bước 7 — Duc JOIN lại (t = 30s)

```
docker start peer-duc
```

**Mục đích**: Test recovery sau crash.
**Kỳ vọng**: Duc join bình thường, không khác gì bob.

---

### Bước 8 — Snapshot cuối (t = 35s)

```
final = snapshot_online(peers)
```

**Mục đích**: Ghi trạng thái cuối cùng và in bảng tổng kết.

---

## 5. Đọc và giải thích kết quả Log

### Cấu trúc file CSV

```
timestamp              peer   event               method  elapsed_ms  note
2026-05-27T10:46:26  bob    CRASH               kill        647       ok
2026-05-27T10:47:03  bob    JOIN                start       539       ok
2026-05-27T10:47:25  bob    FINAL_STATE_ONLINE  snapshot      0
```

### Cách đọc

| Cột | Ý nghĩa |
|---|---|
| `timestamp` | Thời điểm xảy ra sự kiện |
| `peer` | Peer nào bị ảnh hưởng |
| `event` | `JOIN` / `CRASH` / `FINAL_STATE_*` |
| `method` | `kill` (docker kill) / `start` (docker start) / `snapshot` |
| `elapsed_ms` | Lệnh Docker mất bao nhiêu mili giây |
| `note` | `ok` hoặc thông báo lỗi |

### Phân tích thời gian

```
CRASH kill:     ~40-50ms   ← SIGKILL ngay lập tức
JOIN start:     ~500-900ms ← Docker start + JVM boot + đăng ký
```

### Bảng stats cuối kịch bản

```
  alice       join=  0  crash=  0
  bob         join=  2  crash=  1
  duc         join=  2  crash=  1
  hoang       join=  0  crash=  0
  hoan        join=  0  crash=  0
```

---

## 6. Các chỉ số đánh giá hệ thống

### Chỉ số 1: Failure Detection Time

```
Ungraceful crash: ~15 giây  (heartbeat interval × miss threshold)
```

**Công thức:**

```
Detection Time = heartbeat_interval × miss_threshold
               = 5s × 3
               = 15s
```

### Chỉ số 2: Recovery Time

```
Thời gian từ docker start → peer nhận tin từ mailbox
≈ JVM boot (2s) + REGISTER (0.5s) + PULL_MESSAGES (0.5s)
≈ 3 giây
```

### Chỉ số 3: Message Delivery Rate

```
Delivery rate = tin đến nơi / tổng tin gửi × 100%

Kỳ vọng:
- Direct delivery (peer online):   100%
- Via mailbox (peer offline < TTL): ~100%
- Peer offline > TTL:                0% (bị expire)
```

### Chỉ số 4: Crash Detection — Crash vs Join

| | CRASH (docker kill) | JOIN (docker start) |
|---|---|---|
| Signal | SIGKILL | — |
| Peer gửi thông báo? | Không | — |
| Bootstrap phát hiện | Sau ~15s (heartbeat timeout) | Ngay (REGISTER) |
| Thời gian Docker | ~40ms | ~500-900ms |
| Data volume | Giữ nguyên | Giữ nguyên |

---

## 7. Kết luận

### Hệ thống P2PChat đã đạt được

| Tính chất | Kết quả |
|---|---|
| **Availability** | ✅ Tin nhắn không mất dù peer offline (mailbox) |
| **Failure Detection** | ✅ Crash: 15s |
| **Recovery** | ✅ Peer join lại nhận đủ tin tồn đọng |
| **Broadcast resilience** | ✅ Broadcast bỏ qua peer offline, không block |

### Điểm có thể cải thiện

| Vấn đề | Giải pháp tiềm năng |
|---|---|
| Crash detection 15s còn chậm | Giảm heartbeat interval (tốn băng thông hơn) |
| Không có metric tự động | Thêm Prometheus/Grafana |

---

## 8. Cấu trúc file trong project

```
P2PChat/
├── churn-sim.py          ← Script mô phỏng churn tự động
├── churn-monitor.py      ← Terminal dashboard quan sát real-time
├── churn-modes.md        ← Tài liệu chi tiết 3 chế độ mô phỏng
├── churn-logs/
│   ├── churn_simulation_demo.md   ← File này
│   └── churn_YYYYMMDD_HHMMSS.csv ← Log tự sinh khi chạy
└── run.sh                ← Quản lý Docker container
```
