P=/usr/local/share/droiddeck/native-engine.json
[ -f $P.lanner-orig ] || cp -a $P $P.lanner-orig
printf '%s' '{"tag":"native-engine-131","url":"https://github.com/renxida/DroidDeck/releases/download/native-engine-131/native-engine.tar.zst","sha256":"d2ef8136f9a715aa8515028818e72a85dfae4ff4f81c7c081188acca8f19f565","api":1}' > $P
cat $P; echo; echo PIN131
