"""生成各 dpi 的 ic_launcher.png（纯 Python，无第三方库）。"""
import math
import os
import struct
import zlib

OUT = r"D:\tmp\android\app\res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG_TOP = (13, 18, 25)
BG_BOT = (7, 11, 16)
ACCENT = (53, 224, 161)
RING = (53, 224, 161)


def write_png(path, w, h, pixels):
    raw = b"".join(b"\x00" + bytes(pixels[y * w * 4:(y + 1) * w * 4]) for y in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    idat = zlib.compress(raw, 9)
    with open(path, "wb") as f:
        f.write(sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (neg and pos)


def render(size):
    ss = 3
    px = [0] * (size * size * 4)
    cx = cy = size / 2.0
    ring_r = size * 0.30
    ring_w = size * 0.038
    tri = ((size * 0.435, size * 0.365),
           (size * 0.665, size * 0.5),
           (size * 0.435, size * 0.635))

    for y in range(size):
        for x in range(size):
            ar = ag = ab = 0.0
            rr = rg = rb = 0.0
            ta = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    fx = x + (sx + 0.5) / ss
                    fy = y + (sy + 0.5) / ss
                    # 背景渐变
                    t = fy / size
                    br = BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t
                    bg = BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t
                    bb = BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t
                    ar += br; ag += bg; ab += bb
                    # 圆环
                    d = math.hypot(fx - cx, fy - cy)
                    if abs(d - ring_r) <= ring_w / 2:
                        rr += RING[0]; rg += RING[1]; rb += RING[2]
                    # 三角
                    if in_triangle(fx, fy, *tri):
                        ta += 1
            n = ss * ss
            ar, ag, ab = ar / n, ag / n, ab / n
            ring_a = 1.0 - (rr / n) / 255.0 if rr else 1.0
            # 叠加圆环
            if rr:
                k = 0.55
                ar = ar * (1 - k) + (rr / n) * k
                ag = ag * (1 - k) + (rg / n) * k
                ab = ab * (1 - k) + (rb / n) * k
            # 叠加三角
            if ta > 0:
                cov = ta / n
                ar = ar * (1 - cov) + ACCENT[0] * cov
                ag = ag * (1 - cov) + ACCENT[1] * cov
                ab = ab * (1 - cov) + ACCENT[2] * cov
            i = (y * size + x) * 4
            px[i] = int(max(0, min(255, ar)))
            px[i + 1] = int(max(0, min(255, ag)))
            px[i + 2] = int(max(0, min(255, ab)))
            px[i + 3] = 255
    return px


for dpi, size in SIZES.items():
    folder = os.path.join(OUT, "mipmap-" + dpi)
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, "ic_launcher.png")
    write_png(path, size, size, render(size))
    print("生成", path, size, "px")
