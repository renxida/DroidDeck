#!/usr/bin/env bash
# Run the synthetic scene (or another project that saves BATCHTEST frames) under every arm on lavapipe, in
# parallel, and compare them.
#
#   tools/native-engine/devloop/run.sh BINARY [PROJECT_DIR] [-- compare.py arguments]
#
# Arms: stock-a and stock-b (twice, to show the renderer is deterministic here), reorder, poly, both, slots
# (texture slots alone), quad (rects batch with nine-patches) and all (every one of them). Logs and
# frames go to $DEVLOOP_OUT (default /tmp/devloop-out). With no compare arguments it uses the CI gate's rules.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
BIN=$(realpath "$1"); shift
PROJ=$REPO/tools/native-engine/batchtest
if [ $# -gt 0 ] && [ "$1" != "--" ]; then PROJ=$(realpath "$1"); shift; fi
[ "${1:-}" = "--" ] && shift
OUT=${DEVLOOP_OUT:-/tmp/devloop-out}
mkdir -p "$OUT"
rm -f "$OUT"/*.raw "$OUT"/*.log
LVP=$(ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -1)
# The phone's Vulkan call counter, built for this machine, so each arm reports draws, set binds, passes and
# barriers per steady frame: the counts the phone's CPU cost follows.
L=$OUT/layer
VKH=${DEVLOOP_VULKAN_INCLUDE:-${DEVLOOP_WORK:-$HOME/.cache/droiddeck-devloop}/godot/thirdparty/vulkan/include}
if [ ! -f "$L/libVkLayer_droiddeck_vkcount.so" ] || [ "$REPO/tools/linuxfs/vkcount-layer/vkcount_layer.c" -nt "$L/libVkLayer_droiddeck_vkcount.so" ]; then
  mkdir -p "$L"
  cc -shared -fPIC -O2 -fvisibility=hidden -pthread -I "$VKH" -o "$L/libVkLayer_droiddeck_vkcount.so" \
    "$REPO/tools/linuxfs/vkcount-layer/vkcount_layer.c"
  cp "$REPO/tools/linuxfs/vkcount-layer/VkLayer_droiddeck_vkcount.json" "$L/"
fi
run() { # name, then env assignments
  local n=$1; shift
  env "$@" VK_ICD_FILENAMES="$LVP" VK_ADD_LAYER_PATH="$L" VK_INSTANCE_LAYERS=VK_LAYER_DROIDDECK_vkcount \
    DROIDDECK_VKCOUNT_SECONDS=1 BATCHTEST_SECONDS=${DEVLOOP_SECONDS:-2.5} \
    BATCHTEST_OUT="$OUT" BATCHTEST_NAME="$n" DROIDDECK_NATIVE_RENDER_CENSUS=0.05 \
    timeout 120 xvfb-run -a -s "-screen 0 ${DEVLOOP_SCREEN:-640x480}x24" "$BIN" --path "$PROJ" --rendering-method mobile \
    --rendering-driver vulkan --verbose > "$OUT/$n.log" 2>&1 || { echo "$n exit $?"; tail -20 "$OUT/$n.log"; return 1; }
}
run stock-a & run stock-b & run reorder DROIDDECK_NATIVE_CANVAS_REORDER=16 &
run poly DROIDDECK_NATIVE_CANVAS_POLY_BATCH=256 & run both DROIDDECK_NATIVE_CANVAS_REORDER=16 DROIDDECK_NATIVE_CANVAS_POLY_BATCH=256 &
run slots DROIDDECK_NATIVE_CANVAS_TEXTURE_SLOTS=7 &
run quad DROIDDECK_NATIVE_CANVAS_QUAD_MERGE=1 &
run all DROIDDECK_NATIVE_CANVAS_REORDER=16 DROIDDECK_NATIVE_CANVAS_POLY_BATCH=256 DROIDDECK_NATIVE_CANVAS_TEXTURE_SLOTS=7 \
  DROIDDECK_NATIVE_CANVAS_QUAD_MERGE=1 &
fail=0; for j in 1 2 3 4 5 6 7 8; do wait -n || fail=1; done
[ $fail = 0 ] || exit 1
for n in stock-a reorder poly both slots quad all; do grep -hE "native_compat: census" "$OUT/$n.log" | tail -1 | cut -c1-400 | sed "s/^/$n: /"; done
if [ $# -eq 0 ]; then set -- reorder=exact poly=max=3 both=max=3 slots=exact quad=exact all=max=3 --fewer "reorder<stock-a" --fewer "poly<=stock-a" \
    --fewer "both<reorder" --fewer "slots<stock-a" --fewer "quad<=stock-a" --fewer "all<both"; fi
python3 "$REPO/tools/native-engine/batchtest/compare.py" "$OUT" "$@"
