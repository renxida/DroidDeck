#!/usr/bin/env python3
"""Disassemble the hottest named guest blocks from a profile run with FEX_BLOCKJITNAMING=1.

Reads `perf report --sort symbol` lines on stdin (symbols like /path/libc.so.6+0xab6f9), and for the top N
file-backed blocks prints the nearest exported symbol and the first instructions of the block, using
x86_64-linux-gnu-objdump on the file inside the rootfs.
"""
import bisect, re, subprocess, sys

N = int(sys.argv[1]) if len(sys.argv) > 1 else 6
seen = []
for line in sys.stdin:
    m = re.match(r"\s*([\d.]+)%.*?\[\.\]\s+(/\S+)\+0x([0-9a-f]+)", line)
    if m and (m.group(2), m.group(3)) not in [(f, o) for _, f, o in seen]:
        seen.append((float(m.group(1)), m.group(2), m.group(3)))
seen.sort(reverse=True)
objdump = "x86_64-linux-gnu-objdump"
for pct, path, off in seen[:N]:
    start = int(off, 16)
    syms = []
    try:
        out = subprocess.run([objdump, "-T", path], capture_output=True, text=True).stdout
        for s in out.splitlines():
            p = s.split()
            if len(p) >= 6 and re.fullmatch(r"[0-9a-f]{16}", p[0]) and any("F" in x for x in p[1:4]):
                syms.append((int(p[0], 16), p[-1]))
    except OSError as error:
        print("  block: no objdump (%s)" % error)
        break
    syms.sort()
    i = bisect.bisect_right(syms, (start, "￿")) - 1
    near = "%s+0x%x" % (syms[i][1], start - syms[i][0]) if i >= 0 else "?"
    print("  block: %.2f%% %s+0x%x (nearest export %s)" % (pct, path.split("/")[-1], start, near))
    dis = subprocess.run([objdump, "-d", "--no-show-raw-insn", "--start-address=0x%x" % start,
                          "--stop-address=0x%x" % (start + 0x80), path], capture_output=True, text=True).stdout
    for d in [l for l in dis.splitlines() if re.match(r"\s+[0-9a-f]+:", l)][:24]:
        print("  block:   " + d.strip())
