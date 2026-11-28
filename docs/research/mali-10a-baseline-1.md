# Mali baseline 1: Slay the Spire 2 on a Pixel 10a (build ae32618, engine 134 embedded)

**Question.** Does trunk Lanner (`chat.comb.lanner`, build "perf/lanner-id at ae32618", same code as
`main` @ 2929cca) run Slay the Spire 2 (app 2868840) on a Pixel 10a (Tensor G4, Mali-G715, 8 GB,
Android 17), on which path (native engine 134 or Proton), and what are the launch times, menu frame
pacing, memory and thermals to compare later runs against?

**Short answer.** It runs, **through Proton, not the native engine.** The native engine was never tried:
the `native-engine` experiment is off on a fresh install and nothing on this phone had turned it on. Under
Proton + stock FEX on the open PanVK driver the game reaches its main menu about 45 to 53 s after the
launch request and holds about 46 fps there, p99 31 ms, thermal status 0. Memory is the problem: available
memory sits at 170 to 440 MB with swap about 90 % full for as long as the game is up. The native path on
this phone is still untested, and needs a save decision from Cedar first (section 5).

Marks: **VERIFIED** = read in a run's artifacts, a screenshot I looked at, or source; **INFERRED** =
reasoned from verified facts, not observed. Times are the phone's local clock (PST), Nov 27 2026.
Artifacts (outside the repo): `~/projects/droiddeck/output/mali-10a-baseline/` (`logs-A`, `logs-C`,
`shots/`, `A-*`/`C-*` samples, `logcat-stream.txt`).

## 1. Method

- Phone docked on USB, driven with `tools/droiddeckctl` (`DROIDDECK_PACKAGE=chat.comb.lanner`). Debugging
  tools stayed **off** the whole time.
- Each run: pre-test step (force-stop Chrome and `chat.comb.familiar.voice`, wait for MemAvailable over
  2.5 GB, record the header), check the Lanner switches (`setting-offline` On, `setting-game-sound` Off,
  `setting-boot-movie-sound` Off), media volume 0, `start steam`, wait READY, **screenshot and look for
  Steam's offline icon**, then `launch 2868840`, dismiss the game's start-up dialog by touch, measure for
  120 s at the main menu, `quit 2868840`, `stop`, `logs latest`.
- The game was started with `start steam` + `launch`, not the `play-last` button, so that Steam could be
  seen offline before the game started. The one-tap button is therefore not timed here.
- Frame times: the session's `frames.csv` (gamescope's frame times), computed the way
  `docs/handoff/phone-tests/fp.sh` does (sorted window, p = element at `int(q*n)`, frames over 50 ms per
  minute), over the same 120 s as `droiddeckctl pacing --seconds 120` (SurfaceFlinger: what reached the
  panel).
- CPU: `processes.csv` and the per-thread csv in the session bundle, plus three `top` samples 20 s apart.
  Memory: `memory.csv` (5 s rows), `/proc/meminfo`. Thermal: `droiddeckctl thermal`, `power.csv`,
  `clocks.log`. lmkd kills: a filtered `adb logcat -b main,system,events` stream kept for the whole
  campaign, because the device's log buffer only reached back about 4 minutes; counted lines are
  `lowmemorykiller: Kill '<process>' ... reason: ...`, the same format as the stale run's extract.

## 2. Run headers (pre-test numbers)

| | Run A (cold) | Run C (second launch) |
|---|---|---|
| Pre-test at | 19:02:38 | 19:12:27 |
| MemAvailable | 4151 MB | 4294 MB |
| SwapFree / SwapTotal | 2686 / 3876 MB | 2995 / 3876 MB |
| Battery temp, thermal status | 35.2 °C, 0 | 33.8 °C, 0 |
| Battery | 90 %, USB powered, "not charging" (80 % limit) | 87 % |
| lmkd kills since previous run | 0 (since the stream started, 19:02) | 30 (all during run A) |
| Lanner switches | offline On, game sound Off, boot-movie sound Off, volume 0 | same |
| Session id | `2026-11-27-04-steam` | `2026-11-27-05-steam` |

"Cold" for run A means a new session and no FEX code cache for this FEX build (`no cache yet`); the Lanner
app process itself was already up, and the stale-build runs had been made on this phone an hour earlier.
Run C found the FEX cache run A wrote (`2 files, 297.3 MB`).

## 3. Findings

### 3.1 Path: Proton, because the native engine is switched off (VERIFIED)

- `events.jsonl`, both runs: `session.boot engine_intended "Proton + stock FEX"`,
  `game.engine {"path":"proton","build":"FEX-2611-79-g727948a","native_skipped":null}`. `session.log`:
  `droiddeck-game-env: engine: proton FEX-2611-79-g727948a`. There is no `native-engine:` line at all, so
  this is not a fallback: the wrapper never ran.
- Why: `droiddeck-game-env` only tries the engine when the `native-engine` experiment is on
  (`experiment(env, "native-engine")`), and `Experiments.defaults` does not contain it. The session's
  experiment list (`build.txt`, boot card) is `godot-render,warmup,all,compat-quiet,memory,adpf-all,
  two-frames,one-limiter,native-hz,quick-tap,fex-codegen,fex-fast,fex-stats,free-memory`. In the app,
  Settings > Advanced > Experiments shows `setting-exp-native-engine` **Off**. `session.remote_config` is
  `applied: none`, so no remote config turned it on either.
- So HANDOFF.md's "the native ARM engine is the main path" describes Cedar's Pixel 10 as configured, not
  what a fresh install of trunk does. The APK footer says "engine 134", which reads as if it were in use.
- "Play ... beta" and "Play stable" are **not** an engine choice (VERIFIED in source): they are the game's
  Steam branches. `SteamBranch.request` writes `beta` or `public` for app 2868840 and
  `droiddeck-steam-branch` swaps the whole install between parked copies before Steam starts. Neither was
  pressed in this campaign.

### 3.2 Graphics path (VERIFIED)

- Game and Steam render through the open **PanVK** driver on the kbase kernel interface, not Venus:
  `graphics-report.txt` "path: open driver pair (PanVK / RADV)", "runtime driver:
  PanVK-Mali-kbase_Linux_glibc_", "bridge false, venus false", "auto would pick: nad:panvk-mali-kbase";
  the game's environment has `VK_ICD_FILENAMES=.../PanVK-Mali-kbase_Linux_glibc_/icd.json`. Godot reports
  `Vulkan 1.4.363 - Forward+ - Using Device #0: ARM - Mali-G715 MC7`. Mesa prints "panvk is not a
  conformant Vulkan implementation" and "BC emulation on, lowmem".
- The app's own compositor uses the phone's Mali driver (v1.r54p3, Vulkan 1.4.343). Output is
  **1616x720 at 120 Hz**; the game draws 16:9 inside it. gamescope runs `-r 60`.
- Settings > Advanced has "Mali (Venus): off" and "Mali bridge: off" buttons, so a Venus A/B looks
  possible from the UI. Not run here.

### 3.3 Launch timeline (VERIFIED: events.jsonl, frames.csv, screenshots)

| Stage | Run A | Run C |
|---|---|---|
| Session created | 19:02:58 (0) | 19:13:04 (0) |
| Steam UI first frame (`frame.first`, `steam.ui_first_frame`) | +11.7 s | +11.4 s |
| Launch requested | 19:04:17 (L) | 19:13:49 (L) |
| Game engine exec (`game.engine`) | L+7.4 s | L+7.6 s |
| First game frame (first `VULKAN` frame in frames.csv) | L+18.8 s | L+16.9 s |
| Main menu drawn behind the game's dialog | L+41 to 51 s (screenshots 10 s apart; last mode switch L+53 s) | L+45 s (screenshot at 19:14:35; last mode switch L+47 s) |
| Pauses over 1 s between first frame and menu (frames.csv) | 1.7, 10.6, 4.7 s | 1.1, 8.3, 4.3 s |

- The gap between Steam's first frame and the launch request is me taking the offline screenshot, not the
  app. Added up without it, session start to menu is about **65 s (A) and 58 s (C)**. The Pixel 10 native
  numbers in HANDOFF.md are 46 to 60 s, with Steam at 18 s: Steam's own start is faster here (11.5 s), the
  game's load under Proton is slower (about 37 to 46 s from exec against 17 s native on the Pixel 10).
- `game.first_frame` in both bundles is a stale event from an earlier session (negative offset), so the
  first-frame time above comes from frames.csv. `state.output` reads 1920x1080 at IDLE and 1616x720 in a
  session.
- The "Steam Input" dialog did not appear in either run.
- The game showed a different one-time dialog each launch: "Load Mods?" in A (Cancel pressed), "What's
  Early Access?" in C (Proceed pressed, the only button). INFERRED: the game's local `settings.save` is
  new on this phone, so its first-run notices are still working through.

### 3.4 Main menu, 120 s (VERIFIED)

| | Run A (19:07:14 to 19:09:14) | Run C (19:15:33 to 19:17:33) |
|---|---|---|
| frames.csv: fps, p50 / p95 / p99, max | 46.3, 21.6 / 28.3 / 31.6 ms, 218 ms | 46.8, 21.4 / 27.8 / 31.4 ms, 56 ms |
| frames.csv: frames over 50 ms per minute | 1.0 | 0.5 |
| `pacing`: fps on screen, p50 / p95 / p99 | 46.3, 24 / 25 / 33 ms | 46.8, 24 / 25 / 33 ms |
| `pacing`: off the 16 ms cadence | 57.6 % (16 ms: 2353, 24-25 ms: 2958, 33 ms: 175) | 56.3 % |
| CPU, game process (processes.csv / top) | 87 % / 87 % of one core | 106 % / 87 % |
| CPU, others (top) | Lanner 26, gamescope 22, surfaceflinger 19, mali-gpuq 18, steam 13, pulseaudio 12 | 25, 22, 18, 18, 13, 12 |
| MemAvailable (memory.csv mean) | 255 MB | 412 MB |
| Swap used of 3785 MB | 3371 MB | 3315 MB |
| Game RSS + swap | 361 + 1827 MB | 321 + 1751 MB |
| Biggest process | SlayTheSpire2.exe, about 2.07 GB RSS + swap | about 1.93 GB |
| Battery temp, thermal status, headroom | 35.9 °C, 0, 0.75 to 0.73 | 34.7 °C, 0, 0.72 to 0.71 |
| CPU frequency caps | none (`thermal`: no policy capped) | none |
| Power (power.csv, on USB, "not charging") | 3.4 W mean | 3.1 W mean |

- The two runs agree to within 0.5 fps and 0.3 ms at p99, so the menu numbers repeat.
- The menu runs at about 46 fps on a 120 Hz panel, so the panel sees a mix of 16, 24/25 and 33 ms
  intervals. No frame cap is logged (`sched` and `fps-30` are off), so INFERRED this is what the Proton
  path can produce on the menu, not a limiter. The game's main thread is 23 % of a core; the whole process
  under one core, so it is not obviously CPU-bound on one thread either. Not resolved here.
- pulseaudio at 12 % and the game's FMOD/audio threads at about 17 % together run with game sound off.
- Input: a touch tap opened Compendium and a pad `B` (second press; the first switched the game to pad
  glyphs) went back to the menu. `droiddeckctl input tap` takes **output** coordinates (1616x720 here):
  my first tap used screenshot-scale coordinates, was clamped to the bottom edge and hit nothing.
- Lanner's on-screen controls drew over the game in both runs. The Steam performance overlay
  (`setting-mangoapp` On) was not visible at the menu.

### 3.5 Memory (VERIFIED numbers, INFERRED risk)

- Run A fell from 3868 MB available at session start to **168 MB** at 19:06:15; swap used peaked at
  3499 of 3785 MB. `memory.lmk_risk` fired at 19:05:04 with 52 MB available and 52 MB swap free, and
  Lanner showed "Memory is almost full. Android may soon close Lanner together with Steam". Run C
  bottomed at 355 MB with `lmk_risk` at 363 MB.
- lmkd killed 30 processes in run A (19:04:21 to 19:05:00) and 6 in run C (all at 19:13:55), every one a
  cached or background app (lowest `oom_score_adj` 800: Camera, Settings). Reasons: "low watermark is
  breached", three times "and swap is low", once "min watermark is breached even after kill". **Lanner
  and the session survived both runs.** The stale-build run on this phone lost the whole session to lmkd
  the same afternoon, so the margin is thin and this was only the main menu.
- Steam before the game costs about 2.1 GB of available memory (3868 to about 1760 MB;
  steamwebhelper top at 335 to 416 MB). The game then pushes about 1.8 GB of itself and 0.6 GB of the
  Steam side into zram. `swapin_mb_s` was blank in memory.csv, so swap thrash was not measured.
- `memory.freed` reports `freedMb` of -154, 267, -103 and -211: the "free memory" step's own accounting
  goes negative three times out of four.

### 3.6 Differences from the Pixel 10 / PowerVR notes

| | Pixel 10 (HANDOFF.md, baseline-run-1) | Pixel 10a (this run) |
|---|---|---|
| Path measured | native engine 105 to 134 | Proton + stock FEX (native off by default) |
| GPU route | PowerVR system driver through libhybris | PanVK (open driver) on kbase; Venus off |
| Steam UI first frame | about 18 s | 11.5 s |
| Launch to menu | 46 to 60 s whole, game load 13.9 s | 58 to 65 s whole, 45 to 53 s from the launch request |
| fps | fight 46 to 48, p99 about 40 ms | menu only: 46 to 47, p99 31 ms |
| Thermal | 43 to 46 °C in fights; status 4 after 19 min on Proton | 34 to 36 °C, status 0 (menu, 5 min) |
| Memory | 1.7 GB available at start, swap full of other apps | 4.2 GB at start, 0.2 to 0.4 GB with the game up |

The PowerVR-specific switches (`godot-render`'s Mobile renderer on PowerVR, hybris, the CEF
`-cef-disable-gpu-compositing` path) did nothing visible here. Fight numbers were not measured.

## 4. What broke or misled

1. **Native engine never ran** (3.1). The headline question is still open on this phone.
2. **Memory is one bad minute from an lmkd kill** at the main menu with every other app closed (3.5).
3. The game's first-run dialogs differ per launch, so "dismiss the dialog" cannot be a fixed tap.
4. `game.first_frame` carries over from an earlier session; `state.output` is wrong at IDLE; `input tap`
   silently clamps out-of-range coordinates instead of refusing them.
5. Home screen: the build line ("Lanner 0.7.1 · ae32618 · ... · engine 134") is drawn over the
   "Settings" rail label.
6. The phone drained from 90 % to 85 % in 16 minutes on USB while reporting "not charging" (80 % limit
   active above 80 %), so battery-power readings on the dock are not meaningful, as on the Pixel 10.
7. `droiddeck-game-env` edits the game's own `settings.save` (`fps_limit`, `vsync`) when a frame cap is
   set (`sts2_settings_cap`). No cap was on in these runs, so nothing was written, but a capped
   experiment would change the game's settings on Cedar's profile.

## 5. Not done, and why

- **Native-engine launch.** It needs `setting-exp-native-engine` turned on, and I did not do that.
  VERIFIED in source: `droiddeck-native-godot` and `launch_env` set no user-data mapping, so the native
  engine's `user://` is the Linux default in the guest home, not the Proton prefix's
  `AppData/Roaming/SlayTheSpire2` where this phone's game data sits. INFERRED: an offline native launch
  would open a second, empty local profile and write fresh save files beside Cedar's real ones, and what
  Steam Cloud does with those on the next online session is unknown. That is a save decision, so it waits
  for Cedar.
- Proton vs native comparison (same reason), Venus vs PanVK A/B (stretch; the buttons exist), fights
  (out of scope), the one-tap `play-last` path (would start the game before Steam could be seen offline).

## 6. Next experiments, by expected value

"P99 first, battery second", and the HANDOFF open-work list.

1. **Native engine on the 10a (open work 1, and the biggest P99 and memory lever).** Before the first
   launch: confirm where the native engine reads and writes saves, back the Proton-side saves up
   (`docs/handoff/phone-tests/backup.sh`, re-pathed), and get Cedar's go on running it online or offline.
   Then run A and C again with `native-engine` on. Confirmed if `game.engine path=native`, the profile
   shows Cedar's progress, and game RSS + swap drops well under 2 GB. If it falls back, the
   `native-engine:` lines in session.log say why. Also decide whether `native-engine` belongs in
   `Experiments.defaults` now that native is "the main path".
2. **Memory headroom on 8 GB.** Whichever path wins, measure with `webhelper-trim` on (Steam's 0.6 GB in
   swap), `tex-astc` (PanVK's BC emulation is in "lowmem" mode and Mali has ASTC), and `vk-blocks-64`.
   Target: over 800 MB available at the menu and no `lmk_risk` event.
3. **Fight P99 on the chosen path** (open work 5), once Cedar picks a save to fight in: 2 minutes,
   fp.sh numbers, with thermals over 20 minutes since the Pixel 10 only throttled late.
4. **Why 46 fps at the menu.** One run with `composite-timing` and one with `godot-render-thread`; if
   gamescope's `-r 60` or PanVK is the limit, Venus A/B from Settings > Advanced is the next comparison.
5. **Boot and resume (open work 2).** Steam is 11.5 s and the game 46 s here, so
   `steam_native_prestart` and the freeze/standby path are worth more on the 10a than on the Pixel 10;
   both need the native engine first, and frozen processes need the memory from item 2.
6. **Steam toast blackout (open work 3).** Not seen (offline, no toasts). Check with `BL_STEAM_TOASTS=1`
   on PanVK, where CEF may composite on the GPU.
7. **Steam start (open work 4).** 11.5 s on the 10a against 18 s; lower priority than on the Pixel 10.
8. Small fixes: reset `game.first_frame` per session, report the live output size in `state`, refuse
   out-of-range `input tap`, move the build line off the rail label, fix `memory.freed` accounting.
