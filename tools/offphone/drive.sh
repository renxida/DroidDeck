#!/usr/bin/env bash
# Drives the installed app on the CI Android (redroid) through one launch scenario and leaves
# the evidence in OUT: rig-session/ (the app's own session folder), rig-events.jsonl, verdict.json.
#
#   tools/offphone/drive.sh steam-ui|stand-in-game OUT [lavapipe|venus] [timeout-seconds] [stand-in-dir]
#
# steam-ui       Steam's client starts and reaches its sign-in screen. Nothing signs in.
# stand-in-game  The stand-in Godot game (our own code, no game files) runs through Proton and FEX
#                to its first frame and a clean main-loop exit.
# Exit status: 0 when the scenario's target stage was reached (see tools/offphone/verdict.py).
set -uo pipefail
scenario=${1:?scenario}; out=${2:?out dir}; gv=${3:-lavapipe}; limit=${4:-900}; standin=${5:-}
here=$(cd "$(dirname "$0")" && pwd); repo=$(cd "$here/../.." && pwd)
ctl="$repo/tools/droiddeckctl"
pkg=$("$repo/tools/app-id")
fs=/data/user/0/$pkg/files/linuxfs
mkdir -p "$out"; : > "$out/rig-events.jsonl"
now_ms() { date +%s%3N; }
rig_event() { printf '{"stage":"%s","t":%s}\n' "$1" "$(now_ms)" >> "$out/rig-events.jsonl"; echo "rig: $1"; }
phase() { "$ctl" state 2>/dev/null | jq -r '.session.phase // "?"'; }
latest_dir() { adb shell "ls -1d /data/user/0/$pkg/files/logs/*/ 2>/dev/null | tail -1" | tr -d '\r'; }

# Debugging tools (Setup > Diagnostics) and Android's phantom-process limit, as on a test phone.
"$here/enable-debug-tools.sh" "$pkg" >/dev/null
adb shell device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true
adb shell device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1 || true
adb shell settings put global settings_enable_monitor_phantom_procs false >/dev/null 2>&1 || true

wait_idle() { # seconds
  local end=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    case $(phase) in IDLE|FAILED) return 0;; esac; sleep 5
  done; return 1
}

# CI Android has no PowerVR, Mali or Adreno; the guest's Vulkan comes from Mesa's CPU driver
# (lavapipe) or, on request, Venus over the emulator's SwiftShader.
case $gv in
  venus) "$ctl" prefs set session venus true --type bool >/dev/null ;;
  lavapipe)
    if ! adb shell "ls $fs/usr/share/vulkan/icd.d" >/dev/null 2>&1; then
      echo "rig: first start installs the runtime"
      "$ctl" start steam >/dev/null 2>&1 || true
      sleep 10; wait_idle 600 || true
      "$ctl" stop >/dev/null 2>&1 || true
    fi
    uid=$(adb shell stat -c %u "$fs" | tr -d '\r')
    # Ubuntu's manifest names the library bare; the session wants a path.
    lib=$(adb shell "cd $fs && ls usr/lib/*/libvulkan_lvp.so" | tr -d '\r' | head -1)
    [ -n "$lib" ] || { echo "rig: lavapipe is not in the runtime"; exit 2; }
    adb shell "mkdir -p $fs/usr/local/share/offphone $fs/usr/local/lib/offphone $fs/usr/local/share/vulkan/implicit_layer.d"
    adb shell "printf '%s' '{\"file_format_version\":\"1.0.1\",\"ICD\":{\"library_path\":\"/$lib\",\"api_version\":\"1.4.0\"}}' > $fs/usr/local/share/offphone/lvp_icd.json"
    # gamescope needs sync-file semaphores; the rig's own layer adds them (never in the APK).
    adb push "${CPUSYNC_DIR:-cpusync}/libVkLayer_offphone_cpu_sync.so" "$fs/usr/local/lib/offphone/" >/dev/null
    adb push "${CPUSYNC_DIR:-cpusync}/VkLayer_offphone_cpu_sync.json" "$fs/usr/local/share/vulkan/implicit_layer.d/" >/dev/null
    adb shell "chown -R $uid:$uid $fs/usr/local/share/offphone $fs/usr/local/lib/offphone $fs/usr/local/share/vulkan && chmod 755 $fs/usr/local/lib/offphone/*.so && restorecon -R $fs/usr/local/share/offphone $fs/usr/local/lib/offphone $fs/usr/local/share/vulkan"
    # lavapipe's shareable memory comes from udmabuf; the app reaches it like a dma-buf heap.
    adb shell "chmod 666 /dev/udmabuf && chcon u:object_r:dmabuf_system_heap_device:s0 /dev/udmabuf" 2>/dev/null || true
    # Zink will not take a CPU device: GL goes to llvmpipe, the session's own software fallback.
    "$ctl" env set "BL_VK_DRIVER=/usr/local/share/offphone/lvp_icd.json" GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1 --persistent >/dev/null
    ;;
esac

before=$(latest_dir)
t0=$(date +%s)
case $scenario in
  steam-ui) "$ctl" start steam ;;
  stand-in-game)
    [ -d "$standin" ] || { echo "rig: no stand-in folder"; exit 2; }
    exe=$(cd "$standin" && find . -maxdepth 1 -iname '*.exe' | head -1); exe=${exe#./}
    adb shell "rm -rf $fs/root/standin && mkdir -p $fs/root/standin"
    adb push "$standin/." "$fs/root/standin/" >/dev/null
    uid=$(adb shell stat -c %u "$fs" | tr -d '\r')
    adb shell "chown -R $uid:$uid $fs/root/standin && restorecon -R $fs/root/standin"
    "$ctl" run /usr/local/bin/droiddeck-proton-run -- "/root/standin/$exe" --quit-after-frames 300 --max-fps 30 ;;
  *) echo "unknown scenario $scenario"; exit 2 ;;
esac

last=; idle=0; login=
while [ $(( $(date +%s) - t0 )) -lt "$limit" ]; do
  st=$("$ctl" state 2>/dev/null); ph=$(jq -r '.session.phase // "?"' <<<"$st")
  line="$ph $(jq -c '{step:.session.step, error:.session.error}' <<<"$st" 2>/dev/null)"
  [ "$line" != "$last" ] && { echo "+$(( $(date +%s) - t0 ))s $line"; last=$line; }
  echo "$st" > "$out/state.json"
  if [ "$ph" = IDLE ] && "$ctl" ui dump 2>/dev/null | grep -q "Install anyway"; then
    echo "rig: unsupported-GPU dialog, pressing Install anyway"
    "$ctl" ui click --match "Install anyway" >/dev/null 2>&1 || true
  fi
  case $ph in FAILED) break;; IDLE) idle=$((idle + 1)); [ $idle -ge 6 ] && break;; *) idle=0;; esac
  if [ "$scenario" = steam-ui ] && [ "$ph" = READY ]; then
    # Steam's sign-in screen, read from the client's own DevTools. Nothing is typed into it.
    t=$("$ctl" cdp targets 2>/dev/null)
    if jq -e '[.. | strings] | map(test("login|signin|sign in|log in"; "i")) | any' <<<"$t" >/dev/null 2>&1; then
      echo "$t" > "$out/cdp-targets.json"; rig_event login_screen; login=1; break
    fi
  fi
  sleep 5
done
[ "$scenario" = steam-ui ] && [ -z "$login" ] && { "$ctl" cdp targets > "$out/cdp-targets.json" 2>&1 || true; }
echo "rig: DevTools targets:"; jq -c '[.. | objects | select(.title? or .url?) | {title, url}]' "$out/cdp-targets.json" 2>/dev/null | head -c 3000; echo

# The app's own session folder, whole.
dir=$(latest_dir)
[ "$dir" = "$before" ] && echo "rig: no new session folder (the session never started)"
rm -rf "$out/rig-session"
[ -n "$dir" ] && adb pull "${dir%/}" "$out/rig-session" >/dev/null 2>&1 && echo "rig: session folder $(basename "$dir")"
python3 "$here/verdict.py" --session-dir "$out/rig-session" --scenario "$scenario" \
  --rig-events "$out/rig-events.jsonl" --out "$out/verdict.json"
