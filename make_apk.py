#!/usr/bin/env python3
"""Assembles the final APK from aapt2's --output-to-dir contents.

Termux ships no `zipalign`, and Android 11+ refuses to install an app whose
`resources.arsc` is not both *uncompressed* and *4-byte aligned*. Rather than post-process a
finished zip (which means rewriting offsets in the central directory), this writes the archive
from scratch and puts the alignment padding straight into each local header's extra field, the
same way zipalign does.

Everything under `assets/` is stored uncompressed and streamed straight from disk: the bundled
media payload is ~270 MiB, and AssetManager hands out a memory-mapped stream for a stored asset
but has to inflate a deflated one. Nothing is buffered whole in memory.

Usage: python3 make_apk.py <contents-dir> <out.apk>
"""
import os
import shutil
import struct
import sys
import zlib

ALIGN = 4
# Android requires resources.arsc uncompressed; the rest are already-compressed formats where
# deflating only burns CPU.
STORE_EXT = {
    ".arsc", ".png", ".jpg", ".jpeg", ".webp", ".gif",
    ".mp3", ".ogg", ".wav", ".m4a", ".aac", ".opus", ".flac", ".oga",
}
# A fixed DOS timestamp (1980-01-01) keeps builds byte-reproducible.
DOS_TIME = 0
DOS_DATE = 0x0021
CHUNK = 1 << 20


def store_as(name):
    if name == "resources.arsc" or name.startswith("assets/"):
        return True
    return os.path.splitext(name)[1].lower() in STORE_EXT


def deflate(data):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def crc_of(path):
    crc = 0
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(CHUNK)
            if not chunk:
                break
            crc = zlib.crc32(chunk, crc)
    return crc & 0xFFFFFFFF


def collect(src, assets_dir=None, lib_dir=None):
    out = []
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for fn in files:
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, src).replace(os.sep, "/")
            out.append((rel, full))
    for tree, prefix in ((assets_dir, "assets/"), (lib_dir, "lib/")):
        if not tree:
            continue
        # Read straight from the source tree rather than copying hundreds of MiB into the staging
        # directory first. lib/ matters for more than tidiness: the installer extracts those into the
        # app's native library directory, the only place an app may execute from.
        for root, dirs, files in os.walk(tree):
            dirs.sort()
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, tree).replace(os.sep, "/")
                out.append((prefix + rel, full))
    out.sort(key=lambda e: e[0])
    return out


def build(src, dest, assets_dir=None, lib_dir=None):
    files = collect(src, assets_dir, lib_dir)
    if not files:
        raise SystemExit("no files under " + src)

    central = []
    stored = aligned = deflated = 0

    with open(dest, "wb") as out:
        for name, full in files:
            nb = name.encode("utf-8")
            raw_size = os.path.getsize(full)

            if store_as(name):
                method = 0
            else:
                with open(full, "rb") as fh:
                    data = fh.read()
                body = deflate(data)
                # Incompressible: storing is smaller *and* earns alignment.
                method = 0 if len(body) >= raw_size else 8
                if method == 0:
                    body = data
                del data

            # The CRC in a zip entry is over the *uncompressed* bytes, whatever the method is.
            # Computing it over `body` for a deflated entry ships a wrong checksum: the Android
            # installer never reads it, but strict readers (unzip -t, ZipInputStream) do.
            crc = crc_of(full)
            if method == 0:
                body_size = raw_size
                stored += 1
            else:
                body_size = len(body)
                deflated += 1

            offset = out.tell()
            extra = b""
            if method == 0:
                base = offset + 30 + len(nb)
                if base % ALIGN != 0:
                    n = (ALIGN - ((base + 4) % ALIGN)) % ALIGN
                    extra = struct.pack("<HH", 0xD935, n) + b"\x00" * n
                aligned += 1

            out.write(struct.pack(
                "<IHHHHHIIIHH",
                0x04034B50, 20, 0, method, DOS_TIME, DOS_DATE,
                crc, body_size, raw_size, len(nb), len(extra)))
            out.write(nb)
            out.write(extra)
            if method == 0:
                with open(full, "rb") as fh:
                    shutil.copyfileobj(fh, out, CHUNK)
            else:
                out.write(body)
                del body

            central.append(struct.pack(
                "<IHHHHHHIIIHHHHHII",
                0x02014B50, 20, 20, 0, method, DOS_TIME, DOS_DATE,
                crc, body_size, raw_size, len(nb), 0, 0, 0, 0,
                0x81A40000, offset) + nb)

        cd_offset = out.tell()
        for rec in central:
            out.write(rec)
        cd_size = out.tell() - cd_offset
        out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central),
                              cd_size, cd_offset, 0))

    return len(files), stored, aligned, deflated, os.path.getsize(dest)


def main():
    if len(sys.argv) not in (3, 4, 5):
        raise SystemExit(__doc__)
    assets_dir = sys.argv[3] if len(sys.argv) >= 4 else None
    lib_dir = sys.argv[4] if len(sys.argv) == 5 else None
    count, stored, aligned, deflated, size = build(sys.argv[1], sys.argv[2], assets_dir, lib_dir)
    print("packed %d entries (%d stored %d deflated, %d aligned to %d) -> %.1f MiB"
          % (count, stored, deflated, aligned, ALIGN, size / 1048576.0))


if __name__ == "__main__":
    main()
