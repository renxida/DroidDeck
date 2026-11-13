#!/usr/bin/env python3
"""What the hottest translated blocks are made of. Joins three things from one run of FEX built with
ENABLE_VIXL_DISASSEMBLER (FEX_DISASSEMBLE=blocks,stats, FEX_BLOCKJITNAMING=1, disk cache off):
  log      FEX's output log: per block "RIP: 0x<guest> Host: 0x<host>", guest and host instruction counts, ARM code
  perfmap  /tmp/perf-<pid>.map from the same run: host start, size, block name
  report   `perf report --no-children --sort symbol` text for the same run
Prints, weighted by each block's share of samples: ARM instructions per guest instruction, and the share of ARM
instructions that keep x86 flags, order memory (TSO), touch FEX's state block in memory (spills and context), are
vector/NEON, ordinary loads/stores, branches and the rest. Then the hottest blocks' code, for hand reading.
Usage: blockmix.py LOG PERFMAP REPORT [TOP]"""
import collections, re, sys

log, perfmap, report = sys.argv[1:4]
top = int(sys.argv[4]) if len(sys.argv) > 4 else 40

blocks, cur, code = {}, None, None
for line in open(log, errors="replace"):
    line = re.sub(r"^\[\w\]\s*", "", line.rstrip("\n"))
    m = re.match(r"RIP: 0x([0-9a-f]+) Host: 0x([0-9a-f]+)", line)
    if m:
        cur = {"rip": int(m.group(1), 16), "host": int(m.group(2), 16), "guest_n": 0, "host_n": 0, "code": []}
        blocks[cur["host"]] = cur
        continue
    if cur is None:
        continue
    if line.startswith("Guest Code instructions:"):
        cur["guest_n"] = int(line.split(":")[1])
    elif line.startswith("Host Code instructions:"):
        cur["host_n"] = int(line.split(":")[1])
    elif line == "Disassemble Begin":
        code = cur["code"]
    elif line == "Disassemble End":
        code = None
    elif code is not None:
        code.append(line.strip())

# A multiblock region is registered once per sub-block under one name: its lowest address lies in the region.
names = {}
for line in open(perfmap, errors="replace"):
    p = line.split(maxsplit=2)
    if len(p) == 3:
        a, n = int(p[0], 16), p[2].strip()
        names[n] = min(a, names.get(n, a))
starts = sorted(blocks)

def block_at(addr):
    import bisect
    i = bisect.bisect_right(starts, addr) - 1
    if i < 0:
        return None
    b = blocks[starts[i]]
    return b if addr < b["host"] + 4 * max(b["host_n"], len(b["code"])) + 256 else None

hot = []
for line in open(report, errors="replace"):
    m = re.match(r"\s*([\d.]+)%\s+\[\.\]\s+(.+?)\s*$", line)
    if m and m.group(2) in names:
        hot.append((float(m.group(1)), m.group(2)))
hot.sort(reverse=True)

def kind(ins):
    op = ins.split()[0] if ins else ""
    if "nzcv" in ins or op in ("setf8", "setf16", "rmif", "cfinv", "axflag", "xaflag"):
        return "flags"
    if op in ("dmb", "dsb", "isb") or re.match(r"(ldapr|ldar|stlr|ldapur|stlur|ldaxr|stlxr|ldxr|stxr|cas|ldadd|ldclr|ldset|ldeor|swp)", op):
        return "ordering"
    if "[x28" in ins:
        return "state"
    if re.search(r"\b[vqdsbh]\d+\b|\bz\d+\b", ins) and not op.startswith(("b", "cb", "tb")):
        return "vector"
    if re.match(r"(ldr|str|ldp|stp|ldur|stur)", op):
        return "load/store"
    if re.match(r"(b|bl|br|blr|ret|cbz|cbnz|tbz|tbnz)(\.|$)", op):
        return "branch"
    return "alu/other"

KINDS = ["alu/other", "load/store", "branch", "flags", "ordering", "state", "vector"]
w = collections.Counter()
wsum = blow = blow_w = covered = 0.0
rows = []
for pct, name in hot[:top]:
    b = block_at(names[name])
    if not b or not b["code"]:
        continue
    c = collections.Counter(kind(i) for i in b["code"])
    n = sum(c.values())
    for k in KINDS:
        w[k] += pct * c[k] / n
    wsum += pct
    if b["guest_n"]:
        blow += pct * b["host_n"] / b["guest_n"]
        blow_w += pct
    rows.append((pct, name, b, c, n))
total = sum(p for p, _ in hot)
print(f"blocks with code: {len(rows)} of the top {min(top, len(hot))}, {wsum:.1f}% of samples (all named blocks: {total:.1f}%)")
if blow_w:
    print(f"ARM instructions per guest instruction (sample-weighted): {blow / blow_w:.2f}")
if wsum:
    print("ARM instruction mix in the hot blocks (sample-weighted):")
    for k in KINDS:
        print(f"  mix: {k:11s} {100 * w[k] / wsum:5.1f}%")
print()
for pct, name, b, c, n in rows[:8]:
    mix = ", ".join(f"{k} {c[k]}" for k in KINDS if c[k])
    print(f"== {pct:.2f}% {name}  guest {b['guest_n']} -> host {b['host_n']}  ({mix})")
    for ins in b["code"][:120]:
        print(f"   {ins}")
    if len(b["code"]) > 120:
        print(f"   ... {len(b['code']) - 120} more")
