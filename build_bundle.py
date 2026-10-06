#!/usr/bin/env python3
"""Assembles android/assets/ for the APK.

Bodies come from the upstream release bundle (GitHub's CDN is far faster than a self-hosted server's
uplink, and both carry the same payload), but the **ETags come from the live server**: an ETag is
derived from a file's size and mtime *as that server sees them*, so it cannot be recomputed from a zip
that stores different timestamps.

Every file is then reconciled: if the server's Content-Length disagrees with what the zip gave us, the
body is re-fetched from the server. A random sample is verified by SHA-256 as well.

Usage: python3 build_bundle.py <release.zip> <base-url> <android-assets-dir> [workers]
"""
import hashlib
import json
import os
import random
import ssl
import sys
import urllib.error
import urllib.request
import zipfile
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

SAMPLE = 40


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


def http(base, path, method, ctx, timeout=60):
    req = urllib.request.Request(base + path, method=method, headers={
        "User-Agent": "StrongholdProtocol-Bundler/1.1",
        "Accept-Encoding": "identity",
    })
    return urllib.request.urlopen(req, timeout=timeout, context=ctx)


def main():
    zip_path, base, dest = sys.argv[1], sys.argv[2].rstrip("/"), sys.argv[3]
    workers = int(sys.argv[4]) if len(sys.argv) > 4 else 24

    mirror = os.path.join(dest, "mirror")
    baseline = os.path.join(dest, "baseline")
    os.makedirs(mirror, exist_ok=True)
    os.makedirs(baseline, exist_ok=True)
    ctx = ssl.create_default_context()

    # ---- 1. what should be in the bundle ---------------------------------------------------------
    with http(base, "/data/assets.json", "GET", ctx) as r:
        server_manifest_raw = r.read()
    server_manifest = json.loads(server_manifest_raw.decode("utf-8"))
    server_hash = "%s:%s" % (server_manifest.get("version"), server_manifest.get("hash"))

    paths = collect_manifest_paths(server_manifest)
    for v in VENDOR:
        paths.add(v)
    paths = sorted(paths)
    print("server manifest %s -> %d paths" % (server_hash, len(paths)))

    # ---- 2. extract the same payload out of the release zip --------------------------------------
    zf = zipfile.ZipFile(zip_path)
    names = zf.namelist()
    prefix = None
    for n in names:
        if n.endswith("public/index.html"):
            prefix = n[: -len("public/index.html")]
            break
    if prefix is None:
        raise SystemExit("no public/index.html inside %s" % zip_path)
    print("zip prefix: %r" % prefix)

    zip_manifest = None
    try:
        zip_manifest = json.loads(zf.read(prefix + "data/assets.json").decode("utf-8"))
        print("zip manifest  %s:%s" % (zip_manifest.get("version"), zip_manifest.get("hash")))
    except KeyError:
        print("zip has no data/assets.json")

    extracted = 0
    for want in paths:
        src = prefix + ("public" + want).lstrip("/")
        target = os.path.join(mirror, want.lstrip("/"))
        try:
            info = zf.getinfo(src)
        except KeyError:
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with zf.open(info) as srcfh, open(target, "wb") as dstfh:
            dstfh.write(srcfh.read())
        extracted += 1
    print("extracted %d/%d paths from the zip" % (extracted, len(paths)))

    # ---- 3. ask the server for the authoritative ETag + length of every path ---------------------
    def head(path):
        try:
            with http(base, path, "HEAD", ctx, timeout=30) as r:
                return path, r.status, r.headers.get("ETag"), int(r.headers.get("Content-Length") or -1)
        except urllib.error.HTTPError as e:
            return path, e.code, None, -1
        except Exception as e:
            return path, 0, None, -1

    print("HEAD %d paths ..." % len(paths))
    with ThreadPoolExecutor(workers) as ex:
        heads = list(ex.map(head, paths))

    bad = [h for h in heads if h[1] != 200]
    print("  %d ok, %d not-200" % (len(heads) - len(bad), len(bad)))

    etags = {}
    need_fetch = []
    for path, status, etag, length in heads:
        if status != 200:
            continue
        if etag:
            etags[path] = etag
        local = os.path.join(mirror, path.lstrip("/"))
        if not os.path.isfile(local) or os.path.getsize(local) != length:
            need_fetch.append((path, length))

    print("  %d paths need the body from the server" % len(need_fetch))

    def fetch(item):
        path, _ = item
        target = os.path.join(mirror, path.lstrip("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        try:
            with http(base, path, "GET", ctx) as r:
                body = r.read()
            tmp = target + ".part"
            with open(tmp, "wb") as fh:
                fh.write(body)
            os.replace(tmp, target)
            return None
        except Exception as e:
            return (path, str(e))

    if need_fetch:
        with ThreadPoolExecutor(max(4, workers // 3)) as ex:
            errs = [e for e in ex.map(fetch, need_fetch) if e]
        for p, why in errs[:10]:
            print("  FETCH FAILED %s: %s" % (p, why))

    # ---- 4. spot-check a random sample byte for byte ---------------------------------------------
    have = [p for p in paths if os.path.isfile(os.path.join(mirror, p.lstrip("/")))]
    random.seed(11)
    sample = random.sample(have, min(SAMPLE, len(have)))
    mismatches = 0

    def digest(path):
        local = os.path.join(mirror, path.lstrip("/"))
        h = hashlib.sha256()
        with open(local, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        try:
            with http(base, path, "GET", ctx) as r:
                remote = hashlib.sha256(r.read()).hexdigest()
        except Exception as e:
            return path, "unreachable: %s" % e
        return path, None if h.hexdigest() == remote else "SHA-256 differs"

    with ThreadPoolExecutor(8) as ex:
        for path, problem in ex.map(digest, sample):
            if problem:
                mismatches += 1
                print("  MISMATCH %s: %s" % (path, problem))
    print("sha256 spot-check: %d/%d identical" % (len(sample) - mismatches, len(sample)))

    # ---- 5. baseline metadata --------------------------------------------------------------------
    with open(os.path.join(baseline, "assets.json"), "wb") as fh:
        fh.write(server_manifest_raw)

    with open(os.path.join(baseline, "etags.tsv"), "w", encoding="utf-8") as fh:
        for p in paths:
            if p in etags:
                fh.write("%s\t%s\n" % (p, etags[p]))

    present = []
    for p in paths:
        if os.path.isfile(os.path.join(mirror, p.lstrip("/"))):
            present.append(p)
    with open(os.path.join(baseline, "index.txt"), "w", encoding="utf-8") as fh:
        for p in present:
            fh.write(p + "\n")

    total = sum(os.path.getsize(os.path.join(mirror, p.lstrip("/"))) for p in present)
    print("\nbaseline: %d files, %.1f MiB, %d with an ETag" % (len(present), total / 1048576.0, len(etags)))
    missing = [p for p in paths if p not in present]
    for p in missing[:15]:
        print("  MISSING %s" % p)
    return 1 if (missing or mismatches or bad) else 0


if __name__ == "__main__":
    sys.exit(main())
