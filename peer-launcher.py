#!/usr/bin/env python3
import html
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
    run([
        "docker", "run", "-d", "--name", "bootstrap", "--network", NETWORK,
        "-p", f"{BOOTSTRAP_PORT}:{BOOTSTRAP_PORT}",
        "-p", f"{DASHBOARD_PORT}:{DASHBOARD_PORT}",
        BOOTSTRAP_IMG,
        "--port", str(BOOTSTRAP_PORT),
        "--dashboard-port", str(DASHBOARD_PORT),
        "--mailbox", f"mailbox:{MAILBOX_PORT}",
    ])


def ensure_bootstrap_for_mailbox(mailbox_addr):
    if "bootstrap" in running_names():
        return
    ensure_network()
    ensure_mailbox()
    run(["docker", "rm", "-f", "bootstrap"], check=False)
    run([
        "docker", "run", "-d", "--name", "bootstrap", "--network", NETWORK,
        "-p", f"{BOOTSTRAP_PORT}:{BOOTSTRAP_PORT}",
        "-p", f"{DASHBOARD_PORT}:{DASHBOARD_PORT}",
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
    for port in range(33143, 60999):
        if port_available(port) and port_available(port + 1000) and port_available(port + 2000):
            return port
    raise RuntimeError("No free port range found")


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


def peers():
    items = []
    for name in sorted(n for n in docker_names() if n.startswith("peer-")):
        username = name.removeprefix("peer-")
        web = container_web_port(name)
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

    advertised_host = str(payload.get("advertisedHost", "")).strip()
    advertised_host_provided = bool(advertised_host)
    if not advertised_host:
        advertised_host = f"peer-{username}"

    bootstrap_addr = str(payload.get("bootstrap", "")).strip()
    use_local_infra = not bootstrap_addr
    if bootstrap_addr and ":" not in bootstrap_addr:
        raise ValueError("Bootstrap endpoint phải có dạng host:port")
    if not bootstrap_addr:
        bootstrap_addr = f"bootstrap:{BOOTSTRAP_PORT}"

    mailbox_addr = str(payload.get("mailbox", "")).strip()
    if mailbox_addr and ":" not in mailbox_addr:
        raise ValueError("Mailbox endpoint phải có dạng host:port")
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

    for port in (web_port, peer_port, file_port):
        if not port_available(port):
            raise ValueError(f"Port {port} đang được sử dụng")

    return username, advertised_host, advertised_host_provided, bootstrap_addr, mailbox_addr, use_local_infra, web_port, peer_port, file_port


def create_peer(payload):
    username, advertised_host, advertised_host_provided, bootstrap_addr, mailbox_addr, use_local_infra, web_port, peer_port, file_port = validate_peer_request(payload)
    container = f"peer-{username}"
    if container in docker_names():
        existing = start_existing_peer(username)
        existing["alreadyExists"] = True
        return existing

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
        <label>IP máy này
          <input name="advertisedHost" placeholder="Tailscale/Public IP, ví dụ 100.64.1.20" />
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
        <div class="hint">Nếu máy này làm bootstrap/mailbox, nhập IP máy này và để trống Bootstrap server. Nếu máy này chỉ tham gia, nhập IP máy này và Bootstrap server của máy chủ.</div>
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
        const prefix = data.alreadyExists ? `@${username} đã tồn tại, đã mở lại peer.` : `Đã tạo @${username}.`;
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
    server = ThreadingHTTPServer(("127.0.0.1", 9200), Handler)
    print("P2PChat launcher: http://localhost:9200")
    print("Press Ctrl+C to stop.")
    server.serve_forever()


if __name__ == "__main__":
    main()
