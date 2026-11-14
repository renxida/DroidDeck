"""Counts JIT compiles in a DOTNET_JitDisasmSummary file: own (namespace given) first-time compiles, own
re-compiles at Tier1, everything, first-time compiles of framework code (the framework's generics over the
program's types among them) and of System.Text.Json. Prints one CSV row:
label,own_first,own_tier1,all,other_first,json_first."""
import re
import sys

label, path, namespace = sys.argv[1], sys.argv[2], sys.argv[3]
own_first = own_tier1 = total = other_first = json_first = 0
for line in open(path, encoding="utf-8", errors="replace"):
    match = re.search(r"JIT compiled (\S+).*\[([^,\]]+)", line)
    if not match:
        continue
    total += 1
    first = not match.group(2).startswith("Tier1")
    if match.group(1).startswith(namespace + ".") or match.group(1).startswith("<"):
        if first:
            own_first += 1
        else:
            own_tier1 += 1
    elif first:
        other_first += 1
        json_first += "System.Text.Json" in match.group(1)
print("%s,%d,%d,%d,%d,%d" % (label, own_first, own_tier1, total, other_first, json_first))
