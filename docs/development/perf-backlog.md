# Performance backlog

See also the [off-device profile](offdevice-profile.md), which covers APK size and the
start-up path and lists some of the items below as well.

Written 2026-11-12 while looking for frame-rate, frame-pacing and time-to-game gains for
Slay the Spire 2 (app 2868840: Godot 4 .NET, Vulkan, ARM64 Proton with FEX) and for Steam
itself. Nothing here has been measured on a device yet. The first section lists the changes
that were made, each on its own `perf/*` branch. The rest lists ideas that were not
implemented, why each should help, why it was left, and how to measure it.

## Branches (unmeasured)

| branch | change | measure | expected |
|---|---|---|---|
| `perf/appinfo-skip` | the `appinfo.vdf` decoder builds only the sections it uses and stops reading a record once it has them | Steam start: time between the STEP lines "setting up the ARM64 Proton compatibility tool" and the next one | that step about 2× faster; seconds on large (50–150 MB) caches |
| `perf/appinfo-cache` | a complete decode saves each app's entry next to `appinfo.vdf`, keyed on the record's SHA-1 and change number; later reads decode only changed records | same step, second and later Steam starts; also CPU of `droiddeck-steam-compat` after Steam writes `appinfo.vdf` | 5–6× on unchanged records (0.44 s → 0.07 s on a synthetic 23 MB cache) |
| `perf/component-hashes` | the SHA-256 of each unpacked component file is kept next to it and reused while its size and mtime match; the session's `republish` no longer hashes every selected component file once per Proton build and scope | `session.created` to `guest.started` in the session's `events.jsonl`, with DXVK, VKD3D or FEX selected | tens to hundreds of ms per session start, more with several Proton builds |
| `perf/early-buses` | the system bus, session bus and `droiddeck-login1` start before the Steam setup helpers instead of after them | `start steam --wait` seconds | 0.3–1.5 s less, the start-up of a Python and GObject process plus two buses |

The two appinfo branches change the same function and will conflict when merged; they
compose (the cache makes unchanged records free, the skip makes changed ones cheaper).

## How to measure

Run each A/B on the same device, with the same Steam library and `appinfo.vdf`, screen on
(Doze makes everything about 7× slower) and the battery cooled, at least five runs each, and
compare medians.

- **Steam start:**
  `for i in 1 2 3 4 5; do tools/droiddeckctl stop --timeout 120; tools/droiddeckctl wait cool --below 38 --timeout 900; /usr/bin/time -f %e tools/droiddeckctl start steam --wait --timeout 240; done`.
  Then pull `session.log` and compare the `== STEP` timestamps around the setup helpers.
- **Game launch and pacing:** `tools/droiddeck-scenario tools/scenarios/slay-the-spire-2.json`.
  The `launch --wait` step's `seconds` is time-to-game; `fpsOnScreen`, `p99Ms` and
  `offCadencePercent` come from `pacing`. Its `start` step uses `--reuse`, so it does not time
  Steam's start.
- **Where proot spends the time:** `tools/droiddeckctl proot-profile enable`, run the case,
  then `tools/droiddeckctl proot-profile report ./session.log --sort wall`.

## Ideas not implemented

### Frame rate and pacing

**FEX memory ordering for .NET.** `dotnet_memory_ordering` in `droiddeck-game-env` gives every
game with `coreclr.dll` full TSO emulation: `FEX_TSOENABLED`, `FEX_VECTORTSOENABLED`,
`FEX_MEMCPYSETTSOENABLED` and `FEX_HALFBARRIERTSOENABLED`. Vector TSO puts barriers around
every SIMD load and store, and memcpy/memset TSO does the same for `rep movs`/`rep stos`.
These are the expensive options, and Slay the Spire 2's game logic is .NET. Nothing recorded
says which of the four the runtime needs. The risk is memory corruption in the GC, which shows
up as rare crashes, not as wrong frames, so it was not changed blind.
To test: set `FEX_VECTORTSOENABLED=0` (then also `FEX_MEMCPYSETTSOENABLED=0`) for 2868840 in
the environment editor, run the scenario for `fpsOnScreen`/`p99Ms`, then play 30 minutes
or more for stability. If it is CPU-bound, expect up to 10–30%.

**Live compatibility helper during a game.** `droiddeck-steam-compat` runs `reconcile()` each time
`appinfo.vdf`, `libraryfolders.vdf`, `request.json` or `games.json` changes. That covers every
Steam write of `appinfo.vdf`, also while a game runs. `ensure_tools()` already checks
`game_running()`, but `reconcile()` does not. Each reconcile decodes all of appinfo (cheap with
`perf/appinfo-cache`), lists the libraries, and makes two DevTools calls into the Steam UI
process.
Before deferring watcher-triggered reconciles until the game exits, count how often they
happen in a game. Add a timestamp to its `say()` lines, or watch the process with `top`
during `pacing`. If it happens, expect fewer `p99Ms` spikes in that window.

**Compositor keeps one frame in flight.** `render_impl` in `waylandcomp/src/vk_present.c` submits
the blit, presents, and then waits for its fence (`timed_wait(g_fence, UINT64_MAX)`) before
returning. The compositor thread is blocked while the GPU, which is busy with the game, gets
to the blit. Two frames in flight (a fence and command buffer per slot) would overlap that wait.
This only matters on the path that renders through `vkp_render`, not where frames go straight
to SurfaceControl layers. It needs device testing of frame generation and HDR, which share
this code.
Measure `offCadencePercent` and the HUD's present timings.

**Layer path waits for its own blit.** On the SurfaceControl layer path, `sc_layer_present`
calls `vkp_blit_image`, which copies the game's buffer into a layer slot. It then waits for its fence on
the CPU (`timed_wait(g_fence, 1 s)`) before `present_slot` hands the slot to Android. The thread
therefore blocks for the GPU's queue time on every frame. Exporting the blit's completion as a sync fd
and passing it as the layer buffer's acquire fence would remove the wait. Both of these then have
to follow that fence: the client buffer's Wayland release (`release_external_client_image`), and
the reuse of `g_cmds[0]` and `g_fence`, which needs per-slot command buffers. That is a change to
synchronisation that needs a device to validate.

**ADPF game-thread hints on more SoCs.** `GameThreadHints.kt` returns early unless
`Build.SOC_MODEL == "CQ8725S"` (Odin 3). Performance hints let the governor ramp up the
game's threads before a frame is late. On other Snapdragon devices, enable them through a
debug preference and compare `p99Ms` and the battery temperature.

**Per-thread status reads every 5 s.** For the first hour, the session's request loop (steam
mode) reads `/proc/<pid>/task/*/status` for every thread of Steam, its web helpers and
gamescope every 10 ticks. It does this with bash `read` loops to check CPU pinning, and it
runs on shared cores during play. It probably costs well under 1% of a core. Check it with
`top -H` in `droiddeckctl guest` before changing it.

### Steam start

**x86-64 client: `--sync` at every start.** With the x86-64 client, the session runs
`droiddeck-steam-install --sync steamdeck_publicbeta` before every start, and it can take up to
30 s. It is outside the ARM64 path that Slay the Spire 2 uses. A stamp of the last successful sync
per channel and client build would skip it.

**R8 for the release build.** `minifyEnabled false`: five dex files, 68.7 MiB uncompressed. Shrinking
mostly helps the app's own cold start and install size, not the guest. It needs keep rules for JNI
and reflection, and a device run to catch any missing one.

**One process for the setup helpers.** `droiddeck-steam-library`, `steam-compatibility`,
`droiddeck-steam-ui-scale` and `droiddeck-steam-shortcuts` run one after another as separate
Python processes, followed by the shell script `droiddeck-seed-redists`. Each Python process pays
interpreter start-up under proot (about 90 ms with the fast path, from the table in
[proot-performance](proot-performance.md)). The shortcuts helper also loads all of
`steam-compatibility` as a module. They can't simply run in parallel, because several of them
rewrite `config.vdf`, `libraryfolders.vdf` or `shortcuts.vdf`. Merging the Python ones into one
process saves about 0.2–0.4 s.

**PipeWire before gamescope.** `droiddeck-pipewire start` runs just before gamescope. It runs
`cksum` on its bundle, then polls for the socket with `sleep 0.1`, an exec per try. It could
start before `droiddeck-fonts` and the `wayland-info` loop and be waited for just before
gamescope, with `nap`. About 50–150 ms.

**`gamescope --version` for the log line.** The session runs the gamescope binary once just to
log its version. ld.so's library lookups are not covered by the fast path, so each costs a
proot round trip. Writing the version into the rootfs at build time saves an exec and a few
hundred traps, about 10–30 ms.

**PulseAudio set-up on the Android side.** `PulseAudioComponent.start()` runs a CRC over
`pulseaudio.tzst` (540 KB). `copyFromLibraryDir()` copies seven libraries out of the APK
through the class loader on every session start, even when they are unchanged. Stamping them on
the APK's identity, as `SessionFiles` does for the guest overlay, saves tens of milliseconds
on the session thread.

**Microphone stall (unverified).** An earlier note suspected a 30 s stall in Steam's
`GetVoiceMicrophoneVolume` when no capture source exists. PulseAudio always has the sinks'
monitor sources, so "no source" is not strictly true. Confirm it with a session log that shows
the stall before acting on it.

### Game launch

**Launch requests wait for a 0.5 s poll.** The session's request loop checks
`$BL_LAUNCH_DIR/steam-game` (and `steam-stop`) every `nap 0.5`, so `droiddeckctl launch` waits
about 250 ms on average before anything happens. A shorter nap, with the 5 s work kept on its
own counter, takes that to about 50 ms.

**Cold launch with a game URL polls once a second.** `steam_hold_launch` runs `xprop` every
second until Steam's UI has focus, then retries `droiddeck-agent --steam-ready` with `sleep 1`.
Reacting to `xprop -spy` (already used in the stretch watcher) removes up to a second.

**Component checks hash files on every launch.** `bl_components` in the compatibility tool's
script runs `sha256sum` over every selected component file in the Proton depot before each
launch, which is several MB of DXVK/VKD3D DLLs. A stamp of size and mtime per file, re-hashed
only when it changes, saves about 0.05–0.2 s when components are selected.

**Python compiled at every run.** The overlay's Python programs have no `.py` suffix, and helpers
load each other with `runpy.run_path`, so nothing is cached as bytecode. Compiling took 1–10 ms
per script on a desktop CPU, for example 9.5 ms for `droiddeck-game-env` and 9.1 ms for
`droiddeck-esync`. A game launch compiles about five of them, roughly 50–100 ms on a phone.
That is small, and changing it means a loader and cache invalidation, so it was left.

**Prefix DLL check on every launch.** `repair_foreign_system_dlls` lists `system32` and
`syswow64` in the prefix and stats each DLL that has a builtin. It reads the PE header only of
regular files, because Proton's own files are symlinks and are skipped. With the fast path a
stat costs about 1 µs, so this is milliseconds, not worth a stamp.

### Larger changes

From [proot-performance](proot-performance.md), still open:

- **A second proot for the game tree.** It measured 2.5× the trap throughput, and Steam/CEF
  bursts would stop stalling the game. It is a large change to how the session starts.
- **A proot-aware glibc.** It would cover ld.so, fts and nss path calls and remove the
  `SIGSYS` round trips for Android-blocked syscalls.

**gamescope patches.** Changes to gamescope itself, such as the WSI image count or the
limiter, need a rebuilt component, not an APK-only branch.
