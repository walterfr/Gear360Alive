#!/usr/bin/env python3
"""Watches the camera's Wi-Fi layer by layer, read only, to tell apart the kinds of "disconnected".

Every 2 s it records:
  visible  - the camera's network is in the PC's Wi-Fi list (netsh; the list can lag a scan)
  joined   - the PC is on that network, with an address on 192.168.43.x
  ping     - the camera answers ICMP
  port     - TCP 7676 (UPnP) accepts a connection
  service  - GET /smp_2_ answers 200 (every 10 s; the same read the other scripts use)
Only changes are printed, with the time, plus a line every minute. Everything goes to
reports/net-monitor-<time>.jsonl. It never sends SOAP, never joins or leaves a network.

Usage:
    python net_monitor.py                 (20 min)
    python net_monitor.py --minutes 30
    python net_monitor.py --self-test
"""
import argparse
import json
import pathlib
import re
import socket
import subprocess
import sys
import time

import probe

HOST = "192.168.43.1"
SSID = "Gear 360(7F97)"
EVERY_S = 2
SERVICE_EVERY = 5  # ticks: GET /smp_2_ every 10 s
HEARTBEAT_S = 60
LAYERS = ("visible", "joined", "ping", "port", "service")


def decode(raw):
    for encoding in ("utf-8", "cp850", "latin1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin1", "replace")


def netsh(*args):
    try:
        out = subprocess.run(["netsh", "wlan", "show", *args], capture_output=True, timeout=8)
        return decode(out.stdout)
    except (OSError, subprocess.SubprocessError):
        return ""


def interface_info(text):
    """SSID, state and signal of the Wi-Fi interface, from `netsh wlan show interfaces` (any language)."""
    info = {}
    for line in text.splitlines():
        m = re.match(r"^\s+(SSID|Estado|State|Sinal|Signal)\s*:\s*(.*?)\s*$", line)
        if m:
            key = {"Estado": "state", "State": "state", "Sinal": "signal", "Signal": "signal", "SSID": "ssid"}[m.group(1)]
            info.setdefault(key, m.group(2))
    return info


def visible(text, ssid):
    return any(re.match(rf"^SSID \d+\s*:\s*{re.escape(ssid)}\s*$", line.strip()) for line in text.splitlines())


def ping(host):
    try:
        out = subprocess.run(["ping", "-n", "1", "-w", "1000", host], capture_output=True, timeout=5)
        return b"TTL=" in out.stdout.upper()
    except (OSError, subprocess.SubprocessError):
        return False


def port_open(host, port, source):
    try:
        with socket.create_connection((host, port), 1.5, (source, 0)):
            return True
    except OSError:
        return False


def changes(previous, current):
    """Layers whose value changed, as 'layer: old -> new'."""
    return [f"{k}: {previous.get(k)} -> {current.get(k)}" for k in LAYERS if previous.get(k) != current.get(k)]


def sample(tick, last_service):
    iface = interface_info(netsh("interfaces"))
    joined_ssid = iface.get("ssid") == SSID
    source = probe.local_ip_for(HOST)
    on_net = joined_ssid and probe.same_subnet(source, HOST)
    state = {
        "visible": visible(netsh("networks"), SSID) or joined_ssid,
        "joined": on_net,
        "ping": on_net and ping(HOST),
        "port": on_net and port_open(HOST, 7676, source),
        "service": last_service,
    }
    if not state["port"]:
        state["service"] = False
    elif tick % SERVICE_EVERY == 0:
        state["service"] = probe.http_get(HOST, 7676, "/smp_2_", source, probe.STOCK_HEADERS).get("status") == 200
    return state, iface


def main(minutes):
    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"net-monitor-{time.strftime('%Y%m%d-%H%M%S')}.jsonl"
    print(f"watching {SSID} / {HOST} for {minutes} min, read only; log: {path}")
    previous, last_beat, tick = {}, 0.0, 0
    until = time.monotonic() + minutes * 60
    with path.open("a", encoding="utf-8") as log:
        try:
            while time.monotonic() < until:
                started = time.monotonic()
                state, iface = sample(tick, previous.get("service", False))
                stamp = time.strftime("%H:%M:%S")
                diff = changes(previous, state)
                log.write(json.dumps({"at": stamp, **state, "signal": iface.get("signal"), "pc_ssid": iface.get("ssid"),
                                      "pc_state": iface.get("state")}, ensure_ascii=False) + "\n")
                log.flush()
                if diff or time.monotonic() - last_beat >= HEARTBEAT_S:
                    flags = " ".join(f"{k}={'1' if state[k] else '0'}" for k in LAYERS)
                    print(f"[{stamp}] {flags} signal={iface.get('signal')}" + (f"  CHANGED {'; '.join(diff)}" if diff and previous else ""))
                    last_beat = time.monotonic()
                previous = state
                tick += 1
                time.sleep(max(0.0, EVERY_S - (time.monotonic() - started)))
        except KeyboardInterrupt:
            print("stopped")
    print(f"log: {path}")
    return 0


def self_test():
    pt = """
    Nome                   : Wi-Fi 2
    Estado                 : conectado
    SSID                   : Gear 360(7F97)
    BSSID                  : f4:c2:48:9c:7f:97
    Sinal                  : 96%
    """
    en = """
    Name                   : Wi-Fi 2
    State                  : disconnected
    """
    assert interface_info(pt) == {"state": "conectado", "ssid": "Gear 360(7F97)", "signal": "96%"}, interface_info(pt)
    assert interface_info(en) == {"state": "disconnected"}
    networks = "Interface name : Wi-Fi 2\nSSID 9 : Gear 360(7F97)\n    Network type : Infrastructure\nSSID 10 : Gear 360(7F97).OSC\n"
    assert visible(networks, "Gear 360(7F97)")
    assert visible(networks, "Gear 360(7F97).OSC")
    assert not visible("SSID 1 : Gear 360(7F97).OSC\n", "Gear 360(7F97)")
    a = {"visible": True, "joined": True, "ping": True, "port": True, "service": True}
    b = dict(a, port=False, service=False)
    assert changes(a, b) == ["port: True -> False", "service: True -> False"]
    assert changes(a, a) == []
    print("self-test ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--minutes", type=float, default=20)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        sys.exit(0)
    sys.exit(main(args.minutes))
