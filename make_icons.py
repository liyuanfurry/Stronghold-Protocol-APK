#!/usr/bin/env python3
"""Draws the launcher icons (legacy bitmaps + adaptive foreground) with no image library.

The glyph is the stronghold mark from the game's own favicon: that SVG path uses only
M/h/v/l/H/V/L/z commands, so it is an exact polygon and can be scan-filled directly.

Usage: python3 make_icons.py <res-dir>
"""
import os
import struct
import sys
import zlib

# public/index.html favicon path, viewBox 0 0 24 24, expanded to absolute vertices.
GLYPH = [
    (5, 3), (8, 3), (8, 5), (10, 5), (10, 3), (14, 3), (14, 5), (16, 5), (16, 3),
    (19, 3), (19, 8), (17, 10), (17, 17), (19, 19), (19, 21), (5, 21), (5, 19),
    (7, 17), (7, 10), (5, 8),
]

BG = (0x0C, 0x0F, 0x0E)
FG = (0x4E, 0xD8, 0xAF)
VIEW = 24.0
SS = 3  # supersampling factor per axis for antialiasing


def inside(x, y, poly):
    """Ray-casting point-in-polygon."""
    n = len(poly)
    hit = False
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y):
            xint = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < xint:
                hit = not hit
        j = i
    return hit


def rounded_rect_alpha(px, py, size, radius):
    """Coverage of a rounded square at pixel centre, 0..1 (1 inside)."""
    cx = min(max(px, radius), size - radius)
    cy = min(max(py, radius), size - radius)
    dx = px - cx
    dy = py - cy
    d = (dx * dx + dy * dy) ** 0.5
    if d <= radius - 0.5:
        return 1.0
    if d >= radius + 0.5:
        return 0.0
    return radius + 0.5 - d


def render(size, glyph_frac, rounded):
    """Returns RGBA rows for one icon bitmap. `glyph_frac` is the glyph's share of the canvas."""
    scale = size * glyph_frac / VIEW
    ox = (size - VIEW * scale) / 2.0
    oy = ox
    radius = size * 0.22 if rounded else 0.0
    rows = []
    step = 1.0 / SS
    for y in range(size):
        row = bytearray()
        for x in range(size):
            # background coverage
            if rounded:
                bg_a = rounded_rect_alpha(x + 0.5, y + 0.5, size, radius)
            else:
                bg_a = 1.0
            # glyph coverage by supersampling
            hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    gx = (x + (sx + 0.5) * step - ox) / scale
                    gy = (y + (sy + 0.5) * step - oy) / scale
                    if inside(gx, gy, GLYPH):
                        hits += 1
            g_a = hits / float(SS * SS) * bg_a
            r = int(round(BG[0] * (1 - g_a) + FG[0] * g_a))
            g = int(round(BG[1] * (1 - g_a) + FG[1] * g_a))
            b = int(round(BG[2] * (1 - g_a) + FG[2] * g_a))
            row += bytes((r, g, b, int(round(bg_a * 255))))
        rows.append(bytes(row))
    return rows


def write_png(path, size, rows):
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    blob = b"\x89PNG\r\n\x1a\n"
    blob += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    blob += chunk(b"IDAT", zlib.compress(raw, 9))
    blob += chunk(b"IEND", b"")
    with open(path, "wb") as fh:
        fh.write(blob)


def main():
    res = sys.argv[1] if len(sys.argv) > 1 else "android/res"
    # (density dir, legacy icon size). The legacy glyph is large; the adaptive one must stay inside
    # the 66/108 safe zone or launchers will crop its corners.
    densities = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
    for name, size in densities:
        d = os.path.join(res, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), size, render(size, 0.62, True))
        # Adaptive foreground: 108dp canvas, glyph inside the middle ~55%.
        fsize = int(round(size * 108 / 48.0))
        write_png(os.path.join(d, "ic_launcher_fg.png"), fsize, render(fsize, 0.42, False))
        print("  " + name + ": " + str(size) + "px + fg " + str(fsize) + "px")

    anydpi = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                 '    <background android:drawable="@color/icon_bg" />\n'
                 '    <foreground android:drawable="@mipmap/ic_launcher_fg" />\n'
                 '</adaptive-icon>\n')
    print("  adaptive icon written")


if __name__ == "__main__":
    main()
