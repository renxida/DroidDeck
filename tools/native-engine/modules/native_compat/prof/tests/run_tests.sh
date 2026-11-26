#!/bin/sh
# Builds and runs the sampling profiler's host test (no engine needed), with a symbol map made from the test
# binary the way CI makes the engine's.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/prof_test.$$
c++ -std=c++17 -O1 -g -Wall -Wextra -fno-exceptions -fno-omit-frame-pointer -fsanitize=address,undefined -Wl,--build-id \
  "$here/prof_test.cpp" "$here/../prof.cpp" -o "$out"
{ echo "# buildid $(readelf -n "$out" | awk '/Build ID/{print $3}')"
  nm -C -n -S --defined-only "$out" | awk '$2 ~ /^[tTwW]$/ || $3 ~ /^[tTwW]$/'; } > "$out.syms"
"$out" "$out.syms"
"$out"
home="$out.home"
mkdir -p "$home/.cache/droiddeck/native/other" "$home/.cache/droiddeck/native/this"
echo "# buildid 00" > "$home/.cache/droiddeck/native/other/godot.syms"
cp "$out.syms" "$home/.cache/droiddeck/native/this/godot.syms"
HOME="$home" "$out" --cache
rm -rf "$out" "$out.syms" "$home"
