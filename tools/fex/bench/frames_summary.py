#!/usr/bin/env python3
"""One table from cache-bench.sh's frames.csv. Warm columns are medians over every warm run of every sequence;
cold columns over the cold runs. first run = process start to the end of the first frame, on a cold cache."""
import csv, statistics, sys
from collections import defaultdict

rows = list(csv.DictReader(open(sys.argv[1])))
by = defaultdict(lambda: {"cold": [], "warm": []})
order = []
for r in rows:
    if r["config"] not in order:
        order.append(r["config"])
    by[r["config"]]["cold" if r["run"] == "cold" else "warm"].append(r)

def med(rs, k):
    v = [float(r[k]) for r in rs if r.get(k) not in (None, "")]
    return statistics.median(v) if v else None

def f(v, d=1):
    return "-" if v is None else f"{v:.{d}f}"

sums = {r["checksum"] for r in rows}
native = by.get("native_jit", {}).get("warm") or by.get("native_jit", {}).get("cold") or []
base = med(native, "frame_p50_ms")
print("| config | warm p50 ms | warm p99 ms | warm max ms | first run ms (cold) | cold total ms | warm total ms | "
      "translated ms cold / warm | cache hit warm | code buffer full | RSS MB warm |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for name in order:
    c, w = by[name]["cold"], by[name]["warm"] or by[name]["cold"]
    first = None
    if c:
        v = [float(r["startup_ms"]) + float(r["first_frame_ms"]) for r in c if r["startup_ms"] and r["first_frame_ms"]]
        first = statistics.median(v) if v else None
    p50 = med(w, "frame_p50_ms")
    rel = f" ({p50 / base:.2f}x)" if p50 and base else ""
    hit = None
    hs = [(float(r["disk_hit"]), float(r["disk_miss"])) for r in w if r.get("disk_hit")]
    if hs and sum(h + m for h, m in hs):
        hit = 100 * sum(h for h, _ in hs) / sum(h + m for h, m in hs)
    rss = med(w, "max_rss_kb")
    print(f"| {name} | {f(p50, 2)}{rel} | {f(med(w, 'frame_p99_ms'), 2)} | {f(med(w, 'frame_max_ms'), 1)} | {f(first)} | "
          f"{f(med(c, 'total_ms'))} | {f(med(w, 'total_ms'))} | {f(med(c, 'jit_ms'), 0)} / {f(med(w, 'jit_ms'), 0)} | "
          f"{'-' if hit is None else f'{hit:.0f}%'} | {f(med(c + w, 'code_buffer_full'), 0)} | {f(rss / 1024 if rss else None, 0)} |")
print()
print(f"checksums: {', '.join(sorted(sums))}" + ("" if len(sums) == 1 else "  (MISMATCH: some config ran wrong code)"))
