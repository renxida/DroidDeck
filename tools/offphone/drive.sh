#!/usr/bin/env bash
# Drives the installed app on the CI Android (redroid) through one launch scenario and leaves
# the evidence in OUT: rig-session/ (the app's own session folder), rig-events.jsonl, verdict.json.
#
#   tools/offphone/drive.sh SCENARIO OUT [lavapipe|venus] [timeout-seconds] [stand-in-dir]
#
# steam-ui               Steam's client starts and reaches its sign-in screen. Nothing signs in.
# stand-in-game          The stand-in Godot game (our own code, no game files) runs through Proton
#                        and FEX, presents its frames and exits cleanly.
# steam-ui-screen-cycle  steam-ui with the display switched off and on three times while Steam
#                        starts (cdabfbd: Steam's UI took 150 s instead of 25).
# steam-ui-background    steam-ui with the app sent home for 30 s while Steam starts.
# stand-in-background    stand-in-game with the app sent home for 20 s while the game loads (008c6625).
# stand-in-native        The stand-in with the native-engine experiment on, as Slay the Spire 2 runs on a
#                        phone: droiddeck-game-env must start it on the native engine this APK embeds
#                        (Steam preflight on, Steam's client installed) and it must run to its last frame.
#                        "Native engine skipped" or a fall back to Proton fails the run.
# steam-ui-park          steam-ui with the steam-ui-park experiment on. At the sign-in screen the rig asks for a
#                        park (no game runs here): the web helper must end, the client's restart of it must
#                        wait in droiddeck-steam-taskset, the client must stay up for two minutes without it,
#                        and a restore must bring the sign-in screen back.
# steam-ui-park-kill     steam-ui-park with BL_STEAM_UI_PARK_SIGNAL=KILL: the helper is killed, not asked to end.
# steam-ui-helper-term, steam-ui-helper-kill
#                        Probes with no experiment on: at the sign-in screen the rig itself sends the web
#                        helper's browser process SIGTERM, or all its processes SIGKILL, and watches for two
#                        minutes whether the client stays up and starts a new helper.
# steam-ui-x-soft        steam-ui with the steam-x-soft experiment on: Steam's X server runs without glamor and
#                        the interface must still reach the screen and its sign-in page.
# upgrade                The previous kestrel.apk installed and set up first (OLD_APK, already
#                        installed by the caller), then this APK (NEW_APK) installed over it: a
#                        setting must survive, then steam-ui.
# Exit status: 0 when the scenario's target stage was reached (see tools/offphone/verdict.py).
set -uo pipefail
scenario=${1:?scenario}; out=${2:?out dir}; gv=${3:-lavapipe}; limit=${4:-900}; standin=${5:-}
here=$(cd "$(dirname "$0")" && pwd); repo=$(cd "$here/../.." && pwd)
# The app's own tools (droiddeckctl, app-id) come from the APK's commit when CI checks that out
# separately from the rig; locally they are this checkout's.
apptools=${DD_APP_TOOLS:-$repo/tools}
ctl="$apptools/droiddeckctl"
pkg=$("$apptools/app-id")
fs=/data/user/0/$pkg/files/linuxfs
case $scenario in
  steam-ui|steam-ui-screen-cycle|steam-ui-background|upgrade|steam-ui-park|steam-ui-park-kill|steam-ui-x-soft|steam-ui-helper-term|steam-ui-helper-kill) base=steam-ui ;;
  stand-in-game|stand-in-background|stand-in-native) base=stand-in-game ;;
  *) echo "unknown scenario $scenario"; exit 2 ;;
esac
mkdir -p "$out"; : > "$out/rig-events.jsonl"
now_ms() { date +%s%3N; }
rig_event() { printf '{"stage":"%s","t":%s}\n' "$1" "$(now_ms)" >> "$out/rig-events.jsonl"; echo "rig: $1"; }
phase() { "$ctl" state 2>/dev/null | jq -r '.session.phase // "?"'; }
latest_dir() { adb shell "ls -1d /data/user/0/$pkg/files/logs/*/ 2>/dev/null | tail -1" | tr -d '\r'; }
# Session folders made since the rig's own start (names sort by time).
new_dirs() { adb shell "ls -1d /data/user/0/$pkg/files/logs/*/ 2>/dev/null" | tr -d '\r' | awk -v b="$before" '"" $0 > "" b'; }

# Debugging tools (Setup > Diagnostics) and Android's phantom-process limit, as on a test phone.
"$here/enable-debug-tools.sh" "$pkg" >/dev/null
adb shell device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true
adb shell device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1 || true
adb shell settings put global settings_enable_monitor_phantom_procs false >/dev/null 2>&1 || true

guest_env=()
appuser=
# Settings > Advanced > Experiments, as the app saves it: the fresh-install set plus the named switches.
experiments_on() {
  local uid dir=/data/data/$pkg/shared_prefs ids
  ids=$(python3 - "$apptools/../app/src/main/java/com/droiddeck/launcher/session/Experiments.kt" "$@" <<'PY'
import re, sys
source = open(sys.argv[1]).read()
names = dict(re.findall(r'const val (\w+) = "([^"]+)"', source))
defaults = [names[n.strip()] for n in re.search(r"val defaults = setOf\(([^)]*)\)", source).group(1).split(",")]
missing = [i for i in sys.argv[2:] if i not in names.values()]
if missing:
    sys.exit("this APK's commit has no experiment " + ", ".join(missing))
print("".join("<string>%s</string>" % i for i in defaults + sys.argv[2:]))
PY
  ) || { echo "rig: $ids"; exit 2; }
  adb shell am force-stop "$pkg"
  uid=$(adb shell stat -c %u "/data/data/$pkg" | tr -d '\r')
  adb shell "cat > $dir/experiments.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <set name="on">$ids</set>
</map>
XML
  adb shell "chown $uid:$uid $dir/experiments.xml && chmod 660 $dir/experiments.xml && restorecon $dir/experiments.xml"
  echo "rig: experiments on: $*"
}
case $scenario in
  steam-ui-park) experiments_on steam-ui-park ;;
  steam-ui-park-kill) experiments_on steam-ui-park; guest_env+=(BL_STEAM_UI_PARK_SIGNAL=KILL) ;;
  # The rig's Steam composites as the session's default does, not as an open driver pair's; the switch is
  # told to apply anyway.
  steam-ui-x-soft) experiments_on steam-x-soft; guest_env+=(BL_STEAM_X_SOFT=1) ;;
esac
# The app's processes (proot, gamescope, wine, FEX), by CPU time: what a stuck start is doing.
sample_ps() { # label
  [ -n "$appuser" ] || appuser=$(adb shell "stat -c %U /data/user/0/$pkg" | tr -d '\r')
  adb shell "ps -A -o USER,PID,PPID,TIME,ETIME,RSS,S,ARGS" | tr -d '\r' | awk -v u="$appuser" 'NR==1 || $1==u' > "$out/ps-$1.txt"
  echo "rig: processes at $1 (top by CPU time):"
  { head -1 "$out/ps-$1.txt"; tail -n +2 "$out/ps-$1.txt" | sort -k4,4r | head -12; } | cut -c1-220
}

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
    guest_env+=("BL_VK_DRIVER=/usr/local/share/offphone/lvp_icd.json" GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1)
    ;;
esac
[ ${#guest_env[@]} -gt 0 ] && "$ctl" env set "${guest_env[@]}" --persistent >/dev/null

# A phone that runs games has Steam's client installed; Proton's steam.exe loads its
# steamclient.so even for a game that is not on Steam. So the stand-in runs after Steam has
# installed itself once (to its sign-in screen, nothing signed in), as on a phone.
steamclient() { adb shell "find $fs/root/.local/share/Steam -name steamclient.so 2>/dev/null" | tr -d '\r'; }
if [ "$base" = stand-in-game ] && [ -z "$(steamclient)" ]; then
  echo "rig: Steam's client is not installed yet; starting Steam once to its sign-in screen"
  "$ctl" start steam >/dev/null 2>&1 || true
  w0=$(date +%s)
  while [ $(( $(date +%s) - w0 )) -lt 600 ]; do
    case $(phase) in READY) [ -n "$(steamclient)" ] && break;; FAILED) break;; esac; sleep 5
  done
  echo "rig: Steam warm-up $(( $(date +%s) - w0 ))s, phase $(phase), steamclient: $(steamclient | tr '\n' ' ')"
  "$ctl" stop >/dev/null 2>&1 || true; wait_idle 120 || true
fi

top_activity() { adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*{[^ ]* [^ ]* //; s/ t[0-9]*}.*//' | tr -d '\r'; }
launcher() { adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r' | tail -1; }

if [ "$scenario" = upgrade ]; then
  # Settings and the installed runtime from the previous release must survive the update.
  "$ctl" prefs set session rig_upgrade_marker true --type bool >/dev/null
  echo "rig: previous build $(adb shell dumpsys package "$pkg" | grep -m1 versionName | tr -d '\r ')"
  adb shell am force-stop "$pkg"
  r=$(adb install -r -g "${NEW_APK:?NEW_APK}" 2>&1 | tail -1); echo "rig: install over it: $r"
  [ "$r" = Success ] && rig_event upgrade_installed
  echo "rig: now $(adb shell dumpsys package "$pkg" | grep -m1 versionName | tr -d '\r ')"
  adb shell am start -W -n "$(launcher)" >/dev/null; sleep 5
  "$here/enable-debug-tools.sh" "$pkg" >/dev/null
  if "$ctl" prefs get session 2>/dev/null | jq -e '[.. | objects | .rig_upgrade_marker? // empty] | any(. == true or . == "true")' >/dev/null; then
    rig_event settings_kept
  else echo "rig: the setting made before the update is gone"; "$ctl" prefs get session 2>&1 | head -c 2000; echo; fi
  adb shell "ls $fs/usr/bin/bash" >/dev/null 2>&1 && rig_event runtime_kept || echo "rig: the runtime is gone after the update"
fi

perturbed=
perturb() { # once, while the start is under way
  [ -n "$perturbed" ] && return; perturbed=1
  case $scenario in
    steam-ui-screen-cycle)
      for i in 1 2 3; do
        adb shell input keyevent KEYCODE_SLEEP; sleep 4
        adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1; sleep 4
      done
      echo "rig: display: $(adb shell dumpsys power | grep -m1 -E 'mWakefulness=' | tr -d '\r ')"
      rig_event screen_cycled ;;
    steam-ui-background|stand-in-background)
      local away=30; [ "$base" = stand-in-game ] && away=20
      adb shell input keyevent KEYCODE_HOME; echo "rig: app sent home for ${away}s"
      sleep 2; echo "rig: top while away: $(top_activity)"; sleep "$away"
      # Back the way a person comes back: the launcher icon (an existing task comes to the front).
      adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$(launcher)" >/dev/null
      local top i; for i in 1 2 3 4 5; do sleep 2; top=$(top_activity); case $top in *SessionActivity*) break;; esac; done
      echo "rig: top after return: $top"
      local seen_text; seen_text=$("$ctl" ui dump 2>/dev/null | jq -r '[.. | objects | .text? // empty | strings | select(length > 0)] | unique | join(" | ")' 2>/dev/null | head -c 600)
      echo "rig: on screen after return: $seen_text"
      rig_event backgrounded
      # The icon should bring the running session back. When it lands on the home screen
      # instead, that is the bug this scenario exists for: the run fails (returned_to_session is
      # missing), but the rig presses Open Steam the way a person would, so the rest of the
      # launch is still measured and the log shows whether the session survived.
      case $top in
        *SessionActivity*) rig_event returned_to_session ;;
        *)
          rig_event landed_on_home
          if grep -q "Open Steam" <<<"$seen_text"; then
            sleep 2; "$ctl" ui click --match "Open Steam" >/dev/null 2>&1 && echo "rig: pressed Open Steam"
            sleep 3; echo "rig: top after Open Steam: $(top_activity)"
          fi ;;
      esac ;;
  esac
}

before=$(latest_dir)
t0=$(date +%s); rig_event start_sent
case $base in
  steam-ui) "$ctl" start steam ;;
  stand-in-game)
    [ -d "$standin" ] || { echo "rig: no stand-in folder"; exit 2; }
    exe=$(cd "$standin" && find . -maxdepth 1 -iname '*.exe' | head -1); exe=${exe#./}
    adb shell "rm -rf $fs/root/standin && mkdir -p $fs/root/standin"
    adb push "$standin/." "$fs/root/standin/" >/dev/null
    uid=$(adb shell stat -c %u "$fs" | tr -d '\r')
    adb shell "chown -R $uid:$uid $fs/root/standin && restorecon -R $fs/root/standin"
    # In stand-in-background the game must still be running when the rig comes back (READY at
    # about 12 s, 20 s away), so it runs about three times longer there.
    frames=300; [ "$scenario" = stand-in-background ] && frames=1200
    # The native-engine switch as Settings > Experiments sets it for a game launch: game-env strips DROIDDECK_EXP_*
    # from the inherited environment and takes game-level switches from game-environment.json's shared entries.
    # The app's own copy is what it republishes into the guest when a session starts; the guest copy covers a
    # session that is already running.
    if [ "$scenario" = stand-in-native ]; then
      for f in "/data/user/0/$pkg/files/game-environment.json" "$fs/root/.config/droiddeck/game-environment.json"; do
        cur=$(adb shell "cat $f 2>/dev/null" | tr -d '\r'); jq -e . >/dev/null 2>&1 <<<"$cur" || cur='{"version":1,"shared":{},"games":{}}'
        new=$(jq -c '.shared = ((.shared // {}) + {"DROIDDECK_EXP_NATIVE_ENGINE": "1"})' <<<"$cur")
        adb shell "mkdir -p ${f%/*} && chown $uid:$uid ${f%/*} && printf '%s' '$new' > $f && chown $uid:$uid $f && restorecon $f" 2>/dev/null \
          || echo "rig: could not write $f"
      done
      echo "rig: native-engine on for game launches: $(adb shell "cat $fs/root/.config/droiddeck/game-environment.json" | tr -d '\r' | jq -c .shared)"
    fi
    "$ctl" run /usr/local/bin/droiddeck-proton-run -- "/root/standin/$exe" --quit-after-frames $frames --max-fps 30 ;;
esac

last=; idle=0; login=; next_ps=60
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
  el=$(( $(date +%s) - t0 ))
  [ "$el" -ge "$next_ps" ] && { sample_ps "${el}s"; next_ps=$(( next_ps + (next_ps < 240 ? 60 : 180) )); }
  # The perturbation lands while Steam starts, or while the game loads (just after READY).
  case "$base:$ph" in
    steam-ui:STARTING_STEAM) [ "$el" -ge 10 ] && perturb ;;
    stand-in-game:READY) perturb ;;
  esac
  if [ "$base" = steam-ui ] && [ "$ph" = READY ]; then
    # Steam's sign-in screen, read from the client's own DevTools (its JS context sits on
    # steamloopback.host/routes/login). Nothing is typed into it.
    t=$("$ctl" cdp targets 2>/dev/null)
    if jq -e '[.. | objects | .url? // empty | strings | test("/routes/login")] | any' <<<"$t" >/dev/null 2>&1; then
      echo "$t" > "$out/cdp-targets.json"; rig_event login_screen; login=1; break
    fi
  fi
  # The start the rig asked for must finish by itself; another session means the app gave up
  # on it (or the person would have had to start again).
  if [ -z "${restarted:-}" ] && [ "$(new_dirs | wc -l)" -gt 1 ]; then
    restarted=1; rig_event session_restarted; echo "rig: a second session started: $(new_dirs | tr '\n' ' ')"
  fi
  if [ "$base" = stand-in-game ]; then
    # The stand-in prints its markers to stdout, which lands in the session log.
    d=$(latest_dir)
    if [ -n "$d" ] && [ "$d" != "$before" ]; then
      # Natively the game's stdout goes to native-engine.log in the session folder, not session.log.
      m=$(adb shell "grep -rh -m2 -E '^(FIRST_FRAME|MAINLOOP_OK)' ${d%/}/session.log ${d%/}/native-engine.log ${d%/}/games 2>/dev/null" | tr -d '\r')
      grep -q '^FIRST_FRAME' <<<"$m" && ! grep -q standin_first_frame "$out/rig-events.jsonl" && rig_event standin_first_frame
      grep -q '^MAINLOOP_OK' <<<"$m" && { rig_event standin_mainloop_ok; break; }
    fi
  fi
  sleep 5
done
# What the session log says, on the device (the folder is pulled at the end).
session_log_has() { adb shell "grep -c -- '$1' ${rigdir%/}/session.log 2>/dev/null" | tr -d '\r' | grep -qv '^0$'; }
wait_log() { # pattern seconds
  local end=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$end" ]; do session_log_has "$1" && return 0; sleep 2; done; return 1
}
# The Steam side's processes: web helper count and memory, the client, the X servers.
steam_ps() { # label
  [ -n "$appuser" ] || appuser=$(adb shell "stat -c %U /data/user/0/$pkg" | tr -d '\r')
  adb shell "ps -A -o USER,PID,PPID,TIME,RSS,S,ARGS" | tr -d '\r' | awk -v u="$appuser" '$1==u' \
    | grep -E 'steamwebhelper|steamrtarm64/steam |Xwayland|gamescope|droiddeck-steam/taskset|droiddeck-steam-ui-park' > "$out/steam-ps-$1.txt"
  awk -v l="$1" '
    /steamwebhelper/ && !/steamwebhelper\.sh/ && !/taskset/ { h++; hr += $5 }
    /taskset/ { held++ }
    /Xwayland/ { x = x " " $5/1024 "MB" }
    /steamrtarm64\/steam / && !/child-update-ui/ { c++; ct = $4 }
    END { printf "rig: %s: web helper processes %d, resident %d MB; held starts %d; Steam client processes %d (cpu %s); Xwayland resident%s\n", l, h, hr/1024, held, c, ct, x }' "$out/steam-ps-$1.txt"
}
helper_count() { awk '/steamwebhelper/ && !/steamwebhelper\.sh/ && !/taskset/ { n++ } END { print n + 0 }' "$out/steam-ps-$1.txt"; }
client_count() { awk '/steamrtarm64\/steam / && !/child-update-ui/ { n++ } END { print n + 0 }' "$out/steam-ps-$1.txt"; }
rigdir=$(new_dirs | head -1)
if [ "$scenario" = steam-ui-x-soft ] && [ -n "$login" ] && [ -n "$rigdir" ]; then
  session_log_has 'event=steam_x_soft applied=1' && rig_event x_soft_applied
  echo "rig: steam-x-soft lines: $(adb shell "grep -hE 'steam-x-soft|steam_x_soft' ${rigdir%/}/session.log" | tr -d '\r' | cut -c1-200 | tr '\n' '|')"
  steam_ps x-soft
  for p in $(adb shell pidof Xwayland | tr -d '\r'); do
    echo "rig: Xwayland pid $p: $(adb shell "tr '\\0' ' ' < /proc/$p/cmdline | cut -c1-40") glamor off in its environment: $(adb shell "tr '\\0' '\\n' < /proc/$p/environ | grep -c '^XWAYLAND_NO_GLAMOR=1'" | tr -d '\r')"
  done
fi
case $scenario in steam-ui-helper-term|steam-ui-helper-kill)
  if [ -n "$login" ] && [ -n "$rigdir" ]; then
    steam_ps before
    old=$(awk '/steamwebhelper/ && !/steamwebhelper\.sh/ && !/taskset/ { print $2 }' "$out/steam-ps-before.txt" | sort | tr '\n' ' ')
    browser=$(awk '/steamwebhelper/ && !/steamwebhelper\.sh/ && !/taskset/ && !/--type=/ { print $2 }' "$out/steam-ps-before.txt" | tr '\n' ' ')
    if [ "$scenario" = steam-ui-helper-term ]; then adb shell "kill -TERM $browser"; else adb shell "kill -KILL $old"; fi
    echo "rig: signalled the web helper (browser $browser; all $old)"; rig_event helper_signalled; asked=$(date +%s)
    survived=1; back=
    for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
      sleep 10; steam_ps "after-$((i * 10))s"
      [ "$(client_count "after-$((i * 10))s")" -ge 1 ] || { survived=; echo "rig: the Steam client is gone $((i * 10))s after the signal"; break; }
      new=$(awk '/steamwebhelper/ && !/steamwebhelper\.sh/ && !/taskset/ { print $2 }' "$out/steam-ps-after-$((i * 10))s.txt" | sort | tr '\n' ' ')
      [ -n "$new" ] && [ "$new" != "$old" ] && [ -z "$back" ] && { back=1; rig_event helper_back; echo "rig: a new web helper $((i * 10))s after the signal"; }
      if [ -n "$back" ] && "$ctl" cdp targets 2>/dev/null | jq -e '[.. | objects | .url? // empty | strings | test("/routes/login")] | any' >/dev/null 2>&1; then
        rig_event login_screen_again; echo "rig: sign-in screen back $(( $(date +%s) - asked ))s after the signal"; break
      fi
    done
    [ -n "$survived" ] && [ "$(phase)" = READY ] && rig_event client_survived
    echo "::group::the session log after the signal"; adb shell "tail -n 60 ${rigdir%/}/session.log" | tr -d '\r' | cut -c1-300; echo "::endgroup::"
  fi ;;
esac
if [ "${scenario#steam-ui-park}" != "$scenario" ] && [ -n "$login" ] && [ -n "$rigdir" ]; then
  sess=/data/user/0/$pkg/files/session
  uid=$(adb shell stat -c %u "$fs" | tr -d '\r')
  ask() { adb shell "printf '%s\n' $1 > $sess/steam-ui-park.tmp && chown $uid:$uid $sess/steam-ui-park.tmp && chmod 644 $sess/steam-ui-park.tmp && mv $sess/steam-ui-park.tmp $sess/steam-ui-park"; }
  session_log_has 'event=steam_ui_park_ready' && rig_event park_ready
  steam_ps before
  ask park; asked=$(date +%s)
  if wait_log 'event=steam_ui_parked' 60; then rig_event ui_parked; fi
  # The held start says so on its stderr, which may not reach the session log; its marker file is beside the gate.
  for i in $(seq 1 30); do
    if session_log_has 'event=steam_ui_park_hold ' || adb shell "ls $sess/steam-ui-gate.held.* 2>/dev/null" | grep -q held; then
      rig_event ui_held; break
    fi
    sleep 2
  done
  echo "rig: gate files: $(adb shell "ls $sess | grep steam-ui" | tr -d '\r' | tr '\n' ' ')"
  echo "rig: parked $(( $(date +%s) - asked ))s after the request"
  # Two minutes without its web helper: the client must stay up and must not get a helper past the gate.
  stayed=1
  for i in 1 2 3 4; do
    sleep 30; steam_ps "parked-$((i * 30))s"
    [ "$(client_count "parked-$((i * 30))s")" -ge 1 ] || { stayed=; echo "rig: the Steam client is gone"; }
    [ "$(helper_count "parked-$((i * 30))s")" -eq 0 ] || { stayed=; echo "rig: a web helper is running while parked"; }
  done
  session_log_has 'event=steam_ui_park_hold_missed' && stayed=
  session_log_has 'event=steam_ui_park_failed' && stayed=
  [ "$(phase)" = READY ] || { stayed=; echo "rig: session phase $(phase) while parked"; }
  [ -n "$stayed" ] && grep -q ui_held "$out/rig-events.jsonl" && rig_event ui_stayed_parked
  ask restore; asked=$(date +%s)
  if wait_log 'event=steam_ui_restored' 120; then rig_event ui_restored; fi
  echo "rig: restored $(( $(date +%s) - asked ))s after the request"
  for i in $(seq 1 30); do
    t=$("$ctl" cdp targets 2>/dev/null)
    if jq -e '[.. | objects | .url? // empty | strings | test("/routes/login")] | any' <<<"$t" >/dev/null 2>&1; then
      echo "$t" > "$out/cdp-targets-restored.json"; rig_event login_screen_again
      echo "rig: sign-in screen back $(( $(date +%s) - asked ))s after the restore request"; break
    fi
    sleep 3
  done
  steam_ps restored
  echo "::group::steam-ui-park lines in the session log"
  adb shell "grep -hE 'steam_ui_|steam-ui-park|steam: its interface' ${rigdir%/}/session.log" | tr -d '\r' | cut -c1-400
  echo "::endgroup::"
fi
sample_ps end
[ -n "$perturbed" ] && echo "rig: at the end: top $(top_activity); on screen: $("$ctl" ui dump 2>/dev/null | jq -r '[.. | objects | .text? // empty | strings | select(length > 0)] | unique | join(" | ")' 2>/dev/null | head -c 600)"
[ "$base" = steam-ui ] && [ -z "$login" ] && { "$ctl" cdp targets > "$out/cdp-targets.json" 2>&1 || true; }
echo "rig: DevTools targets:"; jq -c '[.. | objects | select(.title? or .url?) | {title, url}]' "$out/cdp-targets.json" 2>/dev/null | head -c 3000; echo

# The app's own session folder, whole: the one the rig started, and any after it.
dirs=$(new_dirs); dir=$(head -1 <<<"$dirs")
[ -z "$dir" ] && echo "rig: no new session folder (the session never started)"
rm -rf "$out/rig-session" "$out"/rig-session-later-*
[ -n "$dir" ] && adb pull "${dir%/}" "$out/rig-session" >/dev/null 2>&1 && echo "rig: session folder $(basename "$dir")"
i=0; for d in $(tail -n +2 <<<"$dirs"); do
  i=$((i + 1)); adb pull "${d%/}" "$out/rig-session-later-$i" >/dev/null 2>&1
  echo "::group::later session $(basename "$d") events"; cut -c1-300 "$out/rig-session-later-$i/events.jsonl" 2>/dev/null; echo "::endgroup::"
done
echo "::group::first session's last events"; tail -n 25 "$out/rig-session/events.jsonl" 2>/dev/null | cut -c1-300; echo "::endgroup::"
# The app keeps per-game logs (Proton's own log, DXVK, Mesa) under games/ on a GPU that is
# not an Adreno, which CI Android is not.
# FEX's own "D <tid>" lines (handled exceptions) are dropped; they drown the Wine and game lines.
for f in $(find "$out/rig-session/games" -type f 2>/dev/null | sort); do
  n=$(grep -cv '^D [0-9]' "$f")
  echo "::group::game log ${f#$out/rig-session/} ($n lines without FEX's D lines; first 150 and last 250)"
  if [ "$n" -gt 400 ]; then grep -v '^D [0-9]' "$f" | head -150; echo "..."; grep -v '^D [0-9]' "$f" | tail -250
  else grep -v '^D [0-9]' "$f"; fi | cut -c1-300
  echo; echo "::endgroup::"
done
if [ "$scenario" = stand-in-native ]; then
  echo "rig: engine lines: $(grep -h 'droiddeck-game-env: engine: ' "$out/rig-session/session.log" 2>/dev/null | cut -c1-200 | tr '\n' '|')"
  for f in $(find "$out/rig-session" -name native-engine.log 2>/dev/null); do
    echo "::group::native engine log ${f#$out/rig-session/} (last 200 lines)"; tail -n 200 "$f" | cut -c1-300; echo "::endgroup::"
  done
fi
if [ "$base" = stand-in-game ]; then
  echo "rig: stand-in folder: $(ls "$standin" | tr '\n' ' ')"
  # Godot's own log, in the game's prefix.
  adb pull "$fs/root/.local/share/Steam/steamapps/compatdata/4055603035/pfx/drive_c/users/steamuser/AppData/Roaming/Godot/app_userdata/StandIn/logs" "$out/godot-logs" >/dev/null 2>&1 || true
  for f in "$out"/godot-logs/*.log; do [ -f "$f" ] && { echo "::group::Godot $(basename "$f")"; tail -n 200 "$f" | cut -c1-300; echo "::endgroup::"; }; done
  echo "rig: stand-in markers anywhere: $(grep -rhoE '^(STANDIN_DEPS|FIRST_FRAME|MAINLOOP_OK)[^\r]*' "$out/rig-session" "$out/godot-logs" 2>/dev/null | cut -c1-120 | tr '\n' '|')"
fi
echo "::group::all app processes at the end"; cat "$out/ps-end.txt" 2>/dev/null | cut -c1-240; echo "::endgroup::"
echo "rig: session folder files:"; ls -la "$out/rig-session" 2>/dev/null
python3 "$here/verdict.py" --session-dir "$out/rig-session" --extra-logs "$out/godot-logs" --scenario "$scenario" \
  --rig-events "$out/rig-events.jsonl" --out "$out/verdict.json"
