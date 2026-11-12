#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=${1:?usage: build.sh <out-dir>}
mkdir -p "$out"
out=$(cd "$out" && pwd)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

fetch() {
    bash "$here/fetch-verified.sh" "$@"
}

pack() {
    local name=$1 src=$2
    shift 2
    for required in "$@"; do
        [ -f "$src/$required" ] || { echo "$name pack: $src/$required is missing" >&2; exit 1; }
    done
    python3 -I - "$src" "$work/$name.tar" <<'EOF'
import os, sys, tarfile
src, dest = sys.argv[1], sys.argv[2]
files = []
for base, dirs, names in os.walk(src):
    dirs.sort()
    for name in names:
        path = os.path.join(base, name)
        if os.path.islink(path) or not os.path.isfile(path):
            sys.exit(f"{path}: only regular files can be packed")
        files.append(os.path.relpath(path, src))
with tarfile.open(dest, "w", format=tarfile.USTAR_FORMAT) as tar:
    for rel in sorted(files):
        path = os.path.join(src, rel)
        info = tar.gettarinfo(path, arcname=rel)
        info.mtime, info.uid, info.gid, info.uname, info.gname = 0, 0, 0, "", ""
        info.mode = 0o755 if os.stat(path).st_mode & 0o111 else 0o644
        with open(path, "rb") as f:
            tar.addfile(info, f)
EOF
    zstd -q -19 -f "$work/$name.tar" -o "$out/$name.tar.zst"
    echo "$name $(sha256 "$out/$name.tar.zst") $(wc -c < "$out/$name.tar.zst" | tr -d ' ')"
}

mkdir "$work/proot"
install -m755 "$repo/app/src/main/jniLibs/arm64-v8a/libproot.so" "$repo/app/src/main/jniLibs/arm64-v8a/libproot-loader.so" "$work/proot/"
pack proot "$work/proot" libproot.so libproot-loader.so
pack directaudio "$repo/app/src/main/assets/directaudio" \
    linux-wine11/version.txt linux-wine11-systhread/version.txt
pack powervr "$repo/app/src/main/assets/hybris" runtime.tzst version.txt

