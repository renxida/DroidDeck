#!/bin/bash
# Usage: host-test.sh <outdir>. Pushes the helper, runs the device test, pulls results.
set -x
OUT=${1:-results}; mkdir -p "$OUT"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
adb push proto/vd.dex proto/device-test.sh /data/local/tmp/
adb shell sh /data/local/tmp/device-test.sh || true
adb pull /data/local/tmp/out/. "$OUT"/ || true
echo "=================== REPORT ==================="
cat "$OUT"/report.txt || true
