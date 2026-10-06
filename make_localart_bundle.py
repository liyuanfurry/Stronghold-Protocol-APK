#!/usr/bin/env python3
"""Bundle the host's "local client art" into the APK.

Why this exists: the game ships two artwork indexes, and only one is media.

  data/assets.json        the web artwork        -> already in the bundle (272 MiB)
  data/local-assets.json  the official art a host extracted from a game client
                          (real board atlas, UI sprites, emotes, guide pages)

The second one lives under /assets/local/... , which the WebView interception already serves from
disk — but those files were never part of the bundle, so every session re-fetched ~1500 files
(~87 MiB) from the host, and the first visit to each screen waited on the network. Bundling them
makes the client genuinely self-contained and removes that traffic entirely.

tiles.json is not listed by any index: render/boardArt.js fetches it from the board atlas's own
directory, so it is derived the same way here.

Usage:
  python3 make_localart_bundle.py <mirror-dir> <baseline-dir> <server-base-url> [--jobs N]
"""
import json
import os
import ssl
import sys
import threading
import urllib.request
import concurrent.futures as cf

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
UA = "make_localart_bundle/1.0"
lock = threading.Lock()


def fetch(url, body=True, tries=3):
    last = None
    for _ in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90, context=CTX) as r:
                return (r.read() if body else b""), dict(r.headers)
        except Exception as e:
            last = e
    raise last


def main():
    mirror, baseline, server = sys.argv[1], sys.argv[2], sys.argv[3].rstrip("/")
    jobs = 6
    if "--jobs" in sys.argv:
        jobs = int(sys.argv[sys.argv.index("--jobs") + 1])

    sys.stderr.write("reading %s/data/local-assets.json\n" % server)
    raw, _ = fetch(server + "/data/local-assets.json")
    manifest = json.loads(raw.decode("utf-8"))
    groups = manifest.get("groups") or {}

    paths = []
    for grp, items in groups.items():
        for name, entry in items.items():
            p = entry.get("path") if isinstance(entry, dict) else None
            if isinstance(p, str) and p.startswith("/"):
                paths.append(p)
    atlas = ((groups.get("map/autochess") or {}).get("TX_autochessi_D") or {}).get("path")
    if atlas:
        paths.append(atlas[: atlas.rfind("/") + 1] + "tiles.json")
    paths = sorted(set(paths))
    sys.stderr.write("%d files to bundle\n" % len(paths))

    tag_file = os.path.join(baseline, "localart.tsv")
    known = {}
    if os.path.isfile(tag_file):
        for line in open(tag_file, encoding="utf-8"):
            t = line.rstrip("\n").split("\t")
            if len(t) == 3:
                known[t[0]] = (t[1], int(t[2]))

    done = [0]
    bytes_total = [0]

    def one(p):
        dest = os.path.join(mirror, p.lstrip("/"))
        cached = known.get(p)
        if cached and os.path.isfile(dest) and os.path.getsize(dest) == cached[1]:
            with lock:
                done[0] += 1
                bytes_total[0] += cached[1]
            return p, cached[0], cached[1]
        body, headers = fetch(server + p)
        parent = os.path.dirname(dest)
        if parent and not os.path.isdir(parent):
            os.makedirs(parent, exist_ok=True)
        with open(dest, "wb") as fh:
            fh.write(body)
        etag = headers.get("ETag", "")
        with lock:
            done[0] += 1
            bytes_total[0] += len(body)
            if done[0] % 100 == 0 or done[0] == len(paths):
                sys.stderr.write("  %d/%d  %.1f MiB\n" % (done[0], len(paths), bytes_total[0] / 1048576))
        return p, etag, len(body)

    results = {}
    with cf.ThreadPoolExecutor(max_workers=jobs) as ex:
        for p, etag, size in ex.map(one, paths):
            results[p] = (etag, size)

    with open(tag_file, "w", encoding="utf-8") as fh:
        for p in sorted(results):
            fh.write("%s\t%s\t%d\n" % (p, results[p][0], results[p][1]))

    # ---- fold them into the bundle's index and etag table, idempotently
    index = os.path.join(baseline, "index.txt")
    have = set()
    if os.path.isfile(index):
        have = set(l.strip() for l in open(index, encoding="utf-8") if l.strip())
    added = [p for p in results if p not in have]
    if added:
        with open(index, "a", encoding="utf-8") as fh:
            for p in sorted(added):
                fh.write(p + "\n")

    tags = os.path.join(baseline, "etags.tsv")
    tag_lines = {}
    if os.path.isfile(tags):
        for line in open(tags, encoding="utf-8"):
            t = line.rstrip("\n").split("\t")
            if len(t) == 2:
                tag_lines[t[0]] = t[1]
    for p, (etag, _size) in results.items():
        if etag:
            tag_lines[p] = etag
    with open(tags, "w", encoding="utf-8") as fh:
        for p in sorted(tag_lines):
            fh.write("%s\t%s\n" % (p, tag_lines[p]))

    total_files = total_bytes = 0
    for dirpath, _dirs, files in os.walk(mirror):
        for fn in files:
            total_files += 1
            total_bytes += os.path.getsize(os.path.join(dirpath, fn))

    info = os.path.join(baseline, "info.txt")
    lines = []
    if os.path.isfile(info):
        for line in open(info, encoding="utf-8"):
            if line.startswith(("version=", "hash=")):
                lines.append(line.rstrip("\n"))
    with open(info, "w", encoding="utf-8") as fh:
        for l in lines:
            fh.write(l + "\n")
        fh.write("files=%d\nbytes=%d\n" % (total_files, total_bytes))

    sys.stderr.write("bundled %d new files; bundle now %d files, %.1f MiB\n"
                     % (len(added), total_files, total_bytes / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
