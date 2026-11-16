#!/bin/bash
# Usage: host-test.sh <outdir>. Pushes the helper, runs the device test, pulls results.
set -x
OUT=${1:-results}; mkdir -p "$OUT"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
adb push proto/vd.dex proto/device-test.sh /data/local/tmp/
for run in "31945 full 1" "29897 nogroup 0"; do
  set -- $run
  adb shell sh /data/local/tmp/device-test.sh $1 $2 $3 || true
  mkdir -p "$OUT/$2"; adb pull /data/local/tmp/out/$2/. "$OUT/$2"/ || true
  echo "=================== REPORT $2 ==================="
  cat "$OUT/$2"/report.txt || true
done
