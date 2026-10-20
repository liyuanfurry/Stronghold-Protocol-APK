#!/usr/bin/env python3
"""Assembles android/assets/ from the upstream release zip alone, with no server contact.

Used when the update host is unreachable. Everything the server would normally supply is substituted:

  manifest   <- the ZIP's own data/assets.json (byte-identical to the host's when the versions match)
  bodies     <- the ZIP
  ETags      <- none. The app then cannot revalidate incrementally; a sync would transfer everything.
                The manifest-hash gate still keeps the steady state at one request, so the cost only
                appears when the host actually publishes a new version.

Usage: python3 build_bundle_offline.py <release.zip> <android-assets-dir>
"""
import json
import os
import sys
import zipfile

VENDOR = ["/vendor/preact.module.js", "/vendor/hooks.module.js", "/vendor/htm.module.js",
          "/vendor/pixi.min.js", "/vendor/pixi-spine.js", "/vendor/three.core.js", "/vendor/three.module.js"]


def manifest_paths(m):
    out = set()

    def walk(n):
        if isinstance(n, dict):
            for v in n.values():
                walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)
        elif isinstance(n, str) and n.startswith(("/assets/", "/fonts/")):
            out.add(n)

    walk(m)
    return out


def main():
    zpath, dest = sys.argv[1], sys.argv[2]
    mirror = os.path.join(dest, "mirror")
    baseline = os.path.join(dest, "baseline")
    os.makedirs(mirror, exist_ok=True)
    os.makedirs(baseline, exist_ok=True)

    with zipfile.ZipFile(zpath) as zf:
        names = zf.namelist()
        prefix = None
        for n in names:
            if n.endswith("public/index.html"):
                prefix = n[: -len("public/index.html")]
                break
        if prefix is None:
            raise SystemExit("no public/index.html in %s" % zpath)
        raw = zf.read(prefix + "data/assets.json")
        manifest = json.loads(raw.decode("utf-8"))
        print("zip manifest %s:%s" % (manifest.get("version"), manifest.get("hash")))

        paths = sorted(manifest_paths(manifest) | set(VENDOR))
        written = 0
        for p in paths:
            src = prefix + ("public" + p).lstrip("/")
            try:
                info = zf.getinfo(src)
            except KeyError:
                continue
            target = os.path.join(mirror, p.lstrip("/"))
            d = os.path.dirname(target)
            if d and not os.path.isdir(d):
                os.makedirs(d, exist_ok=True)
            with zf.open(info) as s, open(target, "wb") as o:
                o.write(s.read())
            written += 1
        print("extracted %d/%d paths" % (written, len(paths)))

    present = []
    total = 0
    for p in paths:
        f = os.path.join(mirror, p.lstrip("/"))
        if os.path.isfile(f):
            present.append(p)
            total += os.path.getsize(f)
    with open(os.path.join(baseline, "index.txt"), "w", encoding="utf-8") as fh:
        for p in present:
            fh.write(p + "\n")
    open(os.path.join(baseline, "etags.tsv"), "w", encoding="utf-8").close()
    with open(os.path.join(baseline, "assets.json"), "wb") as fh:
        fh.write(raw)
    with open(os.path.join(baseline, "info.txt"), "w", encoding="utf-8") as fh:
        fh.write("version=%s\nhash=%s:%s\nfiles=%d\nbytes=%d\n" % (
            manifest.get("version"), manifest.get("version"), manifest.get("hash"), len(present), total))
    print("baseline: %d files, %.1f MiB, NO ETags (offline build)" % (len(present), total / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
