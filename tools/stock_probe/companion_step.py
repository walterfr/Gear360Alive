#!/usr/bin/env python3
"""Companion-mode test on a stock Gear 360 (SM-R210) in "Connect to iOS". Dry run unless --send.

Question it answers: with the gallery session open (changeToML), does the camera still take a
picture or a video from its own button, report it with NOTIFY, and list the new file in Browse?

Sequence, each step once:
1. SUBSCRIBE /smp_5_, then SetOperationState changeToML, then Browse of container 8 (all pages).
2. Listen for --duration seconds while the owner presses the camera's button.
   - With --reply, the first OperationState=CaptureStartToML is answered, once per session, the
     way the official app's "ML Shutter" does: X_ControllerStatus with StatusValue=Idle,
     StreamingOperationValue=Stop, ChangeStatusValue=NA. Without --reply the camera waits for an
     answer that never comes, and its button can stay stuck (2026-11-12).
   - After OperationState=UpldateML or "Capture Finished" (never from the first NOTIFY, which only
     carries the camera's old state), wait a few seconds and Browse again; report the new files.
   With --download, read each new file once over port 7679 and keep only its size and SHA-256.
3. UNSUBSCRIBE, then wait until the camera answers again.

This script sends no Shot, no StartRecord and no setting: the capture is the owner's, on the camera.

Usage:
    python companion_step.py --reply
    python companion_step.py --send --reply --download
    python companion_step.py --self-test
"""
import argparse
import json
import pathlib
import sys
import threading
import time

import download_file
import list_media
import listen_button_events as events
import probe
import session_step
import shot_once
import soap_query

CHANGE_TO_ML = ("SetOperationState", {"StateEvent": "changeToML"})
# The official app's answer to CaptureStartToML ("Idle_Stop_NA" split on "_"), in the action's argument order
ML_SHUTTER_REPLY = ("X_ControllerStatus", {"StatusValue": "Idle", "StreamingOperationValue": "Stop", "ChangeStatusValue": "NA"})
LISTEN_SECONDS = 120
AFTER_CAPTURE_SECONDS = 3
SETTLE_SECONDS = 3


def is_capture_finished(parsed):
    return any(str(v).strip().lower() == "capture finished" for v in parsed.values())


def classify(parsed, first):
    """'request' when the camera asks to capture, 'refresh' when its files changed, else None.
    The first NOTIFY of a subscription is the camera's whole state, old values included."""
    if first:
        return None
    state = str(parsed.get("OperationState", "")).strip()
    if state == "CaptureStartToML":
        return "request"
    if state == "UpldateML" or is_capture_finished(parsed):
        return "refresh"
    return None


def signal_ready():
    """Three beeps on the PC when it is time to press the camera's button (Windows only)."""
    try:
        import winsound
        for _ in range(3):
            winsound.Beep(1200, 250)
            time.sleep(0.15)
    except (ImportError, RuntimeError):
        print("\a", end="", flush=True)


def browse_all(source, browse=list_media.browse_page):
    """Every file of container 8, page by page; (items, total, pages, last status)."""
    items, total, pages, status = [], 0, 0, None
    while True:
        answer = browse(len(items), source)
        status = answer.get("status", answer.get("error"))
        page, total = list_media.parse_didl(answer.get("body", ""))
        pages += 1
        items += page
        if status != 200 or not page or len(items) >= total or pages >= 20:
            return items, total, pages, status


def summary(item):
    res = item.get("res") or [{}]
    return {"title": item.get("title"), "size": res[0].get("size"), "url": res[0].get("url")}


def new_items(known_titles, items):
    return [summary(i) for i in items if i.get("title") not in known_titles]


def run(send, duration, download, reply):
    print(f"to http://{soap_query.HOST}:{soap_query.CONTROL_PORT}:")
    print("  SUBSCRIBE /smp_5_ (CALLBACK port 8059, TIMEOUT Second-300)")
    print(f"  after 2s: SetOperationState {CHANGE_TO_ML[1]}")
    print(f"  after {SETTLE_SECONDS}s: Browse ObjectID=8, all pages")
    if reply:
        print(f"  on the first CaptureStartToML, ONCE: POST /smp_4_ {ML_SHUTTER_REPLY[0]} {ML_SHUTTER_REPLY[1]}")
        print(f"    {soap_query.envelope(*ML_SHUTTER_REPLY)}")
    else:
        print("  no answer to CaptureStartToML: do not press the button (it can stay stuck)")
    print(f"  listen {duration}s; after each UpldateML or 'Capture Finished': wait {AFTER_CAPTURE_SECONDS}s, Browse again")
    if download:
        print("  each new file: one GET on port 7679, keeping only size and SHA-256")
    print("  then UNSUBSCRIBE and wait for the camera to answer again")
    print("  only in Photo mode, with 20% battery or more and a card with space; three beeps when the gallery is open")
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

    report = {"before": shot_once.read_state(source), "events": [], "captures": [], "replies": []}
    print(f"before: {report['before']}")
    if report["before"] is None:
        print("camera state not read; nothing sent")
        return 3
    # In Video mode the button starts a recording, and changeToML then hung the camera (2026-11-12)
    found = shot_once.problems(report["before"])
    if found:
        print("NOT SENT:")
        for problem in found:
            print(f"  {problem}")
        return 4
    captured = threading.Event()
    requested = threading.Event()

    def on_event(parsed, raw):
        values = {k: v for k, v in parsed.items() if k not in ("propertyset", "property")}
        stamp = time.strftime("%H:%M:%S")
        kind = classify(parsed, first=not report["events"])
        report["events"].append({"at": stamp, "values": values, "kind": kind})
        print(f"[{stamp}] NOTIFY {values}" + (f"  -> {kind}" if kind else ""))
        if kind == "request":
            requested.set()
        elif kind == "refresh":
            captured.set()

    server = events.start_listener(source, events.CALLBACK_PORT, on_event)
    sid = None
    try:
        sub = events.event_request("SUBSCRIBE", events.subscribe_headers(source, events.CALLBACK_PORT), source)
        sid = events.sid_of(sub)
        report["subscribe"] = {"status": sub.get("status", sub.get("error")), "sid": bool(sid)}
        print(f"SUBSCRIBE: {report['subscribe']}")
        if not sid:
            return 1
        time.sleep(2)
        ml = session_step.post_action(source, *CHANGE_TO_ML)
        report["changeToML"] = {k: ml.get(k) for k in ("status", "error", "seconds")}
        print(f"changeToML: {report['changeToML']}")
        if ml.get("status") != 200:
            return 1
        time.sleep(SETTLE_SECONDS)
        items, total, pages, status = browse_all(source)
        known = {i.get("title") for i in items}
        report["browse"] = {"status": status, "listed": len(items), "total": total, "pages": pages}
        print(f"Browse: {report['browse']}")

        # The owner is at the camera, not the screen: three beeps say the gallery is open
        signal_ready()
        print(f"\n>>> Galeria aberta: aperte o botão da câmera UMA vez, para foto. Escutando {duration} s. <<<\n")
        until = time.monotonic() + duration
        replied = False
        while time.monotonic() < until:
            if requested.is_set():
                requested.clear()
                if reply and not replied:
                    # Once per session, as the official app: never repeated, whatever the answer
                    replied = True
                    answer = session_step.post_action(source, *ML_SHUTTER_REPLY)
                    entry = {"at": time.strftime("%H:%M:%S"), **{k: answer.get(k) for k in ("status", "error", "seconds", "body")}}
                    report["replies"].append(entry)
                    print(f"{ML_SHUTTER_REPLY[0]}: {answer.get('status', answer.get('error'))} in {answer.get('seconds')} s")
                    print(f"  {(answer.get('body') or '')[:700]}")
            if not captured.wait(timeout=1):
                continue
            captured.clear()
            time.sleep(AFTER_CAPTURE_SECONDS)
            items, total, pages, status = browse_all(source)
            fresh = new_items(known, items)
            known |= {i.get("title") for i in items}
            capture = {"at": time.strftime("%H:%M:%S"), "browse_status": status, "total": total, "new": fresh}
            print(f"after capture: Browse {status}, total {total}, new {[f['title'] for f in fresh]}")
            if download:
                for f in fresh:
                    code, size, digest = download_file.download(f["url"], send=True, out_path=None)
                    f["download"] = {"status": code, "bytes": size, "sha256": digest}
            report["captures"].append(capture)
        report["healthy_before_unsubscribe"] = soap_query.healthy(source)
    finally:
        if sid:
            unsub = events.event_request("UNSUBSCRIBE", {**probe.STOCK_HEADERS, "SID": sid}, source)
            report["unsubscribe"] = unsub.get("status", unsub.get("error"))
            print(f"UNSUBSCRIBE: {report['unsubscribe']}")
        server.shutdown()
        server.server_close()

    report["recovered_after_seconds"] = session_step.wait_recovered(source)
    recovered = report["recovered_after_seconds"]
    report["after"] = shot_once.read_state(source) if recovered is not None else None
    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"companion-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"replies sent: {len(report['replies'])}, captures seen: {len(report['captures'])}, events: {len(report['events'])}")
    print(f"answers again after {recovered} s" if recovered is not None else "NO ANSWER after the wait")
    print(f"after: {report['after']}")
    print(f"report: {path}")
    return 0 if recovered is not None else 1


def self_test():
    assert is_capture_finished({"RVF_DEVICE_STATUS": "Capture Finished"})
    assert not is_capture_finished({"RVF_DEVICE_STATUS": "Capture Started"})
    assert not is_capture_finished({"OperationState": "IDLE"})
    # The first NOTIFY carries an old "Capture Finished": it must not count (2026-11-12)
    assert classify({"RVF_DEVICE_STATUS": "Capture Finished", "OperationState": "IDLE"}, first=True) is None
    assert classify({"RVF_DEVICE_STATUS": "Capture Finished"}, first=False) == "refresh"
    assert classify({"OperationState": "CaptureStartToML"}, first=False) == "request"
    assert classify({"OperationState": "UpldateML"}, first=False) == "refresh"
    assert classify({"OperationState": "TransitionToML"}, first=False) is None
    assert classify({"RVF_BATTERY_LEVEL_VALUE": "33"}, first=False) is None
    photo = {"mode": "Photo", "battery": "80", "card": "External", "free": "57239"}
    assert shot_once.problems(photo) == []
    # Video mode is refused before anything is sent (the 2026-11-12 hang)
    assert len(shot_once.problems(dict(photo, mode="Video"))) == 1
    assert len(shot_once.problems(dict(photo, battery="19"))) == 1
    body = soap_query.envelope(*ML_SHUTTER_REPLY)
    assert ("<StatusValue>Idle</StatusValue><StreamingOperationValue>Stop</StreamingOperationValue>"
            "<ChangeStatusValue>NA</ChangeStatusValue>") in body, body

    def didl(titles, total):
        rows = "".join(
            f'&lt;item id="{t}"&gt;&lt;dc:title&gt;{t}&lt;/dc:title&gt;'
            f'&lt;res size="10"&gt;http://192.168.43.1:7679/{t}&lt;/res&gt;&lt;/item&gt;'
            for t in titles
        )
        return {"status": 200, "body": f"<Result>{rows}</Result><TotalMatches>{total}</TotalMatches>"}

    names = [f"360_{n:04d}.JPG" for n in range(1, 61)]
    asked = []

    def fake_browse(start, source):
        asked.append(start)
        return didl(names[start:start + 50], len(names))

    items, total, pages, status = browse_all("127.0.0.1", fake_browse)
    assert (len(items), total, pages, status) == (60, 60, 2, 200), (len(items), total, pages, status)
    assert asked == [0, 50], asked

    fresh = new_items({i["title"] for i in items[:-1]}, items)
    assert fresh == [{"title": "360_0060.JPG", "size": "10", "url": "http://192.168.43.1:7679/360_0060.JPG"}], fresh

    # An answer with an error ends the paging instead of looping
    assert browse_all("127.0.0.1", lambda start, source: {"error": "TimeoutError"})[3] == "TimeoutError"
    print("self-test ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--download", action="store_true", help="read each new file once, keep only its hash")
    parser.add_argument("--reply", action="store_true", help="answer the first CaptureStartToML as the official app")
    parser.add_argument("--duration", type=int, default=LISTEN_SECONDS)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        sys.exit(0)
    sys.exit(run(args.send, args.duration, args.download, args.reply))
