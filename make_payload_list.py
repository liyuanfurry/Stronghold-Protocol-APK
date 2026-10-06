#!/usr/bin/env python3
"""Writes android/assets/payload.txt — the list of files the embedded server unpacks at first run.

The app walks this list instead of recursing with AssetManager.list(): the order is fixed, the file
count is known up front (so a progress bar can be honest), and there is no reliance on how a given
Android version happens to enumerate APK asset directories.

Format, one line per file:  <path under assets/> TAB <mode>
`mode` is what the file must be chmod'ed to after unpacking (the node binary has to be executable).

Usage: python3 make_payload_list.py <android-assets-dir>
"""
import os
import sys

# Directories that hold something the app runs itself rather than just reads.
EXECUTABLE = {"node/bin/node"}


def main():
    assets = sys.argv[1] if len(sys.argv) > 1 else "android/assets"
    roots = [d for d in ("node", "gsrv") if os.path.isdir(os.path.join(assets, d))]
    if not roots:
        raise SystemExit("no node/ or gsrv/ under %s — nothing to list" % assets)

    rows = []
    total = 0
    for root in roots:
        base = os.path.join(assets, root)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames.sort()
            for fn in sorted(filenames):
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, assets).replace(os.sep, "/")
                mode = 0o755 if rel in EXECUTABLE else 0o644
                rows.append((rel, mode))
                total += os.path.getsize(full)

    rows.sort()
    out = os.path.join(assets, "payload.txt")
    with open(out, "w", encoding="utf-8") as fh:
        for rel, mode in rows:
            fh.write("%s\t%o\n" % (rel, mode))

    print("payload.txt: %d files, %.1f MiB (unpacked)" % (len(rows), total / 1048576.0))
    for rel, _ in rows[:3]:
        print("   " + rel)
    print("   ...")
    for rel, _ in rows[-2:]:
        print("   " + rel)
    execs = [r for r, m in rows if m == 0o755]
    print("executable: %s" % (", ".join(execs) if execs else "(none)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
