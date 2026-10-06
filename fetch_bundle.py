#!/usr/bin/env python3
"""Build-time: pulls the game's media payload off a running server into android/assets/.

The APK ships this payload so a fresh install has every texture, Spine model, sound effect and webfont
without touching the network. Three things come out of it:

  mirror/<path>          the bytes, laid out exactly as the server serves them
  baseline/etags.tsv     path -> ETag, so the app can later revalidate a *bundled* file against the
                         server with If-None-Match and re-download only what actually changed
  baseline/assets.json   the manifest this bundle was built from (its hash is the bundle's version)
  baseline/index.txt     flat list of bundled paths, for O(1) "is this in the APK?" lookups

Usage: python3 fetch_bundle.py <base-url> <android-assets-dir> [workers]
"""
import json
import os
import ssl
import sys
import threading
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

lock = threading.Lock()
done = [0]
bytes_done = [0]
failed = []


def collect_manifest_paths(manifest):
    out = set()

    def walk(node):
        if isinstance(node, dict):
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
        elif isinstance(node, str) and (node.startswith("/assets/") or node.startswith("/fonts/")):
            out.add(node)

    walk(manifest)
    return out


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else "https://sp.fur-ly.me:8443").rstrip("/")
    dest = sys.argv[2]
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 12

    mirror = os.path.join(dest, "mirror")
    baseline = os.path.join(dest, "baseline")
    os.makedirs(mirror, exist_ok=True)
    os.makedirs(baseline, exist_ok=True)

    ctx = ssl.create_default_context()

    def get(path, binary=True, timeout=90):
        req = urllib.request.Request(base + path, headers={
            "User-Agent": "StrongholdProtocol-Bundler/1.0",
            "Accept-Encoding": "identity",
        })
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            return r.read(), r.headers.get("ETag")

    print("fetching manifest %s/data/assets.json" % base)
    raw, _ = get("/data/assets.json")
    manifest = json.loads(raw.decode("utf-8"))

    paths = collect_manifest_paths(manifest)
    for v in VENDOR:
        paths.add(v)
    paths = sorted(paths)
    total = len(paths)
    print("manifest hash=%s version=%s -> %d paths" % (manifest.get("hash"), manifest.get("version"), total))

    with open(os.path.join(baseline, "assets.json"), "wb") as fh:
        fh.write(raw)

    etags = {}

    def one(path):
        target = os.path.join(mirror, path.lstrip("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.isfile(target) and os.path.getsize(target) > 0:
            with lock:
                done[0] += 1
                bytes_done[0] += os.path.getsize(target)
            return
        last = None
        for attempt in range(3):
            try:
                body, etag = get(path)
                tmp = target + ".part"
                with open(tmp, "wb") as fh:
                    fh.write(body)
                os.replace(tmp, target)
                with lock:
                    if etag:
                        etags[path] = etag
                    done[0] += 1
                    bytes_done[0] += len(body)
                    if done[0] % 250 == 0 or done[0] == total:
                        print("  %d/%d  %.1f MiB" % (done[0], total, bytes_done[0] / 1048576.0), flush=True)
                return
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    with lock:
                        failed.append((path, "404"))
                        done[0] += 1
                    return
                last = e
            except Exception as e:
                last = e
            import time
            time.sleep(0.4 * (attempt + 1))
        with lock:
            failed.append((path, str(last)))
            done[0] += 1

    with ThreadPoolExecutor(workers) as ex:
        list(ex.map(one, paths))

    with open(os.path.join(baseline, "etags.tsv"), "w", encoding="utf-8") as fh:
        for p in paths:
            if p in etags:
                fh.write("%s\t%s\n" % (p, etags[p]))
    with open(os.path.join(baseline, "index.txt"), "w", encoding="utf-8") as fh:
        for p in paths:
            fh.write(p + "\n")

    print("\ndone: %d/%d paths, %.1f MiB, %d without etag, %d failed"
          % (total - len(failed), total, bytes_done[0] / 1048576.0, total - len(etags), len(failed)))
    for p, why in failed[:20]:
        print("  FAILED %s: %s" % (p, why))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
