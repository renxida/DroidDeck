#!/usr/bin/env bash
# Runs gc-bench under the GC settings the game-env experiments set, with the same EventPipe tracing that
# the dotnet-trace experiment turns on, and checks droiddeck-dotnet-trace against the runtime's own counts.
# Usage: run.sh OUT_DIR [SECONDS]   (BENCH_FRAMEWORK=net8.0 to build for an older SDK)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(mkdir -p "$1" && cd "$1" && pwd)
seconds=${2:-20}
trace="$here/../linuxfs/overlay/usr/local/bin/droiddeck-dotnet-trace"
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
dotnet build "$here/GcBench.csproj" -c Release -p:BenchFramework="${BENCH_FRAMEWORK:-net9.0}" \
  -o "$out/bin" --nologo -v q
declare -A configs=(
  [default]=""
  [shipped]="DOTNET_gcConcurrent=0 DOTNET_GCConserveMemory=5 DOTNET_TieredPGO=0"
  [dotnet-gen0]="DOTNET_gcConcurrent=0 DOTNET_GCConserveMemory=5 DOTNET_TieredPGO=0 DOTNET_GCgen0size=4000000"
  [dotnet-gc-stock]="DOTNET_gcConcurrent=1 DOTNET_GCConserveMemory=0 DOTNET_TieredPGO=0"
)
printf '| config | fps | p95 ms | p99 ms | max ms | GCs | gen2 | paused ms | heap MB |\n|---|---|---|---|---|---|---|---|---|\n' > "$out/summary.md"
for name in default shipped dotnet-gen0 dotnet-gc-stock; do
  dir="$out/$name"; rm -rf "$dir"; mkdir -p "$dir"
  env ${configs[$name]} DOTNET_EnableEventPipe=1 DOTNET_EventPipeOutputStreaming=1 DOTNET_EventPipeRundown=0 \
    DOTNET_EventPipeOutputPath="$dir/dotnet-{pid}.nettrace" \
    DOTNET_EventPipeConfig='Microsoft-Windows-DotNETRuntime:0x400000001:4,System.Runtime:0:4:EventCounterIntervalSec=1' \
    dotnet "$out/bin/gc-bench.dll" --seconds "$seconds" > "$dir/result.json"
  python3 "$trace" "$dir"/dotnet-*.nettrace "$dir" 2> "$dir/parse.txt"
  python3 - "$name" "$dir" >> "$out/summary.md" <<'PY'
import json, sys, pathlib
name, d = sys.argv[1], pathlib.Path(sys.argv[2])
r = json.loads((d / "result.json").read_text())
traced = len((d / "dotnet-gc.txt").read_text().splitlines()) - 1
# Worker threads can still allocate between the bench's last count and process exit.
if not r["gc_total"] <= traced <= r["gc_total"] + 1:
    sys.exit(f"{name}: trace has {traced} GCs, runtime counted {r['gc_total']}")
print(f"| {name} | {r['fps']} | {r['p95_ms']} | {r['p99_ms']} | {r['max_ms']} | {r['gc_total']} | {r['gen2']} | {r['pause_ms']} | {r['heap_mb']} |")
PY
done
cat "$out/summary.md"
