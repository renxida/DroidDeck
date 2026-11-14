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
pids=$(pgrep -f "$(basename "$exe")" | tr '\n' ' ')
echo "== $label: $(cat "$log" | tr '\n' ' ') start $(echo "$ready - $start" | bc) s, unix pids ${pids:-none}"
if [ -n "$pids" ] && [ -n "$range" ]; then
  python3 -I "$(dirname "$0")/smaps.py" "$range" $pids | grep . || for p in $pids; do echo "  pid $p ($(cat /proc/$p/comm 2>/dev/null)): no mapping at $range"; done
fi
wineserver -k 2>/dev/null || true
wait 2>/dev/null
rm -f "$log"
