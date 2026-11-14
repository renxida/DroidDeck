#!/usr/bin/env python3
"""smaps.py ADDR PID...: for each process mapping ADDR, prints the mappings from ADDR on (the section and the
ones right after it): size, Rss, Anonymous, Swap and whether they are file-backed. Also the process's totals."""
import sys


def mappings(pid):
    out, head, fields = [], None, {}
    for line in open("/proc/%s/smaps" % pid):
        parts = line.split()
        if parts and "-" in parts[0] and ":" not in parts[0]:
            if head:
                out.append((head, fields))
            head, fields = parts, {}
        elif len(parts) >= 2 and parts[0].endswith(":") and parts[1].isdigit():
            fields[parts[0][:-1]] = int(parts[1])
    if head:
        out.append((head, fields))
    return out


def main():
    addr = int(sys.argv[1], 16)
    for pid in sys.argv[2:]:
        try:
            maps = mappings(pid)
            comm = open("/proc/%s/comm" % pid).read().strip()
        except OSError:
            continue
        spans = [(int(h[0].split("-")[0], 16), int(h[0].split("-")[1], 16), h, f) for h, f in maps]
        at = [i for i, (lo, hi, _, _) in enumerate(spans) if lo <= addr < hi]
        if not at:
            continue
        print("  pid %s (%s), Rss %d MB, Anonymous %d MB in all" % (
            pid, comm, sum(f.get("Rss", 0) for _, f in maps) >> 10, sum(f.get("Anonymous", 0) for _, f in maps) >> 10))
        for lo, hi, h, f in spans[at[0]:at[0] + 3]:
            path = h[5] if len(h) > 5 else "[anonymous]"
            print("    %s %s %7.1f MB  Rss %d kB  Anonymous %d kB  Swap %d kB  %s" % (
                h[0], h[1], (hi - lo) / 1048576, f.get("Rss", 0), f.get("Anonymous", 0), f.get("Swap", 0), path))


main()
