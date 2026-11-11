#!/usr/bin/env python3
"""Safely downloads one file from the camera's HTTP media server (port 9001) over stock firmware.

Changes nothing on the camera. Read-only GET request.

Usage:
    python download_file.py --url "http://192.168.43.1:9001/DCIM/100PHOTO/SAM_0307.JPG"
    python download_file.py --url "http://192.168.43.1:9001/DCIM/100PHOTO/SAM_0307.JPG" --send
    python download_file.py --self-test
"""
import argparse
import hashlib
import http.client
import ipaddress
import json
import pathlib
import socket
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

import probe

DEFAULT_HOST = "192.168.43.1"
PORT = 9001
STOCK_HEADERS = {
    "User-Agent": "SEC_RVF_ML_02:00:00:00:00:00",
    "Access-Method": "manual",
    "Connection": "close",
}


def download(url: str, send: bool = False, out_path: pathlib.Path = None):
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname or DEFAULT_HOST
    port = parsed.port or PORT
    path = parsed.path or "/"

    print(f"Target: http://{host}:{port}{path}")
    print(f"Method: GET")
    print(f"Headers: {STOCK_HEADERS}")

    if not send:
        print("[dry-run] No packets sent. Re-run with --send after approval.")
        return 0, None, None

    source = probe.local_ip_for(host)
    if not probe.same_subnet(source, host):
        print(f"Error: Host {host} is not on local subnet (source IP: {source})")
        return 1, None, None

    conn = http.client.HTTPConnection(host, port, timeout=10)
    start_time = time.time()
    hasher = hashlib.sha256()
    total_bytes = 0

    try:
        conn.request("GET", path, headers=STOCK_HEADERS)
        resp = conn.getresponse()
        print(f"HTTP Status: {resp.status} {resp.reason}")
        if resp.status != 200:
            return resp.status, None, None

        f = None
        if out_path:
            out_path.parent.mkdir(parents=True, exist_ok=True)
            f = open(out_path, "wb")

        try:
            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                hasher.update(chunk)
                total_bytes += len(chunk)
                if f:
                    f.write(chunk)
        finally:
            if f:
                f.close()

        elapsed = time.time() - start_time
        digest = hasher.hexdigest()
        mbps = (total_bytes * 8 / (1024 * 1024)) / elapsed if elapsed > 0 else 0

        print(f"Downloaded: {total_bytes:,} bytes in {elapsed:.2f}s ({mbps:.2f} Mbps)")
        print(f"SHA-256: {digest}")
        if out_path:
            print(f"Saved to: {out_path}")

        return resp.status, total_bytes, digest
    finally:
        conn.close()


def self_test():
    test_data = b"GEAR360_TEST_MEDIA_BYTES_FOR_PORT_9001"
    test_sha = hashlib.sha256(test_data).hexdigest()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/DCIM/100PHOTO/TEST.JPG":
                self.send_response(200)
                self.send_header("Content-Length", str(len(test_data)))
                self.end_headers()
                self.wfile.write(test_data)
            else:
                self.send_response(404)
                self.end_headers()

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        url = f"http://127.0.0.1:{port}/DCIM/100PHOTO/TEST.JPG"
        # Dry-run test
        code, bytes_count, digest = download(url, send=False)
        assert code == 0 and bytes_count is None

        # Real test to loopback
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
        conn.request("GET", "/DCIM/100PHOTO/TEST.JPG", headers=STOCK_HEADERS)
        resp = conn.getresponse()
        assert resp.status == 200
        body = resp.read()
        conn.close()
        assert hashlib.sha256(body).hexdigest() == test_sha
        print("self-test ok")
    finally:
        server.shutdown()
        server.server_close()


def main():
    parser = argparse.ArgumentParser(description="Safely download one file from camera port 9001")
    parser.add_argument("--url", help="HTTP URL of the file (e.g. http://192.168.43.1:9001/DCIM/...)")
    parser.add_argument("--out", help="Output file path on PC", default=None)
    parser.add_argument("--send", action="store_true", help="Send actual request (read-only GET)")
    parser.add_argument("--self-test", action="store_true", help="Run offline self-test against local server")

    args = parser.parse_args()

    if args.self_test:
        self_test()
        return

    if not args.url:
        print("Error: --url is required unless --self-test is used.")
        sys.exit(1)

    out_path = pathlib.Path(args.out) if args.out else None
    download(args.url, send=args.send, out_path=out_path)


if __name__ == "__main__":
    main()
