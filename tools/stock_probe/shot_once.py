#!/usr/bin/env python3
"""Takes one remote photo on a Gear 360 (SM-R210) on stock firmware, with SOAP action Shot.

Without --send it only runs the checks and prints what it would send. It sends Shot once and
never retries, even after a timeout: a retry could take a second picture.

Usage:
    python shot_once.py            (checks only)
    python shot_once.py --send     (one photo)
    python shot_once.py --self-test
"""
import argparse
import http.client
import json
import pathlib
import re
import sys
import time

import probe
import soap_query

ACTION = "Shot"
MIN_BATTERY = 20
MIN_FREE = 100  # Space values look like MB
SHOT_TIMEOUT_S = 30
RECOVERY_WAIT_S = 60


def tag(xml, name):
    match = re.search(rf"<{name}>(.*?)</{name}>", xml, re.S)
    return match.group(1).strip() if match else None


def state(settings, storage):
    return {
        "mode": tag(tag(settings, "DialModeValue") or "", "Default"),
        "lens": tag(tag(settings, "SwitchLensValue") or "", "Default"),
        "battery": tag(settings, "BatteryLevel"),
        "card": tag(settings, "CardStatusValue"),
        "device": tag(settings, "DeviceStatus"),
        "firmware": tag(settings, "FWVersion"),
        "free": tag(storage, "AvaiableSpaceValue"),
        "used": tag(storage, "UsedSpaceValue"),
    }


def problems(current):
    """Why the shot must not be sent. In another mode the shutter could start a recording."""
    found = []
    if current["mode"] != "Photo":
        found.append(f"camera is in {current['mode']} mode: set it to Photo on the camera")
    if not (current["battery"] or "").isdigit() or int(current["battery"]) < MIN_BATTERY:
        found.append(f"battery {current['battery']}: needs {MIN_BATTERY}% or more")
    if current["card"] != "External":
        found.append(f"card status {current['card']}: no usable card")
    if not (current["free"] or "").isdigit() or int(current["free"]) < MIN_FREE:
        found.append(f"free space {current['free']}: needs {MIN_FREE} or more")
    return found


def read_state(source):
    settings = soap_query.send("X_GetSettingInformation", {}, source)
    storage = soap_query.send("X_GetStorage", {}, source)
    if settings.get("status") != 200 or storage.get("status") != 200:
        return None
    return state(settings["body"], storage["body"])


def send_shot(source, host=soap_query.HOST, port=soap_query.CONTROL_PORT):
    conn = http.client.HTTPConnection(host, port, timeout=SHOT_TIMEOUT_S, source_address=(source, 0))
    try:
        conn.request(
            "POST",
            soap_query.CONTROL_PATH,
            soap_query.envelope(ACTION, {"GPSINFO": ""}).encode("utf-8"),
            {
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPACTION": f'"{soap_query.SERVICE}#{ACTION}"',
                "Connection": "close",
            },
        )
        r = conn.getresponse()
        return {"status": r.status, "body": r.read(probe.MAX_BODY_BYTES).decode("utf-8", "replace")}
    except (OSError, http.client.HTTPException) as e:
        return {"error": type(e).__name__, "detail": str(e)[:200]}
    finally:
        conn.close()


def main(send):
    source = probe.local_ip_for(soap_query.HOST)
    if not probe.same_subnet(source, soap_query.HOST):
        print(f"not on the camera network (source {source}); nothing sent")
        return 2
    if not soap_query.healthy(source):
        print("camera does not answer; nothing sent")
        return 3
    before = read_state(source)
    print(f"before: {before}")
    if before is None:
        print("camera state not read; nothing sent")
        return 3
    found = problems(before)
    if found:
        print("NOT SENT:")
        for problem in found:
            print(f"  {problem}")
        return 4
    if not send:
        print(f"checks passed. Would send, once:\n{soap_query.envelope(ACTION, {'GPSINFO': ''})}")
        return 0

    started = time.monotonic()
    result = send_shot(source)
    elapsed = round(time.monotonic() - started, 1)
    print(f"Shot: {result.get('status', result.get('error'))} in {elapsed} s")
    print(f"photo: {tag(result.get('body', ''), 'AFSHOTRESULT')}")

    recovered = None
    while time.monotonic() - started < RECOVERY_WAIT_S:
        if soap_query.healthy(source):
            recovered = round(time.monotonic() - started, 1)
            break
        time.sleep(5)
    after = read_state(source) if recovered is not None else None
    print(f"answers again after {recovered} s" if recovered is not None else f"NO ANSWER after {RECOVERY_WAIT_S} s")
    print(f"after: {after}")

    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"shot-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(
        json.dumps({"before": before, "result": result, "seconds": elapsed, "recovered": recovered, "after": after},
                   indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"report: {path}")
    return 0 if result.get("status") == 200 and recovered is not None else 1


def self_test():
    from http.server import BaseHTTPRequestHandler, HTTPServer
    import threading

    fixtures = pathlib.Path(__file__).resolve().parents[2] / "app/src/test/resources/stock"
    settings = (fixtures / "settings.xml").read_text(encoding="utf-8")
    storage = (fixtures / "storage.xml").read_text(encoding="utf-8")
    current = state(settings, storage)
    assert current["mode"] == "Photo" and current["card"] == "External", current
    assert current["firmware"] == "R210GLU0ARB2_0.81"
    assert problems(current) == []
    assert len(problems(dict(current, mode="Video"))) == 1
    assert len(problems(dict(current, mode="Looping Video"))) == 1
    assert len(problems(dict(current, mode=None))) == 1
    assert len(problems(dict(current, battery="19"))) == 1
    assert len(problems(dict(current, battery=None))) == 1
    assert len(problems(dict(current, card="None"))) == 1
    assert len(problems(dict(current, free="99"))) == 1

    seen = []

    class Fake(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            length = int(self.headers["Content-Length"])
            seen.append((self.path, self.headers["SOAPACTION"], self.rfile.read(length).decode()))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"<AFSHOTRESULT>http://x/360_0001.JPG</AFSHOTRESULT>")

    server = HTTPServer(("127.0.0.1", 0), Fake)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    result = send_shot("127.0.0.1", "127.0.0.1", server.server_port)
    server.shutdown()
    server.server_close()
    assert len(seen) == 1, seen
    assert seen[0][0] == "/smp_4_" and seen[0][1].endswith('#Shot"'), seen
    assert "<GPSINFO></GPSINFO>" in seen[0][2], seen
    assert tag(result["body"], "AFSHOTRESULT").endswith("360_0001.JPG")
    print("self-test ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--send", action="store_true", help="really take one photo")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        sys.exit(0)
    sys.exit(main(args.send))
