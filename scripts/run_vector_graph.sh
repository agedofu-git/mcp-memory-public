#!/bin/sh
# Start the read-only graph on this VM's Tailscale address and restart it if needed.
set -u
umask 077

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
cache_root=${XDG_CACHE_HOME:-"$HOME/.cache"}/mcp-memory
mkdir -p "$cache_root" || exit 1
exec >> "$cache_root/vector-graph.log" 2>&1
exec 9> "$cache_root/vector-graph.lock"
flock -n 9 || exit 0
cd "$repo_root" || exit 1

child=
stop() {
    if [ -n "$child" ]; then kill "$child" 2>/dev/null || true; fi
    exit 0
}
trap stop INT TERM

while :; do
    tailscale_ip=$(tailscale ip -4 2>/dev/null || true)
    db_container=${VECTOR_GRAPH_CONTAINER:-$(docker compose ps -q db 2>/dev/null || true)}
    if [ -n "$tailscale_ip" ] && [ -n "$db_container" ]; then
        python3 -B scripts/vector_graph_server.py --host "$tailscale_ip" --port "${VECTOR_GRAPH_PORT:-8765}" --container "$db_container" &
        child=$!
        wait "$child"
        result=$?
        child=
        printf '%s graph server exited (%s); retrying in 10 seconds\n' "$(date -Is)" "$result"
    fi
    sleep 10
done
