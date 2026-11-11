#!/usr/bin/env python3
"""Tests switching camera to Media List mode (changeToML) to list files and download from port 9001.

Sequence as used by official Samsung app:
1. SUBSCRIBE to /smp_5_ events.
2. Send SOAP SetOperationState with <StateEvent>changeToML</StateEvent>.
3. Wait 3 seconds.
4. Send SOAP Browse (ObjectID=8 for AllMedia).
5. If items are returned, download the first photo/video via port 9001 (read-only GET).
6. Send UNSUBSCRIBE.

Usage:
    python ml_step.py
    python ml_step.py --send
    python ml_step.py --self-test
"""
import argparse
import hashlib
import http.client
import http.server
import json
import pathlib
import sys
import threading
import time
import xml.etree.ElementTree as ET

import list_media
import probe
import session_step
import soap_query

CHANGE_TO_ML = ("SetOperationState", {"StateEvent": "changeToML"})


def run_ml_session(send=False):
    print("Sequence: SUBSCRIBE -> SetOperationState(changeToML) -> Browse(ObjectID=8) -> UNSUBSCRIBE")
    print(f"Target: http://{soap_query.HOST}:{soap_query.CONTROL_PORT}")
    print(f"Action: SetOperationState with arguments: {CHANGE_TO_ML[1]}")

    if not send:
        print("[dry-run] No packets sent. Re-run with --send after approval.")
        return

    source = probe.local_ip_for(soap_query.HOST)
    if not probe.same_subnet(source, soap_query.HOST) or not soap_query.healthy(source):
        print("Camera not reachable or not healthy; aborting.")
        return

    notifies = []
    server = session_step.start_listener(source, session_step.CALLBACK_PORT, notifies)

    try:
        # Step 1: SUBSCRIBE
        print("\n1. Sending SUBSCRIBE...")
        sub_headers = session_step.subscribe_headers(source, session_step.CALLBACK_PORT)
        sub_resp = session_step.event_request("SUBSCRIBE", sub_headers, source)
        sid = session_step.sid_of(sub_resp)
        print(f"   SUBSCRIBE status: {sub_resp.get('status')}, SID: {sid}")
        if not sid:
            print("Failed to get subscription SID.")
            return

        time.sleep(2)

        # Step 2: changeToML
        print("\n2. Sending SetOperationState (changeToML)...")
        ml_resp = session_step.post_action(source, CHANGE_TO_ML[0], CHANGE_TO_ML[1])
        print(f"   changeToML status: {ml_resp.get('status')}")
        print(f"   changeToML rtt: {ml_resp.get('duration_s')}s")
        if ml_resp.get("status") != 200:
            print(f"   changeToML failed: {ml_resp.get('body')}")
            return

        print("   Waiting 3s for camera to index media...")
        time.sleep(3)

        # Step 3: Browse ObjectID=8
        print("\n3. Sending Browse (ObjectID=8)...")
        browse_resp = list_media.browse_page(0, source)
        print(f"   Browse status: {browse_resp.get('status')}")
        items, total = list_media.parse_didl(browse_resp.get("body", ""))
        print(f"   Browse total items: {total}, parsed items on page: {len(items)}")

        for item in items:
            title = item.get("title")
            urls = [r.get("url") for r in item.get("res", [])]
            print(f"   Found: {title} -> {urls}")

        # Step 4: If an item exists, test GET on port 9001
        if items and items[0].get("res"):
            media_url = items[0]["res"][0]["url"]
            print(f"\n4. Testing GET on first item URL: {media_url}...")
            # Use download logic
            import download_file
            code, bytes_count, digest = download_file.download(media_url, send=True, out_path=None)
            print(f"   Download result: code={code}, bytes={bytes_count}, sha256={digest}")

    finally:
        if 'sid' in locals() and sid:
            print("\nClosing session (UNSUBSCRIBE)...")
            unsub_headers = {**probe.STOCK_HEADERS, "SID": sid}
            unsub_resp = session_step.event_request("UNSUBSCRIBE", unsub_headers, source)
            print(f"UNSUBSCRIBE status: {unsub_resp.get('status', unsub_resp.get('error'))}")
        server.shutdown()


def self_test():
    assert CHANGE_TO_ML[0] == "SetOperationState"
    assert CHANGE_TO_ML[1]["StateEvent"] == "changeToML"
    print("self-test ok")


def main():
    parser = argparse.ArgumentParser(description="Test changeToML session sequence")
    parser.add_argument("--send", action="store_true", help="Send commands to camera")
    parser.add_argument("--self-test", action="store_true", help="Run local self-test")

    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        run_ml_session(send=args.send)


if __name__ == "__main__":
    main()
