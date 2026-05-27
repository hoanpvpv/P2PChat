#!/usr/bin/env python3
"""
churn-sim.py — Mô phỏng Churn cho P2PChat
==========================================
Tự động start/stop Docker container peer theo thời gian và ghi log.

Cach dung:
    python3 churn-sim.py                        # Random JOIN/CRASH, 180s
    python3 churn-sim.py --mode random           # Chi JOIN/CRASH, interval 15s
    python3 churn-sim.py --mode wave             # Sóng JOIN/CRASH
    python3 churn-sim.py --mode scenario        # Kich ban co dinh
    python3 churn-sim.py --duration 120          # Chay 120 giay
    python3 churn-sim.py --interval 10           # Moi 10 giay kiem tra 1 lan

Yêu cầu: Docker đang chạy, đã chạy ./run.sh start trước.
"""

import argparse
import csv
import io
import json
import os
import random
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

if sys.platform == "win32":
    import codecs
    # Force UTF-8 output on Windows to avoid cp1252 encoding errors with Vietnamese
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# ─────────────────────────────────────────────────────
# Cấu hình mặc định — khớp với run.sh start
# ─────────────────────────────────────────────────────
DEFAULT_PEERS = [
    {"name": "alice",  "web_port": 33143},
    {"name": "bob",    "web_port": 33144},
    {"name": "duc",    "web_port": 12345},
    {"name": "hoang",  "web_port": 12346},
    {"name": "hoan",   "web_port": 12349},
]

LOG_DIR  = Path(__file__).parent / "churn-logs"
LOG_FILE = LOG_DIR / f"churn_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"


# ─────────────────────────────────────────────────────
# Logger
# ─────────────────────────────────────────────────────
class ChurnLogger:
    """Ghi log sự kiện churn ra CSV và stdout."""

    HEADER = ["timestamp", "peer", "event", "method", "elapsed_ms", "note"]

    def __init__(self, log_file: Path):
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        self._file = open(log_file, "w", newline="", encoding="utf-8")
        self._writer = csv.writer(self._file)
        self._writer.writerow(self.HEADER)
        self._file.flush()
        self._start_time = time.time()
        print(f"[LOG] Ghi log vào: {log_file}")

    def log(self, peer: str, event: str, method: str = "", elapsed_ms: int = 0, note: str = ""):
        ts = datetime.now().isoformat(timespec="milliseconds")
        row = [ts, peer, event, method, elapsed_ms, note]
        self._writer.writerow(row)
        self._file.flush()
        elapsed_total = int((time.time() - self._start_time) * 1000)
        print(f"  [{elapsed_total:6d}ms] {peer:10s}  {event:20s}  {method:9s}  {note}")

    def summary(self, stats: dict):
        """In bảng tổng kết cuối phiên."""
        print("\n" + "=" * 60)
        print("CHURN SIMULATION — KET QUA")
        print("=" * 60)
        for peer, s in stats.items():
            print(f"  {peer:10s}  join={s['join']:3d}  crash={s['crash']:3d}")
        print(f"\nLog day du: {LOG_FILE}")

    def close(self):
        self._file.close()


# ─────────────────────────────────────────────────────
# Docker helpers
# ─────────────────────────────────────────────────────
def _run(args: list, check=False) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=True, check=check)


def is_running(peer_name: str) -> bool:
    """Kiểm tra container peer-<name> có đang chạy không."""
    result = _run([
        "docker", "inspect", "-f", "{{.State.Running}}",
        f"peer-{peer_name}"
    ])
    return result.returncode == 0 and result.stdout.strip() == "true"


def container_exists(peer_name: str) -> bool:
    """Kiểm tra container có tồn tại không (kể cả đã dừng)."""
    result = _run(["docker", "inspect", f"peer-{peer_name}"])
    return result.returncode == 0


def graceful_leave(peer_name: str, logger: ChurnLogger) -> bool:
    """
    Dừng peer bằng docker stop — container nhận SIGTERM, 
    PeerNode.shutdown() được gọi, gửi PEER_LEAVE tới bootstrap.
    """
    t0 = time.time()
    result = _run(["docker", "stop", "--time", "5", f"peer-{peer_name}"])
    elapsed = int((time.time() - t0) * 1000)
    ok = result.returncode == 0
    logger.log(peer_name, "LEAVE", "graceful", elapsed,
               "ok" if ok else result.stderr.strip()[:80])
    return ok


def ungraceful_crash(peer_name: str, logger: ChurnLogger) -> bool:
    """
    Kill container bằng SIGKILL — mô phỏng crash đột ngột.
    Bootstrap phát hiện sau khi heartbeat timeout (~3 lần miss).
    """
    t0 = time.time()
    result = _run(["docker", "kill", f"peer-{peer_name}"])
    elapsed = int((time.time() - t0) * 1000)
    ok = result.returncode == 0
    logger.log(peer_name, "CRASH", "kill", elapsed,
               "ok" if ok else result.stderr.strip()[:80])
    return ok


def join_network(peer_name: str, logger: ChurnLogger) -> bool:
    """
    Khởi động lại container đã dừng — peer tự đăng ký lại với bootstrap.
    Container giữ nguyên data volume (lịch sử chat, keypair, v.v.).
    """
    if not container_exists(peer_name):
        logger.log(peer_name, "JOIN_SKIP", "", 0, "container không tồn tại")
        return False
    t0 = time.time()
    result = _run(["docker", "start", f"peer-{peer_name}"])
    elapsed = int((time.time() - t0) * 1000)
    ok = result.returncode == 0
    logger.log(peer_name, "JOIN", "start", elapsed,
               "ok" if ok else result.stderr.strip()[:80])
    return ok


def snapshot_online(peers: list) -> dict:
    """Trả về dict {name: bool} trạng thái hiện tại của tất cả peer."""
    return {p["name"]: is_running(p["name"]) for p in peers}


# ─────────────────────────────────────────────────────
# Các chế độ mô phỏng
# ─────────────────────────────────────────────────────

def mode_random(peers: list, duration: int, interval: int,
                logger: ChurnLogger,
                p_join: float = 0.5):
    """
    Che do Random: Tai moi chu ky, peer offline co xac suat JOIN lai.

    Tham so:
        p_join:   Xac suat peer dang OFFLINE se tham gia trong chu ky nay
        interval: Khoang cach giua moi chu ky (giay)
    """
    print(f"\n[MODE] RANDOM  duration={duration}s  interval={interval}s")
    print(f"       p_join={p_join}")
    print("-" * 60)

    stats = {p["name"]: {"join": 0, "crash": 0} for p in peers}
    deadline = time.time() + duration
    tick = 0

    while time.time() < deadline:
        tick += 1
        remaining = int(deadline - time.time())
        print(f"\n-- Tick {tick:3d} | con {remaining:3d}s --")

        online_map = snapshot_online(peers)
        for p in peers:
            name = p["name"]
            if not online_map[name]:
                if random.random() < p_join:
                    if join_network(name, logger):
                        stats[name]["join"] += 1
                        time.sleep(2)

        time.sleep(interval)

    logger.summary(stats)


def mode_wave(peers: list, duration: int, logger: ChurnLogger):
    """
    Che do Wave: Mo phong 3 dot JOIN/CRASH.

    Dot 1 (t=0):      Tat ca peer online (baseline)
    Dot 2 (t=25%):    50% peer crash dot ngot
    Dot 3 (t=50%):    Cac peer crash JOIN lai
    Dot 4 (t=75%):    Mot so peer crash nua de tang churn
    """
    print(f"\n[MODE] WAVE  duration={duration}s")
    print("-" * 60)

    stats = {p["name"]: {"join": 0, "crash": 0} for p in peers}
    start = time.time()

    def wait_until(fraction, label):
        target = start + duration * fraction
        remaining = target - time.time()
        if remaining > 0:
            print(f"\n  ⏳ Cho {remaining:.0f}s den dot '{label}'...")
            time.sleep(remaining)
        print(f"\n-- DOT: {label} --")

    # Dot 1: Baseline — tat ca online
    print("\n-- DOT 1: Baseline — dam bao tat ca peer online --")
    for p in peers:
        if not is_running(p["name"]):
            if join_network(p["name"], logger):
                stats[p["name"]]["join"] += 1
    time.sleep(5)

    # Dot 2: Crash wave — nua so peer crash dot ngot
    wait_until(0.25, "CRASH WAVE — 50% peer crash")
    crash_targets = random.sample(peers, k=max(1, len(peers) // 2))
    for p in crash_targets:
        ungraceful_crash(p["name"], logger)
        stats[p["name"]]["crash"] += 1
    print(f"  → Da crash: {[p['name'] for p in crash_targets]}")

    # Dot 3: Recovery wave — cac peer crash quay lai
    wait_until(0.50, "RECOVERY WAVE — peer crash tham gia lai")
    for p in crash_targets:
        if join_network(p["name"], logger):
            stats[p["name"]]["join"] += 1
    time.sleep(3)
    print(f"  → Da join lai: {[p['name'] for p in crash_targets]}")

    # Dot 4: Dot crash thu hai — tang churn
    wait_until(0.75, "CRASH WAVE 2 — them peer crash")
    crash2_targets = random.sample(
        [p for p in peers if p not in crash_targets],
        k=max(1, len(peers) // 3)
    )
    for p in crash2_targets:
        ungraceful_crash(p["name"], logger)
        stats[p["name"]]["crash"] += 1
    print(f"  → Da crash dot 2: {[p['name'] for p in crash2_targets]}")

    # Cho het thoi gian con lai
    remaining = duration - (time.time() - start)
    if remaining > 0:
        print(f"\n  ⏳ Quan sat them {remaining:.0f}s...")
        time.sleep(remaining)

    logger.summary(stats)


def mode_scenario(peers: list, logger: ChurnLogger):
    """
    Che do Scenario: Kich ban co dinh chi JOIN/CRASH.

    Thu tu su kien:
    - B1: Baseline — tat ca online
    - B2: bob CRASH ungraceful
    - B3: alice gui tin cho bob (store-and-forward)
    - B4: duc CRASH ungraceful
    - B5: alice BROADCAST khi co peer offline
    - B6: bob JOIN lai — pull mailbox
    - B7: duc JOIN lai
    - B8: Snapshot cuoi
    """
    print("\n[MODE] SCENARIO — Kich ban JOIN/CRASH")
    print("-" * 60)

    stats = {p["name"]: {"join": 0, "crash": 0} for p in peers}

    def step(t: int, msg: str):
        print(f"\n  [t={t:3d}s] {msg}")

    def wait(seconds: int):
        time.sleep(seconds)

    t = 0

    # Buoc 1: Tat ca online
    step(t, "Baseline: Dam bao tat ca peer online")
    for p in peers:
        if not is_running(p["name"]):
            if join_network(p["name"], logger):
                stats[p["name"]]["join"] += 1
    wait(5); t += 5

    # Buoc 2: bob crash dot ngot
    step(t, "bob CRASH ungraceful (mo phong mat dien)")
    ungraceful_crash("bob", logger)
    stats["bob"]["crash"] += 1
    wait(5); t += 5

    # Buoc 3: alice gui tin trong khi bob offline
    step(t, "→ alice gui tin cho bob (ky vong: STORED_MAILBOX)")
    _send_message_via_api(33143, "bob", "Chao bob, ban co nhan duoc khong?")
    wait(5); t += 5

    # Buoc 4: duc crash
    step(t, "duc CRASH ungraceful")
    ungraceful_crash("duc", logger)
    stats["duc"]["crash"] += 1
    wait(5); t += 5

    # Buoc 5: alice broadcast
    step(t, "→ alice BROADCAST (ky vong: chi den peer dang online)")
    _send_broadcast_via_api(33143, "Broadcast test khi co peer offline")
    wait(5); t += 5

    # Buoc 6: bob join lai — keo tin tu mailbox
    step(t, "bob JOIN lai → se pull tin tu mailbox")
    join_network("bob", logger)
    stats["bob"]["join"] += 1
    wait(5); t += 5

    # Buoc 7: duc join lai
    step(t, "duc JOIN lai")
    join_network("duc", logger)
    stats["duc"]["join"] += 1
    wait(5); t += 5

    # Buoc 8: Snapshot cuoi
    step(t, "Snapshot trang thai cuoi kich ban")
    final = snapshot_online(peers)
    for name, online in final.items():
        status = "ONLINE" if online else "OFFLINE"
        logger.log(name, f"FINAL_STATE_{status}", "snapshot", 0, "")

    logger.summary(stats)


def _send_message_via_api(web_port: int, receiver: str, content: str):
    """Gửi direct message qua REST API của peer-node."""
    try:
        import urllib.request, urllib.error
        payload = json.dumps({"receiver": receiver, "content": content}).encode()
        req = urllib.request.Request(
            f"http://localhost:{web_port}/api/msg",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=3) as resp:
            print(f"    API msg → {receiver}: HTTP {resp.status}")
    except Exception as e:
        print(f"    API msg → {receiver}: FAILED ({e})")


def _send_broadcast_via_api(web_port: int, content: str):
    """Gửi broadcast qua REST API của peer-node."""
    try:
        import urllib.request, urllib.error
        payload = json.dumps({"content": content}).encode()
        req = urllib.request.Request(
            f"http://localhost:{web_port}/api/broadcast",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=3) as resp:
            print(f"    API broadcast: HTTP {resp.status}")
    except Exception as e:
        print(f"    API broadcast: FAILED ({e})")


# ─────────────────────────────────────────────────────
# Tiện ích: in trạng thái hiện tại
# ─────────────────────────────────────────────────────
def print_status(peers: list):
    print("\n  Trạng thái hiện tại:")
    print("  " + "-" * 35)
    for p in peers:
        name = p["name"]
        status = "🟢 ONLINE " if is_running(name) else "🔴 OFFLINE"
        print(f"  {status}  {name:10s}  web=localhost:{p['web_port']}")
    print()


# ─────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(
        description="Churn Simulation cho P2PChat",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Vi du:
  python3 churn-sim.py                           # Random, 180s, interval 15s
  python3 churn-sim.py --mode wave --duration 120
  python3 churn-sim.py --mode scenario
  python3 churn-sim.py --mode random --duration 300 --interval 20
  python3 churn-sim.py --status                  # Chi xem trang thai, khong chay
        """
    )
    parser.add_argument("--mode", choices=["random", "wave", "scenario"],
                        default="random", help="Chế độ mô phỏng (mặc định: random)")
    parser.add_argument("--duration", type=int, default=180,
                        help="Tong thoi gian chay (giay, mac dinh: 180)")
    parser.add_argument("--interval", type=int, default=15,
                        help="Chu ky kiem tra cho che do random (giay, mac dinh: 15)")
    parser.add_argument("--p-join", type=float, default=0.5,
                        help="Xac suat peer offline JOIN lai moi chu ky (random mode, mac dinh: 0.5)")
    parser.add_argument("--status", action="store_true",
                        help="Chi in trang thai peer hien tai roi thoat")
    args = parser.parse_args()

    # Kiểm tra docker có sẵn không
    if _run(["docker", "info"]).returncode != 0:
        print("[ERROR] Docker không chạy hoặc không tìm thấy. Kiểm tra lại.")
        sys.exit(1)

    peers = DEFAULT_PEERS

    if args.status:
        print_status(peers)
        return

    print("=" * 60)
    print("P2PChat Churn Simulation")
    print("=" * 60)
    print_status(peers)

    logger = ChurnLogger(LOG_FILE)

    try:
        if args.mode == "random":
            mode_random(peers, args.duration, args.interval,
                        logger, args.p_join)
        elif args.mode == "wave":
            mode_wave(peers, args.duration, logger)
        elif args.mode == "scenario":
            mode_scenario(peers, logger)
    except KeyboardInterrupt:
        print("\n\n[INFO] Dừng sớm bằng Ctrl+C.")
    finally:
        logger.close()
        print("\nTrạng thái sau mô phỏng:")
        print_status(peers)


if __name__ == "__main__":
    main()
