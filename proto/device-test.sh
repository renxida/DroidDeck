#!/system/bin/sh
# Runs on the device as the shell user. Writes everything to /data/local/tmp/out.
FLAGS=${1:-31945}; LABEL=${2:-full}; MATRIX=${3:-1}
D=/data/local/tmp; O=$D/out/$LABEL; rm -rf $O; mkdir -p $O
REP=$O/report.txt
log() { echo "$@" >> $REP; }
run() { CLASSPATH=$D/vd.dex app_process / claude.vd.Main "$@"; }
log "== build"; getprop ro.build.fingerprint >> $REP; log "sdk=$(getprop ro.build.version.sdk)"; id >> $REP
log "== uinput node"; ls -lZ /dev/uinput >> $REP 2>&1
log "== shell permissions of interest"
dumpsys package com.android.shell | grep -E 'ADD_TRUSTED_DISPLAY|ADD_ALWAYS_UNLOCKED_DISPLAY|CAPTURE_SECURE_VIDEO_OUTPUT|CAPTURE_VIDEO_OUTPUT|INJECT_EVENTS|ASSOCIATE_INPUT_DEVICE_TO_DISPLAY|INTERNAL_SYSTEM_WINDOW|ACCESS_COMPUTER_CONTROL|CREATE_VIRTUAL_DEVICE|MANAGE_ACTIVITY_TASKS' >> $REP 2>&1

# Flag matrix. 201=PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES; +1024 TRUSTED, +2048 OWN_DISPLAY_GROUP,
# +4096 ALWAYS_UNLOCKED, +8192 TOUCH_FEEDBACK_DISABLED, +16384 OWN_FOCUS, +4 SECURE, +512 SYSTEM_DECORATIONS
if [ "$MATRIX" = 1 ]; then
log "== flag matrix"
for f in 201 1225 2249 4297 8393 16585 205 713 31945 31949; do
  log "-- flags $f"
  printf 'info\nquit\n' | run 1080 2400 420 $f m$f >> $REP 2>&1
done
fi

log "== workspace session flags=$FLAGS label=$LABEL"
# Commands go in by appending to a file that tail -f pipes into the helper (a pipe works; a fifo did not).
: > $D/cmd.txt
(tail -f $D/cmd.txt | CLASSPATH=$D/vd.dex app_process / claude.vd.Main 1080 2400 420 $FLAGS claude-ws > $O/helper.log 2>&1) &
c() { echo "$@" >> $D/cmd.txt; sleep 1; }
pw() { log "power[$1]: $(dumpsys power | grep -iE 'mWakefulness=|groupId|DisplayGroup' | tr -s ' ' | tr '\n' ';' | cut -c1-400)"; c info; }
ID=""
for i in $(seq 1 40); do ID=$(grep DISPLAY_ID $O/helper.log | head -1 | cut -d' ' -f2); [ -n "$ID" ] && break; sleep 0.5; done
log "helper start: $(head -3 $O/helper.log)"
log "workspace id=$ID"
dumpsys display > $O/dumpsys_display.txt
log "-- launch Settings on the workspace"
am start -W --display $ID -n com.android.settings/.Settings >> $REP 2>&1
sleep 3
c "shot $O/1_ws_settings.png"
pw "before use display 0 at "
log "-- use display 0 at the same time (home, swipe up)"
input keyevent KEYCODE_WAKEUP; input keyevent KEYCODE_HOME; sleep 1
input -d 0 swipe 540 2000 540 600 300; sleep 1
screencap -p $O/2_panel.png
pw "before drive the workspa"
log "-- drive the workspace: tap and scroll"
input -d $ID swipe 540 1800 540 700 300 >> $REP 2>&1; sleep 1
c "shot $O/3_ws_scrolled.png"
input -d $ID tap 540 900 >> $REP 2>&1; sleep 2
c "shot $O/4_ws_tapped.png"
screencap -p $O/5_panel_after.png
pw "before keyboard inside t"
log "-- keyboard inside the workspace"
c "ime $ID 0"
input -d $ID keyevent KEYCODE_BACK; sleep 1
am start -W --display $ID -a android.settings.APP_SEARCH_SETTINGS >> $REP 2>&1
am start -W --display $ID -a com.android.settings.action.SETTINGS_SEARCH >> $REP 2>&1
sleep 2
input -d $ID text hello >> $REP 2>&1; sleep 2
c "shot $O/6_ws_typing.png"
dumpsys input_method > $O/dumpsys_input_method.txt
dumpsys window displays > $O/dumpsys_window_displays.txt
dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mTopFocusedDisplayId' >> $REP
pw "before panel locked, wor"
log "-- panel locked, workspace still driven"
input keyevent KEYCODE_SLEEP; sleep 2
input -d $ID keyevent KEYCODE_BACK >> $REP 2>&1; sleep 1
input -d $ID swipe 540 1800 540 700 300 >> $REP 2>&1; sleep 2
c "shot $O/7_ws_while_locked.png"
dumpsys window | grep -iE 'keyguard|isKeyguardShowing|mDreamingLockscreen' | head -5 >> $REP
input keyevent KEYCODE_WAKEUP
pw "before surface swap, uin"
log "-- surface swap, uinput, display association"
c swap; c uinput; c assoc; c info
sleep 4
dumpsys activity activities | grep -E 'Display #|ActivityRecord|mResumedActivity' | head -40 > $O/activities.txt
c quit; pkill -f "tail -f $D/cmd.txt"
sleep 2
log "== helper log"; cat $O/helper.log >> $REP
log "== display dump, workspace lines"; grep -iE 'claude-ws|mDisplayId|FLAG_' $O/dumpsys_display.txt | head -60 >> $REP
echo DONE >> $REP
