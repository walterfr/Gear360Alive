#!/usr/bin/env python3
"""Listens to UPnP NOTIFY events while user presses camera buttons on stock firmware (SM-R210).

Read-only measurement:
- Subscribes to /smp_5_ events.
- Listens for 60 seconds on port 8059.
- Prints all NOTIFY events in real time (especially RVF_LAST_PHOTO_URL_VALUE and RVF_BUTTON_ACTION).
- Cleanly sends UNSUBSCRIBE at the end.

Usage:
    python listen_button_events.py
    python listen_button_events.py --send
    python listen_button_events.py --self-test
"""
import argparse
import http.client
import http.server
import json
import pathlib
import sys
import threading
import time
import xml.etree.ElementTree as ET

import probe
import soap_query

EVENT_PATH = "/smp_5_"
CALLBACK_PORT = 8059
CALLBACK_PATH = "/evetSub"
SUBSCRIBE_TIMEOUT = "Second-300"
LISTEN_DURATION_S = 45


def subscribe_headers(source_ip, port):
    return {
        **probe.STOCK_HEADERS,
        "CALLBACK": f"<http://{source_ip}:{port}{CALLBACK_PATH}>",
        "NT": "upnp:event",
        "TIMEOUT": SUBSCRIBE_TIMEOUT,
    }


def event_request(method, headers, source_ip):
    conn = http.client.HTTPConnection(
        soap_query.HOST, soap_query.CONTROL_PORT, timeout=8, source_address=(source_ip, 0)
    )
    try:
        conn.request(method, EVENT_PATH, headers=headers)
        r = conn.getresponse()
        return {
            "status": r.status,
            "headers": dict(r.getheaders()),
            "body": r.read(probe.MAX_BODY_BYTES).decode("utf-8", "replace"),
        }
    except Exception as e:
        return {"error": type(e).__name__, "detail": str(e)[:200]}
    finally:
        conn.close()


def sid_of(response):
    return next((v for k, v in response.get("headers", {}).items() if k.lower() == "sid"), None)


def parse_notify_xml(xml_text):
    out = {}
    try:
        root = ET.fromstring(xml_text)
        for prop in root.iter():
            # Strip XML namespaces
            tag = prop.tag.split("}")[-1]
            if prop.text and prop.text.strip():
                out[tag] = prop.text.strip()
    except Exception:
        pass
    return out


def start_listener(source_ip, port, event_callback):
    class Listener(http.server.BaseHTTPRequestHandler):
        def do_NOTIFY(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(min(length, probe.MAX_BODY_BYTES)).decode("utf-8", "replace")
            parsed = parse_notify_xml(body)
            event_callback(parsed, body)
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer((source_ip, port), Listener)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server


def run_measurement(send=False, duration=LISTEN_DURATION_S):
    print(f"Target: http://{soap_query.HOST}:{soap_query.CONTROL_PORT}{EVENT_PATH}")
    print(f"Action: SUBSCRIBE (listen to button events for {duration} s) -> UNSUBSCRIBE")

    if not send:
        print("[dry-run] No packets sent. Re-run with --send after approval.")
        return

    source = probe.local_ip_for(soap_query.HOST)
    if not probe.same_subnet(source, soap_query.HOST) or not soap_query.healthy(source):
        print("Camera not reachable or not healthy; aborting.")
        return

    all_events = []

    def on_event(parsed, raw):
        ts = time.strftime("%H:%M:%S")
        print(f"\n[{ts}] === NOTIFY EVENT RECEIVED ===")
        for k, v in parsed.items():
            if k not in ("propertyset", "property"):
                print(f"   {k}: {v}")
        all_events.append({"time": ts, "parsed": parsed, "raw": raw})

    print(f"Starting local event server on {source}:{CALLBACK_PORT}...")
    server = start_listener(source, CALLBACK_PORT, on_event)

    sub_headers = subscribe_headers(source, CALLBACK_PORT)
    print("Sending SUBSCRIBE...")
    sub_resp = event_request("SUBSCRIBE", sub_headers, source)
    print(f"SUBSCRIBE status: {sub_resp.get('status')}")

    sid = sid_of(sub_resp)
    if not sid:
        print(f"Failed to get subscription SID: {sub_resp}")
        server.shutdown()
        return

    print(f"Subscribed! SID: {sid}")
    print(f"\n>>> Agora aperte o botão disparador físico da câmera para testar. <<<")
    print(f"Escutando eventos por {duration} segundos...\n")

    try:
        for remaining in range(duration, 0, -1):
            time.sleep(1)
            if remaining % 10 == 0:
                print(f"[{remaining}s restantes...]")
    finally:
        print("\nSending UNSUBSCRIBE...")
        unsub_headers = {**probe.STOCK_HEADERS, "SID": sid}
        unsub_resp = event_request("UNSUBSCRIBE", unsub_headers, source)
        print(f"UNSUBSCRIBE status: {unsub_resp.get('status', unsub_resp.get('error'))}")
        server.shutdown()

    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    report_file = out_dir / f"button_events_{time.strftime('%Y%m%d_%H%M%S')}.json"
    report_file.write_text(json.dumps(all_events, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nSalvo relatório com {len(all_events)} eventos em: {report_file}")


def self_test():
    test_xml = """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
        <e:property>
            <RVF_BUTTON_ACTION>ShutterClick</RVF_BUTTON_ACTION>
        </e:property>
        <e:property>
            <RVF_LAST_PHOTO_URL_VALUE>http://192.168.43.1:9001/DCIM/100PHOTO/360_0001.JPG</RVF_LAST_PHOTO_URL_VALUE>
        </e:property>
    </e:propertyset>"""
    parsed = parse_notify_xml(test_xml)
    assert parsed.get("RVF_BUTTON_ACTION") == "ShutterClick"
    assert "360_0001.JPG" in parsed.get("RVF_LAST_PHOTO_URL_VALUE")
    print("self-test ok")


def main():
    parser = argparse.ArgumentParser(description="Listen to camera button events")
    parser.add_argument("--send", action="store_true", help="Send SUBSCRIBE and listen to camera")
    parser.add_argument("--duration", type=int, default=LISTEN_DURATION_S, help="Listen duration in seconds")
    parser.add_argument("--self-test", action="store_true", help="Run local self-test")

    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        run_measurement(send=args.send, duration=args.duration)


if __name__ == "__main__":
    main()
