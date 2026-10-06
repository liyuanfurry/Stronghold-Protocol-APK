#!/usr/bin/env python3
"""Writes android/assets/baseline/info.txt from what fetch_bundle.py actually produced.

The app reads this tiny file at startup instead of parsing the 676 KiB manifest: it carries the
payload version this APK shipped with (the thing "校对版本" compares against) plus the file count and
total size for the settings screen.

Run after fetch_bundle.py:
    python3 finalize_bundle.py <android-assets-dir>
"""
import json
import os
import sys


def main():
    assets = sys.argv[1] if len(sys.argv) > 1 else "android/assets"
    baseline = os.path.join(assets, "baseline")
    mirror = os.path.join(assets, "mirror")

    with open(os.path.join(baseline, "assets.json"), "rb") as fh:
        manifest = json.loads(fh.read().decode("utf-8"))

    version = manifest.get("version")
    digest = manifest.get("hash")
    if digest is None:
        raise SystemExit("manifest has no hash; cannot stamp the bundle")

    files = 0
    total = 0
    for root, dirs, names in os.walk(mirror):
        for n in names:
            if n.endswith(".part"):
                continue
            files += 1
            total += os.path.getsize(os.path.join(root, n))

    listed = 0
    with open(os.path.join(baseline, "index.txt"), encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                listed += 1

    if files < listed:
        print("WARNING: only %d of %d listed files are on disk" % (files, listed))

    out = os.path.join(baseline, "info.txt")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("version=%s\n" % version)
        fh.write("hash=%s:%s\n" % (version, digest))     # matches "<version>:<hash>" from /data/assets.json
        fh.write("files=%d\n" % files)
        fh.write("bytes=%d\n" % total)

    print("wrote %s" % out)
    print("  version=%s hash=%s" % (version, digest))
    print("  files=%d bytes=%d (%.1f MiB)" % (files, total, total / 1048576.0))
    if files < listed:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
