#!/usr/bin/env python3
"""Keeps one event subscription alive by renewing it, and never sends UNSUBSCRIBE. Dry run unless --send.

Why: on 2026-11-14 the camera turned off its "Connect to iOS" Wi-Fi 4-11 s after an UNSUBSCRIBE,
and it only came back from the camera's menu. The official app keeps its session while it is open.
This tests the standard UPnP renewal (SUBSCRIBE with the SID, no CALLBACK/NT) on the SM-R210.

Sequence:
1. SUBSCRIBE /smp_5_ with TIMEOUT Second-<timeout> (no SetOperationState, no Browse).
2. Renew at --renew-at seconds (default 90 and 180): SUBSCRIBE with SID and TIMEOUT only.
3. Stop renewing and keep listening until the subscription would have expired plus a minute,
   to see what the camera does when it simply lapses. No UNSUBSCRIBE, ever.
Run net_monitor.py alongside to see each layer of the Wi-Fi.

Usage:
    python renew_step.py
    python renew_step.py --send
    python renew_step.py --self-test
"""
import argparse
import json
import pathlib
import sys
import threading
import time

import listen_button_events as events
import probe
import shot_once
import soap_query

TIMEOUT_S = 120
RENEW_AT = (90, 180)
AFTER_LAPSE_S = 60


def renew_headers(sid, timeout_s):
    """A renewal: the SID and a new TIMEOUT, without CALLBACK or NT (UPnP Device Architecture 4.1.2)."""
    return {**probe.STOCK_HEADERS, "SID": sid, "TIMEOUT": f"Second-{timeout_s}"}


def subscribe_headers(source, timeout_s):
    return {**events.subscribe_headers(source, events.CALLBACK_PORT), "TIMEOUT": f"Second-{timeout_s}"}


def header(response, name):
    return next((v for k, v in response.get("headers", {}).items() if k.lower() == name.lower()), None)


def run_session(source, timeout_s, renew_at, after_lapse_s, clock=time.monotonic, sleep=time.sleep):
    report = {"events": [], "renewals": []}

    def on_event(parsed, raw):
        values = {k: v for k, v in parsed.items() if k not in ("propertyset", "property") and v not in ("NULL", "")}
        stamp = time.strftime("%H:%M:%S")
        report["events"].append({"at": stamp, "values": values})
        print(f"[{stamp}] NOTIFY {values if len(values) < 6 else '(whole state)'}")

    server = events.start_listener(source, events.CALLBACK_PORT, on_event)
    try:
        sub = events.event_request("SUBSCRIBE", subscribe_headers(source, timeout_s), source)
        sid = events.sid_of(sub)
        report["subscribe"] = {"at": time.strftime("%H:%M:%S"), "status": sub.get("status", sub.get("error")),
                               "timeout": header(sub, "TIMEOUT"), "sid": bool(sid)}
        print(f"SUBSCRIBE: {report['subscribe']}")
        if not sid:
            return report
        started = clock()
        last = 0
        for at in renew_at:
            sleep(max(0, at - (clock() - started)))
            answer = events.event_request("SUBSCRIBE", renew_headers(sid, timeout_s), source)
            entry = {"at": time.strftime("%H:%M:%S"), "after_s": round(clock() - started),
                     "status": answer.get("status", answer.get("error")), "timeout": header(answer, "TIMEOUT"),
                     "same_sid": events.sid_of(answer) in (None, sid), "healthy": soap_query.healthy(source)}
            report["renewals"].append(entry)
            print(f"renewal: {entry}")
            last = at
            if answer.get("status") != 200:
                break
        # Not renewed any more: it lapses at last + timeout. Watch past that, then just stop listening.
        lapse = last + timeout_s
        report["lapse_after_s"] = lapse
        print(f"no more renewals; the subscription lapses about {lapse} s after it began. Listening until then + {after_lapse_s} s")
        sleep(max(0, lapse + after_lapse_s - (clock() - started)))
        report["healthy_at_end"] = soap_query.healthy(source)
    finally:
        server.shutdown()
        server.server_close()
    return report


def main(send, timeout_s, renew_at):
    print(f"to http://{soap_query.HOST}:{soap_query.CONTROL_PORT}/smp_5_:")
    print(f"  SUBSCRIBE with CALLBACK <http://<pc>:{events.CALLBACK_PORT}{events.CALLBACK_PATH}>, NT upnp:event, TIMEOUT Second-{timeout_s}")
    for at in renew_at:
        print(f"  at {at}s, once: SUBSCRIBE with SID: <sid>, TIMEOUT Second-{timeout_s} (no CALLBACK, no NT)")
    lapse = (renew_at[-1] if renew_at else 0) + timeout_s
    print(f"  no UNSUBSCRIBE: the subscription lapses at about {lapse}s; listening until {lapse + AFTER_LAPSE_S}s")
    print("  no SetOperationState, no Browse, no capture")
    if not send:
        print("dry run: nothing sent")
        return 0
    source = probe.local_ip_for(soap_query.HOST)
    if not probe.same_subnet(source, soap_query.HOST):
        print(f"not on the camera network (source {source}); nothing sent")
        return 2
    if not soap_query.healthy(source):
        print("camera does not answer; nothing sent")
        return 3
    before = shot_once.read_state(source)
    print(f"before: {before}")
    report = run_session(source, timeout_s, renew_at, AFTER_LAPSE_S)
    report["before"] = before
    report["after"] = shot_once.read_state(source) if report.get("healthy_at_end") else None
    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"renew-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"answers at end: {report.get('healthy_at_end')}; after: {report['after']}")
    print(f"report: {path}")
    return 0 if report.get("healthy_at_end") else 1


def self_test():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    seen = []

    class Fake(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_SUBSCRIBE(self):
            seen.append({k: self.headers.get(k) for k in ("SID", "CALLBACK", "NT", "TIMEOUT")})
            self.send_response(200)
            self.send_header("SID", "uuid:fake")
            self.send_header("TIMEOUT", self.headers.get("TIMEOUT"))
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_UNSUBSCRIBE(self):
            seen.append("UNSUBSCRIBE")
            self.send_response(200)
            self.end_headers()

        def log_message(self, *args):
            pass

    camera = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
    threading.Thread(target=camera.serve_forever, daemon=True).start()
    soap_query.HOST, soap_query.CONTROL_PORT = "127.0.0.1", camera.server_address[1]
    events.CALLBACK_PORT = 0  # any free port for the listener

    now = [0.0]
    report = run_session("127.0.0.1", 120, (90, 180), 60, clock=lambda: now[0],
                         sleep=lambda s: now.__setitem__(0, now[0] + s))
    assert report["subscribe"]["status"] == 200 and report["subscribe"]["timeout"] == "Second-120"
    assert seen[0]["CALLBACK"] and seen[0]["NT"] == "upnp:event" and seen[0]["SID"] is None
    renewals = seen[1:]
    assert len(renewals) == 2 and all(r["SID"] == "uuid:fake" and r["CALLBACK"] is None and r["NT"] is None
                                      and r["TIMEOUT"] == "Second-120" for r in renewals), renewals
    assert [r["after_s"] for r in report["renewals"]] == [90, 180]
    assert report["lapse_after_s"] == 300 and now[0] == 360
    assert "UNSUBSCRIBE" not in seen
    camera.shutdown()
    camera.server_close()
    print("self-test ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--timeout", type=int, default=TIMEOUT_S, help="seconds asked in TIMEOUT")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        sys.exit(0)
    sys.exit(main(args.send, args.timeout, RENEW_AT))
