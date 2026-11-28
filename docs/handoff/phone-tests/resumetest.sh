# resumetest.sh <apk-commit-prefix> : waits for the phone idle on the charger, then measures a cold launch
# and freeze/resume of a running game. Logs to stdout.
cd /tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad
L=/home/claude/droid-deck/wt-switchboard/tools/lanner-remote
WANT=$1
R() { timeout 200 python3 $L "$@" --wait 150 2>/dev/null; }
pg() { R ping '{}'; }
n=0
for i in $(seq 1 40); do
  p=$(pg); c=$(echo "$p" | grep -o '"charging": [a-z]*' | cut -d' ' -f2); v=$(echo "$p" | grep -o '"short_commit": "[0-9a-f]*' | cut -d'"' -f4)
  ph=$(R state | grep -o '"phase": "[A-Z_]*"' | head -1)
  echo "chk $i $(date -u +%T) chg=$c apk=$v $ph"
  ok=1; case "$v" in $WANT*) ;; *) ok=0;; esac
  echo "$ph" | grep -q IDLE || ok=0
  [ "$c" = true ] && [ $ok = 1 ] && n=$((n+1)) || n=0
  [ $n -ge 2 ] && break; sleep 300
done
[ $n -ge 2 ] || { echo "not-ready $(date -u +%T)"; exit 0; }
echo "ready $(date -u +%T)"
b=$(sh rn.sh "$(cat backup.sh)" 120 | tail -1); echo "$b"
case "$b" in BACKUP*) ;; *) echo "no-backup-abort"; exit 0;; esac
R prefs '{"op":"set","file":"session","key":"suspendPolicy.steam","type":"string","value":"native"}' | grep -o '"ok": [a-z]*' | head -1
R prefs '{"op":"get","file":"session","key":"suspendPolicy.steam"}' | grep -o '"value": "[a-z]*"'
t0=$(date +%s)
R start '{"appId":"2868840"}' | grep -o '"ok": [a-z]*' | head -1
# cold launch: wait for the game to own focus, then settle
for i in $(seq 1 30); do
  sleep 10
  f=$(sh rn.sh 'DISPLAY=:0 xprop -root GAMESCOPE_FOCUSED_APP 2>/dev/null' 30 | tail -1)
  echo "$(( $(date +%s) - t0 ))s focus: $f"
  echo "$f" | grep -q 2868840 && break
done
sleep 60
R screenshot '{}' >/dev/null; ls -t *.jpg | head -1 | sed 's/^/menu-shot /'
LOG=$(R state | grep -o '"logDir": "[^"]*' | cut -d'"' -f4); echo "logdir $LOG"
ev() { sh rn.sh "grep -E 'suspend|resum|freeze|paged|stuck' $LOG/events.jsonl | tail -${1:-8} | cut -c1-330" 60; }
game_cpu() { sh rn.sh 'p=$(pgrep -f "[s]ts2 --main-pack" | head -1); [ -n "$p" ] && { a=$(awk "{print \$14+\$15}" /proc/$p/stat); sleep 3; b=$(awk "{print \$14+\$15}" /proc/$p/stat); echo "game pid $p state $(awk "{print \$3}" /proc/$p/stat) cpu_ticks_3s $((b-a)) rss_kb $(grep VmRSS /proc/$p/status | tr -s " " | cut -d" " -f2) swap_kb $(grep VmSwap /proc/$p/status | tr -s " " | cut -d" " -f2)"; } || echo "no game process"' 60; }
game_cpu
for hide in 30 300; do
  echo "== hide $hide s at $(date -u +%T)"
  R hide '{}' | grep -o '"ok": [a-z]*' | head -1
  sleep $hide
  ev 4; game_cpu
  sh rn.sh 'for p in $(pgrep -f dev.renxida.kestrel); do echo "adj $p $(cat /proc/$p/oom_score_adj 2>/dev/null)"; done | head -4; grep -E "SwapFree|MemAvailable" /proc/meminfo' 30
  t=$(date +%s%3N); R wake '{}' | grep -o '"ok": [a-z]*' | head -1; echo "wake took $(( $(date +%s%3N) - t )) ms round trip"
  sleep 4
  ev 3; game_cpu
  R screenshot '{}' >/dev/null; ls -t *.jpg | head -1 | sed 's/^/after-resume-shot /'
done
# standby: the game closes, Lanner loads it again out of sight and freezes it at the menu
R prefs '{"op":"set","file":"session","key":"standbyAfterGame","type":"bool","value":"true"}' | grep -o '"ok": [a-z]*' | head -1
echo "== standby: closing the game at $(date -u +%T)"
sh rn.sh 'p=$(pgrep -f "[s]ts2 --main-pack" | head -1); kill -TERM $p && echo "sent TERM to $p"' 30
sleep 170
ev 6; game_cpu
t=$(date +%s)
R start '{"appId":"2868840"}' | grep -o '"ok": [a-z]*' | head -1
for i in $(seq 1 20); do
  sleep 3
  f=$(sh rn.sh 'DISPLAY=:0 xprop -root GAMESCOPE_FOCUSED_APP 2>/dev/null' 30 | tail -1)
  echo "$(( $(date +%s) - t ))s after Play: $f"
  echo "$f" | grep -q 2868840 && break
done
ev 4; game_cpu
R screenshot '{}' >/dev/null; ls -t *.jpg | head -1 | sed 's/^/standby-play-shot /'
echo "resumetest-done $(date -u +%T)"
