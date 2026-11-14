#!/usr/bin/env python3
"""Summarise the nightly FEX rows: per row (all repeats), hangs, unfinished runs, stalls and work against stock.

    nightly-summary.py <dir of v-*.txt>

Each line: <group> <seconds> VERDICT <program> <verdict> <secs> <ops> <stalled secs>.
"""
import collections
import glob
import re
import sys

runs = collections.defaultdict(list)
for path in glob.glob(f"{sys.argv[1]}/v-*.txt"):
    for line in open(path):
        p = line.split()
        if len(p) >= 8 and p[2] == "VERDICT":
            runs[(p[0], p[1])].append({"prog": p[3], "verdict": p[4], "secs": max(int(p[5]), 1), "ops": int(p[6]), "stalls": int(p[7])})


def rate(rows, prog_base, secs):
    # Work per wall second, start-up included (the Godot stand-in does a fixed number of frames, so only its time moves).
    ops = [r["ops"] / r["secs"] for r in rows if re.sub(r"\d+$", "", r["prog"]) == prog_base and r["verdict"] == "done"]
    return sum(ops) / len(ops) if ops else None


stock = {}
for (g, secs), rows in runs.items():
    if g == "stock":
        stock = {b: rate(rows, b, secs) for b in ("stress", "clr", "godot")}
print("| row | runs | hung | unfinished | stalled s | stress vs stock | clr vs stock | godot vs stock |")
print("|---|---|---|---|---|---|---|---|")
bad = []
for (g, secs), rows in sorted(runs.items()):
    hung = sum(r["verdict"] == "HANG" for r in rows)
    unf = sum(r["verdict"] not in ("done", "HANG") for r in rows)
    stalls = sum(r["stalls"] for r in rows)
    cells = []
    for b in ("stress", "clr", "godot"):
        v, s = rate(rows, b, secs), stock.get(b)
        cells.append("-" if v is None or not s else f"{v / s:.2f}x")
    print(f"| {g} ({secs} s) | {len(rows)} | {hung} | {unf} | {stalls} | {" | ".join(cells)} |")
    if hung:
        bad.append(f"{g}: {hung} hung")
print()
print("Hangs: " + ("; ".join(bad) if bad else "none"))
