# Mali fight 1: the first fight baseline on a Pixel 10a (build 21a1157, engine 135)

**Question.** Every number from this phone so far was taken at Slay the Spire 2's main menu. Is a fight smooth,
in which configuration (native engine or Proton; the open PanVK driver or the "Mali bridge"), how long does a
press take to reach the screen, and can the native engine be trusted to write saves?

**Short answer.**

- **Native engine on the Mali bridge is the only configuration that is close to smooth, and it is not smooth
  yet.** In the scripted fight it holds 58.7 fps (the game's own 60 cap) with frame time p50 16.6 ms, p95 21.7 ms
  and **p99 36 to 40 ms inside the animation windows** (two runs), 11 to 17 frames over 50 ms per minute and one
  frame of 170 to 180 ms on the first card of each launch. Press-to-screen is **p50 88 to 91 ms, p95 105 ms**.
  Free memory 1.63 GB, no low-memory warning, phone at 32 to 33 °C with thermal status 0.
- **Proton is far worse in a fight than at the menu.** On the bridge: animation p99 **119 ms**, 76 frames over
  50 ms per minute, a 685 ms frame on the first card and 100 to 150 ms frames on every attack, including the
  ninth. On PanVK: p99 68 ms at 37 fps. The menu hid this completely (both paths read 60 fps there).
- **PanVK, the route this phone was set to, does not survive a fight on the native engine.** Three attempts:
  a 2.9 s freeze on the first card; a GPU job timeout (`kbase: timeout`), 17.7 s without a frame and `Vulkan
  device was lost`; and one complete run at 35 fps, p99 49 ms, with a 927 ms frame. Press-to-screen is 114 ms.
- **`present-dmabuf` (lab request #9) is confirmed and is the best result of the campaign:** PanVK spent 20 to
  22 ms of every frame inside the present call; with the switch it is 0.16 ms, and the same fight runs at 59.7
  fps with **p99 30 ms** (three runs), press-to-screen p50 81 to 85 ms, the picture intact. It does not fix
  PanVK's memory: 0.4 to 0.75 GB free, one low-memory warning.
- **Native save writes are mechanically sound** (a new profile, a new run, three turns, Save and Quit, Quit, a
  profile switch): only the expected files changed in Steam's remote storage and Proton opened the
  native-written profile and run and played the same fight from it.
- **But the game installed on this phone is older than the owner's saves, and that is a live hazard.** The
  phone has v0.111.1 (progress schema 24); the owner's Profile 1 is schema 25. At the very end, quitting from
  the game's menu with Profile 1 selected made the game rewrite Profile 1's `progress.save` as schema 24:
  289 106 to 263 646 bytes, 14 keys gone (the "blitz" and "dread" preferences, `max_blitz_ascension`), stats
  trimmed. Steam was offline, nothing synced; the file, its native mirror and Steam's index entries were
  **restored from the backup and verified** (sha256 `29d08792…451a81`, as before the campaign). Any clean exit
  on Profile 1 from this phone will do it again, on either path (INFERRED for Proton: same game build), and
  with Steam online it would upload. Section 11 has the details; section 10 what to do about it.

Sections 4 to 10: the menu for continuity, `present-dmabuf`, the 60 against 120 cap, what press-to-screen is
made of, the CPU picture, lab request #3's checklist and the recommendation.

Marks: **VERIFIED** = read in a run's `summary.json` or raw artifacts, a screenshot I looked at, a file hash from
the phone, or source; **INFERRED** = reasoned from verified facts, not observed. Times are the phone's clock
(PST); the campaign ran from Nov 27 23:31 to Nov 28. Artifacts (outside the repo):
`~/projects/droiddeck/output/bench/<run>/` (`summary.json`, `run.log`, `shots/`, `sampler.txt`,
`top-threads.txt`, `latency-*.json`, `pacing.json`, `trace.pftrace`, `logs/`), `manifests/` (save hashes between
steps), `explore/` (the screenshots from setting the profile up), and the save backup in
`~/projects/droiddeck/output/sts2-save-backup-20261128T073323Z/`.

## 1. Method

The harness is `tools/bench/run.py`; `tools/bench/README.md` has the full procedure and the `summary.json`
schema. In short:

- **Build.** `chat.comb.lanner`, `test/lab-1` at 21a115726 (CI run 46121398828, package and signer checked),
  installed over "ux/design-pass-1 at 2b24df8" with `adb install -r`. It carries native engine 135 (the earlier
  notes ran 134). `pe-align` reads On by itself on this build.
- **Rules, enforced by the harness before every session:** Lanner in front and USB power; Offline mode On and
  Steam's crossed-out network icon found in a screenshot before the launch (the crop is kept per run; Steam's
  log has `User is offline` at every game start); Game sound Off, boot-movie Sound Off, media volume 0; Chrome
  and `chat.comb.familiar.voice` stopped; MemAvailable over 2.5 GB.
- **Profile.** The game's profile screen showed Save Profile 1 (269 h of play in the game's own count, the
  owner's), Save Profile 2 (0:00, last updated Aug 13) and Save Profile 3 (Empty). All bench work is on
  **Profile 3**, so no existing profile's file is touched.
- **The fight.** A new profile's first run "presents rooms in a set order" (the game's log), so floor 1 is
  always one Nibbit (45 HP, encounter `NIBBITS_WEAK`); the game has no seed entry on a new profile. The run
  made here is Ironclad, seed `KW6PMT0GVE5V`. The game writes `current_run.save` when a fight starts and "Save
  and Quit" does not move it, so **Continue starts the same fight with the same hand every time, on both
  paths** (VERIFIED in every run: enemy bar full, hand Bash + 4 Strikes). The script never wins: the Nibbit ends
  on 8 HP.
- **Scripted sequence** (pad presses through the app's agent bridge, the same virtual pad the on-screen
  controls drive): turn 1 d-pad right, Strike x3 (each A, A 0.8 s apart: select, then confirm the target), Y
  (End Turn; the Nibbit attacks for 12); turn 2 right x3, Strike, Defend, Defend, Y (attack for 6, block);
  turn 3 Strike x3, Y (buff). 9 cards, 7 of them attacks, 3 enemy turns, 45 s of windows. After each step one
  screenshot checks the enemy's health bar (39, 33, 27 ... 8 of 45) and, after End Turn, the new hand; a
  mismatch aborts the run. Then Start, Save and Quit, Quit, Yes: every game exit was through the game's menu
  except in aborted runs.
- **Animation windows.** A card: from its first A being sent until 2 s after its second A. End Turn: from Y
  being sent until 6 s after. Fixed lengths, so every arm is measured over the same game events. "Animation"
  numbers are the frames inside those windows; "overall" also has the short waits between steps.
- **Frame times** are the session's `frames.csv` read the `fp.sh` way (fps = frames / span, percentile =
  `sorted[int(q*n)]`), plus frames over 33 ms and over 50 ms per minute. On the native path the engine's own
  counter agrees with it to 0.3 fps in every run.
- **Press-to-screen** is `droiddeckctl latency`, 100 presses alternating `pad:right` / `pad:left` at the start
  of the fight (the focus moves between Bash and the first Strike; nothing is played), watching the patch of
  screen where Bash's tooltip appears and disappears. It runs from the moment the app injects the press to the
  moment the first game frame that changed that patch **reached the panel** (`endsAt: display`, the driver's
  display timing). It includes Steam Input, the game's own input and animation code, the game's render, the
  present through gamescope and the app's compositor, and SurfaceFlinger. It does not include the touch
  controller, Android's dispatch of a real touch to the on-screen pad, or the panel's response. About 60 of
  100 presses are answered: when the lifted card's glow is already in the patch the probe rejects the sample
  as noisy (`no-change`).
- **Also captured per run:** `pacing` (SurfaceFlinger's view), a 10 s sampler (`/proc/meminfo`, `dumpsys gpu
  --gpumem` per owner, temperature, thermal status, clocks), lmkd kills, `top -H` every 5 s through the fight,
  a 60 s Perfetto trace (sched, frequency, gfx, frame timeline, memory counters; about 100 MB each), the
  session's own logs.
- **Inputs do not disturb the frames.** The first complete run sent presses with `content call`, which starts
  an Android runtime for each (1.2 s, a visible 30 to 55 ms frame 0.4 s later). The harness now sends them
  with `cmd activity broadcast` (0.1 s, no runtime). That first run (`fight-native-bridge-1`) is kept as the
  write test but not used for frame numbers.
- **Measurement load, the same in every arm:** Perfetto's `traced_probes` 7 to 8 % of a core, `top` 4 %,
  Debugging tools on.

## 2. Backup and the native write test (rule: saves outrank everything)

- **Backup** at 07:33:23 UTC (Nov 27 23:33 PST), no game running, before any save-affecting step:
  `/root/lanner-save-backup-20261128T073323Z/` inside the app's container (16 MB, left there) and the same tree
  pulled to the desktop. Contents: all of Steam's `userdata/155889176/2868840` (112 files), the Proton `user://`
  (`steam/`, `localization_override/`: 107 files), the native `user://` (`/root/.local/share/SlayTheSpire2/`,
  same two folders: 107 files) and `override.cfg`. Saves and settings only; no binaries, no `.pck`, no shader
  caches (checked by name). Every file's sha256 is in the four `SRC-*.sha256` lists; both copies were checked
  against them (`sha256sum -c`, all OK) and the pulled archive has the hash the phone reported (`36c26e88…e329`).
  The earlier backup (`…T050207Z`) is still there too.
- **What the native engine wrote** (VERIFIED: `native-engine.log`, and hash manifests taken before and after):
  choosing Save Profile 3 wrote `profile.save` (`last_profile_id` 1 to 3); starting the run wrote
  `profile3/saves/progress.save` (1090 bytes), `prefs.save` (351) and, on entering the fight,
  `current_run.save` (35 000, then 35 224 bytes), each "to steam remote store" and to the native mirror. Each
  later Continue rewrites `current_run.save` (same size, different bytes); each Quit rewrites `prefs.save`
  and `progress.save` with the same bytes. Steam rewrote its index `remotecache.vdf`.
- **Against the backup after the write test** (`fight-native-bridge-1`: Continue, 9 cards, 3 enemy turns, Save
  and Quit, Quit from the game's menu; the game logged `Quit button pressed` and exited by itself):
  - Steam remote storage: changed `remote/profile.save` and `remotecache.vdf`; new
    `remote/profile3/saves/{current_run,prefs,progress}.save`; **all 110 other files identical**, among them
    every file of `profile1` (the 289 KB `progress.save`, `prefs.save`, 100 history runs), `profile2` and
    `modded/`.
  - Proton `user://`: identical, nothing new (Proton had not run yet).
  - Native `user://`: `profile.save`, `settings.save` and `settings.save.backup` changed (the game marked its
    Early Access notice as seen and rewrites the file at every exit); new `profile3/…` and
    `profile.save.backup`; everything else identical.
  - `override.cfg` identical. The backup itself intact.
- **Proton sees the same state** (VERIFIED on screenshots of the first Proton launch after the native writes,
  offline): the badge reads "Profile 3", the menu has Continue and Abandon Run, and Continue opens floor 1
  against the Nibbit at 45/45 with Bash and four Strikes. Proton then played the same script to the same
  health values (39, 33, 27, …, 8), which also shows the run's random state survived the round trip. Later
  native launches continued from runs last touched by Proton without complaint.
- **Verdict: native save writes are mechanically sound** for profile selection, a new profile, a run start
  and continuing a run: the bridge's remote-storage writes land where Proton's do and Proton reads them. Not
  exercised: finishing or abandoning a run (the bench run was kept, section 11), unlocks, the run history of
  a profile that has one, and a first sync with Steam online, which is where a wrong file would travel to the
  owner's other computers. Nothing here went online.
- **What is not sound is the game version, on either path** (section 11, item 1): this phone's game writes
  progress schema 24 and the owner's Profile 1 is schema 25, so a clean exit with Profile 1 loaded downgrades
  that file. It happened once, at the end, and was restored from this backup.

## 3. Results: the scripted fight

All runs: same build, Steam offline, sound off, thermal status 0 at the start, Debugging tools on, the game's
own cap at 60 fps (section 6 has the 120 arm). Numbers are from each run's `summary.json`. "Animation" = inside
the animation windows (45 s per run: 27 s of card plays, 18 s of enemy turns).

| | Native, bridge | Native, bridge (repeat) | Native, PanVK | Native, PanVK + `present-dmabuf` (3 runs; the first also had `vk-count`) | Native, Venus | Proton, bridge | Proton, PanVK |
|---|---|---|---|---|---|---|---|
| Run | `fight-native-bridge-2` | `-bridge-3` | `fight-native-panvk-2` | `fight-native-panvk-dmabuf`, `-2`, `-3` | `fight-native-venus-1` | `fight-proton-bridge-1` | `fight-proton-panvk-1` |
| **Animation fps** | **58.7** | **58.6** | 35.2 | **59.7 / 59.7 / 59.6** | 50.7 | 51.5 | 37.0 |
| **Animation frame time p50 / p95 / p99 ms** | 16.5 / 21.8 / **36.1** | 16.6 / 21.6 / **40.0** | 27.3 / 35.7 / **49.1** | 16.5 / 20.3 / **30.5, 30.5, 29.6** | 16.6 / 24.3 / **30.1** | 16.4 / 32.4 / **119.3** | 24.9 / 41.1 / **68.0** |
| Longest frame ms | 169 | 181 | 927 | 85 / 125 / 112 | **6409** | 685 | 357 |
| Frames over 33 / over 50 ms per minute | 49 / 11 | 54 / 17 | 214 / 17 | 31 / 5, 27 / 9, 30 / 7 | 22 / 7 | 147 / 76 | 198 / 64 |
| p99: card plays; enemy turns ms | 35.1; 37.8 | 40.3; 40.0 | 47.5; 49.1 | 29.8; 32.8 (first run) | 30.3; 30.1 | 135.5; 58.3 | 70.0; 68.0 |
| Overall fps, p99 ms (waits included) | 58.9, 35.5 | 58.8, 36.6 | 35.4, 46.2 | 59.8, 29.8 | 51.5, 30.1 | 52.4, 110.1 | 37.7, 65.4 |
| `pacing`: fps on screen, p99, off cadence | 59.2, 33 ms, 27 % | 59.1, 33, 26 % | 35.4, 50, 48 % | 59.8, 33, 29 % | 52.4, 33, 35 % | 54.7, 74, 28 % | 39.0, 66, 70 % |
| **Press-to-screen p50 / p95 / p99 ms** (answered) | **91.3 / 106.3 / 187.6** (36) | **88.4 / 104.8 / 448.8** (61) | 114.3 / 131.1 / 134.9 (60) | **81.4 / 92.8 / 97.2**, 83.4 / 114.3 / 203.1, 84.5 / 98.1 / 100.5 (60 each) | 81.0 / 94.2 / 107.8 (60) | 94.0 / 108.9 / 123.3 (60) | 111.2 / 600.3 / 625.6 (69) |
| Press to the changed game frame p50 ms; game frames it took | 65.6; 4 | 63.9; 4 | 84.7; 3 | 53.8; 3 | 57.5; 4 | 66.0; 4 | 82.9; 3 |
| **MemAvailable mean / min MB** | **1633 / 1588** | **1644 / 1603** | 728 / 679 | 755 / 732, **383 / 344**, 522 / 488 | 1149 / 1077 | 745 / 692 | **208 / 148** |
| Swap free MB; zram MB | 2057; 510 | 1992; 546 | 2272; 420 | 2416; 362 (first run) | 1886; 519 | 405; 1114 | 275; 1191 |
| GPU MB (`Gpu:`) + dma-buf heap MB | 1278 + 883 | 1287 + 907 | 2670 + 415 | 2624 + 446, 3125 + 457, 2988 + 391 | 226 + 2437 | 1262 + 881 | 2532 + 429 |
| Largest GPU owners MB | bridge server 1607 | bridge server 1586 | game 1229, Xwayland 826, web helper 388 | game 1204 to 1462, Xwayland 827 to 1093, web helper 388 | render server 2183 | bridge server 1616 | game 1210, Xwayland 706, web helper 388 |
| `memory.lmk_risk` | none | none | none | none, **`memory` at 377 MB**, none | none | `memory` at 374 MB | `memory_and_swap` at 369 MB |
| lmkd kills in the run (lowest adj) | 0 | 3 (915) | 2 (905) | 14 (800), 4 (955), 2 (935) | 9 (905) | 2 (905) | 5 (800) |
| Engine's own counter: fps, main thread ms per frame (busy) | 59.1, 8.0 (48 %) | 58.8, 8.2 (48 %) | 35.5, 16.0 (57 %) | 59.8, 6.4 (38 %) | 52.2, 4.5 (23 %) | not logged | not logged |
| GPU clock mean MHz (of 940) | 580 | 591 | 770 | 739 / 725 / 719 | 490 | 619 | 760 |
| Battery °C start, max; thermal status max | 31.2, 32.2; 0 | 33.9 (start), 33.2; 0 | 30.7, 32.4; 0 | 32.6, 33.8; 0 | 32.7, **36.7; 1** | 31.5, 32.6; 0 | 32.3, 33.8; 0 |
| Launch request to main menu s | 32 | 33 | 32 | 33 | 32 | 61 | 52 |

Engine and route in every run as intended (VERIFIED: `game.engine path=native, native-engine-135` or
`path=proton`; `graphics-report.txt` "Mali bridge", "open driver pair (PanVK / RADV)" or "Mali (Venus)"), no
fallback to Proton, and on the native path the game's log lists the nine cards and the Nibbit's three moves
(`BUTT_MOVE`, `SLICE_MOVE`, `HISS_MOVE`) in every completed run.

**Noise.** The two native bridge runs differ by 0.1 fps, 3.9 ms in animation p99, 3 ms in press-to-screen p50
and 11 MB in MemAvailable; the three `present-dmabuf` runs by 0.1 fps and 0.9 ms in p99. Press-to-screen p99
and the longest frame are single events and do not repeat (187 against 449 ms; 85 to 125 ms). MemAvailable on
PanVK moves by 370 MB between identical runs (383 to 755 MB), because Xwayland's share of GPU memory does
(827 to 1093 MB), as in the earlier notes. So differences under about 4 ms in p99, 5 ms in press-to-screen p50
or, on PanVK, 400 MB of memory are not findings.

### 3.1 What the table says

- **Proton against native, same route.** On the bridge the fight costs Proton 7 fps and triples p99 (119 against
  36 to 40 ms); on PanVK p99 is 68 against 49 ms. Every attack on Proton has a run of 100 to 150 ms frames
  (the per-step worst frames are 685, 143, 149 ms for turn 1's three Strikes and still 153, 154, 137 ms for
  turn 3's), so this is not a first-use cost that plays out. Free memory is 0.75 GB against 1.63 GB on the
  bridge and 0.2 GB on PanVK with `lmk_risk`. The menu showed none of it: both paths hold 60.0 fps with p99
  20.8 and 20.9 ms there (section 4). **This is the first measurement that shows what the native engine is
  for.**
- **Bridge against PanVK, native.** 58.7 against 35.2 fps, p99 36 to 40 against 49 ms, press-to-screen 90
  against 114 ms, 1.63 GB against 0.73 GB free. PanVK is worse in the fight than at the menu (35 against 41
  fps).
- **`present-dmabuf` removes PanVK's frame-rate problem entirely** (section 5) and gives the best frame
  times of the campaign, but not its memory problem.
- **Venus** reaches p99 30 ms but freezes for 6.4 s on the first card and 1.7 s in turn 3, its web helper
  burns a full core (103 %) and it is the only arm that heated the phone (36.7 °C, thermal status 1, the middle
  CPU cluster capped at the end). Not a candidate.
- **Where the 60 cap binds.** On native bridge and native PanVK + `present-dmabuf` the fight sits at the cap
  (p50 16.5 to 16.6 ms, fps 58.6 to 59.7; the shortfall from 60 is the slow frames). Venus (50.7), Proton
  bridge (51.5) and everything on plain PanVK (35 to 37) are below it. At the menu both bridge arms are on it
  (60.0).

### 3.2 Why native is "close to smooth" and not smooth

On the bridge and with `present-dmabuf` alike, the frame in which a card lands takes 40 to 57 ms, and each
enemy turn has one frame of 45 to 130 ms; the first card of a launch costs 140 to 180 ms on the bridge and 85
ms with `present-dmabuf`. Worst frame per step, ms (c = card, e = End Turn):

| Run | Turn 1 | Turn 2 | Turn 3 |
|---|---|---|---|
| Native, bridge (2) | c 169, c 40, c 57, e 61 | c 44, c 52, c 41, e 66 | c 45, c 36, c 38, e 129 |
| Native, bridge (3) | c 140, c 181, c 51, e 56 | c 42, c 56, c 43, e 63 | c 32, c 37, c 47, e 96 |
| Native, PanVK + `present-dmabuf` | c 85, c 41, c 39, e 45 | c 56, c 49, c 45, e 48 | c 45, c 30, c 42, e 84 |
| Native, PanVK | c 108, c 60, c 39, e 64 | c 49, c 57, c 40, e 169 | c 49, c 41, c 43, e 927 |
| Proton, bridge | c 685, c 143, c 149, e 143 | c 135, c 83, c 56, e 110 | c 153, c 154, c 137, e 254 |
| Proton, PanVK | c 287, c 104, c 56, e 195 | c 60, c 133, c 58, e 357 | c 100, c 65, c 60, e 298 |

These frames are in the game process on both graphics routes, at the moment the game's card and turn logic
runs: the profiler arm (section 8) shows them as CPU time on the engine's main thread with the .NET JIT and
runtime in most of the worst ones. They are what "p99 36 ms" consists of, and the next thing to remove.

### 3.3 PanVK without `present-dmabuf` fails in the fight

Three attempts on the native engine, the configuration this phone had before the campaign:

1. `fight-native-panvk-1`: the first Strike froze the picture for **2.9 s** (one frame of 2924 ms); the
   harness, then stricter, aborted on its 2 s check.
2. `fight-native-panvk-1-retry`: during the latency presses the game stopped producing frames for **17.7 s**;
   `native-engine.log` has nine `MESA: error: kbase: timeout snapshot subqueue …` lines, an 8 fps window and
   **`ERROR: Vulkan device was lost`**. The script's presses went nowhere; aborted.
3. `fight-native-panvk-2` (the table's column): completed, with a 927 ms frame in the last enemy turn.

The `vk-count` run (`fight-native-panvk-vkcount`, 37.0 fps, p99 45.9 ms) completed too; its counting layer
saw one present call of 258 ms and one gap of 1.9 s between presents. No such line or freeze in the three
`present-dmabuf` runs. Three sessions are not enough to say `present-dmabuf` removes the GPU timeouts, only
that they were not seen with it.

## 4. The menu, for continuity (120 s from L+240 s, on the bridge)

| | Native, bridge (`menu-native-bridge-1`) | Proton, bridge (`menu-proton-bridge-1`) |
|---|---|---|
| fps; p50 / p95 / p99 ms; longest | 60.0; 16.7 / 19.2 / 20.8; 127 | 60.0; 16.7 / 19.3 / 20.9; 25 |
| `pacing`: fps on screen, p99, off cadence | 59.9, 33 ms, 13 % | 60.0, 33 ms, 15 % |
| Press-to-screen p50 / p95 / p99 ms (answered) | 82.6 / 97.6 / 108.0 (50) | 80.3 / 263.7 / 902.1 (54) |
| MemAvailable mean / min MB; lowest after launch | 1616 / 1447; 1275 | 741 / 577; 377 |
| GPU MB + dma-buf heap MB | 1239 + 1176 | 1192 + 936 |
| `memory.lmk_risk` | none | `memory` at 372 MB |
| Engine counter: fps, main thread ms (busy) | 60.0, 5.4 (32 %) | not logged |
| CPU (one core = 100): game; bridge server; Lanner; gamescope | 52; 33; 36; 14 | 84; 23; 37; 12 |
| Launch request to main menu s | 28 | 51 |

These repeat `mali-10a-gpu-1.md` (60.0 fps, p99 20.4 to 20.9 ms; 1385 and 718 MB) on the new build and on the
bench profile, whose menu has five entries instead of six. Press-to-screen at the menu was measured with
`pad:down` / `pad:up` between Continue and Abandon Run (nothing activated), watching those two entries; every
second press is answered, for the same reason as in the fight. **The menu and the fight have the same
press-to-screen p50 (83 against 88 to 91 ms), so the delay is not fight load.**

## 5. `present-dmabuf` (lab request #9): confirmed

The diagnosis was that PanVK on this phone presents in software: every frame is copied and the game's thread
waits for the GPU inside the present call. Plans A and B were written for the menu; they were run in the fight
instead, which is the harder case, with the menu arms of section 4 as the bridge reference.

| | Plan A: PanVK + `vk-count` | Plan B: PanVK + `vk-count` + `present-dmabuf` | Pass line in the ticket |
|---|---|---|---|
| Run | `fight-native-panvk-vkcount` | `fight-native-panvk-dmabuf` | |
| `vk-count` "present" ms per frame | **19.8 to 22.5** | **0.14 to 0.17** | under 1.5 (and "10 to 13" expected for A) |
| Time between presents p50 ms | 25 to 30 | 16.5 to 17.0 | |
| fps (animation windows) | 37.0 | 59.7 | 58 or more |
| p99 ms (animation windows) | 45.9 | 30.5 | 20 or less (set for the menu) |
| Frames over 50 ms per minute | 17.4 | 5.3 | none (set for the menu) |
| gamescope CPU (one core = 100) | 21.8 | 7.8 | under 12 |
| Game process CPU; engine main thread ms per frame | 75; 15.0 | 60; 6.4 | |
| Press-to-screen p50 / p99 ms | 112.5 / 585.2 | 81.4 / 97.2 | p50 at least 15 lower; p99 under 70 |
| Game's GPU memory MB | 1229 | 1204 | within 50 |
| Swapchain the game asked for | 1920x1080, MAILBOX, 3 images | the same | |

- **The diagnosis is confirmed** (VERIFIED): 20 to 22 ms of every frame was spent inside
  `vkQueuePresentKHR` (more than the 10 to 13 ms predicted), and with the switch on it is 0.16 ms. The frame
  rate goes from 37 to the 60 cap, the engine's main thread from 15.0 to 6.4 ms a frame, gamescope from 22 to
  8 % of a core, press-to-screen p50 down by 31 ms. `session.log` says `present-dmabuf: PanVK swapchain images
  are dma-bufs (linear), no CPU copy or GPU wait in present`.
- **The picture is intact** (VERIFIED on stills: the menu and fight screenshots I looked at show the same
  picture as the bridge, no black or torn frame; every automatic health-bar and hand check passed in all
  three runs). Flicker between stills was not looked for with a recording.
- **It repeats:** three runs, p99 30.5, 30.5 and 29.6 ms, press-to-screen p50 81, 83 and 85 ms.
- **Against the ticket's lines:** present time, fps, gamescope CPU, latency p50 and GPU memory pass. The p99
  line (20 ms) and "no frame over 50 ms" were written for the menu and are not met in a fight on any route
  (section 3.2); press-to-screen p99 was 97, 203 and 101 ms against the 70 ms line.
- **Not done from Plan B's regression list:** Steam overlay and back, a toast, background and return,
  picture-in-picture, rotation, screen off and on, the 10 minute file-descriptor count, the AFBC variant, and
  Plan C. Quit to Steam and relaunch worked (each run is a fresh launch). Proton with the switch was not run.
- **What it does not fix: memory.** PanVK still holds 2.6 to 3.1 GB of GPU memory (game 1.2 to 1.5, Xwayland
  0.8 to 1.1, web helper 0.39), so MemAvailable in the fight was 755, 383 and 522 MB against 1.63 GB on the
  bridge, with one `lmk_risk`. And the GPU job timeouts of section 3.3 are PanVK's, not the present path's.

## 6. Headroom: the game's cap at 60 against 120 (native, bridge)

For this arm only the native engine's own `settings.save` (local to `/root/.local/share/SlayTheSpire2/`, not in
Steam's remote storage, separate from Proton's) had `fps_limit` changed from 60 to 120 with the game closed,
after copying both files aside; it was put back afterwards and its sha256 is the one from before the arm
(`8ea8e328…ec43b`, VERIFIED). The game did not rewrite the file during the run. Proton's settings and Steam's
remote storage were not touched for this.

| Fight, native, bridge | Cap 60 (`fight-native-bridge-2`, `-3`) | Cap 120 (`fight-native-bridge-cap120`) |
|---|---|---|
| Animation fps | 58.7, 58.6 | **85.7** (card plays 81.9, enemy turns 91.4) |
| Animation frame time p50 / p95 / p99 ms | 16.5 / 21.8 / 36.1; 16.6 / 21.6 / 40.0 | 11.1 / 18.7 / **28.2** |
| Longest frame ms; over 33 / over 50 ms per minute | 169, 181; 49 / 11, 54 / 17 | 232; 28 / 9 |
| `pacing`: fps on screen, p99, off cadence | 59.2, 33 ms, 27 % | 83.5, 25 ms, 39 % |
| Press-to-screen p50 / p95 / p99 ms | 91.3 / 106.3 / 187.6; 88.4 / 104.8 / 448.8 | **74.7 / 90.8 / 95.2** (67 answered) |
| Press to the changed game frame p50 ms; game frames it took | 65.6, 63.9; 4 | 51.6; 4 |
| Engine counter: fps, main thread ms per frame (busy) | 59.1, 8.0 (48 %) | 84.9, 6.3 (53 %) |
| CPU (one core = 100): game; bridge server; Lanner; gamescope; SurfaceFlinger + composer | 78; 41; 25; 12; 43 | 82; 48; 39; 13; 50 |
| Whole phone, of 800: user + system + interrupts | about 350 | about 370 |
| GPU clock mean MHz (of 940) | 580, 591 | 709 |
| Battery °C start, max; thermal status | 31.2, 32.2 and 33.9, 33.2; 0 | 33.8, **35.1**; 0; no CPU policy capped |
| MemAvailable MB | 1633, 1644 | 1584 |

- With the cap lifted the fight runs at **86 fps, not 120**: the engine's main thread is 53 % busy, so the
  limit is on the GPU and present side (INFERRED from the GPU clock at 709 of 940 MHz and the bridge server at
  48 % of a core; not broken down further).
- **p99 falls from 36 to 40 ms to 28 ms and press-to-screen p50 by 14 to 17 ms** (the game still takes four
  of its own frames to answer a press, and four frames are shorter). Both are outside the noise of section 3.
  The slow frames at card plays and enemy turns are as frequent as before (9 against 11 to 17 over 50 ms per
  minute): the cap does not touch them.
- **Cost:** GPU clock +21 %, about 0.4 of a core more across the bridge server, Lanner and SurfaceFlinger, and
  the battery warmed by 1.3 °C in a session of 5 minutes against 1.0 °C or less at 60 (from a warmer start,
  33.8 °C). Thermal status stayed 0. One 5 minute session says nothing about a 30 minute one; that needs the
  soak in section 13.
- The screen is at 120 Hz in a session (`refreshHz 120`), so frames above 60 are shown (83.5 fps on screen).

## 7. Press-to-screen: what the 80 to 115 ms is made of

The probe gives three times per press (native, bridge, `fight-native-bridge-3`; the other arms in section 3):

| Stage (ms after the app injects the press) | p50 | p95 |
|---|---|---|
| First game frame of any content after the press (`toFirstFrame`) | 10.6 | 16.9 |
| The game frame whose picture changed reaches the compositor (`toChangedFrame`) | 63.9 | 77.2 |
| That frame reaches the panel (`toScreen`, display timing) | 88.4 | 104.8 |

- **About 55 ms is inside the game:** it answers a pad press on its **fourth frame** on the bridge and Venus (third on
  PanVK), at 60 fps. That is the same at the menu (4 frames) and in the fight, and at the 120 cap it
  is still four frames but they are 11 ms each (51.6 ms). INFERRED: Steam Input's polling plus the game's own
  input and focus animation; which of the two was not separated. The Steam client's controller thread
  (`CSteamControlle`) uses 10 to 12 % of a core in every arm.
- **About 25 ms is from the game's frame to the panel:** gamescope, the app's compositor in "two in flight"
  mode, SurfaceFlinger and scan-out. On PanVK without `present-dmabuf` the game-side part is 83 ms instead of
  64, which is the present copy; with it 54 to 57 ms.
- Arms, p50: PanVK 112 to 114, Proton PanVK 111, Proton bridge 94, native bridge 88 to 91 (three runs), native
  PanVK + `present-dmabuf` 81 to 85, Venus 81, native bridge at the 120 cap 75. The noise between identical
  runs is 3 ms.
- p99 is one or two slow presses out of 36 to 69 answered ones and does not repeat between runs (97 to 449 ms
  on the two good configurations); p95 (93 to 114 ms) is the steadier tail number.
- **Coarse cross-check from a screen recording** (`fight-native-bridge-prof`, `xcheck-fight.mp4`, Android's
  touch dots on, two real 50 ms flicks on the on-screen swipe pad, each of which is one d-pad press): the hand
  changed **26 ms and 58 ms after the frame in which the touch dot appeared** (recording at 16.6 ms a frame).
  That is shorter than the probe's shortest sample on this route (69 ms), so the dot must be drawn some frames after the touch it
  reports (INFERRED; the system draws it on its own layer). The recording therefore shows that a real flick
  reaches the picture and that nothing slower than the probe's range is hiding in the touch path, but it
  cannot confirm the probe to better than a few frames. A 240 fps camera on the finger is the real check.

## 8. The CPU picture in a fight (which of our processes cost what)

Android's `top -H` every 5 s through the scripted fight, one core = 100. The whole phone is at about 350 of
800 in a native bridge fight: 158 user, 156 system, 36 interrupts. System time as large as user time is itself
a finding (INFERRED: the bridge's and the container's syscall traffic; not broken down).

| Process (busiest threads) | Native, bridge | Native, PanVK + `present-dmabuf` | Native, PanVK | Proton, bridge |
|---|---|---|---|---|
| The game | 78 (main thread 75, .NET tiered compile 2) | 60 | 78 | 107 (main 82, FMOD mixer 10, audio client 8, FMOD stream 6) + wineserver 3 |
| **Bridge server** (`libvkbridge_server.so`) | **41** (its main thread 35, `mali-event-hand` 6) | 0 | 0 | 31 |
| **Lanner's app process** | **25** (`Thread-18` 9.5, `droiddeck-vsync` 5, `game-thread-hin` 2) | 26 | 24 | **35** (also `lmk-risk` 7, `battery-sysfs` 2, `session-log-syn` 2) |
| **gamescope** | 12 (`gamescope-xwm` 8) | **8** | **22** (`gamescope-xwm` 20) | 9 |
| **Steam client** | 15 (`CSteamControlle` 11.6) + web helper 3 | 18 | 17 | 13 + 3 |
| **Audio server** (pulseaudio) | 5 | 5 | 4.5 | **12** |
| Xwayland | 2 | 2 | 2 | 0.1 |
| Session script, proot, dbus | 4 + 3.5 + 3 | similar | similar | 6 + 2.5 |
| SurfaceFlinger + composer HAL (not ours, driven by us) | 32 + 10 | 31 + 10 | 21 + 7 | 26 + 8 |
| The bench itself: Perfetto probes, `top` | 8, 4 | 7, 4 | 7, 4 | 7, 4 |

- **Our own overhead around a native game on the bridge is about 1.1 cores** (bridge server 0.41, Lanner
  0.25, Steam 0.18, gamescope 0.12, audio 0.05, session plumbing 0.1) for a game that uses 0.78, plus 0.42 in
  SurfaceFlinger and the composer.
- **The bridge server is the largest item we own** (0.41 of a core, 0.48 at the 120 cap). PanVK with
  `present-dmabuf` has no such process, the game itself drops to 0.60 and gamescope to 0.08: that route does
  the same fight with about 0.6 of a core less.
- **Inside the game's main thread** (the engine's profiler, 1000 Hz, `fight-native-bridge-prof`,
  `native-profiler.txt`): 7.2 to 7.6 ms of CPU per frame at p50; by call tree about a third of it is under
  the bridge's client library (`libvulkan_droidbridge.so`, 30 to 37 % inclusive), 40 to 47 % is the engine's
  own code and 35 to 40 % is libc (unsymbolised here: allocation, locks and copies; `pthread_cond_signal`
  alone is 4 to 5 %).
- **The slow frames are main-thread CPU, and the .NET runtime is in most of them.** The profiler's worst
  frames in the fight windows: 54 ms (40 ms of CPU; 22 % in `libclrjit.so`, 11 % `libcoreclr.so`), 58 ms (17 %
  JIT), 46 ms (43 % CoreCLR), 45 ms (67 % CoreCLR), 86 ms (61 ms CPU; 24 % JIT), 94 ms (76 ms CPU; 33 %
  CoreCLR, 17 % JIT), 74 ms (38 % CoreCLR, 19 % JIT). VERIFIED that these frames are CPU-bound on the main
  thread and contain JIT and runtime samples; INFERRED that first-use compilation of the card and turn code
  is the main cause (each bench run is a fresh process, so every card play is early in the process's life;
  a long session would show how much of it wears off).
- **Lanner's app process** costs a quarter of a core with nothing on its screen but the game: `Thread-18`
  (9.5 %, unnamed) and `droiddeck-vsync` (5 %) are the two to look at. On Proton its `lmk-risk` thread adds
  7 % while memory is short.
- **`CSteamControlle`** burns 10 to 12 % of a core in every arm, including the menu.
- **Proton** adds 0.3 of a core in the game (FEX), three audio threads (0.24) and doubles pulseaudio, with the
  sound off.
- The Perfetto traces (one per run, sched + frequency + frame timeline, about 100 MB each) are in the run
  folders. They were not analysed on the desktop: there is no `trace_processor` there and fetching one was
  not permitted in this session. Everything above is from `top`, the session's tables and the engine's
  profiler.

## 9. Lab request #3 (defaults and small fixes): checklist

| Line in the issue | Result |
|---|---|
| `pe-align` on by itself; `experiments:` lists it; `warmup: aligned copy … in use` | **Present.** `setting-exp-pe-align` read On untouched after the install (it was Off on 2b24df8); `session.log` lists it; Proton runs log `warmup: aligned copy of SlayTheSpire2.exe in use (853 MB)`. "Switch it off, relaunch: stays off" was **not tested**. |
| `gpu_mb` in `memory.csv` filled; `DROIDDECK_GPU_MEMORY source=/proc/meminfo:Gpu` | **Present.** 1262 to 1306 MB in a bridge fight, 2532 to 3125 MB on PanVK; the `app.log` line is there. |
| `vk-blocks-64` on: `experiments on but not applied …` and `game.experiment_skipped` | **Present** (`fight-native-bridge-prof`): `experiments on but not applied: vk-blocks-64 (PowerVR system-driver path only; …)` and the event with `reason: no_powervr_runtime`. |
| `memory.lmk_risk` carries `short` and the real `swapFreeMb` | **Present.** `short: memory`, `swapFreeMb 1438` (Proton, bridge); `short: memory_and_swap` (Proton, PanVK). |
| `memory.freed` has `availableChangeMb` / `headroomChangeMb`, no `freedMb` | **Present.** |
| `game.first_frame` belongs to this session | **Present** (its time is after `session.created` in every run). |
| `state`: `output` null at IDLE, `[1616,720]` in a session | **Present**, both. |
| Native launch: `textures:` / `free memory:` before `engine: native`; `memory.freed` with `game_exec`; launch time not worse | **Present, and the launch is slower.** The free-memory step takes 3.9 s on native (4.7 s on Proton) before the engine is chosen: `game.engine` at L+6.8 s and first frame at L+11.2 s, against L+2.9 to 4.1 s and L+7.4 s in `mali-10a-native-1.md`. The game's own "Time to main menu" is unchanged (12.5 to 13.5 s). It also moves memory into swap just before the load (swap used 1151 to 1426 MB in one run). |
| `logs latest` contains `native-engine.log` | **Present.** |
| Expected: Proton game resident + swap about 2.08 GB to 1.23 GB | **Not seen as stated:** 637 + 1787 MB in the bridge fight, 1021 + 1264 MB at the menu (2.3 to 2.4 GB), with `pe-align` in use. Proton's free memory on the bridge is as before (741 MB at the menu against 718). No control without `pe-align` was run on this build, so this is a number to explain, not a regression claim. |
| Pass/fail: fight p99 and press-to-screen not worse than control; no crash in three runs | No control build was measured in a fight (this is the first fight baseline). No crash in 15 completed runs on this build; the two failures were PanVK's (section 3.3). |

One more observation for that track: `memory.csv`'s `game_mb` reads 25 to 100 MB resident plus about 390 MB of
swap for the native game on the bridge (and 495 to 525 MB resident on PanVK). INFERRED: the free-memory step
pushes the freshly started game into swap, or the column follows the wrong process on the bridge; worth a look.

## 10. Recommended configuration for this phone

1. **Engine: native** (`native-engine` On). It is the difference between p99 36 and 119 ms in a fight, and
   between 1.6 GB and 0.75 GB free.
2. **Route: Mali bridge**, today. It needs no experimental switch, holds the 60 cap with p99 36 to 40 ms, and
   leaves 1.6 GB free with no low-memory warning in any run. Plain PanVK (what the phone was set to) should
   not be used for play: 35 fps and a GPU timeout in three tries.
3. **`present-dmabuf` is the route to prefer once it has had its regression pass** (section 5): 6 to 10 ms
   better p99, 5 to 8 ms better press-to-screen and 0.6 of a core less than the bridge, but 0.4 to 0.75 GB
   free instead of 1.6 GB. It becomes the clear winner if the Steam interface's GPU memory comes down (lab
   request #10) or if the GPU timeouts turn out to be gone with it.
4. **The 60 cap:** lifting it to 120 on native gives p99 28 ms and 75 ms press-to-screen for a GPU clock 21 %
   higher. Worth it if a 30 minute soak stays at thermal status 0; a setting on the native path only, since
   the native `settings.save` is local.
5. **Before the owner plays on this phone at all: bring the phone's game to the same version as the desktop's**
   (or keep Steam offline and stay off Profile 1 until then). See section 11, item 1.

Switches I recommend turning on: `native-engine`; the "Mali bridge" route. Leave off: Venus,
`godot-render-thread` (not re-tested here), `vk-count` (an instrument). `present-dmabuf`: on for testing, not
yet as a default. Everything was put back as found (section 11).

## 11. Rule-relevant events

1. **Profile 1's `progress.save` was rewritten by the game, and restored.** Timeline (PST, Nov 28): 02:26
   final session, native on the bridge, to leave the game on Profile 1; the profile screen showed Profile 1
   unchanged (269:32:27, updated Nov 27 4:34 PM); Save Profile 1 selected; Quit, Yes from the game's menu.
   At that Quit (02:27:00) the game logged `Wrote 263646 bytes to profile1/saves/progress.save in steam remote
   store`. The file had been 289 106 bytes. Compared as JSON: `schema_version` 25 to 24; 14 keys dropped
   (`max_blitz_ascension`, `preferred_blitz_ascension`, the six `preferred_*_character` keys and six
   `*dread*` keys); `character_stats`, `encounter_stats`, `enemy_stats` and `ancient_stats`
   shorter by 3.8, 4.8, 5.9 and 0.5 KB; the lists of cards, relics and epochs unchanged. Steam's index marked
   the file `syncstate 3` (changed locally), so the next online start would have uploaded it.
   - Cause (VERIFIED): the game on the phone is `v0.111.1` (build 26655341, `SerializableProgress v24`); the
     owner's file is schema 25, from a newer game. The game loads it (it has warned about an unknown epoch id
     since the first campaign) and writes its own schema back when it saves progress. This is the game's own
     code, not the bridge: INFERRED that Proton does the same, since it runs the same build. The earlier
     campaigns never saw it because they ended the game with a signal and never quit from its menu, and in
     this one every other exit was made on Profile 3.
   - Repair, with Steam and the game stopped: `remote/profile1/saves/progress.save` and `prefs.save` and their
     copies in the native mirror put back from the backup with their original times; the two entries in
     `remotecache.vdf` put back to the backup's (size 289106, `syncstate 1`). VERIFIED afterwards: every file
     of the backup's Steam list except `remotecache.vdf` has the backup's sha256 (`progress.save`
     `29d08792…451a81`), likewise every file of the native and Proton lists. The downgraded file and the index
     as it was are kept in `/root/bench-incident-0227/` on the phone and in `final/incident/` on the desktop.
   - **Still true after this campaign:** the next clean exit from the game on Profile 1, on this phone, will
     downgrade the file again, and online it will sync.
2. **Steam was offline in every session** (the icon found before each launch, looked at by me in the first
   and last sessions and found by the harness in the others; `User is offline` in Steam's log at each game
   start). Nothing synced. When Steam next goes online it will upload Profile 3
   (three files) and a `profile.save` with the original content.
3. **Profile and run.** All fights on Save Profile 3 (created here; it was Empty). One run created: Ironclad,
   seed `KW6PMT0GVE5V`, Nov 27 23:38, "Enable Tutorials?" answered No. **It was not abandoned:** it is the
   fixture the fight scenario continues from (a new profile gets its set first fight only on its first run),
   and abandoning it cannot be undone. It sits at floor 1, turn 1, with 0:00 play time shown. To remove it:
   Profile 3, Abandon Run on the main menu; or delete Profile 3 from the profile screen. The game is back on
   Profile 1. Profile 2 was not opened.
4. **Prompts answered:** "What's Early Access?" (Proceed, its only button) once on native and twice on
   Proton; "Enable Tutorials?" No; "Quit Game" Yes at each exit. Because the game was quit from its menu, it
   saved the notice's "seen" flag in both paths' `settings.save`; **both settings files were restored from the
   backup at the end** (VERIFIED by hash), so the notice will show again as before.
5. **Debugging tools on Nov 27 23:33, off Nov 28 02:30** (real taps). Env lines: `DROIDDECK_NATIVE_PROF=1000`
   and `DROIDDECK_NATIVE_PROF_HITCH_MS=33` for one run; the list was empty after every run and at the end.
   `native` was used for the backup, the hash checks, the headroom edit and the repair.
6. **Game settings:** only the native `settings.save` `fps_limit` for the headroom arm (section 6), restored.
7. **System settings:** Android's `show_touches` was on for 9 s for the cross-check and is 0 again.
8. **Lanner switches:** as found: `native-engine` Off, "Mali bridge" off, "Mali (Venus)" off, every experiment
   as before the campaign except `pe-align`, which the new build turns on by itself. `maliFallbackByAuto` was
   read before the first route press: empty, and empty now.
9. **Build:** the phone keeps `test/lab-1` at 21a1157 (installed with `adb install -r`).
10. **Thermal:** status 0 in every arm except Venus (status 1 for the last 2 minutes, 36.7 °C); the next arm
    started at 35.5 °C and status 0. No owner pickup (Lanner in front and USB power at every check).
11. **Left on the phone:** the two backups under `/root/lanner-save-backup-*` (and one 15 MB tar),
    `/root/bench-incident-0227/` (0.3 MB), Profile 3 with its run.

## 12. What broke or misled

1. Section 11, item 1: a game older than its saves. The bench now cannot be blamed for it again only as long as
   it never exits on Profile 1; the harness refuses to run a fight on any profile but the bench one, but the
   `menu` scenario runs on whatever profile is selected and quits from the menu. **Do not run `menu` on Profile
   1 until the versions match.**
2. PanVK in a fight: a 2.9 s freeze, then a GPU timeout and device loss (section 3.3). The harness's first
   version aborted on the freeze; it now waits and records the step as late.
3. `droiddeckctl input` (one `content call` per press) costs the game a 30 to 55 ms frame 0.4 s after each call.
   The bench sends presses with `cmd activity broadcast` instead.
4. Two A presses 0.3 s apart are too fast for the game: the second is dropped while the targeting arrow is
   still appearing. 0.8 s works on every route.
5. The "What's Early Access?" notice does not take the A button (its pad glyph is Y); the bench taps it.
6. The first touch after pad input only switches the game to mouse mode; a second tap does the click.
7. The latency probe answers only every second press in the fight and at the menu, because the focused entry's
   glow counts as noise in the watched patch; 100 presses give 50 to 69 samples.
8. `memory.csv`'s process table has too few samples in a 50 s window to use (one every 30 to 45 s); the CPU
   table above is from `top`.
9. `latency.csv` in the session logs stays empty for presses sent through the agent bridge.
10. The game renders at 1920x1080 (`vk-count`: every pass is 1920x1080) into a 1616x720 session. Not examined.
11. Two bench bugs cost a run each before the matrix (a wrong pad action name; the notice above), and three
    aborted runs sit beside the good ones as `aborted-*`.

## 13. Next experiments, by expected value

1. **Match the game version to the saves, then repeat the write test on Profile 1's copy.** Without it nothing
   else matters to the owner. Confirmed when a clean exit leaves `progress.save` at schema 25 and the same
   size.
2. **`native-r2r` (ahead-of-time code) on the native bridge fight.** The slow frames are JIT and runtime time
   on the main thread; if they are first-use compilation, p99 should fall towards the menu's 21 ms. Refuted if
   the per-card 40 to 57 ms frames stay. Also the .NET arms of lab request #4.
3. **`present-dmabuf` regression pass and soak** (overlay, background and return, screen off and on, rotation,
   ten minutes of file-descriptor count, thirty minutes of fights watching for `kbase: timeout`), then on
   Proton.
4. **Steam's interface off GPU memory on PanVK** (lab request #10): with `present-dmabuf` it is the only thing
   between PanVK and the bridge.
5. **The bridge server's 0.41 of a core** and the third of the game's main thread spent in its client: what
   the calls are, and whether the faster (non-safe) bridge mode changes it.
6. **The four frames to answer a pad press:** the same probe with `key:` and `tap:` inputs, and with Steam
   Input off for the game, to split Steam Input from the game's own delay.
7. **A 30 minute soak at the 120 cap** on native bridge: thermal status, clocks, p99 over time.
8. **Lanner's `Thread-18` and `droiddeck-vsync`**, and `CSteamControlle` at 11 % of a core.
9. A longer fight script (two fights in one process) to see how much of the per-card cost wears off.
