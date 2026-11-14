#!/usr/bin/env bash
# Run one Windows x86-64 test program under Wine + ARM64EC FEX and decide whether it hung.
#
#   run.sh <label> <outdir> <seconds> <program.exe>
#
# The program prints one "hb t=N ..." line a second and "done" at the end. No new heartbeat for
# STALL_S seconds (default 40) is a hang: every thread of the program's process is listed with its state,
# kernel wait channel and syscall, then gdb prints each thread's native stack, and the process tree is
# killed. Exit status: 0 done, 1 hang, 2 exited without "done".
set -uo pipefail
label=$1 out=$2 seconds=$3 exe=$4
stall=${STALL_S:-40}
base=$(basename "$exe")
mkdir -p "$out"
log="$out/$label.out" err="$out/$label.err" diag="$out/$label.hang.txt"
rm -f /dev/shm/fex-*-samples.csv
# Wine names the process after the program (15 characters, the kernel's limit).
pids() { pgrep -x "${base:0:15}" || pgrep -f "[\\/]$base" | while read -r p; do
  case $(cat "/proc/$p/comm" 2>/dev/null) in timeout|bash|run.sh) ;; *) echo "$p" ;; esac; done; }

start=$(date +%s)
timeout --signal=KILL $((seconds + 240)) wine "$exe" "$seconds" >"$log" 2>"$err" &
runner=$!

last_hb="" last_change=$start status=2
while kill -0 "$runner" 2>/dev/null; do
  sleep 2
  hb=$(grep -a '^hb ' "$log" | tail -1)
  now=$(date +%s)
  if [ "$hb" != "$last_hb" ]; then
    if [ -z "$last_hb" ]; then
      # Which emulator DLL the process really loaded, once it is up.
      for pid in $(pids); do
        echo "pid $pid maps: $(grep -o '[^/ ]*fex[^/ ]*$' "/proc/$pid/maps" 2>/dev/null | sort -u | tr '\n' ' ')" >>"$out/$label.maps"
      done
    fi
    last_hb=$hb last_change=$now
  elif [ $((now - last_change)) -ge "$stall" ] && [ $((now - start)) -ge "$stall" ]; then
    status=1
    {
      echo "HANG: no heartbeat for $((now - last_change)) s; last: ${last_hb:-none} (wall $((now - start)) s)"
      for pid in $(pids); do
        [ -r "/proc/$pid/status" ] || continue
        echo "== pid $pid: $(tr '\0' ' ' < "/proc/$pid/cmdline" | cut -c1-200)"
        grep -E '^(VmRSS|VmSwap|Threads)' "/proc/$pid/status"
        for t in /proc/"$pid"/task/*; do
          printf '%-8s %-16s %s wchan=%s syscall=%s\n' "$(basename "$t")" "$(cat "$t/comm" 2>/dev/null)" \
            "$(cut -d' ' -f3 "$t/stat" 2>/dev/null)" "$(cat "$t/wchan" 2>/dev/null)" "$(sudo cat "$t/syscall" 2>/dev/null | cut -d' ' -f1-3)"
        done
        echo "-- gdb"
        timeout 120 sudo gdb -p "$pid" -batch -nx -ex 'set pagination off' -ex 'info threads' \
          -ex 'thread apply all bt 24' 2>&1 | grep -v '^\[New LWP\|^warning: ' | head -3000
      done
      echo "-- wineserver threads"
      for pid in $(pgrep -x wineserver); do
        for t in /proc/"$pid"/task/*; do printf '%s %s wchan=%s\n' "$(basename "$t")" "$(cut -d' ' -f3 "$t/stat")" "$(cat "$t/wchan")"; done
      done
    } >"$diag" 2>&1
    for pid in $(pids); do kill -KILL "$pid"; done
    pkill -KILL -f "$base" || true
    break
  fi
done
wait "$runner" 2>/dev/null
grep -aq '^done' "$log" && status=0
cp /dev/shm/fex-*-samples.csv "$out/" 2>/dev/null || true
end=$(date +%s)
case $status in
  0) verdict=done ;;
  1) verdict=HANG ;;
  *) verdict="exited-without-done" ;;
esac
echo "$label: $verdict after $((end - start)) s ($(cat "$out/$label.maps" 2>/dev/null | head -1))"
echo "$label: last heartbeat: $(grep -a '^hb ' "$log" | tail -1)"
grep -a -i "fex\|Sampler\|NameWindowsMemory\|TrimIdleThreads\|err:" "$err" | tail -15 || true
exit $status
