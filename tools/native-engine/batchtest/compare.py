#!/usr/bin/env python3
"""Compare the synthetic scene's frames and draw counts across engine arms.

    compare.py DIR [--logs LOGDIR] [ARM=RULE ...] [--fewer A<B ...]

DIR holds NAME.raw (width, height as little-endian u32, then RGBA8) and NAME.log (with "BATCHTEST NAME draws N";
in LOGDIR instead when given) for stock-a, stock-b and every arm. RULE is "exact" (every byte the same as stock-a) or "max=K" (no channel more
than K steps out of 255 away). --fewer A<B requires arm A to draw fewer calls than arm B (A<=B allows equal).
Prints one line per arm, and where pixels moved, where they moved. Exits 1 on any failure. If the two stock runs
differ, the renderer itself is not deterministic here, so it warns and passes rather than judging anything.
"""
import re
import sys


LOGS = {}


def log_path(d, name):
    return "%s/%s.log" % (LOGS.get("dir", d), name)


def load(d, name):
    raw = open("%s/%s.raw" % (d, name), "rb").read()
    log = open(log_path(d, name), errors="replace").read()
    m = re.search(r"BATCHTEST %s draws (\d+)" % re.escape(name), log)
    if not m:
        raise SystemExit("%s: no BATCHTEST line in %s.log" % (name, name))
    return raw, int(m.group(1))


VKCOUNT = re.compile(r"droiddeck-vkcount: window .*?per frame: .*? ([\d.]+) draws .*? ([\d.]+) pipeline-binds ([\d.]+) set-binds "
                     r"([\d.]+) push-consts ([\d.]+) passes ([\d.]+) barriers")
# Main-thread cost per call on the phone (PowerVR driver through the hybris layer, Nov 24 vkcount slopes).
PHONE_US = {"draws": 37, "set-binds": 25}


def vkcount(d, name):
    """The last steady window's per-frame Vulkan calls, or None when the counter layer did not report."""
    found = VKCOUNT.findall(open(log_path(d, name), errors="replace").read())
    if not found:
        return None
    keys = ("draws", "pipeline-binds", "set-binds", "push-consts", "passes", "barriers")
    return dict(zip(keys, (float(x) for x in found[-1])))


def phone_ms(c):
    return sum(c[k] * us for k, us in PHONE_US.items()) / 1000.0


def diff(a, b):
    """(pixels that differ, worst channel step, top cells, worst pixel)."""
    if len(a) != len(b):
        return None
    w = int.from_bytes(a[0:4], "little")
    moved, worst, cells, worst_at = 0, 0, {}, None
    av, bv = memoryview(a)[8:], memoryview(b)[8:]
    if av == bv:
        return 0, 0, [], None
    for i in range(0, len(av), 4):
        pa, pb = av[i:i + 4], bv[i:i + 4]
        if pa != pb:
            moved += 1
            d = max(abs(p - q) for p, q in zip(pa, pb))
            x, y = i // 4 % w, i // 4 // w
            key = (x // 32 * 32, y // 32 * 32)
            cells[key] = cells.get(key, 0) + 1
            if d > worst:
                worst, worst_at = d, (d, x, y, tuple(pa), tuple(pb))
    return moved, worst, sorted(cells.items(), key=lambda kv: -kv[1])[:8], worst_at


def main(argv):
    d, args = argv[0], argv[1:]
    rules, fewer = {}, []
    it = iter(args)
    for a in it:
        if a == "--fewer":
            fewer.append(next(it))
        elif a == "--logs":
            LOGS["dir"] = next(it)
        else:
            name, rule = a.split("=", 1)
            rules[name] = rule
    base, base_draws = load(d, "stock-a")
    other, _ = load(d, "stock-b")
    if base != other:
        print("::warning::two stock renders differ, so the pixel comparison means nothing here")
        return 0
    draws = {"stock-a": base_draws}
    bad = False
    calls = {}
    for name in ["stock-a"] + list(rules):
        calls[name] = vkcount(d, name)
    if any(calls.values()):
        print("Vulkan calls per steady frame, and what they would cost the phone's main thread (%s us per draw, set bind):"
              % ", ".join("%d" % v for v in PHONE_US.values()))
        for name, c in calls.items():
            if c:
                print("  %-10s %5.0f draws %5.0f set-binds %5.0f pipeline-binds %5.0f push-consts %4.1f passes %5.1f barriers"
                      "  ~%.2f ms" % (name, c["draws"], c["set-binds"], c["pipeline-binds"], c["push-consts"], c["passes"],
                                      c["barriers"], phone_ms(c)))
    print("%-10s %6s %8s %6s" % ("arm", "draws", "pixels", "worst"))
    print("%-10s %6d %8s %6s" % ("stock", base_draws, "-", "-"))
    for name, rule in rules.items():
        raw, n = load(d, name)
        draws[name] = n
        r = diff(base, raw)
        if r is None:
            print("::error::%s: frame size differs" % name)
            bad = True
            continue
        moved, worst, cells, worst_at = r
        limit = 0 if rule == "exact" else int(rule.split("=", 1)[1])
        ok = worst <= limit
        print("%-10s %6d %8d %6d  %s" % (name, n, moved, worst, "ok" if ok else "FAIL (limit %d)" % limit))
        if moved:
            print("           cells (x, y): count %s; worst (step, x, y, stock, new) %s" % (cells, worst_at))
        if not ok:
            print("::error::%s moved a pixel by %d steps, more than %d" % (name, worst, limit))
            bad = True
    for f in fewer:
        a, op, b = re.match(r"([\w-]+)(<=|<)([\w-]+)$", f).groups()
        if a not in draws:
            draws[a] = load(d, a)[1]
        if b not in draws:
            draws[b] = load(d, b)[1]
        if not (draws[a] < draws[b] if op == "<" else draws[a] <= draws[b]):
            print("::error::expected %s draws %s %s draws (%d vs %d)" % (a, op, b, draws[a], draws[b]))
            bad = True
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
