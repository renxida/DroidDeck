# bootab.sh: cold boots to menu with engine boot switches (no gate; caller gates)
cd /tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad
L=/home/claude/droid-deck/wt-switchboard/tools/lanner-remote
R() { timeout 330 python3 $L "$@" --wait 300 2>/dev/null; }
b=$(sh rn.sh "$(cat backup.sh)" 120 | tail -1); echo "$b"
case "$b" in BACKUP*) ;; *) echo "no-backup-abort"; exit 0;; esac
plugged() { sh rn.sh 'D=/data/user/0/dev.renxida.kestrel/files/logs; S=$D/$(ls -t $D | head -1); tail -1 $S/power.csv | cut -d, -f8' | tail -1; }
B='"DROIDDECK_NATIVE_LOG_TIME=1","DROIDDECK_NATIVE_PIPELINE_CENSUS=1","DROIDDECK_NATIVE_PROF=250","DROIDDECK_NATIVE_PROF_WINDOW=3"'
boot() { tag=$1; envl=$2
  echo "=== $(date -u +%T) boot $tag"
  R stop '{}' >/dev/null; sleep 15
  R env '{"op":"clear","scope":"next"}' >/dev/null
  R env "{\"op\":\"set\",\"lines\":[$envl]}" | grep -o '"ok": [a-z]*' | head -1
  t0=$(date +%s); R start '{"appId":"2868840"}' | grep -o '"ok": [a-z]*' | head -1
  for k in $(seq 1 40); do sleep 5
    f=$(sh rn.sh 'DISPLAY=:0 xprop -root GAMESCOPE_FOCUSED_APP 2>/dev/null' 30 | tail -1)
    echo "$f" | grep -q 2868840 && { echo "focus at $(( $(date +%s) - t0 ))s"; break; }
  done
  sleep 75
  R wake '{}' >/dev/null; sleep 3
  R screenshot '{}' >/dev/null; echo "menu-shot $(ls -t *.jpg | head -1)"
  [ "$(plugged)" = none ] && { echo UNPLUGGED; return 1; }
  sh rn.sh 'D=/data/user/0/dev.renxida.kestrel/files/logs; s=$(ls -t $D | head -1); echo session $s; grep -E "^== STEP" $D/$s/session.log | tail -20; grep -rhE "^\[[0-9]+\.[0-9]+\] |native-engine-1|pipeline cache|census|prof |native_compat: (prof|census)|vkcount|Project FPS|prestart" $D/$s/native-engine.log 2>/dev/null | cut -c1-400 | head -400; head -2 $D/$s/frames.csv' 120 > boot3-$tag-$(date +%H%M).txt
  wc -l boot3-$tag-*.txt | tail -1
}
evs() { sh rn.sh 'D=/data/user/0/dev.renxida.kestrel/files/logs; s=$(ls -t $D | head -1); grep -E "session.created|session.ready|steam.ui_first_frame|game.engine|\"focusedApp\":2868840" $D/$s/events.jsonl | head -6 | cut -c1-160; grep -hE "Time to main menu|boot window|prof other|skip|IntroLogo|Preloading" $D/$s/native-engine.log | cut -c1-500 | head -250' 90; }
arm() { t=$1; e=$2; boot $t "$e" || return 1; evs >> $(ls -t boot3-$t-*.txt | head -1); }
P='"DROIDDECK_NATIVE_LOG_TIME=1"'
arm stock "$P" || exit 0
arm fps30 "$P,\"DROIDDECK_NATIVE_BOOT_MAX_FPS=30\"" || exit 0
arm fps30s4 "$P,\"DROIDDECK_NATIVE_BOOT_MAX_FPS=30\",\"DROIDDECK_NATIVE_BOOT_TIME_SCALE=4\"" || exit 0
arm prof "$P,\"DROIDDECK_NATIVE_PROF=250\",\"DROIDDECK_NATIVE_PROF_WINDOW=1\",\"DROIDDECK_NATIVE_PROF_OTHER=1\",\"DOTNET_PerfMapEnabled=3\"" || exit 0
arm stock2 "$P" || exit 0
R stop '{}' >/dev/null; sleep 15
R env '{"op":"clear","scope":"next"}' | grep -o '"ok": [a-z]*' | head -1
