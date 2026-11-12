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
| `session.log` | `== STEP HH:MM:SS.mmm` lines (UTC) and `DROIDDECK_TIMELINE event=game_exec ... wall_ms=` when a game process starts |

Timeline events: `app.process_start`, `frontend.first_frame`, `session.created`, `service.started`,
`runtime.installing`/`runtime.ready` (with `setup.runtime.download|verify|extract|extracted`),
`proton.installing`/`proton.ready`, `compositor.starting`/`started`, `guest.starting`/`started`,
`steam.ui_first_frame`, `steam.signed_in`, `steam.game_launch_requested`, `game.first_frame`,
`frames.focus`, `session.stopping`/`stopped`.

## Automatic upload (one-time setup)

1. On GitHub, create a private repository (default name `renxida/kestrel-telemetry`), with any
   first commit (a README is enough).
2. Settings > Developer settings > Fine-grained personal access tokens > Generate: repository access
   "Only select repositories" with that repository, permission Contents: Read and write, nothing else.
3. In the app: Setup > Diagnostics > Telemetry upload. Turn it on, check the repository, paste the
   token, Save. The token stays in app-private storage (`no_backup/telemetry/token`); it is never
   logged, shared in log bundles or written to a report.

Each session then uploads `runs/<branch>-<commit>/<Manufacturer-Model>/<start UTC>.zip`: a
checkpoint every 5 minutes (overwriting the same file) and the full bundle when the session ends.
Anything that could not be sent (offline, crash) goes out at the next app start. The status line
under the switch shows the last result.

## Comparing builds

Install one APK, play for the same length and scene, end the session; install the other over it
(same signing key) and repeat. Then, from a clone of the telemetry repository or a folder of share
zips:

    python3 tools/perf_report.py runs/
    python3 tools/perf_report.py DroidDeck-2026-11-12-01-steam-session.zip DroidDeck-2026-11-12-02-steam-session.zip

It prints each session (device, GPU and driver, build, timeline, phase durations, in-game frame
stats, thermals, CPU caps, memory) and, for two or more sessions, a side-by-side table; with two,
the difference and whether it is better. `--json` prints the parsed numbers.
