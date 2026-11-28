# keep lanner-pin-game running in each session (sessions kill leftover processes at start)
cd /tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad
i=0; while [ $i -lt 110 ]; do i=$((i+1))
  o=$(timeout 100 sh rn.sh 'if DISPLAY=:0 xprop -root GAMESCOPE_PID >/dev/null 2>&1; then n=$(ps -eo args | grep -c "[s]h /root/lanner-pin-game.sh"); if [ "$n" = 0 ]; then setsid nohup sh /root/lanner-pin-game.sh >/dev/null 2>&1 < /dev/null & echo rearmed; else echo ok; fi; else echo nosession; fi' 60 | tail -1)
  [ "$o" = rearmed ] && echo "$(date -u +%T) rearmed"
  sleep 55
done
