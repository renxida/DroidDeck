#!/usr/bin/env bash
# Builds the memory-ordering test for win-x64 (framework-dependent: it runs on the .NET runtime a game ships)
# into OUT. With --smoke SECONDS it also runs the test on this x86 machine, where no forbidden outcome may show.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
[ $# -ge 1 ] || { echo "usage: build.sh OUT [--smoke SECONDS]" >&2; exit 2; }
mkdir -p "$1"
out=$(cd "$1" && pwd)
smoke=
if [ "${2:-}" = --smoke ]; then smoke=${3:-20}; fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
mkdir "$work/src"
cp "$here"/*.cs "$here"/*.csproj "$work/src/"
dotnet publish "$work/src/TsoLitmus.csproj" -c Release -r win-x64 --self-contained false -p:UseAppHost=true \
  ${TSO_LITMUS_FRAMEWORK:+-p:LitmusFramework=$TSO_LITMUS_FRAMEWORK} -o "$work/win" --nologo
install -m 644 "$work/win/tso-litmus.exe" "$out/tso-litmus.exe"
install -m 644 "$work/win/tso-litmus.dll" "$out/tso-litmus.dll"
ls -l "$out/tso-litmus.exe" "$out/tso-litmus.dll"

[ -n "$smoke" ] || exit 0
# The struct test relies on the JIT copying six references with the runtime's bulk GC copy (SSE stores).
DOTNET_JitDisasm=Produce DOTNET_JitStdOutFile="$work/disasm.txt" \
  dotnet "$work/win/tso-litmus.dll" --seconds "$smoke" --config ci-smoke --out "$work/smoke.jsonl"
if grep -q CORINFO_HELP_BULK_WRITEBARRIER "$work/disasm.txt" 2>/dev/null; then
  echo "tso-litmus: the struct copy uses CORINFO_HELP_BULK_WRITEBARRIER"
else
  echo "::warning::tso-litmus: no CORINFO_HELP_BULK_WRITEBARRIER in the JIT output for Produce; refs-struct-copy may not exercise the runtime's SSE reference copy"
fi
python3 "$repo/tools/linuxfs/overlay/usr/local/bin/droiddeck-tso-litmus" --check "$work/smoke.jsonl"
