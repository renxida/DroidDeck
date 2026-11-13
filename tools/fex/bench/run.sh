#!/usr/bin/env bash
# Run Bench.dll under each line of configs.txt; write results.csv (config,phase,best_ms,median_ms) and
# fexstats.csv (config + fex-stats summary). Inputs: BENCH_DLL, FEX_BIN (dir with the FEX binary),
# ROOTFS (x86-64 rootfs with x64 .NET at /opt/dotnet and the bench at /bench), FEX_STATS (fex-stats binary).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/bench-out}
REPS=${REPS:-5}
ROUNDS=${ROUNDS:-2}
mkdir -p "$OUT"
echo "config,round,phase,best_ms,median_ms" > "$OUT/results.csv"
echo "config,round,wall_s,fex,t_s,pid,threads,jit_ms,signal_ms,sigbus,smc,float_fallback,cache_miss,cache_rlock_ms,cache_wlock_ms,jit_blocks,disk_hit,disk_miss,disk_lookup_ms" > "$OUT/fexstats.csv"
GAME_DOTNET="DOTNET_TieredPGO=0 DOTNET_EnableWriteXorExecute=0 DOTNET_gcConcurrent=0 DOTNET_GCConserveMemory=5 DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1"
for round in $(seq 1 "$ROUNDS"); do
  grep -v '^\s*#' "$HERE/configs.txt" | grep -v '^\s*$' | while IFS='|' read -r name runner envs; do
    name=$(echo $name); runner=$(echo $runner)
    echo "== $name (round $round)" >&2
    start=$(date +%s.%N)
    if [ "$runner" = native ]; then
      env $GAME_DOTNET $envs dotnet "$BENCH_DLL" "$REPS" > "$OUT/$name.$round.txt"
    else
      rm -f /dev/shm/fex-*-stats
      "$FEX_STATS" -s -i 50 > "$OUT/$name.$round.stats" & sp=$!
      env $GAME_DOTNET $envs FEX_ROOTFS="$ROOTFS" FEX_PROFILESTATS=1 FEX_SILENTLOG=1 \
        "$FEX_BIN/FEX" /opt/dotnet/dotnet /bench/Bench.dll "$REPS" > "$OUT/$name.$round.txt" \
        || echo "checksum,crashed-$?,0" >> "$OUT/$name.$round.txt"
      sleep 0.3; kill $sp 2>/dev/null || true; wait $sp 2>/dev/null || true
    fi
    wall=$(echo "$(date +%s.%N) - $start" | bc)
    # The job log is the only place these can be read from outside GitHub's artifact storage.
    sed "s/^/  /" "$OUT/$name.$round.txt" >&2
    [ -s "$OUT/$name.$round.stats" ] 2>/dev/null && sed "s/^/  stats: /" "$OUT/$name.$round.stats" >&2
    tail -n +2 "$OUT/$name.$round.txt" | sed "s/^/$name,$round,/" >> "$OUT/results.csv"
    if [ -s "$OUT/$name.$round.stats" ] 2>/dev/null; then
      sed "s/^/$name,$round,$wall,/" "$OUT/$name.$round.stats" >> "$OUT/fexstats.csv"
    else
      echo "$name,$round,$wall,,,,,,,,,,,,,,,," >> "$OUT/fexstats.csv"
    fi
  done
done
python3 "$HERE/summarize.py" "$OUT/results.csv" "$OUT/fexstats.csv" | tee "$OUT/summary.md"
