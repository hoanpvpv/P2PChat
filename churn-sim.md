# Mô phỏng Churn — Event-Driven Simulation cho P2PChat

## Mục lục

1. [Mô phỏng churn là gì?](#1-mô-phỏng-churn-là-gì)
2. [Kiến trúc mô phỏng](#2-kiến-trúc-mô-phỏng)
3. [Các tham số mô phỏng](#3-các-tham-số-mô-phỏng)
4. [Cách chạy](#4-cách-chạy)
5. [Các loại sự kiện](#5-các-loại-sự-kiện)
6. [Các độ đo đầu ra](#6-các-độ-đo-đầu-ra)
7. [Kết quả mẫu và giải thích](#7-kết-quả-mẫu-và-giải-thích)

---

## 1. Mô phỏng churn là gì?

**Churn** (hay còn gọi là *tỷ lệ rời mạng*) là hiện tượng các peer trong mạng P2P liên tục tham gia (join) và rời khỏi (crash/offline) mạng một cách không đồng bộ. Trong thực tế, người dùng mạng ngang hàng thường không thông báo trước khi offline — họ có thể tắt ứng dụng, mất kết nối internet, hoặc máy tính bị tắt đột ngột.

Mô phỏng churn giúp đánh giá **khả năng chịu đựng** của mạng P2P trước sự biến động liên tục này, thông qua việc:

- Mô phỏng các peer join/crash theo các phân phối xác suất thực tế
- Theo dõi ảnh hưởng của từng sự kiện đến trạng thái mạng
- Đo lường 3 chỉ số cốt lõi: **MDR**, **CT**, **CO**

---

## 2. Kiến trúc mô phỏng

### 2.1 Mô hình hướng sự kiện (Event-Driven)

Thay vì mô phỏng theo bước thời gian cố định (tick-based), mô phỏng này dùng **hàng đợi ưu tiên** (priority queue — min-heap) sắp xếp theo `sim_time`. Mỗi sự kiện được xử lý **ngay tại thời điểm xảy ra** mà không cần chờ real-time.

```
Event Queue (min-heap):
┌─────────┬────────────┬────────┐
│ sim_time│ seq (tie)  │ event  │
├─────────┼────────────┼────────┤
│  2.15   │  3         │ MSG    │
│  5.00   │  7         │ PING   │  ← lấy sự kiện có sim_time nhỏ nhất
│ 10.00   │  12        │ PING   │
│ 26.67   │  18        │ REG    │
│  ...    │  ...       │  ...   │
└─────────┴────────────┴────────┘
```

**Ưu điểm so với tick-based:**
- Chính xác tuyệt đối về thời gian sự kiện
- Hiệu suất cao vì chỉ xử lý sự kiện thực sự xảy ra
- Dễ dàng mở rộng thêm loại sự kiện mới

### 2.2 Các thành phần chính

```
churn_sim.py
│
├── BootstrapNode          # Node trung tâm: quản lý danh sách peer online
│
├── RoutingTable           # Bảng định tuyến: láng giềng của mỗi peer
│
├── Peer                  # Đối tượng peer: trạng thái, mailbox, inbox
│
├── Network               # Mạng P2P toàn cục: quản lý tất cả peer
│
├── Metrics               # Tracker: thu thập và tính MDR, CT, CO
│
└── Simulation           # Engine: event loop, scheduling, handlers
```

### 2.3 Vòng đời của một peer

```
                         ┌─────────────────────┐
                         │  Peer được tạo sẵn  │
                         │  (Init peer)        │
                         └────────┬────────────┘
                                  │ join ngay t=0
                                  ▼
                         ┌─────────────────────┐
                         │     ONLINE          │
                         │  • Nhận tin nhắn   │
                         │  • Gửi tin nhắn    │
                         │  • Ping láng giềng │
                         └────────┬────────────┘
                                  │ session_time ~ Exp(mean_session)
                                  │ CRASH ĐỘT NGỘT (không báo trước)
                                  ▼
                         ┌─────────────────────┐
                         │     OFFLINE        │  ← sau downtime
                         │  • Bị loại khỏi mạng│
                         │  • Láng giềng cập nhật bảng│
                         │  • Tin nhắn chờ mailbox│
                         └────────┬────────────┘
                                  │ downtime_time ~ Exp(mean_downtime)
                                  │ REJOIN
                                  ▼
                         ┌─────────────────────┐
                         │     ONLINE          │  ← quay lại mạng
                         │  • Nhận mailbox     │
                         │  • Tiếp tục giao tiếp│
                         └────────┬────────────┘
                                  │ (lặp vô hạn)
```

**Vòng đời peer lặp vô hạn:** `ONLINE → OFFLINE → ONLINE → ...` cho đến khi mô phỏng kết thúc.

### 2.4 Mạng P2P và Topology

Mỗi peer duy trì **bảng định tuyến** chứa danh sách láng giềng (neighbors). Khi peer join/crash, tất cả bảng liên quan được cập nhật.

**3 loại topology:**

| Topology | Mô tả | Ưu điểm |
|---|---|---|
| `random` | Mỗi peer chọn ngẫu nhiên `max_neighbors` peer khác | Mô phỏng mạng ngang hàng ngẫu nhiên |
| `ring` | Peer kết nối với 2 láng giềng liền kề | Đơn giản, dễ phân tích |
| `mesh` | Peer kết nối với tất cả peer còn lại (hoặc tối đa) | Độ liên kết cao, khả năng chịu lỗi tốt |

---

## 3. Các tham số mô phỏng

### 3.1 Danh sách tham số

| Tham số | Flag | Mặc định | Ý nghĩa |
|---|---|---|---|
| `n-peers` | `--n-peers` | 10 | Tổng số peer trong mạng (bao gồm init + join thêm) |
| `duration` | `--duration` | 300s | Thời gian mô phỏng (giây) |
| `mean-session` | `--mean-session` | 60s | Trung bình thời gian peer sống trước khi crash |
| `mean-downtime` | `--mean-downtime` | 30s | Trung bình thời gian offline trước khi rejoin |
| `msg-interval` | `--msg-interval` | 5s | Khoảng cách trung bình giữa 2 tin nhắn chat |
| `heartbeat-interval` | `--heartbeat-interval` | 5s | Chu kỳ heartbeat (Ping/Pong) |
| `lambda-join` | `--lambda-join` | 0.05 peer/s | Tốc độ peer mới tham gia (3 peer/phút) |
| `topology` | `--topology` | random | Cấu trúc liên kết mạng |
| `max-neighbors` | `--max-neighbors` | 4 | Số láng giềng tối đa mỗi peer |
| `seed` | `--seed` | 42 | Hạt giống random (để tái lập kết quả) |

### 3.2 Ý nghĩa chi tiết từng tham số

#### `--n-peers`
Tổng số peer trong mạng. Trong đó:
- **`DEFAULT_INIT_PEERS = 5`** peer có sẵn từ đầu, có vòng đời churn đầy đủ (online → crash → offline → rejoin → online → ...)
- **N - 5** peer còn lại tham gia theo Poisson trong quá trình mô phỏng, sau đó cũng có vòng đời churn riêng

#### `--mean-session` (Thời gian sống)
Thời gian sống trung bình của peer. Thời gian sống thực tế mỗi peer được lấy mẫu từ **phân phối mũ (Exponential)**:

```
session_time_i ~ Exp(λ = 1/mean_session)
```

- `mean_session = 60s` → trung bình peer sống 60s trước khi crash
- `mean_session = 120s` → mạng ổn định hơn, ít churn hơn
- `mean_session = 20s` → mạng biến động mạnh, nhiều crash hơn

#### `--mean-downtime` (Thời gian downtime)
Thời gian offline trung bình trước khi peer quay lại mạng. Thời gian downtime thực tế được lấy mẫu từ **phân phối mũ (Exponential)**:

```
downtime_i ~ Exp(λ = 1/mean_downtime)
```

- `mean_downtime = 30s` → trung bình peer offline 30s rồi rejoin
- `mean_downtime = 60s` → peer offline lâu hơn trước khi quay lại
- `mean_downtime = 10s` → peer rejoin nhanh, mạng ít thời gian mất kết nối

#### `--lambda-join` (Tốc độ join)
Tốc độ peer mới tham gia mạng theo **quá trình Poisson**:
- Thời gian giữa 2 lần join liên tiếp: `Exp(1/lambda_join)`
- `lambda_join = 0.05` → trung bình 1 peer mới tham gia mỗi 20s = 3 peer/phút

#### `--heartbeat-interval` (Chu kỳ heartbeat)
Khoảng thời gian giữa 2 lần heartbeat. Mỗi heartbeat:
1. Mỗi peer gửi **PING** đến **tất cả láng giềng**
2. Láng giềng trả **PONG** nếu còn online
3. Nếu không nhận được PONG → peer đó được coi là **crashed**

```
Convergence Time lý thuyết = heartbeat_interval × 2 đến 3
Ví dụ: HB = 5s → mạng hội tụ trong khoảng 10–15s sau crash
```

#### `--msg-interval` (Khoảng cách tin nhắn)
Khoảng thời gian trung bình giữa 2 tin nhắn chat:
- Tin nhắn được gửi **ngẫu nhiên** giữa 2 peer đang online
- Nếu receiver offline → tin nhắn được lưu trong **mailbox**

#### `--topology`
- `random`: Mỗi peer chọn `max_neighbors` peer ngẫu nhiên làm láng giềng
- `ring`: Peer `i` kết nối với `i-1` và `i+1` (theo vòng tròn)
- `mesh`: Mỗi peer kết nối với tất cả peer khác (hoặc `max_neighbors` peer nếu đủ)

---

## 4. Cách chạy

### 4.1 Lệnh cơ bản

```bash
# Chạy mặc định (10 peer, 300s, random topology)
python churn_sim.py

# Với tham số tùy chỉnh
python churn_sim.py --n-peers 20 --duration 300 --mean-session 60
```

### 4.2 Các ví dụ chạy

```bash
# Ring topology — ít liên kết hơn, CT cao hơn
python churn_sim.py --topology ring --n-peers 15 --duration 300

# Churn nặng — peer sống ngắn, nhiều crash
python churn_sim.py --mean-session 20 --duration 300 --seed 7

# Mesh topology — nhiều láng giềng, CT thấp hơn
python churn_sim.py --topology mesh --max-neighbors 6 --n-peers 20 --duration 300

# Tin nhắn dày đặc hơn
python churn_sim.py --msg-interval 1.0 --duration 300

# Heartbeat nhanh hơn — phát hiện crash sớm hơn
python churn_sim.py --heartbeat-interval 2.0 --duration 300

# Tái lập kết quả với cùng seed
python churn_sim.py --seed 42 --n-peers 15 --duration 300
```

### 4.3 Output

```
======================================================================
  P2PChat Event-Driven Churn Simulation
======================================================================
  Peers            : 10
  Init peers       : 5
  Topology         : random
  Duration         : 300.0s
  Mean session     : 60.0s  (Exponential)
  Mean msg gap     : 5.0s  (Exponential)
  HB interval      : 5.0s
  Lambda join      : 0.05 peer/s
  Max neighbors    : 4
======================================================================
    SIM_T  EVENT            SRC          DST          DETAIL
----------------------------------------------------------------------
     2.15  SEND_MSG         peer_02      peer_00      [ONLINE] delivered=True
     5.00  PING             ALL          NEIGHBORS    online=5 total_neigh=20
    26.67  REGISTER         peer_05      BOOTSTRAP    online=6 neighbors=4
   100.93  CRASH            peer_06      NETWORK      session=51.7s affected=4
   105.00  CONVERGE         NETWORK      peer_06      ct=4.07s
   215.76  CRASH            peer_05      NETWORK      session=189.1s affected=4
   300.00  END                                        simulation complete

======================================================================
  KET QUA MO PHONG CHURN
======================================================================

  [1] THONG KE SU KIEN
  Tong su kien xu ly : 89
  Loai                          So lan
  ----------------------------------------
  ACK                               5
  CONVERGE                          3
  CRASH                             4
  END                               1
  PING                             60
  REGISTER                          5
  SEND_MSG                        63
  ----------------------------------------
  Online dau                       5
  Online cuoi                      6

  [2] MESSAGE DELIVERY RATIO (MDR)
  Tong tin gui       : 63
  Da delivered       : 63
  MDR tong            : 100.00%
  MDR direct          : 100.00%  (63 tin, receiver online)

  [3] CONVERGENCE TIME (CT)
  So crash phat hien   : 3
  Trung binh           : 13.32s
  Min                  : 2.57s
  Max                  : 24.07s
  Ly thuyet (~HB*3)    : 15s

  [4] CONTROL OVERHEAD (CO)
  Data bytes          : 16,128
  Control bytes       : 198,528
  Control Overhead     : 92.49%
  Pings sent          : 1,260
  Pings lost          : 16

  [5] KET LUAN
  MDR  = 100.00%  | [OK]
  CT   =  13.32s  | [OK]
  CO   =  92.49%  | [WARN] High overhead

  Log : churn-logs\churn_20260527_173423.csv
======================================================================
```

### 4.4 File log CSV

Mỗi lần chạy sinh file log tại `churn-logs/churn_<timestamp>.csv`:

```csv
sim_time,event,src,dst,detail
2.15,SEND_MSG,peer_02,peer_00,[ONLINE] delivered=True
5.00,PING,ALL,NEIGHBORS,online=5 total_neigh=20
26.67,REGISTER,peer_05,BOOTSTRAP,online=6 neighbors=4
100.93,CRASH,peer_06,NETWORK,session=51.7s affected=4
105.00,CONVERGE,NETWORK,peer_06,ct=4.07s
300.00,END,,,simulation complete
```

---

## 5. Các loại sự kiện

### 5.1 Tổng quan

| Sự kiện | Mô tả | Ai tạo ra |
|---|---|---|
| `REGISTER` | Peer mới đăng ký với Bootstrap Node | Poisson join scheduler |
| `ACK` | Bootstrap trả lời xác nhận đăng ký | Bootstrap (trong `_on_register`) |
| `CRASH` | Peer rời mạng đột ngột | Session timer |
| `REJOIN` | Peer quay lại sau downtime | Downtime timer |
| `SEND_MSG` | Peer gửi tin nhắn chat | Message scheduler |
| `PING` | Peer gửi heartbeat đến láng giềng | Heartbeat scheduler |
| `CONVERGE` | Mạng đã hội tụ sau 1 crash | `_on_ping` handler |
| `END` | Mô phỏng kết thúc | `_schedule_initial` |

### 5.2 Luồng xử lý chi tiết

#### REGISTER → ACK

```
Peer mới ──REGISTER──▶ BootstrapNode
                            │
                            │ register(peer_id)
                            │ rebuild routing table
                            │ broadcast update
                            │
                            ▼
                      ◀──ACK── Peer mới online
```

#### CRASH → REJOIN

```
Peer ──CRASH──▶ Network
                    │
                    │ peer_crash(pid)
                    │  • online = False
                    │  • unregister
                    │  • remove from neighbors' tables
                    │  • clear routing table
                    │
                    ▼
              CONVERGENCE tracked
                    │
                    │ downtime ~ Exp(mean_downtime)
                    ▼
              Peer ──REJOIN──▶ BootstrapNode
                    │                │
                    │                │ register, rebuild table
                    ▼                ▼
              ONLINE ◀─────────────ACK
```

#### PING / Heartbeat

```
Mỗi peer online gửi PING đến mỗi láng giềng
    │
    ├─▶ Láng giềng online ──PONG──▶ Ghi nhận PONG
    │
    └─▶ Láng giềng offline ──(mất PONG)──▶ Đánh dấu mất
                                                      │
                                                      ▼
                            Kiểm tra: crashed peer còn trong bảng ai?
                                 │
                                 ├─ Còn → Chờ heartbeat tiếp theo
                                 └─ Không còn → CONVERGE
```

---

## 6. Các độ đo đầu ra

### 6.1 MDR — Message Delivery Ratio

**Ý nghĩa:** Tỷ lệ phần trăm tin nhắn chat **thực sự đến được** người nhận.

```
MDR = (Số tin nhắn đã delivered) / (Tổng số tin nhắn gửi) × 100%
```

**Phân loại:**
- **MDR Direct**: tin gửi khi receiver đang online → giao ngay lập tức
- **MDR Mailbox**: tin gửi khi receiver offline → lưu trong mailbox, delivered khi receiver quay lại

**Ngưỡng đánh giá:**

| MDR | Đánh giá |
|---|---|
| ≥ 80% | `[OK]` — Mạng hoạt động tốt |
| < 80% | `[WARN]` — Tỷ lệ mất tin cao |

**Cách tính trong code:**

```python
def mdr(self) -> float:
    total     = len(self.messages)
    delivered = sum(1 for m in self.messages.values() if m["delivered"])
    return delivered / total * 100.0
```

### 6.2 CT — Convergence Time

**Ý nghĩa:** Thời gian trung bình để **tất cả bảng định tuyến trong mạng** ổn định lại sau khi một peer bị crash.

```
CT = Thời điểm mạng hội tụ - Thời điểm crash xảy ra
```

**Cơ chế đo lường:**
1. Peer crash → thêm vào `_pending_convergence`
2. Mỗi heartbeat cycle: kiểm tra xem crashed peer còn trong bảng định tuyến của ai không
3. Khi không còn ai giữ crashed peer trong bảng → ghi nhận convergence

```
Crash t=100s ──────────────── heartbeat ──────────────── Converge t=124s
                                 │                           │
                                 └──────── CT = 24s ────────┘
```

**Ngưỡng đánh giá:**

| CT | Đánh giá |
|---|---|
| ≤ HB × 4 | `[OK]` — Mạng hội tụ nhanh |
| > HB × 4 | `[WARN]` — Hội tụ chậm, cần tối ưu |

**Công thức lý thuyết:** `CT ≈ 2 × heartbeat_interval` (trung bình phát hiện trong 2 chu kỳ heartbeat)

### 6.3 CO — Control Overhead

**Ý nghĩa:** Tỷ lệ băng thông tiêu tốn cho **gói tin điều khiển** (Ping, REGISTER, ACK, UPDATE) trên tổng lưu lượng mạng.

```
CO = Control_bytes / (Data_bytes + Control_bytes) × 100%
```

**Kích thước gói tin (bytes):**

| Loại gói | Kích thước |
|---|---|
| REGISTER | 128 |
| ACK | 128 |
| PING | 64 |
| PONG | 64 |
| UPDATE (route) | 80 |
| CHAT_MSG (data) | 256 |

**Ngưỡng đánh giá:**

| CO | Đánh giá |
|---|---|
| ≤ 80% | `[OK]` — Tỷ lệ data/control hợp lý |
| > 80% | `[WARN]` — Quá nhiều control packet |

**Giải thích giá trị CO cao (~90%):** Trong mô phỏng này, heartbeat được gửi **mỗi 5s** từ **mọi peer** đến **mọi láng giềng** → số lượng PING/PONG rất lớn. Tin nhắn chat chỉ gửi mỗi ~5s giữa 2 peer. Trong thực tế P2P, tỷ lệ CO cao là bình thường vì heartbeat là cơ chế bắt buộc để duy trì trạng thái mạng.

---

## 7. Kết quả mẫu và giải thích

### 7.1 Ví dụ 1: Mạng ổn định

```bash
python churn_sim.py --n-peers 10 --mean-session 60 --mean-downtime 30 --duration 300 --seed 42
```

| Độ đo | Giá trị | Ý nghĩa |
|---|---|---|
| MDR | 100.00% | Tất cả tin nhắn đến được đích |
| CT | ~10–13s | Mạng hồi phục trong ~10-13s sau crash |
| CO | ~88% | Heartbeat chiếm ưu thế trong lưu lượng |
| REJOIN | Nhiều | Peer quay lại sau downtime |

### 7.2 Ví dụ 2: Churn nặng (short session, short downtime)

```bash
python churn_sim.py --n-peers 10 --mean-session 15 --mean-downtime 10 --duration 60 --seed 42
```

| Độ đo | Giá trị | Ý nghĩa |
|---|---|---|
| MDR | 100.00% | Tất cả tin nhắn đến được đích |
| CT | ~10s | Nhiều crash xảy ra gần nhau |
| REJOIN | Nhiều | Peer rejoin nhanh sau downtime ngắn |
| Số CRASH | 18 | Nhiều sự kiện churn trong 60s |

### 7.3 Ví dụ 3: Ring topology

```bash
python churn_sim.py --topology ring --n-peers 15 --mean-session 60 --mean-downtime 30 --duration 300
```

| Độ đo | Giá trị | Ý nghĩa |
|---|---|---|
| CT | Cao hơn | Ring có ít liên kết hơn → mạng mất thời gian lan truyền thông tin |
| MDR | Tương đương | Tin nhắn vẫn đến được (vì chọn 2 peer online ngẫu nhiên) |
| REJOIN | Nhiều | Peer quay lại sau downtime, ring topology ít ảnh hưởng |

---

## Phụ lục: Cấu trúc code

```
churn_sim.py (786 dòng)
│
├── Cấu hình (hằng số, kích thước gói tin)
│
├── Định nghĩa sự kiện (EV_*)
│
├── Event dataclass (min-heap compatible)
│
├── exp_sample() — phân phối Exponential
│
├── BootstrapNode — quản lý peer registry
│
├── RoutingTable — bảng định tuyến peer
│
├── Peer — đối tượng peer (online, mailbox, inbox)
│
├── Network — quản lý mạng P2P (join, crash, deliver)
│
├── Metrics — tracker cho MDR, CT, CO
│
└── Simulation — engine event-driven
    │
    ├── _schedule_initial()     — lên lịch sự kiện ban đầu
    ├── _schedule_crash()       — đặt crash cho peer
    ├── _schedule_all_poisson_joins()
    │
    ├── _on_register()          — xử lý peer join
    ├── _on_crash()             — xử lý peer crash
    ├── _on_send_msg()          — xử lý gửi tin nhắn
    ├── _on_ping()              — xử lý heartbeat + convergence
    │
    ├── run()                   — vòng event loop chính
    └── _print_results()        — in kết quả cuối
```
