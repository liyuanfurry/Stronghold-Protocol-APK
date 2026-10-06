#!/usr/bin/env python3
"""Assembles the on-phone game server payload: android/lib/arm64-v8a/ + android/assets/gsrv/.

    lib/arm64-v8a/   Node 24 plus every shared library it needs, all named lib*.so
    assets/gsrv/     upstream server code at a checkout, plus the `ws` module from npm

Nothing under android/lib or android/assets is committed: the first is a pile of Termux binaries, the
second is upstream GPL source that belongs in the upstream repository rather than vendored here.

**Why the libraries live in lib/ and not in assets/.** Android refuses to execve() a file in an app's
home directory. Measured on-device: the file had mode 0700 and canExecute() was true, yet execve still
returned EACCES -- while an identical file with an identical SELinux label executed fine for a
different app on the same device. The native library directory is the one place the platform always
treats as code, so the executable goes there.

Two consequences, both handled below:

  * the installer only extracts entries under lib/<abi>/ whose names end in .so -- so every file is
    renamed to an unversioned lib*.so;
  * a linker resolves libraries by their exact soname -- so DT_NEEDED, DT_SONAME **and** the
    .gnu.version_r verneed entries are all rewritten to match.

Patching DT_NEEDED alone is not enough; the device reported
  cannot find "libcrypto.so" from verneed[0] in DT_NEEDED list
because the version-need table and the loaded library's own SONAME still carried the old name. New
names are always shorter, so the rewrite happens in place inside .dynstr with NUL padding.

Usage:
  python3 make_native_payload.py <android-dir> <upstream-checkout> [--mirror <termux-repo-url>]
                                 [--keep-debs <dir>] [--node-only|--gsrv-only]
"""
import io
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request

PACKAGES = ["nodejs-lts", "libicu", "libc++", "openssl", "c-ares", "libsqlite", "zlib"]
WS_VERSION = "8.22.0"
GSRV_TREES = ["server", "shared", "data", "package.json"]
# public/{assets,fonts} stay out on purpose: every device running this client serves those out of its
# own APK, so the host never has to ship 272 MiB of artwork it would only ever 404 on.
GSRV_PUBLIC = ["css", "js", "index.html"]

DT_NEEDED, DT_SONAME = 1, 14
SHT_DYNAMIC, SHT_GNU_VERNEED = 6, 0x6FFFFFFE

UA = "make_native_payload/2.0"


def _sections(data):
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, _ = struct.unpack_from("<HHH", data, 0x3A)
    out = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        _n, stype, _f, _a, offset, size, link, _i, _al, _ent = \
            struct.unpack_from("<IIQQQQIIQQ", data, off)
        out.append(dict(type=stype, offset=offset, size=size, link=link))
    return out


def _dynamic(data):
    secs = _sections(data)
    dyn = next((s for s in secs if s["type"] == SHT_DYNAMIC), None)
    if dyn is None:
        return None, None, secs
    return dyn, secs[dyn["link"]], secs


def sonames(path):
    """The DT_NEEDED names of an ELF."""
    with open(path, "rb") as fh:
        data = bytearray(fh.read())
    dyn, strtab, _ = _dynamic(data)
    if dyn is None:
        return []
    strings = data[strtab["offset"]:strtab["offset"] + strtab["size"]]
    out = []
    for i in range(dyn["size"] // 16):
        tag, val = struct.unpack_from("<qQ", data, dyn["offset"] + i * 16)
        if tag == 0:
            break
        if tag == DT_NEEDED:
            out.append(strings[val:strings.index(b"\0", val)].decode())
    return out


def unversioned(soname):
    """libcrypto.so.3 -> libcrypto.so ; libcares.so is already fine."""
    return re.sub(r"\.so(\.\d+)+$", ".so", soname)


def rewrite_sonames(path):
    """Rewrites DT_NEEDED / DT_SONAME / verneed in place. Returns [(old, new), ...]."""
    with open(path, "rb") as fh:
        data = bytearray(fh.read())
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return []
    dyn, strtab, secs = _dynamic(data)
    if dyn is None:
        return []

    offsets = []
    for i in range(dyn["size"] // 16):
        tag, val = struct.unpack_from("<qQ", data, dyn["offset"] + i * 16)
        if tag == 0:
            break
        if tag in (DT_NEEDED, DT_SONAME):
            offsets.append(val)
    ver = next((s for s in secs if s["type"] == SHT_GNU_VERNEED), None)
    if ver is not None:
        off, end = ver["offset"], ver["offset"] + ver["size"]
        while off < end:
            _v, _c, vn_file, _a, vn_next = struct.unpack_from("<HHIII", data, off)
            if vn_file:
                offsets.append(vn_file)
            if vn_next == 0:
                break
            off += vn_next

    base = strtab["offset"]
    changes = []
    for off in sorted(set(offsets)):
        end = data.index(b"\0", base + off)
        old = data[base + off:end].decode("utf-8", "replace")
        new = unversioned(old)
        if new == old:
            continue
        if len(new) > len(old):
            raise SystemExit("%s: %s -> %s would grow the string" % (path, old, new))
        data[base + off:base + off + len(old)] = new.encode() + b"\0" * (len(old) - len(new))
        changes.append((old, new))
    if changes:
        with open(path, "wb") as fh:
            fh.write(data)
    return changes


def _ar_members(blob):
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


def _deb_data(path):
    with open(path, "rb") as fh:
        members = _ar_members(fh.read())
    for name, mode in (("data.tar.xz", "r:xz"), ("data.tar.gz", "r:gz"), ("data.tar", "r:")):
        if name in members:
            return tarfile.open(fileobj=io.BytesIO(members[name]), mode=mode)
    raise SystemExit("no usable data.tar in %s" % path)


def fetch(url, dest):
    """Download once; the Termux CDN answers 403 to a request with no User-Agent."""
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        return dest
    parent = os.path.dirname(dest)
    if parent:
        os.makedirs(parent, exist_ok=True)
    print("  GET %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as r, open(dest, "wb") as fh:
        shutil.copyfileobj(r, fh, 1 << 20)
    return dest


def package_filenames(mirror, work):
    index = os.path.join(work, "Packages")
    fetch(mirror.rstrip("/") + "/dists/stable/main/binary-aarch64/Packages", index)
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


def node_payload(android_dir, mirror, work):
    lib_out = os.path.join(android_dir, "lib", "arm64-v8a")
    shutil.rmtree(lib_out, ignore_errors=True)
    os.makedirs(lib_out, exist_ok=True)

    filenames = package_filenames(mirror, work)
    roots = []
    print("Termux packages:")
    for pkg in PACKAGES:
        deb = fetch(mirror.rstrip("/") + "/" + filenames[pkg],
                    os.path.join(work, os.path.basename(filenames[pkg])))
        staging = os.path.join(work, "x", pkg)
        shutil.rmtree(staging, ignore_errors=True)
        _deb_data(deb).extractall(staging)
        root = os.path.join(staging, "data/data/com.termux/files/usr")
        roots.append(root if os.path.isdir(root) else staging)
        print("  %-12s ok" % pkg)

    def locate(rel):
        for r in roots:
            p = os.path.join(r, rel)
            if os.path.exists(p):
                return os.path.realpath(p)
        return None

    node_src = locate("bin/node")
    if not node_src:
        raise SystemExit("no bin/node in any package")

    # Transitive closure: libicuuc needs libicudata, which node itself never names. Shipping only the
    # direct dependencies produces a node that cannot start.
    SYSTEM = ("libc.", "libm.", "libdl.", "liblog.")
    resolved, queue = {}, list(sonames(node_src))
    while queue:
        so = queue.pop(0)
        if so in resolved or so.startswith(SYSTEM):
            continue
        src = locate("lib/" + so)
        if src is None:
            raise SystemExit("cannot resolve %s -- is the mirror complete?" % so)
        resolved[so] = src
        for dep in sonames(src):
            if dep not in resolved and dep not in queue:
                queue.append(dep)

    print("node needs %d libraries (transitively):" % len(resolved))
    for so in sorted(resolved):
        target = os.path.join(lib_out, unversioned(so))
        shutil.copyfile(resolved[so], target)
        print("  %-22s -> %-20s %.1f MB" % (so, os.path.basename(target),
                                            os.path.getsize(target) / 1048576.0))

    node_target = os.path.join(lib_out, "libnode.so")
    shutil.copyfile(node_src, node_target)

    print("rewriting sonames (DT_NEEDED / DT_SONAME / verneed):")
    for name in sorted(os.listdir(lib_out)):
        for old, new in rewrite_sonames(os.path.join(lib_out, name)):
            print("  %-18s %s -> %s" % (name, old, new))
    return lib_out


def gsrv_payload(android_dir, upstream, work):
    out = os.path.join(android_dir, "assets", "gsrv")
    shutil.rmtree(out, ignore_errors=True)
    subprocess.check_call(["git", "-C", upstream, "rev-parse", "--short", "HEAD"],
                          stdout=subprocess.DEVNULL)
    commit = subprocess.check_output(["git", "-C", upstream, "rev-parse", "--short", "HEAD"],
                                     text=True).strip()
    print("upstream server code at %s:" % commit)
    for tree in GSRV_TREES:
        src, dst = os.path.join(upstream, tree), os.path.join(out, tree)
        if not os.path.exists(src):
            raise SystemExit("missing %s in %s" % (tree, upstream))
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        else:
            shutil.copyfile(src, dst)
    os.makedirs(os.path.join(out, "public"), exist_ok=True)
    for item in GSRV_PUBLIC:
        src = os.path.join(upstream, "public", item)
        dst = os.path.join(out, "public", item)
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        else:
            shutil.copyfile(src, dst)
    print("  server/ shared/ data/ public/{css,js,index.html}")

    tgz = fetch("https://registry.npmjs.org/ws/-/ws-%s.tgz" % WS_VERSION,
                os.path.join(work, "ws-%s.tgz" % WS_VERSION))
    ws_dir = os.path.join(out, "node_modules", "ws")
    shutil.rmtree(ws_dir, ignore_errors=True)
    tmp = os.path.join(work, "wsx")
    shutil.rmtree(tmp, ignore_errors=True)
    with tarfile.open(tgz, "r:gz") as tf:
        tf.extractall(tmp)
    shutil.move(os.path.join(tmp, "package"), ws_dir)
    print("  node_modules/ws %s" % WS_VERSION)
    return out


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    android_dir, upstream = sys.argv[1], sys.argv[2]
    mirror = "https://packages.termux.dev/apt/termux-main"
    work = ".native-payload"
    only = None
    args = sys.argv[3:]
    for i, a in enumerate(args):
        if a == "--mirror":
            mirror = args[i + 1]
        elif a == "--keep-debs":
            work = args[i + 1]
        elif a in ("--node-only", "--gsrv-only"):
            only = a
    os.makedirs(work, exist_ok=True)

    if only != "--gsrv-only":
        print("-> %s" % node_payload(android_dir, mirror, work))
    if only != "--node-only":
        print("-> %s" % gsrv_payload(android_dir, upstream, work))

    subprocess.check_call([sys.executable,
                           os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                        "make_payload_list.py"),
                           os.path.join(android_dir, "assets")])
    print("\ndone. build the APK with ./build.sh")
    return 0


if __name__ == "__main__":
    sys.exit(main())
