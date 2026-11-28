# Bench dev loop 1: where a phone arm's 7 minutes go, and two faster modes

**Question.** Is the measure-on-the-phone loop efficient? Where does it wait needlessly, and how short can the
time to "is it promising?" and to "is it actually good?" get?

**Short answer.**

- A fight arm takes **7.3 minutes** (median of the last 11 native runs); the measured fight is **53 s of it (12 %)**
  and the animation windows 45 s (10 %). The rest: the latency probe 119 s (27 %), a fixed wait until 100 s after
  the launch 72 s (16 %), starting and ending the session and the game 141 s (32 %), waiting for `pacing` and
  Perfetto to run out after the fight and pulling the 101 MB trace 25 s (6 %). About 128 s (29 %) is sleeps of a
  set length.
- `tools/bench/run.py --mode screen` keeps Steam and the game up and loops Continue, the fight, Save and Quit.
  Expected **1.4 minutes per measured fight** (4.5 minutes for three, with a spread) against 7.3 for one.
  **INFERRED: nothing in this note has run on the phone.** The harness logic is tested against a scripted
  stand-in for the phone (`tools/tests/test_bench_modes.py`); the timings are sums of measured pieces.
- `--mode confirm` runs A/B/A/B as screen arms and stops on a seeded bootstrap interval, guarded by the noise
  floor, or after six pairs.
- **Noise floor from the 12 same-configuration repeats on disk:** animation p99 has a run-to-run standard
  deviation of **2.0 ms** on native + bridge. One pair of full-mode runs cannot tell a p99 change under about
  5.4 ms from noise; three pairs 3.1 ms; six pairs 2.2 ms. Every lab-3 arm (34.4 to 37.2 ms) is inside that.
- No experiment is re-read by a running game. 35 are read at each game launch, 19 at session start; the route
  and env lines are session-level. Two switches the bench has used on the bridge do nothing there.
- Outside the bench: CI takes 20 minutes a build (17.8 to 26.8); the APK exists 5 to 6 minutes before the run
  ends; a change that only touches `tools/bench` triggers the whole build.

Marks: **VERIFIED** = read from a run's artifacts, CI's job records or source; **INFERRED** = reasoned, not
observed. Artifacts: `~/projects/droiddeck/output/bench/<run>/` (`run.log`, `summary.json`, `logs/events.jsonl`,
file times).

## 1. Phase timing of a full-mode fight arm

Sources per run: `summary.json` (`started_utc`, `marks_ms`, `preflight.start_to_ready_s`), the timestamps in
`run.log`, the session's `events.jsonl`, and the modification times of `trace.pftrace` and the pulled `logs/`. All
22 completed fight arms were read; the main column is the 11 native + bridge runs of lab 2 and lab 3
(`lab2-fight-native-bridge-1/2`, `lab3-control-1/2`, `-zram-late-1/2`, `-all-1/2`, `-pad-idle-1`,
`-bridge-stats-1`, `-safe-off-1`). Seconds, median (range). VERIFIED.

| Phase | Native, bridge (11) | Native, PanVK or Venus (7) | Proton (3) | What it waits on | Fixed sleep inside |
|---|---|---|---|---|---|
| Preflight: owner check, close apps, read Offline and sound settings, set route and switches, memory | 42 (39-69) | 52 (43-62) | 43 (43-47) | about 22 `droiddeckctl` calls at 1.2 s each | 11 s (1 s after each UI click) |
| of which the wait for 2.5 GB free | 0 (0-0) | 0 | 0 | MemAvailable | none needed in any run |
| Steam start to READY | 14.2 (12.2-16.5) | 16.3 (12.1-16.6) | 14.3 | session event | |
| Offline icon check | 6.7 (6.5-6.8) | 6.6 | 6.7 | screenshot | 6 s before the first look |
| Launch request to main menu (first frame at 7.6 s native, 19.7 s Proton) | 27.9 (27.5-32.4) | 32.8 (31.8-33.4) | 52.3 (50.6-61.2) | screenshots every 4 s | 2 s confirm |
| **Fixed wait until launch + 100 s** | **72.1 (67.6-72.5)** | 67.2 | 47.7 (38.8-49.4) | nothing | all of it |
| Focus the menu, Continue | 4.1 (3.4-5.0) | 4.2 | 4.3 | screenshots | 1.5 s |
| Continue to the fight ready | 8 (7-8) | 8 (7-11) | 11 | screenshots every 3 s | |
| **Latency probe** (3 s, then 100 presses) | **119 (118-119)** | 109 (108-119) | 109 | the probe itself | 3 s |
| Start captures (engine counter, `top`, Perfetto, `pacing`) | 6.1 (5.3-6.6) | 5.9 | 4.1 | | 2.5 s |
| **The fight** (first scripted press to the last window's end) | **52.7 (52.6-53.0)** | 52.4 (52.0-59.3) | 52.1 | settle times, check screenshots | by design |
| of which animation windows (what `frames.animation` covers) | 44.9 (44.8-45.0) | 44.9 | 44.8 | | |
| After the fight until Save and Quit is pressed: `pacing` and Perfetto run out, trace pull, pause menu | 30.8 (30.0-31.1) | 29.2 (21.6-30.3) | 29.7 | `pacing` was asked for 77 s and Perfetto for 60 s; the fight takes 53 | about 23 s of overrun, 2.7 s in the pause menu |
| of which until the 101 MB trace is on the desktop | 24.7 (24.2-25.2) | 23.4 | 24.0 | | |
| Save and Quit to the game's Quit pressed | 19.5 (19.0-20.2) | 20.4 | 22.1 | screenshots every 3 s, six focus moves | 2 s |
| Quit to the game gone | 3.3 (2.7-3.7) | 3.0 | 2.9 | session focus every 3 s | |
| Stop the session (with the 6 s sleep before it) | 17.4 (16.8-18.9) | 18.0 (12.5-23.0) | 15.0 | session event | 6 s |
| Log pull | 6.7 (5.1-7.3) | 7.9 | 6.4 | zip on the phone, `adb pull` | |
| Clear env, restore switches and route, analysis | 9.9 (7.8-39.0) | 21.7 (11.6-31.5) | 18.1 | `droiddeckctl` calls | 1 s per click |
| **Total** | **439 (435-497)** | 453 (432-474) | 447 (432-453) | | **about 128 s (29 %)** |

**Where the 7.3 minutes go** (native, bridge): the latency probe 27 %; the session and game life cycle (preflight,
Steam, offline check, launch, quit, stop, restore) 141 s, 32 %; the fixed wait 16 %; the fight 12 %; the tail after
the fight 7 %; everything else 6 %. Sleeps of a set length are about 128 s: 72 + 11 + 6 + 3 + 2.5 + 23 + 2.7 + 2 + 6.
Waiting on a real event (Steam ready, menu up, fight ready, game gone, session stopped) is about 75 s.

**What a faster mode can drop or shrink:**

| Phase | Full | Screen, per fight after the first launch | How |
|---|---|---|---|
| Preflight, Steam, offline icon, launch | 91 s | 0 (once per session: 91 s; a game relaunch alone about 50 s) | session and game kept |
| Fixed wait to launch + 100 s | 72 s | 0 | the hand must be ready and steady instead; the first fight of a launch is marked `cold` |
| Rule re-checks | in preflight | 4 s | owner, volume, three preferences, thermal, badge |
| Continue to fight ready | 12 s | 10 s | screenshots every 1 s instead of 3 s, plus a steadiness check |
| Latency probe | 119 s | 0 | `--with-latency N` adds it back |
| Start captures | 6 s | 3 s | only the engine's counters (one call, then the 1.5 s that lets its slow frame pass) |
| The fight | 53 s | 53 s (17 s with `--turns 1`) | unchanged: the same settle times |
| Tail after the fight | 31 s | 6 s | no `pacing`, no Perfetto; `--with-pacing`, `--with-perfetto`, `--with-cpu` add them back |
| Save and Quit to the main menu | about 9 s of the 19.5 | 8 s | 1 s polls |
| Game Quit, stop, restore | 41 s | 0 | kept running; `--stop` or `--stop-after` does it once |
| Log pull, analysis | 17 s | 2 to 3 s (7 s once per arm) | one live pull per arm |
| **Per measured fight** | **439 s** | **about 85 s** (INFERRED) | |

So: three fights in one arm about 4.5 minutes instead of 22; one fight 1.6 minutes instead of 7.3. An arm that
needs a game relaunch adds about 0.8 minutes, one that needs a new session about 2 (stop 17 s, restore, the 91 s
above). Unverified until the phone: every number in the "Screen" column, that Lanner's settings can be clicked
while a session is up (the fallback is a session restart), that `logs latest` returns a complete `frames.csv`
from a running session (the writer flushes after every poll: VERIFIED in `FrameLogComponent.kt`; the 32 MiB cap
of `frames.csv` is about 13 hours at 60 fps), and that Continue repeats the fight inside one launch.

The harness now writes these numbers itself: `phases` in `summary.json`, in every mode
(`tools/bench/README.md`, `benchlib.PhaseLog`).

## 2. How the fight repeats (design choice)

**Chosen: Save and Quit to the main menu, Continue again, nothing else.** The game writes `current_run.save` when
the fight starts and Save and Quit does not move it; the script never wins (the Nibbit ends on 8 HP), so the run
never advances. Across game launches this is VERIFIED: every full-mode run does Continue, three turns, Save and
Quit, and the next run finds the bar full and the same hand (`docs/research/mali-10a-fight-1.md` section 1,
`mali-10a-lab-2.md` section 6: "the same on every Continue, over 6 launches"). **Inside one game launch it is
INFERRED**: no run so far has pressed Continue twice without quitting the game in between.

Not chosen: restoring the bench profile's save files between fights the way the park and unpark scripts do
(`native` commands on Steam's remote storage, both mirrors and `remotecache.vdf`). It writes save files under a
running game and Steam client, which is the one thing the campaign rules rank above everything, and it is not
needed if the above holds. If the lab finds the fight is not put back inside a launch, the fallback that keeps
saves untouched is one game relaunch per fight (about 50 s more per fight, still under half of a full arm).

The harness does not trust the inference. Every fight: the enemy's bar must be full after Continue (otherwise it
stops with "Save and Quit did not put it back", ends the game and the session), every scripted health value is
checked as before, and on the native engine the cards and enemy moves the game logged must match the first
fight's (`identical` in the summary; a mismatch makes the run `ok: false`).

One known difference between fights of a launch: the first card of each launch has a 120 to 240 ms frame
(shader and asset warm-up). It moves the maximum, hardly the p99 (the 26th worst of 2 600 frames). Each fight
carries `rep_in_launch` and `cold`, `spread_warm` leaves the first out, and `--warmup-reps` plays unmeasured
fights first.

## 3. When a switch takes effect

From `Experiments.kt`, `SessionService.kt` and the guest scripts (`droiddeck-game-env`, `droiddeck-session`).
Saving a switch rewrites `/root/.config/droiddeck/game-environment.json` at once; `droiddeck-game-env` drops every
inherited `DROIDDECK_EXP_*` and reads that file at each game launch; the app's own parts read the snapshot taken
when the session started (`Experiments.session`). **No switch is live.** The app's hint says the same ("Game parts
apply at the next game launch, Steam parts at the next session"); only two labels carry "(needs a new session)".
The guest's own list of session-level switches (`SESSION_EXPERIMENTS`) leaves out seven that have app-side
parts. VERIFIED in source unless marked.

| Level | Switches | Where it is read |
|---|---|---|
| **Game launch** (35) | `native-engine`, `present-dmabuf`, `vk-count`, `vk-blocks-64`, `pe-align`, `godot-render`, `godot-render-thread`, `godot-verbose`, `sched`, `fps-30`, `one-limiter`, `native-hz`, `fex-codegen`, `fex-memcpy-tso`, `fex-vector-tso`, `fex-tso`, `fex-fast`, `fex-l1`, `fex-code-buffer`, `fex-trim`, `fex-cache-keep`, `dotnet-trace`, `dotnet-gen0`, `dotnet-gc-stock`, `dotnet-r2r`, `dotnet-r2r-generics`, `dotnet-r2r-bubble`, `dxvk-arm64ec`, `wayland-present`, `native-inventory`, `hybris-index`, `webhelper-trim`, `tex-astc`, `tex-half`, `native-r2r` (INFERRED) | `droiddeck-game-env` at each launch |
| **Session** (13) | `pad-idle-reports`, `hints-idle`, `zram-game-late`, `bridge-default`, `two-frames`, `layer-fence`, `adpf-all`, `quick-tap`, `all`, `compat-quiet`, `hybris-index-session`, `composite-timing`, `fex-kestrel` | `SessionService`, `CompositorHost`, `GameThreadHints`, `droiddeck-session` at session start |
| Session, with a launch-level part (6) | `memory`, `warmup`, `free-memory`, `fex-stats`, `fex-sample`, `fex-smaps` | both; treated as session |
| Session | the route buttons (`vkBridge`, Venus) | `SessionService` while the session is set up |
| Session | env lines (`droiddeckctl env set`): pending lines join the next session's env and last one session | `AgentEnv.beginSession` |
| Session | `BL_VKBRIDGE_CEF_GPU` | `droiddeck-session` when Steam starts |
| Session env, read at engine start | `DROIDDECK_NATIVE_PROF`, `DROIDDECK_NATIVE_PROF_HITCH_MS` | the engine at process start; set as env lines, so a session restart |
| Game launch | a pushed file (`--push-file`) | needs the game process gone |

The table is `benchlib.EXPERIMENT_LEVEL`; a test fails when `Experiments.kt` gains an id the table lacks or the
guest's `SESSION_EXPERIMENTS` names one the table calls launch-level. An unknown switch is treated as session-level.

Three findings beside the table (VERIFIED in `droiddeck-game-env`, INFERRED for their effect on past rows):

- **`vk-blocks-64` does nothing on the Mali bridge or PanVK** (it is applied only through the PowerVR paths), so
  `fight-native-bridge-prof` in `RESULTS.md` ran without the 64 MB cap its row names.
- **`vk-count` and `present-dmabuf` do nothing on the bridge** (open-driver and PowerVR paths only).
- **`VKBRIDGE_STATS` as an env line reaches the game (the bridge's client) but not the bridge server**, which
  starts with only `HOME` in its environment and reads its settings from a file. INFERRED (the bridge's source
  is not in this checkout).

## 4. Noise floor from the runs on disk

Same-configuration repeats (same build, path, route, switches): five pairs on native + bridge
(`fight-native-bridge-2/3` on 21a1157; `lab2-fight-native-bridge-1/2` on c56cc02; on d1ddd2b `lab3-control-1/2`,
`lab3-zram-late-1/2`, `lab3-all-1/2`) and one on PanVK + `present-dmabuf` (`fight-native-panvk-dmabuf-2/3`).
Proton + bridge has one run per build (119.3 and 122.2 ms), so no same-build repeat. `fight-native-bridge-1` used
the older input method and is left out. VERIFIED from each `summary.json`.

| Pair | Animation p99 ms | Animation fps | Over 50 ms per min | MemAvailable MB |
|---|---|---|---|---|
| Bridge, 21a1157 | 36.1, 40.0 | 58.7, 58.6 | 10.7, 17.4 | 1633, 1644 |
| Bridge, c56cc02 | 35.7, 40.1 | 58.5, 58.4 | 8.0, 12.0 | 1319, 1627 |
| Bridge, d1ddd2b control | 37.2, 36.0 | 58.5, 58.3 | 13.4, 16.0 | 1153, 802 |
| Bridge, d1ddd2b `zram-game-late` | 35.7, 35.8 | 58.8, 58.6 | 9.3, 10.7 | 1141, 1056 |
| Bridge, d1ddd2b all three switches | 34.4, 36.0 | 58.6, 58.6 | 10.7, 12.0 | 960, 821 |
| PanVK + `present-dmabuf`, 21a1157 | 30.5, 29.6 | 59.7, 59.6 | 9.4, 6.7 | 383, 522 |
| **Pooled sd within pairs, bridge (5 degrees of freedom)** | **1.96** | **0.10** | **2.67** | **156** |
| Largest difference inside a pair | 4.4 | 0.2 | 6.7 | 351 |

**What n pairs can detect** (B minus A, each arm one full-mode fight; "just excluded" = 1.96 x sd x sqrt(2/n), the
difference at which a 95 % interval stops including 0; "found 4 times in 5" = 2.8 x sd x sqrt(2/n)):

| | 1 pair | 3 pairs | 6 pairs |
|---|---|---|---|
| Animation p99: just excluded / found 4 in 5 | 5.4 / 7.8 ms | 3.1 / 4.5 ms | 2.2 / 3.2 ms |
| Animation fps | 0.28 / 0.40 | 0.16 / 0.23 | 0.11 / 0.16 |
| Frames over 50 ms per minute | 7.4 / 10.6 | 4.3 / 6.1 | 3.0 / 4.3 |
| MemAvailable MB | 434 / 619 | 250 / 358 | 177 / 253 |

Reading it:

- **One pair can show** native against Proton (36 against 120 ms), the bridge against PanVK + `present-dmabuf`
  (37 against 30 ms), plain PanVK (46 to 49 ms). **It cannot show** anything under about 5 ms of p99: every lab-3
  arm (34.4 to 37.2 ms, controls 37.2 and 36.0) is inside one pair's noise, so "no change" there means "no change
  over 5 ms".
- **Three pairs** reach about 3 ms (8 % of the bridge's p99), **six pairs** about 2 ms (6 %). Below 2 ms the
  fight as scripted cannot tell: the p99 of 2 600 frames is its 26th worst frame.
- MemAvailable on the bridge is the noisiest metric against the effects looked for: lab 3's controls are 351 MB
  apart, more than most memory switches are expected to move. It drifts with what ran before (1 153 MB in the
  first lab-3 arm, 802 MB in the seventh), so memory needs interleaved arms, which confirm mode does.
- The estimate rests on 5 degrees of freedom: the true sd is between about 1.2 and 4.8 ms (95 %). Each confirm
  run adds a noise-floor line to `docs/bench/RESULTS.md` (the two lines there now are this table).
- Whether three fights in one launch average the noise down is not known: it depends on how much of the 2 ms is
  between launches. The detectable sizes above therefore count pairs, not fights. An A/A confirm run
  (`--a "" --b ""`) answers it.

## 5. Confirm mode's stopping rule, checked by simulation

The rule asked for (stop when a 95 % bootstrap interval on the p99 difference excludes 0, or after six pairs),
simulated with one fight per arm, sd 2.0 ms, 1 500 campaigns each:

| True difference | Interval alone, from 3 pairs | With the noise-floor condition (what is implemented) |
|---|---|---|
| 0 ms: called "different" | **37 %** | 9 % |
| 3 ms: called "different" | | 74 % (4.4 pairs on average) |
| 5 ms: called "different" | | 99 % (3.3 pairs) |

A percentile bootstrap over three to six values, looked at after every pair, excludes 0 far too easily, so the
harness also requires the difference to be at least what the earlier noise floor allows for that many pairs
(`--noise-sd`, default 2.0 ms; 0 turns the condition off) and at least `--min-pairs` (3). At six pairs without
that, the verdict is "no difference shown" or "interval excludes 0 but the difference is inside the earlier
noise floor". The 9 % is still above 5 %: a "different" from confirm mode is strong evidence, not proof, and a
result that matters deserves a second campaign with another `--seed` order of arms or a full-mode pair.

Cost (INFERRED): a launch-level A/B with three fights per arm is about 10 minutes a pair, so 30 to 60 minutes
for a verdict; with `--reps 1` about 5 minutes a pair. The same six pairs in full mode are 12 arms of 8 minutes,
96 minutes, with a third of the fights.

## 6. Other waits in the dev loop

**CI, Build APK** (the last ten successful runs, 07:39 to 12:29 UTC on Nov 28; VERIFIED from `ghf run view --json jobs`):

| Stage | Minutes, median (range) | Notes |
|---|---|---|
| Whole run, push to done | 20.0 (17.8-26.8) | |
| Prepare verified components | 2.9 (2.2-3.3) | cached (preloads, proot, PipeWire source); once it started 7.5 minutes late waiting for a runner |
| **Compile APK and Android tests** | **10.4 (8.9-11.8)** | half of the run: Gradle Build 5.0 (4.2-6.2), Unit tests 3.0 (2.2-3.3), which run before the APK is signed and uploaded |
| APK artifact available after the push | 14.2 (12.4-19.8) | the end of that job |
| Off-phone rig (three emulator scenarios) | 5.0 (4.7-6.3) | starts after the compile job; a quarter of the run |
| Python and native tests; native startup gate | 3.1; 3 to 5 | in parallel, not on the critical path |
| Runner waits seen | 3 minutes before the rig, 3 before the last job (perf/own-overhead); 7.5 before components (test/lab-3) | the two slowest runs |

- **Dominant:** the compile job (Gradle and unit tests), then the rig. Gradle, the CMake tree, the pack inputs
  and the Robolectric jars are cached already.
- **A docs-only change does not build** (`paths-ignore`: `**/*.md`, `docs/**`). **A tools-only change does**: a
  push that touches only `tools/bench/` or `tools/tests/` runs the whole 20 minutes, APK, rig and gate included
  (this branch's pushes do).
- **The lab waited for the whole run.** Lab 3: pushed 12:29:01, the compile job (and the APK) done 12:48:50, the
  run done 12:54:25, APK downloaded 12:55:13, first bench run 12:58:36. Fetching the artifact when the compile
  job ends would have saved 5.6 minutes; the rig's verdict can be read while the phone is being set up.

**APK install and first session** (VERIFIED from file times and `lab3-control-1`): 3.4 minutes from the APK on
the desktop to the first bench run starting (install over the old build, un-parking the bench profile, checks).
The first session after the install reached READY in 14.2 s, like every other (12.2 to 16.5 s): no first-start
cost shows.

**What the lab does one after another:** nine lab-3 arms started 7.7 to 8.8 minutes apart for runs of 7.3
minutes: about 50 s between arms (starting the next by hand, reading the last). While CI builds (20 to 25
minutes) the phone is idle. Each arm repeats preflight, Steam start, launch, quit and stop.

**Three changes with the best time saved per effort:**

1. **Screen with `--mode screen`, confirm with `--mode confirm`** (done here; needs the lab's first run). From 7.3
   minutes to about 1.4 per measured fight; a five-arm launch-level screen with three fights each under 30
   minutes, against 40 minutes for five single fights now, and the arms queue themselves, which also removes the 50 s
   between arms.
2. **Take the APK when the compile job finishes, not when the run does** (no code: `ghf run download <id> -n
   droiddeck-apk` once "Compile APK and Android tests" is green): 5 to 6 minutes per build. With it, **stop
   building the APK for pushes that touch only `tools/bench/**` and `tools/tests/test_bench_*`** (add them to
   `paths-ignore` and run the Python tests in a small workflow of their own): 20 runner-minutes per bench-only
   push, and no runner queue in front of the builds that matter. CI-side, not done here.
3. **Use CI's 20 minutes on the phone:** an A/A confirm run on the installed build (`--a "" --b ""
   --warmup-reps 1`) measures the day's noise floor and whether fights inside a launch are as noisy as fights
   across launches, the one number section 4 could not give. Then, in full mode, ask `pacing` and Perfetto for
   the script's real length (53 s, `benchlib.script_seconds`) instead of 77 s and halve the latency probe to 50
   presses: about 23 s and 55 s per full arm. Not done here because it changes what the default mode's `pacing`
   window covers.

## 7. What was built, and what is not verified

- `tools/bench/run.py`: `phases` in every summary; `--mode screen` (`--reps`, `--warmup-reps`, `--turns`,
  `--with-latency`, `--with-cpu`, `--with-perfetto`, `--with-pacing`, `--push-file`, `--restore-pushed`,
  `--stop-after`, `--stop`, `--restart`, `--max-thermal-status`); `--mode confirm` (`--a`, `--b`, `--max-pairs`,
  `--min-pairs`, `--noise-sd`, `--seed`, `--keep-running`). The default mode's options and flow are unchanged
  (its sleeps go through one helper that also books them; the step loop moved into a method).
- `tools/bench/benchlib.py`: `PhaseLog`, the switch-level table and `plan_change`, `parse_arm`, the push-path
  rules, `truncate_steps`, `offline_evidence`, `reps_identical`, `spread`, `pooled_sd`, `bootstrap_diff_ci`,
  `detectable`, `confirm_decision`, the noise-floor line.
- Tests: 45 in `test_bench_lib.py`, 10 in `test_bench_modes.py` (reuse, game relaunch, session restart, fallback
  when the settings cannot be used, a fight that does not restart, a rule broken on a kept session, a foreign
  session left alone, the wrong profile, push and restore, a confirm campaign).

Rules kept: the full preflight runs unchanged whenever a session is started; on a kept session the owner check,
media volume, the Offline and two sound settings (read from the app's preferences), the thermal status and the
blue badge are checked before every fight, Steam's own offline lines after every arm; the offline icon is seen
once per session, at its start, as now. A session the mode did not start itself is never touched. Any failure
ends the game and the session and restores switches, route and pushed files.

**INFERRED until the lab runs it:** the per-fight times; Continue repeating the fight within one launch;
Lanner's settings being clickable with a session up (else a session restart, automatically); `droiddeckctl
resume` bringing the session screen back after that; a live `logs latest` being complete; the guest seeing the
app's inbox folder at the same path (`LinuxRuntime.java` binds external storage there) for `--push-file`; a
session start restaging overlay files; the offline lines' time zone matching the phone's clock (it did in
`lab3-control-1`).

**Not done:** nothing ran on the phone; no CI change; the default mode's 77 s `pacing` request and 100-press
probe are as they were; `origin/test/lab-3-results` did not exist when this was pushed, so there was nothing to
rebase onto.
