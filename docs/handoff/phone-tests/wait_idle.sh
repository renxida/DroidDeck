cd /home/claude/droid-deck/wt-switchboard
while true; do
  s=$(timeout 200 python3 tools/lanner-remote state --wait 150 2>&1)
  r=$(echo "$s" | grep -o '"running": *[a-z]*' | head -1)
  echo "$(date -u +%H:%M) $r"
  echo "$r" | grep -q false && { echo IDLE; break; }
  sleep 240
done
