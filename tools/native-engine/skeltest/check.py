"""Checks the two SkelTest frames: the red arm is drawn, swings right by frame b, and the blue head follows."""
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos, idat, w = 8, b"", 0
    while pos < len(data):
        n, t = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if t == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and ctype in (2, 6), (depth, ctype)
            bpp = 4 if ctype == 6 else 3
        elif t == b"IDAT":
            idat += body
        pos += 12 + n
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    return w, h, bpp, rows


def centroid(path, want):
    w, h, bpp, rows = read_png(path)
    xs, ys = [], []
    for y, line in enumerate(rows):
        for x in range(w):
            r, g, b = line[x * bpp:x * bpp + 3]
            if want(r, g, b):
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return len(xs), sum(xs) / len(xs), sum(ys) / len(ys)


def main(out):
    red = lambda r, g, b: r > 200 and g < 60 and b < 60
    blue = lambda r, g, b: b > 200 and r < 60 and g < 60
    ra, rb = centroid(f"{out}/a.png", red), centroid(f"{out}/b.png", red)
    ba, bb = centroid(f"{out}/a.png", blue), centroid(f"{out}/b.png", blue)
    print("red a", ra, "red b", rb)
    print("blue a", ba, "blue b", bb)
    ok = True
    if not ra or not rb or not ba or not bb:
        print("FAIL: arm or head not drawn")
        return 1
    # Frame a: arm straight up from (128, 220), 100 long: centroid near x=128, y=170; head near y=120.
    ok &= abs(ra[1] - 128) < 4 and abs(ra[2] - 170) < 6
    ok &= abs(ba[1] - 128) < 4 and abs(ba[2] - 120) < 6
    # Frame b: rotated 60 degrees clockwise on screen: head near (128 + 100 sin60, 220 - 100 cos60) = (214.6, 170).
    ok &= abs(bb[1] - 214.6) < 6 and abs(bb[2] - 170) < 6
    ok &= rb[1] > ra[1] + 30
    # Area is preserved (drawn as a solid quad both times).
    ok &= 0.8 < rb[0] / ra[0] < 1.25
    print("OK" if ok else "FAIL: geometry off")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
