# Generates the BOOK-UI pixel art (card frame, binder) for Habits. 1 texel = 1 "card pixel".
# Run from anywhere: python tools/gen_book_tex.py  (needs Pillow). Overwrites the PNGs in place.
import json, math, os, random
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPR = os.path.join(ROOT, "src/main/resources/assets/habits/textures/gui/sprites/book")
TEX = os.path.join(ROOT, "src/main/resources/assets/habits/textures/book")
os.makedirs(SPR, exist_ok=True)
os.makedirs(TEX, exist_ok=True)
rnd = random.Random(7)

T = (0, 0, 0, 0)
K = (0, 0, 0, 255)


def save(img, name, scaling=None, folder=SPR):
    p = os.path.join(folder, name + ".png")
    img.save(p)
    if scaling:
        with open(p + ".mcmeta", "w") as f:
            json.dump({"gui": {"scaling": scaling}}, f)


def nine(w, h, border):
    return {"type": "nine_slice", "width": w, "height": h, "border": border}


def g(v, a=255):
    v = max(0, min(255, int(round(v))))
    return (v, v, v, a)


def clamp(c):
    return tuple(max(0, min(255, int(round(x)))) for x in c)


def shade(c, f):
    return clamp((c[0] * f, c[1] * f, c[2] * f)) + (c[3] if len(c) > 3 else 255,)


def rounded_mask(w, h, x, y, r=2):
    """True if (x,y) is inside a pixel-stepped rounded rect of radius 2 (cut: 3 px staircase)."""
    cx = min(x, w - 1 - x)
    cy = min(y, h - 1 - y)
    return cx + cy >= r


# ------------------------------------------------------------------ card frame (grayscale, tinted by rarity)
def card_frame():
    s = 16
    im = Image.new("RGBA", (s, s), T)
    for y in range(s):
        for x in range(s):
            d = min(x, y, s - 1 - x, s - 1 - y)  # ring index
            cx, cy = min(x, s - 1 - x), min(y, s - 1 - y)
            if cx + cy < 2:
                continue  # rounded outer corner
            if d == 0 or (cx + cy == 2):
                im.putpixel((x, y), K)
                continue
            if d >= 4:
                continue
            if d == 3 or (cx + cy == 5 and min(cx, cy) <= 3):
                im.putpixel((x, y), K)
                continue
            top_left = (y < s - 1 - y and cy <= cx) or (x < s - 1 - x and cx < cy)
            if d == 1:
                im.putpixel((x, y), g(255 if top_left else 170))
            else:  # d == 2
                im.putpixel((x, y), g(215 if top_left else 150))
    save(im, "card_frame", nine(s, s, 4))


def card_shadow():
    s = 16
    b = 6
    im = Image.new("RGBA", (s, s), T)
    for y in range(s):
        for x in range(s):
            dx = max(b - x, x - (s - 1 - b), 0)
            dy = max(b - y, y - (s - 1 - b), 0)
            d = math.hypot(dx, dy)
            a = max(0.0, 1 - d / (b + 0.5)) ** 1.6
            im.putpixel((x, y), (0, 0, 0, int(150 * a)))
    save(im, "card_shadow", nine(s, s, b))


def gem():
    rows = ["...O...",
            "..OWO..",
            ".OHHMO.",
            "OHHMMSO",
            ".OMMSO.",
            "..OSO..",
            "...O..."]
    pal = {".": T, "O": K, "W": g(255), "H": g(235), "M": g(185), "S": g(120)}
    im = Image.new("RGBA", (7, 7), T)
    for y, r in enumerate(rows):
        for x, c in enumerate(r):
            im.putpixel((x, y), pal[c])
    save(im, "gem")


def radial(w, h, cx, cy, inner, outer, peak, invert=False):
    im = Image.new("RGBA", (w, h), T)
    for y in range(h):
        for x in range(w):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            t = (d - inner) / max(1e-6, outer - inner)
            t = max(0.0, min(1.0, t))
            a = t if invert else 1 - t
            a = a * a * (3 - 2 * a)  # smoothstep
            col = (0, 0, 0) if invert else (255, 255, 255)
            im.putpixel((x, y), col + (int(peak * a),))
    return im


ART_W, ART_H = 49, 46


def art_layers():
    cx, cy = ART_W / 2, ART_H * 0.45
    far = math.hypot(ART_W / 2, ART_H * 0.55)
    save(radial(ART_W, ART_H, cx, cy, 0, far * 0.62, 140), "art_glow")
    far2 = math.hypot(ART_W / 2, ART_H / 2)
    save(radial(ART_W, ART_H, ART_W / 2, ART_H / 2, far2 * 0.42, far2 * 1.0, 175, invert=True), "art_vignette")
    save(radial(13, 13, 6.5, 6.5, 0, 6.5, 200), "gem_glow")


def foil():
    w, h = 160, 88
    im = Image.new("RGBA", (w, h), T)
    stops = [(0.00, (255, 80, 200)), (0.45, (80, 220, 255)), (1.00, (255, 240, 120))]
    band0, band1 = 60, 92

    def lerp(a, b, t):
        return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))

    for y in range(h):
        for x in range(w):
            xp = (x + 0.42 * y) % w
            if not (band0 <= xp < band1):
                continue
            t = (xp - band0) / (band1 - band0)
            for i in range(len(stops) - 1):
                if stops[i][0] <= t <= stops[i + 1][0]:
                    c = lerp(stops[i][1], stops[i + 1][1], (t - stops[i][0]) / (stops[i + 1][0] - stops[i][0]))
            a = math.sin(math.pi * t) ** 1.3
            im.putpixel((x, y), clamp(c) + (int(150 * a),))
    save(im, "foil", folder=TEX)


# ------------------------------------------------------------------ card back
GOLD_H, GOLD, GOLD_S, GOLD_D = (255, 226, 140, 255), (214, 170, 72, 255), (160, 116, 40, 255), (96, 64, 20, 255)


def back_frame():
    s = 16
    im = Image.new("RGBA", (s, s), T)
    for y in range(s):
        for x in range(s):
            d = min(x, y, s - 1 - x, s - 1 - y)
            cx, cy = min(x, s - 1 - x), min(y, s - 1 - y)
            if cx + cy < 2:
                continue
            if d == 0 or cx + cy == 2:
                im.putpixel((x, y), K)
            elif d in (1, 2, 3):
                tl = (y < s - 1 - y and cy <= cx) or (x < s - 1 - x and cx < cy)
                im.putpixel((x, y), [GOLD_H if tl else GOLD_S, GOLD, GOLD_S if tl else GOLD_D][d - 1])
            elif d == 4:
                im.putpixel((x, y), K)
    # small gold corner studs inside
    save(im, "back_frame", nine(s, s, 5))


def back_emblem():
    n = 33
    c = n // 2
    im = Image.new("RGBA", (n, n), T)
    for y in range(n):
        for x in range(n):
            d = abs(x - c) + abs(y - c)
            if d > c:
                continue
            if d == c:
                im.putpixel((x, y), K)
            elif d >= c - 3:
                ring = c - d  # 1..3
                tl = (x + y) < 2 * c
                col = [GOLD_H if tl else GOLD_S, GOLD, GOLD_D][ring - 1]
                im.putpixel((x, y), col)
            elif d == c - 4:
                im.putpixel((x, y), K)
            else:
                v = 1 - d / c * 0.35
                base = (52, 30, 16)
                im.putpixel((x, y), clamp(tuple(b * v + rnd.uniform(-4, 4) for b in base)) + (255,))
    save(im, "back_emblem")


# ------------------------------------------------------------------ binder
LEATHER = (92, 52, 30)


def noise_tile(w, h, base, amp, blotch):
    # value noise that wraps (tileable)
    gw, gh = 4, 4
    grid = [[rnd.uniform(-1, 1) for _ in range(gw)] for _ in range(gh)]

    def smooth(x, y):
        fx, fy = x / w * gw, y / h * gh
        x0, y0 = int(fx) % gw, int(fy) % gh
        x1, y1 = (x0 + 1) % gw, (y0 + 1) % gh
        tx, ty = fx - int(fx), fy - int(fy)
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = grid[y0][x0] * (1 - tx) + grid[y0][x1] * tx
        b = grid[y1][x0] * (1 - tx) + grid[y1][x1] * tx
        return a * (1 - ty) + b * ty

    out = [[None] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            f = 1 + smooth(x, y) * blotch + rnd.uniform(-amp, amp)
            out[y][x] = clamp(tuple(c * f for c in base))
    return out


def leather():
    s, b = 128, 14  # big center: GUI nine-slice tiles the center, so a tiny one costs hundreds of draws
    tile = noise_tile(s, s, LEATHER, 0.05, 0.10)
    im = Image.new("RGBA", (s, s), T)
    for y in range(s):
        for x in range(s):
            c = tile[y][x] + (255,)
            d = min(x, y, s - 1 - x, s - 1 - y)
            cx, cy = min(x, s - 1 - x), min(y, s - 1 - y)
            if cx + cy < 2:
                continue
            if d == 0 or cx + cy == 2:
                c = (30, 16, 8, 255)
            elif d == 1:
                tl = (y < s - 1 - y and cy <= cx) or (x < s - 1 - x and cx < cy)
                c = shade(c, 1.28 if tl else 0.72)
            elif d == 2:
                c = shade(c, 1.1)
            im.putpixel((x, y), c)
    # stitching: inset 5, dashes 3 on / 2 off along the tiled edge (edge length 20 -> period 5 divides... 20/5=4)
    ins = 5
    for i in range(s):
        on = (i % 5) < 3
        if not on:
            continue
        for (x, y, sx, sy) in ((i, ins, 0, 1), (i, s - 1 - ins, 0, 1), (ins, i, 1, 0), (s - 1 - ins, i, 1, 0)):
            if ins <= x <= s - 1 - ins and ins <= y <= s - 1 - ins:
                im.putpixel((x, y), (214, 182, 120, 255))
                xx, yy = x + sx, y + sy
                if 0 <= xx < s and 0 <= yy < s and im.getpixel((xx, yy)) != (214, 182, 120, 255):
                    im.putpixel((xx, yy), shade(im.getpixel((xx, yy)), 0.6))
    # gold corner protectors
    for (ox, oy, fx, fy) in ((0, 0, 1, 1), (s - 1, 0, -1, 1), (0, s - 1, 1, -1), (s - 1, s - 1, -1, -1)):
        for j in range(11):
            for i in range(11 - j):
                x, y = ox + fx * i, oy + fy * j
                cx, cy = i, j
                if cx + cy < 2:
                    continue
                if cx + cy == 2 or i + j == 10:
                    col = K if cx + cy == 2 or i + j == 10 else GOLD
                elif i + j == 9:
                    col = GOLD_D
                else:
                    col = GOLD_H if (i == 1 or j == 1) else (GOLD if (i + j) < 7 else GOLD_S)
                im.putpixel((x, y), col)
    save(im, "leather", nine(s, s, b))


PAPER = (236, 226, 202)


def page(name, gutter_right):
    s, b = 128, 8
    tile = noise_tile(s, s, PAPER, 0.018, 0.025)
    im = Image.new("RGBA", (s, s), T)
    for y in range(s):
        for x in range(s):
            c = tile[y][x] + (255,)
            if rnd.random() < 0.025:
                c = shade(c, 0.95)
            gx = (s - 1 - x) if gutter_right else x  # distance from the spine
            if gx < 8:
                c = shade(c, 0.80 + 0.20 * (gx / 8) ** 0.7)
            ox = x if gutter_right else (s - 1 - x)  # distance from the outer edge
            if ox == 0 or y == 0 or y == s - 1:
                c = (150, 128, 92, 255)
            elif ox == 2:
                c = shade(c, 0.86)  # page stack line
            elif ox == 1 or y == 1 or y == s - 2:
                c = shade(c, 0.95)
            im.putpixel((x, y), c)
    border = {"left": b if not gutter_right else 3, "right": b if gutter_right else 3, "top": 3, "bottom": 3}
    save(im, name, nine(s, s, border))


def ring():
    w, h = 24, 14
    im = Image.new("RGBA", (w, h), T)
    cx, cy = (w - 1) / 2, (h - 1) / 2
    for y in range(h):
        for x in range(w):
            e = ((x - cx) / (w / 2 - 0.5)) ** 2 + ((y - cy) / (h / 2 - 0.5)) ** 2
            if e > 1.0 or e < 0.22:
                continue
            if e > 0.80 or e < 0.32:
                col = (26, 26, 30, 255)
            else:
                v = 235 - (y / (h - 1)) * 150
                col = g(v)
                if e < 0.42:
                    col = g(v * 0.7)  # inner bevel
                if y < cy and abs(x - cx) < 4 and e > 0.5:
                    col = g(255)
            im.putpixel((x, y), col)
    save(im, "ring")


def tab():
    w, h = 24, 24
    im = Image.new("RGBA", (w, h), T)
    for y in range(h):
        for x in range(w):
            cy = min(y, h - 1 - y)
            rx = w - 1 - x
            if rx + cy < 3:
                continue
            if rx == 0 or cy == 0 or rx + cy == 3:
                col = K
            elif rx == 1 or cy == 1 or rx + cy == 4:
                col = g(255) if (y < h / 2 and cy <= rx) else g(140)
                if rx == 1 and y >= h / 2:
                    col = g(140)
                if cy == 1 and y < h / 2:
                    col = g(255)
            else:
                col = g(205 - (y / h) * 30)
            im.putpixel((x, y), col)
    # symmetric borders: vanilla nine-slice drew the right edge column wrong with left 2 / right 6 (holes on the edge)
    save(im, "tab", nine(w, h, 6))


def slot_locked():
    w, h = 63, 88
    im = Image.new("RGBA", (w, h), T)
    base = (214, 202, 172)
    for y in range(h):
        for x in range(w):
            cx, cy = min(x, w - 1 - x), min(y, h - 1 - y)
            if cx + cy < 2:
                continue
            if cx == 0 or cy == 0 or cx + cy == 2:
                col = (176, 160, 124, 255)
            else:
                f = 1 + rnd.uniform(-0.015, 0.015)
                if y <= 3 or x <= 2:
                    f *= 0.93 if (y <= 2 or x <= 1) else 0.97  # inner shadow (recessed)
                col = clamp(tuple(c * f for c in base)) + (255,)
            im.putpixel((x, y), col)
    # dashed inner outline
    for i in range(4, w - 4):
        if (i // 2) % 2 == 0:
            im.putpixel((i, 4), (190, 175, 140, 255))
            im.putpixel((i, h - 5), (190, 175, 140, 255))
    for i in range(4, h - 4):
        if (i // 2) % 2 == 0:
            im.putpixel((4, i), (190, 175, 140, 255))
            im.putpixel((w - 5, i), (190, 175, 140, 255))
    q = ["..XXXXXX..",
         ".XXXXXXXX.",
         "XXX....XXX",
         "XXX....XXX",
         ".......XXX",
         "......XXX.",
         ".....XXX..",
         "....XXX...",
         "....XXX...",
         "..........",
         "....XXX...",
         "....XXX..."]
    sc = 2
    qw, qh = len(q[0]) * sc, len(q) * sc
    ox, oy = (w - qw) // 2 + 1, (h - qh) // 2
    for y, r in enumerate(q):
        for x, c in enumerate(r):
            if c != "X":
                continue
            for j in range(sc):
                for i in range(sc):
                    px, py = ox + x * sc + i, oy + y * sc + j
                    im.putpixel((px + 1, py + 1), (240, 232, 212, 255))
            for j in range(sc):
                for i in range(sc):
                    im.putpixel((ox + x * sc + i, oy + y * sc + j), (176, 158, 120, 255))
    save(im, "slot_locked")


def pocket():
    s, b = 64, 8
    tile = noise_tile(s, s, (70, 40, 23), 0.05, 0.10)
    im = Image.new("RGBA", (s, s), T)
    for y in range(s):
        for x in range(s):
            c = tile[y][x] + (255,)
            d = min(x, y, s - 1 - x, s - 1 - y)
            cx, cy = min(x, s - 1 - x), min(y, s - 1 - y)
            if cx + cy < 2:
                continue
            if d == 0 or cx + cy == 2:
                c = (24, 12, 6, 255)
            elif d == 1:
                tl = (y < s - 1 - y and cy <= cx) or (x < s - 1 - x and cx < cy)
                c = shade(c, 1.3 if tl else 0.7)
            elif d == 3 and ((x + y) % 5) < 3:
                c = (190, 160, 104, 255)
            im.putpixel((x, y), c)
    save(im, "pocket", nine(s, s, b))


def inv_slot():
    s = 18
    im = Image.new("RGBA", (s, s), T)
    for y in range(s):
        for x in range(s):
            if x == 0 or y == 0:
                col = (28, 15, 8, 255)
            elif x == s - 1 or y == s - 1:
                col = (150, 104, 66, 255)
            else:
                col = (48, 27, 15, 255)
            im.putpixel((x, y), col)
    save(im, "inv_slot")


def arrows():
    rows = ["........K.....",
            "........KK....",
            "KKKKKKKKKAK...",
            "KAAAAAAAAAAK..",
            "KAAAAAAAAAAAK.",
            "KAAAAAAAAAAK..",
            "KKKKKKKKKAK...",
            "........KK....",
            "........K....."]
    for name, fill in (("arrow", (138, 115, 85, 255)), ("arrow_hover", (181, 84, 47, 255))):
        im = Image.new("RGBA", (14, 9), T)
        for y, r in enumerate(rows):
            for x, c in enumerate(r):
                if c == "K":
                    im.putpixel((x, y), (74, 52, 35, 255))
                elif c == "A":
                    im.putpixel((x, y), fill if y != 3 else shade(fill, 1.2))
        save(im, name + "_next")
        save(im.transpose(Image.FLIP_LEFT_RIGHT), name + "_prev")


arrows()
card_frame(); card_shadow(); gem(); art_layers(); foil()
back_frame(); back_emblem()
leather(); page("page_left", True); page("page_right", False); ring(); tab(); slot_locked()
print("ok")

# ------------------------------------------------------------------ card remodel: smooth pieces (linear filtered)
from PIL import ImageDraw


def blurry(img, name):
    p = os.path.join(TEX, name + ".png")
    img.save(p)
    with open(p + ".mcmeta", "w") as f:
        json.dump({"texture": {"blur": True}}, f)


def supersample(size, draw_fn, ss=4):
    big = Image.new("RGBA", (size * ss, size * ss), T)
    draw_fn(ImageDraw.Draw(big), ss)
    return big.resize((size, size), Image.LANCZOS)


def star5(cx, cy, ro, ri, rot=-90):
    pts = []
    for i in range(10):
        r = ro if i % 2 == 0 else ri
        a = math.radians(rot + i * 36)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def symbols():
    W = (255, 255, 255, 255)
    sz = 64
    blurry(supersample(sz, lambda d, k: d.ellipse([(32 - 15) * k, (32 - 15) * k, (32 + 15) * k, (32 + 15) * k], fill=W)), "sym_common")
    blurry(supersample(sz, lambda d, k: d.polygon([(32 * k, 10 * k), (54 * k, 32 * k), (32 * k, 54 * k), (10 * k, 32 * k)], fill=W)), "sym_uncommon")
    blurry(supersample(sz, lambda d, k: d.polygon([(x * k, y * k) for x, y in star5(32, 34, 27, 11)], fill=W)), "sym_rare")

    def epic(d, k):  # two stars side by side, 2:1 wide sprite drawn in a square (centered)
        for cx in (17, 47):
            d.polygon([(x * k, y * k) for x, y in star5(cx, 33, 15, 6.2)], fill=W)
    blurry(supersample(sz, epic), "sym_epic")

    def crown(d, k):
        d.polygon([(x * k, y * k) for x, y in [(6, 20), (20, 31), (32, 11), (44, 31), (58, 20), (53, 46), (11, 46)]], fill=W)
        d.rounded_rectangle([11 * k, 49 * k, 53 * k, 55 * k], radius=2 * k, fill=W)
    blurry(supersample(sz, crown), "sym_legendary")


def sparkle():
    # tile of four-point stars (Epic holo); white on transparent, the holo colors it
    n = 64

    def star4(d, cx, cy, r, w, k):
        d.polygon([(cx * k, (cy - r) * k), ((cx + w) * k, (cy - w) * k), ((cx + r) * k, cy * k), ((cx + w) * k, (cy + w) * k),
                   (cx * k, (cy + r) * k), ((cx - w) * k, (cy + w) * k), ((cx - r) * k, cy * k), ((cx - w) * k, (cy - w) * k)], fill=(255, 255, 255, 255))

    def draw(d, k):
        star4(d, 13, 12, 8, 1.6, k)
        star4(d, 45, 37, 5, 1.0, k)
        star4(d, 32, 55, 3.4, .7, k)
        star4(d, 56, 8, 2.6, .6, k)
        for cx, cy, r in ((53, 52, 1.3), (6, 40, 1.1), (27, 28, 1.0)):
            d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=(255, 255, 255, 255))
    blurry(supersample(n, draw), "sparkle")


def etch():
    n = 32

    def draw(d, k):
        for r in (15, 9, 3.5):
            pts = [(16, 16 - r), (16 + r, 16), (16, 16 + r), (16 - r, 16), (16, 16 - r)]
            d.line([(x * k, y * k) for x, y in pts], fill=(255, 255, 255, 255), width=int(.9 * k))
    blurry(supersample(n, draw), "etch")


symbols(); sparkle(); etch()
print("ok remodel")
