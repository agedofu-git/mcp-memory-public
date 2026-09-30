#!/usr/bin/env python3
"""Serve the MCP Memory vector graph to a Tailscale device with automatic refresh.

The HTTP endpoints are read-only. Bind only to loopback or a Tailscale IPv4
address; do not expose private memory text on a public network interface.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace
from urllib.parse import urlsplit

import vector_graph


TAILSCALE_RANGE = ipaddress.ip_network("100.64.0.0/10")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="loopback or this VM's Tailscale IPv4 address")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--interval", type=float, default=5.0, help="seconds between database checks (1-60)")
    parser.add_argument("--container", default="mcp-memory-db-1")
    parser.add_argument("--db-user", default="memory")
    parser.add_argument("--db-name", default="memory")
    parser.add_argument("--limit", type=int, default=300)
    parser.add_argument("--neighbors", type=int, default=3)
    args = parser.parse_args()
    try:
        address = ipaddress.ip_address(args.host)
    except ValueError:
        parser.error("--host must be an IPv4 address")
    if not isinstance(address, ipaddress.IPv4Address) or not (address.is_loopback or address in TAILSCALE_RANGE):
        parser.error("--host must be loopback or an address in Tailscale's 100.64.0.0/10 range")
    if not address.is_loopback:
        try:
            own_tailscale_ip = subprocess.check_output(["tailscale", "ip", "-4"], text=True, timeout=5).strip()
        except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            parser.error("could not verify this VM's Tailscale IPv4 address")
        if args.host != own_tailscale_ip:
            parser.error("--host must match this VM's Tailscale IPv4 address")
    if not 1024 <= args.port <= 65535:
        parser.error("--port must be between 1024 and 65535")
    if not 1 <= args.interval <= 60:
        parser.error("--interval must be between 1 and 60 seconds")
    if not 1 <= args.limit <= 500:
        parser.error("--limit must be between 1 and 500")
    if not 1 <= args.neighbors <= 10:
        parser.error("--neighbors must be between 1 and 10")
    return args


class GraphCache:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.lock = threading.Lock()
        self.next_check = 0.0
        self.source_hash = ""
        self.json_bytes = b""
        self.html_bytes = b""
        self.etag = ""

    def get(self) -> tuple[bytes, bytes, str]:
        with self.lock:
            if not self.json_bytes or time.monotonic() >= self.next_check:
                try:
                    database_args = SimpleNamespace(
                        container=self.args.container,
                        db_user=self.args.db_user,
                        db_name=self.args.db_name,
                        limit=self.args.limit,
                        active_only=False,
                    )
                    snapshot = vector_graph.read_database(database_args)
                    source = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
                    source_hash = hashlib.sha256(source).hexdigest()
                    if source_hash != self.source_hash:
                        data = vector_graph.prepare(snapshot, self.args.neighbors)
                        self.json_bytes = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                        self.html_bytes = vector_graph.render_html(data, live_url="/api/graph").encode("utf-8")
                        self.etag = '"' + hashlib.sha256(self.json_bytes).hexdigest() + '"'
                        self.source_hash = source_hash
                    self.next_check = time.monotonic() + self.args.interval
                except Exception:
                    self.next_check = time.monotonic() + min(self.args.interval, 2.0)
                    raise
            return self.json_bytes, self.html_bytes, self.etag


def handler_for(cache: GraphCache):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            path = urlsplit(self.path).path
            if path not in ("/", "/index.html", "/api/graph", "/healthz"):
                self.send_error(404, "Not found")
                return
            try:
                json_bytes, html_bytes, etag = cache.get()
            except Exception as exc:
                print(f"graph refresh failed: {type(exc).__name__}: {exc}", flush=True)
                self.send_response(503)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if path == "/api/graph" and self.headers.get("If-None-Match") == etag:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            if path == "/api/graph":
                body, content_type = json_bytes, "application/json; charset=utf-8"
            elif path == "/healthz":
                body, content_type = b"ok\n", "text/plain; charset=utf-8"
            else:
                body, content_type = html_bytes, "text/html; charset=utf-8"
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Content-Type-Options", "nosniff")
            if path == "/api/graph":
                self.send_header("ETag", etag)
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: object) -> None:
            pass

    return Handler


def main() -> int:
    args = arguments()
    cache = GraphCache(args)
    cache.get()  # Fail at startup if the database or embedding data is unavailable.
    server = ThreadingHTTPServer((args.host, args.port), handler_for(cache))
    print(f"MCP Memory vector graph listening on http://{args.host}:{args.port}/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
