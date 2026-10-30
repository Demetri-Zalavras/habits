"""Progression item textures (sigils, charms). Run: python tools/art/progression_art.py

Layer convention: sigil_<type>.png and charm_base.png are neutral grey (tinted by rarity, layer0 tintindex 0);
sigil_<type>_glyph.png and charm_<category>.png are untinted overlays (layer1).
Also writes run/screenshots/progression_art_sheet.png (8x, every tint, overlay on top).
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/habits/textures/item/progression"
SHEET = ROOT / "run/screenshots/progression_art_sheet.png"

# Neutral ramp: bright enough that a multiply tint stays vivid.
GREY = {"O": 40, "1": 245, "2": 212, "3": 172, "4": 128, "5": 92}


def grey(v):
    return (v, v, v, 255)


def from_rows(rows, pal):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), "art grid must be 16x16"
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), pal[ch])
    return img


def shade(mask, face):
    """Bevelled shape: outline, light top-left rim, dark bottom-right rim, `face` fills the rest."""
    def m(x, y):
        return 0 <= x < 16 and 0 <= y < 16 and mask[y][x]
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            if not m(x, y):
                row += "."
            elif not all(m(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                row += "O"
            elif not m(x - 2, y) or not m(x, y - 2) or not m(x - 1, y - 1):
                row += "1"
            elif not m(x + 2, y) or not m(x, y + 2) or not m(x + 1, y + 1):
                row += "4"
            else:
                row += face(x, y)
        rows.append(row)
    return rows


# ---------- sigil stone ----------
def sigil_base():
    def inside(x, y):
        if not (2 <= x <= 13 and 1 <= y <= 14):
            return False
        cx = min(x - 2, 13 - x)
        cy = min(y - 1, 14 - y)
        return cx + cy >= 2  # clipped corners
    mask = [[inside(x, y) for x in range(16)] for y in range(16)]
    speck = {(5, 12), (10, 4), (11, 10), (4, 7)}
    rows = shade(mask, lambda x, y: "3" if (x, y) in speck else "2")
    return from_rows(rows, {k: grey(v) for k, v in GREY.items()})


GLYPH = {"W": (255, 255, 255, 255), "C": (150, 235, 255, 255), "S": (24, 40, 56, 255)}

SIGIL_GLYPHS = {
    "potent": [  # upward arrow
        "................",
        "................",
        "................",
        ".......WW.......",
        "......WWWC......",
        ".....WWWWCC.....",
        "....WWWWWWCC....",
        ".......WC.......",
        ".......WC.......",
        ".......WC.......",
        ".......WC.......",
        ".......WC.......",
        "................",
        "................",
        "................",
        "................",
    ],
    "swift": [  # lightning bolt
        "................",
        "................",
        "................",
        ".........WW.....",
        "........WW......",
        ".......WW.......",
        "......WW........",
        ".....WWWWWW.....",
        "........WC......",
        ".......WC.......",
        "......WC........",
        ".....WC.........",
        "................",
        "................",
        "................",
        "................",
    ],
    "reach": [  # ring around a dot
        "................",
        "................",
        "................",
        "......WWWW......",
        ".....W....C.....",
        "....W......C....",
        "....W..WW..C....",
        "....W..WC..C....",
        "....W......C....",
        ".....W....C.....",
        "......CCCC......",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "lucky": [  # four-leaf clover
        "................",
        "................",
        "................",
        ".....WW.WW......",
        "....WWW.WWW.....",
        "....WWW.WWC.....",
        ".....W...C......",
        "....WWW.WWC.....",
        "....WWC.WCC.....",
        ".....CC.CC......",
        ".......C........",
        "........C.......",
        "................",
        "................",
        "................",
        "................",
    ],
}


def with_shadow(img):
    """Carved look: dark pixel below-right of each glyph pixel where the glyph is not."""
    out = img.copy()
    for y in range(16):
        for x in range(16):
            if img.getpixel((x, y))[3] and x < 15 and y < 15 and not img.getpixel((x + 1, y + 1))[3]:
                out.putpixel((x + 1, y + 1), GLYPH["S"])
    return out


# ---------- charm ----------
def charm_base():
    cx, cy, r, inner = 7.5, 9.0, 6.6, 4.2

    def ring(x, y):
        return math.hypot(x - cx, y - cy) <= r

    mask = [[ring(x, y) for x in range(16)] for y in range(16)]
    rows = shade(mask, lambda x, y: "4" if math.hypot(x - cx, y - cy) <= inner else "2")
    rows = [list(r_) for r_ in rows]
    # inset rim: dark on top-left, light on bottom-right (recessed)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if rows[y][x] == "4" and inner - 1.0 < d:
                rows[y][x] = "5" if (x - cx) + (y - cy) < 0 else "3"
    # bail loop on top
    for x, y, ch in ((6, 0, "O"), (7, 0, "O"), (8, 0, "O"), (9, 0, "O"),
                     (6, 1, "O"), (7, 1, "1"), (8, 1, "2"), (9, 1, "O"),
                     (6, 2, "O"), (9, 2, "O"), (7, 2, "O"), (8, 2, "O")):
        rows[y][x] = ch
    return from_rows(["".join(r_) for r_ in rows], {k: grey(v) for k, v in GREY.items()})


# Emblems sit in the inset (x4..11, y6..13). k = dark outline.
K = (30, 24, 20, 255)
CHARMS = {
    "mining": ({"w": (137, 103, 61, 255), "g": (210, 225, 230, 255), "d": (120, 135, 145, 255)}, [
        "..kkkkk...",
        ".kgggggk..",
        "..kkkwdgk.",
        "....kwkdgk",
        "...kwk.kgk",
        "..kwk...k.",
        ".kwk......",
        ".kk.......",
    ]),
    "farming": ({"y": (240, 205, 80, 255), "o": (190, 140, 40, 255), "s": (110, 150, 50, 255)}, [
        "...ky.yk..",
        "...kyoyk..",
        "....yoy...",
        "...kyoyk..",
        "....kyk...",
        ".....s....",
        "....ks....",
        "....s.....",
    ]),
    "combat": ({"g": (235, 240, 245, 255), "d": (150, 160, 170, 255), "w": (137, 103, 61, 255), "y": (230, 190, 60, 255)}, [
        "........kk",
        ".......kgk",
        "......kgdk",
        "..ky.kgdk.",
        "...kygdk..",
        "....wyk...",
        "...kwkyk..",
        "..kwk..k..",
    ]),
    "movement": ({"g": (245, 245, 250, 255), "c": (170, 200, 230, 255), "d": (110, 140, 175, 255)}, [
        "......kkk.",
        "....kkggck",
        "..kkggccdk",
        ".kggccddk.",
        ".kgccddk..",
        "..kcddk...",
        "...kdk....",
        "....k.....",
    ]),
    "survival": ({"r": (230, 50, 50, 255), "h": (255, 170, 170, 255), "d": (150, 20, 30, 255)}, [
        "..........",
        "...kk.kk..",
        "..khrkrrk.",
        "..krrrrrk.",
        "..krrrrdk.",
        "...krrdk..",
        "....kdk...",
        ".....k....",
    ]),
    "crafting": ({"g": (200, 205, 215, 255), "d": (110, 115, 125, 255), "w": (137, 103, 61, 255)}, [
        "..kkkkk...",
        "..kgggdk..",
        "..kgddk...",
        "...kkwk...",
        ".....kwk..",
        "......kwk.",
        ".......kwk",
        "........k.",
    ]),
    "nature": ({"g": (120, 200, 70, 255), "l": (180, 235, 110, 255), "d": (60, 130, 40, 255)}, [
        "........kk",
        "......kklk",
        "....kkggdk",
        "...klgggdk",
        "...kgggdk.",
        "...kgddk..",
        "...kkkk...",
        "..k.......",
    ]),
    "exploration": ({"g": (225, 225, 230, 255), "r": (220, 50, 50, 255), "b": (90, 100, 120, 255)}, [
        "...kkkk...",
        "..kggggk..",
        ".kgggrrgk.",
        ".kggbrggk.",
        ".kggrbggk.",
        ".kgrrgggk.",
        "..kggggk..",
        "...kkkk...",
    ]),
    "building": ({"r": (190, 90, 60, 255), "l": (220, 130, 95, 255), "m": (215, 205, 190, 255)}, [
        "..........",
        ".kkkkkkkk.",
        ".klrrmlrk.",
        ".kmmmmmmk.",
        ".krmlrrmk.",
        ".kmmmmmmk.",
        ".klrrmlrk.",
        ".kkkkkkkk.",
    ]),
    "odd": ({"w": (240, 240, 245, 255), "p": (170, 90, 230, 255), "d": (60, 20, 80, 255)}, [
        "..........",
        "...kkkk...",
        "..kwwwwk..",
        ".kwwppwwk.",
        ".kwpddpwk.",
        "..kwppwk..",
        "...kkkk...",
        "..........",
    ]),
}


def emblem(pal, rows):
    pal = dict(pal, k=K)
    grid = ["." * 16] * 16
    grid = [list(r) for r in grid]
    for y, row in enumerate(rows):  # 10 wide x 8 tall art placed at x3, y5 (centered in the inset)
        for x, ch in enumerate(row):
            grid[y + 5][x + 3] = ch
    return from_rows(["".join(r) for r in grid], pal)


# ---------- contact sheet ----------
TINTS = {"grey": (255, 255, 255), "green": (85, 255, 85), "blue": (85, 150, 255),
         "purple": (180, 90, 255), "gold": (255, 180, 40)}


def tinted(img, t):
    out = img.copy()
    px = out.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[x, y]
            px[x, y] = (r * t[0] // 255, g * t[1] // 255, b * t[2] // 255, a)
    return out


def sheet(items):
    s, pad, label = 8, 8, 110
    cell = 16 * s + pad
    W = label + cell * (len(TINTS) + 1)
    H = cell * len(items) + pad
    im = Image.new("RGBA", (W, H), (139, 139, 139, 255))  # vanilla slot grey
    d = ImageDraw.Draw(im)
    for row, (name, base, over) in enumerate(items):
        y = pad + row * cell
        d.text((6, y + 56), name, fill=(20, 20, 20, 255))
        for col, t in enumerate(TINTS.values()):
            comp = tinted(base, t)
            comp.alpha_composite(over)
            im.alpha_composite(comp.resize((16 * s, 16 * s), Image.NEAREST), (label + col * cell, y))
        comp = tinted(base, TINTS["blue"])  # true size, for a real-scale read
        comp.alpha_composite(over)
        im.alpha_composite(comp.resize((32, 32), Image.NEAREST), (label + len(TINTS) * cell + 8, y + 40))
        im.alpha_composite(comp, (label + len(TINTS) * cell + 56, y + 48))
    return im


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    items = []
    stone = sigil_base()
    for name, rows in SIGIL_GLYPHS.items():
        glyph = with_shadow(from_rows(rows, GLYPH))
        stone.save(OUT / f"sigil_{name}.png")
        glyph.save(OUT / f"sigil_{name}_glyph.png")
        items.append((f"sigil_{name}", stone, glyph))
    base = charm_base()
    base.save(OUT / "charm_base.png")
    for name, (pal, rows) in CHARMS.items():
        assert len(rows) == 8 and all(len(r) == 10 for r in rows), name
        e = emblem(pal, rows)
        e.save(OUT / f"charm_{name}.png")
        items.append((f"charm_{name}", base, e))
    SHEET.parent.mkdir(parents=True, exist_ok=True)
    sheet(items).save(SHEET)
    print(f"wrote {len(items) + 1} textures + {SHEET}")


if __name__ == "__main__":
    main()
