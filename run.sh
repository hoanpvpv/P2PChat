#!/usr/bin/env bash
set -e

# Fix for Windows Git Bash: Prevent path conversion for Docker volumes like /app/data
export MSYS_NO_PATHCONV=1

# Fix for Windows: fallback to python if python3 is not found
if ! command -v python3 >/dev/null 2>&1; then
    if command -v python >/dev/null 2>&1; then
        python3() { python "$@"; }
        export -f python3
    elif command -v py >/dev/null 2>&1; then
        python3() { py -3 "$@"; }
        export -f python3
    fi
fi

cd "$(dirname "$0")"

NETWORK="p2p-net"
BOOTSTRAP_IMG="p2pchat-bootstrap"
MAILBOX_IMG="p2pchat-mailbox"
PEER_IMG="p2pchat-peer"
BOOTSTRAP_PORT=9000
DASHBOARD_PORT=9001
MAILBOX_PORT=9100

image_exists() {
    docker image inspect "$1:latest" >/dev/null 2>&1
}

ensure_images() {
    if ! image_exists "$MAILBOX_IMG" || ! image_exists "$BOOTSTRAP_IMG" || ! image_exists "$PEER_IMG"; then
        echo "Docker images are missing; building first..."
        build
    fi
}

best_host_ip() {
    local ts_ip
    if command -v tailscale >/dev/null 2>&1; then
        ts_ip=$(tailscale ip -4 2>/dev/null | awk 'NR==1 {print $1}')
        if [ -n "$ts_ip" ]; then
            echo "$ts_ip"
            return
        fi
    fi

    if command -v ip >/dev/null 2>&1; then
        ip -o -4 addr show scope global 2>/dev/null | awk '
            {
                split($4, a, "/");
                ip=a[1];
                if (ip ~ /^127\\./) next;
                if (ip ~ /^169\\.254\\./) next;
                if (ip ~ /^172\\.(1[6-9]|2[0-9]|3[0-1])\\./) next;
                print ip;
                exit;
            }'
        return
    fi
}

is_docker_bridge_ip() {
    local ip="$1"
    [[ "$ip" =~ ^172\.(1[6-9]|2[0-9]|3[0-1])\. ]]
}

docker_reserved_ports() {
    for c in $(docker ps -a --format '{{.Names}}' 2>/dev/null); do
        docker inspect -f '{{range $containerPort, $bindings := .HostConfig.PortBindings}}{{range $bindings}}{{println .HostPort}}{{end}}{{end}}' "$c" 2>/dev/null || true
    done | awk 'NF {print $1}' | sort -n | uniq
}

port_available() {
    local port="$1"
    python3 - "$port" <<'PY' >/dev/null 2>&1
import socket, sys
port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind(("127.0.0.1", port))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
}

port_reserved() {
    local needle="$1"
    docker_reserved_ports | awk -v p="$needle" '$1 == p { found=1 } END { exit found ? 0 : 1 }'
}

choose_web_port() {
    local port
    for port in $(seq 33143 60999); do
        port_reserved "$port" && continue
        port_reserved "$((port + 1000))" && continue
        port_reserved "$((port + 2000))" && continue
        port_available "$port" && port_available "$((port + 1000))" && port_available "$((port + 2000))" && {
            echo "$port"
            return
        }
    done
    echo "No free port range found" >&2
    exit 1
}

detect_host_for_bootstrap() {
    local bootstrap_addr="${1:?missing bootstrap address}"
    python3 - "$bootstrap_addr" <<'PY'
import socket, sys
addr = sys.argv[1]
host, port = addr.rsplit(":", 1)
with socket.create_connection((host, int(port)), timeout=3) as sock:
    print(sock.getsockname()[0])
PY
}

ensure_network() {
    docker network inspect $NETWORK >/dev/null 2>&1 || docker network create $NETWORK >/dev/null
}

ensure_mailbox() {
    if ! docker ps --format '{{.Names}}' | grep -q '^mailbox$'; then
        docker rm -f mailbox 2>/dev/null || true
        ensure_network
        mkdir -p "$(pwd)/data/mailbox"
        docker run -d --name mailbox --network $NETWORK \
            -p ${MAILBOX_PORT}:${MAILBOX_PORT} \
            -v "$(pwd)/data/mailbox:/app/data" \
            $MAILBOX_IMG --port $MAILBOX_PORT >/dev/null
        sleep 1
        echo "Mailbox server started on port $MAILBOX_PORT"
    fi
}

ensure_mailbox_public() {
    if ! docker ps --format '{{.Names}}' | grep -q '^mailbox$'; then
        docker rm -f mailbox 2>/dev/null || true
        ensure_network
        mkdir -p "$(pwd)/data/mailbox"
        docker run -d --name mailbox --network $NETWORK \
            -p ${MAILBOX_PORT}:${MAILBOX_PORT} \
            -v "$(pwd)/data/mailbox:/app/data" \
            $MAILBOX_IMG --port $MAILBOX_PORT >/dev/null
        sleep 1
        echo "Mailbox server started on port $MAILBOX_PORT"
    fi
}

ensure_bootstrap() {
    if ! docker ps --format '{{.Names}}' | grep -q '^bootstrap$'; then
        docker rm -f bootstrap 2>/dev/null || true
        ensure_network
        ensure_mailbox
        mkdir -p "$(pwd)/data/bootstrap"
        local host_ip
        host_ip=$(best_host_ip)
        local mailbox_addr="mailbox:${MAILBOX_PORT}"
        if [ -n "$host_ip" ]; then
            mailbox_addr="${host_ip}:${MAILBOX_PORT}"
        fi
        docker run -d --name bootstrap --network $NETWORK \
            -p ${BOOTSTRAP_PORT}:${BOOTSTRAP_PORT} -p ${DASHBOARD_PORT}:${DASHBOARD_PORT} \
            -v "$(pwd)/data/bootstrap:/app/data" \
            $BOOTSTRAP_IMG \
            --port $BOOTSTRAP_PORT --dashboard-port $DASHBOARD_PORT --mailbox "$mailbox_addr" >/dev/null
        sleep 1
        echo "Bootstrap server started on port $BOOTSTRAP_PORT (Dashboard: $DASHBOARD_PORT)"
    fi
}

infra() {
    local host_ip="${1:?Usage: ./run.sh infra <your-reachable-ip-or-hostname>}"
    ensure_network
    ensure_mailbox_public
    if ! docker ps --format '{{.Names}}' | grep -q '^bootstrap$'; then
        docker rm -f bootstrap 2>/dev/null || true
        mkdir -p "$(pwd)/data/bootstrap"
        docker run -d --name bootstrap --network $NETWORK \
            -p ${BOOTSTRAP_PORT}:${BOOTSTRAP_PORT} -p ${DASHBOARD_PORT}:${DASHBOARD_PORT} \
            -v "$(pwd)/data/bootstrap:/app/data" \
            $BOOTSTRAP_IMG \
            --port $BOOTSTRAP_PORT --dashboard-port $DASHBOARD_PORT --mailbox ${host_ip}:${MAILBOX_PORT} >/dev/null
        sleep 1
        echo "Bootstrap server started on port $BOOTSTRAP_PORT (Dashboard: $DASHBOARD_PORT)"
    fi
    echo ""
    echo "Infrastructure ready:"
    echo "  Bootstrap: ${host_ip}:${BOOTSTRAP_PORT}"
    echo "  Dashboard: http://${host_ip}:${DASHBOARD_PORT}"
    echo "  Mailbox:   ${host_ip}:${MAILBOX_PORT}"
    echo ""
    echo "Other machines can join with:"
    echo "  ./run.sh join <username> <their-ip> ${host_ip}:${BOOTSTRAP_PORT}"
}

start_peer_container() {
    local username="${1:?missing username}"
    local advertise_host="${2:?missing advertised host}"
    local bootstrap_addr="${3:?missing bootstrap address}"
    local mailbox_addr="${4:?missing mailbox address}"
    local web_port="${5:?missing web port}"

    local peer_port=$((web_port + 1000))
    local file_port=$((peer_port + 1000))
    local container_name="peer-${username}"
    local data_dir="$(pwd)/data/peers/${username}"

    if [ "$file_port" -gt 65535 ]; then
        echo "Invalid web port: file port would be $file_port (> 65535)."
        exit 1
    fi
    docker rm -f $container_name 2>/dev/null || true

    for port in "$web_port" "$peer_port" "$file_port"; do
        if port_reserved "$port"; then
            echo "Port $port is already reserved by another Docker container."
            exit 1
        fi
        if ! port_available "$port"; then
            echo "Port $port is already in use."
            exit 1
        fi
    done

    mkdir -p "$data_dir"

    docker run -d --name $container_name --network $NETWORK \
        -p ${peer_port}:${peer_port} -p ${file_port}:${file_port} -p ${web_port}:${web_port} \
        -v "$data_dir:/app/data" \
        $PEER_IMG \
        --username "$username" --host "$advertise_host" --port $peer_port \
        --bootstrap "$bootstrap_addr" --mailbox "$mailbox_addr" --web $web_port >/dev/null

    sleep 1
    echo ""
    echo "Peer '$username' started:"
    echo "  Web UI:    http://localhost:${web_port}"
    echo "  Advertise: ${advertise_host}:${peer_port}"
    echo "  P2P port:  $peer_port"
    echo "  File port: $file_port"
    echo "  Bootstrap: $bootstrap_addr"
    echo "  Mailbox:   $mailbox_addr"
    echo ""
}

build() {
    echo "Building Docker images..."
    docker build -t $MAILBOX_IMG -f mailbox-server/Dockerfile mailbox-server/
    docker build -t $BOOTSTRAP_IMG -f bootstrap-server/Dockerfile bootstrap-server/
    docker build -f Dockerfile -t $PEER_IMG .
    echo "Build complete."
}

peer() {
    local username="${1:?Usage: ./run.sh peer <username>}"
    local web_port="$2"
    ensure_images
    ensure_network
    ensure_mailbox
    ensure_bootstrap

    if [ -z "$web_port" ]; then
        if [ "$username" = "alice" ]; then web_port=33143;
        elif [ "$username" = "bob" ]; then web_port=33144;
        else web_port=$(choose_web_port); fi
    fi

    local advertise_host
    advertise_host=$(best_host_ip)
    if [ -z "$advertise_host" ]; then
        advertise_host="peer-${username}"
    fi
    start_peer_container "$username" "$advertise_host" "bootstrap:${BOOTSTRAP_PORT}" "mailbox:${MAILBOX_PORT}" "$web_port"
    gen_index
}

join() {
    local username="${1:?Usage: ./run.sh join <username> [your-reachable-ip-or-auto] <bootstrap-host:port> [web-port] [mailbox-host:port]}"
    local host_ip="$2"
    local bootstrap_addr="$3"
    local web_port="$4"
    local mailbox_addr="$5"

    if [[ "$host_ip" == *:* && -z "$bootstrap_addr" ]]; then
        bootstrap_addr="$host_ip"
        host_ip="auto"
    fi
    if [ -z "$bootstrap_addr" ]; then
        echo "Usage: ./run.sh join <username> [your-reachable-ip-or-auto] <bootstrap-host:port> [web-port] [mailbox-host:port]"
        exit 1
    fi
    ensure_images

    ensure_network

    if [ -z "$web_port" ]; then
        web_port=$(choose_web_port)
    fi
    if [ -z "$host_ip" ] || [ "$host_ip" = "auto" ]; then
        host_ip=$(best_host_ip)
        if [ -z "$host_ip" ]; then
            host_ip=$(detect_host_for_bootstrap "$bootstrap_addr")
        fi
        if is_docker_bridge_ip "$host_ip"; then
            echo "Detected Docker bridge IP $host_ip; remote peers cannot reach it. Pass this machine's Tailscale IP instead."
            exit 1
        fi
    fi
    if [ -z "$mailbox_addr" ]; then
        local bootstrap_host="${bootstrap_addr%%:*}"
        mailbox_addr="${bootstrap_host}:${MAILBOX_PORT}"
    fi

    start_peer_container "$username" "$host_ip" "$bootstrap_addr" "$mailbox_addr" "$web_port"
    gen_index
}

start() {
    peer "alice" "33143"
    peer "bob" "33144"
    peer "duc" "12345"
    peer "hoang" "12346"
    peer "hoan" "12349"
}

stop() {
    docker rm -f bootstrap 2>/dev/null || true
    docker rm -f mailbox 2>/dev/null || true
    docker rm -f $(docker ps -a --filter "name=^peer-" -q) 2>/dev/null || true
    echo "Stopped all."
    gen_index
}

logs() {
    local target="${1:-all}"
    case $target in
        s|server)  docker logs -f bootstrap ;;
        all)       docker logs -f $(docker ps --filter "name=^peer-" -q) ;;
        *)         docker logs -f "peer-${target}" ;;
    esac
}

list() {
    echo "Running peers:"
    for c in $(docker ps --filter "name=^peer-" --format '{{.Names}}'); do
        local name=${c#peer-}
        local web=$(docker inspect -f '{{range .Config.Cmd}}{{println .}}{{end}}' "$c" | awk 'found {print; exit} $0 == "--web" {found=1}')
        echo "  $name  ->  http://localhost:$web"
    done
    if docker ps --format '{{.Names}}' | grep -q '^bootstrap$'; then
        echo "  [bootstrap on port $BOOTSTRAP_PORT]  Dashboard: http://localhost:$DASHBOARD_PORT"
    fi
    if docker ps --format '{{.Names}}' | grep -q '^mailbox$'; then
        echo "  [mailbox on port $MAILBOX_PORT]"
    fi
}

# Tạo trang HTML index liệt kê tất cả peer + URL của chúng.
# MềEfile://$(pwd)/peers-index.html trong trình duyệt, bookmark đềEbấm 1 phát vào UI.
gen_index() {
    local out="$(pwd)/peers-index.html"
    {
        cat <<'HEAD'
<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><title>P2PChat Peers</title>
<style>
 body{font-family:system-ui,Segoe UI,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:32px;}
 h1{margin:0 0 8px;font-size:22px;color:#f8fafc}
 p.sub{margin:0 0 24px;color:#94a3b8;font-size:13px}
 .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}
 a.card{display:block;padding:16px;background:#1e293b;border:1px solid #334155;border-radius:10px;
        text-decoration:none;color:#e2e8f0;transition:.15s}
 a.card:hover{background:#334155;border-color:#64748b;transform:translateY(-1px)}
 .name{font-weight:700;font-size:16px;color:#f1f5f9;margin-bottom:6px}
 .url{font-size:12px;color:#7dd3fc;font-family:monospace}
 .infra{margin-top:32px;font-size:13px;color:#64748b}
 .infra a{color:#7dd3fc}
 button.refresh{float:right;background:#334155;color:#fff;border:0;padding:6px 12px;border-radius:6px;cursor:pointer}
</style></head><body>
<h1>P2PChat Peers <button class="refresh" onclick="location.reload()">↻ Refresh</button></h1>
<p class="sub">Bookmark this page. Re-run <code>./run.sh peer &lt;name&gt;</code> rồi refresh đềEcập nhật.</p>
<div class="grid">
HEAD
        for c in $(docker ps --filter "name=^peer-" --format '{{.Names}}' | sort); do
            local name=${c#peer-}
            local web=$(docker inspect -f '{{range .Config.Cmd}}{{println .}}{{end}}' "$c" | awk 'found {print; exit} $0 == "--web" {found=1}')
            [ -z "$web" ] && continue
            echo "<a class=\"card\" target=\"_blank\" href=\"http://localhost:$web\"><div class=\"name\">@$name</div><div class=\"url\">localhost:$web</div></a>"
        done
        cat <<'FOOT'
</div>
<div class="infra">
FOOT
        if docker ps --format '{{.Names}}' | grep -q '^bootstrap$'; then
            echo "<div>Bootstrap dashboard: <a target=\"_blank\" href=\"http://localhost:$DASHBOARD_PORT\">http://localhost:$DASHBOARD_PORT</a> (TCP $BOOTSTRAP_PORT)</div>"
        fi
        if docker ps --format '{{.Names}}' | grep -q '^mailbox$'; then
            echo "<div>Mailbox TCP: localhost:$MAILBOX_PORT (no UI)</div>"
        fi
        echo "<div style=\"margin-top:12px;font-size:11px;color:#475569\">Generated $(date '+%Y-%m-%d %H:%M:%S')</div>"
        echo "</div></body></html>"
    } > "$out"
    echo "ↁEĐã cập nhật file://$out"
}

urls() {
    gen_index
    list
}

doctor() {
    echo "Local Tailscale IP: $(tailscale ip -4 2>/dev/null | awk 'NR==1 {print $1}')"
    echo "Best advertise IP: $(best_host_ip)"
    echo ""
    echo "Docker peers:"
    docker ps --filter "name=^peer-" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    echo ""
    echo "Listening ports:"
    ss -ltnp | grep -E ":(${BOOTSTRAP_PORT}|${MAILBOX_PORT}|3[0-9]{4})" || true
    echo ""
    if [ -n "$1" ] && [ -n "$2" ]; then
        echo "Remote direct check: $1:$2"
        nc -vz -w 6 "$1" "$2"
    else
        echo "Remote direct check usage: ./run.sh doctor <remote-tailscale-ip> <peer-port>"
    fi
}

launcher() {
    if ! command -v python3 >/dev/null 2>&1; then
        echo "python3 is required to run the launcher."
        exit 1
    fi
    ensure_images
    # Kill any existing launcher (matches `python3 peer-launcher.py`) so a fresh
    # process picks up code changes without manual cleanup.
    local existing
    existing=$(pgrep -f "python3 .*peer-launcher\.py" | grep -v $$ || true)
    if [ -n "$existing" ]; then
        echo "Stopping existing launcher: $existing"
        kill $existing 2>/dev/null || true
        # Give the socket time to free.
        for _ in 1 2 3 4 5; do
            pgrep -f "python3 .*peer-launcher\.py" | grep -v $$ >/dev/null || break
            sleep 0.3
        done
        # Force kill anything still alive.
        pgrep -f "python3 .*peer-launcher\.py" | grep -v $$ | xargs -r kill -9 2>/dev/null || true
    fi
    python3 peer-launcher.py
}

case "${1:-help}" in
    build)  build ;;
    peer)   peer "$2" "$3" ;;
    infra)  infra "$2" ;;
    join)   join "$2" "$3" "$4" "$5" "$6" ;;
    start)  start "$2" "$3" ;;
    stop)   stop ;;
    logs)   logs "$2" ;;
    list)   list ;;
    urls)   urls ;;
    doctor) doctor "$2" "$3" ;;
    launcher) launcher ;;
    restart) stop; start "$2" "$3" ;;
    help|*)
        echo "Usage: ./run.sh <command>"
        echo ""
        echo "  ./run.sh build              Build images"
        echo "  ./run.sh peer <name>        Start 1 peer with random ports"
        echo "  ./run.sh infra <host-ip>    Start mailbox + bootstrap for remote peers"
        echo "  ./run.sh join <name> <host-ip> <bootstrap> [web] [mailbox]"
        echo "                              Join remote bootstrap as a peer"
        echo "  ./run.sh start [n1] [n2]    Start 2 peers (default: alice, bob)"
        echo "  ./run.sh launcher           Open localhost UI to create peers"
        echo "  ./run.sh list               List running peers + URLs"
        echo "  ./run.sh urls               MềEfile peers-index.html (clickable links cho tất cả peer)"
        echo "  ./run.sh doctor [ip port]   Check local config and optional remote direct TCP"
        echo "  ./run.sh logs [name|s]      Follow logs"
        echo "  ./run.sh stop               Stop all"
        echo "  ./run.sh restart [n1] [n2]  Restart all"
        ;;
esac
