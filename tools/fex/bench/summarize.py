#!/usr/bin/env python3
"""Turn run.sh output into a markdown table: per phase, best time per config and the ratio to native."""
import csv, sys
from collections import defaultdict

res, stats = sys.argv[1], sys.argv[2]
best = defaultdict(dict)
configs, phases = [], []
for r in csv.DictReader(open(res)):
    c, p = r["config"], r["phase"]
    if p == "gc_count":
        continue
    if c not in configs: configs.append(c)
    if p not in phases: phases.append(p)
    v = float(r["best_ms"])
    best[p][c] = min(v, best[p].get(c, v))

def fmt(v): return f"{v:,.0f}" if v >= 100 else f"{v:.1f}"
print("| phase | " + " | ".join(configs) + " |")
print("|---|" + "---|" * len(configs))
tot = defaultdict(float)
for p in phases:
    row = []
    base = best[p].get("native")
    for c in configs:
        v = best[p].get(c)
        if v is None: row.append("-"); continue
        tot[c] += v
        row.append(fmt(v) + (f" ({v / base:.1f}x)" if base and c != "native" else ""))
    print(f"| {p} | " + " | ".join(row) + " |")
base = tot.get("native")
print("| **total ms** | " + " | ".join(fmt(tot[c]) + (f" ({tot[c] / base:.2f}x)" if base and c != "native" else "") for c in configs) + " |")
if "fex_notso" in tot:
    n = tot["fex_notso"]
    print()
    print("Cost of each setting relative to FEX with TSO off (same translator, no memory ordering):")
    for c in configs:
        if c.startswith("fex") and c != "fex_notso":
            print(f"- {c}: {100 * (tot[c] / n - 1):+.1f}%")

agg = defaultdict(lambda: defaultdict(list))
for r in csv.DictReader(open(stats)):
    if not r.get("jit_ms"): continue
    for k in ("wall_s", "jit_ms", "signal_ms", "sigbus", "smc", "jit_blocks", "cache_miss"):
        try: agg[r["config"]][k].append(float(r[k]))
        except ValueError: pass
if agg:
    print()
    print("| FEX stats (mean over rounds) | wall s | JIT ms | JIT share | blocks | SMC | SIGBUS | signal ms |")
    print("|---|---|---|---|---|---|---|---|")
    for c, d in agg.items():
        m = {k: sum(v) / len(v) for k, v in d.items() if v}
        share = m["jit_ms"] / (m["wall_s"] * 1000) if m.get("wall_s") else 0
        print(f"| {c} | {m['wall_s']:.1f} | {m['jit_ms']:,.0f} | {100 * share:.0f}% | {m['jit_blocks']:,.0f} | {m['smc']:,.0f} | {m['sigbus']:,.0f} | {m['signal_ms']:,.0f} |")
