#!/usr/bin/env bash
# Where FEX's own time goes for one config: perf profile of the bench under FEX (after run.sh, so the disk cache is
# warm), grouped by FEX function. Prints the top functions in FEX itself and the share in translated code ("JIT"),
# so the compile path splits into translating, loading cached code and slow block lookups.
# Inputs: as run.sh, plus CONFIG (a name in configs.txt, default fex_game_dc) and EXTRA (more K=V env, applied last).
# With EXTRA=FEX_LIBRARYJITNAMING=1, translated code is named by the guest library it came from (libcoreclr.so,
# libclrjit.so, libc...); blocks with no file (jitted C#) stay unnamed in the JIT map.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/bench-out}
REPS=${REPS:-5}
CONFIG=${CONFIG:-fex_game_dc}
EXTRA=${EXTRA:-}
GAME_DOTNET="DOTNET_TieredPGO=0 DOTNET_EnableWriteXorExecute=0 DOTNET_gcConcurrent=0 DOTNET_GCConserveMemory=5 DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1"
envs=$(grep -v '^\s*#' "$HERE/configs.txt" | awk -F'|' -v c="$CONFIG" '{n=$1; gsub(/ /,"",n)} n==c {print $3}')
command -v perf >/dev/null || { echo "perf not available; no profile" >&2; exit 0; }
echo "== profile $CONFIG $EXTRA" >&2
env $GAME_DOTNET $envs $EXTRA FEX_ROOTFS="$ROOTFS" FEX_SILENTLOG=1 \
  perf record -q -F 2999 -g -o "$OUT/perf.data" -- "$FEX_BIN/FEX" /opt/dotnet/dotnet /bench/Bench.dll "$REPS" > "$OUT/profile.txt" 2>&1
# Self time by DSO, then by symbol in FEX itself, then inclusive time of the compile-path entry points.
perf report -i "$OUT/perf.data" --no-children --sort dso --stdio -g none 2>/dev/null | grep -v '^#' | grep -v '^$' | head -15 | sed 's/^/  dso: /' >&2
perf report -i "$OUT/perf.data" --no-children --sort symbol --dsos FEX --stdio -g none 2>/dev/null | grep -v '^#' | grep -v '^$' | grep '%' | head -40 | sed 's/^/  self: /' >&2
perf report -i "$OUT/perf.data" --children --sort symbol --dsos FEX --stdio -g none 2>/dev/null | grep -v '^#' | grep '%' \
  | grep -E 'CompileBlock|CompileCode|FindBlock|LoadCachedCode|DiskCache|EmitJITCode|Optimize|RegisterAllocation|Decode|AddBlockMapping|Invalidate|HandleSignal|ExitFunctionLink|LinkBlock|Dispatcher' \
  | head -30 | sed 's/^/  incl: /' >&2
if [ -n "$EXTRA" ]; then
  # Self time in translated code, grouped: FEX's dispatcher, each guest library, and anonymous (jitted C#) blocks.
  perf report -i "$OUT/perf.data" --no-children --sort dso,symbol --stdio -g none 2>/dev/null | grep -v '^#' | grep '%' \
    | python3 -c '
import collections, re, sys
groups, top = collections.Counter(), []
for line in sys.stdin:
    m = re.match(r"\s*([\d.]+)%\s+(.*?)\s+\[\.\]\s+(\S+)", line)
    if not m:
        continue
    pct, dso, sym = float(m.group(1)), m.group(2).strip(), m.group(3)
    if not dso.startswith("[JIT]"):
        continue
    if sym.startswith("Dispatch_"):
        key = "FEX dispatcher"
    elif "/" in sym:
        key = sym.split("/")[-1].split("+")[0]
    elif sym.startswith("JIT_0x"):
        key = "anonymous guest code (jitted C#)"
    else:
        key = "unnamed " + sym[:-3] + "xxx"
    groups[key] += pct
    top.append((pct, sym))
for key, pct in groups.most_common(25):
    print("  group: %6.2f%% %s" % (pct, key))
for pct, sym in sorted(top, reverse=True)[:25]:
    print("  hot: %6.2f%% %s" % (pct, sym[-90:]))
' >&2
  case "$EXTRA" in *BLOCKJITNAMING=1*)
    perf report -i "$OUT/perf.data" --no-children --sort symbol --stdio -g none 2>/dev/null | grep '%' \
      | python3 "$HERE/hotblocks.py" 8 >&2 ;;
  esac
fi
exit 0
