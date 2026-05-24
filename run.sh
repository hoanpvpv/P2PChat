#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

NETWORK="p2p-net"
BOOTSTRAP_IMG="p2pchat-bootstrap"
MAILBOX_IMG="p2pchat-mailbox"
PEER_IMG="p2pchat-peer"
BOOTSTRAP_PORT=9000
DASHBOARD_PORT=9001
MAILBOX_PORT=9100

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
        docker run -d --name bootstrap --network $NETWORK \
            -p ${BOOTSTRAP_PORT}:${BOOTSTRAP_PORT} -p ${DASHBOARD_PORT}:${DASHBOARD_PORT} $BOOTSTRAP_IMG \
            --port $BOOTSTRAP_PORT --dashboard-port $DASHBOARD_PORT --mailbox mailbox:${MAILBOX_PORT} >/dev/null
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
        docker run -d --name bootstrap --network $NETWORK \
            -p ${BOOTSTRAP_PORT}:${BOOTSTRAP_PORT} -p ${DASHBOARD_PORT}:${DASHBOARD_PORT} $BOOTSTRAP_IMG \
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

random_port() {
    echo $((RANDOM % 50000 + 10000))
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
    ensure_network
    ensure_mailbox
    ensure_bootstrap

    if [ -z "$web_port" ]; then
        if [ "$username" = "alice" ]; then web_port=33143;
        elif [ "$username" = "bob" ]; then web_port=33144;
        else web_port=$(random_port); fi
    fi

    start_peer_container "$username" "peer-${username}" "bootstrap:${BOOTSTRAP_PORT}" "mailbox:${MAILBOX_PORT}" "$web_port"
    gen_index
}

join() {
    local username="${1:?Usage: ./run.sh join <username> <your-reachable-ip-or-hostname> <bootstrap-host:port> [web-port] [mailbox-host:port]}"
    local host_ip="${2:?Usage: ./run.sh join <username> <your-reachable-ip-or-hostname> <bootstrap-host:port> [web-port] [mailbox-host:port]}"
    local bootstrap_addr="${3:?Usage: ./run.sh join <username> <your-reachable-ip-or-hostname> <bootstrap-host:port> [web-port] [mailbox-host:port]}"
    local web_port="$4"
    local mailbox_addr="$5"

    ensure_network

    if [ -z "$web_port" ]; then
        web_port=$(random_port)
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
# Mở file://$(pwd)/peers-index.html trong trình duyệt, bookmark để bấm 1 phát vào UI.
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
<p class="sub">Bookmark this page. Re-run <code>./run.sh peer &lt;name&gt;</code> rồi refresh để cập nhật.</p>
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
    echo "→ Đã cập nhật file://$out"
}

urls() {
    gen_index
    list
}

launcher() {
    if ! command -v python3 >/dev/null 2>&1; then
        echo "python3 is required to run the launcher."
        exit 1
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
        echo "  ./run.sh urls               Mở file peers-index.html (clickable links cho tất cả peer)"
        echo "  ./run.sh logs [name|s]      Follow logs"
        echo "  ./run.sh stop               Stop all"
        echo "  ./run.sh restart [n1] [n2]  Restart all"
        ;;
esac
