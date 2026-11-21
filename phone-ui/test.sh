#!/bin/bash
# Usage: phone-ui/test.sh <outdir>. Installs the Claude phone UI, films and screenshots every surface
# in light and dark, and measures latency and frame times. Results: <outdir>/report.txt, perf.txt, shots/, video/.
OUT=${1:-results/ui}; mkdir -p "$OUT/shots" "$OUT/video"
R=$OUT/report.txt; P=$OUT/perf.txt; : > $R; : > $P
log() { echo "== $*" | tee -a $R; }
A() { adb shell "$@"; }
B() { local c=$1; shift; A am broadcast -n claude.phone/.DemoReceiver --es cmd "$c" "$@" > /dev/null; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; }
waitfor() { # pattern timeout_s
  local i=0; while [ $i -lt $(( ${2:-10} * 10 )) ]; do adb logcat -d -s ClaudeDemo:I | grep -q "$1" && return 0; sleep 0.1; i=$((i+1)); done
  log "TIMEOUT waiting for $1"; return 1; }
perfdump() { echo "--- $1" >> $P; adb logcat -d -s ClaudePerf:I ClaudeDemo:I | grep -vE '^-+ beginning' | sed 's/^.*Claude\(Perf\|Demo\): //' >> $P; }

adb wait-for-device; A 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
log "build $(A getprop ro.build.fingerprint)"
A settings put global window_animation_scale 1; A settings put global transition_animation_scale 1; A settings put global animator_duration_scale 1
A settings put system screen_off_timeout 1800000; A svc power stayon true; A wm dismiss-keyguard; A input keyevent KEYCODE_WAKEUP
A cmd uimode night no
log "install"; adb install -r -g phone-ui/claude-phone.apk 2>&1 | tail -1 | tee -a $R
# Crashes and warnings for the whole run (logcat -c elsewhere would clear them).
adb logcat -b crash -b main -v time AndroidRuntime:E ClaudePerf:W "*:S" > $OUT/crash.txt 2>/dev/null &
CRASHPID=$!
A appops set claude.phone SYSTEM_ALERT_WINDOW allow
A cmd role add-role-holder android.app.role.ASSISTANT claude.phone; log "role exit=$? holders=$(A cmd role get-role-holders android.app.role.ASSISTANT)"
A settings put global power_button_long_press 5
A input keyevent KEYCODE_HOME; sleep 6

turn() { # demo label shots(yes/no)
  local d=$1 L=$2 S=${3:-yes}
  adb logcat -c; B demo --es v "$d"; sleep 0.3
  A input keyevent KEYCODE_ASSIST
  waitfor "STATE listening" 8
  if [ $S = yes ]; then sleep 1.3; shot ${L}_1_listening; fi
  if [ $d != offline ] && [ $d != idle ]; then waitfor "STATE thinking" 10; [ $S = yes ] && shot ${L}_2_thinking; fi
  case $d in
    message)
      waitfor "STATE speaking" 8; [ $S = yes ] && { sleep 0.9; shot ${L}_3_speaking; }
      waitfor "STATE confirm" 10; sleep 0.7; [ $S = yes ] && shot ${L}_4_confirm
      local xy=$(adb logcat -d -s ClaudeDemo:I | grep -o 'HOLD [0-9]* [0-9]*' | tail -1 | cut -d' ' -f2-)
      local x=${xy% *} y=${xy#* }
      A input motionevent DOWN $x $y; sleep 0.3; [ $S = yes ] && shot ${L}_5_holding
      sleep 0.7; A input motionevent UP $x $y
      waitfor "STATE sent" 5; sleep 0.8; [ $S = yes ] && shot ${L}_6_sent ;;
    chart|tool)
      waitfor "STATE speaking" 8; [ $S = yes ] && { sleep 0.5; shot ${L}_3_streaming; }
      waitfor "STATE canvas_done" 15; sleep 0.6; [ $S = yes ] && shot ${L}_4_canvas ;;
    offline)
      waitfor "STATE offline" 10; sleep 0.9; [ $S = yes ] && shot ${L}_2_offline ;;
    idle)
      waitfor "STATE idle" 12; sleep 0.8; [ $S = yes ] && shot ${L}_2_idle ;;
  esac
  A input keyevent KEYCODE_BACK; waitfor "STATE hidden" 5; sleep 1.2
  perfdump "$L"
}

# ---------- 1. Latency and frame times, no screenshots (they steal CPU) ----------
log "perf: cold start without prewarm"
B prewarm --es v false; A am force-stop claude.phone; sleep 8
turn chart perf_cold_noprewarm no
turn chart perf_warm_noprewarm_1 no
log "perf: cold start with prewarm"
B prewarm --es v true; A am force-stop claude.phone; sleep 8
turn chart perf_cold_prewarm no
turn chart perf_warm_prewarm_1 no
turn message perf_msg_1 no
turn message perf_msg_2 no
turn message perf_msg_3 no
turn tool perf_tool_1 no
log "perf: with a screenshot captured on every invoke (Android's default)"
B screenshot --es v true; A am force-stop claude.phone; sleep 8
turn message perf_msg_shot_1 no
turn message perf_msg_shot_2 no
B screenshot --es v false; A am force-stop claude.phone; sleep 8

# ---------- 1b. Calibration: stock apps on this same emulator ----------
log "calibration: Settings scroll"
A am start -W -a android.settings.SETTINGS > /dev/null; sleep 2
A dumpsys gfxinfo com.android.settings reset > /dev/null
for i in 1 2 3 4; do A input swipe 540 1800 540 600 150; sleep 0.7; A input swipe 540 600 540 1800 150; sleep 0.7; done
A dumpsys gfxinfo com.android.settings | grep -E 'Total frames|Janky|percentile' | sed 's/^/settings: /' | tee -a $R
log "calibration: notification shade"
A input keyevent KEYCODE_HOME; sleep 1
A dumpsys gfxinfo com.android.systemui reset > /dev/null
for i in 1 2 3; do A cmd statusbar expand-notifications; sleep 1.2; A cmd statusbar collapse; sleep 1.2; done
A dumpsys gfxinfo com.android.systemui | grep -E 'Total frames|Janky|percentile' | sed 's/^/systemui: /' | tee -a $R

# ---------- 2. Video of the message flow ----------
log "video"
A 'screenrecord --bit-rate 6000000 --time-limit 30 /sdcard/flow_light.mp4' & RP=$!
sleep 1; turn message vid no; turn chart vid2 no; sleep 0.5
A pkill -INT screenrecord; wait $RP; sleep 1; adb pull /sdcard/flow_light.mp4 $OUT/video/ >/dev/null

# ---------- 3. Screenshots, light then dark ----------
for mode in light dark; do
  log "screens $mode"
  [ $mode = dark ] && { A cmd uimode night yes; sleep 3; }
  A input keyevent KEYCODE_HOME; sleep 1
  B variant --es v card; turn message ${mode}_card_msg; B variant --es v ambient
  turn message ${mode}_msg; turn chart ${mode}_chart; turn tool ${mode}_tool; turn offline ${mode}_offline; turn idle ${mode}_idle
  # Notifications and the kill switch
  B resume; B nudge; B needs; sleep 1
  A cmd statusbar expand-notifications; sleep 1.5; shot ${mode}_notif_shade; A cmd statusbar collapse; sleep 0.8
  A cmd statusbar add-tile claude.phone/.KillTile; sleep 0.5
  A cmd statusbar expand-settings; sleep 1.5; shot ${mode}_qs_working
  adb logcat -c; A cmd statusbar click-tile claude.phone/.KillTile; sleep 1.2; shot ${mode}_qs_paused; perfdump "${mode}_killtile"
  A cmd statusbar collapse; sleep 0.5; B resume; B cancel_all
  # Edge glow over another app
  A am start -W -a android.intent.action.SHOW_ALARMS > /dev/null; sleep 1.5  # not Settings: it hides non-system overlays
  echo "${mode} overlay appop: $(A appops get claude.phone SYSTEM_ALERT_WINDOW)" >> $R
  B glow_on; sleep 1.2; shot ${mode}_glow; B glow_off; sleep 0.5
  A input keyevent KEYCODE_HOME
  # Activity log
  B seed --ei n 40
  A am start -W -S -n claude.phone/.LogActivity | grep -E 'Time|State' | tr '\n' ' ' | sed "s/^/${mode} log start: /" >> $R; echo >> $R
  sleep 1.2; shot ${mode}_log
  A input swipe 540 1700 540 900 300; sleep 1; shot ${mode}_log_scrolled
  B clearlog; A am start -W -S -n claude.phone/.LogActivity > /dev/null; sleep 1.2; shot ${mode}_log_empty
  A input keyevent KEYCODE_HOME; sleep 0.5
done
A cmd uimode night no

# ---------- 4. Activity log: cold start and scroll jank ----------
log "log cold start x5"
B seed --ei n 120
for i in 1 2 3 4 5; do A am start -W -S -n claude.phone/.LogActivity | grep -E 'Time|State' | tr '\n' ' ' | tee -a $R; echo >> $R; sleep 0.8; done
log "log warm start x3"
for i in 1 2 3; do A input keyevent KEYCODE_HOME; sleep 0.8; A am start -W -n claude.phone/.LogActivity | grep -E 'Time|State' | tr '\n' ' ' | tee -a $R; echo >> $R; sleep 0.8; done
A dumpsys gfxinfo claude.phone reset > /dev/null
for i in 1 2 3 4 5 6; do A input swipe 540 1900 540 400 120; sleep 0.6; done
for i in 1 2 3; do A input swipe 540 500 540 1900 120; sleep 0.6; done
log "log scroll gfxinfo"; A dumpsys gfxinfo claude.phone | grep -E 'Total frames|Janky|percentile|Number' | tee -a $R
adb logcat -c; A input keyevent KEYCODE_HOME; sleep 1; perfdump log_scroll
A input keyevent KEYCODE_HOME

kill $CRASHPID 2>/dev/null; log "crashes"; grep -c "FATAL" $OUT/crash.txt | tee -a $R; grep -A12 "FATAL" $OUT/crash.txt | head -60 >> $R
log "summary"
grep -E '^---|FIRST_FRAME|LATENCY|MARK (create_to_onShow|key_to_onShow|overlay_build|session_onCreate|prewarm|canvas|kill)|FRAMES (overlay|log)/ALL' $P | head -200 >> $R
# Shots to webp so they can live in the repo.
for f in $OUT/shots/*.png; do convert "$f" -quality 92 "${f%.png}.webp" && rm "$f"; done
cat $R
