# Engine devloop: try a renderer change in under a minute, off the phone

```
export DEVLOOP_GODOT_SRC=<clean Godot 4.5.1 checkout> DEVLOOP_WORK=<cache dir>
B=$(tools/native-engine/devloop/build.sh | tail -1)      # first build ~14 min on 4 cores, then ~25 s
tools/native-engine/devloop/run.sh "$B"                   # synthetic scene, every arm, ~10 s
REPLAY_TRACE=<file.trace> DEVLOOP_SCREEN=2560x1440 tools/native-engine/devloop/run.sh "$B" tools/native-engine/replay
```

- `build.sh` builds an x86_64 template (no C#) from a worktree of the checkout with every patch in `patches/`
  applied. The patches are arch-neutral C++, so this is the code the phone runs; CI still builds the arm64 one.
- `run.sh` runs stock (twice), reorder, poly and both in parallel on lavapipe with the phone's Vulkan call
  counter (`tools/linuxfs/vkcount-layer`), then `batchtest/compare.py` prints per-arm draws, set binds, passes and
  barriers per steady frame, a phone main-thread estimate (37 us per draw, 25 us per set bind, Nov 24 slopes),
  and which pixels moved. Lavapipe's counts match CI's exactly (Nov 24: 164/92/164/70 draws, same pixels).
- A real scene: run the engine with `DROIDDECK_NATIVE_CANVAS_TRACE=<dir>` (patches/canvas_trace.py) and replay a
  trace with `replay/`. A trace holds layout only (item order, rects, texture ids and sizes, polygon bounds and
  triangle counts), never pixels, vertices or text. Traces stay out of this repo, CI and logs.
  Replaying the synthetic scene's own trace gives 163 draws for its 164 and the same reorder result.
