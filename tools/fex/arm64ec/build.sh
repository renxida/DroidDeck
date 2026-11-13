#!/usr/bin/env bash
# Build the FEX that runs *inside* games: Wine's ARM64EC emulator DLL (libarm64ecfex.dll), its WOW64
# sibling (libwow64fex.dll) and their Linux unixlibs, from upstream FEX at a pinned commit plus Kestrel's
# patches, and package them as a Kestrel component bundle (.wcp, type FEXCore-Linux).
#
#   tools/fex/arm64ec/build.sh dlls      # needs llvm-mingw (arm64ec-w64-mingw32-clang) on PATH
#   tools/fex/arm64ec/build.sh unixlib   # needs an aarch64 Linux C++ compiler; run in an old glibc
#   tools/fex/arm64ec/build.sh package
#   tools/fex/arm64ec/build.sh all
#
# Inputs (env): FEX_REF (upstream commit, default from tools/fex/arm64ec/FEX_REF), WORK (default ./fex-ec).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
FEX_REF=${FEX_REF:-$(cat "$HERE/FEX_REF")}
WORK=${WORK:-$REPO/fex-ec}
SRC=$WORK/src
OUT=$WORK/install
JOBS=${JOBS:-$(nproc)}

fetch() {
  local stamp
  stamp="$FEX_REF $(cat "$HERE"/patches/*.patch | sha256sum | cut -c1-16)"
  if [ -d "$SRC/.git" ] && [ "$(cat "$WORK/applied" 2>/dev/null)" = "$stamp" ]; then
    return
  fi
  rm -rf "$SRC" && mkdir -p "$SRC" "$WORK"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin https://github.com/FEX-Emu/FEX.git
  git -C "$SRC" fetch -q --depth 1 origin "$FEX_REF"
  git -C "$SRC" checkout -q FETCH_HEAD
  git -C "$SRC" submodule update -q --init --depth 1
  for p in "$HERE"/patches/*.patch; do
    git -C "$SRC" -c user.name=Kestrel -c user.email=kestrel@invalid am -q --3way "$p"
    echo "applied $(basename "$p")"
  done
  echo "$stamp" > "$WORK/applied"
}

dll() { # $1 = arm64ec | wow64
  local triple=arm64ec
  [ "$1" = wow64 ] && triple=aarch64
  cmake -S "$SRC" -B "$WORK/build_$1" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=Data/CMake/toolchain_mingw.cmake -DMINGW_TRIPLE=${triple}-w64-mingw32 \
    -DCMAKE_INSTALL_LIBDIR=/usr/lib/wine/aarch64-windows -DCMAKE_INSTALL_PREFIX=/usr \
    -DENABLE_LTO=False -DENABLE_ASSERTIONS=False -DENABLE_JEMALLOC_GLIBC_ALLOC=False \
    -DBUILD_TESTING=False -DTUNE_ARCH=generic -DTUNE_CPU=none -DRANGES_NATIVE=OFF
  ninja -C "$WORK/build_$1" -j "$JOBS"
  DESTDIR="$OUT" ninja -C "$WORK/build_$1" install >/dev/null
}

unixlib() {
  cmake -S "$SRC/Source/Windows/UnixLib" -B "$WORK/build_unixlib" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_LIBDIR=/usr/lib/wine/aarch64-unix -DCMAKE_INSTALL_PREFIX=/usr
  ninja -C "$WORK/build_unixlib"
  DESTDIR="$OUT" ninja -C "$WORK/build_unixlib" install >/dev/null
  # Report the newest glibc symbol each unixlib needs; the game's Wine loads them in the guest's runtime.
  for f in "$OUT"/usr/lib/wine/aarch64-unix/*.so; do
    printf '%s needs %s\n' "$(basename "$f")" \
      "$(objdump -T "$f" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1)"
  done
}

package() {
  local upstream patches version files=() f
  upstream=$(cut -c1-9 < "$HERE/FEX_REF")
  patches=$(cat "$HERE"/patches/*.patch | sha256sum | cut -c1-8)
  version=${FEX_VERSION_NAME:-kestrel-$upstream-$patches}
  local stage=$WORK/wcp
  rm -rf "$stage" && mkdir -p "$stage/files/lib/wine/aarch64-windows" "$stage/files/lib/wine/aarch64-unix"
  for f in aarch64-windows/libarm64ecfex.dll aarch64-windows/libwow64fex.dll \
           aarch64-unix/libarm64ecfex.so aarch64-unix/libwow64fex.so; do
    test -f "$OUT/usr/lib/wine/$f" || { echo "missing $f" >&2; exit 1; }
    cp "$OUT/usr/lib/wine/$f" "$stage/files/lib/wine/$f"
    files+=("./files/lib/wine/$f")
  done
  python3 - "$stage/profile.json" "$version" "$FEX_REF" "$HERE/patches" <<'PY'
import json, os, sys
path, version, ref, pdir = sys.argv[1:]
patches = sorted(p for p in os.listdir(pdir) if p.endswith('.patch'))
json.dump({
    "type": "FEXCore-Linux",
    "versionName": version,
    "versionCode": 0,
    "description": "Kestrel FEX: upstream FEX-Emu/FEX " + ref[:12] + " + " + ", ".join(patches),
    "files": [],
}, open(path, "w"), indent=2)
PY
  local name="Kestrel-FEXCore-$version.wcp"
  (cd "$stage" && tar --owner=0 --group=0 --numeric-owner -cf - ./profile.json "${files[@]}") | zstd -q -19 -T0 -o "$WORK/$name" -f
  (cd "$WORK" && sha256sum "$name" | tee "$name.sha256")
  echo "$name" > "$WORK/wcp-name"
}

case "${1:-all}" in
  fetch) fetch ;;
  dlls) fetch; dll arm64ec; dll wow64 ;;
  unixlib) fetch; unixlib ;;
  package) package ;;
  all) fetch; dll arm64ec; dll wow64; unixlib; package ;;
  *) echo "usage: $0 fetch|dlls|unixlib|package|all" >&2; exit 2 ;;
esac
