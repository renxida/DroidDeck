#!/bin/sh
# Builds the Steam bridge (steam_bridge.c) for linux-arm64 and checks it is what it claims to be.
#   build.sh VALVE_LIBSTEAM_API_SO OUT_DIR
# Valve's library is linked against (so the bridge records DT_NEEDED libsteam_api.so) but never copied out:
# the device fetches its own pinned copy. Run on an arm64 host with gcc and binutils.
set -eu
valve=$1 out=$2
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp "$valve" "$tmp/libsteam_api.so"
gcc -O2 -ffreestanding -fPIC -shared -fvisibility=hidden -o "$out/libsteam_api64.so" "$here/steam_bridge.c" \
    -Wl,-soname,libsteam_api64.so -Wl,-rpath,'$ORIGIN' -Wl,--no-as-needed "$tmp/libsteam_api.so" -Wl,-z,defs \
    -nostdlib
# It defines exactly the two accessors and needs only Valve's library.
nm -D --defined-only "$out/libsteam_api64.so" | awk '{print $3}' | grep -v '^_' | sort > "$tmp/defined"
printf '%s\n' SteamAPI_ISteamClient_GetISteamGameSearch SteamAPI_ISteamClient_GetISteamMusicRemote > "$tmp/want"
diff -u "$tmp/want" "$tmp/defined"
readelf -d "$out/libsteam_api64.so" | grep NEEDED | grep -q '\[libsteam_api.so\]'
test "$(readelf -d "$out/libsteam_api64.so" | grep -c NEEDED)" = 1
readelf -d "$out/libsteam_api64.so" | grep -E 'RUNPATH|RPATH' | grep -q 'ORIGIN'
# Each accessor is one tail call to GetISteamGenericInterface: the arguments in x0-x3 reach it untouched.
for fn in SteamAPI_ISteamClient_GetISteamGameSearch SteamAPI_ISteamClient_GetISteamMusicRemote; do
    body=$(objdump -d --no-show-raw-insn "$out/libsteam_api64.so" | awk -v f="<$fn>:" '$2==f{on=1;next} on&&/^$/{exit} on{print}')
    echo "$fn:"; echo "$body"
    test "$(echo "$body" | grep -c .)" = 1
    echo "$body" | grep -qE '\bb\s+[0-9a-f]+ <SteamAPI_ISteamClient_GetISteamGenericInterface@plt>'
done
echo "steam bridge: $out/libsteam_api64.so"
