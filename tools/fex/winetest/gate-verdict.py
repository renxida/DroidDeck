#!/usr/bin/env python3
"""Decide the FEX gate from the stock and candidate runs' VERDICT lines.

    gate-verdict.py <stock verdicts> <candidate verdicts> <min throughput ratio> <stall slack seconds>

Each line: VERDICT <program> <done|HANG|exited-without-done|no-verdict> <seconds> <ops> <stalled seconds>.
Programs whose label ends in a digit (stress2) are repeats and pool with their base (stress) for throughput.
Prints a Markdown table and exits 1 if the candidate fails.
"""
import re
import sys


def load(path):
    rows = {}
    try:
        with open(path) as f:
            for line in f:
                p = line.split()
                if len(p) >= 6 and p[0] == "VERDICT":
                    rows[p[1]] = {"verdict": p[2], "seconds": int(p[3]), "ops": int(p[4]), "stalls": int(p[5])}
    except FileNotFoundError:
        pass
    return rows


def main():
    stock, cand = load(sys.argv[1]), load(sys.argv[2])
    ratio, slack = float(sys.argv[3]), int(sys.argv[4])
    failures, notes = [], []
    if not cand:
        failures.append("no candidate results (the candidate run did not finish)")
    print("| program | stock | candidate |")
    print("|---|---|---|")
    for name in sorted(set(stock) | set(cand)):
        s, c = stock.get(name), cand.get(name)
        fmt = lambda r: "missing" if r is None else f"{r['verdict']} {r['seconds']} s, {r['ops']:,} ops, {r['stalls']} stalled s"
        print(f"| {name} | {fmt(s)} | {fmt(c)} |")
        if c is None:
            failures.append(f"{name}: no candidate result")
            continue
        if c["verdict"] == "HANG":
            failures.append(f"{name}: hung under the candidate")
        elif c["verdict"] != "done":
            if s is not None and s["verdict"] == "done":
                failures.append(f"{name}: finishes under stock FEX but {c['verdict']} under the candidate")
            else:
                notes.append(f"{name}: {c['verdict']} under stock FEX too, so not counted against the candidate")
        if s is not None and c["verdict"] == "done" and s["verdict"] == "done" and c["stalls"] > s["stalls"] + slack:
            failures.append(f"{name}: {c['stalls']} stalled seconds against stock's {s['stalls']}")
    # Throughput, pooled over repeats (stress + stress2), only where both finished.
    pools = {}
    for name in set(stock) & set(cand):
        s, c = stock[name], cand[name]
        if s["verdict"] != "done" or c["verdict"] != "done":
            continue
        base = re.sub(r"\d+$", "", name)
        a = pools.setdefault(base, [0, 0])
        a[0] += s["ops"]
        a[1] += c["ops"]
    print()
    for base, (s_ops, c_ops) in sorted(pools.items()):
        r = c_ops / s_ops if s_ops else 0.0
        print(f"- {base}: candidate does {r:.2f}x stock's work (minimum {ratio:.2f})")
        if r < ratio:
            failures.append(f"{base}: throughput {r:.2f}x stock, below {ratio:.2f}")
    print()
    for n in notes:
        print(f"- note: {n}")
    if failures:
        print("**Gate failed**")
        for f in failures:
            print(f"- {f}")
            print(f"::error::{f}", file=sys.stderr)
        return 1
    print("**Gate passed**")
    return 0


if __name__ == "__main__":
    sys.exit(main())
