#!/usr/bin/env bash
# Compare ways of cutting translation cost on one stand-in: Bench.dll's frame loop (see Bench.Frames), under each line
# of cache-configs.txt. Each config runs as a sequence: cold (its own empty FEX code cache), then WARM warm runs that
# reuse that cache, as a game does on its next launch. The sequence repeats SEQS times. Writes frames.csv and prints
# frames-summary.md. Inputs: JIT_DLL and R2R_DLL (native arm64 builds), ROOTFS with each x64 build in its own folder
# /bench/<dll> (the config's dll column: jit, r2r, or any other variant), FEX_BIN, FEX_STATS. A folder holding an
# executable Bench (self-contained) runs that instead of /opt/dotnet/dotnet. DOTNET_* in a config's env apply too.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/bench-out}
FRAMES=${FRAMES:-3000}
WARM=${WARM:-3}
SEQS=${SEQS:-2}
mkdir -p "$OUT"
CSV="$OUT/frames.csv"
echo "config,seq,run,startup_ms,first_frame_ms,frame_p50_ms,frame_p99_ms,load_frame_ms,load_frame_max_ms,frame_max_ms,total_ms,checksum,jit_ms,jit_blocks,disk_hit,disk_miss,code_buffer_full,max_rss_kb" > "$CSV"
GAME_DOTNET="DOTNET_TieredPGO=0 DOTNET_EnableWriteXorExecute=0 DOTNET_gcConcurrent=0 DOTNET_GCConserveMemory=5 DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1"
GAME_FEX="FEX_TSOENABLED=1 FEX_VECTORTSOENABLED=0 FEX_MEMCPYSETTSOENABLED=0 FEX_HALFBARRIERTSOENABLED=1 FEX_MULTIBLOCK=1 FEX_DYNAMICL1CACHE=0 FEX_L1CACHEENTRIES=262144"
metric() { awk -F, -v k="$1" '$1==k{print $2}' "$2"; }
THP_FILE=/sys/kernel/mm/transparent_hugepage/enabled
THP_DEFAULT=$(sed -n 's/.*\[\(.*\)\].*/\1/p' "$THP_FILE" 2>/dev/null || echo madvise)
set_thp() { echo "$1" | sudo tee "$THP_FILE" >/dev/null 2>&1 || echo "  (could not set THP to $1)" >&2; }
for seq in $(seq 1 "$SEQS"); do
  grep -v '^\s*#' "$HERE/cache-configs.txt" | grep -v '^\s*$' | while IFS='|' read -r name runner dll envs; do
    name=$(echo $name); runner=$(echo $runner); dll=$(echo $dll)
    thp=$(echo "$envs" | sed -n 's/.*THP=\([a-z]*\).*/\1/p'); envs=$(echo "$envs" | sed 's/THP=[a-z]*//')
    set_thp "${thp:-$THP_DEFAULT}"
    cache="$OUT/cache-$name"
    rm -rf "$cache"; mkdir -p "$cache"
    pkill -x FEXServer 2>/dev/null || true
    for run in cold $(seq -f "warm%g" 1 "$WARM"); do
      [ "$runner" = native ] && [ "$run" != cold ] && [ "$run" != warm1 ] && continue
      echo "== $name seq $seq $run" >&2
      maps=""; [ "$run" = cold ] && [ "$seq" = 1 ] && maps="BENCH_MAPS=1"
      txt="$OUT/$name.$seq.$run.txt"; st="$OUT/$name.$seq.$run.stats"; rss="$OUT/$name.$seq.$run.rss"
      rm -f "$st" "$rss"
      if [ "$runner" = native ]; then
        d=$JIT_DLL; [ "$dll" = r2r ] && d=$R2R_DLL
        env $GAME_DOTNET $maps /usr/bin/time -f "%M" -o "$rss" dotnet "$d" "$FRAMES" frames < /dev/null > "$txt" 2> >(sed "s/^/  /" >&2)
      else
        # A self-contained build (its own runtime, e.g. composite ReadyToRun) ships an apphost: run that instead.
        guest="/opt/dotnet/dotnet /bench/$dll/Bench.dll"; [ -x "$ROOTFS/bench/$dll/Bench" ] && guest="/bench/$dll/Bench"
        rm -f /dev/shm/fex-*-stats
        "$FEX_STATS" -s -i 50 > "$st" & sp=$!
        env $GAME_DOTNET $GAME_FEX $envs $maps FEX_DISKCACHEPATH="$cache" FEX_ROOTFS="$ROOTFS" FEX_PROFILESTATS=1 FEX_SILENTLOG=1 \
          /usr/bin/time -f "%M" -o "$rss" "$FEX_BIN/FEX" $guest "$FRAMES" frames < /dev/null > "$txt" 2> >(sed "s/^/  /" >&2) \
          || echo "checksum,crashed-$?,0" >> "$txt"
        sleep 0.3; kill $sp 2>/dev/null || true; wait $sp 2>/dev/null || true
      fi
      sed "s/^/  /" "$txt" >&2
      # fex-stats summary: version,t_s,pid,threads,jit_ms,signal_ms,sigbus,smc,float_fallback,cache_miss,
      # cache_rlock_ms,cache_wlock_ms,jit_blocks,disk_hit,disk_miss,disk_lookup_ms,code_buffer_full (several pids: sum them)
      fx=",,,,"
      if [ -s "$st" ]; then
        sed "s/^/  stats: /" "$st" >&2
        fx=$(awk -F, '{j+=$5; b+=$13; h+=$14; m+=$15; c+=$17} END{printf "%.1f,%d,%d,%d,%d", j, b, h, m, c}' "$st")
      fi
      r=$( [ -s "$rss" ] && tail -1 "$rss" || echo "")
      echo "$name,$seq,$run,$(metric startup_ms "$txt"),$(metric first_frame_ms "$txt"),$(metric frame_p50_ms "$txt"),$(metric frame_p99_ms "$txt"),$(metric load_frame_ms "$txt"),$(metric load_frame_max_ms "$txt"),$(metric frame_max_ms "$txt"),$(metric total_ms "$txt"),$(metric checksum "$txt"),$fx,$r" >> "$CSV"
      du -sh "$cache" 2>/dev/null | sed "s/^/  cache size: /" >&2 || true
    done
  done
done
set_thp "$THP_DEFAULT"
python3 "$HERE/frames_summary.py" "$CSV" | tee "$OUT/frames-summary.md"
