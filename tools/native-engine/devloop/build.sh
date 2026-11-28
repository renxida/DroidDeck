#!/usr/bin/env bash
# Build the patched engine for this machine (x86_64, no C#) so canvas and RenderingDevice changes can be tried in
# minutes, off the phone and off CI. The patches touch only arch-neutral C++, so what runs here is what ships;
# CI still builds the arm64 mono template that the phone runs.
#
#   DEVLOOP_GODOT_SRC=<clean Godot 4.5.1 git checkout> tools/native-engine/devloop/build.sh
#
# Keeps a worktree of that checkout under DEVLOOP_WORK (default ~/.cache/droiddeck-devloop), resets the files the
# patches touch, re-applies every patch from this repo and rebuilds. SCons hashes contents, so only what a change
# really touches recompiles (one renderer unit and the link, about a minute). Prints the binary's path last.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
SRC=${DEVLOOP_GODOT_SRC:?set DEVLOOP_GODOT_SRC to a clean Godot 4.5.1 checkout}
W=${DEVLOOP_WORK:-$HOME/.cache/droiddeck-devloop}
G=$W/godot
mkdir -p "$W"
if [ ! -d "$G" ]; then
  git -C "$SRC" worktree add -q --detach "$G" HEAD
fi
cd "$G"
git checkout -q -- .
P=$REPO/tools/native-engine/patches
python3 "$P/rd_skip_redundant_set_binds.py" "$G" > /dev/null
python3 "$P/rd_pipeline_cache_save.py" "$G" > /dev/null
python3 "$P/main_rendering_method_env.py" "$G" > /dev/null
python3 "$P/logger_time_prefix.py" "$G" > /dev/null
python3 "$P/x11_steam_game.py" "$G" > /dev/null
python3 "$P/canvas_poly_batch.py" "$G" > /dev/null
python3 "$P/canvas_texture_slots.py" "$G" > /dev/null
python3 "$P/canvas_quad_merge.py" "$G" > /dev/null
python3 "$P/canvas_group_hoist.py" "$G" > /dev/null
python3 "$P/canvas_batch_census.py" "$G" > /dev/null
python3 "$P/canvas_reorder.py" "$G" > /dev/null
python3 "$P/canvas_trace.py" "$G" > /dev/null
export SCONS_CACHE=$W/scons-cache SCONS_CACHE_LIMIT=8192
scons platform=linuxbsd arch=x86_64 target=template_release module_mono_enabled=no \
  custom_modules="$REPO/tools/native-engine/modules" debug_symbols=no linker=lld scu_build=yes \
  opengl3=no wayland=no module_openxr_enabled=no -j"$(nproc)" 2>&1 | grep -E "error|Error|warning: unused|done building" || true
B=$G/bin/godot.linuxbsd.template_release.x86_64
test -x "$B"
echo "$B"
