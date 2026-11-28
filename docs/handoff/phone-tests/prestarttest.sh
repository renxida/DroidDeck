# prestarttest.sh: one cold Play with DROIDDECK_NATIVE_PRESTART=1 vs one without; focus timing, held window, prestart lines
cd /tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad
L=/home/claude/droid-deck/wt-switchboard/tools/lanner-remote
R() { timeout 330 python3 $L "$@" --wait 300 2>/dev/null; }
plugged() { sh rn.sh 'D=/data/user/0/dev.renxida.kestrel/files/logs; S=$D/$(ls -t $D | head -1); tail -1 $S/power.csv | cut -d, -f8' | tail -1; }
for tag in cold prestart; do
  echo "=== $(date -u +%T) $tag"
  R stop '{}' >/dev/null; sleep 15
  R env '{"op":"clear","scope":"next"}' >/dev/null
  E='"DROIDDECK_NATIVE_LOG_TIME=1"'; [ $tag = prestart ] && E="$E,\"DROIDDECK_NATIVE_PRESTART=1\""
  R env "{\"op\":\"set\",\"lines\":[$E]}" | grep -o '"ok": [a-z]*' | head -1
  t0=$(date +%s); R start '{"appId":"2868840"}' | grep -o '"ok": [a-z]*' | head -1
  shot=0
  for k in $(seq 1 60); do sleep 3
    f=$(sh rn.sh 'DISPLAY=:0 xprop -root GAMESCOPE_FOCUSED_APP 2>/dev/null' 30 | tail -1 | sed 's/.*= *//')
    el=$(( $(date +%s) - t0 ))
    if [ "$f" = 769 ] && [ $shot = 0 ]; then shot=1; R screenshot '{}' >/dev/null; echo "steam-ui at ${el}s shot $(ls -t *.jpg | head -1)"; fi
    echo "$f" | grep -q 2868840 && { echo "game focus at ${el}s"; break; }
  done
  sleep 60; R wake '{}' >/dev/null; sleep 3
  R screenshot '{}' >/dev/null; echo "menu-shot $(ls -t *.jpg | head -1)"
  [ "$(plugged)" = none ] && { echo UNPLUGGED; break; }
  sh rn.sh 'D=/data/user/0/dev.renxida.kestrel/files/logs; s=$(ls -t $D | head -1); echo session $s; grep -E "^== (STEP|prestart)" $D/$s/session.log | tail -25; grep -hE "prestart|STEAM_GAME|Project FPS|native-engine-1" $D/$s/native-engine.log 2>/dev/null | cut -c1-300 | head -20; L=/data/user/0/dev.renxida.kestrel/files; find / -path "*/native-prestart.log" 2>/dev/null | head -1 | xargs -r tail -5' 120 > prestart-$tag-$(date +%H%M).txt
  grep -E "prestart|STEAM_GAME|STEP" prestart-$tag-*.txt | tail -12
done
R env '{"op":"clear","scope":"next"}' >/dev/null
echo "prestarttest-done $(date -u +%T)"
