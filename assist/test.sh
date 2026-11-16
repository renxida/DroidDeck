#!/bin/bash
# Usage: assist/test.sh <outdir>. Installs the test assistant and checks it can hold the assistant role.
set -x
OUT=${1:-results/assist}; mkdir -p "$OUT"; R=$OUT/report.txt
adb wait-for-device; adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
{
echo "== build"; adb shell getprop ro.build.fingerprint
echo "== assistant before"; adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell settings get secure assistant; adb shell settings get secure voice_interaction_service
echo "== install"; adb install -r assist/claude-assist.apk
echo "== qualifies?"; adb shell cmd role add-role-holder android.app.role.ASSISTANT claude.assist; echo "exit=$?"
sleep 3
echo "== assistant after"; adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell settings get secure assistant; adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction | head -40
adb logcat -c
echo "== KEYCODE_ASSIST"; adb shell input keyevent KEYCODE_ASSIST; sleep 4; adb logcat -d -s ClaudeAssist:I
adb shell input keyevent KEYCODE_BACK; sleep 1; adb logcat -c
echo "== long-press power (power_button_long_press=5 means assistant)"
adb shell settings get global power_button_long_press
adb shell settings put global power_button_long_press 5
adb shell input keyevent --longpress KEYCODE_POWER; sleep 4; adb logcat -d -s ClaudeAssist:I
adb shell input keyevent KEYCODE_BACK; sleep 1; adb logcat -c
echo "== voiceinteraction show"; adb shell cmd voiceinteraction show; sleep 4; adb logcat -d -s ClaudeAssist:I
adb shell screencap -p /data/local/tmp/assist.png; adb pull /data/local/tmp/assist.png $OUT/
echo "== VIS log since boot"; adb logcat -d -b all | grep -iE 'ClaudeAssist|claude.assist' | tail -30
echo "== hotword permission"; adb shell dumpsys package claude.assist | grep -iE 'HOTWORD|RECORD_AUDIO' ; adb shell pm grant claude.assist android.permission.CAPTURE_AUDIO_HOTWORD; echo "grant_exit=$?"
} > $R 2>&1
cat $R
