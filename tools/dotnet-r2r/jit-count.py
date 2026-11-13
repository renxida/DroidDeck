"""Counts JIT compiles in a DOTNET_JitDisasmSummary file: own (namespace given) first-time compiles, own
re-compiles at Tier1, and everything. Prints one CSV row: label,own_first,own_tier1,all."""
import re
import sys

label, path, namespace = sys.argv[1], sys.argv[2], sys.argv[3]
own_first = own_tier1 = total = 0
for line in open(path, encoding="utf-8", errors="replace"):
    match = re.search(r"JIT compiled (\S+).*\[([^,\]]+)", line)
    if not match:
        continue
    total += 1
    if match.group(1).startswith(namespace + ".") or match.group(1).startswith("<"):
        if match.group(2).startswith("Tier1"):
            own_tier1 += 1
        else:
            own_first += 1
print("%s,%d,%d,%d" % (label, own_first, own_tier1, total))
