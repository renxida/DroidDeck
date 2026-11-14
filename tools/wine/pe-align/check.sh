#!/bin/bash
# check.sh LABEL EXE [touch]: runs EXE under Wine, finds the .pck section's mapping in the process's smaps and prints
# whether it is file-backed or anonymous (copied), with its Rss, Anonymous and Swap, and how long start took.
set -uo pipefail
label=$1 exe=$2 touch=${3:-}
log=$(mktemp)
start=$(date +%s.%N)
wine "$exe" 40 $touch > "$log" 2>/dev/null &
for _ in $(seq 1 600); do grep -q '^ready' "$log" && break; sleep 0.1; done
ready=$(date +%s.%N)
range=$(sed -n 's/^pck \(0x\)\{0,1\}\([0-9a-fA-F]*\)-.*/\2/p' "$log")
pid=$(pgrep -f "$(basename "$exe")" | head -1)
echo "== $label: $(cat "$log" | tr '\n' ' ') start $(echo "$ready - $start" | bc) s, unix pid ${pid:-none}"
if [ -n "$pid" ] && [ -n "$range" ]; then
  python3 -I - "$pid" "$range" <<'PY'
import sys
pid, addr = sys.argv[1], int(sys.argv[2], 16)
head, fields, hit = None, {}, []
def flush():
    if head:
        lo, hi = (int(x, 16) for x in head.split()[0].split("-"))
        if lo <= addr < hi or (hit and lo == hit[-1][1]):
            hit.append((lo, hi, head, dict(fields)))
for line in open("/proc/%s/smaps" % pid):
    parts = line.split()
    if "-" in parts[0] and len(parts[0]) > 8 and ":" not in parts[0]:
        flush(); head, fields = line.strip(), {}
    elif parts[0].endswith(":") and len(parts) >= 2 and parts[1].isdigit():
        fields[parts[0][:-1]] = int(parts[1])
flush()
for lo, hi, h, f in hit[:4]:
    rest = h.split()
    path = rest[5] if len(rest) > 5 else "[anonymous]"
    print("  %s %s %6.1f MB  Rss %d kB  Anonymous %d kB  Swap %d kB  %s" % (
        rest[0], rest[1], (hi - lo) / 1048576, f.get("Rss", 0), f.get("Anonymous", 0), f.get("Swap", 0), path))
PY
fi
wineserver -k 2>/dev/null || true
wait 2>/dev/null
rm -f "$log"
