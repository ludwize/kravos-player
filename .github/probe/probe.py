#!/usr/bin/env python3
"""Checks, without any token, that the app's updater can see and download the latest release."""
import json, os, urllib.request

OUT = "probe-out"
os.makedirs(OUT, exist_ok=True)
index = []
req = urllib.request.Request("https://api.github.com/repos/ludwize/kravos-player/releases/latest",
                             headers={"User-Agent": "KravosPlayer", "Accept": "application/vnd.github+json"})
try:
    with urllib.request.urlopen(req, timeout=30) as r:
        o = json.loads(r.read())
    index.append(f"latest: {o.get('tag_name')}")
    for a in o.get("assets", []):
        index.append(f"asset: {a['name']} {a['size']} {a['browser_download_url']}")
        with urllib.request.urlopen(urllib.request.Request(a["browser_download_url"], headers={"User-Agent": "KravosPlayer"}), timeout=60) as d:
            data = d.read()
        index.append(f"downloaded {len(data)} bytes, matches size: {len(data) == a['size']}")
except Exception as e:
    index.append(f"ERROR {e}")
open(f"{OUT}/index.txt", "w").write("\n".join(index) + "\n")
print("\n".join(index))
