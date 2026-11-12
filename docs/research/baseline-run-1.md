# Baseline run 1: Slay the Spire 2 on a Pixel 10 (build bccccaf)

**Question.** Cedar ran baseline build bccccaf on a Pixel 10 (Tensor G5, PowerVR, system Vulkan
driver through libhybris) for about 30 minutes of Slay the Spire 2 (app 2868840). What do the
logs say about errors, slow phases, pauses and memory, which problems can be fixed without the
phone, and what must the next run record that this one did not?

Marks: **VERIFIED** = read in the run's bundle or in source; **INFERRED** = reasoned from verified
facts, not observed. Times are the phone's local clock; "+N s" counts from the session start in
`events.jsonl`.

## 1. Timeline (VERIFIED, events.jsonl and session.log)

| Phase | Time | Note |
|---|---|---|
| Runtime download, extract, Proton install | 12.6 s, 9.2 s, 17.8 s | first run |
| Steam client check | 39 s | `droiddeck-steam-install` fetched 362 MB |
| Steam updater | (inside the 182 s below) | re-downloaded 668 MB, restarted with rc=42 |
| Steam start to signed in | 182 s | includes Cedar typing credentials, 11:48:51-11:50:01 |
| Game exec | 11:55:07 (+555 s) | |
| Game window | 11:55:28-34 | |
| Main menu | 64.3 s after start | Godot: `'Common' preload` took 27.4 s |
| fps reaches 39 | about 130 s after the window | low fps for the first ~2 minutes |
| Thermal status 1 / 2 / 3 / 4 | 11:53:34 / 12:04:39 / 12:12:40 / 12:14:11 | status 4 about 19 min into the game, not 7 |
| fps 16-28 | minutes 20-30 | prime core capped to about 1.9-2.7 GHz |

Frame pauses over 1 s (frames.csv): 15 in total. 14 fall between +581 s and +722 s, that is the
game's first ~3 minutes. The 15th, at +845.7 s (11:59:58), is the screen-off suspend and resume
(`session.suspended` events), not a stall.

## 2. Findings, ranked by effect on this run

1. **Proton's exception tracing ran for the whole start-up. VERIFIED (cause), INFERRED (size of
   effect).** With `PROTON_LOG=1` and no `WINEDEBUG`, Proton sets
   `+timestamp,+pid,+tid,+seh,+unwind,+threadname,+debugstr,+loaddll,+mscoree`
   (ValveSoftware/Proton `proton_10.0`, `proton` 1618-1619). DroidDeck sets `PROTON_LOG=1`
   whenever game logs are on, and `BL_GAME_LOGS=1` is automatic on non-Adreno GPUs
   (`GraphicsReport.detailed`). The run's `steam-2868840.log` was about 20.9 MB (19,922,924 bytes
   cut from the bundle); 7820 of the 10,546 kept lines are `trace:unwind`. .NET throws and catches
   managed exceptions during start-up, and every frame unwound is formatted and written through
   FEX. The trace stops at Wine time 451506.8 (~11:57:16), the same time fps recovers. Correlation,
   not proof, but the coincidence and the volume make it the top suspect for the 14 pauses.
   **Fixed:** game logs now set a light `WINEDEBUG` when none is given.
2. **Steam's shader pre-caching compiled at the same time as the game. VERIFIED (overlap),
   INFERRED (waste).** `fossilize_replay` pids 30391-30397 ran ~11:55:00-11:56:40, overlapping the
   game's start and its first pauses. No "shader pre-caching" line was logged, so
   `BL_SHADER_PRECACHE` was unset and Steam's own default (on) applied. Seven replay processes
   took CPU from a game that was already stalling. Whether the phone's driver keeps any replayed
   pipeline where Godot would find it is unknown; Godot keeps its own pipeline cache, which
   perf/godot-render handles (INFERRED). **Fixed:** with the
   system Vulkan driver, pre-caching now defaults to off unless the user chose it in DroidDeck.
3. **Memory: the phone started the game with almost nothing free. VERIFIED.** At session start
   swap held 5498 MB of ~5.8 GB and 1.7 GB was available; the swap was full of other Android apps.
   `available_mb` reached 48 when the game reached 1855 MB. The zRAM page-out was skipped every
   30 s ("swap headroom too small"). droiddeck's own side was 990-1097 MB before the game and
   200-350 MB resident during it (paged out), but memory.csv had no per-process split, so what
   held it cannot be told. Not fixable in code here: perf/memory adds the low-memory-killer
   warning and trims; Cedar can close apps before a run. **Fixed (telemetry):** processes.csv.
4. **Thermal throttling explains the late fps drop. VERIFIED.** Status 4 at 12:14:11, fps 16-28
   afterwards. perf/sched (ADPF, thermal pacing) covers this; nothing added here.
5. **First-run-only costs. VERIFIED.** Steam downloaded its client twice (362 MB by DroidDeck's
   bootstrap, 668 MB by Steam's updater) because the bootstrap fetches only the `_all` and
   `_linuxarm64_linuxarm64` blocks, writes no `.installed` manifest and deletes the zips. The
   first launch used winepulse audio (52 ms sink latency) because DirectAudio needs the prefix
   (`droiddeck-proton: no prefix yet - DirectAudio takes effect the next time this game starts`).
   Godot did a first cloud save sync. Not fixed: both fixes touch first-run state whose format is
   Steam's or Proton's, and both cost only once.
6. **Godot errors. VERIFIED.** godot.log reports `Vulkan 1.3.0 - Forward+ - Using Device #0:
   ImgTec - PowerVR`, `VSync: Adaptive`, one `vkCreateGraphicsPipelines failed with error -13` in
   forward_clustered `_create_pipeline` (one pipeline missing; -13 is
   `VK_ERROR_UNKNOWN`), Sentry GDScript parse errors
   while `[Sentry.NET] Initialized` still ran, and ends with `WASAPI: Current output_device
   invalidated` at exit. The pipeline error is perf/godot-render's area; Sentry is set in the
   game's code.
7. **Benign or already fixed. VERIFIED.** Wine `err:nsi` bind errno 13, `err:hid` sdl_bus_init
   (no udev), `err:wineusb`, `err:ntoskrnl` winebth; gamescope "Failed to add waitable: Operation
   not permitted"; "No CAP_SYS_NICE"; pipewire rtkit; "oom_score_adj raise was dropped"; Steam
   shutdown `mmap() failed`; the `KeyboardInterrupt` traceback in run-proton.py at exit (normal
   SIGINT). The mangoapp `/sys/class/drm` crash loop and Godot accessibility are fixed on main.
8. **launch.txt shows the wrong layers. VERIFIED.** It lists Steam's
   `VK_INSTANCE_LAYERS=VK_LAYER_HYBRIS_compat:VK_LAYER_VALVE_rpo:VK_LAYER_VALVE_fdm_injection`,
   written before `system_graphics_launch` replaces them with
   `VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat`. Anyone reading the file is
   misled. **Fixed:** launch.txt now ends with the environment the game really gets.

## 3. What changed on test/baseline-fixes

| Change | File | Line logged on device |
|---|---|---|
| Light `WINEDEBUG` (`+timestamp,+pid,+tid,+threadname,+loaddll`) for game logs unless the user set one | `tools/linuxfs/overlay/usr/local/bin/droiddeck-game-env` 1352-1380 | `droiddeck-game-env: game log Wine channels ... (Proton's +seh,+unwind default stays off)` |
| `DROIDDECK_GAME_LOG_DIR` exported to the game | same | (in launch.txt) |
| launch.txt ends with `== final environment (...)` listing VK_, WINE*, DOTNET_, DXVK_, VKD3D*, PROTON_, FEX_, HYBRIS_, MESA_, GODOT*, SteamGameId | same, `record_final_env` | `== final environment` |
| Shader pre-caching off by default on the system Vulkan driver; a DroidDeck choice is remembered in `~/.config/droiddeck/shader-precache-choice` and wins | `droiddeck-steam-shaders` (`system_driver_default`), `droiddeck-session` 1058-1068 | `DROIDDECK_SHADER_PRECACHE off reason=system-vulkan-driver root=...` |
| Pre-caching state logged every session | `droiddeck-steam-shaders --state` | `== steam: shader pre-caching on\|off in ...` |
| processes.csv: every 60 s, each session process of >= 16 MB or >= 1 % CPU with RSS, swap and CPU % | `MemoryReadout.kt` (`MemoryLog.processLines`) | logcat `DROIDDECK_PROCESS_LOG file=processes.csv every_s=60 min_mb=16` |
| Telemetry carries processes.csv and Steam's `shader_log.txt`, `content_log.txt`, `compat_log.txt`, `bootstrap_log.txt` written since the session start (256 KB each, scrubbed) | `TelemetryBundle.kt` | `steam-logs/<arch>/` in the bundle |
| Merged test/telemetry-live (VmSwap columns, top process in memory.csv) | | |

Tests: `tools/tests/test_game_environment.py` (light channels, final environment),
`tools/tests/test_steam_shaders.py` (`--state`, system-driver default), `MemoryReadoutTest`
(CPU ticks, process rows), `TelemetryUploadTest` (processes.csv and Steam logs in the bundle).

Already captured before this branch (VERIFIED in `TelemetryBundle.evidence`): Wine log head and
tail, FEX faults via crash-lines, ApplicationExitInfo traces, Godot and other game logs found in
compatdata (godot.log), and dxvk/vkd3d logs under the game log folder. Which renderer Godot used
is in godot.log's `Vulkan ... - Forward+ - Using Device` line, now kept.

## 4. Experiment plan for run 2

| Hypothesis | Confirmed if | Refuted if |
|---|---|---|
| Unwind tracing caused the start-up pauses | Wine log < 2 MB, no `trace:unwind`; pauses > 1 s in the first 3 min drop from 14 to a few; fps reaches 39 well before 130 s | Same pauses with the light channels line present in session.log |
| Pre-caching competed with the game | `DROIDDECK_SHADER_PRECACHE off` in session.log, no `fossilize_replay` processes in processes.csv, no new entries in `steam-logs/*/shader_log.txt` | Pauses unchanged with both fixes in |
| droiddeck's ~1 GB before the game is steamwebhelper | processes.csv shows steamwebhelper rows summing to most of it, with CPU % during the game | Most of it is the Android app or gamescope |
| Memory pressure stalls the game | `available_mb` < 200 in memory.csv at the same times as pauses in frames.csv | Pauses with ample available memory |
| Late fps loss is thermal | fps drop follows thermal status 3-4 in events.jsonl | fps falls while status is 0-1 |

For a clean comparison, close other apps before the run so swap starts below half full, and
note it; this run started with swap full.
