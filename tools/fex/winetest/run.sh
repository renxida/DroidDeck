#!/usr/bin/env bash
# Run one Windows x86-64 test program under Wine + ARM64EC FEX and decide whether it hung.
#
#   run.sh <label> <outdir> <seconds> <program.exe>
#
# The program prints one "hb t=N ..." line a second and "done" at the end. No new heartbeat for
# STALL_S seconds (default 40) is a hang: every thread of the program's process is listed with its state,
# kernel wait channel and syscall, then gdb prints each thread's native stack, and the process tree is
# killed. Exit status: 0 done, 1 hang, 2 exited without "done".
#
# Another program's progress lines: HB_RE (default '^hb '), DONE_RE (default '^done'), OPS_KEY (the
# "<key>=N" count on the last progress line; default ops) and PROG_ARGS (its arguments; default <seconds>).
# The Godot stand-in: HB_RE='^(STANDIN_DEPS|FIRST_FRAME|MAINLOOP_OK)' DONE_RE='^MAINLOOP_OK' OPS_KEY=frames.
set -uo pipefail
label=$1 out=$2 seconds=$3 exe=$4
stall=${STALL_S:-40}
hb_re=${HB_RE:-'^hb '} done_re=${DONE_RE:-'^done'} ops_key=${OPS_KEY:-ops}
read -r -a prog_args <<< "${PROG_ARGS:-$seconds}"
base=$(basename "$exe")
mkdir -p "$out"
log="$out/$label.out" err="$out/$label.err" diag="$out/$label.hang.txt"
rm -f /dev/shm/fex-*-samples.csv
# Wine names the process after the program (15 characters, the kernel's limit).
pids() { pgrep -x "${base:0:15}" || pgrep -f "[\\/]$base" | while read -r p; do
  case $(cat "/proc/$p/comm" 2>/dev/null) in timeout|bash|run.sh) ;; *) echo "$p" ;; esac; done; }

start=$(date +%s)
timeout --signal=KILL $((seconds + 240)) wine "$exe" "${prog_args[@]}" >"$log" 2>"$err" &
runner=$!

last_hb="" last_change=$start status=2
while kill -0 "$runner" 2>/dev/null; do
  sleep 2
  hb=$(grep -aE "$hb_re" "$log" | tail -1)
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
      # Windows-side stacks: winedbg attaches to the hung process (Wine pid from its process list) and walks every
      # thread; the test FEX DLL is built with debug info so its frames have names.
      echo "-- winedbg"
      wpid=$(WINEDEBUG=-all timeout 30 winedbg --command "info proc" 2>/dev/null | grep -F "$base" | grep -oE '[0-9a-f]{8}' | head -1 || true)
      if [ -n "$wpid" ]; then
        printf 'attach 0x%s\nbt all\ndetach\n' "$wpid" > "$out/$label.wdbg"
        WINEDEBUG=-all timeout 120 winedbg --file "$out/$label.wdbg" 2>&1 | grep -v '^fixme:\|^[0-9a-f]*:fixme' | head -1500
      else
        echo "no Wine pid for $base"
      fi
      echo "-- wineserver threads"
      for pid in $(pgrep -x wineserver); do
        for t in /proc/"$pid"/task/*; do printf '%s %s wchan=%s\n' "$(basename "$t")" "$(cut -d' ' -f3 "$t/stat")" "$(cat "$t/wchan")"; done
      done
    } >"$diag" 2>&1
    for pid in $(pids); do kill -KILL "$pid"; done
    wineserver -k 2>/dev/null || true
    break
  fi
done
wait "$runner" 2>/dev/null
grep -aqE "$done_re" "$log" && status=0
cp /dev/shm/fex-*-samples.csv "$out/" 2>/dev/null || true
end=$(date +%s)
case $status in
  0) verdict=done ;;
  1) verdict=HANG ;;
  *) verdict="exited-without-done" ;;
esac
echo "$label: $verdict after $((end - start)) s ($(cat "$out/$label.maps" 2>/dev/null | head -1))"
echo "$label: last heartbeat: $(grep -aE "$hb_re" "$log" | tail -1)"
# One machine-readable line for the gate: verdict, wall seconds, last ops count, seconds that made no progress, and
# the seconds the last heartbeat reports (t=, the time the ops took; wall seconds when the program has no t=).
ops=$(grep -aE "$hb_re" "$log" | tail -1 | grep -oE "$ops_key=[0-9]+" | cut -d= -f2)
busy=$(grep -aE "$hb_re" "$log" | tail -1 | grep -oE "(^| )t=[0-9]+" | cut -d= -f2)
echo "VERDICT $label $verdict $((end - start)) ${ops:-0} $(grep -ac 'STALLED' "$log") ${busy:-$((end - start))}"
grep -a -i "fex\|Sampler\|NameWindowsMemory\|TrimIdleThreads" "$err" | tail -10 || true
for csv in "$out"/fex-*-samples.csv; do
  [ -f "$csv" ] || continue
  echo "$label: sampler meta $(basename "$csv"):"; grep -a ',meta,' "$csv" | cut -d, -f1,7,9 | tr '\n' ' '; echo
  echo "$label: sampler top rows:"; grep -av ',meta,' "$csv" | head -15
done
if [ $status = 2 ]; then
  # The crash itself, before winedbg's process list and dbghelp's noise.
  echo "-- crash (stdout)"; grep -a -m1 -A40 'Unhandled exception\|^Backtrace\|Exception\|Unhandled' "$log" || head -60 "$log"
  echo "-- errors (stderr)"; grep -a 'err:\|seh:\|Unhandled\|exception\|EXCEPTION' "$err" | grep -av 'fixme\|dbghelp' | head -60
  echo "-- stderr tail (without display noise)"; grep -av 'wgl:\|winediag\|systray\|explorer\|fixme:dbghelp' "$err" | tail -40
fi
exit $status
