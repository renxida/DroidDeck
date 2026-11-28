#!/bin/sh
# setfps.sh N [vsync] : stop game, set fps_limit (and vsync) in Steam profile settings.save, start, wait, Continue
S=/tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad; cd $S
L=/home/claude/droid-deck/wt-switchboard/tools/lanner-remote
R() { timeout 330 python3 $L "$@" --wait 300 2>/dev/null; }
R stop '{}' >/dev/null; sleep 20
V=${2:-adaptive}
sh rn.sh "python3 -c \"import json;f='/root/.local/share/SlayTheSpire2/steam/76561198116154904/settings.save';d=json.load(open(f));d['fps_limit']=$1;d['vsync']='$V';json.dump(d,open(f,'w'),indent=2);print('set',d['fps_limit'],d['vsync'])\""
R start '{"appId":"2868840"}' | grep -o '"ok": [a-z]*' | head -1
sleep 100
sh tap.sh 555 350 12
