#!/bin/bash
# Builds droiddeck-ctex-astc. Usage: build.sh OUT [CXX] [extra flags...]
# CI: build.sh "$D/usr/local/bin/droiddeck-ctex-astc" aarch64-linux-gnu-g++ -DASTCENC_NEON=1
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$1
cxx=${2:-g++}
shift $(( $# >= 2 ? 2 : 1 ))
isa=("$@")
if [ ${#isa[@]} -eq 0 ]; then
  case "$($cxx -dumpmachine)" in
    aarch64*) isa=(-DASTCENC_NEON=1 -DASTCENC_SSE=0 -DASTCENC_AVX=0 -DASTCENC_POPCNT=0 -DASTCENC_F16C=0) ;;
    x86_64*) isa=(-DASTCENC_NEON=0 -DASTCENC_SSE=41 -DASTCENC_AVX=0 -DASTCENC_POPCNT=1 -DASTCENC_F16C=0 -msse4.1 -mpopcnt) ;;
    *) isa=(-DASTCENC_NEON=0 -DASTCENC_SSE=0 -DASTCENC_AVX=0 -DASTCENC_POPCNT=0 -DASTCENC_F16C=0) ;;
  esac
fi
mkdir -p "$(dirname "$out")"
"$cxx" -std=c++17 -O2 -pthread -Wall -Wno-unused-function \
  "${isa[@]}" -I"$here/third_party/astcenc" -I"$here/third_party/bcdec" -I"$here/third_party/stb" \
  -o "$out" "$here/ctex_astc.cpp" "$here"/third_party/astcenc/astcenc_*.cpp \
  -static-libstdc++ -static-libgcc
