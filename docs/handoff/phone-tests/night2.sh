# night.sh <apk-prefix>: gated on idle + charging; skip-intro boots, prestart (if APK matches), then resumetest
cd /tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad
L=/home/claude/droid-deck/wt-switchboard/tools/lanner-remote
R() { timeout 330 python3 $L "$@" --wait 300 2>/dev/null; }
WANT=$1; n=0
for i in $(seq 1 140); do
  p=$(R ping '{}'); c=$(echo "$p" | grep -o '"charging": [a-z]*' | cut -d' ' -f2); v=$(echo "$p" | grep -o '"short_commit": "[0-9a-f]*' | cut -d'"' -f4)
  ph=$(R state | grep -o '"phase": "[A-Z_]*"' | head -1)
  echo "chk $i $(date -u +%T) chg=$c apk=$v $ph"
  case "$v" in $WANT*) m=1;; *) m=0;; esac
  [ "$c" = true ] && [ $m = 1 ] && echo "$ph" | grep -q IDLE && n=$((n+1)) || n=0
  [ $n -ge 2 ] && break; sleep 300
done
[ $n -ge 2 ] || { echo "not-ready $(date -u +%T)"; exit 0; }
echo "ready $(date -u +%T) apk=$v"
# Cedar: sound always off during tests (their music, their sleep). Restored at the end.
gv() { R prefs "{\"op\":\"get\",\"file\":\"session\",\"key\":\"$1\"}" | python3 -c "import json,sys; print(str(json.load(sys.stdin)['result']['values'].get('$1','$2')).lower())"; }
GS=$(gv gameSound false); BS=$(gv bootMovieSound true); echo "sound was game=$GS boot=$BS"
sp() { R prefs "{\"op\":\"set\",\"file\":\"session\",\"key\":\"$1\",\"type\":\"bool\",\"value\":\"$2\"}" | grep -o '"ok": [a-z]*' | head -1; }
restore_sound() { sp gameSound "$GS"; sp bootMovieSound "$BS"; echo "sound restored game=$GS boot=$BS"; }
trap restore_sound EXIT
sp gameSound false; sp bootMovieSound false
[ "$(gv gameSound x)" = false ] && [ "$(gv bootMovieSound x)" = false ] || { echo "sound-not-off-abort"; exit 0; }

plugged() { R ping '{}' | grep -q '"charging": true'; }
sh boot3.sh
plugged || { echo "unplugged-stop $(date -u +%T)"; exit 0; }
case "$v" in $WANT*) sh prestarttest.sh;; *) echo "prestart skipped: apk $v";; esac
R stop '{}' >/dev/null; sleep 10

R env '{"op":"clear","scope":"next"}' >/dev/null
case "$v" in $WANT*) sh resumetest.sh $WANT;; *) echo "resumetest skipped: apk $v";; esac
R stop '{}' >/dev/null; echo "night-done $(date -u +%T)"
