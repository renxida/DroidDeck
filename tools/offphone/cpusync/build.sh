#!/usr/bin/env bash
# Builds the rig's sync-file layer for arm64 glibc (run on an arm64 Linux host with libvulkan-dev).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/out}
mkdir -p "$out"
cc=${CC:-gcc}
"$cc" -O2 -g -Wall -Wextra -Wno-unused-parameter -fPIC -shared -fvisibility=hidden \
  -o "$out/libVkLayer_offphone_cpu_sync.so" "$here/layer.c" -lpthread
cp "$here/VkLayer_offphone_cpu_sync.json" "$out/"
echo "$out/libVkLayer_offphone_cpu_sync.so"
