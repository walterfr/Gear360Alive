#!/usr/bin/env python3
"""Measures camera keepalive behavior over stock firmware (SM-R210).

Read-only test:
- Sends periodic lightweight SOAP queries (X_GetSettingInformation) every 20 seconds.
- Monitors if camera stays on past the configured AutoPowerOff (5 minutes = 300 s).
- Runs for up to 360 s (6 minutes) or until disconnected.
- Logs battery level and response times.

Usage:
    python measure_keepalive.py
    python measure_keepalive.py --send
    python measure_keepalive.py --self-test
"""
import argparse
import json
import pathlib
import sys
import time

import probe
import soap_query

POLL_INTERVAL_S = 20
TEST_DURATION_S = 360  # 6 minutes (past the 5min auto-off timer)


def run_test(send=False, duration=TEST_DURATION_S):
    print(f"Target: http://{soap_query.HOST}:{soap_query.CONTROL_PORT}{soap_query.CONTROL_PATH}")
    print(f"Action: X_GetSettingInformation polled every {POLL_INTERVAL_S}s for {duration}s")
    print(f"Purpose: Check if periodic queries prevent the 5-minute auto-power-off shutdown.")

    if not send:
        print("[dry-run] No packets sent. Re-run with --send after approval.")
        return

    source = probe.local_ip_for(soap_query.HOST)
    if not probe.same_subnet(source, soap_query.HOST) or not soap_query.healthy(source):
        print("Camera not reachable or not healthy; aborting.")
        return

    records = []
    start_time = time.time()
    next_poll = start_time
    iteration = 0

    print("\nStarting keepalive measurement...")

    try:
        while True:
            now = time.time()
            elapsed = int(now - start_time)
            if elapsed >= duration:
                print(f"\n[DONE] Test reached {duration}s! Camera did NOT auto power off during active polling.")
                break

            iteration += 1
            t0 = time.time()
            res = soap_query.send("X_GetSettingInformation", {}, source)
            round_trip = time.time() - t0

            status = res.get("status")
            body = res.get("body", "")

            # Extract BatteryLevel if available
            battery = "?"
            if "<BatteryLevel>" in body:
                battery = body.split("<BatteryLevel>")[1].split("</BatteryLevel>")[0]

            record = {
                "iteration": iteration,
                "elapsed_s": elapsed,
                "status": status,
                "round_trip_s": round(round_trip, 3),
                "battery": battery,
            }
            records.append(record)

            ts = time.strftime("%H:%M:%S")
            print(f"[{ts}] +{elapsed}s (iter {iteration}): HTTP {status}, rtt {round_trip:.2f}s, battery {battery}%")

            if status != 200:
                print(f"[FAIL] Camera stopped responding at {elapsed}s: {res}")
                break

            time.sleep(POLL_INTERVAL_S)

    finally:
        out_dir = pathlib.Path(__file__).resolve().parent / "reports"
        out_dir.mkdir(parents=True, exist_ok=True)
        report_file = out_dir / f"keepalive_test_{time.strftime('%Y%m%d_%H%M%S')}.json"
        report_file.write_text(json.dumps(records, indent=2), encoding="utf-8")
        print(f"\nReport saved: {report_file}")


def self_test():
    assert POLL_INTERVAL_S > 0
    assert TEST_DURATION_S > 300
    print("self-test ok")


def main():
    parser = argparse.ArgumentParser(description="Measure camera auto-off keepalive")
    parser.add_argument("--send", action="store_true", help="Send periodic polling to camera")
    parser.add_argument("--duration", type=int, default=TEST_DURATION_S, help="Duration in seconds")
    parser.add_argument("--self-test", action="store_true", help="Run local self-test")

    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        run_test(send=args.send, duration=args.duration)


if __name__ == "__main__":
    main()
