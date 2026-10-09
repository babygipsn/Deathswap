#!/usr/bin/env python3
"""Generates the Deathswap HUD resource pack (pure Python, no dependencies).

The pack contains no copied Mojang textures. Its fonts *reference* the vanilla
glyph texture (minecraft:font/ascii.png) with shifted ascents, so the server can
draw text at any vertical offset. A space font provides negative/positive advances
for horizontal positioning. The white boss bar is made transparent so the HUD can be
anchored to the top of the screen through an invisible boss bar.

Usage:
    python tools/generate_resourcepack.py <path to a vanilla minecraft client jar>

Outputs (all committed to the repo):
    resourcepack/                                  unpacked pack sources
    resourcepack/dist/Deathswap-HUD-v<N>.zip       deterministic zip (served from GitHub)
    src/main/resources/deathswap/Deathswap-HUD.zip same bytes, bundled in the mod jar
    src/main/resources/deathswap/hud_glyphs.json   glyph advances the server uses for layout
    src/main/resources/assets/deathswap/icon.png   mod icon (same art as pack.png)
"""
import hashlib
import io
import json
import os
import shutil
import struct
import sys
import zipfile
import zlib

PACK_VERSION = 1  # bump whenever the pack contents change (new file name => no stale caches)
SHIFT_MAX = 320   # largest downward offset in GUI pixels
SHIFT_STEP = 2    # granularity of vertical offsets
# Private-use codepoints for horizontal moves: NEG_BASE+k moves -(2^k) px, POS_BASE+k moves +(2^k) px
NEG_BASE = 0xE000
POS_BASE = 0xE010
POW_COUNT = 11    # 1..1024 px

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK_DIR = os.path.join(ROOT, "resourcepack")
SRC_DIR = os.path.join(PACK_DIR, "src")
DIST_DIR = os.path.join(PACK_DIR, "dist")
RES_DIR = os.path.join(ROOT, "src", "main", "resources")


# ---------------------------------------------------------------- PNG helpers
def read_png_alpha(data):
    """Returns (width, height, alpha rows) for palette/gray/rgb(a) PNGs of any bit depth."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    i, idat, pal, trns = 8, b"", None, None
    while i < len(data):
        n, = struct.unpack(">I", data[i:i + 4])
        t, d = data[i + 4:i + 8], data[i + 8:i + 8 + n]
        i += 12 + n
        if t == b"IHDR":
            w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", d)
            assert il == 0, "interlaced PNG not supported"
        elif t == b"IDAT":
            idat += d
        elif t == b"PLTE":
            pal = d
        elif t == b"tRNS":
            trns = d
    raw = zlib.decompress(idat)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    bpp = max(1, channels * bd // 8)
    stride = (w * channels * bd + 7) // 8
    rows, prev, p = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[p]; line = bytearray(raw[p + 1:p + 1 + stride]); p += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        prev = line
        # unpack samples
        samples = []
        if bd < 8:
            for byte in line:
                for s in range(8 // bd):
                    samples.append((byte >> (8 - bd * (s + 1))) & ((1 << bd) - 1))
            samples = samples[:w * channels]
        else:
            samples = list(line)
        alpha = []
        for x in range(w):
            if ct == 3:
                idx = samples[x]
                alpha.append(trns[idx] if trns and idx < len(trns) else 255)
            elif ct == 6:
                alpha.append(samples[x * 4 + 3])
            elif ct == 4:
                alpha.append(samples[x * 2 + 1])
            elif ct == 0:  # grayscale: Minecraft uses luminance as "alpha" for these
                alpha.append(samples[x])
            else:
                alpha.append(255)
        rows.append(alpha)
    return w, h, rows


def write_png(w, h, pixels):
    """pixels: list of rows of (r,g,b,a) tuples."""
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


# ---------------------------------------------------------------- icon art
ICON = [  # 16x16: skull with swap arrows; . transparent, K dark, W bone, R red, Y gold
    "................",
    "..YY........RR..",
    ".YYYY......RRRR.",
    "..YY..WWWW..RR..",
    "..Y..WWWWWW..R..",
    "..Y.WWWWWWWW.R..",
    "..Y.WKKWWKKW.R..",
    "..Y.WKKWWKKW.R..",
    "..Y.WWWKKWWW.R..",
    "..Y..WWWWWW..R..",
    "..Y..WKWKWW..R..",
    "..Y...WWWW...R..",
    "..YY........RR..",
    ".YYYY......RRRR.",
    "..YY........RR..",
    "................",
]
COLORS = {".": (0, 0, 0, 0), "K": (24, 20, 28, 255), "W": (236, 230, 214, 255),
          "R": (214, 48, 49, 255), "Y": (253, 203, 110, 255)}


def icon_png(size):
    scale = size // 16
    bg_top, bg_bot = (45, 20, 44), (20, 8, 20)
    rows = []
    for y in range(size):
        t = y / (size - 1)
        bg = tuple(int(bg_top[i] * (1 - t) + bg_bot[i] * t) for i in range(3)) + (255,)
        row = []
        for x in range(size):
            c = COLORS[ICON[y // scale][x // scale]]
            row.append(c if c[3] else bg)
        rows.append(row)
    return write_png(size, size, rows)


# ---------------------------------------------------------------- main
def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    jar = zipfile.ZipFile(sys.argv[1])
    default = json.loads(jar.read("assets/minecraft/font/include/default.json").decode("utf-8"))
    ascii_def = next(p for p in default["providers"] if p.get("file") == "minecraft:font/ascii.png")
    grid = ascii_def["chars"]
    w, h, alpha = read_png_alpha(jar.read("assets/minecraft/textures/font/ascii.png"))
    gw, gh = w // len(grid[0]), h // len(grid)
    height = ascii_def.get("height", 8)
    scale = height / gh

    # Glyph advances, computed exactly like BitmapProvider does
    advances = {}
    for gy, row in enumerate(grid):
        for gx, ch in enumerate(row):
            if ch == "\u0000":
                continue
            width = gw - 1
            while width >= 0:
                if any(alpha[gy * gh + y][gx * gw + width] for y in range(gh)):
                    break
                width -= 1
            advances[ch] = int(0.5 + (width + 1) * scale) + 1
    advances[" "] = 4

    space_advances = {" ": 4}
    for k in range(POW_COUNT):
        space_advances[chr(NEG_BASE + k)] = -(1 << k)
        space_advances[chr(POS_BASE + k)] = 1 << k

    if os.path.isdir(SRC_DIR):
        shutil.rmtree(SRC_DIR)
    files = {}  # path inside pack -> bytes

    files["pack.mcmeta"] = json.dumps({
        "pack": {
            "description": "Deathswap HUD (fonts for the left-side timer & death list)",
            "pack_format": 15,
            "supported_formats": [15, 64],
            "min_format": 15,
            "max_format": 9999,
        }
    }, indent=2).encode()
    files["pack.png"] = icon_png(64)

    for shift in range(0, SHIFT_MAX + 1, SHIFT_STEP):
        font = {"providers": [
            {"type": "space", "advances": space_advances},
            {"type": "bitmap", "file": "minecraft:font/ascii.png", "height": height,
             "ascent": ascii_def["ascent"] - shift, "chars": grid},
        ]}
        files[f"assets/deathswap/font/hud/y{shift}.json"] = json.dumps(font, ensure_ascii=True).encode()
    files["assets/deathswap/font/hud/space.json"] = json.dumps(
        {"providers": [{"type": "space", "advances": space_advances}]}, ensure_ascii=True).encode()

    # Invisible white boss bar (1.20.2+ sprite paths) -> our HUD anchor shows only its title.
    clear = write_png(182, 5, [[(0, 0, 0, 0)] * 182 for _ in range(5)])
    for part in ("background", "progress"):
        files[f"assets/minecraft/textures/gui/sprites/boss_bar/white_{part}.png"] = clear

    for path, data in files.items():
        out = os.path.join(SRC_DIR, path)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "wb") as f:
            f.write(data)

    # Deterministic zip: sorted entries, fixed timestamps
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted(files):
            info = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, files[path])
    zip_bytes = buf.getvalue()
    sha1 = hashlib.sha1(zip_bytes).hexdigest()

    os.makedirs(DIST_DIR, exist_ok=True)
    dist_name = f"Deathswap-HUD-v{PACK_VERSION}.zip"
    with open(os.path.join(DIST_DIR, dist_name), "wb") as f:
        f.write(zip_bytes)
    os.makedirs(os.path.join(RES_DIR, "deathswap"), exist_ok=True)
    with open(os.path.join(RES_DIR, "deathswap", "Deathswap-HUD.zip"), "wb") as f:
        f.write(zip_bytes)

    meta = {
        "packVersion": PACK_VERSION,
        "distFile": dist_name,
        "sha1": sha1,
        "shiftMax": SHIFT_MAX,
        "shiftStep": SHIFT_STEP,
        "lineHeight": 9,
        "negBase": NEG_BASE,
        "posBase": POS_BASE,
        "powCount": POW_COUNT,
        "advances": {ch: adv for ch, adv in sorted(advances.items())},
    }
    with open(os.path.join(RES_DIR, "deathswap", "hud_glyphs.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=True, indent=1)

    os.makedirs(os.path.join(RES_DIR, "assets", "deathswap"), exist_ok=True)
    with open(os.path.join(RES_DIR, "assets", "deathswap", "icon.png"), "wb") as f:
        f.write(icon_png(128))

    print(f"pack v{PACK_VERSION}: {len(files)} files, {len(zip_bytes)} bytes, sha1 {sha1}")


if __name__ == "__main__":
    main()
