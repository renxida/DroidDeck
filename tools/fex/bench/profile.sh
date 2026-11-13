#!/usr/bin/env bash
# Where FEX's own time goes for one config: perf profile of the bench under FEX (after run.sh, so the disk cache is
# warm), grouped by FEX function. Prints the top functions in FEX itself and the share in translated code ("JIT"),
# so the compile path splits into translating, loading cached code and slow block lookups.
# Inputs: as run.sh, plus CONFIG (a name in configs.txt, default fex_game_dc).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/bench-out}
REPS=${REPS:-5}
CONFIG=${CONFIG:-fex_game_dc}
GAME_DOTNET="DOTNET_TieredPGO=0 DOTNET_EnableWriteXorExecute=0 DOTNET_gcConcurrent=0 DOTNET_GCConserveMemory=5 DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1"
envs=$(grep -v '^\s*#' "$HERE/configs.txt" | awk -F'|' -v c="$CONFIG" '{n=$1; gsub(/ /,"",n)} n==c {print $3}')
command -v perf >/dev/null || { echo "perf not available; no profile" >&2; exit 0; }
echo "== profile $CONFIG" >&2
env $GAME_DOTNET $envs FEX_ROOTFS="$ROOTFS" FEX_SILENTLOG=1 \
  perf record -q -F 2999 -g -o "$OUT/perf.data" -- "$FEX_BIN/FEX" /opt/dotnet/dotnet /bench/Bench.dll "$REPS" > "$OUT/profile.txt" 2>&1
# Self time by DSO, then by symbol in FEX itself, then inclusive time of the compile-path entry points.
perf report -i "$OUT/perf.data" --no-children --sort dso --stdio 2>/dev/null | grep -v '^#' | grep -v '^$' | head -15 | sed 's/^/  dso: /' >&2
perf report -i "$OUT/perf.data" --no-children --sort symbol --dsos FEX --stdio 2>/dev/null | grep -v '^#' | grep -v '^$' | grep '%' | head -40 | sed 's/^/  self: /' >&2
perf report -i "$OUT/perf.data" --children --sort symbol --dsos FEX --stdio -g none 2>/dev/null | grep -v '^#' | grep '%' \
  | grep -E 'CompileBlock|CompileCode|FindBlock|LoadCachedCode|DiskCache|EmitJITCode|Optimize|RegisterAllocation|Decode|AddBlockMapping|Invalidate|HandleSignal|ExitFunctionLink|LinkBlock|Dispatcher' \
  | head -30 | sed 's/^/  incl: /' >&2
