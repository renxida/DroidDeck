#!/usr/bin/env python3
"""Print where an APK's bytes go: totals per top-level group and the largest entries."""

import argparse
import sys
import zipfile
from collections import defaultdict


def group_of(name):
    parts = name.split("/")
    if parts[0] in ("lib", "assets") and len(parts) > 2:
        return "/".join(parts[:2]) + "/"
    if len(parts) > 1:
        return parts[0] + "/"
    if name.endswith(".dex"):
        return "*.dex"
    return name


def entries(path):
    with zipfile.ZipFile(path) as apk:
        return [(i.filename, i.compress_size, i.file_size) for i in apk.infolist() if not i.is_dir()]


def mib(n):
    return f"{n / 1048576:9.2f}"


def report(rows, top):
    out = []
    total_c = sum(r[1] for r in rows)
    total_u = sum(r[2] for r in rows)
    out.append(f"entries {len(rows)}  compressed {mib(total_c).strip()} MiB  uncompressed {mib(total_u).strip()} MiB")
    groups = defaultdict(lambda: [0, 0, 0])
    for name, c, u in rows:
        g = groups[group_of(name)]
        g[0] += 1
        g[1] += c
        g[2] += u
    out.append("")
    out.append("by group (compressed MiB, uncompressed MiB, entries)")
    for name, (n, c, u) in sorted(groups.items(), key=lambda kv: -kv[1][1]):
        out.append(f"{mib(c)} {mib(u)} {n:6d}  {name}")
    for label, key in (("compressed", 1), ("uncompressed", 2)):
        out.append("")
        out.append(f"top {top} by {label} (compressed MiB, uncompressed MiB)")
        for name, c, u in sorted(rows, key=lambda r: -r[key])[:top]:
            out.append(f"{mib(c)} {mib(u)}  {name}")
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--top", type=int, default=30)
    args = parser.parse_args(argv)
    print(report(entries(args.apk), args.top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
