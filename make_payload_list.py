#!/usr/bin/env python3
"""Writes <assets>/payload.txt - the files the app unpacks at first run.

Only gsrv/ is listed. The Node runtime and its libraries are NOT unpacked: they ship as
lib/arm64-v8a/lib*.so so the installer places them in the app's native library directory, which is
the one location Android lets an app execute from and which needs no runtime chmod.

The app walks this list instead of recursing with AssetManager.list(): the order is fixed, the file
count is known up front (so a progress bar can be honest), and nothing depends on how a given Android
version happens to enumerate APK asset directories.

Format, one line per file:  <path under assets/> TAB <mode>

Usage: python3 make_payload_list.py <assets-dir>
"""
import os
import sys


def main():
    assets = sys.argv[1] if len(sys.argv) > 1 else "android/assets"
    root = "gsrv"
    base = os.path.join(assets, root)
    if not os.path.isdir(base):
        raise SystemExit("no %s under %s - run make_native_payload.py first" % (root, assets))

    rows = []
    total = 0
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for fn in sorted(filenames):
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, assets).replace(os.sep, "/")
            rows.append(rel)
            total += os.path.getsize(full)

    rows.sort()
    out = os.path.join(assets, "payload.txt")
    with open(out, "w", encoding="utf-8") as fh:
        for rel in rows:
            fh.write("%s\t644\n" % rel)

    print("payload.txt: %d files, %.1f MiB (unpacked)" % (len(rows), total / 1048576.0))
    for rel in rows[:3]:
        print("   " + rel)
    if len(rows) > 3:
        print("   ...")
    return 0


if __name__ == "__main__":
    sys.exit(main())
