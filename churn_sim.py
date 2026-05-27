#!/usr/bin/env python3
"""
churn_sim.py — Event-Driven Churn Simulation cho P2PChat
=========================================================

Spec:
  - N peer định danh, liên kết theo topology (random | ring | mesh).
  - Peer arrivals: Poisson process -> inter-arrival ~ Exp(1/lambda_join).
    Các peer mới gọi Bootstrap Node để cập nhật bảng định tuyến.
  - Session Time: Exp(mean_session) -> peer offline ĐỘT NGỘT không báo trước.
  - Downtime: Exp(mean_downtime) -> peer quay lại (REJOIN) sau downtime.
  - Messages: peer online gửi tin nhắn ngẫu nhiên cho nhau.
  - Heartbeat: Ping/Pong định kỳ phát hiện peer lân cận sập.
  - Metrics: MDR, Convergence Time, Control Overhead.

Metrics đầu ra:
  1. MDR  — Message Delivery Ratio    : % tin nhắn đến được đích.
  2. CT   — Convergence Time         : TB thời gian mạng ổn định sau mỗi crash.
  3. CO   — Control Overhead          : % băng thông cho control trên tổng.

Usage:
  python churn_sim.py
  python churn_sim.py --n-peers 20 --duration 300
  python churn_sim.py --mean-session 60 --mean-downtime 30
  python churn_sim.py --topology ring --msg-interval 2.0
"""

import heapq
import random
import math
import argparse
import csv
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional

# ─── Cấu hình mặc định ───────────────────────────────────────────────────────
DEFAULT_N_PEERS        = 10
DEFAULT_DURATION       = 300.0       # giây mô phỏng
DEFAULT_MEAN_SESSION   = 60.0       # thời gian sống TB (s) — sau đó crash
DEFAULT_MEAN_DOWNTIME  = 30.0       # thời gian offline TB (s) — trước khi rejoin
DEFAULT_MSG_INTERVAL   = 5.0        # khoảng cách TB giữa 2 tin nhắn (s)
DEFAULT_HB_INTERVAL   = 5.0        # chu kỳ heartbeat (s)
DEFAULT_LAMBDA_JOIN   = 0.05       # tốc độ peer mới join: 0.05 peer/s = 3 peer/phút
DEFAULT_MAX_NEIGHBORS = 4          # số láng giềng tối đa
DEFAULT_TOPOLOGY      = "random"   # random | ring | mesh
DEFAULT_SEED          = 42
DEFAULT_INIT_PEERS    = 5          # số peer có sẵn từ đầu

LOG_DIR = Path(__file__).parent / "churn-logs"

# Kích thước gói tin (bytes) — cho Control Overhead
SIZE_REGISTER  = 128
SIZE_ACK      = 128
SIZE_PING     = 64
SIZE_PONG     = 64
SIZE_UPDATE   = 80
SIZE_CHAT_MSG = 256


# ─── Loại sự kiện ───────────────────────────────────────────────────────────
EV_REGISTER    = "REGISTER"   # Peer mới đăng ký với Bootstrap
EV_ACK        = "ACK"        # Bootstrap trả lời ACK + peer list
EV_CRASH      = "CRASH"      # Peer crash đột ngột (không báo trước)
EV_REJOIN     = "REJOIN"     # Peer quay lại sau downtime
EV_SEND_MSG   = "SEND_MSG"   # Gửi tin nhắn chat
EV_PING       = "PING"       # Peer gửi Ping đến láng giềng
EV_CONVERGENCE = "CONVERGE"  # Mạng đã hội tụ sau crash
EV_END        = "END"        # Mô phỏng kết thúc


# ─── Cấu trúc sự kiện (min-heap) ─────────────────────────────────────────────
@dataclass(order=True)
class Event:
    sim_time:  float
    seq:       int = field(compare=True)
    ev_type:   str = field(compare=False, default="")
    src:       str = field(compare=False, default="")
    dst:       str = field(compare=False, default="")
    data:      dict = field(compare=False, default_factory=dict)


# ─── Phân phối xác suất ───────────────────────────────────────────────────────
def exp_sample(mean: float) -> float:
    """Mẫu từ phân phối Exponential với trung bình = mean."""
    if mean <= 0:
        return float('inf')
    return random.expovariate(1.0 / mean)


# ─── Bootstrap Node ───────────────────────────────────────────────────────────
class BootstrapNode:
    """Bootstrap Node: luôn online, quản lý danh sách peer và topology."""

    def __init__(self):
        self.peer_registry: set[str] = set()

    def register(self, peer_id: str):
        self.peer_registry.add(peer_id)

    def unregister(self, peer_id: str):
        self.peer_registry.discard(peer_id)

    def get_online_peers(self) -> list[str]:
        return list(self.peer_registry)

    def count(self) -> int:
        return len(self.peer_registry)


# ─── Routing Table ─────────────────────────────────────────────────────────────
class RoutingTable:
    def __init__(self, peer_id: str, max_neighbors: int = DEFAULT_MAX_NEIGHBORS):
        self.peer_id        = peer_id
        self.max_neighbors  = max_neighbors
        self.neighbors: set[str] = set()

    def rebuild(self, all_peers: list[str]):
        """Xây dựng lại danh sách láng giềng từ danh sách peer đang online."""
        self.neighbors.clear()
        available = [p for p in all_peers if p != self.peer_id]
        random.shuffle(available)
        for p in available[:self.max_neighbors]:
            self.neighbors.add(p)

    def remove_neighbor(self, peer: str):
        self.neighbors.discard(peer)

    def add_neighbor(self, peer: str):
        if len(self.neighbors) < self.max_neighbors and peer != self.peer_id:
            self.neighbors.add(peer)


# ─── Peer ────────────────────────────────────────────────────────────────────
class Peer:
    def __init__(self, peer_id: str, max_neighbors: int = DEFAULT_MAX_NEIGHBORS):
        self.peer_id    = peer_id
        self.online     = False
        self.crashed_at: float = -1.0
        self.rt         = RoutingTable(peer_id, max_neighbors)
        self.mailbox: dict[str, dict] = {}
        self.inbox: list[dict] = []

    def receive_msg(self, sender_id: str, msg_id: str, content: str, sim_time: float):
        if self.online:
            self.inbox.append({
                "sender": sender_id, "msg_id": msg_id,
                "content": content, "sim_time": sim_time,
                "delivered": True,
            })


# ─── Network ─────────────────────────────────────────────────────────────────
class Network:
    """Mạng P2P toàn cục — quản lý trạng thái tất cả peer và topology."""

    def __init__(self, n_peers: int, topology: str = "random",
                 max_neighbors: int = DEFAULT_MAX_NEIGHBORS,
                 seed: int = DEFAULT_SEED):
        random.seed(seed)
        self.n_peers       = n_peers
        self.topology      = topology
        self.max_neighbors = max_neighbors
        self.sim_time      = 0.0
        self.bootstrap     = BootstrapNode()

        self.peer_ids = [f"peer_{i:02d}" for i in range(n_peers)]
        self.peers: dict[str, Peer] = {
            pid: Peer(pid, max_neighbors) for pid in self.peer_ids
        }

        self._init_peers = min(DEFAULT_INIT_PEERS, n_peers)
        for pid in self.peer_ids[:self._init_peers]:
            self.peer_join(pid)

    def _init_topology_for(self, pid: str):
        """Rebuild topology của 1 peer khi có peer mới / rejoin."""
        all_online = self.bootstrap.get_online_peers()
        self.peers[pid].rt.rebuild(all_online)
        for nid in self.peers[pid].rt.neighbors:
            self.peers[nid].rt.add_neighbor(pid)

    def peer_join(self, peer_id: str) -> bool:
        """Peer join/rejoin: đăng ký với Bootstrap, cập nhật bảng định tuyến."""
        peer = self.peers.get(peer_id)
        if not peer:
            return False

        peer.online = True
        self.bootstrap.register(peer_id)
        self._init_topology_for(peer_id)
        return True

    def _drain_mailbox(self, peer_id: str):
        """Khi peer online, nhận các tin nhắn đang chờ trong mailbox."""
        peer = self.peers.get(peer_id)
        if not peer or not peer.online:
            return
        for pid, p in self.peers.items():
            if pid == peer_id:
                continue
            for msg_id, msg in list(p.mailbox.items()):
                if msg.get("receiver") == peer_id:
                    peer.receive_msg(msg["sender"], msg_id, msg["content"], self.sim_time)
                    del p.mailbox[msg_id]

    def peer_crash(self, peer_id: str) -> set[str]:
        """Peer crash: loại khỏi mạng, thông báo cho láng giềng."""
        peer = self.peers.get(peer_id)
        if not peer or not peer.online:
            return set()

        peer.online     = False
        peer.crashed_at = self.sim_time
        self.bootstrap.unregister(peer_id)

        affected = set(peer.rt.neighbors)
        for nid in affected:
            self.peers[nid].rt.remove_neighbor(peer_id)
        peer.rt.neighbors.clear()
        return affected

    def deliver_message(self, sender_id: str, receiver_id: str,
                        msg_id: str, content: str) -> bool:
        """Gửi tin nhắn: online -> giao ngay; offline -> mailbox."""
        sender   = self.peers.get(sender_id)
        receiver = self.peers.get(receiver_id)
        if not sender or not receiver:
            return False

        if receiver.online:
            receiver.receive_msg(sender_id, msg_id, content, self.sim_time)
            return True
        else:
            receiver.mailbox[msg_id] = {
                "sender": sender_id, "receiver": receiver_id,
                "content": content, "sim_time": self.sim_time,
            }
            return True

    def is_online(self, peer_id: str) -> bool:
        return peer_id in self.bootstrap.peer_registry

    def online_peers(self) -> list[str]:
        return self.bootstrap.get_online_peers()


# ─── Metrics Tracker ─────────────────────────────────────────────────────────
class Metrics:
    def __init__(self):
        self.messages: dict[str, dict] = {}
        self.msg_seq   = 0

        # Convergence Time
        self.convergence: list[dict] = []
        self._pending_crash: list[dict] = []

        # Control Overhead (bytes)
        self.data_bytes     = 0
        self.control_bytes  = 0

        # Ping/Pong stats
        self.pings_sent  = 0
        self.pongs_recv  = 0
        self.pings_lost  = 0

    # ── Messages ─────────────────────────────────────────────────────────────

    def record_msg(self, sender: str, receiver: str,
                  online_when_sent: bool, sim_time: float) -> str:
        self.msg_seq += 1
        msg_id = f"m{self.msg_seq:04d}"
        self.messages[msg_id] = {
            "sender": sender, "receiver": receiver,
            "online_when_sent": online_when_sent,
            "delivered": False, "sim_time": sim_time,
            "size": SIZE_CHAT_MSG,
        }
        self.data_bytes += SIZE_CHAT_MSG
        return msg_id

    def mark_delivered(self, msg_id: str):
        if msg_id in self.messages:
            self.messages[msg_id]["delivered"] = True

    def mdr(self) -> float:
        total = len(self.messages)
        if total == 0:
            return 0.0
        delivered = sum(1 for m in self.messages.values() if m["delivered"])
        return delivered / total * 100.0

    def mdr_direct(self) -> float:
        direct = [m for m in self.messages.values() if m["online_when_sent"]]
        if not direct:
            return 0.0
        d = sum(1 for m in direct if m["delivered"])
        return d / len(direct) * 100.0

    def mdr_mailbox(self) -> float:
        offline = [m for m in self.messages.values() if not m["online_when_sent"]]
        if not offline:
            return 0.0
        d = sum(1 for m in offline if m["delivered"])
        return d / len(offline) * 100.0

    # ── Convergence ──────────────────────────────────────────────────────────

    def record_crash(self, peer: str, crash_t: float, affected_neighbors: int):
        rec = {
            "peer": peer,
            "crash_t": crash_t,
            "detected_t": None,
            "convergence_s": None,
            "affected_neighbors": affected_neighbors,
        }
        self.convergence.append(rec)
        self._pending_crash.append(rec)

    def mark_converged(self, peer: str, detected_sim_time: float):
        for r in self._pending_crash:
            if r["peer"] == peer and r["convergence_s"] is None:
                r["detected_t"]      = detected_sim_time
                r["convergence_s"]   = detected_sim_time - r["crash_t"]
                return

    def avg_convergence(self) -> float:
        times = [r["convergence_s"] for r in self.convergence
                 if r["convergence_s"] is not None]
        return sum(times) / len(times) if times else 0.0

    def max_convergence(self) -> float:
        times = [r["convergence_s"] for r in self.convergence
                 if r["convergence_s"] is not None]
        return max(times) if times else 0.0

    def min_convergence(self) -> float:
        times = [r["convergence_s"] for r in self.convergence
                 if r["convergence_s"] is not None]
        return min(times) if times else 0.0

    # ── Control Overhead ─────────────────────────────────────────────────────

    def record_register(self):
        self.control_bytes += SIZE_REGISTER + SIZE_ACK

    def record_ping(self):
        self.control_bytes += SIZE_PING
        self.pings_sent += 1

    def record_pong(self):
        self.control_bytes += SIZE_PONG
        self.pongs_recv += 1

    def record_ping_lost(self, n: int = 1):
        self.pings_lost += n

    def record_route_update(self, n_peers: int):
        self.control_bytes += n_peers * SIZE_UPDATE

    def control_overhead_pct(self) -> float:
        total = self.data_bytes + self.control_bytes
        if total == 0:
            return 0.0
        return self.control_bytes / total * 100.0


# ─── Simulation Engine ────────────────────────────────────────────────────────
class Simulation:
    """
    Bộ mô phỏng hướng sự kiện (Event-Driven Simulation).

    - Hàng đợi ưu tiên: min-heap theo sim_time.
    - sim_time tăng dần, không có real-time sleep.
    - Mỗi sự kiện xử lý ngay tại sim_time tương ứng.
    """

    def __init__(self, args):
        self.args    = args
        self.network = Network(
            n_peers       = args.n_peers,
            topology      = args.topology,
            max_neighbors = args.max_neighbors,
            seed          = args.seed,
        )
        self.metrics = Metrics()
        self._events: list[Event] = []
        self._seq    = 0
        self._running = False

        self._ev_counts: dict[str, int] = {}

        # Peers đã từng tham gia mạng (để Poisson join không chọn trùng)
        self._seen_peers: set[str] = set()

        # Peers đã crash — theo dõi convergence
        self._crashed_peers: dict[str, float] = {}

        # Crash đang chờ heartbeat phát hiện
        self._pending_convergence: list[str] = []

        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        self.log_file = LOG_DIR / f"churn_{ts}.csv"

    # ── Logging ───────────────────────────────────────────────────────────────

    def _log_init(self):
        self._logf = open(self.log_file, "w", newline="", encoding="utf-8")
        self._logw = csv.writer(self._logf)
        self._logw.writerow(["sim_time", "event", "src", "dst", "detail"])

    def log(self, sim_time: float, ev_type: str,
            src: str = "", dst: str = "", detail: str = ""):
        self._logw.writerow([f"{sim_time:.2f}", ev_type, src, dst, detail])
        self._logf.flush()
        self._ev_counts[ev_type] = self._ev_counts.get(ev_type, 0) + 1
        sys.stdout.write(
            f"  {sim_time:>7.2f}  {ev_type:<16} {src:<12} {dst:<12} {detail}\n"
        )
        sys.stdout.flush()

    # ── Event Queue ──────────────────────────────────────────────────────────

    def _push(self, sim_time: float, ev_type: str,
              src: str = "", dst: str = "", data: dict = None):
        self._seq += 1
        ev = Event(sim_time=sim_time, seq=self._seq,
                   ev_type=ev_type, src=src, dst=dst,
                   data=data or {})
        heapq.heappush(self._events, ev)

    def _pop(self) -> Event:
        return heapq.heappop(self._events)

    # ── Schedule sự kiện ban đầu ─────────────────────────────────────────────

    def _schedule_initial(self):
        # 1. Init peers đã online trong Network.__init__
        #    Đánh dấu đã tham gia để _on_register bỏ qua
        for pid in self.network.peer_ids[:self.network._init_peers]:
            self._seen_peers.add(pid)

        # 2. Đặt lịch crash/rejoin cycle cho TẤT CẢ peers từ t=0
        for pid in self.network.peer_ids:
            self._schedule_peer_cycle(pid, 0.0)

        # 3. Đặt lịch join Poisson cho peers mới (chưa từng tham gia)
        self._schedule_all_poisson_joins()

        # 4. Message sending: bắt đầu sau 2s
        first_msg = 2.0 + random.uniform(0, self.args.msg_interval)
        if first_msg < self.args.duration:
            self._push(first_msg, EV_SEND_MSG)

        # 5. Heartbeat: bắt đầu sau hb_interval
        self._push(self.args.heartbeat_interval, EV_PING)

        # 6. END
        self._push(self.args.duration, EV_END)

    def _schedule_all_poisson_joins(self):
        """Đặt lịch join Poisson cho tất cả peer chưa từng online."""
        init_count = self.network._init_peers
        new_peers  = self.network.peer_ids[init_count:]

        t = 0.0
        for pid in new_peers:
            t += exp_sample(1.0 / self.args.lambda_join)
            if t < self.args.duration:
                self._push(t, EV_REGISTER)
                self._seen_peers.add(pid)

    def _schedule_peer_cycle(self, pid: str, start_t: float):
        """
        Đặt lịch cycle cho 1 peer: session → crash → downtime → rejoin → ...
        Mỗi peer lặp vô hạn: ONLINE → OFFLINE → ONLINE → ...
        """
        # Phase 1: ONLINE → đặt lịch CRASH
        session_t = exp_sample(self.args.mean_session)
        crash_t   = start_t + session_t
        if crash_t < self.args.duration:
            self._push(crash_t, EV_CRASH, pid,
                       data={"session_time": session_t})

        # Phase 2: OFFLINE → đặt lịch REJOIN
        downtime_t = exp_sample(self.args.mean_downtime)
        rejoin_t   = crash_t + downtime_t
        if rejoin_t < self.args.duration:
            self._push(rejoin_t, EV_REJOIN, pid,
                       data={"downtime": downtime_t})
            # Lên lịch cycle tiếp theo sau rejoin
            self._schedule_peer_cycle(pid, rejoin_t)

    # ── Event Handlers ───────────────────────────────────────────────────────

    def _on_register(self, ev: Event):
        """Peer mới REGISTER với Bootstrap Node."""
        new_pid = None
        for pid in self.network.peer_ids:
            if not self.network.is_online(pid) and pid not in self._seen_peers:
                new_pid = pid
                break

        if new_pid is None:
            self.log(ev.sim_time, EV_REGISTER, "NONE", "BOOTSTRAP",
                     "no more peers available")
            return

        self._seen_peers.add(new_pid)
        self.network.peer_join(new_pid)
        self.metrics.record_register()

        n_online = self.network.bootstrap.count()
        n_neigh  = len(self.network.peers[new_pid].rt.neighbors)
        self.log(ev.sim_time, EV_REGISTER, new_pid, "BOOTSTRAP",
                 f"online={n_online} neighbors={n_neigh}")

        self.log(ev.sim_time, EV_ACK, "BOOTSTRAP", new_pid,
                 f"peers={n_online}")

        # Đặt lịch crash/rejoin cycle cho peer mới
        self._schedule_peer_cycle(new_pid, ev.sim_time)

    def _on_crash(self, ev: Event):
        """Peer CRASH: rời đột ngột, không báo trước. Sẽ rejoin sau downtime."""
        pid = ev.src
        if not self.network.is_online(pid):
            return

        affected = self.network.peer_crash(pid)
        self.metrics.record_crash(pid, ev.sim_time, len(affected))
        self.metrics.record_route_update(len(affected))

        session_t = ev.data.get("session_time", 0.0)
        self.log(ev.sim_time, EV_CRASH, pid, "NETWORK",
                 f"session={session_t:.1f}s affected={len(affected)}")

        # Theo dõi convergence
        self._crashed_peers[pid] = ev.sim_time
        self._pending_convergence.append(pid)

    def _on_rejoin(self, ev: Event):
        """Peer REJOIN: quay lại sau downtime."""
        pid = ev.src
        if self.network.is_online(pid):
            return

        downtime = ev.data.get("downtime", 0.0)
        self.network.peer_join(pid)
        self.metrics.record_register()

        n_online = self.network.bootstrap.count()
        n_neigh  = len(self.network.peers[pid].rt.neighbors)
        self.log(ev.sim_time, EV_REJOIN, pid, "BOOTSTRAP",
                 f"offline={downtime:.1f}s online={n_online} neighbors={n_neigh}")

        self.log(ev.sim_time, EV_ACK, "BOOTSTRAP", pid,
                 f"peers={n_online}")

    def _on_send_msg(self, ev: Event):
        """Gửi tin nhắn chat ngẫu nhiên giữa 2 peer online."""
        online = self.network.online_peers()
        if len(online) < 2:
            self.log(ev.sim_time, EV_SEND_MSG, "", "",
                     f"skip: {len(online)} peer(s) online")
        else:
            sender, receiver = random.sample(online, 2)
            recv_online = self.network.is_online(receiver)
            content = f"[{sender[-5:]}->{receiver[-5:]}]"
            msg_id = self.metrics.record_msg(
                sender, receiver, recv_online, ev.sim_time
            )
            delivered = self.network.deliver_message(
                sender, receiver, msg_id, content
            )
            if delivered and recv_online:
                self.metrics.mark_delivered(msg_id)

            status = "ONLINE" if recv_online else "MAILBOX"
            self.log(ev.sim_time, EV_SEND_MSG, sender, receiver,
                     f"[{status}] delivered={delivered}")

        next_t = ev.sim_time + exp_sample(self.args.msg_interval)
        if next_t < self.args.duration:
            self._push(next_t, EV_SEND_MSG)

    def _on_ping(self, ev: Event):
        """Heartbeat cycle: phát hiện crash và cập nhật bảng định tuyến."""
        hb_t   = ev.sim_time
        online = self.network.online_peers()

        # 1. Mỗi peer online gửi PING đến mỗi láng giềng
        for pid in online:
            peer = self.network.peers.get(pid)
            if not peer or not peer.online:
                continue
            for nid in list(peer.rt.neighbors):
                self.metrics.record_ping()
                if self.network.is_online(nid):
                    self.metrics.record_pong()
                else:
                    self.metrics.record_ping_lost(1)

        # 2. Kiểm tra convergence: peer crashed có còn trong bảng ai không?
        still_pending = []
        for cpid in self._pending_convergence:
            if cpid not in self._crashed_peers:
                continue
            found = False
            for opid in online:
                op = self.network.peers.get(opid)
                if op and cpid in op.rt.neighbors:
                    found = True
                    break
            if not found:
                conv_t = self._crashed_peers.get(cpid, hb_t)
                self.metrics.mark_converged(cpid, hb_t)
                self.log(hb_t, EV_CONVERGENCE, "NETWORK", cpid,
                         f"ct={hb_t - conv_t:.2f}s")
                del self._crashed_peers[cpid]
            else:
                still_pending.append(cpid)

        self._pending_convergence = still_pending

        n_total_neigh = sum(
            len(self.network.peers[pid].rt.neighbors)
            for pid in online
        )
        self.log(hb_t, EV_PING, "ALL", "NEIGHBORS",
                 f"online={len(online)} total_neigh={n_total_neigh}")

        next_hb = hb_t + self.args.heartbeat_interval
        if next_hb < self.args.duration:
            self._push(next_hb, EV_PING)

    # ── Main Loop ────────────────────────────────────────────────────────────

    def run(self):
        self._running = True
        self._log_init()
        self._schedule_initial()

        sys.stdout.write("\n")
        sys.stdout.write("=" * 70 + "\n")
        sys.stdout.write("  P2PChat Event-Driven Churn Simulation\n")
        sys.stdout.write("=" * 70 + "\n")
        sys.stdout.write(f"  Peers            : {self.args.n_peers}\n")
        sys.stdout.write(f"  Init peers       : {self.network._init_peers}\n")
        sys.stdout.write(f"  Topology         : {self.args.topology}\n")
        sys.stdout.write(f"  Duration         : {self.args.duration}s\n")
        sys.stdout.write(f"  Mean session     : {self.args.mean_session}s  (Exponential)\n")
        sys.stdout.write(f"  Mean downtime    : {self.args.mean_downtime}s  (Exponential)\n")
        sys.stdout.write(f"  Mean msg gap     : {self.args.msg_interval}s  (Exponential)\n")
        sys.stdout.write(f"  HB interval      : {self.args.heartbeat_interval}s\n")
        sys.stdout.write(f"  Lambda join      : {self.args.lambda_join} peer/s\n")
        sys.stdout.write(f"  Max neighbors    : {self.args.max_neighbors}\n")
        sys.stdout.write("=" * 70 + "\n")
        sys.stdout.write(f"  {'SIM_T':>7}  {'EVENT':<16} {'SRC':<12} {'DST':<12} DETAIL\n")
        sys.stdout.write("-" * 70 + "\n")
        sys.stdout.flush()

        event_count = 0
        while self._events and self._running:
            ev = self._pop()
            self.network.sim_time = ev.sim_time
            event_count += 1

            if ev.ev_type == EV_END:
                self.log(ev.sim_time, EV_END, "", "", "simulation complete")
                break

            if ev.ev_type == EV_REGISTER:
                self._on_register(ev)
            elif ev.ev_type == EV_CRASH:
                self._on_crash(ev)
            elif ev.ev_type == EV_REJOIN:
                self._on_rejoin(ev)
            elif ev.ev_type == EV_SEND_MSG:
                self._on_send_msg(ev)
            elif ev.ev_type == EV_PING:
                self._on_ping(ev)

        self._logf.close()
        self._print_results(event_count)

    def _print_results(self, total_events: int):
        m        = self.metrics
        msgs     = m.messages
        total    = len(msgs)
        delivered = sum(1 for x in msgs.values() if x["delivered"])
        direct    = [x for x in msgs.values() if x["online_when_sent"]]
        offline   = [x for x in msgs.values() if not x["online_when_sent"]]
        ct_recs   = [r for r in m.convergence if r["convergence_s"] is not None]

        sys.stdout.write("\n" + "=" * 70 + "\n")
        sys.stdout.write("  KET QUA MO PHONG CHURN\n")
        sys.stdout.write("=" * 70 + "\n\n")

        # 1. Thống kê sự kiện
        sys.stdout.write("  [1] THONG KE SU KIEN\n")
        sys.stdout.write(f"  Tong su kien xu ly : {total_events}\n")
        sys.stdout.write(f"  {'Loai':<20} {'So lan':>10}\n")
        sys.stdout.write(f"  {'-'*32}\n")
        for ev_type in sorted(self._ev_counts):
            sys.stdout.write(f"  {ev_type:<20} {self._ev_counts[ev_type]:>10}\n")
        sys.stdout.write(f"  {'-'*32}\n")
        sys.stdout.write(f"  {'Online dau':<20} {self.network._init_peers:>10}\n")
        sys.stdout.write(f"  {'Online cuoi':<20} {len(self.network.online_peers()):>10}\n")

        # 2. MDR
        sys.stdout.write("\n  [2] MESSAGE DELIVERY RATIO (MDR)\n")
        sys.stdout.write(f"  Tong tin gui       : {total}\n")
        sys.stdout.write(f"  Da delivered       : {delivered}\n")
        sys.stdout.write(f"  MDR tong            : {m.mdr():.2f}%\n")
        if direct:
            d = sum(1 for x in direct if x["delivered"])
            sys.stdout.write(f"  MDR direct          : {d/len(direct)*100:.2f}%  "
                              f"({len(direct)} tin, receiver online)\n")
        if offline:
            d = sum(1 for x in offline if x["delivered"])
            sys.stdout.write(f"  MDR mailbox         : {d/len(offline)*100:.2f}%  "
                              f"({len(offline)} tin, receiver offline)\n")

        # 3. Convergence Time
        sys.stdout.write("\n  [3] CONVERGENCE TIME (CT)\n")
        if ct_recs:
            times = [r["convergence_s"] for r in ct_recs]
            sys.stdout.write(f"  So crash phat hien   : {len(ct_recs)}\n")
            sys.stdout.write(f"  Trung binh           : {sum(times)/len(times):.2f}s\n")
            sys.stdout.write(f"  Min                  : {min(times):.2f}s\n")
            sys.stdout.write(f"  Max                  : {max(times):.2f}s\n")
            sys.stdout.write(f"  Ly thuyet (~HB*3)    : {self.args.heartbeat_interval*3:.0f}s\n")
        else:
            sys.stdout.write("  Khong co crash nao duoc phat hien.\n")

        # 4. Control Overhead
        sys.stdout.write("\n  [4] CONTROL OVERHEAD (CO)\n")
        sys.stdout.write(f"  Data bytes          : {m.data_bytes:,}\n")
        sys.stdout.write(f"  Control bytes       : {m.control_bytes:,}\n")
        co = m.control_overhead_pct()
        sys.stdout.write(f"  Control Overhead     : {co:.2f}%\n")
        sys.stdout.write(f"  Pings sent          : {m.pings_sent}\n")
        sys.stdout.write(f"  Pings lost          : {m.pings_lost}\n")

        # 5. Kết luận
        avg_ct = m.avg_convergence()
        sys.stdout.write("\n  [5] KET LUAN\n")
        sys.stdout.write(f"  MDR  = {m.mdr():>6.2f}%  | "
                          f"{'[OK]' if m.mdr() >= 80 else '[WARN] Low delivery'}\n")
        sys.stdout.write(f"  CT   = {avg_ct:>6.2f}s  | "
                          f"{'[OK]' if avg_ct <= self.args.heartbeat_interval * 4 else '[WARN] Slow convergence'}\n")
        sys.stdout.write(f"  CO   = {co:>6.2f}%  | "
                          f"{'[OK]' if co <= 80 else '[WARN] High overhead'}\n")

        sys.stdout.write(f"\n  Log : {self.log_file}\n")
        sys.stdout.write("=" * 70 + "\n")
        sys.stdout.flush()


# ─── Entry Point ─────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(
        description="Event-Driven Churn Simulation cho P2PChat",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--n-peers",        type=int,    default=DEFAULT_N_PEERS,
                        help=f"So peer (mac dinh: {DEFAULT_N_PEERS})")
    parser.add_argument("--duration",        type=float,  default=DEFAULT_DURATION,
                        help=f"Thoi gian mo phong giay (mac dinh: {DEFAULT_DURATION})")
    parser.add_argument("--mean-session",    type=float,  default=DEFAULT_MEAN_SESSION,
                        help=f"Session time TB giay (mac dinh: {DEFAULT_MEAN_SESSION})")
    parser.add_argument("--mean-downtime",   type=float,  default=DEFAULT_MEAN_DOWNTIME,
                        help=f"Downtime TB giay (mac dinh: {DEFAULT_MEAN_DOWNTIME})")
    parser.add_argument("--msg-interval",    type=float,  default=DEFAULT_MSG_INTERVAL,
                        help=f"Khoang cach TB giua 2 tin giay (mac dinh: {DEFAULT_MSG_INTERVAL})")
    parser.add_argument("--heartbeat-interval", type=float, default=DEFAULT_HB_INTERVAL,
                        help=f"Chu ky heartbeat giay (mac dinh: {DEFAULT_HB_INTERVAL})")
    parser.add_argument("--lambda-join",      type=float,  default=DEFAULT_LAMBDA_JOIN,
                        help=f"Toc do join peer/s (mac dinh: {DEFAULT_LAMBDA_JOIN})")
    parser.add_argument("--topology",         type=str,    default=DEFAULT_TOPOLOGY,
                        choices=["random", "ring", "mesh"],
                        help=f"Topology (mac dinh: {DEFAULT_TOPOLOGY})")
    parser.add_argument("--max-neighbors",   type=int,    default=DEFAULT_MAX_NEIGHBORS,
                        help=f"So lang cung toi da (mac dinh: {DEFAULT_MAX_NEIGHBORS})")
    parser.add_argument("--seed",             type=int,    default=DEFAULT_SEED,
                        help=f"Random seed (mac dinh: {DEFAULT_SEED})")

    args = parser.parse_args()
    sim = Simulation(args)
    try:
        sim.run()
    except KeyboardInterrupt:
        sim._logf.close()
        sim._print_results(0)


if __name__ == "__main__":
    main()
