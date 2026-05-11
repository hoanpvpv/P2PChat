#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

NETWORK="p2p-net"
BOOTSTRAP_IMG="p2pchat-bootstrap"
PEER_IMG="p2pchat-peer"
BOOTSTRAP_PORT=8080

ensure_network() {
    docker network inspect $NETWORK >/dev/null 2>&1 || docker network create $NETWORK >/dev/null
}

ensure_bootstrap() {
    if ! docker ps --format '{{.Names}}' | grep -q '^bootstrap$'; then
        docker rm -f bootstrap 2>/dev/null || true
        ensure_network
        docker run -d --name bootstrap --network $NETWORK -p ${BOOTSTRAP_PORT}:${BOOTSTRAP_PORT} $BOOTSTRAP_IMG >/dev/null
        sleep 1
        echo "Bootstrap server started on port $BOOTSTRAP_PORT"
    fi
}

random_port() {
    echo $((RANDOM % 50000 + 10000))
}

build() {
    echo "Building Docker images..."
    docker build -t $BOOTSTRAP_IMG -f bootstrap-server/Dockerfile bootstrap-server/
    docker build -f Dockerfile -t $PEER_IMG .
    echo "Build complete."
}

peer() {
    local username="${1:?Usage: ./run.sh peer <username>}"
    ensure_network
    ensure_bootstrap

    local peer_port=$(random_port)
    local web_port=$(random_port)
    local container_name="peer-${username}"

    docker rm -f $container_name 2>/dev/null || true

    docker run -d --name $container_name --network $NETWORK \
        -p ${peer_port}:${peer_port} -p ${web_port}:${web_port} $PEER_IMG \
        --username "$username" --host $container_name --port $peer_port --bootstrap bootstrap:${BOOTSTRAP_PORT} --web $web_port >/dev/null

    sleep 1
    echo ""
    echo "Peer '$username' started:"
    echo "  Web UI: http://localhost:${web_port}"
    echo "  P2P port: $peer_port"
    echo ""
}

start() {
    local username="${1:-alice}"
    local username2="${2:-bob}"
    peer "$username"
    peer "$username2"
}

stop() {
    docker rm -f bootstrap 2>/dev/null || true
    docker rm -f $(docker ps -a --filter "name=^peer-" -q) 2>/dev/null || true
    echo "Stopped all."
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
        local ports=$(docker port $c)
        local web=$(echo "$ports" | grep -oP ':\K\d+' | tail -1)
        echo "  $name  ->  http://localhost:$web"
    done
    if docker ps --format '{{.Names}}' | grep -q '^bootstrap$'; then
        echo "  [bootstrap on port $BOOTSTRAP_PORT]"
    fi
}

case "${1:-help}" in
    build)  build ;;
    peer)   peer "$2" ;;
    start)  start "$2" "$3" ;;
    stop)   stop ;;
    logs)   logs "$2" ;;
    list)   list ;;
    restart) stop; start "$2" "$3" ;;
    help|*)
        echo "Usage: ./run.sh <command>"
        echo ""
        echo "  ./run.sh build              Build images"
        echo "  ./run.sh peer <name>        Start 1 peer with random ports"
        echo "  ./run.sh start [n1] [n2]    Start 2 peers (default: alice, bob)"
        echo "  ./run.sh list               List running peers + URLs"
        echo "  ./run.sh logs [name|s]      Follow logs"
        echo "  ./run.sh stop               Stop all"
        echo "  ./run.sh restart [n1] [n2]  Restart all"
        ;;
esac
