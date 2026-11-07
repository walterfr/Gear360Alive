#!/usr/bin/env python3
"""Sends query-only SOAP actions to a Gear 360 (SM-R210) on stock firmware, one at a time.

Only the fixed actions in QUERIES can be sent. Between actions it checks that the camera
still answers, and stops at the first failure.

Usage:
    python soap_query.py
    python soap_query.py --self-test
"""
import argparse
import http.client
import json
import pathlib
import sys
import time

import probe

HOST = "192.168.43.1"
CONTROL_PORT = 7676
CONTROL_PATH = "/smp_4_"
SERVICE = "urn:schemas-upnp-org:service:ContentDirectory:1"

BROWSE_ROOT = {
    "ObjectID": "0",
    "BrowseFlag": "BrowseDirectChildren",
    "Filter": "*",
    "StartingIndex": "0",
    "RequestedCount": "5",
    "SortCriteria": "",
}
# Order: least to most camera-specific. X_GetDeviceStatus and GetInfomation are excluded:
# the first takes a StateEvent input, the second raises EVT_RVF_VIEWERFOUND on the camera UI.
QUERIES = (
    ("GetSystemUpdateID", {}),
    ("GetSearchCapabilities", {}),
    ("GetSortCapabilities", {}),
    ("X_GetStorage", {}),
    ("X_GetRemainStorageInfo", {}),
    ("X_GetDeviceOpertaionMode", {}),
    ("X_GetFeatureList", {}),
    ("X_GetSettingInformation", {}),
    ("GetIP", {}),
    ("Browse", BROWSE_ROOT),
)
ALLOWED = {name for name, _ in QUERIES}


def envelope(action, arguments):
    args = "".join(f"<{k}>{v}</{k}>" for k, v in arguments.items())
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
        f'<u:{action} xmlns:u="{SERVICE}">{args}</u:{action}>'
        "</s:Body></s:Envelope>"
    )


def send(action, arguments, source_ip):
    if action not in ALLOWED:
        raise ValueError(f"action not allowed: {action}")
    conn = http.client.HTTPConnection(HOST, CONTROL_PORT, timeout=8, source_address=(source_ip, 0))
    try:
        conn.request(
            "POST",
            CONTROL_PATH,
            envelope(action, arguments).encode("utf-8"),
            {
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPACTION": f'"{SERVICE}#{action}"',
                "Connection": "close",
            },
        )
        r = conn.getresponse()
        return {"status": r.status, "body": r.read(probe.MAX_BODY_BYTES).decode("utf-8", "replace")}
    except (OSError, http.client.HTTPException) as e:
        return {"error": type(e).__name__, "detail": str(e)[:200]}
    finally:
        conn.close()


def healthy(source_ip):
    """One retry: the first request after a pause has answered 503 on this camera."""
    for attempt in range(2):
        if probe.http_get(HOST, CONTROL_PORT, "/smp_2_", source_ip, probe.STOCK_HEADERS).get("status") == 200:
            return True
        if attempt == 0:
            time.sleep(1)
    return False


def main():
    source = probe.local_ip_for(HOST)
    if not probe.same_subnet(source, HOST):
        print(f"not on the camera network (source {source})")
        return 2
    if not healthy(source):
        print("camera does not answer before the first query; nothing sent")
        return 3
    results = {}
    stopped = None
    for action, arguments in QUERIES:
        results[action] = send(action, arguments, source)
        status = results[action].get("status", results[action].get("error"))
        body = results[action].get("body", "")
        print(f"{action}: {status} len={len(body)}")
        time.sleep(1)
        if not healthy(source):
            stopped = action
            print(f"STOPPED: camera stopped answering after {action}")
            break
    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"soap-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps({"stopped": stopped, "results": results}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"report: {path}")
    return 1 if stopped else 0


def self_test():
    blocked = {"X_SetFormat", "X_SetReset", "X_DeleteFile", "X_DeleteFileByID", "Shot", "StartRecord",
               "X_GetDeviceStatus", "GetInfomation", "SetOperationState", "ImportResource"}
    assert not (ALLOWED & blocked)
    assert all(not a.startswith(("Set", "X_Set", "X_Delete")) for a in ALLOWED)
    for action in blocked:
        try:
            send(action, {}, "127.0.0.1")
            raise AssertionError(f"sent {action}")
        except ValueError:
            pass
    body = envelope("Browse", BROWSE_ROOT)
    assert "<ObjectID>0</ObjectID>" in body and "<RequestedCount>5</RequestedCount>" in body
    print("self-test ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true")
    if parser.parse_args().self_test:
        self_test()
        sys.exit(0)
    sys.exit(main())
