# P2PChat — Issues cần khắc phục cho agent

Tài liệu này tổng hợp các vấn đề từ phần review hiện tại + các điểm mình bổ sung, theo hướng **có thể giao cho agent sửa trực tiếp**.  
Mỗi mục đều có: **vấn đề**, **ảnh hưởng**, **hướng khắc phục**, **task cho agent**.

---


## 🟡 Important (chưa khắc phục — không ảnh hưởng luồng chat chính)

### 5) Gossip timeout 15s có thể gây false positive
**Vấn đề**  
3 chu kỳ gossip mất (15s) để kết luận chết có thể hơi chặt trong môi trường Wi-Fi yếu, JVM GC pause, hoặc máy đang bận.

**Ảnh hưởng**  
- Peer sống nhưng bị đánh dấu dead.
- Re-election diễn ra quá sớm.
- Có thể làm nhóm rung lắc không cần thiết.

**Hướng khắc phục**  
- Nới timeout hoặc dùng threshold mềm hơn.
- Cân nhắc heartbeat 2s nhưng dead sau nhiều lần miss hơn, hoặc adaptive theo RTT / jitter.
- Có grace window cho môi trường demo LAN.

---

### 6) FILE_HAVE broadcast có nguy cơ tạo traffic O(N²)
**Vấn đề**  
Nếu nhiều người đang tải file và mỗi lần hoàn thành chunk batch đều broadcast `FILE_HAVE`, số message có thể tăng mạnh theo số peer. (Lưu ý: swarming chưa được triển khai thực tế, chỉ có message types.)

**Ảnh hưởng**  
- Tăng traffic control-plane khi triển khai swarming.
- Nhóm lớn hoặc file nhỏ với nhiều batch sẽ gây overhead rõ rệt.

**Hướng khắc phục**  
- Gom chunk indexes thành batch lớn hơn.
- Broadcast theo interval thay vì từng lần hoàn thành nhỏ.

---

### 7) Hard Cancel của C1 timeout chưa có persistence cho requestId
**Vấn đề**  
`processedRequestIds` hiện lưu trong memory (`ConcurrentHashMap.newKeySet()`). Nếu coordinator restart, dedup set bị mất.

**Ảnh hưởng**  
- Retry sau restart có thể bị xử lý lại.
- Chỉ ảnh hưởng edge case (restart giữa lúc đang xử lý request).

**Hướng khắc phục**  
- Persist `processedRequestIds` vào SQLite nếu cần (thêm bảng `processed_requests`).
- Có TTL rõ ràng cho requestId (ví dụ 60s).

---

## 🟢 Improvement (không ảnh hưởng chức năng)

### 3) P2P swarming chưa xử lý peer churn khi đang tải
**Trạng thái:** Swarming chưa triển khai thực tế — chỉ có message types (`FILE_HAVE`, `FILE_HAVE_REQ`, `FILE_HAVE_RESP`). File transfer nhóm hiện dùng direct pull từ sender. Sẽ xử lý khi implement swarming.

### 9) Lamport buffer 200ms đang hard-code
**Trạng thái:** Giá trị 200ms phù hợp cho demo LAN. Có thể chuyển sang configurable nếu cần.

### 10) Cách diễn đạt về Bootstrap cần nhất quán
**Trạng thái:** Bootstrap vẫn cần cho peer discovery và offline store 1-1. Tài liệu đã ghi rõ "Bootstrap không còn lưu GroupInfo" nhưng nên tránh nói "không còn SPOF" tuyệt đối.

---

## Kết luận

Đã khắc phục tất cả các issue Critical liên quan đến luồng chat. Các issues còn lại thuộc dạng **optimization** hoặc **future work**, không ảnh hưởng đến luồng chat chính.
