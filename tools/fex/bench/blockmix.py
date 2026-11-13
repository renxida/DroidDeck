#!/usr/bin/env python3
"""What the hottest translated blocks are made of. Joins three things from one run of FEX built with
ENABLE_VIXL_DISASSEMBLER (FEX_DISASSEMBLE=blocks,stats, FEX_BLOCKJITNAMING=1, disk cache off):
  log      FEX's output log: per block "RIP: 0x<guest> Host: 0x<host>", guest and host instruction counts, ARM code
  perfmap  /tmp/perf-<pid>.map from the same run: host start, size, block name
  ips      `perf script -F ip` for the same run: one sampled host address per line (joined by address, since
           perf report truncates and pads long JIT names)
Prints, weighted by each block's share of samples: ARM instructions per guest instruction, and the share of ARM
instructions that keep x86 flags, order memory (TSO), touch FEX's state block in memory (spills and context), are
vector/NEON, ordinary loads/stores, branches and the rest. Then the hottest blocks' code, for hand reading.
Usage: blockmix.py LOG PERFMAP IPS [TOP]"""
import collections, re, sys

log, perfmap, report = sys.argv[1:4]
top = int(sys.argv[4]) if len(sys.argv) > 4 else 40

blocks, cur, code = {}, None, None
for line in open(log, errors="replace"):
    # Log lines may carry a level or thread prefix such as "[I] " or "[DEBUG] "; drop any leading [..] tokens.
    # FEX prefixes each log line with its level ("I ", or "[I] " in some builds); drop it.
    line = re.sub(r"^(\[[^\]]*\]\s*|[A-Z] )", "", line.rstrip("\n")).strip()
    m = re.search(r"RIP: 0x([0-9a-fA-F]+) Host: 0x([0-9a-fA-F]+)", line)
    if m:
        cur = {"rip": int(m.group(1), 16), "host": int(m.group(2), 16), "guest_n": 0, "host_n": 0, "code": []}
        blocks[cur["host"]] = cur
        continue
    if cur is None:
        continue
    m = re.search(r"(Guest|Host) Code instructions:\s*(\d+)", line)
    if m:
        cur["guest_n" if m.group(1) == "Guest" else "host_n"] = int(m.group(2))
    elif "Disassemble Begin" in line:
        code = cur["code"]
    elif "Disassemble End" in line:
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

counts, nsamples, unmatched = collections.Counter(), 0, collections.Counter()
for line in open(report, errors="replace"):
    t = line.strip()
    if not t:
        continue
    try:
        ip = int(t.split()[0], 16)
    except ValueError:
        continue
    nsamples += 1
    b = block_at(ip)
    if b:
        counts[b["host"]] += 1
    else:
        unmatched[ip >> 20] += 1
hot = sorted(((100.0 * n / nsamples, f"JIT_0x{blocks[h]['rip']:x}_0x{h:x}", h) for h, n in counts.items()), reverse=True)

if not hot:
    print(f"diagnostics: {len(blocks)} blocks parsed from the log, {sum(1 for b in blocks.values() if b['code'])} with code;"
          f" {nsamples} samples, none inside a logged block")
    print("busiest 1 MB regions of unmatched samples:", [(hex(r << 20), n) for r, n in unmatched.most_common(8)])
    print("first blocks:", [hex(h) for h in starts[:5]])

def kind(ins):
    # Drop a leading address or raw encoding ("0x...:", "0000000000001000", "d503201f") before the mnemonic.
    toks = [t for t in ins.replace(":", " ").split()]
    while toks and re.fullmatch(r"(0x)?[0-9a-fA-F]{8,16}", toks[0]):
        toks.pop(0)
    ins = " ".join(toks)
    op = toks[0] if toks else ""
    if "nzcv" in ins or op in ("setf8", "setf16", "rmif", "cfinv", "axflag", "xaflag"):
        return "flags"
    if op in ("dmb", "dsb", "isb") or re.match(r"(ldapr|ldar|stlr|ldapur|stlur|ldaxr|stlxr|ldxr|stxr|cas|ldadd|ldclr|ldset|ldeor|swp)", op):
        return "ordering"
    if "[x28, #160]" in ins:
        return "branch lookup"  # loads the per-thread lookup table for an indirect branch, call or return
    if "[x28" in ins:
        return "state"
    if op == "movk":
        return "constants"  # 64-bit immediates (guest addresses) built 16 bits at a time
    if op in ("adds", "subs", "cmp", "cmn", "tst", "ands", "bics", "adcs", "sbcs", "ccmp", "ccmn", "fcmp", "fcmpe", "negs", "ngcs"):
        return "sets flags"
    if re.search(r"\b[vqdsbh]\d+\b|\bz\d+\b", ins) and not op.startswith(("b", "cb", "tb")):
        return "vector"
    if re.match(r"(ldr|str|ldp|stp|ldur|stur)", op):
        return "load/store"
    if re.match(r"(b|bl|br|blr|ret|cbz|cbnz|tbz|tbnz)(\.|$)", op):
        return "branch"
    return "alu/other"

KINDS = ["alu/other", "load/store", "branch", "sets flags", "flags", "ordering", "state", "vector", "branch lookup", "constants"]
w = collections.Counter()
wsum = blow = blow_w = covered = 0.0
rows = []
for pct, name, h in hot[:top]:
    b = blocks[h]
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
total = sum(p for p, _, _ in hot)
print(f"blocks with code: {len(rows)} of the top {min(top, len(hot))}, {wsum:.1f}% of samples (all translated blocks: {total:.1f}%)")
if blow_w:
    print(f"ARM instructions per guest instruction (sample-weighted): {blow / blow_w:.2f}")
if wsum:
    print("ARM instruction mix in the hot blocks (sample-weighted):")
    for k in KINDS:
        print(f"  mix: {k:13s} {100 * w[k] / wsum:5.1f}%")
    # Each lookup is the table load plus about 6 more instructions (mask, add, pair load, compare, branch, fallback).
    print(f"  est. inline indirect-branch lookups (8 instr each): {800 * w['branch lookup'] / wsum:5.1f}%")
    print(f"  est. 64-bit constant building (movk plus its mov): {100 * w['constants'] * 4 / 3 / wsum:5.1f}%")
print()
for pct, name, b, c, n in rows[:8]:
    mix = ", ".join(f"{k} {c[k]}" for k in KINDS if c[k])
    print(f"== {pct:.2f}% {name}  guest {b['guest_n']} -> host {b['host_n']}  ({mix})")
    for ins in b["code"][:120]:
        print(f"   {ins}")
    if len(b["code"]) > 120:
        print(f"   ... {len(b['code']) - 120} more")
