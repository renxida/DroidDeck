#!/usr/bin/env bash
# Fetch the newest Godot stand-in game (artifact native-standin-win-x64 from native-startup-gate.yml:
# Godot 4.5.1 mono, win-x64, ReadyToRun, NuGet deps, a Windows-only GDExtension) into <dest>.
#
#   get-standin.sh <dest>      (needs GH_TOKEN with actions: read)
#
# The artifact expires 30 days after its run; with none left this warns and exits 0, and the Godot row is skipped.
set -euo pipefail
dest=$1
repo=${GITHUB_REPOSITORY:?}
run=$(gh api "repos/$repo/actions/artifacts?name=native-standin-win-x64&per_page=20" \
  --jq '[.artifacts[] | select(.expired | not)] | sort_by(.created_at) | last | .workflow_run.id // empty')
if [ -z "$run" ]; then
  echo "::warning::no native-standin-win-x64 artifact left; the Godot row is skipped"
  exit 0
fi
gh run download "$run" -R "$repo" -n native-standin-win-x64 -D "$dest"
echo "stand-in from run $run: $(ls "$dest" | tr '\n' ' ')"
test -f "$dest/StandIn.exe"
