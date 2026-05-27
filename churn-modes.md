# Chế Độ Mô Phỏng Churn — P2PChat

## Tổng quan

`churn-sim.py` là script mô phỏng hiện tượng **churn** (node liên tục join/leave/crash) trong mạng P2P. Script điều khiển trạng thái Docker container của 5 peer (alice, bob, duc, hoang, hoan) để kiểm thử khả năng chịu tải và xử lý churn của hệ thống.

### 5 peer mặc định

| Peer   | Web Port | Container      |
|--------|----------|----------------|
| alice  | 33143    | `peer-alice`   |
| bob    | 33144    | `peer-bob`     |
| duc    | 12345    | `peer-duc`     |
| hoang  | 12346    | `peer-hoang`   |
| hoan   | 12349    | `peer-hoan`    |

### Ba chế độ mô phỏng

| Chế độ  | Lệnh                    | Mục đích chính                    |
|---------|-------------------------|-----------------------------------|
| random  | `--mode random`         | Stress-test, load-test            |
| wave    | `--mode wave`           | Quan sát phản ứng qua từng giai đoạn |
| scenario| `--mode scenario`        | Kiểm thử từng tính năng cụ thể   |

---

## 1. Chế độ Random

### Giới thiệu

Chế độ **random** mô phỏng churn hoàn toàn ngẫu nhiên — tại mỗi tick, mỗi peer có xác suất join hoặc leave/crash. Không có pattern, không có kịch bản trước. Đây là chế độ gần với thực tế nhất: trong mạng P2P thực sự, không ai rời mạng theo lịch trình.

### Dùng để làm gì?

- **Stress-test**: Kiểm tra hệ thống chịu được churn liên tục trong thời gian dài
- **Load-test**: Xem Bootstrap server và các peer xử lý thế nào khi churn xảy ra liên tục không ngừng
- **Phát hiện race condition**: Tình trạng cạnh tranh khi nhiều node churn cùng lúc
- **Phát hiện memory leak**: Rò rỉ tài nguyên khi churn lặp đi lặp lại nhiều lần

### Cách hoạt động

Mỗi **tick** (mặc định 15 giây), script thực hiện:

```
Với mỗi peer:
  Nếu peer đang online:
      random() < p_leave (0.30)? → Có thể rời mạng
          random() < crash_ratio (0.30)?
              → docker kill        → CRASH (ungraceful)
          Ngược lại:
              → docker stop        → LEAVE (graceful)
  Nếu peer đang offline:
      random() < p_join (0.50)? → Có thể tham gia lại
          → docker start          → JOIN
          → sleep(2)              (chờ đăng ký bootstrap)
```

### Tham số

| Tham số       | Mặc định | Mô tả                                      |
|---------------|----------|--------------------------------------------|
| `--duration`  | 180s     | Tổng thời gian chạy                       |
| `--interval`  | 15s      | Khoảng cách giữa mỗi tick                  |
| `--crash-ratio` | 0.30  | Tỉ lệ crash trong tổng lần leave (0–1)    |
| `--p-leave`  | 0.30     | Xác suất peer online rời mạng mỗi tick     |
| `--p-join`    | 0.50     | Xác suất peer offline join lại mỗi tick    |

### Ví dụ một phiên chạy

```
── Tick 1 ──  t=0s
  alice   ● ONLINE    (random 0.72 > 0.30 → giữ nguyên)
  bob     ● ONLINE    (random 0.15 < 0.30 → leave graceful)
  duc     ● ONLINE    (random 0.28 < 0.30 → crash ungraceful)
  hoang   ● ONLINE    (random 0.65 > 0.30 → giữ nguyên)
  hoan    ○ OFFLINE   (random 0.82 > 0.50 → giữ nguyên)
  → bob LEAVE, duc CRASH

── Tick 2 ──  t=15s
  bob     ○ OFFLINE   (random 0.33 < 0.50 → join)
  duc     ○ OFFLINE   (random 0.11 < 0.50 → join)
  hoan    ○ OFFLINE   (random 0.90 > 0.50 → giữ nguyên)
  → bob JOIN, duc JOIN
```

### Minh họa luồng

```
┌──────────────┐     tick (15s)      ┌────────────────────┐
│  Peer đang    │ ─────────────────► │ random() < p_leave?│
│  ONLINE       │                     │   Có → leave/crash │
└──────────────┘                     └─────────┬──────────┘
                                                │
                              ┌─────────────────┴──────────┐
                              │ random() < crash_ratio?     │
                              │   Có → docker kill (CRASH)  │
                              │   Không → docker stop (LEAVE)│
                              └─────────────────────────────┘

┌──────────────┐     tick (15s)      ┌────────────────────┐
│  Peer đang    │ ─────────────────► │ random() < p_join?  │
│  OFFLINE      │                     │   Có → docker start │
└──────────────┘                     │        (JOIN)       │
                                      └─────────────────────┘
```

---

## 2. Chế độ Wave

### Giới thiệu

Chế độ **wave** chạy 4 đợt sóng cố định chia đều theo thời gian: baseline → crash wave → recovery → graceful leave. Mỗi đợt có mục đích rõ ràng và thời điểm cố định, giúp người quan sát dễ dàng theo dõi phản ứng của hệ thống qua từng giai đoạn.

### Dùng để làm gì?

- **Quan sát phản ứng hệ thống** qua từng giai đoạn có tính kịch bản
- **Demo trực quan** cho báo cáo — biết trước "sắp xảy ra gì" nên dễ theo dõi
- **So sánh thời gian phản ứng** giữa crash detection và graceful leave detection
- **Kiểm tra recovery** — hệ thống phục hồi thế nào sau crash wave

### Cách hoạt động

```
Thời gian   │ Đợt              │ Docker command      │ Mục đích kiểm thử
────────────┼──────────────────┼────────────────────┼──────────────────────
t = 0%     │ BASELINE          │ docker start ALL    │ Khởi tạo trạng thái sạch
t = 25%    │ CRASH WAVE        │ docker kill (50%)   │ Ungraceful failure
t = 50%    │ RECOVERY WAVE     │ docker start (lại)  │ Rejoin + mailbox pull
t = 75%    │ GRACEFUL LEAVE    │ docker stop (1-2)   │ Graceful leave detection
t = 100%   │ (chờ còn lại)    │ (quan sát)          │ Quan sát phản ứng cuối
```

### Chi tiết từng đợt

#### Đợt 1 — Baseline (t = 0%)
```
for p in peers:
    if not is_running(p):
        docker start p          # Khởi động container
sleep(5)                         # Chờ đăng ký bootstrap
```
- **Mục đích**: Đảm bảo tất cả peer bắt đầu ở trạng thái online
- **Kỳ vọng**: Tất cả peer xuất hiện trong Bootstrap peer list

#### Đợt 2 — Crash Wave (t = 25%)
```
crash_targets = random.sample(peers, k=len(peers)//2)
for p in crash_targets:
    docker kill peer-{p}         # SIGKILL — không graceful
```
- **Mục đích**: Mô phỏng mất điện, mạng đứt đột ngột
- **Docker command**: `docker kill` gửi SIGKILL — container chết ngay lập tức
- **Bootstrap phản ứng**: Phải đợi **3 lần heartbeat miss** (~9-15 giây) mới đánh dấu peer offline
- **Kỳ vọng**: Peer trong danh sách biến mất khỏi online sau 9-15s

#### Đợt 3 — Recovery Wave (t = 50%)
```
for p in crash_targets:
    docker start peer-{p}        # Khởi động lại container
sleep(3)                         # Chờ đăng ký bootstrap
```
- **Mục đích**: Test khả năng rejoin sau ungraceful crash
- **Docker command**: `docker start` — container giữ nguyên data volume
- **Kỳ vọng**: Peer join lại, đăng ký bootstrap, có thể kéo mailbox

#### Đợt 4 — Graceful Leave (t = 75%)
```
leave_targets = random.sample(peers, k=min(2, len(peers)))
for p in leave_targets:
    docker stop peer-{p}         # SIGTERM — graceful
```
- **Mục đích**: Test graceful leave detection
- **Docker command**: `docker stop` gửi SIGTERM — cho phép container shutdown graceful
- **Bootstrap phản ứng**: Nhận `PEER_LEAVE` message **NGAY LẬP TỨC** — không cần đợi heartbeat
- **Điểm khác biệt với Crash Wave**:

| | Crash Wave | Graceful Leave |
|--|-----------|----------------|
| Docker command | `docker kill` | `docker stop` |
| Signal | SIGKILL | SIGTERM |
| Peer có gửi thông báo? | Không | Có — PEER_LEAVE |
| Bootstrap phát hiện | Sau ~9-15s (heartbeat timeout) | Ngay lập tức |
| Thời gian chờ để phát hiện | Dài | Tức thì |

### Minh họa luồng theo thời gian

```
Thời gian (180s)
├─0s─────┼─45s──────┼─90s──────┼─135s─────┼─180s─┤
│BASELINE │CRASH WAVE│RECOVERY  │GRACEFUL  │Chờ   │
│  ●●●●●  │  ●●○○○   │  ●●●●●   │  ●●●○○   │ ●●●●●│
│  all up │  50% die │  back up │  1-2 out  │stable│
```

---

## 3. Chế độ Scenario

### Giới thiệu

Chế độ **scenario** là kịch bản kiểm thử cố định gồm 10 bước, mỗi bước test một tính năng cụ thể của hệ thống P2PChat. Khác với random và wave, scenario **gửi tin nhắn thật qua REST API** để xác minh các tính năng store-and-forward, mailbox, broadcast hoạt động đúng.

### Dùng để làm gì?

- **Kiểm thử từng tính năng** một cách có hệ thống và có thể tái tạo
- **Xác minh** store-and-forward, graceful leave, crash detection, mailbox pull
- **Làm demo / báo cáo** — chuỗi kịch bản rõ ràng, dễ trình bày
- **So sánh** thời gian phản ứng giữa crash và graceful leave

### Cách hoạt động

10 bước chạy tuần tự, mỗi bước cách nhau vài giây để hệ thống xử lý:

```
Bước │ Thời │ Sự kiện                      │ Tính năng kiểm thử
─────┼───────┼──────────────────────────────┼──────────────────────────────
  1  │ t=0s  │ Tất cả peer online           │ Baseline — trạng thái sạch
  2  │ t=5s  │ bob CRASH ungraceful         │ Crash detection
  3  │ t=10s │ alice gửi tin cho bob        │ Store-and-forward
  4  │ t=15s │ hoang LEAVE graceful         │ Graceful leave detection
  5  │ t=23s │ duc CRASH ungraceful         │ Nhiều peer crash cùng lúc
  6  │ t=28s │ alice BROADCAST              │ Broadcast khi có peer offline
  7  │ t=33s │ bob JOIN lại                 │ Mailbox pull on join
  8  │ t=41s │ hoang JOIN lại               │ Graceful peer return
  9  │ t=46s │ duc JOIN lại                 │ Mailbox TTL
 10  │ t=51s │ Snapshot trạng thái cuối     │ Tổng kết kịch bản
```

### Chi tiết từng bước

#### Bước 1 — Baseline (t = 0s)
```
for p in peers:
    if not is_running(p["name"]):
        docker start peer-{p["name"]}
sleep(5)
```
- **Mục đích**: Khởi tạo trạng thái sạch — tất cả 5 peer online
- **Kỳ vọng**: Bootstrap nhận REGISTER từ alice, bob, duc, hoang, hoan
- **Kiểm tra**: 5 peer trong `/api/peers`, tất cả `online = true`

#### Bước 2 — Bob CRASH (t = 5s)
```
docker kill peer-bob     # SIGKILL
```
- **Mục đích**: Mô phỏng mất điện, mạng đứt đột ngột
- **Mechanism**: `docker kill` gửi SIGKILL, không cho phép graceful shutdown
- **Bootstrap phản ứng**: Bob tiếp tục "online" trong danh sách ~9-15s cho đến khi heartbeat timeout
- **Kỳ vọng**: Sau 9-15s, bob `online = false` trong `/api/peers`

#### Bước 3 — Alice gửi tin cho Bob (t = 10s)
```
POST http://localhost:33143/api/msg
{
  "receiver": "bob",
  "content": "Chào bob, bạn có nhận được không?"
}
```
- **Mục đích**: Test **store-and-forward**
- **Mechanism**: Bob đang offline, nhưng tin được lưu vào **mailbox** của bob trên Bootstrap server
- **Kỳ vọng**: API trả `STORED_MAILBOX` hoặc tương đương, tin được ghi vào mailbox

#### Bước 4 — Hoang LEAVE graceful (t = 15s)
```
docker stop peer-hoang   # SIGTERM
```
- **Mục đích**: Test **graceful leave detection**
- **Mechanism**: `docker stop` gửi SIGTERM → `PeerNode.shutdown()` được gọi → gửi `PEER_LEAVE` tới Bootstrap
- **Bootstrap phản ứng**: Nhận `PEER_LEAVE` **ngay lập tức** — không cần đợi heartbeat
- **Kỳ vọng**: Hoang `online = false` trong `/api/peers` **ngay** sau khi docker stop

#### Bước 5 — Duc CRASH (t = 23s)
```
docker kill peer-duc
```
- **Mục đích**: Test hệ thống xử lý **nhiều peer crash cùng lúc** (bob đã crash ở bước 2)
- **Kỳ vọng**: Bootstrap xử lý được 2 crash trước đó mà không bị block

#### Bước 6 — Alice BROADCAST (t = 28s)
```
POST http://localhost:33143/api/broadcast
{
  "content": "Broadcast test khi có peer offline"
}
```
- **Mục đích**: Test **broadcast khi có peer offline**
- **Tình huống**: Alice, hoan online | Bob, duc, hoang offline
- **Kỳ vọng**: Tin broadcast **chỉ gửi đến** alice và hoan — bob, duc, hoang (offline) không nhận được

#### Bước 7 — Bob JOIN lại (t = 33s)
```
docker start peer-bob
```
- **Mục đích**: Test **mailbox pull on join**
- **Mechanism**: Bob join → Bootstrap gửi danh sách offline messages cho bob
- **Kỳ vọng**: Bob nhận được tin nhắn alice đã gửi ở **bước 3** (đã được lưu trong mailbox)

#### Bước 8 — Hoang JOIN lại (t = 41s)
```
docker start peer-hoang
```
- **Mục đích**: Test graceful leave → graceful return
- **So với bước 7**: Hoang rời mạng graceful (bước 4), bob rời mạng ungraceful (bước 2)
- **Kỳ vọng**: Cả hai đều join lại bình thường, không khác biệt về kết quả

#### Bước 9 — Duc JOIN lại (t = 46s)
```
docker start peer-duc
```
- **Mục đích**: Test **mailbox TTL**
- **Tình huống**: Duc offline từ bước 5 (~40 giây), có thể có offline messages
- **Kỳ vọng**: Duc nhận tin tồn đọng, kiểm tra tin không bị xóa quá sớm

#### Bước 10 — Snapshot cuối (t = 51s)
```
final = snapshot_online(peers)
for name, online in final.items():
    logger.log(name, f"FINAL_STATE_{status}", "snapshot", 0, "")
```
- **Mục đích**: Ghi trạng thái cuối cùng và in bảng tổng kết

### Các tính năng được kiểm thử

| Tính năng | Bước | Cơ chế | Dấu hiệu thành công |
|-----------|------|--------|----------------------|
| **Store-and-Forward** | 3 | Bob offline, alice gửi tin → lưu mailbox | Tin không mất, được nhận ở bước 7 |
| **Graceful Leave Detection** | 4 | `docker stop` → PEER_LEAVE → bootstrap cập nhật ngay | Hoang offline **ngay** trong peer list |
| **Ungraceful Crash Detection** | 2, 5 | `docker kill` → bootstrap đợi heartbeat timeout | Bob/Duc offline sau ~9-15s |
| **Broadcast khi peer offline** | 6 | Tin chỉ gửi đến online peers | Alice, hoan nhận; bob, duc, hoang không |
| **Mailbox Pull on Join** | 7 | Bob join → bootstrap gửi offline messages | Bob nhận được tin từ bước 3 |
| **Graceful Leave → Return** | 4→8 | Hoang leave graceful, join graceful | Không khác gì bob (ungraceful) |
| **Mailbox TTL** | 9 | Duc offline lâu → join → nhận mailbox | Tin đến đúng, không bị xóa sớm |

### So sánh Crash vs Graceful

| | Ungraceful Crash (`docker kill`) | Graceful Leave (`docker stop`) |
|--|----------------------------------|-------------------------------|
| Signal | SIGKILL | SIGTERM |
| Peer có gửi thông báo? | ❌ Không | ✅ Có — `PEER_LEAVE` |
| Bootstrap phát hiện | Sau heartbeat timeout (~9-15s) | Ngay lập tức |
| Peer có cleanup? | ❌ Không — chết ngay | ✅ Có — shutdown graceful |
| Container state sau đó | Exited (exit code 137) | Exited (exit code 0) |
| Data volume | Giữ nguyên | Giữ nguyên |

---

## So sánh 3 chế độ

| | Random | Wave | Scenario |
|--|--------|------|---------|
| **Thời gian** | `--duration` (mặc định 180s) | `--duration` (mặc định 180s) | ~51s cố định |
| **Tính ngẫu nhiên** | Cao — mọi tick | Trung bình — đợt cố định, peer ngẫu nhiên | Thấp — chỉ peer ngẫu nhiên |
| **Gửi tin nhắn** | ❌ Không | ❌ Không | ✅ Có — 2 lần qua API |
| **Phù hợp cho** | Stress-test, load-test | Demo, phân tích giai đoạn | Kiểm thử tính năng cụ thể |
| **CSV log** | ✅ Có | ✅ Có | ✅ Có |
| **Kịch bản có thể dự đoán?** | Không | Có — biết 4 đợt | Có — biết 10 bước |
| **Có gửi API request?** | ❌ | ❌ | ✅ |
| **Phát hiện race condition?** | ✅ Cao | ✅ Trung bình | ❌ Thấp |

---

## Đầu ra của mô phỏng

### Console output
Mỗi sự kiện được in ra console với timestamp và chi tiết:
```
  [ 0ms] alice       JOIN              start     ok
  [5000ms] bob       LEAVE             graceful  ok
  [10000ms] duc      CRASH             kill      ok
  [17000ms] alice    FINAL_STATE_ONLINE snapshot  ok
```

### CSV log
File `churn-logs/churn_YYYYMMDD_HHMMSS.csv`:
```csv
timestamp,peer,event,method,elapsed_ms,note
2026-05-27T10:20:18.123,alice,JOHN,start,420,ok
2026-05-27T10:20:23.456,bob,LEAVE,graceful,5200,ok
```

### Summary cuối phiên
```
============================================================
CHURN SIMULATION — KẾT QUẢ
============================================================
  alice       join=2   leave=1   crash=0
  bob         join=1   leave=2   crash=1
  duc         join=0   leave=0   crash=1
  hoang       join=1   leave=1   crash=0
  hoan        join=3   leave=2   crash=0

Log đầy đủ: churn-logs/churn_20260527_102018.csv
```

---

## Chạy đồng thời với Monitor

Để quan sát trực quan nhất, chạy 3 terminal song song:

```bash
# Terminal 1 — Dashboard theo dõi real-time
python3 churn-monitor.py --interval 1

# Terminal 2 — Chạy mô phỏng
python3 churn-sim.py --mode scenario

# Terminal 3 — Log chi tiết Bootstrap server
./run.sh logs s
```

Ngoài ra, có thể mở **Bootstrap Dashboard** trên trình duyệt:
```
http://localhost:9001
```

Và giao diện chat của từng peer:
```
http://localhost:33143  (alice)
http://localhost:33144  (bob)
http://localhost:12345  (duc)
http://localhost:12346  (hoang)
http://localhost:12349  (hoan)
```
