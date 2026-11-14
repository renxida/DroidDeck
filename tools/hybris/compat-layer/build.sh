#!/usr/bin/env bash
# Build only libhybris's PowerVR compat layer, as VK_LAYER_DROIDDECK_hybris_compat, from the pinned
# libhybris source with the runtime's own patches plus this directory's. It links only libc, so a
# plain cross compiler is enough; the full runtime build stays in tools/hybris/build_runtime.sh.
#   tools/hybris/compat-layer/build.sh OUT_DIR [CC]
# CC defaults to aarch64-linux-gnu-gcc; pass gcc to build a host copy for the lavapipe tests.
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)
out=${1:?output directory required}
cc=${2:-aarch64-linux-gnu-gcc}
. "$root/tools/hybris/source.env"
# Vulkan 1.4 headers: the runtime's dxvk-bc-profile patch hooks the maintenance6 commands.
VULKAN_HEADERS_REVISION=952f776f6573aafbb62ea717d871cd1d6816c387  # v1.4.309
mkdir -p "$out"
out=$(cd "$out" && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-hybris-compat.XXXXXX")
trap 'rm -rf "$work"' EXIT
fetch() {
  git init -q "$3"
  git -C "$3" fetch -q --depth=1 "$1" "$2"
  git -C "$3" checkout -q --detach FETCH_HEAD
  test "$(git -C "$3" rev-parse HEAD)" = "$2"
}
fetch https://github.com/taowen/libhybris.git "$HYBRIS_REVISION" "$work/libhybris"
fetch https://github.com/KhronosGroup/Vulkan-Headers.git "$VULKAN_HEADERS_REVISION" "$work/headers"
for patch in "$root"/tools/hybris/patches/*.patch "$root"/tools/hybris/compat-layer/*.patch; do
  git -C "$work/libhybris" apply "$patch"
done
src="$work/libhybris/hybris/vulkan"
# The layer's source list, as hybris/vulkan/Makefile.am gives it.
mapfile -t sources < <(sed -n '/^libVkLayer_hybris_compat_la_SOURCES/,/^libVkLayer_hybris_compat_la_CFLAGS/p' \
  "$src/Makefile.am" | tr ' \\' '\n\n' | grep '\.c$')
test "${#sources[@]}" -gt 30
library=libVkLayer_droiddeck_hybris_compat.so
(cd "$src" && "$cc" -shared -fPIC -O2 -g0 -fvisibility=hidden -pthread -Wl,-z,nodelete \
  -I"$work/headers/include" -I. -Ilayer -Icompat -I"$work/libhybris/hybris/include" \
  -o "$out/$library" "${sources[@]}")
install -m644 "$root/tools/hybris/compat-layer/VkLayer_droiddeck_hybris_compat.json" "$out/"
