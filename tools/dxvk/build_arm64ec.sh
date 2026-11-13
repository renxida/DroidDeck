#!/usr/bin/env bash
# CI component build for the dxvk-arm64ec experiment payload. Same pinned DXVK source and patches as
# tools/dxvk/build.sh, but the 64-bit DLLs are built as ARM64EC with llvm-mingw, so a 64-bit game's
# Direct3D calls run as native ARM code instead of through the x86-64 translator. ARM64EC images keep
# the x86-64 machine field, so Proton copies and loads them exactly like the x86-64 payload.
# 32-bit games have no ARM64EC path under WOW64; x32 is built from the same source with mingw gcc.
# Needs: i686-w64-mingw32-gcc (mingw-w64) and arm64ec-w64-mingw32-clang (llvm-mingw) on PATH.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
. "$root/tools/dxvk/source.env"
mkdir -p "${1:?output directory required}"
out=$(cd "$1" && pwd)
shopt -s nullglob dotglob
existing=("$out"/*)
if ((${#existing[@]})); then
  printf '%s\n' 'output directory must be empty; use a fresh build directory' >&2
  exit 1
fi
shopt -u nullglob dotglob
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git init -q "$work/source"
git -C "$work/source" fetch --depth=1 https://github.com/doitsujin/dxvk.git "$DXVK_REVISION"
git -C "$work/source" checkout -q --detach FETCH_HEAD
test "$(git -C "$work/source" rev-parse HEAD)" = "$DXVK_REVISION"
git -C "$work/source" submodule update --init --recursive --depth=1
for patch in "$root"/tools/dxvk/patches/*.patch; do
  git -C "$work/source" apply --check "$patch"
  git -C "$work/source" apply "$patch"
done
python3 "$root/tools/dxvk/check_present_sync.py" "$work/source/src/dxvk/dxvk_presenter.cpp"
python3 "$root/tools/dxvk/check_hud_storage_format.py" "$work/source/src/dxvk/hud/shaders/hud_frame_time_eval.comp"
python3 "$root/tools/dxvk/check_d3d9_single_sample.py" "$work/source"
cat > "$work/build-arm64ec.txt" <<'EOF'
[binaries]
c = 'arm64ec-w64-mingw32-clang'
cpp = 'arm64ec-w64-mingw32-clang++'
ar = 'arm64ec-w64-mingw32-ar'
strip = 'llvm-strip'
windres = 'arm64ec-w64-mingw32-windres'

[built-in options]
c_link_args = ['-static', '-static-libgcc']
cpp_link_args = ['-static', '-static-libgcc', '-static-libstdc++']

[properties]
needs_exe_wrapper = true

[host_machine]
system = 'windows'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
build() {
  meson setup "$work/build-$1" "$work/source" --cross-file "$2" \
    --buildtype release --prefix "$out" --bindir "$1" --libdir "$1" -Db_ndebug=if-release --strip
  meson compile -C "$work/build-$1" -j "$(nproc)"
  meson install -C "$work/build-$1"
}
build x32 "$work/source/build-win32.txt"
build x64 "$work/build-arm64ec.txt"
rm -f "$out"/x32/*.a "$out"/x64/*.a
python3 - "$out" <<'EOF'
import struct, sys
from pathlib import Path
# x32: i386 PE32. x64: x86-64 PE32+ whose whole code map is ARM64EC (CHPE metadata present).
for arch, machine, magic in (("x32", 0x14c, 0x10b), ("x64", 0x8664, 0x20b)):
    for name in ("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi"):
        data = (Path(sys.argv[1]) / arch / (name + ".dll")).read_bytes()
        pe = struct.unpack_from("<I", data, 60)[0]
        assert data[pe:pe + 4] == b"PE\0\0", name
        assert struct.unpack_from("<H18xH", data, pe + 4) == (machine, magic), (arch, name)
        if arch == "x64":
            assert b"aarch64" in data, "x64/%s.dll is not the ARM64EC build" % name
EOF
cp "$work/source/LICENSE" "$out/LICENSE"
printf '%s\n' "$DXVK_REVISION" > "$out/source-commit"
git -C "$work/source" submodule status --recursive > "$out/submodules.txt"
git -C "$work/source" diff > "$out/downstream.patch"
tar --exclude-vcs --zstd -cf "$out/source.tar.zst" -C "$work/source" .
(cd "$out" && find x32 x64 -name '*.dll' -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
