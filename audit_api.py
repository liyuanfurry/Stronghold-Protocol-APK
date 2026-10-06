#!/usr/bin/env python3
"""Cross-checks every android.* symbol the app references against the platform's api-versions.xml.

A single call to an API newer than minSdkVersion (WebView.getWebChromeClient, API 26, is the classic
one) compiles fine against a modern android.jar and then throws NoSuchMethodError on an older device.
This catches that before the APK ever reaches a phone.

Usage: python3 audit_api.py <classes-dir> <platform-zip> [min-sdk]
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

REF = re.compile(r"//\s+(?:Method|InterfaceMethod|Field)\s+(android/[\w/$]+)\.([\w$<>]+):?(\S*)")


def load_api(zip_path):
    z = zipfile.ZipFile(zip_path)
    entry = [n for n in z.namelist() if n.endswith("api-versions.xml")][0]
    root = ET.fromstring(z.read(entry))
    classes, members = {}, {}
    for cls in root:
        cname = cls.get("name")
        if not cname:
            continue
        classes[cname] = int(cls.get("since") or 1)
        for m in cls:
            nm = m.get("name")
            if nm:
                members[(cname, nm)] = int(m.get("since") or 1)
    return classes, members


def refs_from(class_dir):
    classes = []
    for root, dirs, files in os.walk(class_dir):
        for f in files:
            if f.endswith(".class"):
                classes.append(os.path.join(root, f))
    if not classes:
        raise SystemExit("no .class files under " + class_dir)
    out = subprocess.run(["javap", "-v", "-p"] + classes,
                         capture_output=True, text=True).stdout
    found = set()
    for line in out.splitlines():
        m = REF.search(line)
        if m:
            owner, name, desc = m.group(1), m.group(2), m.group(3)
            found.add((owner, name, desc))
    return found


def load_allow():
    """Symbols acknowledged as guarded at the call site (see audit_allow.txt)."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "audit_allow.txt")
    allowed = set()
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#"):
                    allowed.add(line)
    except IOError:
        pass
    return allowed


def main():
    class_dir, platform_zip = sys.argv[1], sys.argv[2]
    min_sdk = int(sys.argv[3]) if len(sys.argv) > 3 else 21
    classes, members = load_api(platform_zip)

    allowed = load_allow()
    bad = []
    acknowledged = []
    unknown = []
    for owner, name, desc in sorted(refs_from(class_dir)):
        cvar = owner.split("$")[0]
        if cvar not in classes:
            continue                      # not a framework class we can date (e.g. org.json on some dumps)
        # Members are keyed with the descriptor attached, javap splits it at ':'.
        key = (owner, name + desc) if desc else (owner, name)
        since = members.get(key, members.get((owner, name), classes[cvar]))
        if since > min_sdk:
            if owner in allowed or (owner + "." + name) in allowed:
                acknowledged.append((since, owner, name + desc))
            else:
                bad.append((since, owner, name + desc))

    print("checked against minSdk=%d" % min_sdk)
    if unknown:
        for u in unknown:
            print("  ? " + u)
    for since, owner, sig in sorted(set(acknowledged)):
        print("  acknowledged (guarded) API %d %s.%s" % (since, owner, sig))
    if bad:
        print("FOUND %d API(s) newer than minSdk:" % len(bad))
        for since, owner, sig in sorted(bad, reverse=True):
            print("  API %-3d %s.%s" % (since, owner, sig))
        return 1
    print("OK: every referenced framework API exists at minSdk=%d" % min_sdk)
    return 0


if __name__ == "__main__":
    sys.exit(main())
