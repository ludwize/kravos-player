#!/usr/bin/env python3
"""Probes podcast feeds from a GitHub runner (the workspace can't reach them)."""
import json, os, re, urllib.request, urllib.parse

OUT = "probe-out"
os.makedirs(OUT, exist_ok=True)
index = []
UA = "KravosPlayer/1.2 (Android)"

def get(name, url, limit=400000):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read(limit)
            index.append(f"{name}: {r.status} {r.headers.get('Content-Type')} len={len(body)} clen={r.headers.get('Content-Length')} final={r.geturl()}")
            open(f"{OUT}/{name}", "wb").write(body)
            return body
    except Exception as e:
        index.append(f"{name}: ERROR {e}")

s = get("search_rogan.json", "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=5&term=" + urllib.parse.quote("joe rogan experience"))
if s:
    for i, r in enumerate(json.loads(s)["results"][:2]):
        index.append(f"rogan{i}: {r.get('collectionName')} {r.get('feedUrl')}")
        if r.get("feedUrl"):
            get(f"rogan{i}.xml", r["feedUrl"])
open(f"{OUT}/index.txt", "w").write("\n".join(index) + "\n")
print("\n".join(index))
