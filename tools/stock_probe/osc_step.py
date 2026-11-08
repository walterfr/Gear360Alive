#!/usr/bin/env python3
"""One step on a stock Gear 360 (SM-R210) in "Connect to Street View" mode (OSC v1, port 80).
Dry run unless --send.

Step options: POST /osc/state, camera.startSession, one camera.getOptions, camera.closeSession.
Step picture: the same session with one camera.takePicture instead, never retried, then reads
/osc/commands/status until it is done. The photo stays on the camera; nothing is downloaded.
Step image: POST /osc/state, then one camera.getImage for an existing fileUri on the card.
Reads the stream to the end and records only byte count, SHA-256 and JPEG markers, never the image.
Nothing else can be sent from here. Checks GET /osc/info before and after.

Usage:
    python osc_step.py --step picture
    python osc_step.py --step image
    python osc_step.py --step image --send
    python osc_step.py --self-test
"""
import argparse
import hashlib
import http.client
import json
import pathlib
import sys
import threading
import time

import probe

HOST = "192.168.43.1"
PORT = 80
DEFAULT_FILE = "SAM_0306.JPG"
CARD_DIR = "/mnt/mmc/DCIM/100PHOTO/"
MAX_IMAGE_BYTES = 32 * 1024 * 1024
OPTION_NAMES = [
    "captureMode", "captureModeSupport",
    "exposureProgram", "exposureProgramSupport",
    "iso", "isoSupport",
    "shutterSpeed", "shutterSpeedSupport",
    "whiteBalance", "whiteBalanceSupport",
    "hdr", "hdrSupport",
    "fileFormat", "fileFormatSupport",
    "remainingPictures", "remainingSpace", "totalSpace", "batteryLevel",
]
# The only commands this script can send
ALLOWED = {
    "camera.startSession",
    "camera.getOptions",
    "camera.closeSession",
    "camera.takePicture",
    "camera.getImage",
}
SETTLE_SECONDS = 2
STATUS_EVERY_SECONDS = 2
PICTURE_LIMIT_SECONDS = 60


def request(method, path, body=None):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
    started = time.monotonic()
    try:
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json;charset=utf-8", "Connection": "close"} if data else {"Connection": "close"}
        conn.request(method, path, data, headers)
        r = conn.getresponse()
        text = r.read(probe.MAX_BODY_BYTES).decode("utf-8", "replace")
        result = {"status": r.status, "seconds": round(time.monotonic() - started, 2), "body": text}
        try:
            result["json"] = json.loads(text)
        except ValueError:
            pass
        return result
    except (OSError, http.client.HTTPException) as e:
        return {"error": type(e).__name__, "detail": str(e)[:200]}
    finally:
        conn.close()


def command(name, parameters):
    if name not in ALLOWED:
        raise ValueError(f"command not allowed: {name}")
    return request("POST", "/osc/commands/execute", {"name": name, "parameters": parameters})


def healthy():
    return request("GET", "/osc/info").get("status") == 200


def file_uri_of(name):
    """Avoids a leading slash on the command line, which Git Bash on Windows rewrites."""
    if "/" in name or "\\" in name or ".." in name:
        raise ValueError(f"plain file name expected: {name}")
    return f"{CARD_DIR}{name}"


def get_image(file_uri):
    """One camera.getImage; reads the full response stream and keeps only counts and hashes."""
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    started = time.monotonic()
    try:
        payload = json.dumps({"name": "camera.getImage", "parameters": {"fileUri": file_uri}}).encode()
        headers = {"Content-Type": "application/json;charset=utf-8", "Connection": "close"}
        conn.request("POST", "/osc/commands/execute", payload, headers)
        r = conn.getresponse()
        digest = hashlib.sha256()
        total = 0
        head = b""
        tail = b""
        while total < MAX_IMAGE_BYTES:
            chunk = r.read(65536)
            if not chunk:
                break
            if len(head) < 4096:
                head = (head + chunk)[:4096]
            tail = (tail + chunk)[-2:]
            digest.update(chunk)
            total += len(chunk)
        is_jpeg = head.startswith(b"\xff\xd8")
        result = {
            "fileUri": file_uri,
            "status": r.status,
            "content_type": r.getheader("Content-Type"),
            "bytes": total,
            "is_jpeg": is_jpeg,
            "first": head[:4].hex(),
            "last": tail.hex(),
            "sha256": digest.hexdigest() if total else None,
            "seconds": round(time.monotonic() - started, 2),
        }
        if not is_jpeg and head:
            text = head.decode("utf-8", "replace")
            result["body"] = text
            try:
                result["json"] = json.loads(text)
            except ValueError:
                pass
        return result
    except (OSError, http.client.HTTPException) as e:
        return {"fileUri": file_uri, "error": type(e).__name__, "detail": str(e)[:200]}
    finally:
        conn.close()


def take_picture(session, every, limit):
    """One takePicture; then only status reads, until done, error or the limit."""
    shot = command("camera.takePicture", {"sessionId": session})
    polls = []
    answer = shot.get("json", {})
    command_id = answer.get("id")
    started = time.monotonic()
    while answer.get("state") == "inProgress" and command_id and time.monotonic() - started < limit:
        time.sleep(every)
        status = request("POST", "/osc/commands/status", {"id": command_id})
        polls.append(status)
        answer = status.get("json", {})
    return {
        "takePicture": shot,
        "polls": polls,
        "final_state": answer.get("state"),
        "fileUri": answer.get("results", {}).get("fileUri"),
        "seconds": round(time.monotonic() - started, 1),
    }


def run_step(step, file_uri=CARD_DIR + DEFAULT_FILE, settle=SETTLE_SECONDS, every=STATUS_EVERY_SECONDS, limit=PICTURE_LIMIT_SECONDS):
    """Always closes a session it opened, even after a failed command."""
    report = {"step": step, "state": request("POST", "/osc/state", {})}
    if step == "image":
        time.sleep(settle)
        report["getImage"] = get_image(file_uri)
        report["healthy_at_end"] = healthy()
        return report
    started = command("camera.startSession", {})
    report["startSession"] = started
    session = started.get("json", {}).get("results", {}).get("sessionId")
    report["sessionId"] = session
    if session:
        time.sleep(settle)
        if step == "options":
            report["getOptions"] = command("camera.getOptions", {"sessionId": session, "optionNames": OPTION_NAMES})
        else:
            report["picture"] = take_picture(session, every, limit)
        time.sleep(settle)
        report["closeSession"] = command("camera.closeSession", {"sessionId": session})
    report["healthy_at_end"] = healthy()
    return report


def main(step, send, file_name=DEFAULT_FILE):
    file_uri = file_uri_of(file_name)
    print(f"to http://{HOST}:{PORT}:")
    print("  POST /osc/state {}")
    if step == "image":
        print(f'  after {SETTLE_SECONDS}s, ONCE: POST /osc/commands/execute {{"name": "camera.getImage", "parameters": {{"fileUri": "{file_uri}"}}}}')
    else:
        print('  POST /osc/commands/execute {"name": "camera.startSession", "parameters": {}}')
        if step == "options":
            print(f'  after {SETTLE_SECONDS}s: camera.getOptions {{"sessionId": <id>, "optionNames": {OPTION_NAMES}}}')
        else:
            print(f'  after {SETTLE_SECONDS}s, ONCE: camera.takePicture {{"sessionId": <id>}}')
            print(f'  every {STATUS_EVERY_SECONDS}s, up to {PICTURE_LIMIT_SECONDS}s: POST /osc/commands/status {{"id": <command id>}}')
        print(f"  after {SETTLE_SECONDS}s: camera.closeSession {{\"sessionId\": <id>}}")
    if not send:
        print("dry run: nothing sent")
        return 0
    source = probe.local_ip_for(HOST)
    if not probe.same_subnet(source, HOST):
        print(f"not on the camera network (source {source}); nothing sent")
        return 2
    if not healthy():
        print("camera does not answer /osc/info; nothing sent")
        return 3
    report = run_step(step, file_uri=file_uri)
    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"osc-{step}-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    for key in ("state", "startSession", "getOptions", "closeSession"):
        if key in report:
            r = report[key]
            print(f"{key}: {r.get('status', r.get('error'))}  {r.get('body', '')[:1200]}")
    if "picture" in report:
        p = report["picture"]
        shot = p["takePicture"]
        print(f"takePicture: {shot.get('status', shot.get('error'))}  {shot.get('body', '')[:600]}")
        print(f"status reads: {len(p['polls'])}, final state {p['final_state']} after {p['seconds']} s")
        print(f"fileUri: {p['fileUri']}")
    if "getImage" in report:
        img = report["getImage"]
        print(
            f"getImage: {img.get('status', img.get('error'))} content_type={img.get('content_type')} "
            f"bytes={img.get('bytes')} is_jpeg={img.get('is_jpeg')} sha256={img.get('sha256')} "
            f"in {img.get('seconds')} s"
        )
        if "body" in img:
            print(f"  body: {img['body'][:600]}")
    print(f"answers at end: {report['healthy_at_end']}")
    print(f"report: {path}")
    return 0 if report["healthy_at_end"] else 1


def self_test():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    seen = []
    fail_options = [False]
    polls = [0]
    fake_jpeg = b"\xff\xd8\xff\xe0" + b"\x11" * 70000 + b"\xff\xd9"

    class Fake(BaseHTTPRequestHandler):
        def reply(self, code, body):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            seen.append(("GET", self.path))
            self.reply(200, {"model": "GEAR 360"})

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])) or b"{}")
            seen.append(("POST", self.path, body.get("name"), body.get("parameters")))
            if self.path == "/osc/state":
                return self.reply(200, {"fingerprint": "1", "state": {"batteryLevel": 0.5}})
            if self.path == "/osc/commands/status":
                assert body["id"] == "C1"
                polls[0] += 1
                if polls[0] < 2:
                    return self.reply(200, {"name": "camera.takePicture", "state": "inProgress", "id": "C1"})
                return self.reply(200, {"name": "camera.takePicture", "state": "done",
                                        "results": {"fileUri": "100PHOTO/360_0001.JPG"}})
            name = body["name"]
            if name == "camera.getImage":
                uri = body["parameters"]["fileUri"]
                if uri.endswith("SAM_0306.JPG"):
                    self.send_response(200)
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(len(fake_jpeg)))
                    self.end_headers()
                    self.wfile.write(fake_jpeg)
                    return
                return self.reply(400, {"name": name, "state": "error", "error": {"code": "invalidParameterValue"}})
            if name == "camera.takePicture":
                return self.reply(200, {"name": name, "state": "inProgress", "id": "C1"})
            if name == "camera.startSession":
                return self.reply(200, {"name": name, "state": "done", "results": {"sessionId": "S1", "timeout": 180}})
            assert body["parameters"]["sessionId"] == "S1"
            if name == "camera.getOptions" and fail_options[0]:
                return self.reply(400, {"name": name, "state": "error", "error": {"code": "invalidParameterName"}})
            self.reply(200, {"name": name, "state": "done", "results": {"options": {"iso": 0}}})

        def log_message(self, *args):
            pass

    global HOST, PORT
    server = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    HOST, PORT = "127.0.0.1", server.server_address[1]

    fast = {"settle": 0, "every": 0, "limit": 5}
    executed = lambda: [s[2] for s in seen if s[0] == "POST" and s[1] == "/osc/commands/execute"]

    report = run_step("options", **fast)
    assert report["sessionId"] == "S1" and report["getOptions"]["json"]["results"]["options"]["iso"] == 0
    assert executed() == ["camera.startSession", "camera.getOptions", "camera.closeSession"], executed()
    assert report["healthy_at_end"]

    seen.clear()
    fail_options[0] = True
    report = run_step("options", **fast)
    assert report["getOptions"]["status"] == 400 and report["closeSession"]["status"] == 200

    seen.clear()
    report = run_step("picture", **fast)
    assert executed() == ["camera.startSession", "camera.takePicture", "camera.closeSession"], executed()
    assert report["picture"]["final_state"] == "done" and report["picture"]["fileUri"].endswith("360_0001.JPG")
    assert len(report["picture"]["polls"]) == 2

    seen.clear()
    report = run_step("image", file_uri=file_uri_of("SAM_0306.JPG"), **fast)
    assert executed() == ["camera.getImage"], executed()
    img = report["getImage"]
    assert img["status"] == 200 and img["is_jpeg"] and img["bytes"] == len(fake_jpeg)
    assert img["sha256"] == hashlib.sha256(fake_jpeg).hexdigest()
    assert report["healthy_at_end"]

    seen.clear()
    report = run_step("image", file_uri=file_uri_of("MISSING.JPG"), **fast)
    img = report["getImage"]
    assert img["status"] == 400 and not img["is_jpeg"] and img["json"]["state"] == "error"

    for bad in ("/mnt/mmc/x.jpg", "../x.jpg", "a/b.jpg"):
        try:
            file_uri_of(bad)
            raise AssertionError(f"accepted {bad}")
        except ValueError:
            pass

    for blocked in ("camera.delete", "camera.setOptions", "camera._startCapture", "camera.listImages"):
        try:
            command(blocked, {})
            raise AssertionError(f"sent {blocked}")
        except ValueError:
            pass
    server.shutdown()
    server.server_close()
    print("self-test ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--step", choices=("options", "picture", "image"), default="options")
    parser.add_argument("--file", default=DEFAULT_FILE, help="file name in /mnt/mmc/DCIM/100PHOTO/")
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        sys.exit(0)
    sys.exit(main(args.step, args.send, args.file))
