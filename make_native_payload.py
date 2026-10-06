#!/usr/bin/env python3
"""Assembles android/assets/{node,gsrv} — what the on-phone game server needs to run.

Nothing under android/assets/ is committed. For `node/` that is because it is a pile of Termux binaries;
for `gsrv/` because it is upstream GPL source that belongs in the upstream repository rather than
vendored here. Both are rebuilt by this script:

    node/   Bionic Node 24 + the shared libraries it links against, straight out of Termux .debs
    gsrv/   upstream server code at a tag, plus the `ws` module from npm

The needed SONAMEs are read out of the Node binary's own DT_NEEDED, so bumping the Node version does
not silently produce a payload with a missing library.

Usage:
  python3 make_native_payload.py <assets-dir> <upstream-checkout> [--tag <ref>]
                                 [--mirror <termux-repo-url>] [--keep-debs <dir>]
"""
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request

PACKAGES = ["nodejs-lts", "libicu", "libc++", "openssl", "c-ares", "libsqlite", "zlib"]
WS_VERSION = "8.22.0"
# Copied into gsrv/. public/{assets,fonts} are deliberately absent: the app's own WebView serves those
# out of the APK for every device running this client, so the host does not need to ship 272 MiB.
GSRV_TREES = ["server", "shared", "data", "package.json"]
GSRV_PUBLIC = ["css", "js", "index.html"]


# ---- tiny ELF reader: what does this binary need to be linked against? ---------------------------

def needed_sonames(path):
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:4] != b"\x7fELF" or data[4] != 2:            # 64-bit only
        raise SystemExit("%s is not a 64-bit ELF" % path)
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, stype, _flags, _addr, offset, size, link, _info, _align, entsize = \
            struct.unpack_from("<IIQQQQIIQQ", data, off)
        sections.append(dict(name=name, type=stype, offset=offset, size=size,
                             link=link, entsize=entsize))
    dyn = next((s for s in sections if s["type"] == 6), None)      # SHT_DYNAMIC
    if dyn is None:
        return []
    strtab = sections[dyn["link"]]
    strings = data[strtab["offset"]:strtab["offset"] + strtab["size"]]
    out = []
    for i in range(dyn["size"] // 16):
        tag, val = struct.unpack_from("<qQ", data, dyn["offset"] + i * 16)
        if tag == 0:
            break
        if tag == 1:                                                # DT_NEEDED
            end = strings.index(b"\0", val)
            out.append(strings[val:end].decode())
    return out


# ---- .deb extraction without dpkg ----------------------------------------------------------------

def ar_members(blob):
    if blob[:8] != b"!<arch>\n":
        raise SystemExit("not an ar archive")
    pos, out = 8, {}
    while pos + 60 <= len(blob):
        header = blob[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        start = pos + 60
        out[name] = blob[start:start + size]
        pos = start + size + (size & 1)
    return out


def deb_data_tar(path):
    with open(path, "rb") as fh:
        members = ar_members(fh.read())
    for name in ("data.tar.xz", "data.tar.gz", "data.tar.zst", "data.tar"):
        if name in members:
            if name.endswith(".zst"):
                raise SystemExit("data.tar.zst unsupported; install zstd or pick another mirror")
            return tarfile.open(fileobj=io.BytesIO(members[name]), mode="r:*")
    raise SystemExit("no data.tar in %s (%s)" % (path, list(members)))


# The Termux CDN answers 403 to a request with no User-Agent, so every fetch carries one.
UA = "make_native_payload/1.0 (+https://github.com)"


def fetch(url, dest):
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print("  GET %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as fh:
        shutil.copyfileobj(r, fh, 1 << 20)
    return dest


def package_filenames(mirror):
    index = fetch(mirror.rstrip("/") + "/dists/stable/main/binary-aarch64/Packages",
                  ".native-payload/Packages")
    want, cur = {}, None
    with open(index, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("Package: "):
                cur = line[9:].strip()
            elif line.startswith("Filename: ") and cur in PACKAGES:
                want[cur] = line[10:].strip()
    missing = [p for p in PACKAGES if p not in want]
    if missing:
        raise SystemExit("mirror does not carry: %s" % ", ".join(missing))
    return want


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    assets, upstream = sys.argv[1], sys.argv[2]
    mirror = "https://packages.termux.dev/apt/termux-main"
    debdir = ".native-payload"
    args = sys.argv[3:]
    for i, a in enumerate(args):
        if a == "--mirror":
            mirror = args[i + 1]
        elif a == "--keep-debs":
            debdir = args[i + 1]
    os.makedirs(debdir, exist_ok=True)

    node_out = os.path.join(assets, "node")
    gsrv_out = os.path.join(assets, "gsrv")
    shutil.rmtree(node_out, ignore_errors=True)
    os.makedirs(os.path.join(node_out, "bin"), exist_ok=True)
    os.makedirs(os.path.join(node_out, "lib"), exist_ok=True)

    # ---- 1. Termux packages ----------------------------------------------------------------------
    print("Termux packages:")
    filenames = package_filenames(mirror)
    roots = []
    for pkg in PACKAGES:
        deb = fetch(mirror.rstrip("/") + "/" + filenames[pkg],
                    os.path.join(debdir, os.path.basename(filenames[pkg])))
        staging = os.path.join(debdir, "x", pkg)
        shutil.rmtree(staging, ignore_errors=True)
        deb_data_tar(deb).extractall(staging)
        root = os.path.join(staging, "data/data/com.termux/files/usr")
        roots.append(root if os.path.isdir(root) else staging)
        print("  %-12s ok" % pkg)

    def find(rel):
        for r in roots:
            p = os.path.join(r, rel)
            if os.path.exists(p):
                return os.path.realpath(p)
        return None

    node_src = find("bin/node")
    if not node_src:
        raise SystemExit("no bin/node in any package")
    shutil.copyfile(node_src, os.path.join(node_out, "bin/node"))
    os.chmod(os.path.join(node_out, "bin/node"), 0o755)

    # Transitive closure, not just the binary's own list: libicuuc.so needs libicudata.so, and node
    # itself never mentions it — copying only the direct dependencies ships a payload that cannot start.
    SYSTEM = ("libc.", "libm.", "libdl.", "liblog.", "libandroid.")
    libdirs = [os.path.join(r, "lib") for r in roots]

    def locate(so):
        for d in libdirs:
            cand = os.path.join(d, so)
            if os.path.exists(cand):
                return os.path.realpath(cand)
        return None

    resolved = {}
    queue = list(needed_sonames(node_src))
    while queue:
        so = queue.pop(0)
        if so in resolved or so.startswith(SYSTEM):
            continue
        src = locate(so)
        if src is None:
            raise SystemExit("cannot resolve %s — is the mirror complete?" % so)
        resolved[so] = src
        for dep in needed_sonames(src):
            if dep not in resolved and dep not in queue:
                queue.append(dep)

    print("node needs %d libraries (transitively):" % len(resolved))
    for so in sorted(resolved):
        shutil.copyfile(resolved[so], os.path.join(node_out, "lib", so))
        print("  %-22s %.1f MB" % (so, os.path.getsize(resolved[so]) / 1048576.0))

    # ---- 2. upstream server code -----------------------------------------------------------------
    print("upstream server code from %s:" % upstream)
    subprocess.check_call(["git", "-C", upstream, "rev-parse", "--short", "HEAD"],
                          stdout=subprocess.DEVNULL)
    shutil.rmtree(gsrv_out, ignore_errors=True)
    for tree in GSRV_TREES:
        src = os.path.join(upstream, tree)
        dst = os.path.join(gsrv_out, tree)
        if not os.path.exists(src):
            raise SystemExit("missing %s in %s" % (tree, upstream))
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        else:
            shutil.copyfile(src, dst)
    os.makedirs(os.path.join(gsrv_out, "public"), exist_ok=True)
    for item in GSRV_PUBLIC:
        src = os.path.join(upstream, "public", item)
        dst = os.path.join(gsrv_out, "public", item)
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        else:
            shutil.copyfile(src, dst)
    print("  server/ shared/ data/ public/{css,js,index.html}")

    # ---- 3. ws from npm --------------------------------------------------------------------------
    tgz = fetch("https://registry.npmjs.org/ws/-/ws-%s.tgz" % WS_VERSION,
                os.path.join(debdir, "ws-%s.tgz" % WS_VERSION))
    ws_dir = os.path.join(gsrv_out, "node_modules", "ws")
    shutil.rmtree(ws_dir, ignore_errors=True)
    with tarfile.open(tgz, "r:gz") as tf:
        tmp = os.path.join(debdir, "wsx")
        shutil.rmtree(tmp, ignore_errors=True)
        tf.extractall(tmp)
    shutil.move(os.path.join(tmp, "package"), ws_dir)
    print("  node_modules/ws %s" % WS_VERSION)

    # ---- 4. the unpack list the app walks --------------------------------------------------------
    subprocess.check_call([sys.executable,
                           os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                        "make_payload_list.py"), assets])
    print("\ndone. build the APK with ./build.sh")
    return 0


if __name__ == "__main__":
    sys.exit(main())
