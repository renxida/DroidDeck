# The standard bench

One command measures one configuration ("arm") of Slay the Spire 2 on the test phone and leaves a `summary.json`,
the raw artifacts and a row in `docs/bench/RESULTS.md`. Python 3 standard library only; it drives the phone
through `adb` and `tools/droiddeckctl`.

    export ANDROID_SERIAL=<serial>
    tools/bench/run.py --scenario fight --path native --route bridge --name fight-native-bridge-1
    tools/bench/run.py --scenario fight --path native --route panvk --switch present-dmabuf --switch vk-count --name fight-dmabuf-1
    tools/bench/run.py --scenario menu  --path proton --route bridge --name menu-proton-bridge-1

| Option | Meaning |
|---|---|
| `--scenario menu\|fight` | what is measured (below) |
| `--path native\|proton` | sets the `native-engine` experiment On or Off for the run |
| `--route panvk\|bridge\|venus` | presses the two route buttons in Settings > Advanced until they read that way |
| `--switch TAG`, `--switch-off TAG` | an experiment to turn on or off for this run (`present-dmabuf`, `vk-count`, ...) |
| `--env KEY=VALUE` | a session env line for this run; needs Debugging tools |
| `--name NAME` | the run's folder under the output root (`$BENCH_OUT`, default `~/projects/droiddeck/output/bench`); must be new |
| `--latency-count N` | presses for the latency probe (default 100; 0 skips it; needs Debugging tools) |
| `--xcheck` | fight: also take the screen-recording cross-check of two real flicks |
| `--keep-config` | do not put the route and switches back after the run |

Three modes, one command each (times for the native engine; "INFERRED" ones have not run on the phone yet):

| Mode | Command | Time |
|---|---|---|
| `full` (default): one launch, one fight, everything captured | `tools/bench/run.py --scenario fight --path native --route bridge --name fight-1` | 7.3 minutes an arm (measured, 11 runs) |
| `screen`: "is it promising?" | `tools/bench/run.py --mode screen --scenario fight --path native --route bridge --name screen-1` | INFERRED: 1.4 minutes a fight, so 4.5 minutes for the default 3 fights and 1.6 with `--reps 1`; add 1.5 minutes when it has to start a session, 0.8 when only the game |
| `confirm`: "is it actually good?" | `tools/bench/run.py --mode confirm --scenario fight --path native --route bridge --a "" --b "godot-render-thread" --name confirm-1` | INFERRED: 10 minutes a pair with 3 fights an arm (5 with `--reps 1`), 3 to 6 pairs |

A full fight arm takes about 7 minutes (8 on Proton), a menu arm about 9. The exit code is 0 for a complete run and 1 for an aborted
one; an aborted run still stops the session, pulls the logs, restores the switches and writes `summary.json`
(`ok: false`, `failure`), but adds no row to `RESULTS.md`.

## Screen mode

    tools/bench/run.py --mode screen --scenario fight --path native --route bridge --name screen-1
    tools/bench/run.py --mode screen --scenario fight --path native --route bridge --name screen-2 --reps 5
    tools/bench/run.py --mode screen --scenario fight --path native --route bridge --name screen-3 --push-file build/droiddeck-game-env:/usr/local/bin/droiddeck-game-env
    tools/bench/run.py --mode screen --scenario fight --path native --route bridge --name x --stop

| Option | Meaning |
|---|---|
| `--reps N` | measured fights in this arm (default 3) |
| `--warmup-reps N` | fights played first and not counted (default 0; the first fight of a launch is marked `cold` either way) |
| `--turns N` | play only the first N turns of the script (a 17 s window with 1; numbers are not comparable across values) |
| `--with-latency N`, `--with-cpu`, `--with-perfetto`, `--with-pacing` | add the latency probe (N presses), `top -H`, a Perfetto trace or `droiddeckctl pacing` to every fight; all off by default |
| `--push-file LOCAL:GUEST_PATH` | replace a file in the container before the game is launched (below) |
| `--restore-pushed` | put every pushed file back, relaunch, then measure |
| `--stop-after` | quit the game from its menu, stop the session and restore switches, route and pushed files at the end |
| `--stop` | only do that for what an earlier screen run left running (measures nothing) |
| `--restart` | start a new session even when the kept one would do |
| `--max-thermal-status N` | stop when Android's thermal status reaches N (default 2) |

What it does:

1. **Attach.** It needs Debugging tools (per-rep checks, env lines, pushed files) and stops clearly when they are off. With no
   session (IDLE) it does the full preflight of the default mode, starts Steam, checks the offline icon and launches the
   game. With a session up it goes on only if that session is the one an earlier screen run recorded in
   `<output root>/.screen-session.json` (same session id, same build); any other session is left alone and the run
   fails. Then it compares the recorded configuration with the one asked for and restarts the least that works:
   **nothing** (same path, route, switches, env lines and pushed files: the game is at its main menu already),
   **the game** (a launch-level switch, `--path`, a pushed file: Quit from the game's menu, set the switch in
   Lanner's settings, launch again in the same session), or **the session** (a session-level switch, the route,
   an env line: stop, restore, full preflight, start). `reuse.level` and `reuse.reasons` in the summary say which and why.
2. **Each fight:** the cheap rules again (Lanner in front and on power; media volume 0; `steam_offline`, `gameSound`,
   `bootMovieSound` read from the app's preferences; thermal status), the main menu and the blue badge in a screenshot,
   Continue, wait for the hand (screenshots every second, 60 s limit), **the enemy's bar must be full** (otherwise
   "Save and Quit did not put it back" and the run stops), wait for the hand to stop moving, the script, Start,
   Save and Quit, wait for the main menu (40 s limit). No L+100 s wait, no latency probe, no trace.
3. **Afterwards** the session's logs are pulled while it runs, Steam's offline lines are checked in them (`steam_offline=true`,
   the last `Start offline - 1`, a `User is offline` line no older than the game launch), and the session and game
   stay up at the main menu on the bench profile. Any failure ends the game and the session and restores everything, as
   the default mode does.

**Why the fight repeats.** The game writes the run's save when the fight starts and Save and Quit does not move it
(`docs/research/mali-10a-fight-1.md`), and the script never wins, so Continue deals the same fight again. Across game
launches that is VERIFIED (every full-mode run). Inside one launch it is INFERRED until the lab confirms it; the
harness checks it on every fight (full bar at the start, the scripted health values, and on the native engine the
same cards and enemy moves in the game's log: `identical` in the summary) and never restores save files itself.

**Switch levels** (`benchlib.EXPERIMENT_LEVEL`, one entry per experiment in `Experiments.kt`, checked by a test):
no experiment is re-read by a running game. *Game launch* (read by `droiddeck-game-env` at each launch):
`native-engine`, `present-dmabuf`, `vk-count`, `vk-blocks-64`, `pe-align`, `godot-render`, `godot-render-thread`,
`sched`, `fps-30`, `one-limiter`, `native-hz`, the `fex-*` and `dotnet-*` code switches, `tex-*`, `native-r2r`.
*Session* (read when the session starts): `pad-idle-reports`, `hints-idle`, `zram-game-late`, `bridge-default`,
`two-frames`, `layer-fence`, `adpf-all`, `quick-tap`, `all`, `compat-quiet`, `memory`, `warmup`, `free-memory`,
`fex-kestrel`, `fex-stats`, `fex-sample`, `fex-smaps`, `hybris-index-session`, `composite-timing`; also the route
buttons and every env line. An unknown switch counts as session-level.

**`--push-file LOCAL:GUEST_PATH`** is for engine and overlay iteration without an APK: the local file goes to the app's
inbox with `adb push`, and a command in the container (the guest agent, so Debugging tools and a running session)
copies the original to `GUEST_PATH.bench-orig`, puts the new file in its place with the original's mode, and prints
both sha256 sums, which land in `pushed_files` in the summary. The pushed file's hash must equal the local file's.
It is done with the game not running and counts as a launch-level change. The target must already exist; paths
under Steam's library, `userdata`, a Wine prefix, the game's own data folder, or anything that looks like a save
are refused (`benchlib.push_target_problem`). `--restore-pushed`, `--stop`, `--stop-after`, a failure and every
session restart put the original back and check its hash. A session start may restage overlay files from the APK
(INFERRED); the harness pushes again after every session start it makes.

**What a kept session leaves on the phone:** Steam (offline) and the game at its main menu on the bench profile,
the switches and the route as the last arm set them, `.screen-session.json` on the desktop with what to restore.
End it with `--stop` before anyone else uses the phone and before a default-mode run (which aborts on a running session and stops it in its teardown).

## Confirm mode

    tools/bench/run.py --mode confirm --scenario fight --path native --route bridge --a "" --b "godot-render-thread" --name confirm-1

`--a` and `--b` are words separated by spaces or commas: `tag` turns an experiment on, `-tag` off, `KEY=VALUE`
adds an env line, `path:proton` and `route:panvk` replace `--path` and `--route`; `--switch`/`--env` given outside
apply to both. The arms run A, B, A, B, ... as screen-mode arms named `<name>/p01-a`, `<name>/p01-b`, ...; a
launch-level difference shares the session and relaunches the game per arm, a session-level one restarts the
session per arm, identical arms (an A/A noise run; use `--warmup-reps 1`) share the launch.

After each pair it computes B minus A for the animation-window p99 with a 95 % bootstrap interval (pairs
resampled, then each arm's fights inside a pair; 10 000 draws; `--seed`, default 20261128, so the same values
give the same interval). It stops when the interval excludes 0, at least `--min-pairs` (3) are done **and** the
difference is at least what the earlier noise floor lets that many pairs see (`--noise-sd`, default 2.0 ms, the
pooled run-to-run sd from `docs/bench/RESULTS.md`); otherwise after `--max-pairs` (6). The second condition matters:
simulated with no real difference, the interval alone stops falsely in 37 % of campaigns, with it in 9 %; a real
3 ms difference is called in 74 %, a 5 ms one in 99 %. The same intervals are reported for animation fps, frames
over 50 ms per minute and MemAvailable. At the end it stops the session and restores everything
(`--keep-running` leaves it).

`<name>/summary.json`: `mode: confirm`, `a`, `b`, `pairs`, `runs`, `share.levels` (each arm's restart level),
`values.<metric>.a|b` (per pair, per fight), `intervals.<metric>` (`diff`, `low`, `high`, `pairs`, `seed`, `draws`,
`excludes_zero`), `decision` (`stop`, `why`, `verdict`, `beyond_noise_floor`), `spread.<metric>.a|b`, `ok`,
`failure`. Metrics: `anim_p99_ms`, `anim_fps`, `over50_per_min`, `mem_available_mb`.

A completed confirm run adds one **noise-floor line** to `docs/bench/RESULTS.md` (above the marker comment in its
"Noise floor" section; `--no-results-row` skips it):

    - noise-floor | date | build | configuration | source | n=<repeats> in <groups> groups (df <n>) | anim p99 sd <ms> | fps sd | over-50 sd /min | MemAvailable sd MB | p99 seen from <1 pair> / <3> / <6> ms with 1 / 3 / 6 pairs | `run name`

"sd" is the standard deviation within groups of identical arms (for a confirm run: A's fights and B's fights);
"p99 seen from" is 1.96 x sd x sqrt(2 / pairs).

## What a run does

1. **Preflight, enforced** (any failure aborts before a session starts): Lanner in front and the phone on power
   (the owner-pickup check, repeated before the launch and before the measured window); session IDLE; Chrome and
   `chat.comb.familiar.voice` force-stopped; media volume set to 0 and read back; Offline mode On, Game sound Off
   and boot-movie Sound Off read from Lanner's Settings (the bench never flips these three: if one is wrong it
   stops); route and switches set as asked and read back; no env line pending; wait until MemAvailable is over
   2.5 GB or 3 minutes have passed. Recorded: memory, swap, GPU memory, battery temperature, thermal status,
   lmkd kills since the last run, build, every experiment's state, the route and its "Safe mode" line.
2. **Steam, offline.** `start steam --wait`, then screenshots until the crossed-out network icon is in Steam's
   header (`shots/steam-offline-icon.png` is the crop). No icon within 36 s: abort without launching.
3. **Launch**, with a 10 s sampler (`/proc/meminfo`, `dumpsys gpu --gpumem` with owner names, battery
   temperature, thermal status, GPU and CPU clocks) and a `logcat` stream of lmkd kills. The game's "What's
   Early Access?" notice is answered with its only button.
4. **The scenario** (below), with a Perfetto system trace, `top -H` in batch mode and `droiddeckctl pacing`
   covering the measured window.
5. **Quit through the game's own menu** (Quit, Yes), `stop`, `logs latest`, clear env lines, restore switches.

### `menu`

The main menu, untouched, for 120 s starting 240 s after the launch request (as in the earlier 10a notes). After
the window: the latency probe with `pad:down` / `pad:up` on the first two menu entries.

### `fight`

The game must be on the **bench profile** (its badge in the top left corner is blue: "Profile 3") and that
profile must hold the **saved bench run**: Ironclad, seed `KW6PMT0GVE5V`, floor 1, standing at the start of the
first fight (one Nibbit, 45 HP). The game saves a run when a fight starts and "Save and Quit" does not move that
save, so Continue starts the same fight with the same hand every time. The script never wins the fight (the
Nibbit ends on 8 HP), so the save never advances. The bench refuses to run when the badge is not blue or the
top menu entry is not where Continue sits.

From L+100 s: focus the top menu entry with the d-pad (checked), A (Continue), wait for the hand. Then the
latency probe (`pad:right` / `pad:left` moves the focus between the first two cards; nothing is played), the
cross-check when asked for, then the script in `scenarios/fight.json`. On game v0.114.0 the saved run deals
Strike, Defend, Strike, Bash, Defend (v0.111.1 dealt Bash and four Strikes from the same save, so the script was
rewritten on Nov 28; it still plays 9 cards, 7 of them attacks, and ends on 8 HP):

| Turn | Inputs | Enemy after |
|---|---|---|
| 1 | Strike (A, A on the Nibbit); right, Strike; left, Defend; Y (End Turn): the Nibbit attacks for 12 | 39, 33 |
| 2 | right, Strike, Strike; right, Strike; Y: the Nibbit attacks for 6 and gains block | 27, 21, 15 |
| 3 | Strike, Strike; right, Defend; Y: the Nibbit buffs | 14, 8 |

After a card is played the focus stays on the same position in the hand. When that card is Bash or a Defend in
the middle of the hand, its tooltip covers the enemy's health bar, so the script only checks the bar after the
steps that leave it visible (39, 33 after End Turn 1, 27, 14 and the final 8).

After a step that names a value the bench takes one screenshot and checks the enemy's health bar against the
script (and, after each End Turn, that a new hand was dealt). A mismatch aborts the run. Then Start, Save and Quit, and the game's
Quit.

**Animation windows.** For a card: from its first A being sent until `settle_ms` (2 s) after its second A
(0.8 s later); that covers the targeting arrow, the attack or block animation and the damage numbers. For End
Turn: from Y being sent until 6 s after; that covers the discard, the enemy's move, the "Player Turn" banner and
the new hand. The settle times are fixed so every arm is measured over the same game events. When a check
screenshot shows the step has not finished (a stall), the bench looks again every 1.5 s, up to four times,
records `late_checks` on the step and stretches that step's window to the moment the check passed.
`frames.animation` is the frame statistics over all windows, `frames.card_plays` and `frames.enemy_turns` split
them, and `frames.overall` is everything from the first scripted input to the last window's end (the short
waits and check screenshots between steps included).

**Inputs** are pad presses through the app's agent bridge, sent with `cmd activity broadcast` (about 0.1 s, no
new runtime on the phone). `droiddeckctl input` uses `content call`, which starts an Android runtime per press
(1.2 s) and costs the game a 30 to 55 ms frame each time, so the bench does not use it inside windows. A press
is timestamped with the phone's clock just before and just after the broadcast is sent.

**Frame numbers** come from the session's `frames.csv` the way `docs/handoff/phone-tests/fp.sh` reads it:
`fps` is frames divided by the span, percentiles are `sorted[int(q*n)]`, plus frames over 33 ms and over 50 ms
per minute. `pacing` is SurfaceFlinger's view of what reached the panel.

**Press-to-screen** is `droiddeckctl latency` (see `docs/agent-control.md`): from the moment the app injects the
press to the first game frame whose content changed in the watched region reaching the panel (`endsAt:
display`) or being queued for it (`endsAt: present`). It does not include the touch controller, Android's input
dispatch of a real touch, or the panel's response. In the fight only the presses that start from a quiet patch
are answered (about 60 of 100); the others are reported as `no-change` because the lifted card's glow is in
the patch already.

`--xcheck` adds the coarse cross-check in the fight: Android's touch dots are switched on for 9 s, the screen
is recorded, two real flicks (`input swipe`, 50 ms) land on the on-screen swipe pad, and the recording is read
for the frame where each dot appears and the first later frame where the hand changes (needs `ffmpeg` and
`ffprobe` on the desktop). That time includes the flick itself and the real touch path, and is good to one
recorded frame at each end.

## Output

`<output root>/<name>/`: `summary.json`, `run.log`, `shots/` (every screenshot the run looked at, numbered),
`sampler.txt`, `top-threads.txt`, `logcat-lmk.txt`, `pacing.json`, `latency-<scenario>.json` (every sample),
`xcheck-fight.mp4`,
`trace.pftrace` (Perfetto: sched, CPU and GPU frequency, gfx/view/input slices, frame timeline, memory
counters; open at ui.perfetto.dev), `logs/` (the session's archive from `logs latest`), `vkcount.txt` and
`native-profiler.txt` when those are on. Nothing is written inside the repo except the `RESULTS.md` row.

### `summary.json` (schema 1)

| Key | Content |
|---|---|
| `schema`, `name`, `started_utc`, `scenario`, `path`, `route`, `switches`, `env` | what was asked for |
| `ok`, `failure` | whether the run completed, and why not |
| `build` | `label`, `commit`, `app_version`, `runtime` |
| `engine` | what actually ran: `path` and `build` from `game.engine`, `graphics_path` from `graphics-report.txt`, `vulkan_device` (native), `own_counter_window` (the native engine's 10 s frame counters inside the window: fps, main-thread ms and busy share), `game_log` (cards played and monster moves the game logged), `vkcount`, `profiler_lines` |
| `preflight` | the header described above, plus `start_to_ready_s` and `offline_icon_pixels` |
| `marks_ms` | phone wall-clock ms of `launch`, `menu`, `combat_ready`, `window_start`, `window_end`, `quit` |
| `frames` | `overall` and, for a fight, `animation`, `card_plays`, `enemy_turns`: each `n`, `span_s`, `fps`, `p50`, `p95`, `p99`, `max`, `over33_per_min`, `over50_per_min`; `windows` lists the merged animation windows |
| `pacing` | `fpsOnScreen`, `p50Ms`, `p95Ms`, `p99Ms`, `offCadencePercent` over the window |
| `latency` | `probe`: `toScreen`, `toChangedFrame`, `toFirstFrame`, `framesToChange` (each `n`, `minMs`, `p50Ms`, `p95Ms`, `p99Ms`, `maxMs`), `endsAt`, `statuses`, `inputs`, `region`; `screenrecord`: the coarse cross-check when one was taken |
| `memory` | `window`: means over the window of MemAvailable (and its minimum), SwapFree, `Gpu`, `ION_heap`, zram, and GPU memory per owning process; `available_min_after_launch_mb`; `session_csv_window` (game resident and swap from the session's `memory.csv`); `lmk_risk` events |
| `cpu` | `session_processes` and `session_threads` (the session's own tables, one core = 100), `top_processes` and `top_threads` (Android's `top -H`, which also sees SurfaceFlinger) |
| `thermal` | battery temperature and thermal status at start and end, their maxima in the run, capped CPU policies, mean GPU clock |
| `lmkd` | kills during the run and since the previous run, with the lowest `oom_score_adj` |
| `steps` | every scripted step with its presses' phone timestamps and what the check screenshot read |
| `artifacts`, `checks`, `notes` | paths; `quit_through_game_menu`, `config_restored`, `profile_icon`, `early_access_prompts`, `env_lines_after`; anything odd |
| `phases` | where the wall time went, every mode: `total_s`, `fixed_sleep_s` (sleeps of a set length), `poll_sleep_s` (pauses between looks at something awaited), `by_name.<phase>` (`count`, `seconds`, `fixed_sleep_s`, `poll_sleep_s`) and `items` (each phase in order: `name`, `rep`, `start_s`, `seconds`, `fixed_sleep_s`, `poll_sleep_s`). Phases: `preflight`, `memory-wait`, `steam-start`, `offline-check`, `launch-to-menu`, `fixed-wait`, `continue`, `fight-ready`, `latency`, `capture-start`, `fight` (its fixed sleeps are the settle times, i.e. the measurement), `menu-window`, `capture-finish`, `save-and-quit`, `quit-game`, `stop-session`, `log-pull`, `restore-config`, `analysis`; screen mode adds `attach`, `rep-checks`, `push-files` |
| screen mode only | `mode: screen`; `reps` (per fight: `index`, `launch`, `rep_in_launch`, `cold`, `warmup`, `checks`, `enemy_hp_start`, `window_ms`, `steps`, `frames` with `overall`/`animation`/`card_plays`/`enemy_turns`, `memory`, `cards`, `monster_moves`, `enemy_hp_checks`, `thermal_end`); `spread` and `spread_warm` (per metric: `n`, `mean`, `median`, `sd`, `min`, `max`, `range` over the measured fights, and over those that were not the first of a launch); `identical`; `reuse`; `pushed_files`; `offline_evidence`; `left_running`. The top-level `frames` are over all measured fights together |

`benchlib.validate_summary` checks a summary against this table (`validate_phases`, `validate_screen` and
`validate_confirm` the additions); `tools/tests/test_bench_lib.py` covers the frame statistics, window selection,
parsers, screenshot features, the schema, the switch-level table, the planning and the interval statistics, and
`tools/tests/test_bench_modes.py` runs screen and confirm mode against a scripted stand-in for the phone.

## Setting up the bench profile (once per phone)

Back up the saves first (`docs/research/mali-10a-fight-1.md`, section 2). In the game: click the profile badge,
choose the empty Save Profile 3, Singleplayer, confirm The Ironclad, answer No to "Enable Tutorials?", click the
first node of the map. A new profile's first run presents its rooms in a set order, so the first fight is the
Nibbit; the seed is random, and `scenarios/fight.json` is written for the hand of seed `KW6PMT0GVE5V`. With
another seed, play the fight once by hand and rewrite the steps and expected health values. Then Start, Save
and Quit. When the campaign is over: Abandon Run from the main menu. Switch back to the owner's profile only by selecting it and then ending
the session without the game's own Quit (see Limits) until the game versions match.

## Limits

- **Do not let the game exit from its menu on the owner's profile while the phone's game is older than the
  owner's saves** (`docs/research/mali-10a-fight-1.md`, section 11): the game then rewrites that profile's
  progress file in its own, older schema. The fight scenario refuses any profile but the bench one; the `menu`
  scenario runs on whichever profile is selected and quits from the menu, so select the bench profile first.

- Screenshot checks assume a landscape screenshot of 2424x1080 with the game's 16:9 picture centred (other
  sizes are scaled) and Lanner's on-screen controls in their default places.
- The offline check reads Lanner's setting and Steam's icon before the launch. Steam's own log line (`User is
  offline`, written when a game starts) is in the pulled archive, not checked live.
- Debugging tools must be on for the latency probe, env lines and the native engine's counters inside the
  window; without it use `--latency-count 0`.
