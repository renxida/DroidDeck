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
| `--latency-count N` | presses for the latency probe (default 60; 0 skips it; needs Debugging tools) |
| `--keep-config` | do not put the route and switches back after the run |

A fight arm takes about 7 minutes, a menu arm about 9. The exit code is 0 for a complete run and 1 for an aborted
one; an aborted run still stops the session, pulls the logs, restores the switches and writes `summary.json`
(`ok: false`, `failure`), but adds no row to `RESULTS.md`.

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
latency probe (`pad:right` / `pad:left` moves the focus between the first two cards; nothing is played), then
the script in `scenarios/fight.json`:

| Turn | Inputs | Enemy after |
|---|---|---|
| 1 | right; Strike, Strike, Strike (each: A, A on the Nibbit); Y (End Turn): the Nibbit attacks for 12 | 39, 33, 27 |
| 2 | right x3; Strike; Defend, Defend; Y: the Nibbit attacks for 6 and gains block | 21 |
| 3 | Strike, Strike, Strike; Y: the Nibbit buffs | 20, 14, 8 |

After a step the bench takes one screenshot and checks the enemy's health bar against the script (and, after
each End Turn, that a new hand was dealt). A mismatch aborts the run. Then Start, Save and Quit, and the game's
Quit.

**Animation windows.** For a card: from 250 ms before the first A's bridge call returned until `settle_ms`
(2 s) after the second A returned; that covers the targeting arrow, the attack or block animation and the
damage numbers. For End Turn: from 250 ms before Y returned until 6 s after; that covers the discard, the
enemy's move, the "Player Turn" banner and the new hand. The settle times are fixed so every arm is measured
over the same game events; they were set from a screen recording of the reference arm. `frames.animation` is
the frame statistics over all windows, `frames.card_plays` and `frames.enemy_turns` split them, and
`frames.overall` is everything from the first scripted input to the last window's end (waits between inputs
included). Each press goes through `content call`, which starts a short-lived process on the phone and takes
about 1.2 s; the press lands at the end of that call, so a card's window contains one such call (the second
A). That cost is the same in every arm.

**Frame numbers** come from the session's `frames.csv` the way `docs/handoff/phone-tests/fp.sh` reads it:
`fps` is frames divided by the span, percentiles are `sorted[int(q*n)]`, plus frames over 33 ms and over 50 ms
per minute. `pacing` is SurfaceFlinger's view of what reached the panel.

**Press-to-screen** is `droiddeckctl latency` (see `docs/agent-control.md`): from the moment the app injects the
press to the first game frame whose content changed in the watched region reaching the panel (`endsAt:
display`) or being queued for it (`endsAt: present`). It does not include the touch controller, Android's input
dispatch of a real touch, or the panel's response.

## Output

`<output root>/<name>/`: `summary.json`, `run.log`, `shots/` (every screenshot the run looked at, numbered),
`sampler.txt`, `top-threads.txt`, `logcat-lmk.txt`, `pacing.json`, `latency-<scenario>.json` (every sample),
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

`benchlib.validate_summary` checks a summary against this table; `tools/tests/test_bench_lib.py` covers the
frame statistics, window selection, parsers, screenshot features and the schema.

## Setting up the bench profile (once per phone)

Back up the saves first (`docs/research/mali-10a-fight-1.md`, section 2). In the game: click the profile badge,
choose the empty Save Profile 3, Singleplayer, confirm The Ironclad, answer No to "Enable Tutorials?", click the
first node of the map. A new profile's first run presents its rooms in a set order, so the first fight is the
Nibbit; the seed is random, and `scenarios/fight.json` is written for the hand of seed `KW6PMT0GVE5V`. With
another seed, play the fight once by hand and rewrite the steps and expected health values. Then Start, Save
and Quit. When the campaign is over: Abandon Run from the main menu, and switch back to the owner's profile.

## Limits

- Screenshot checks assume a landscape screenshot of 2424x1080 with the game's 16:9 picture centred (other
  sizes are scaled) and Lanner's on-screen controls in their default places.
- The offline check reads Lanner's setting and Steam's icon before the launch. Steam's own log line (`User is
  offline`, written when a game starts) is in the pulled archive, not checked live.
- Debugging tools must be on for the latency probe, env lines and the native engine's counters inside the
  window; without it use `--latency-count 0`.
