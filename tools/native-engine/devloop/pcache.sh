#!/bin/bash
# Pipeline-cache save check on lavapipe: launches the batch test twice per arm, each stopped with SIGKILL the way
# droiddeck-native-godot stops the engine, and shows what the second launch found on disk.
#   DEVLOOP_WORK=... bash tools/native-engine/devloop/pcache.sh BINARY [ENGINE ARGS]
# ENGINE ARGS default to --path at the batch test project (a template binary run on its sources); an exported
# BatchTest binary, which finds its own pack, takes none (pass "").
# Arms: stock (only the census switch) and quiet (DROIDDECK_NATIVE_PIPELINE_CACHE_QUIET_MS=1000). Lavapipe's own
# cache never grows, so the pcachesim layer stands in for a driver whose cache does. Passes when the quiet arm's
# second launch starts with a cache and the stock arm's does not.
set -euo pipefail
BIN=$1
shift
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
W=${DEVLOOP_WORK:-$HOME/.cache/droiddeck-devloop}
OUT=${DEVLOOP_OUT:-$W/pcache}
VKH=${DEVLOOP_VULKAN_INCLUDE:-$W/godot/thirdparty/vulkan/include}
LVP=$(ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -1)
L=$W/layers
D=$REPO/tools/native-engine/devloop
mkdir -p "$L"
if [ ! -f "$L/libVkLayer_droiddeck_pcachesim.so" ] || [ "$D/pcachesim_layer.c" -nt "$L/libVkLayer_droiddeck_pcachesim.so" ]; then
  cc -shared -fPIC -O2 -fvisibility=hidden -pthread -I "$VKH" -o "$L/libVkLayer_droiddeck_pcachesim.so" "$D/pcachesim_layer.c"
  cp "$D/VkLayer_droiddeck_pcachesim.json" "$L/"
fi
if [ $# -eq 0 ]; then set -- --path "$REPO/tools/native-engine/batchtest"; fi
ARGS=(); for a in "$@"; do [ -z "$a" ] || ARGS+=("$a"); done
rm -rf "$OUT"; mkdir -p "$OUT"
launch() { # arm, n, env...
  local arm=$1 n=$2; shift 2
  env "$@" XDG_DATA_HOME="$OUT/$arm-data" DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 VK_ICD_FILENAMES="$LVP" VK_ADD_LAYER_PATH="$L" \
    VK_INSTANCE_LAYERS=VK_LAYER_DROIDDECK_pcachesim DROIDDECK_NATIVE_PIPELINE_CENSUS=1 \
    BATCHTEST_SECONDS=60 BATCHTEST_FIGHT=1 BATCHTEST_OUT="$OUT" BATCHTEST_NAME="$arm$n" \
    xvfb-run -a -s "-screen 0 640x480x24" timeout -s KILL "${DEVLOOP_PCACHE_SECONDS:-6}" stdbuf -oL -eL "$BIN" \
    ${ARGS[@]+"${ARGS[@]}"} --rendering-method mobile --rendering-driver vulkan > "$OUT/$arm$n.log" 2>&1 || true
}
arm() { local a=$1; shift; launch "$a" 1 "$@"; launch "$a" 2 "$@"; }
arm stock & arm quiet DROIDDECK_NATIVE_PIPELINE_CACHE_QUIET_MS=1000 & wait
fail=0
for a in stock quiet; do
  for n in 1 2; do grep -hE "pipeline c|pcachesim: cache created" "$OUT/$a$n.log" | sed "s/^/$a$n: /"; done
  got=$(grep -hoE "pipeline cache at startup: [0-9]+ KiB" "$OUT/${a}2.log" | grep -oE "[0-9]+" || echo 0)
  if [ "$a" = quiet ] && [ "${got:-0}" -le 0 ]; then echo "FAIL: quiet arm's second launch found no saved cache"; fail=1; fi
  if [ "$a" = stock ] && [ "${got:-0}" -gt 0 ]; then echo "FAIL: stock arm saved a cache under 3 MB before being killed?"; fail=1; fi
done
exit $fail
