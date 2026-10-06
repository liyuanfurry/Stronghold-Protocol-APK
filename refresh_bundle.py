#!/usr/bin/env python3
"""Brings an already-assembled android/assets/ bundle up to whatever the server serves now.

This is the routine "the payload moved on" step. It is deliberately cheap: every path is checked with
a HEAD, and only files whose ETag or Content-Length actually moved are re-fetched. Going from one
payload version to the next therefore costs the delta, not the whole 272 MiB.

Rewrites baseline/{etags.tsv,index.txt,assets.json}. Run finalize_bundle.py afterwards to stamp
baseline/info.txt — that is what the app compares against when it "校对版本".

Usage: python3 refresh_bundle.py <base-url> <android-assets-dir> [workers]
"""
import json
import os
import ssl
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

VENDOR = [
    "/vendor/preact.module.js",
    "/vendor/hooks.module.js",
    "/vendor/htm.module.js",
    "/vendor/pixi.min.js",
    "/vendor/pixi-spine.js",
    "/vendor/three.core.js",
    "/vendor/three.module.js",
]


def collect(manifest):
    out = set()

    def walk(n):
        if isinstance(n, dict):
            for v in n.values():
                walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)
        elif isinstance(n, str) and (n.startswith("/assets/") or n.startswith("/fonts/")):
            out.add(n)

    walk(manifest)
    return out


def main():
    base = sys.argv[1].rstrip("/")
    dest = sys.argv[2]
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 24
    mirror = os.path.join(dest, "mirror")
    baseline = os.path.join(dest, "baseline")
    ctx = ssl.create_default_context()

    def req(path, method, timeout=45):
        r = urllib.request.Request(base + path, method=method, headers={
            "User-Agent": "StrongholdProtocol-Bundler/1.2",
            "Accept-Encoding": "identity",
        })
        return urllib.request.urlopen(r, timeout=timeout, context=ctx)

    with req("/data/assets.json", "GET") as r:
        raw = r.read()
    manifest = json.loads(raw.decode("utf-8"))
    server_hash = "%s:%s" % (manifest.get("version"), manifest.get("hash"))

    old_hash = ""
    try:
        with open(os.path.join(baseline, "info.txt"), encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("hash="):
                    old_hash = line.strip()[5:]
    except IOError:
        pass

    paths = collect(manifest)
    for v in VENDOR:
        paths.add(v)
    paths = sorted(paths)
    print("server %s   bundle %s   %d paths" % (server_hash, old_hash or "(未知)", len(paths)))

    def head(p):
        try:
            with req(p, "HEAD", 30) as r:
                return p, r.status, r.headers.get("ETag"), int(r.headers.get("Content-Length") or -1)
        except urllib.error.HTTPError as e:
            return p, e.code, None, -1
        except Exception as e:
            return p, 0, None, -1

    print("HEAD %d paths ..." % len(paths))
    with ThreadPoolExecutor(workers) as ex:
        heads = list(ex.map(head, paths))

    etags = {}
    need = []
    notfound = 0
    for p, status, etag, length in heads:
        if status != 200:
            notfound += 1
            continue
        if etag:
            etags[p] = etag
        local = os.path.join(mirror, p.lstrip("/"))
        if not os.path.isfile(local):
            need.append(p)
        elif length >= 0 and os.path.getsize(local) != length:
            need.append(p)
    print("  %d ok, %d not-200, %d files to (re)fetch" % (len(heads) - notfound, notfound, len(need)))

    def fetch(p):
        target = os.path.join(mirror, p.lstrip("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        try:
            with req(p, "GET", 120) as r:
                body = r.read()
            tmp = target + ".part"
            with open(tmp, "wb") as fh:
                fh.write(body)
            os.replace(tmp, target)
            return None
        except Exception as e:
            return (p, str(e))

    if need:
        with ThreadPoolExecutor(max(4, workers // 3)) as ex:
            errs = [e for e in ex.map(fetch, need) if e]
        for p, why in errs[:10]:
            print("  FETCH FAILED %s: %s" % (p, why))

    present = [p for p in paths if os.path.isfile(os.path.join(mirror, p.lstrip("/")))]
    with open(os.path.join(baseline, "assets.json"), "wb") as fh:
        fh.write(raw)
    with open(os.path.join(baseline, "etags.tsv"), "w", encoding="utf-8") as fh:
        for p in paths:
            if p in etags:
                fh.write("%s\t%s\n" % (p, etags[p]))
    with open(os.path.join(baseline, "index.txt"), "w", encoding="utf-8") as fh:
        for p in present:
            fh.write(p + "\n")

    total = sum(os.path.getsize(os.path.join(mirror, p.lstrip("/"))) for p in present)
    print("\nbaseline refreshed: %d files, %.1f MiB, %d etags" % (len(present), total / 1048576.0, len(etags)))
    print("run: python3 finalize_bundle.py %s" % dest)
    return 0 if (len(present) == len(paths) and not notfound) else 1


if __name__ == "__main__":
    sys.exit(main())
