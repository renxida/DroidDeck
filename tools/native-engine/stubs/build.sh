#!/bin/sh
# Builds the stubs for linux-arm64 into $1 (default: out/).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-out}
mkdir -p "$out"
cc=${CC:-cc}
$cc -O2 -shared -fPIC -o "$out/libsteam_api64.so" "$here/steam_stub.c" "$here/steam_stub_auto.c" -Wl,-soname,libsteam_api64.so
cp "$out/libsteam_api64.so" "$out/libsteam_api.so"
nm -D --defined-only "$out/libsteam_api64.so" | grep -c " T SteamAPI_" | sed 's/^/stub exports: /'
