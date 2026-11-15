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
gcc -O2 -Wall -Wextra -Werror -fPIC -shared -fvisibility=hidden -o "$out/libsteam_api64.so" "$here/steam_bridge.c" \
    -Wl,-soname,libsteam_api64.so -Wl,-rpath,'$ORIGIN' -Wl,--no-as-needed "$tmp/libsteam_api.so" -Wl,--as-needed -ldl \
    -Wl,-z,defs
# It defines the 22 ISteamClient accessors Steamworks.NET calls at init, FindOrCreateUserInterface and the init
# itself (all in front of Valve's), and the flat functions Valve's library dropped. Nothing else.
nm -D --defined-only "$out/libsteam_api64.so" | awk '{print $3}' | grep -v '^_' | sort > "$tmp/defined"
nm -D --defined-only "$tmp/libsteam_api.so" | awk '{print $3}' | sort > "$tmp/valve"
{
    for i in User Friends Utils Matchmaking MatchmakingServers UserStats Apps Networking RemoteStorage Screenshots \
             HTTP UGC Music HTMLSurface Inventory Video ParentalSettings Input Parties RemotePlay GameSearch MusicRemote; do
        echo "SteamAPI_ISteamClient_GetISteam$i"
    done
    echo SteamInternal_FindOrCreateUserInterface
    echo SteamInternal_SteamAPI_Init
} | sort > "$tmp/intercepted"
comm -23 "$tmp/intercepted" "$tmp/defined" | sed 's/^/the bridge lacks /' | { ! grep .; }
sort -u "$here/steamworks-net-entrypoints.txt" > "$tmp/entry"
comm -23 "$tmp/entry" "$tmp/valve" | sort -u - "$tmp/intercepted" > "$tmp/allowed"
comm -23 "$tmp/defined" "$tmp/allowed" | sed 's/^/the bridge defines, unasked: /' | { ! grep .; }
# The gate for the next EntryPointNotFoundException: every P/Invoke name in the game's Steamworks.NET resolves
# in the bridge or in Valve's library.
sort -u "$tmp/defined" "$tmp/valve" > "$tmp/resolvable"
comm -23 "$tmp/entry" "$tmp/resolvable" | sed 's/^/unresolved Steamworks.NET entry point: /' | { ! grep .; }
echo "steam bridge: all $(wc -l < "$tmp/entry") Steamworks.NET entry points resolve ($(comm -12 "$tmp/entry" "$tmp/defined" | wc -l) in the bridge)"
# Each library-version accessor it serves is one Valve's library exports (the link above also fails otherwise).
grep -o 'SteamAPI_Steam[A-Za-z]*_v[0-9]*' "$here/steam_bridge.c" | sort -u > "$tmp/own"
comm -23 "$tmp/own" "$tmp/valve" | { ! grep .; }
# Valve's library first (DT_NEEDED), then only the C library and its loader.
readelf -d "$out/libsteam_api64.so" | grep NEEDED
readelf -d "$out/libsteam_api64.so" | grep NEEDED | sed -n 1p | grep -q '\[libsteam_api.so\]'
test -z "$(readelf -d "$out/libsteam_api64.so" | grep NEEDED | grep -vE '\[(libsteam_api\.so|libc\.so\.6|libdl\.so\.2|ld-linux-aarch64\.so\.1)\]')"
readelf -d "$out/libsteam_api64.so" | grep -E 'RUNPATH|RPATH' | grep -q 'ORIGIN'
objdump -T "$out/libsteam_api64.so" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1 | sed 's/^/newest glibc symbol version: /'
echo "steam bridge: $out/libsteam_api64.so"
