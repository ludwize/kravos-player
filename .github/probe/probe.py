#!/usr/bin/env python3
"""Probes podcast endpoints from a GitHub runner and saves what came back (the workspace can't reach them)."""
import json, os, sys, urllib.request, urllib.parse

OUT = "probe-out"
os.makedirs(OUT, exist_ok=True)
index = []
UA = "KravosPlayer/1.2 (Android)"


def get(name, url, limit=60000):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=25) as r:
            body = r.read(limit)
            index.append(f"{name}: {r.status} {r.headers.get('Content-Type')} len={len(body)} final={r.geturl()}")
            with open(f"{OUT}/{name}", "wb") as f:
                f.write(body)
            return body
    except Exception as e:
        index.append(f"{name}: ERROR {e}")
        return None


q = lambda t: urllib.parse.quote(t)
s1 = get("search_balkan.json", f"https://itunes.apple.com/search?media=podcast&entity=podcast&limit=8&term={q('balkan')}")
get("search_sr.json", f"https://itunes.apple.com/search?media=podcast&entity=podcast&limit=8&term={q('подкаст')}")
get("search_sr_latin.json", f"https://itunes.apple.com/search?media=podcast&entity=podcast&limit=8&country=RS&term={q('podkast')}")
top_rs = get("top_rs.json", "https://rss.applemarketingtools.com/api/v2/rs/podcasts/top/10/podcasts.json")
get("top_us.json", "https://rss.applemarketingtools.com/api/v2/us/podcasts/top/10/podcasts.json")
get("top_rs_itunes_rss.json", "https://itunes.apple.com/rs/rss/toppodcasts/limit=10/json")

ids = []
if top_rs:
    try:
        ids = [x["id"] for x in json.loads(top_rs)["feed"]["results"]][:5]
    except Exception as e:
        index.append(f"top_rs parse: {e}")
if ids:
    get("lookup.json", f"https://itunes.apple.com/lookup?entity=podcast&id={','.join(ids)}")

if s1:
    try:
        feeds = [r["feedUrl"] for r in json.loads(s1)["results"] if r.get("feedUrl")][:3]
        for i, fu in enumerate(feeds):
            get(f"feed{i}.xml", fu, limit=40000)
            index.append(f"feed{i}.url: {fu}")
    except Exception as e:
        index.append(f"feeds: {e}")

with open(f"{OUT}/index.txt", "w") as f:
    f.write("\n".join(index) + "\n")
print("\n".join(index))
