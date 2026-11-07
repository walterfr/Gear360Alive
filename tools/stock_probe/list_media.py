#!/usr/bin/env python3
"""Lists the camera's media with the query-only Browse action. Changes nothing on the camera.

Usage:
    python list_media.py
    python list_media.py --self-test
"""
import html
import json
import pathlib
import re
import sys
import time

import probe
import soap_query

CONTAINER = "8"  # AllMedia, from Browse of the root
PAGE = 50


def parse_didl(body):
    """Items of a Browse response: id, title and each res URL with its attributes."""
    result = re.search(r"<Result>(.*?)</Result>", body, re.S)
    didl = html.unescape(result.group(1)) if result else ""
    items = []
    for item in re.findall(r"<item\b.*?</item>", didl, re.S):
        title = re.search(r"<dc:title[^>]*>([^<]*)", item)
        items.append({
            "id": (re.search(r'\bid="([^"]*)"', item) or [None, None])[1],
            "title": title.group(1).strip() if title else None,
            "res": [
                {"url": url.strip(), **dict(re.findall(r'([\w:]+)="([^"]*)"', attrs))}
                for attrs, url in re.findall(r"<res\b([^>]*)>([^<]*)</res>", item)
            ],
        })
    total = re.search(r"<TotalMatches>(\d+)</TotalMatches>", body)
    return items, int(total.group(1)) if total else 0


def browse_page(start, source):
    arguments = dict(soap_query.BROWSE_ROOT, ObjectID=CONTAINER, StartingIndex=str(start), RequestedCount=str(PAGE))
    return soap_query.send("Browse", arguments, source)


def main():
    source = probe.local_ip_for(soap_query.HOST)
    if not probe.same_subnet(source, soap_query.HOST) or not soap_query.healthy(source):
        print("camera not reachable; nothing sent")
        return 2
    items = []
    raw = []
    while True:
        response = browse_page(len(items), source)
        raw.append(response)
        if response.get("status") != 200:
            print(f"Browse failed: {response.get('status', response.get('error'))}")
            break
        page, total = parse_didl(response["body"])
        items += page
        if not page or len(items) >= total:
            break
        time.sleep(1)
    out_dir = pathlib.Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"media-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps({"items": items, "raw": raw}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"items: {len(items)}")
    for item in items:
        print(item["title"], "|", " | ".join(
            f"{r.get('resolution', '?')} {r.get('size', '?')}B {r.get('duration', '')} {r['url'].rsplit(':', 1)[-1][:40]}"
            for r in item["res"]
        ))
    print(f"report: {path}")
    return 0


def self_test():
    body = (
        "<Result>&lt;item id=&quot;8$1&quot;&gt;&lt;dc:title&gt;360_0001.JPG&lt;/dc:title&gt;"
        "&lt;res size=&quot;10&quot; resolution=&quot;5792x2896&quot;&gt;http://192.168.43.1:9001/a.JPG&lt;/res&gt;"
        "&lt;/item&gt;</Result><TotalMatches>1</TotalMatches>"
    )
    items, total = parse_didl(body)
    assert total == 1 and items[0]["id"] == "8$1" and items[0]["title"] == "360_0001.JPG"
    assert items[0]["res"][0]["resolution"] == "5792x2896"
    assert items[0]["res"][0]["url"].endswith("/a.JPG")
    print("self-test ok")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        sys.exit(0)
    sys.exit(main())
