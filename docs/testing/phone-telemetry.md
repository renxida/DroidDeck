# Phone telemetry: capture, upload, compare

Every session records the same telemetry in every build, whatever the HUD shows, so two APKs can be
compared from their logs alone. Nothing needs adb or copying by hand.

## What a session records

All in the session's log folder; the share-logs zip (`DroidDeck-<session>-session.zip`) always
carries them under `<session>/`.

| File | Contents |
| --- | --- |
| `build.txt` | build label, branch, commit, upload slug, device, SoC, Android, GPU, Vulkan driver |
| `events.jsonl` | the timeline: one JSON object per event, `t` in epoch ms. Marks made before the session (`app.process_start`, `frontend.first_frame`, `setup.*` downloads) are written into the next session with their own times and `before_session: true` |
| `frames.csv` | every frame gamescope commits: `t_ms,frametime_us`, `t_ms` from the session start on the monotonic clock (`t0_wall_ms` in the first line maps it to wall time). `# t_ms=... engine=... pid=... steam=0/1` lines mark focus changes; capped at 32 MB |
| `clocks.log` | every 2 s: CPU policy cur/max clocks (`(of N)` when capped below the hardware maximum), GPU clock if readable, Android thermal status and headroom, battery temperature. The `start:` line gives wall time and time zone |
| `memory.csv` | every 5 s: used/available/swap, game and app-tree resident memory, app RSS, GPU (KGSL) |
| `session.log` | `== STEP HH:MM:SS.mmm` lines (UTC), `DROIDDECK_TIMELINE event=game_exec ... wall_ms=` when a game process starts, and everything the guest prints (the arm64 Steam and its games) |

Timeline events: `app.process_start`, `frontend.first_frame`, `session.created`, `service.started`,
`runtime.installing`/`runtime.ready` (with `setup.runtime.download|verify|extract|extracted`),
`proton.installing`/`proton.ready`, `compositor.starting`/`started`, `guest.starting`/`started`,
`steam.ui_first_frame`, `steam.signed_in`, `steam.game_launch_requested`, `game.first_frame`,
`frames.focus`, `game.exited`, `app.exit`, `session.stopping`/`stopped`.

## Crashes

`game.exited` is written when the focused game's process (the pid gamescope reports) leaves
`/proc`, or when a launch prints crash output and never shows a window. It carries `app_id`, `pid`,
`engine`, `run_s`, `how`, `abnormal` and up to 12 `evidence` lines from guest stderr: FEX's
`droiddeck-fex: SIGILL at ...` report (libfaultreport.so writes it to the game's stderr, which
lands in `session.log` for the arm64 Steam and in `root/.droiddeck-x64/steam-x64.log` for the
x86_64 Steam), Wine's `Unhandled page fault`, .NET's `Unhandled exception`, Godot's `Program
crashed`, `Segmentation fault`. An exit `status`/`signal` is only known where `droiddeck-game-env`
waits for the game (enhanced diagnostics, or a launch with a cleanup step), which prints
`DROIDDECK_TIMELINE event=game_exit app_id= status= signal= run_ms= wall_ms=`; the plain launch
execs Proton, so nothing is left to see its status. An exit is abnormal with a nonzero status, a
signal, or crash output since its launch, and then the session is uploaded at once (at most every
30 s) instead of waiting for the next checkpoint.

At each app start, Android's record of how the app's own processes ended (ApplicationExitInfo,
Android 11+) becomes `app.exit` events (`reason`, `abnormal`, `status`, `description`, `process`,
`pss_kb`, `rss_kb`) in the session folder that was being written when the process died (otherwise
in the next session); an ANR's thread dump is saved beside it as `app-exit-<ms>-anr.txt`.

## Automatic upload (one-time setup)

1. On GitHub, create a private repository (default name `renxida/kestrel-telemetry`). It may stay
   empty: the first upload names no branch, so GitHub creates the default branch with that commit,
   and later uploads go to the default branch.
2. Settings > Developer settings > Fine-grained personal access tokens > Generate: repository access
   "Only select repositories" with that repository, permission Contents: Read and write, nothing else.
3. In the app: Setup > Diagnostics > Telemetry upload. Turn it on, check the repository, paste the
   token, Save. The token stays in app-private storage (`no_backup/telemetry/token`); it is never
   logged, shared in log bundles or written to a report.

Each session then uploads `runs/<branch>-<commit>/<Manufacturer-Model>/<start UTC>.zip`: a
checkpoint every 5 minutes and right after an abnormal game exit (overwriting the same file), and
the full bundle when the session ends.

The zip holds, at its root: `upload.txt` (session, kind, path), `build.txt`, `frames.csv`,
`clocks.log`, `memory.csv`, `events.jsonl`, `device.txt`, `graphics-report.txt`, then crash
evidence: `crash-lines.txt` (the crash, `game_exec` and `game_exit` lines from `session.log` and
the x86_64 Steam log, newest 256 KB), `crash.log` (Android's crash buffer), `app-exit-*-anr.txt`,
`steam-x64.log` (that log since the session started, 2 MB head and tail), `proton/...` (Proton,
DXVK and VKD3D logs when game logging is on), `game-logs/<appid>/AppData/...` (logs the games
wrote during the session: Godot's `AppData/Roaming/<name>/logs/*.log`, as Slay the Spire 2 does,
`AppData/Roaming/Godot/app_userdata/<name>/logs/*.log`, Unity's `AppData/LocalLow/*/*/Player*.log`;
newest first, 1 MB each, 6 MB in all), and an excerpt of `session.log`. Text files go through the
share bundle's redactor. Over 20 MB, `session.log` is dropped first, then `frames.csv` keeps its
last 16 MB.
Anything that could not be sent (offline, crash) goes out at the next app start. The status line
under the switch shows the last result.

## Comparing builds

Install one APK, play for the same length and scene, end the session; install the other over it
(same signing key) and repeat. Then, from a clone of the telemetry repository or a folder of share
zips:

    python3 tools/perf_report.py runs/
    python3 tools/perf_report.py DroidDeck-2026-11-12-01-steam-session.zip DroidDeck-2026-11-12-02-steam-session.zip

It prints each session (device, GPU and driver, build, timeline, phase durations, in-game frame
stats, thermals, CPU caps, memory, exits) and, for two or more sessions, a side-by-side table; with
two, the difference and whether it is better. It ends with crashes and abnormal exits per build.
`--json` prints the parsed numbers.
