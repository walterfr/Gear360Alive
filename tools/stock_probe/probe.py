#!/usr/bin/env python3
"""Read-only network probe for a Samsung Gear 360 (2017, SM-R210) on stock firmware.

It only opens TCP connections, sends the fixed GET requests in REQUESTS, sends one SSDP
M-SEARCH and fetches a LOCATION it returns, if on the camera's port 7676.
It never sends POST or SOAP, never opens the live stream and never writes to the camera.

Usage:
    python probe.py --mode ios
    python probe.py --mode android
    python probe.py --mode streetview
    python probe.py --self-test
"""
import argparse
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

CANDIDATE_HOSTS = ("192.168.43.1", "192.168.107.1")
TCP_PORTS = (80, 7676, 7679, 9001)
REQUESTS = ((80, "/osc/info"), (80, "/osc/state"), (7676, "/smp_2_"))
TIMEOUT_S = 3
MAX_BODY_BYTES = 64 * 1024
MODES = ("ios", "android", "streetview")
# Headers of Samsung's app (Revive360 docs/00_overview.md). The address is a placeholder.
STOCK_HEADERS = {"User-Agent": "SEC_RVF_ML_02:00:00:00:00:00", "Access-Method": "manual", "Connection": "close"}


def local_ip_for(host):
    """The local address the OS would use to reach host. Sends no packets."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((host, 9))
        return s.getsockname()[0]


def same_subnet(local_ip, host):
    """Stops a host being reached through the cable: the camera must be on the local /24."""
    return ipaddress.ip_address(local_ip) in ipaddress.ip_network(f"{host}/24", strict=False)


def validate_host(host):
    """Only literal private 192.168.x.x addresses may be probed."""
    if ipaddress.ip_address(host) not in ipaddress.ip_network("192.168.0.0/16"):
        raise ValueError(f"not a 192.168.x.x address: {host}")
    return host


def tcp_open(host, port, source_ip):
    start = time.monotonic()
    try:
        with socket.create_connection((host, port), TIMEOUT_S, (source_ip, 0)):
            return {"open": True, "ms": round((time.monotonic() - start) * 1000)}
    except OSError as e:
        return {"open": False, "error": type(e).__name__}


def parse_location(ssdp_response, host):
    """(port, path) of the response's LOCATION, only if it is the camera's port 7676."""
    for line in ssdp_response.splitlines():
        name, _, value = line.partition(":")
        if name.strip().lower() == "location":
            url = urllib.parse.urlsplit(value.strip())
            if url.hostname == host and url.port == 7676 and url.path:
                return url.port, url.path
    return None


def ssdp_search(source_ip):
    """Standard UPnP discovery: one M-SEARCH on the Wi-Fi interface, then collect replies."""
    message = (
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
        'MAN: "ssdp:discover"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n'
    ).encode()
    replies = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind((source_ip, 0))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(source_ip))
        s.settimeout(TIMEOUT_S)
        s.sendto(message, ("239.255.255.250", 1900))
        try:
            while len(replies) < 20:
                data, addr = s.recvfrom(4096)
                replies.append({"from": addr[0], "text": data.decode("utf-8", "replace")})
        except socket.timeout:
            pass
    return replies


def http_get(host, port, path, source_ip, headers=None):
    conn = http.client.HTTPConnection(host, port, timeout=TIMEOUT_S, source_address=(source_ip, 0))
    try:
        conn.request("GET", path, headers=headers or {"User-Agent": "Gear360Alive-probe", "Connection": "close"})
        r = conn.getresponse()
        body = r.read(MAX_BODY_BYTES)
        return {
            "status": r.status,
            "headers": dict(r.getheaders()),
            "body": body.decode("utf-8", "replace"),
            "truncated": len(body) == MAX_BODY_BYTES,
        }
    except (OSError, http.client.HTTPException) as e:
        return {"error": type(e).__name__, "detail": str(e)[:200]}
    finally:
        conn.close()


def probe_host(host, source_ip, port_map=None):
    """port_map only redirects ports for the self-test."""
    mapped = lambda port: (port_map or {}).get(port, port)
    ports = {str(p): tcp_open(host, mapped(p), source_ip) for p in TCP_PORTS}
    requests = {}
    for port, path in REQUESTS:
        if ports[str(port)]["open"]:
            requests[f"{port}{path}"] = http_get(host, mapped(port), path, source_ip)
    if ports["7676"]["open"]:
        for path in ("/smp_2_", "/smp_3_", "/smp_6_"):  # device description and service action lists
            requests[f"7676{path} stock headers"] = http_get(host, mapped(7676), path, source_ip, STOCK_HEADERS)
    return {"host": host, "source_ip": source_ip, "ports": ports, "requests": requests}


def verdict(results):
    open_ports = {int(p) for r in results for p, v in r["ports"].items() if v["open"]}
    if 7676 in open_ports:
        return "UPNP_PORT_OPEN"
    if 80 in open_ports:
        return "OSC_PORT_OPEN"
    return "NO_CAMERA_PORTS"


def run(mode, hosts, out_dir):
    results = []
    skipped = []
    for host in hosts:
        source = local_ip_for(host)
        if same_subnet(source, host):
            result = probe_host(host, source)
            result["ssdp"] = ssdp_search(source)
            result["locations"] = {}
            for reply in result["ssdp"]:
                location = parse_location(reply["text"], host) if reply["from"] == host else None
                if location and f"{location[0]}{location[1]}" not in result["locations"]:
                    result["locations"][f"{location[0]}{location[1]}"] = http_get(host, location[0], location[1], source)
            results.append(result)
        else:
            skipped.append({"host": host, "route_source_ip": source})
    report = {
        "mode": mode,
        "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "verdict": verdict(results) if results else "NOT_ON_CAMERA_NETWORK",
        "results": results,
        "skipped": skipped,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{mode}-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"verdict: {report['verdict']}")
    for r in results:
        states = ", ".join(f"{p}={'open' if v['open'] else 'closed'}" for p, v in r["ports"].items())
        print(f"{r['host']} from {r['source_ip']}: {states}")
    print(f"report: {path}")
    return report


def self_test():
    seen = []

    class Fake(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _record(self):
            seen.append((self.command, self.path))
            self.send_response(200)
            self.end_headers()
            if self.command == "GET":
                self.wfile.write(b"x" * (MAX_BODY_BYTES + 10))

        do_GET = do_POST = do_PUT = do_DELETE = _record

    servers = [HTTPServer(("127.0.0.1", 0), Fake) for _ in range(2)]
    for s in servers:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    closed = socket.socket()
    closed.bind(("127.0.0.1", 0))
    closed_port = closed.getsockname()[1]
    closed.close()
    port_map = {
        80: servers[0].server_port,
        7676: servers[1].server_port,
        7679: closed_port,
        9001: closed_port,
    }
    r = probe_host("127.0.0.1", "127.0.0.1", port_map)
    for s in servers:
        s.shutdown()
        s.server_close()

    expected = ["/osc/info", "/osc/state", "/smp_2_", "/smp_2_", "/smp_3_", "/smp_6_"]
    assert sorted(seen) == [("GET", p) for p in expected], seen
    ok = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.43.1:7676/smp_2_\r\n"
    assert parse_location(ok, "192.168.43.1") == (7676, "/smp_2_")
    assert parse_location(ok.replace(":7676", ":8080"), "192.168.43.1") is None
    assert parse_location(ok, "192.168.107.1") is None
    assert r["ports"]["7676"]["open"] and not r["ports"]["7679"]["open"]
    assert r["requests"]["80/osc/info"]["truncated"]
    assert verdict([r]) == "UPNP_PORT_OPEN"
    assert verdict([{"ports": {"80": {"open": True}, "7676": {"open": False}}}]) == "OSC_PORT_OPEN"
    assert verdict([{"ports": {"80": {"open": False}}}]) == "NO_CAMERA_PORTS"
    assert same_subnet("192.168.43.20", "192.168.43.1")
    assert not same_subnet("192.168.1.50", "192.168.43.1")
    try:
        validate_host("8.8.8.8")
        raise AssertionError("public host accepted")
    except ValueError:
        pass
    print("self-test ok")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mode", choices=MODES)
    parser.add_argument("--host", type=validate_host, help="camera address, if not a known one")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.mode:
        parser.error("--mode is required")
    hosts = (args.host,) if args.host else CANDIDATE_HOSTS
    report = run(args.mode, hosts, pathlib.Path(__file__).resolve().parent / "reports")
    return 0 if report["verdict"] != "NOT_ON_CAMERA_NETWORK" else 2


if __name__ == "__main__":
    sys.exit(main())
