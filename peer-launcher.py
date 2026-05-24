#!/usr/bin/env python3
import html
import ipaddress
import json
import os
import re
import socket
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parent
NETWORK = "p2p-net"
BOOTSTRAP_IMG = "p2pchat-bootstrap"
MAILBOX_IMG = "p2pchat-mailbox"
PEER_IMG = "p2pchat-peer"
BOOTSTRAP_PORT = 9000
DASHBOARD_PORT = 9001
MAILBOX_PORT = 9100
LAUNCHER_HOST = "127.0.0.1"
LAUNCHER_PORT = 9200
USERNAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,32}$")


def run(args, check=True):
    return subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=check)


def docker_names():
    result = run(["docker", "ps", "-a", "--format", "{{.Names}}"], check=False)
    if result.returncode != 0:
        return set()
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def running_names():
    result = run(["docker", "ps", "--format", "{{.Names}}"], check=False)
    if result.returncode != 0:
        return set()
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def ensure_network():
    result = run(["docker", "network", "inspect", NETWORK], check=False)
    if result.returncode != 0:
        run(["docker", "network", "create", NETWORK])


def ensure_mailbox():
    if "mailbox" in running_names():
        return
    ensure_network()
    run(["docker", "rm", "-f", "mailbox"], check=False)
    (ROOT / "data" / "mailbox").mkdir(parents=True, exist_ok=True)
    run([
        "docker", "run", "-d", "--name", "mailbox", "--network", NETWORK,
        "-p", f"{MAILBOX_PORT}:{MAILBOX_PORT}",
        "-v", f"{ROOT / 'data' / 'mailbox'}:/app/data",
        MAILBOX_IMG, "--port", str(MAILBOX_PORT),
    ])


def ensure_bootstrap():
    if "bootstrap" in running_names():
        return
    ensure_network()
    ensure_mailbox()
    run(["docker", "rm", "-f", "bootstrap"], check=False)
    (ROOT / "data" / "bootstrap").mkdir(parents=True, exist_ok=True)
    # Bootstrap reports this address to peers via RESOLVE_MAILBOX. Docker DNS name
    # ("mailbox:9100") only resolves inside p2p-net; remote peers (Tailscale/LAN)
    # need a host-reachable IP. Prefer Tailscale, fall back to LAN, else Docker name.
    best_ip = pick_best_local_ip()
    mailbox_endpoint = f"{best_ip}:{MAILBOX_PORT}" if best_ip else f"mailbox:{MAILBOX_PORT}"
    run([
        "docker", "run", "-d", "--name", "bootstrap", "--network", NETWORK,
        "-p", f"{BOOTSTRAP_PORT}:{BOOTSTRAP_PORT}",
        "-p", f"{DASHBOARD_PORT}:{DASHBOARD_PORT}",
        "-v", f"{ROOT / 'data' / 'bootstrap'}:/app/data",
        BOOTSTRAP_IMG,
        "--port", str(BOOTSTRAP_PORT),
        "--dashboard-port", str(DASHBOARD_PORT),
        "--mailbox", mailbox_endpoint,
    ])


def ensure_bootstrap_for_mailbox(mailbox_addr):
    if "bootstrap" in running_names():
        return
    ensure_network()
    ensure_mailbox()
    run(["docker", "rm", "-f", "bootstrap"], check=False)
    (ROOT / "data" / "bootstrap").mkdir(parents=True, exist_ok=True)
    run([
        "docker", "run", "-d", "--name", "bootstrap", "--network", NETWORK,
        "-p", f"{BOOTSTRAP_PORT}:{BOOTSTRAP_PORT}",
        "-p", f"{DASHBOARD_PORT}:{DASHBOARD_PORT}",
        "-v", f"{ROOT / 'data' / 'bootstrap'}:/app/data",
        BOOTSTRAP_IMG,
        "--port", str(BOOTSTRAP_PORT),
        "--dashboard-port", str(DASHBOARD_PORT),
        "--mailbox", mailbox_addr,
    ])


def port_available(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def choose_web_port():
    reserved = docker_reserved_ports()
    for port in range(33143, 60999):
        if port in reserved or (port + 1000) in reserved or (port + 2000) in reserved:
            continue
        if port_available(port) and port_available(port + 1000) and port_available(port + 2000):
            return port
    raise RuntimeError("No free port range found")


def local_ipv4_addresses():
    addresses = set()
    for host in (socket.gethostname(), socket.getfqdn(), "localhost"):
        try:
            for family, _, _, _, sockaddr in socket.getaddrinfo(host, None, socket.AF_INET):
                if family == socket.AF_INET:
                    addresses.add(sockaddr[0])
        except socket.gaierror:
            pass

    for cmd in (["ip", "-o", "-4", "addr", "show"], ["tailscale", "ip", "-4"]):
        try:
            result = run(cmd, check=False)
        except FileNotFoundError:
            continue
        if result.returncode != 0:
            continue
        if cmd[0] == "ip":
            for match in re.finditer(r"\binet\s+(\d+\.\d+\.\d+\.\d+)/", result.stdout):
                addresses.add(match.group(1))
        else:
            for line in result.stdout.splitlines():
                line = line.strip()
                try:
                    ipaddress.ip_address(line)
                    addresses.add(line)
                except ValueError:
                    pass
    return addresses


TAILSCALE_NET = ipaddress.ip_network("100.64.0.0/10")


def is_docker_bridge_ip(addr):
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    return ip.version == 4 and ip in ipaddress.ip_network("172.16.0.0/12")


def is_tailscale_ip(addr):
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    return ip.version == 4 and ip in TAILSCALE_NET


def pick_best_local_ip():
    """Prefer Tailscale CGNAT, then any private/usable IP, skip loopback/link-local/docker bridge."""
    tailscale = None
    fallback = None
    for addr in local_ipv4_addresses():
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            continue
        if ip.is_loopback or ip.is_link_local or ip.is_unspecified or ip.is_multicast:
            continue
        if ip in TAILSCALE_NET:
            if tailscale is None:
                tailscale = addr
            continue
        # Skip docker bridge subnets to avoid advertising container-side addresses.
        if is_docker_bridge_ip(addr):
            continue
        if fallback is None:
            fallback = addr
    return tailscale or fallback


def docker_reserved_ports():
    """Host ports reserved by any container (running or stopped) via -p bindings."""
    listing = run(["docker", "ps", "-a", "--format", "{{.Names}}"], check=False)
    if listing.returncode != 0:
        return set()
    reserved = set()
    for name in listing.stdout.split():
        name = name.strip()
        if not name:
            continue
        inspect = run(["docker", "inspect", "-f", "{{json .HostConfig.PortBindings}}", name], check=False)
        if inspect.returncode != 0:
            continue
        raw = inspect.stdout.strip()
        if not raw or raw == "null":
            continue
        try:
            bindings = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if not bindings:
            continue
        for host_bindings in bindings.values():
            if not host_bindings:
                continue
            for binding in host_bindings:
                host_port = binding.get("HostPort")
                if not host_port:
                    continue
                try:
                    reserved.add(int(host_port))
                except ValueError:
                    pass
    return reserved


def endpoint_parts(value, label):
    if value.count(":") != 1:
        raise ValueError(f"{label} phải có dạng host:port")
    host, port_raw = value.rsplit(":", 1)
    host = host.strip()
    if not host:
        raise ValueError(f"{label} thiếu host")
    try:
        port = int(port_raw)
    except ValueError:
        raise ValueError(f"{label} port không hợp lệ")
    if port < 1 or port > 65535:
        raise ValueError(f"{label} port phải nằm trong khoảng 1..65535")
    return host, port


def can_connect(host, port, timeout=1.5):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def detect_host_for_endpoint(host, port):
    try:
        with socket.create_connection((host, port), timeout=2) as sock:
            detected = sock.getsockname()[0]
    except OSError as exc:
        raise ValueError(f"Không kết nối được bootstrap server {host}:{port}. Kiểm tra IP/port trước khi tạo peer.") from exc
    if not detected or detected.startswith("127."):
        raise ValueError(
            "Không tự detect được IP máy này qua bootstrap. "
            "Hãy nhập Tailscale IP của máy này vào phần override nâng cao."
        )
    if is_docker_bridge_ip(detected):
        replacement = pick_best_local_ip()
        if replacement:
            return replacement
        raise ValueError(
            f"Tự detect ra IP Docker nội bộ {detected}, peer remote sẽ không kết nối được. "
            "Hãy nhập Tailscale IP của máy này vào phần IP máy này."
        )
    return detected


def validate_advertised_host(host):
    try:
        parsed = ipaddress.ip_address(host)
        if parsed.is_loopback or parsed.is_unspecified:
            raise ValueError("IP máy này không được là localhost/0.0.0.0. Nếu test local, hãy để trống ô IP máy này.")
        if is_docker_bridge_ip(host):
            raise ValueError("IP máy này không được là IP Docker 172.16.0.0/12. Hãy dùng Tailscale IP 100.x hoặc để trống để tự detect.")
        if is_tailscale_ip(host):
            return
        resolved = {str(parsed)}
    except ValueError as exc:
        if "localhost/0.0.0.0" in str(exc):
            raise
        try:
            resolved = {item[4][0] for item in socket.getaddrinfo(host, None, socket.AF_INET)}
        except socket.gaierror:
            raise ValueError("IP máy này không hợp lệ hoặc hostname không resolve được")

    local_addresses = local_ipv4_addresses()
    if not resolved.intersection(local_addresses):
        if any(is_tailscale_ip(addr) for addr in resolved):
            return
        known = ", ".join(sorted(local_addresses)) or "không phát hiện được IP local"
        raise ValueError(
            "IP máy này phải là IP thật của máy đang chạy launcher. "
            f"Bạn nhập '{host}', nhưng IP local phát hiện được là: {known}. "
            "Nếu chỉ test nhiều peer trên cùng máy, hãy để trống ô IP máy này."
        )


def container_web_port(container):
    result = run([
        "docker", "inspect", "-f",
        "{{range .Config.Cmd}}{{println .}}{{end}}",
        container,
    ], check=False)
    if result.returncode != 0:
        return ""
    lines = result.stdout.splitlines()
    for i, line in enumerate(lines):
        if line == "--web" and i + 1 < len(lines):
            return lines[i + 1]
    return ""


def has_published_web_port(container, web_port):
    if not web_port:
        return False
    result = run(["docker", "port", container, f"{web_port}/tcp"], check=False)
    return result.returncode == 0 and bool(result.stdout.strip())


def peers():
    items = []
    for name in sorted(n for n in docker_names() if n.startswith("peer-")):
        username = name.removeprefix("peer-")
        web = container_web_port(name)
        if not has_published_web_port(name, web):
            continue
        running = name in running_names()
        items.append({
            "username": username,
            "container": name,
            "webPort": web,
            "url": f"http://localhost:{web}" if web else "",
            "running": running,
        })
    return items


def start_existing_peer(username):
    if not USERNAME_RE.match(username):
        raise ValueError("Invalid username")
    container = f"peer-{username}"
    if container not in docker_names():
        raise ValueError(f"Peer '{username}' không tồn tại")
    if container not in running_names():
        run(["docker", "start", container])
    web = container_web_port(container)
    if not web:
        raise ValueError(f"Không tìm thấy web port cho peer '{username}'")
    return {
        "username": username,
        "webPort": web,
        "url": f"http://localhost:{web}",
        "running": True,
    }


def validate_peer_request(payload):
    username = str(payload.get("username", "")).strip()
    if not USERNAME_RE.match(username):
        raise ValueError("Username chỉ dùng chữ, số, dấu _ hoặc -, tối đa 32 ký tự")

    bootstrap_addr = str(payload.get("bootstrap", "")).strip()
    use_local_infra = not bootstrap_addr
    bootstrap_host = None
    bootstrap_port = None
    if bootstrap_addr:
        bootstrap_host, bootstrap_port = endpoint_parts(bootstrap_addr, "Bootstrap endpoint")
    if not bootstrap_addr:
        bootstrap_addr = f"bootstrap:{BOOTSTRAP_PORT}"

    advertised_host = str(payload.get("advertisedHost", "")).strip()
    advertised_host_provided = bool(advertised_host)
    if advertised_host_provided:
        validate_advertised_host(advertised_host)
    elif use_local_infra:
        detected = pick_best_local_ip()
        # Container DNS name only works for peers on this host's p2p-net; advertising a
        # real IP lets remote peers (over Tailscale/LAN) reach this peer too.
        advertised_host = detected if detected else f"peer-{username}"
    else:
        advertised_host = detect_host_for_endpoint(bootstrap_host, bootstrap_port)

    mailbox_addr = str(payload.get("mailbox", "")).strip()
    if mailbox_addr:
        mailbox_host, mailbox_port = endpoint_parts(mailbox_addr, "Mailbox endpoint")
        if not can_connect(mailbox_host, mailbox_port):
            raise ValueError(f"Không kết nối được mailbox server {mailbox_addr}.")
    if not mailbox_addr:
        mailbox_addr = f"mailbox:{MAILBOX_PORT}" if use_local_infra else f"{bootstrap_addr.split(':', 1)[0]}:{MAILBOX_PORT}"

    web_port_raw = str(payload.get("webPort", "")).strip()
    web_port = int(web_port_raw) if web_port_raw else choose_web_port()
    if web_port < 1024 or web_port > 63000:
        raise ValueError("Web port phải nằm trong khoảng 1024..63000")

    peer_port = web_port + 1000
    file_port = peer_port + 1000
    if file_port > 65535:
        raise ValueError("Web port quá lớn vì file port vượt 65535")

    reserved_ports = docker_reserved_ports()
    for port in (web_port, peer_port, file_port):
        if port in reserved_ports:
            raise ValueError(f"Port {port} đã được container Docker khác đăng ký")
        if not port_available(port):
            raise ValueError(f"Port {port} đang được sử dụng")

    return username, advertised_host, advertised_host_provided, bootstrap_addr, mailbox_addr, use_local_infra, web_port, peer_port, file_port


def create_peer(payload):
    username, advertised_host, advertised_host_provided, bootstrap_addr, mailbox_addr, use_local_infra, web_port, peer_port, file_port = validate_peer_request(payload)
    container = f"peer-{username}"
    recreated = False
    if container in docker_names():
        run(["docker", "rm", "-f", container], check=False)
        recreated = True

    ensure_network()
    if use_local_infra:
        if advertised_host_provided:
            mailbox_addr = f"{advertised_host}:{MAILBOX_PORT}"
            ensure_bootstrap_for_mailbox(mailbox_addr)
        else:
            ensure_mailbox()
            ensure_bootstrap()

    data_dir = ROOT / "data" / "peers" / username
    data_dir.mkdir(parents=True, exist_ok=True)

    run([
        "docker", "run", "-d", "--name", container, "--network", NETWORK,
        "-p", f"{peer_port}:{peer_port}",
        "-p", f"{file_port}:{file_port}",
        "-p", f"{web_port}:{web_port}",
        "-v", f"{data_dir}:/app/data",
        PEER_IMG,
        "--username", username,
        "--host", advertised_host,
        "--port", str(peer_port),
        "--bootstrap", bootstrap_addr,
        "--mailbox", mailbox_addr,
        "--web", str(web_port),
    ])

    write_peer_index()
    return {
        "username": username,
        "webPort": web_port,
        "peerPort": peer_port,
        "filePort": file_port,
        "advertisedHost": advertised_host,
        "bootstrap": bootstrap_addr,
        "mailbox": mailbox_addr,
        "url": f"http://localhost:{web_port}",
        "recreated": recreated,
    }


def write_peer_index():
    cards = []
    for peer in peers():
        if not peer["webPort"]:
            continue
        name = html.escape(peer["username"])
        url = html.escape(peer["url"])
        state = "running" if peer["running"] else "stopped"
        cards.append(
            f'<a class="card" target="_blank" href="{url}">'
            f'<div class="name">@{name}</div>'
            f'<div class="url">{url}</div>'
            f'<div class="state">{state}</div></a>'
        )
    body = "\n".join(cards)
    (ROOT / "peers-index.html").write_text(f"""<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><title>P2PChat Peers</title>
<style>
body{{font-family:system-ui,Segoe UI,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:32px}}
h1{{margin:0 0 8px;font-size:22px;color:#f8fafc}}p{{color:#94a3b8}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}}
.card{{display:block;padding:16px;background:#1e293b;border:1px solid #334155;border-radius:8px;text-decoration:none;color:#e2e8f0}}
.card:hover{{background:#334155}}.name{{font-weight:700;margin-bottom:6px}}.url{{font:12px monospace;color:#7dd3fc}}.state{{margin-top:8px;font-size:12px;color:#94a3b8}}
</style></head><body>
<h1>P2PChat Peers</h1><p>Tạo peer mới tại <a style="color:#7dd3fc" href="http://localhost:9200">Launcher</a>.</p>
<div class="grid">{body}</div></body></html>
""", encoding="utf-8")


PAGE = """<!doctype html>
<html lang="vi">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>P2PChat Peer Registration</title>
  <style>
    * { box-sizing: border-box; }
    body { margin: 0; font-family: Inter, Segoe UI, system-ui, sans-serif; background: #101828; color: #e4e7ec; }
    main { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
    .panel { width: min(560px, 100%); background: #182230; border: 1px solid #344054; border-radius: 8px; padding: 22px; }
    h1 { margin: 0; font-size: 24px; letter-spacing: 0; }
    .muted { color: #98a2b3; }
    .subtitle { margin: 8px 0 22px; line-height: 1.45; }
    label { display: grid; gap: 7px; margin-bottom: 14px; font-size: 13px; color: #cbd5e1; }
    input { width: 100%; border: 1px solid #475467; border-radius: 6px; padding: 11px 12px; background: #0b1220; color: #f8fafc; font-size: 15px; }
    button { width: 100%; border: 0; border-radius: 6px; padding: 12px 14px; background: #2e90fa; color: white; font-weight: 700; cursor: pointer; }
    button:disabled { opacity: .65; cursor: wait; }
    .hint { font-size: 12px; line-height: 1.45; color: #98a2b3; margin-top: 10px; }
    .toast { margin-top: 14px; padding: 11px 12px; border-radius: 6px; background: #0b1220; border: 1px solid #344054; color: #cbd5e1; }
    .toast.error { border-color: #f04438; color: #fda29b; }
    .open-link { display: block; margin-top: 10px; color: #7dd3fc; font-weight: 700; text-decoration: none; overflow-wrap: anywhere; }
    .open-link:hover { text-decoration: underline; }
    .continue { display: none; margin-bottom: 18px; padding: 14px; border: 1px solid #344054; border-radius: 8px; background: #101828; }
    .continue h2 { margin: 0 0 10px; font-size: 16px; }
    .peer-row { display: grid; grid-template-columns: 1fr auto; gap: 10px; align-items: center; padding: 10px 0; border-top: 1px solid #263241; }
    .peer-row:first-of-type { border-top: 0; padding-top: 0; }
    .peer-name { font-weight: 800; }
    .peer-url { margin-top: 4px; color: #98a2b3; font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
    .open-btn { display: inline-block; border-radius: 6px; padding: 9px 12px; background: #2e90fa; color: #fff; text-decoration: none; font-size: 13px; font-weight: 800; }
    .open-btn.stopped { background: #475467; }
    .divider { height: 1px; margin: 18px 0; background: #344054; }
  </style>
</head>
<body>
  <main>
    <div class="panel">
      <h1>P2PChat</h1>
      <div class="muted subtitle">Mở lại peer đang có hoặc đăng ký một peer mới trên máy này.</div>
      <section class="continue" id="continueBox">
        <h2>Continue</h2>
        <div id="existingPeers"></div>
      </section>
      <form id="form">
        <div class="divider"></div>
        <label>Username
          <input name="username" placeholder="charlie" autocomplete="off" required pattern="[A-Za-z0-9_-]{1,32}" />
        </label>
        <label>IP máy này <span class="muted">(tùy chọn nâng cao)</span>
          <input name="advertisedHost" placeholder="Để trống để tự detect qua bootstrap" />
        </label>
        <label>Bootstrap server
          <input name="bootstrap" placeholder="Máy bạn bè nhập 100.64.1.10:9000, máy chủ có thể bỏ trống" />
        </label>
        <label>Mailbox server
          <input name="mailbox" placeholder="Thường bỏ trống, tự dùng IP bootstrap với port 9100" />
        </label>
        <label>Web port
          <input name="webPort" type="number" min="1024" max="63000" placeholder="Auto nếu bỏ trống" />
        </label>
        <button id="createBtn" type="submit">Register and start peer</button>
        <div class="hint">Bạn bè chỉ cần nhập Username và Bootstrap server. Ô IP máy này để trống; launcher sẽ tự detect IP dùng để kết nối tới bootstrap. Chỉ nhập IP thủ công khi auto-detect sai.</div>
      </form>
      <div id="message"></div>
    </div>
  </main>
  <script>
    const msgEl = document.getElementById('message');
    const form = document.getElementById('form');
    const createBtn = document.getElementById('createBtn');
    const continueBox = document.getElementById('continueBox');
    const existingPeersEl = document.getElementById('existingPeers');

    function toast(text, isError = false) {
      msgEl.innerHTML = text ? `<div class="toast ${isError ? 'error' : ''}">${text}</div>` : '';
    }

    function escapeHtml(value) {
      return String(value).replace(/[&<>"']/g, (ch) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
      }[ch]));
    }

    async function loadExistingPeers() {
      try {
        const res = await fetch('/api/peers');
        const data = await res.json();
        const peers = data.peers || [];
        if (!peers.length) {
          continueBox.style.display = 'none';
          return;
        }
        continueBox.style.display = 'block';
        existingPeersEl.innerHTML = peers.map((peer) => {
          const username = escapeHtml(peer.username);
          const url = escapeHtml(peer.url || '');
          const state = peer.running ? 'Open' : 'Start';
          return `<div class="peer-row">
            <div>
              <div class="peer-name">@${username} ${peer.running ? '' : '<span class="muted">(stopped)</span>'}</div>
              <div class="peer-url">${url || 'No web port found'}</div>
            </div>
            <a class="open-btn ${peer.running ? '' : 'stopped'}" href="/open/${username}">${state}</a>
          </div>`;
        }).join('');
      } catch (error) {
        continueBox.style.display = 'none';
      }
    }

    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      createBtn.disabled = true;
      toast('Đang tạo peer...');
      const body = Object.fromEntries(new FormData(form).entries());
      try {
        const res = await fetch('/api/peer', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'Create peer failed');
        const username = escapeHtml(data.username);
        const url = escapeHtml(data.url);
        const prefix = data.recreated ? `Đã cập nhật và mở lại @${username}.` : `Đã tạo @${username}.`;
        toast(`${prefix}<a class="open-link" target="_blank" href="${url}">Mở peer: ${url}</a>`);
        form.reset();
        loadExistingPeers();
      } catch (error) {
        toast(error.message, true);
      } finally {
        createBtn.disabled = false;
      }
    });
    loadExistingPeers();
  </script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    def send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            body = PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/peers":
            self.send_json(200, {"peers": peers()})
            return
        if path.startswith("/open/"):
            username = path.removeprefix("/open/").strip()
            try:
                peer = start_existing_peer(username)
                self.send_response(302)
                self.send_header("Location", peer["url"])
                self.end_headers()
            except Exception as exc:
                body = f"Failed to open peer: {html.escape(str(exc))}".encode("utf-8")
                self.send_response(400)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            return
        self.send_json(404, {"error": "Not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/api/peer":
            self.send_json(404, {"error": "Not found"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(size).decode("utf-8") or "{}")
            self.send_json(201, create_peer(payload))
        except subprocess.CalledProcessError as exc:
            error = exc.stderr.strip() or exc.stdout.strip() or str(exc)
            self.send_json(500, {"error": error})
        except Exception as exc:
            self.send_json(400, {"error": str(exc)})

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))


def main():
    os.chdir(ROOT)
    try:
        server = ThreadingHTTPServer((LAUNCHER_HOST, LAUNCHER_PORT), Handler)
    except OSError as exc:
        if exc.errno == 98:
            print(f"Launcher is already running: http://localhost:{LAUNCHER_PORT}")
            print("Close that launcher first, or open the URL above in your browser.")
            return
        raise
    print(f"P2PChat launcher: http://localhost:{LAUNCHER_PORT}")
    print("Press Ctrl+C to stop.")
    server.serve_forever()


if __name__ == "__main__":
    main()
