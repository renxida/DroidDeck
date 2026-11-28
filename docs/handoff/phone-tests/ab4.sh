#!/bin/sh
cd /tmp/claude-0/-home-user/40a69eab-007f-593e-8880-23a16be83a56/scratchpad
for c in "30 adaptive" "45 adaptive" "120 adaptive"; do
  echo "=== $(date -u +%T) cond $c"; sh setfps.sh $c | tail -1; sh fight3.sh | tail -6
done
echo ab4-done
