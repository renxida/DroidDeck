#!/usr/bin/env python3
"""Summarise threads.csv from a Kestrel telemetry bundle: per-thread CPU and run-queue wait, per minute.

Usage: thread_report.py <bundle.zip | threads.csv> [--top N]
"""
import csv
import io
import sys
import zipfile
from collections import defaultdict


def load(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            name = next((n for n in z.namelist() if n.endswith("threads.csv")), None)
            if name is None:
                sys.exit("no threads.csv in " + path)
            text = z.read(name).decode()
    else:
        text = open(path).read()
    return list(csv.DictReader(io.StringIO(text)))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 12
    rows = load(args[0])
    if not rows:
        sys.exit("threads.csv is empty")
    t0 = min(int(r["t_wall_ms"]) for r in rows)
    whole = defaultdict(lambda: defaultdict(list))
    per = defaultdict(lambda: defaultdict(list))
    for r in rows:
        minute = (int(r["t_wall_ms"]) - t0) // 60000
        key = (r["process"], r["name"])
        cpu = float(r["cpu_pct"] or 0)
        wait = float(r["wait_pct"] or 0)
        (whole if r["name"] == "*" else per)[key][minute].append((cpu, wait, r["core"]))
    minutes = sorted({m for v in list(whole.values()) + list(per.values()) for m in v})
    def avg(xs, i):
        return sum(x[i] for x in xs) / len(xs) if xs else 0.0
    print("cpu% (wait%) per minute from the first sample; '*' is the whole process")
    print("%-34s" % "process / thread" + "".join("%13s" % ("min %d" % m) for m in minutes))
    for key, by in sorted(whole.items(), key=lambda kv: -sum(avg(v, 0) for v in kv[1].values())):
        print("%-34s" % ("%s *" % key[0])[:34] + "".join("%13s" % ("%.0f (%.0f)" % (avg(by[m], 0), avg(by[m], 1)) if by.get(m) else "-") for m in minutes))
    ranked = sorted(per.items(), key=lambda kv: -sum(avg(v, 0) for v in kv[1].values()))[:top]
    for key, by in ranked:
        cores = sorted({c for v in by.values() for _, _, c in v if c})
        label = "%s (cores %s)" % (key[1], ",".join(cores))
        print("%-34s" % label[:34] + "".join("%13s" % ("%.0f (%.0f)" % (avg(by[m], 0), avg(by[m], 1)) if by.get(m) else "-") for m in minutes))


if __name__ == "__main__":
    main()
