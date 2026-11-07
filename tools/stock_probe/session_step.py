#!/usr/bin/env python3
"""One step of the official session sequence on a stock Gear 360 (SM-R210). Dry run unless --send.

Step subscribe: SUBSCRIBE to the ContentDirectory events URL, collect NOTIFY, UNSUBSCRIBE.
Step rvf: the same, with one SetOperationState changeToRVF inside the subscription.
Step info: rvf, then one GetInfomation. Actions are never retried, and a failure stops the rest.
Step stream: info, then reads the live stream for a few seconds. Keeps only counts, never the picture.
UNSUBSCRIBE closes the session: the camera's ports then close for about a minute.

Usage:
    python session_step.py --step stream
    python session_step.py --step stream --send
    python session_step.py --self-test
"""
import argparse
import http.client
import http.server
import json
import pathlib
import sys
import threading
import time

import probe
import shot_once
import soap_query

EVENT_PATH = "/smp_5_"
CALLBACK_PORT = 8059
CALLBACK_PATH = "/evetSub"
# Short on purpose: if UNSUBSCRIBE fails, the subscription expires by itself.
SUBSCRIBE_TIMEOUT = "Second-300"
LISTEN_SECONDS = 15
SETTLE_SECONDS = 3
RECOVERY_SECONDS = 180
CHANGE_TO_RVF = ("SetOperationState", {"StateEvent": "changeToRVF"})
GET_INFO = ("GetInfomation", {})  # No input argument on the R210: its action list declares none.
# Only these actions can be sent from here.
STEPS = {
    "subscribe": (),
    "rvf": (CHANGE_TO_RVF,),
    "info": (CHANGE_TO_RVF, GET_INFO),
    "stream": (CHANGE_TO_RVF, GET_INFO),
}
STREAM_PORT = 7679
STREAM_PATH = "/livestream_high.avi"
STREAM_HEADERS = {"User-Agent": "Android Linux", "Connection": "Keep-Alive"}  # As the official client
STREAM_SECONDS = 5
STREAM_MAX_SECONDS = 15
# Kept in memory only, to describe the first chunks; never written to disk.
HEADER_BYTES = 256 * 1024
LAYOUT_CHUNKS = 12
CHUNKS = (b"00VD", b"00AU", b"00VR")


def subscribe_headers(source_ip, callback_port):
    return {
        **probe.STOCK_HEADERS,
        "CALLBACK": f"<http://{source_ip}:{callback_port}{CALLBACK_PATH}>",
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
    except (OSError, http.client.HTTPException) as e:
        return {"error": type(e).__name__, "detail": str(e)[:200]}
    finally:
        conn.close()


def sid_of(response):
    return next((v for k, v in response.get("headers", {}).items() if k.lower() == "sid"), None)


def start_listener(source_ip, port, notifies):
    class Listener(http.server.BaseHTTPRequestHandler):
        def do_NOTIFY(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(min(length, probe.MAX_BODY_BYTES))
            notifies.append(
                {
                    "at": time.strftime("%H:%M:%S"),
                    "path": self.path,
                    "headers": dict(self.headers),
                    "body": body.decode("utf-8", "replace"),
                }
            )
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer((source_ip, port), Listener)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def post_action(source_ip, action, arguments):
    conn = http.client.HTTPConnection(
        soap_query.HOST, soap_query.CONTROL_PORT, timeout=15, source_address=(source_ip, 0)
    )
    started = time.monotonic()
    try:
        conn.request(
            "POST",
            soap_query.CONTROL_PATH,
            soap_query.envelope(action, arguments).encode("utf-8"),
            {
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPACTION": f'"{soap_query.SERVICE}#{action}"',
                "Connection": "close",
            },
        )
        r = conn.getresponse()
        return {
            "status": r.status,
            "seconds": round(time.monotonic() - started, 2),
            "body": r.read(probe.MAX_BODY_BYTES).decode("utf-8", "replace"),
        }
    except (OSError, http.client.HTTPException) as e:
        return {"error": type(e).__name__, "detail": str(e)[:200], "seconds": round(time.monotonic() - started, 2)}
    finally:
        conn.close()


def stream_header(header):
    """Magic and size from the TTTS header, read as LiveViewDecoder does."""
    found = {"magic": header[:4].decode("latin1")}
    vid = header.find(b"VID0")
    if vid >= 0 and len(header) >= vid + 24:
        found["width"] = int.from_bytes(header[vid + 16:vid + 20], "big")
        found["height"] = int.from_bytes(header[vid + 20:vid + 24], "big")
    return found


def nal_types(payload):
    """HEVC NAL unit types after Annex B start codes; empty when the payload has none."""
    types = []
    i = payload.find(b"\x00\x00\x01")
    while i >= 0 and i + 3 < len(payload) and len(types) < 16:
        types.append((payload[i + 3] >> 1) & 0x3F)
        i = payload.find(b"\x00\x00\x01", i + 3)
    return types


def chunk_layout(data):
    """The first chunks after `movi`, read as LiveViewDecoder does: id(4) size(4) timestamp(8) data.
    Keeps only ids, sizes, timestamps, the first 8 bytes and the NAL types: no picture."""
    start = data.find(b"movi")
    if start < 0:
        return {"error": "no movi in the first bytes"}
    pos = start + 4
    chunks = []
    while len(chunks) < LAYOUT_CHUNKS and pos + 16 <= len(data):
        cid = data[pos:pos + 4]
        size = int.from_bytes(data[pos + 4:pos + 8], "big")
        stamp = int.from_bytes(data[pos + 8:pos + 16], "big")
        payload = data[pos + 16:pos + 16 + size]
        chunks.append({
            "id": cid.decode("latin1"),
            "size": size,
            "timestamp": stamp,
            "first": payload[:8].hex(),
            "nal": nal_types(payload) if cid == b"00VD" else None,
            "complete": len(payload) == size,
        })
        if len(payload) < size:
            break
        pos += 16 + size
    return {"header_bytes": start + 4, "chunks": chunks}


def read_stream(source_ip, seconds=STREAM_SECONDS):
    """Reads and discards the stream, then closes the connection. Counts are approximate: a tag can occur in data."""
    result = {"bytes": 0, "chunks": {c.decode(): 0 for c in CHUNKS}}
    conn = http.client.HTTPConnection(soap_query.HOST, STREAM_PORT, timeout=10, source_address=(source_ip, 0))
    started = time.monotonic()
    first = None
    header = b""
    tail = b""
    try:
        conn.request("GET", STREAM_PATH, headers=STREAM_HEADERS)
        r = conn.getresponse()
        result["status"] = r.status
        result["content_type"] = r.getheader("Content-Type")
        if r.status not in (200, 206):
            return result
        while time.monotonic() - started < STREAM_MAX_SECONDS:
            if first is not None and time.monotonic() - first >= seconds:
                break
            data = r.read1(65536)
            if not data:
                result["ended_by_camera"] = True
                break
            if first is None:
                first = time.monotonic()
                result["first_byte_seconds"] = round(first - started, 2)
            result["bytes"] += len(data)
            if len(header) < HEADER_BYTES:
                header = (header + data)[:HEADER_BYTES]
            window = tail + data
            for chunk in CHUNKS:
                result["chunks"][chunk.decode()] += window.count(chunk)
            tail = data[-3:]
    except (OSError, http.client.HTTPException) as e:
        result["error"] = type(e).__name__
        result["detail"] = str(e)[:200]
    finally:
        conn.close()
        if first is not None:
            result["read_seconds"] = round(time.monotonic() - first, 2)
        result.update(stream_header(header))
        if header:
            result["layout"] = chunk_layout(header)
    return result


def wait_recovered(source_ip, limit=RECOVERY_SECONDS):
    """Seconds until the camera answers again, or None."""
    started = time.monotonic()
    while time.monotonic() - started < limit:
        if soap_query.healthy(source_ip):
            return round(time.monotonic() - started, 1)
        time.sleep(5)
    return None


def run_session(
    source_ip,
    step,
    callback_port=CALLBACK_PORT,
    settle=SETTLE_SECONDS,
    listen_seconds=LISTEN_SECONDS,
    stream_seconds=STREAM_SECONDS,
):
    """Always tries UNSUBSCRIBE once a SID exists, even if the camera looks unwell."""
    report = {"step": step, "notifies": [], "actions": []}
    server = start_listener(source_ip, callback_port, report["notifies"])
    try:
        report["subscribe"] = event_request("SUBSCRIBE", subscribe_headers(source_ip, callback_port), source_ip)
        sid = sid_of(report["subscribe"])
        report["sid"] = sid
        for action, arguments in STEPS[step] if sid else ():
            time.sleep(settle)
            if not soap_query.healthy(source_ip):
                report["stopped_before"] = action
                break
            result = post_action(source_ip, action, arguments)
            report["actions"].append({"action": action, **result})
            if result.get("status") != 200:
                break
        else:
            if step == "stream" and sid:
                if STREAM_PATH in report["actions"][-1]["body"]:
                    time.sleep(settle)
                    report["stream"] = read_stream(source_ip, stream_seconds)
                else:
                    report["stream"] = {"skipped": f"{STREAM_PATH} not offered by the camera"}
        if sid:
            time.sleep(listen_seconds)
        report["healthy_before_unsubscribe"] = soap_query.healthy(source_ip)
        if sid:
            report["unsubscribe"] = event_request("UNSUBSCRIBE", {**probe.STOCK_HEADERS, "SID": sid}, source_ip)
            time.sleep(1)
        report["recovered_after_seconds"] = wait_recovered(source_ip)
    finally:
        server.shutdown()
        server.server_close()
    return report


def main(step, send):
    source = probe.local_ip_for(soap_query.HOST)
    print(f"SUBSCRIBE {EVENT_PATH} HTTP/1.1  to {soap_query.HOST}:{soap_query.CONTROL_PORT}")
    for k, v in subscribe_headers(source, CALLBACK_PORT).items():
        print(f"  {k}: {v}")
    for action, arguments in STEPS[step]:
        print(f"after {SETTLE_SECONDS}s, once, POST {soap_query.CONTROL_PATH}:")
        print(f"  {soap_query.envelope(action, arguments)}")
    if step == "stream":
        print(f"after {SETTLE_SECONDS}s, once, GET {STREAM_PATH} HTTP/1.1  to port {STREAM_PORT}, {STREAM_HEADERS}")
        print(f"  read {STREAM_SECONDS}s (at most {STREAM_MAX_SECONDS}s), keep counts only, then close the connection")
    print(f"then listen {LISTEN_SECONDS}s on {source}:{CALLBACK_PORT}{CALLBACK_PATH}, then UNSUBSCRIBE with the SID")
    if not send:
        print("dry run: nothing sent")
        return 0
    if not probe.same_subnet(source, soap_query.HOST):
        print(f"not on the camera network (source {source}); nothing sent")
        return 2
    if not soap_query.healthy(source):
        print("camera does not answer; nothing sent")
        return 3
    before = shot_once.read_state(source)
    print(f"before: {before}")
    report = run_session(source, step)
    report["before"] = before
    recovered = report["recovered_after_seconds"]
    report["after"] = shot_once.read_state(source) if recovered is not None else None
    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"session-{step}-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    sub = report["subscribe"]
    print(f"SUBSCRIBE: {sub.get('status', sub.get('error'))} SID={report['sid']}")
    print(f"NOTIFY received: {len(report['notifies'])}")
    for action in report["actions"]:
        print(f"{action['action']}: {action.get('status', action.get('error'))} in {action['seconds']} s")
    if "stream" in report:
        print(f"stream: {report['stream']}")
    if "stopped_before" in report:
        print(f"STOPPED before {report['stopped_before']}: camera did not answer")
    if "unsubscribe" in report:
        unsub = report["unsubscribe"]
        print(f"UNSUBSCRIBE: {unsub.get('status', unsub.get('error'))}")
    print(f"answered before UNSUBSCRIBE: {report['healthy_before_unsubscribe']}")
    print(f"answers again after {recovered} s" if recovered is not None else f"NO ANSWER after {RECOVERY_SECONDS} s")
    print(f"after: {report['after']}")
    print(f"report: {path}")
    return 0 if recovered is not None else 1


def self_test():
    """Runs each step against a fake camera on localhost."""
    seen = []
    give_sid = [True]
    offer = [True]
    fail_status = [200]
    def chunk(cid, payload, stamp):
        return cid + len(payload).to_bytes(4, "big") + stamp.to_bytes(8, "big") + payload

    idr = b"\x00\x00\x00\x01\x40\x01\x0c" + b"\x00\x00\x01\x42\x01" + b"\x00\x00\x01\x44\x01" + b"\x00\x00\x01\x26\x01" + b"\x11" * 70000
    fake_stream = (
        b"TTTS" + bytes(8) + b"VID0" + bytes(12)
        + (2560).to_bytes(4, "big") + (1280).to_bytes(4, "big") + b"movi"
        + chunk(b"00VD", idr, 8000) + chunk(b"00AU", b"\x11" * 10, 8000)
        + chunk(b"00VR", b"\x11" * 24, 8000) + chunk(b"00VD", b"\x00\x00\x01\x02\x01" + b"\x11" * 10, 16000)
    )

    class FakeCamera(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == STREAM_PATH:
                seen.append("STREAM")
                assert self.headers["User-Agent"] == "Android Linux"
                self.send_response(206)
                self.send_header("Content-Type", "video/x-avi")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(fake_stream)
                return
            seen.append("GET")
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_SUBSCRIBE(self):
            seen.append("SUBSCRIBE")
            assert self.path == EVENT_PATH
            assert self.headers["NT"] == "upnp:event" and self.headers["TIMEOUT"] == SUBSCRIBE_TIMEOUT
            assert self.headers["Access-Method"] == "manual"
            callback = self.headers["CALLBACK"].strip("<>").removeprefix("http://")
            hostport, _, path = callback.partition("/")
            host, _, port = hostport.partition(":")
            self.send_response(200)
            if give_sid[0]:
                self.send_header("SID", "uuid:fake")
            self.send_header("Content-Length", "0")
            self.end_headers()
            c = http.client.HTTPConnection(host, int(port), timeout=3)
            c.request("NOTIFY", "/" + path, b"<e>state</e>", {"SID": "uuid:fake", "SEQ": "0"})
            assert c.getresponse().status == 200
            c.close()

        def do_UNSUBSCRIBE(self):
            seen.append("UNSUBSCRIBE")
            assert self.headers["SID"] == "uuid:fake" and "CALLBACK" not in self.headers
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"])).decode()
            seen.append(("POST", self.path, self.headers["SOAPACTION"], body))
            reply = b"<ChargingStatus>Off</ChargingStatus>"
            if "GetInfomation" in body:
                reply = f"<QualityHighUrl>http://x:7679{STREAM_PATH}</QualityHighUrl>".encode() if offer[0] else b"<x/>"
            self.send_response(fail_status[0])
            self.send_header("Content-Length", str(len(reply)))
            self.end_headers()
            self.wfile.write(reply)

        def log_message(self, *args):
            pass

    camera = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeCamera)
    threading.Thread(target=camera.serve_forever, daemon=True).start()
    global STREAM_PORT
    STREAM_PORT = camera.server_address[1]
    soap_query.HOST, soap_query.CONTROL_PORT = "127.0.0.1", camera.server_address[1]
    posts = lambda: [x for x in seen if isinstance(x, tuple)]
    fast = {"callback_port": 18059, "settle": 0, "listen_seconds": 0}

    report = run_session("127.0.0.1", "subscribe", **fast)
    assert report["sid"] == "uuid:fake" and report["unsubscribe"]["status"] == 200
    assert report["recovered_after_seconds"] is not None
    assert len(report["notifies"]) == 1 and report["notifies"][0]["path"] == CALLBACK_PATH
    assert report["notifies"][0]["body"] == "<e>state</e>"
    assert not posts() and seen.count("SUBSCRIBE") == 1 and seen.count("UNSUBSCRIBE") == 1
    assert seen.index("SUBSCRIBE") < seen.index("UNSUBSCRIBE")

    seen.clear()
    report = run_session("127.0.0.1", "info", **fast)
    assert [a["status"] for a in report["actions"]] == [200, 200]
    assert "Off" in report["actions"][0]["body"]
    assert len(posts()) == 2
    _, path, action, body = posts()[0]
    assert path == "/smp_4_" and action.endswith('#SetOperationState"'), action
    assert "<StateEvent>changeToRVF</StateEvent>" in body
    _, path, action, body = posts()[1]
    assert path == "/smp_4_" and action.endswith('#GetInfomation"'), action
    assert "GPSINFO" not in body
    order = [x if isinstance(x, str) else "POST" for x in seen if x != "GET"]
    assert order == ["SUBSCRIBE", "POST", "POST", "UNSUBSCRIBE"], order

    seen.clear()
    fail_status[0] = 500
    report = run_session("127.0.0.1", "info", **fast)
    assert len(posts()) == 1 and report["actions"][0]["status"] == 500
    assert "UNSUBSCRIBE" in seen
    fail_status[0] = 200

    seen.clear()
    give_sid[0] = False
    report = run_session("127.0.0.1", "info", **fast)
    assert report["sid"] is None and not report["actions"] and "unsubscribe" not in report
    assert not posts() and "UNSUBSCRIBE" not in seen

    give_sid[0] = True

    seen.clear()
    report = run_session("127.0.0.1", "info", **fast)
    assert "stream" not in report and "STREAM" not in seen

    seen.clear()
    report = run_session("127.0.0.1", "stream", **fast)
    stream = report["stream"]
    assert stream["status"] == 206 and stream["bytes"] == len(fake_stream) and stream["ended_by_camera"]
    assert stream["magic"] == "TTTS" and (stream["width"], stream["height"]) == (2560, 1280)
    assert stream["chunks"] == {"00VD": 2, "00AU": 1, "00VR": 1}, stream["chunks"]
    layout = stream["layout"]["chunks"]
    assert [c["id"] for c in layout] == ["00VD", "00AU", "00VR", "00VD"], layout
    assert layout[0]["nal"] == [32, 33, 34, 19] and layout[0]["timestamp"] == 8000 and layout[0]["complete"]
    assert layout[3]["nal"] == [1] and layout[1]["nal"] is None
    assert len(json.dumps(stream)) < 4000
    order = [x if isinstance(x, str) else "POST" for x in seen if x != "GET"]
    assert order == ["SUBSCRIBE", "POST", "POST", "STREAM", "UNSUBSCRIBE"], order

    seen.clear()
    offer[0] = False
    report = run_session("127.0.0.1", "stream", **fast)
    assert "skipped" in report["stream"] and "STREAM" not in seen and "UNSUBSCRIBE" in seen
    offer[0] = True

    seen.clear()
    fail_status[0] = 500
    report = run_session("127.0.0.1", "stream", **fast)
    assert "stream" not in report and "STREAM" not in seen and "UNSUBSCRIBE" in seen
    fail_status[0] = 200

    sent = {a for actions in STEPS.values() for a, _ in actions}
    assert sent == {"SetOperationState", "GetInfomation"}

    camera.shutdown()
    camera.server_close()
    print("self-test ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--step", choices=tuple(STEPS), default="subscribe")
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        sys.exit(0)
    sys.exit(main(args.step, args.send))
