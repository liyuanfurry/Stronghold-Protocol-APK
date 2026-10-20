#!/usr/bin/env python3
"""HEAD every bundled path once and write baseline/etags.tsv. No bodies are transferred.

Used when the media is already extracted (from the release zip) and only the authoritative ETags are
missing — the host derives them from its own size+mtime, so they cannot be computed locally.
"""
import os
import ssl
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = sys.argv[1].rstrip("/")
ASSETS = sys.argv[2]
WORKERS = int(sys.argv[3]) if len(sys.argv) > 3 else 24

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def head(path):
    for _ in range(3):
        try:
            req = urllib.request.Request(BASE + path, method="HEAD",
                                         headers={"User-Agent": "dsh", "Accept-Encoding": "identity"})
            with urllib.request.urlopen(req, timeout=30, context=CTX) as r:
                return path, r.status, r.headers.get("ETag")
        except urllib.error.HTTPError as e:
            return path, e.code, None
        except Exception:
            continue
    return path, 0, None


def main():
    index = os.path.join(ASSETS, "baseline/index.txt")
    paths = [l.strip() for l in open(index, encoding="utf-8") if l.strip()]
    print("HEAD %d paths against %s ..." % (len(paths), BASE), flush=True)
    done = [0]
    out = {}
    bad = []
    with ThreadPoolExecutor(WORKERS) as ex:
        for path, status, etag in ex.map(head, paths):
            done[0] += 1
            if status == 200 and etag:
                out[path] = etag
            else:
                bad.append((path, status))
            if done[0] % 1000 == 0:
                print("  %d/%d  (%d with ETag)" % (done[0], len(paths), len(out)), flush=True)
    with open(os.path.join(ASSETS, "baseline/etags.tsv"), "w", encoding="utf-8") as fh:
        for p in paths:
            if p in out:
                fh.write("%s\t%s\n" % (p, out[p]))
    print("wrote %d ETags; %d paths without one" % (len(out), len(bad)), flush=True)
    for p, s in bad[:10]:
        print("  MISS %s (status %s)" % (p, s))
    return 0


if __name__ == "__main__":
    sys.exit(main())
