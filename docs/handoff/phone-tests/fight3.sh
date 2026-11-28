#!/bin/sh
# fight2.sh TAG : 30 s settle, 4 End Turns 25 s apart, then fp.sh window stats
S=/tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad; cd $S
sleep 30; echo "$(date +%T) settle"
for i in 1 2 3 4; do sh tap.sh 943 467 25 >/dev/null; echo "$(date +%T) endturn $i"; done
sh rn.sh "$(cat fp.sh)" 90
echo "$(date +%T) fight-done"
