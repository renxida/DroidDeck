#!/usr/bin/env bash
# Where FEX's own memory goes, per config: run Bench.dll's frame loop (and the 4-thread contended workload) under FEX,
# sample /proc/<pid>/smaps every 0.5 s with smaps_rollup.py, and print a table of FEX's named mappings (code buffers,
# per-thread L2 lookup pages and L1 tables, block links, thread state) at their peak, plus frame p99.
# Inputs as cache-bench.sh: ROOTFS (x64 builds under /bench/jit), FEX_BIN, OUT.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/bench-out}
FRAMES=${FRAMES:-3000}
mkdir -p "$OUT/mem"
GAME_DOTNET="DOTNET_TieredPGO=0 DOTNET_EnableWriteXorExecute=0 DOTNET_gcConcurrent=0 DOTNET_GCConserveMemory=5 DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1"
GAME_FEX="FEX_TSOENABLED=1 FEX_VECTORTSOENABLED=0 FEX_MEMCPYSETTSOENABLED=0 FEX_HALFBARRIERTSOENABLED=1 FEX_MULTIBLOCK=1 FEX_DISKCACHE=0"
# name | env (on top of the game's settings)
CONFIGS=${CONFIGS:-"stock_dynamic_l1|FEX_DYNAMICL1CACHE=1
fex_l1_4mb|FEX_DYNAMICL1CACHE=0 FEX_L1CACHEENTRIES=262144
fex_l1_4mb_codebuf64|FEX_DYNAMICL1CACHE=0 FEX_L1CACHEENTRIES=262144 FEX_INITIALCODEBUFFERMB=64
${EXTRA_CONFIGS:-}"}
labels=()
for workload in frames threads_contended; do
  args="$FRAMES frames"; [ "$workload" = threads_contended ] && args="20 threads_contended"
  while IFS='|' read -r name envs; do
    [ -n "$name" ] || continue
    echo "== $workload $name" >&2
    txt="$OUT/mem/$workload.$name.txt"; js="$OUT/mem/$workload.$name.json"
    pkill -x FEXServer 2>/dev/null || true
    env $GAME_DOTNET $GAME_FEX $envs FEX_ROOTFS="$ROOTFS" FEX_SILENTLOG=1 \
      "$FEX_BIN/FEX" /opt/dotnet/dotnet /bench/jit/Bench.dll $args < /dev/null > "$txt" 2> >(sed "s/^/  /" >&2) &
    pid=$!
    python3 "$HERE/smaps_rollup.py" watch "$pid" "$js" 0.5 &
    wp=$!
    wait $pid || echo "  ($name exited $?)" >&2
    wait $wp || true
    p99=$(awk -F, '$1=="frame_p99_ms"{print $2}' "$txt")
    labels+=("$workload $name${p99:+ (p99 ${p99} ms)}=$js")
  done <<< "$CONFIGS"
done
python3 "$HERE/smaps_rollup.py" table "${labels[@]}" | tee "$OUT/mem-summary.md"
