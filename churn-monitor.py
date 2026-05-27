#!/usr/bin/env python3
"""
churn-monitor.py — Terminal dashboard quan sát churn simulation real-time
=========================================================================
Chạy song song với churn-sim.py để quan sát hệ thống đang thay đổi.

Cách dùng:
    python3 churn-monitor.py                  # Mặc định: poll mỗi 2 giây
    python3 churn-monitor.py --interval 1     # Poll mỗi 1 giây
    python3 churn-monitor.py --no-events      # Ẩn event log
    python3 churn-monitor.py --no-docker      # Ẩn docker stats

Yêu cầu: Python 3 standard library, Docker, Bootstrap đang chạy trên port 9001.
"""

import argparse
import io
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

if sys.platform == "win32":
    import codecs
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# ─── Cấu hình ──────────────────────────────────────────────────────────────
BOOTSTRAP_API  = "http://localhost:9001"
LOG_DIR        = Path(__file__).parent / "churn-logs"
DEFAULT_PEERS  = ["alice", "bob", "duc", "hoang", "hoan"]

# ANSI color codes
class C:
    RESET   = "\033[0m"
    BOLD    = "\033[1m"
    DIM     = "\033[2m"
    GREEN   = "\033[32m"
    RED     = "\033[31m"
    YELLOW  = "\033[33m"
    CYAN    = "\033[36m"
    MAGENTA = "\033[35m"
    WHITE   = "\033[97m"
    BG_DARK = "\033[48;5;234m"
    ORANGE  = "\033[38;5;208m"

def colored(text, *codes):
    return "".join(codes) + str(text) + C.RESET

def clear_screen():
    os.system("cls" if os.name == "nt" else "clear")

# ─── API helpers ────────────────────────────────────────────────────────────

def fetch_json(url: str, timeout: float = 2.0):
    """Fetch JSON từ URL, trả None nếu lỗi."""
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except Exception:
        return None


def fetch_bootstrap_status():
    return fetch_json(f"{BOOTSTRAP_API}/api/status")


def fetch_bootstrap_peers():
    return fetch_json(f"{BOOTSTRAP_API}/api/peers") or []


def fetch_bootstrap_events():
    return fetch_json(f"{BOOTSTRAP_API}/api/events") or []


# ─── Docker helpers ─────────────────────────────────────────────────────────

def _run(args):
    return subprocess.run(args, capture_output=True, text=True)


def docker_container_state(peer_name: str) -> str:
    """Trả về: running / exited / paused / không tồn tại."""
    r = _run(["docker", "inspect", "-f", "{{.State.Status}}", f"peer-{peer_name}"])
    if r.returncode != 0:
        return "missing"
    return r.stdout.strip()


def docker_all_states(peer_names: list) -> dict:
    """Lấy trạng thái tất cả container một lần — nhanh hơn gọi riêng lẻ."""
    states = {}
    r = _run(["docker", "ps", "-a", "--format", "{{.Names}}\t{{.Status}}"])
    running_map = {}
    for line in r.stdout.splitlines():
        parts = line.split("\t", 1)
        if len(parts) == 2:
            running_map[parts[0]] = parts[1]
    for name in peer_names:
        key = f"peer-{name}"
        if key in running_map:
            status_raw = running_map[key].lower()
            if "up" in status_raw:
                states[name] = "running"
            elif "exited" in status_raw:
                states[name] = "exited"
            elif "paused" in status_raw:
                states[name] = "paused"
            else:
                states[name] = "other"
        else:
            states[name] = "missing"
    return states


def docker_stats_brief() -> list:
    """Lấy CPU và RAM của container đang chạy."""
    r = _run([
        "docker", "stats", "--no-stream", "--format",
        "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"
    ])
    rows = []
    for line in r.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0].startswith("peer-"):
            rows.append({
                "name":   parts[0].removeprefix("peer-"),
                "cpu":    parts[1],
                "mem":    parts[2].split("/")[0].strip(),
            })
    return rows


# ─── Log file helpers ────────────────────────────────────────────────────────

def latest_log_file() -> Path | None:
    """Tìm file log CSV mới nhất trong churn-logs/."""
    if not LOG_DIR.exists():
        return None
    csvs = sorted(LOG_DIR.glob("churn_*.csv"), key=lambda f: f.stat().st_mtime, reverse=True)
    return csvs[0] if csvs else None


def tail_log(log_file: Path, n: int = 8) -> list:
    """Đọc n dòng cuối của CSV log, bỏ header."""
    try:
        lines = log_file.read_text(encoding="utf-8").splitlines()
        data_lines = [l for l in lines if l and not l.startswith("timestamp")]
        return data_lines[-n:]
    except Exception:
        return []


# ─── Render helpers ──────────────────────────────────────────────────────────

def status_badge(docker_state: str, bootstrap_online: bool) -> str:
    """
    Ghép 2 nguồn thông tin thành 1 badge trạng thái:
    - docker_state: running / exited / missing
    - bootstrap_online: True/False (từ heartbeat)
    """
    if docker_state == "running" and bootstrap_online:
        return colored("● ONLINE   ", C.GREEN, C.BOLD)
    elif docker_state == "running" and not bootstrap_online:
        return colored("◑ STARTING ", C.YELLOW, C.BOLD)    # Container chạy nhưng chưa đăng ký
    elif docker_state == "exited":
        return colored("○ EXITED   ", C.RED, C.BOLD)        # Graceful leave
    elif docker_state == "missing":
        return colored("✗ MISSING  ", C.RED, C.DIM)
    else:
        return colored("? UNKNOWN  ", C.DIM)


def event_color(event_type: str) -> str:
    if "LEAVE" in event_type or "OFFLINE" in event_type or "CRASH" in event_type:
        return C.RED
    elif "JOIN" in event_type or "ONLINE" in event_type or "REGISTER" in event_type:
        return C.GREEN
    elif "HEARTBEAT" in event_type:
        return C.DIM
    else:
        return C.CYAN


def fmt_ts(iso_str: str) -> str:
    """Rút gọn ISO timestamp → HH:MM:SS."""
    try:
        return iso_str[11:19]
    except Exception:
        return iso_str[:19]


def render_header(tick: int, bootstrap_up: bool):
    now = datetime.now().strftime("%H:%M:%S")
    bs = colored("● UP", C.GREEN) if bootstrap_up else colored("✗ DOWN", C.RED)
    print(colored("═" * 70, C.CYAN))
    print(colored(f"  P2PChat Churn Monitor", C.BOLD, C.WHITE) +
          colored(f"  tick={tick}  {now}", C.DIM) +
          f"  Bootstrap: {bs}")
    print(colored("═" * 70, C.CYAN))


def render_peer_table(peer_names: list, docker_states: dict,
                      bootstrap_peers: list, docker_resources: list):
    """Bảng trạng thái peer tổng hợp từ Docker + Bootstrap."""
    # Index bootstrap peers by username
    bs_map = {p.get("username", ""): p for p in bootstrap_peers}
    # Index docker resources by name
    res_map = {r["name"]: r for r in docker_resources}

    print(colored(f"\n  {'PEER':<12} {'TRẠNG THÁI':<14} {'BOOTSTRAP':<10} "
                  f"{'PORT':<8} {'CPU':<8} {'RAM':<12}", C.BOLD))
    print("  " + "─" * 66)

    for name in peer_names:
        d_state  = docker_states.get(name, "missing")
        bs_info  = bs_map.get(name)
        bs_online = bs_info.get("online", False) if bs_info else False
        badge    = status_badge(d_state, bs_online)

        bs_txt   = colored("✓ registered", C.GREEN) if bs_info else colored("─ unknown", C.DIM)
        port_txt = str(bs_info.get("port", "─")) if bs_info else "─"

        res      = res_map.get(name)
        cpu_txt  = res["cpu"] if res else "─"
        mem_txt  = res["mem"] if res else "─"

        print(f"  {colored(name, C.BOLD):<20} {badge} {bs_txt:<22} "
              f"{colored(port_txt, C.DIM):<16} {colored(cpu_txt, C.DIM):<16} "
              f"{colored(mem_txt, C.DIM)}")


def render_bootstrap_status(status: dict):
    """Tóm tắt số liệu từ bootstrap /api/status."""
    if not status:
        print(colored("\n  [Bootstrap không phản hồi]", C.RED, C.DIM))
        return
    online  = status.get("onlinePeerCount", "?")
    known   = status.get("knownPeerCount", "?")
    offline = status.get("offlineMessageCount", "?")
    print(colored(f"\n  Bootstrap stats: ", C.BOLD) +
          colored(f"{online} online", C.GREEN) +
          colored(f" / {known} known", C.DIM) +
          colored(f" / {offline} offline msgs", C.YELLOW))


def render_bootstrap_events(events: list, n: int = 10):
    """Hiển thị n sự kiện mới nhất từ Bootstrap event log."""
    print(colored("\n  Bootstrap Event Log (mới nhất):", C.BOLD))
    print("  " + "─" * 66)
    if not events:
        print(colored("  (không có sự kiện)", C.DIM))
        return
    for ev in events[:n]:
        ts      = fmt_ts(ev.get("timestamp", ""))
        level   = ev.get("level", "")
        etype   = ev.get("type", "")
        peer    = ev.get("peer", "")
        detail  = ev.get("detail", "")

        level_badge = colored(f"[{level}]", C.YELLOW if level == "WARN" else
                              (C.RED if level == "ERROR" else C.DIM))
        etype_txt   = colored(f"{etype:<20}", event_color(etype))
        peer_txt    = colored(f"{peer:<10}", C.MAGENTA)

        print(f"  {colored(ts, C.DIM)}  {level_badge:<16} {etype_txt} "
              f"{peer_txt}  {colored(detail, C.DIM)}")


def render_churn_log(log_file: Path | None, n: int = 8):
    """Hiển thị n dòng log cuối từ churn-sim.py CSV."""
    print(colored("\n  Churn Simulation Log (mới nhất):", C.BOLD))
    print("  " + "─" * 66)
    if not log_file:
        print(colored("  (chưa có file log — chạy churn-sim.py trước)", C.DIM))
        return
    rows = tail_log(log_file, n)
    if not rows:
        print(colored("  (log trống)", C.DIM))
        return
    for row in rows:
        parts = row.split(",")
        if len(parts) < 5:
            continue
        ts, peer, event, method, elapsed = parts[0], parts[1], parts[2], parts[3], parts[4]
        note = parts[5] if len(parts) > 5 else ""

        ecol = C.RED if "CRASH" in event or "LEAVE" in event else \
               (C.GREEN if "JOIN" in event else C.CYAN)

        print(f"  {colored(fmt_ts(ts), C.DIM)}  {colored(peer, C.MAGENTA):<18} "
              f"{colored(event, ecol, C.BOLD):<26} "
              f"{colored(method, C.DIM):<12}  {colored(note, C.DIM)}")
    print(colored(f"  ← {log_file.name}", C.DIM))


def render_footer(interval: float):
    print(colored("\n  " + "─" * 66, C.DIM))
    print(colored(f"  Refresh mỗi {interval}s  |  Ctrl+C để thoát  |  "
                  f"Nguồn: Bootstrap :9001  +  Docker  +  churn-logs/", C.DIM))


# ─── Main loop ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Churn Monitor cho P2PChat")
    parser.add_argument("--interval", type=float, default=2.0,
                        help="Chu kỳ refresh (giây, mặc định: 2)")
    parser.add_argument("--no-events",  action="store_true", help="Ẩn Bootstrap event log")
    parser.add_argument("--no-docker",  action="store_true", help="Ẩn CPU/RAM docker stats")
    parser.add_argument("--no-churn-log", action="store_true", help="Ẩn churn-sim CSV log")
    parser.add_argument("--peers", nargs="+", default=DEFAULT_PEERS,
                        help="Tên peer cần theo dõi")
    args = parser.parse_args()

    # Kiểm tra ANSI support (Windows cần enable)
    if os.name == "nt":
        os.system("color")

    tick = 0
    print(colored("Đang kết nối...", C.DIM))

    try:
        while True:
            tick += 1

            # ── Thu thập dữ liệu song song ──────────────────────────────
            bs_status  = fetch_bootstrap_status()
            bs_peers   = fetch_bootstrap_peers()
            bs_events  = fetch_bootstrap_events() if not args.no_events else []
            d_states   = docker_all_states(args.peers)
            d_stats    = docker_stats_brief() if not args.no_docker else []
            log_file   = latest_log_file()

            bootstrap_up = bs_status is not None

            # ── Render ──────────────────────────────────────────────────
            clear_screen()
            render_header(tick, bootstrap_up)
            render_peer_table(args.peers, d_states, bs_peers, d_stats)
            render_bootstrap_status(bs_status)

            if not args.no_events:
                render_bootstrap_events(bs_events, n=8)

            if not args.no_churn_log:
                render_churn_log(log_file, n=6)

            render_footer(args.interval)

            time.sleep(args.interval)

    except KeyboardInterrupt:
        clear_screen()
        print(colored("\nMonitor dừng.\n", C.DIM))


if __name__ == "__main__":
    main()
