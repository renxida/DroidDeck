"""Counts JIT compiles in a DOTNET_JitStdOutFile written with DOTNET_JitDisasmSummary=1.

Usage: jit-count.py LABEL FILE PREFIX
Prints one CSV row: label,own_first,own_tier1,all_first,all
  own_first  methods whose name starts with PREFIX compiled for the first time (Tier0, instrumented, FullOpts,
             MinOpts: the code ReadyToRun would have supplied)
  own_tier1  methods starting with PREFIX recompiled hot at Tier1 (happens with or without ReadyToRun)
  all_first  first-time compiles of any method
  all        every line"""
import re
import sys

label, path, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
own_first = own_tier1 = all_first = total = 0
for line in open(path, encoding="utf-8", errors="replace"):
    match = re.search(r"JIT compiled (\S+).*\[([^,\]]+)", line)
    if not match:
        continue
    total += 1
    first = not match.group(2).strip().startswith("Tier1")
    all_first += first
    if match.group(1).startswith(prefix):
        if first:
            own_first += 1
        else:
            own_tier1 += 1
print("%s,%d,%d,%d,%d" % (label, own_first, own_tier1, all_first, total))
