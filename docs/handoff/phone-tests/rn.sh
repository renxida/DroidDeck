#!/bin/sh
# rn.sh 'shell command' [timeout] : run on phone, print stdout text decoded
L=/home/claude/droid-deck/wt-switchboard/tools/lanner-remote
J=$(python3 -c 'import json,sys;print(json.dumps({"argv":["/bin/sh","-c",sys.argv[1]],"timeout":int(sys.argv[2])}))' "$1" "${2:-60}")
timeout 330 python3 $L native "$J" --wait 300 2>/dev/null | python3 -c '
import json,sys
t=sys.stdin.read()
try:
  d=json.loads(t[t.index("{"):])
  r=d.get("result",d)
  def walk(o):
    if isinstance(o,dict):
      for k,v in o.items():
        if k in ("text","stdout","stderr") and isinstance(v,str): print(v,end="")
        else: walk(v)
    elif isinstance(o,list):
      for v in o: walk(v)
  walk(r)
except Exception as e: print("PARSEFAIL",e,t[:500])'
