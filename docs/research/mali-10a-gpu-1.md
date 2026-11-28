# Mali GPU 1: Steam's GPU memory and the 41-47 fps menu on a Pixel 10a (build 2b24df8, engine 134)

**Questions.** The three earlier 10a notes left two problems open at Slay the Spire 2's main menu:

- **Q1 (memory).** About 3 GB of the phone's 7.6 GB is GPU memory, of which Steam's interface holds 1.4 GB behind
  the game (`mali-10a-memory-1.md`). Can Steam's interface be kept off GPU memory with a lever that exists in the
  code today? Target: GPU memory with only Steam's interface up from about 1.7 GB to well under 1 GB, and
  MemAvailable at the menu up by more than the 200 MB noise bar, with Steam's own interface still usable.
- **Q2 (frame rate).** Why does the native engine draw 41 to 45 fps at the menu with its main thread under half
  busy (`mali-10a-native-1.md`)?

**Short answer.** Both problems have the same cause: the open **PanVK** driver this phone was set to. Switching
the graphics route to the **Mali bridge** (Settings > Advanced, the "Mali bridge" button: the session's Vulkan
calls go to the phone's own Mali driver) fixes both, in every run that had it:

- Q1: with only Steam's interface up, GPU memory is 0.60 GB instead of 1.71 GB, and 0.33 GB with the web
  interface also drawn in software; MemAvailable at that point is 2.66 to 2.86 GB instead of 1.67 GB. At the
  game's menu on the native engine MemAvailable is **1.39 to 1.55 GB instead of 0.93 GB** (three bridge runs
  against one control), and on Proton **0.72 GB instead of 0.25 GB** (one run each).
- Q2: on the bridge the native engine holds **60.0 fps, which is the game's own cap** (`fps_limit: 60` in its
  settings), p99 20.4 to 20.7 ms, in three of three runs, against 41.0 fps and p99 33.6 ms on PanVK. Proton on
  the bridge: 60.0 fps against 47.0. The engine's main thread needs 5.2 ms a frame on the bridge and 12.2 ms on
  PanVK, and the GPU's clock averages about 600 MHz instead of 790 MHz of 940.

The levers aimed at the web helper alone do nothing on PanVK (`--disable-gpu`: 1.76 GB) or stop Steam from
starting (Xwayland without glamor, GPU compositing). "Mali (Venus)" also fixes the frame rate (57.6 fps) but not
the memory. Costs and open risks of the bridge are in section 6; the main ones are that it was measured at the
menu only, in its "safe mode", and that one of six normal bridge sessions ended with an Xwayland crash at stop which
made the *next* session, on PanVK, unable to start.

Marks: **VERIFIED** = read in a run's artifacts, a screenshot I looked at, or source; **INFERRED** = reasoned
from verified facts, not observed. Times are the phone's clock (PST), Nov 27 2026. Artifacts (outside the repo):
`~/projects/droiddeck/output/mali-10a-gpu/<run>/` (`header.txt`, `summary.json`, `sampler.txt`,
`menu-gpu-owners.txt`, `gpufreq.txt`, `pacing.json`, `shots/`, `logs/`), `logcat-stream.txt`, `post-verify.txt`
and the run scripts in `scripts/` (the earlier campaigns' scripts with the changes listed in section 2).

## 1. What the code offers (read before the runs)

| Lever | Where | How it is set | Tried |
|---|---|---|---|
| Web helper: software compositing, GPU raster (`-cef-force-gpu -cef-disable-gpu-compositing`) | `droiddeck-session`, `BL_STEAM_SOFT_COMPOSITING=1`, added by `SessionService` for the open driver pair | already the default on PanVK | it is the control |
| Web helper fully in software (`-cef-disable-gpu -cef-disable-gpu-compositing`) | `droiddeck-session`, only inside the `BL_VKBRIDGE=1` branch: automatic under 4 GB of RAM, or `BL_VKBRIDGE_CEF_GPU=0` | env line (Debugging tools), bridge route only | yes |
| `steam_cef_gpu=0` (the memory note's suggestion) | a shell variable assigned in `droiddeck-session`; only the x86-64 client path sets it to 0 | **cannot be set from outside** | no |
| Extra Chromium flags for the web helper's browser process | `tools/linuxfs/preload/webhelper.c`, `BL_WEBHELPER_ARGS` (default `--no-zygote --in-process-gpu -AudioServiceOutOfProcess`) | env line | yes, with `--disable-gpu` added |
| Xwayland without glamor | `XWAYLAND_NO_GLAMOR=1`; `SessionService` adds it by itself after a session whose log has an Xwayland segmentation fault | env line | yes |
| GPU compositing for the web helper | `BL_STEAM_SOFT_COMPOSITING` not 1; the app's own switch for it is hidden on Mali (`steamGpuCompositingAllowed`) | env line `BL_STEAM_SOFT_COMPOSITING=0` | yes |
| Graphics route "Mali bridge" | `SessionPrefs.vkBridge`, button "Mali bridge: off/on" in Settings > Advanced | the button | yes |
| Graphics route "Mali (Venus)" | `SessionPrefs.venus`, button "Mali (Venus): off/on" | the button | yes |
| `godot-render-thread` | experiment; `native_engine()` passes `--render-thread separate` to the native engine too | experiment switch | yes (PanVK) |
| Present mode `two-frames` / `layer-fence` / off, `composite-timing` | experiments; the app compositor's present mode and a gamescope timing log | experiment switches | no (section 8) |
| `one-limiter`, `native-hz`, `sched`, `fps-30`, the frame cap | only act with a frame cap, and the cap rewrites the game's `settings.save` (`sts2_settings_cap`) | | no, by rule |
| `wayland-present`, `vk-count`, `vk-blocks-64`, `hybris-*` | PowerVR paths only (`native_vulkan_env` returns early without a PowerVR runtime) | | no |

- The two route buttons have **no test tags**; they were pressed with `ui click --match "Mali bridge: off"` and
  the like (exact text, one match each).
- Both routes are valid for the native engine (VERIFIED in source, then on the phone): the engine runs on the
  session's environment, so it loads the bridge's ICD or the Venus ICD like any other guest program. On Venus the
  app sets `DROIDDECK_MALI_COMPAT=1`, so the compatibility layer (BC textures decoded on the CPU) is active
  outside Wine as well; the native log shows `droiddeck-mali-compat: emulating ... textureCompressionBC for
  Virtio-GPU Venus (Mali-G715)`.
- The game's own settings (read from the save backup on the desktop, not from the phone): `fps_limit: 60`,
  `vsync: adaptive`, `msaa: 2`, fullscreen. The engine logs `The requested V-Sync mode Adaptive is not available.
  Falling back to V-Sync mode Enabled` on every route. So **60 fps is the ceiling at the menu** whatever the
  panel does; gamescope's Xwayland output is 1616x720@120.
- `SessionPrefs.vkBridge` defaults to on for a Mali GPU in source, yet this phone had it stored as off when the
  campaign started (the baseline note's `graphics-report.txt` already said "bridge false"). What wrote that was
  not determined.

## 2. Method

- Same phone and rules as the earlier notes: Pixel 10a, `chat.comb.lanner`, now build "ux/design-pass-1 at
  2b24df8", Steam offline (the offline icon looked at in a screenshot before every launch), game sound Off,
  boot-movie sound Off, volume 0, menu only. "What's Early Access?" appeared at all eight launches (Proceed, its
  only button); "Load Mods?" never did.
- **Debugging tools were on from 21:40:29 to 23:24:17**, for every probe and run including the controls, because
  three of the levers are environment lines. Env lines were set per session (`env set`, next-session scope) and
  the list was empty after each session and at the end.
- Pre-test before every session as before (force-stop Chrome and `chat.comb.familiar.voice`, MemAvailable over
  2.5 GB, header, switches read from the UI).
- **Steam-only probes** (new): start Steam, screenshots 3 to 32 s after READY, and 45 s after READY one sample of
  `/proc/meminfo` and `dumpsys gpu --gpumem` with a process list for the owners; then eight d-pad presses with
  `pacing --seconds 10`; stop. No game.
- **Menu runs**: as in the two earlier notes: launch, 120 s window from 240 s after the launch request,
  `frames.csv` the `fp.sh` way, `pacing --seconds 120`, the 10 s sampler, `quit`, `stop`, `logs latest`. Added:
  the GPU clock (`/sys/class/misc/mali0/device/cur_freq`, once a second through the window; utilisation is not
  readable from the shell) and, on native, the engine's own counter (`native_compat: frame split ... fps ...
  main thread cpu`, the twelve 10 s lines before the last).
- One control per path, one change at a time; the winner was repeated once on native.
- **lmkd kills**: the earlier scripts' `grep "lowmemorykiller: Kill"` matches nothing in this campaign's stream
  (`logcat -v time` prints `I/lowmemorykiller(  579): Kill`), so every run's `summary.json` says 0. The numbers
  below were recounted from `logcat-stream.txt` by timestamp, pre-test to window end + 60 s. The scripts are
  fixed.

Run headers (all: thermal status 0, battery 80 %, on USB, offline On, sounds Off, volume 0):

| Run | Pre-test at | MemAvailable | SwapFree of 3785 MB | Battery °C | Session id |
|---|---|---|---|---|---|
| Proton, bridge + software browser | 22:10:57 | 3947 MB | 2450 | 29.0 | `2026-11-27-31-steam` |
| Native, bridge + software browser | 22:20:08 | 4127 | 2718 | 31.0 | `-32-` |
| Native control (PanVK) | 22:31:11 | 4099 | 2720 | 31.4 | `-34-` |
| Proton control (PanVK) | 22:39:36 | 4080 | 2674 | 32.8 | `-35-` |
| Native, bridge | 22:48:23 | 4173 | 2864 | 33.4 | `-36-` |
| Native, bridge + software browser, repeat | 22:56:45 | 4182 | 2828 | 32.7 | `-37-` |
| Native, Venus | 23:04:56 | 4064 | 2689 | 33.0 | `-38-` |
| Native, PanVK + `godot-render-thread` | 23:14:02 | 4105 | 2652 | 33.8 | `-39-` |

## 3. Q1: Steam's interface alone (VERIFIED)

45 s after READY, no game. "GPU" is `Gpu:` in `/proc/meminfo`; ION is `ION_heap:` (dma-buf memory, where the
bridge and Venus keep their shared buffers).

| Configuration | GPU MB | ION MB | MemAvailable MB | Largest GPU owners | Steam's first frame after session start |
|---|---|---|---|---|---|
| PanVK (control) | 1713 | 405 | 1667 | Xwayland 1022, web helper 388 | 8.3 s |
| PanVK, web helper with `--disable-gpu` | 1762 | 398 | 1588 | Xwayland 1023, web helper 435 | 8.2 s |
| PanVK, Xwayland without glamor | 272 while stuck | | | | **never** (web helper restarted 13 times in 3.5 min) |
| PanVK, no glamor + `--disable-gpu` | | | | | **never** (7 restarts in 90 s) |
| PanVK, GPU compositing | | | | | **none in 60 s** (no restarts) |
| Mali (Venus) | 223 | 632 | 2773 | render servers 310 + 22 | 55.0 s the first time; start to READY 14.6 s the second time |
| Mali bridge | 599 | 402 | 2662 | bridge server 421 | 10.1 s |
| Mali bridge, browser in software (`BL_VKBRIDGE_CEF_GPU=0`) | 328 | 419 | 2864 | bridge server 201 | 9.3 s |

- The target (well under 1 GB) is met by the bridge and by Venus, not by anything on PanVK.
- On PanVK the web helper's `--disable-gpu` reached its command line (read from `/proc`), and its GPU memory went
  *up*, with Xwayland unchanged. INFERRED: the 1.0 GB in Xwayland and the 0.39 GB in the helper are what PanVK
  and Zink set aside per client, not the interface's pixels: the same two figures appear to the megabyte in
  every PanVK session of three campaigns, and the second Xwayland, with no windows, holds 42 MB.
- Without glamor Xwayland offers no DRI3, and the web helper then dies at start in a loop, with or without
  `--disable-gpu`. This is the state the app puts the *next* session in by itself after an Xwayland crash
  (section 6, item 1).
- **Steam's interface stays usable** on the bridge. The library was complete in the first screenshots I looked
  at (8 s after READY on PanVK and Venus, 14 s on the bridge) and looked the same in all configurations; the
  offline icon was visible in each. During the d-pad presses the screen got 7 to 8 new frames a second on PanVK
  (7.1), with `--disable-gpu` (7.3) and on the bridge with the browser in software (7.6): Steam only redraws on
  change, and software compositing is already what PanVK uses, so the bridge's software browser is no slower
  than today's default. (The plain bridge shows 65 fps there because gamescope composites every frame on that
  route; it says nothing about the interface.) Scrolling a long list was not tried.

## 4. Results at the game's menu (120 s from L+240 s)

| | Proton, PanVK (control) | Proton, bridge + sw browser | Native, PanVK (control) | Native, bridge | Native, bridge + sw browser | same, repeat | Native, Venus | Native, PanVK + render thread |
|---|---|---|---|---|---|---|---|---|
| frames.csv fps | 47.0 | **60.0** | 41.0 | **60.0** | **60.0** | **60.0** | 57.6 | 43.9 |
| frames.csv p50 / p95 / p99 ms | 21.3 / 27.9 / 31.9 | 16.7 / 19.2 / 20.9 | 24.6 / 30.1 / 33.6 | 16.7 / 19.0 / 20.4 | 16.6 / 19.2 / 20.7 | 16.7 / 19.0 / 20.6 | 16.9 / 24.5 / 28.8 | 22.8 / 26.9 / 29.6 |
| max ms; over 50 ms per min | 72; 0.5 | 103; 0.5 | 41; 0 | 37; 0 | 41; 0 | 39; 0 | 34; 0 | 35; 0 |
| `pacing` fps on screen, p99, off cadence | 46.9, 33 ms, 55 % | 60.0, 25, 13 % | 40.9, 33, 26 % | 59.9, 33, 12 % | 60.0, 33, 19 % | 60.0, 25, 12 % | 55.1, 42, 31 % | 43.9, 33, 30 % |
| Engine's own counter: fps, main thread ms (busy) | not logged | not logged | 41.0, 12.2 (50 %) | 60.0, 5.2 (31 %) | 60.0, 5.2 (31 %) | 60.0, 5.3 (31 %) | 57.6, 4.3 (24 %) | 43.9, 2.4 (10 %) |
| MemAvailable mean / min MB | 246 / 193 | **718 / 680** | 931 / 886 | **1385 / 1352** | **1544 / 1522** | **1552 / 1522** | 941 / 909 | 527 / 469 |
| Lowest MemAvailable after launch | 141 (L+70 s) | 391 (L+67 s) | 565 (L+55 s) | 1341 | 1462 | 1416 | 854 | 430 |
| Swap free MB; zram MB | 39; 1262 | 746; 1002 | 1946; 541 | 2506; 353 | 2353; 406 | 2341; 402 | 2322; 403 | 1976; 512 |
| GPU MB (`Gpu:`) | 2926 | 1073 | 2698 | 1349 | 1121 | 1102 | 227 | 3108 |
| ION MB | 410 | 888 | 420 | 919 | 923 | 936 | 2359 | 442 |
| GPU + ION MB | 3336 | 1961 | 3118 | 2268 | 2044 | 2038 | 2586 | 3550 |
| GPU owners MB | game 1207, Xwayland 1026, helper 388 | bridge server 1463 | game 1125, Xwayland 895, helper 388 | bridge server 1663 | bridge server 1429 | bridge server 1445 | render servers 1751 + 239 | game 1381, Xwayland 1027, helper 388 |
| Game resident + swap MB | 95 + 2262 | 1111 + 1231 | 447 + 16 | 431 + 0 | 447 + 0 | 441 + 0 | 480 + 0 | 426 + 38 |
| `memory.lmk_risk` | L+45 s at 325 MB | L+69 s at 363 MB | none | none | none | none | none | none |
| lmkd kills (lowest adj) | 27 (100) | 25 (915) | 2 (975) | 0 | 0 | 0 | 0 | 5 (965) |
| Game CPU; bridge or render server CPU (one core = 100) | 89; - | 83; 26 | 62; - | 53; 33 | 53; 33 | 53; 33 | 53; 64 + 26 | 70; - |
| GPU clock, mean MHz (of 940) | 814 | 661 | 786 | 622 | 615 | 596 | 534 | 769 |
| Battery °C, thermal status at end | 34.3, 0 | 31.6, 0 | 32.9, 0 | 33.3, 0 | 32.1, 0 | 33.0, 0 | 34.3, 0 | 33.8, 0 |

- The two controls repeat the earlier notes (Proton 46 to 47 fps and 250 to 300 MB; native 41 to 45 fps and 690
  to 800 MB, 931 here), so the new build and Debugging tools did not move them.
- In the Proton control lmkd reached `com.google.android.gms` (adj 100), the keyboard and `messaging:rcs` (201)
  with "swap is low" down to 10 MB free; that is the worst kill list of the four campaigns. On the bridge the 25
  kills were all cached apps (adj 915 and up), in the first seconds of the load.
- No CPU policy was capped in any run. The menu looked the same in every screenshot.
- Engine choice in every run as intended (`game.engine path=proton` / `path=native, native-engine-134`, no
  fallback). On the bridge the engine reports `Vulkan 1.4.341 ... ARM - Mali-G715` (the phone's driver); on
  PanVK `Vulkan 1.4.363 ... Mali-G715 MC7` with `BC emulation on, lowmem`.

### 4.1 Q1 at the menu

- **Native:** 931 MB on PanVK; 1385 MB on the bridge; 1544 and 1552 MB on the bridge with the browser in
  software. The bridge gives **+450 MB**, well over the noise bar, with the 800 MB target of the memory note
  passed by 550 MB. The software browser adds another 160 to 170 MB (two runs against one), which is under the
  200 MB bar taken alone but repeats to within 8 MB and matches its 250 MB drop in GPU memory.
- **Proton:** 246 MB on PanVK, 718 MB on the bridge with the software browser: **+470 MB**, single run each.
  Swap free 746 MB instead of 39 MB. `lmk_risk` still fires during the load (at 363 MB, L+69 s), so Proton on
  the bridge is better but not comfortable; `pe-align` (memory note) was off and should add to it.
- **Where the saving is, and what it is not.** `Gpu:` falls by 1.35 to 1.6 GB, but the bridge keeps about 0.5 GB
  more in dma-buf memory (ION 420 to 920 MB), so the real saving is GPU + ION: **3118 to 2040 MB on native, 3336
  to 1961 MB on Proton**. On Venus nearly everything moves to ION (2359 MB), GPU + ION is 2586 MB, and
  MemAvailable (941 MB) is the control's. Venus does not solve Q1 at the menu even though its Steam-only number
  looks best.
- INFERRED: the game's own memory through the phone's driver is about 1.2 GB (bridge server 1429 to 1463 MB
  with the game against 201 MB without, software browser), the same as on PanVK; what disappears is Xwayland's
  1.0 GB and the helper's 0.39 GB.

### 4.2 Q2: why 41 to 45 fps

- **The driver, not the game and not the screen path.** Same engine, same game settings, same gamescope, same
  app compositor and present mode (`two-frames`): PanVK 41.0 fps, bridge 60.0, 60.0, 60.0, Venus 57.6. The
  engine's own counter agrees with `frames.csv` to 0.1 fps on every route, so the 60 is the game's frames, not
  gamescope repeating them.
- **60 is the game's cap** (section 1), so the bridge result is "reaches the cap", not "60 is all it can do".
  The GPU clock sat at 580 to 650 MHz of 940 on the bridge; INFERRED there is headroom above 60.
- **Not the main thread.** With `godot-render-thread` on PanVK the main thread fell from 12.2 ms to 2.4 ms a
  frame (10 % busy) and the frame rate stayed at 43.9, inside the 40.9 to 44.8 that three plain native PanVK runs span (two in the native note, one here).
  Not worth turning on for PanVK; on the bridge it was not tried, and at the cap it would show nothing.
- **What PanVK costs** (VERIFIED numbers, INFERRED cause): 7 ms more main-thread time per frame than the
  phone's driver (12.2 against 5.2 ms) and a GPU clock 170 MHz higher while drawing two thirds of the frames.
  Which part of PanVK it is (the BC emulation, its tiler set-up, missing framebuffer compression) was not
  measured.
- Venus reaches 57.6 fps with a worse tail (p99 28.8 ms, `pacing` p99 42 ms) and spends 0.9 of a core in its
  render and test servers against the bridge's 0.33.
- Proton shows the same step (47.0 to 60.0 fps, p99 31.9 to 20.9 ms) from one run.
- Launch: the native menu is reached as fast on the bridge as on PanVK (the game's "Time to main menu" 13.1 and
  14.5 s against 12.5 s; last load pause ends L+19 to 21 s on both). Proton's load is **slower** on the bridge:
  last mode switch at L+74 s against L+50 s, with two pauses of 12.6 s (single run).

## 5. Rule-relevant events

- Steam was offline in every session (icon looked at each time). No session went online.
- Prompts: "What's Early Access?" eight times, Proceed each time. No run, no Continue, no profile or settings
  screen; the game got no other input. The Steam-only probes sent eight d-pad presses to Steam's
  library each (focus moved and returned; nothing was opened).
- Debugging tools on 21:40:29, off 23:24:17 (real taps). Env lines used: `BL_WEBHELPER_ARGS`,
  `XWAYLAND_NO_GLAMOR`, `BL_STEAM_SOFT_COMPOSITING`, `BL_VKBRIDGE_CEF_GPU`; `env clear all` at the end, list
  empty. `guest` was used to read process command lines and the session log; `prefs get` once (read only).
- **Save re-hash at the end** (`post-verify.txt`): all of `userdata/155889176/2868840`, the Proton `user://`
  mirror and `override.cfg` match the backup's sha256 lists, no file added or missing; the backup itself intact.
- Switches changed and put back: `native-engine` (Off), `godot-render-thread` (Off), "Mali (Venus)" (off),
  "Mali bridge" (off). The stored preferences read `venus: false`, `maliFallbackByAuto: ""` during the campaign;
  the two route buttons clear `maliFallbackByAuto` when pressed, and its value before my first press was not
  read, so that one hidden value is restored only as far as "empty".
- No owner pickup was seen (Lanner in front and USB power at every check).

## 6. What broke or misled

1. **An Xwayland crash at stop poisons the next session on PanVK.** In one of the six bridge sessions that ran normally
   (`-32-`) Xwayland died at `stop` after the bridge server had gone (`lost the bridge server`, `zink: DEVICE
   LOST`, `(EE) glamor0: GL error: GL_OUT_OF_MEMORY`, `(EE) Segmentation fault`). `SessionService.
   lastXwaylandCrashed()` then added `XWAYLAND_NO_GLAMOR=1` to the next session, which was the native PanVK
   control, and that session never drew a frame (web helper restart loop, 9 restarts; kept as
   `native-control-failed-noglamor`). The session after it was normal. Whether a bridge session survives
   `XWAYLAND_NO_GLAMOR=1` was not seen. On PanVK the fallback is fatal every time it applies (section 3).
2. **`start` can leave the session without its screen.** After a `stop` sent while no session screen was open,
   `SessionState.closeScreenForStop` stays set, and the next start's screen closes itself at once ("closing the
   session screen: stop from the notification"); the session then sits in `SUSPENDED` with Steam running and
   `resume` does not bring the screen back. It repeated on every retry until the "Resume Steam" button in the
   launcher was clicked and the session stopped with its screen open. Three starts were lost to it (22:01 to
   22:10); none of those sessions launched the game.
3. The lmkd count in the run scripts read 0 for every run because of the log format (section 2). Recounted.
4. `frames.csv` and `pacing` on the bridge would also read 60 if gamescope repeated frames (it composites every
   frame on that route), so on Proton, where the game logs no counter, the 60.0 is INFERRED from the native runs
   to be real frames.
5. The first Venus start took 55 s to Steam's first frame; the second 14.6 s to READY. One Venus session ended
   with the Steam client's segmentation fault at `stop` that the memory note already lists.
6. `top -H -n 1` prints 0 % for every thread on this phone, so per-thread CPU comes from the engine's counter
   only.
7. The bridge was measured as the phone had it configured: the Settings row beside it reads "Safe mode: on".
   The faster bridge mode was not tried.

## 7. Recommendation

**For this phone: turn "Mali bridge" on** (Settings > Advanced; one button, no Debugging tools needed). At the
menu it takes the native engine from 41 to its 60 fps cap with p99 21 ms instead of 34, and gives 450 MB more
available memory; Proton gains the same frame rate and about as much memory. It costs about a third of a core
in the bridge server (the game's own CPU falls by a tenth of a core), 1 to 2 s more to Steam's first frame, a
slower Proton load, and the risk in section 6 item 1. The software browser on top is worth another 160 MB but
needs an env line today.

It was measured at the menu only, on one game, in safe mode. Before it is the default it needs a fight and a
second game (section 9, items 1 and 2).

**Code changes that would make it the default for Mali phones with 8 GB or less (none made here):**

1. *Route.* `SessionPrefs.vkBridge` already defaults to on for Mali, and `DriverMenus` treats the bridge only as
   a stand-in until a PanVK pair is installed (`useMaliFallback` / `endMaliFallback`). Decide the other way
   round: where `DeviceSupport.bridgeUsable()` and the bridge's self-test pass (13 of 13 on this phone), keep
   the bridge and leave the installed PanVK pair as `BL_VKBRIDGE_FALLBACK_ICD`, which the session script already
   falls back to. Find what stored `vkBridge=false` on this phone first, since the source default disagrees with
   what a fresh install showed.
2. *Browser in software.* In `droiddeck-session`, the rule `MemTotal < 4194304 kB` that sets
   `BL_VKBRIDGE_CEF_GPU=0` could be `<= 8388608` (this phone reports 7752904 kB). Small gain; check scrolling
   first.
3. *Glamor fallback.* `lastXwaylandCrashed()` should not add `XWAYLAND_NO_GLAMOR=1` on the open driver pair,
   where it prevents Steam from starting, or should only look at crashes before `session.stopping`. And the
   bridge route should stop Xwayland before its server.
4. *Start after stop.* Clear `SessionState.closeScreenForStop` when a stop finishes, not only when the next
   session's service starts.

`godot-render-thread` and Venus: leave off. The web-helper flags on PanVK: nothing to gain.

## 8. Not done, and why

- Present mode off / `layer-fence`, `composite-timing`, gamescope's refresh (`BL_REFRESH`): the driver A/B
  answered Q2 and the budget went to repeating the winner. They would matter above the 60 cap, which cannot be
  lifted without writing the game's settings.
- Proton on the plain bridge (GPU browser) and on Venus; a repeat of the Proton bridge run.
- The bridge's non-safe mode; `godot-render-thread` on the bridge.
- Anything in a run, fight or the Compendium; any frame cap; any write to the game's settings.
- Steam's interface under load on the bridge (store pages are offline; long-list scrolling not tried).
- Power: the dock still reports a discharge, so the GPU clock is the only energy proxy here.

## 9. Next experiments, by expected value

1. **A fight on the bridge, native** (needs the write test of `mali-10a-native-1.md` section 6 first): two
   minutes, `fp.sh` numbers and thermals over 20 minutes, against PanVK. Confirmed if p99 holds near 21 ms and
   MemAvailable stays over 1 GB; refuted by device-lost lines in `vkbridge-client.log` or a p99 over PanVK's.
2. **A Direct3D game on the bridge** (DXVK goes through the bridge's own feature set, `bridgeProtonDxvk`):
   whether the route that wins for Godot is safe as a device default.
3. **Twenty start/stop cycles on the bridge** to get the rate of the Xwayland crash at stop, and one session
   started on the bridge with `XWAYLAND_NO_GLAMOR=1` to see whether the app's fallback is survivable there.
4. **Proton on the bridge with `pe-align`**: the two memory gains should add (0.72 GB plus the 0.85 GB of game
   memory `pe-align` removes); check whether `lmk_risk` stops firing during the load, and time the load, which
   was 24 s slower here.
5. **Bridge without safe mode**, and the 0.5 GB of extra dma-buf memory: what it holds and whether the faster
   mode shrinks it.
6. **Above 60 fps**: only with Cedar's go to change `fps_limit` in the game's own settings (or a launch option
   that does not touch the file); then the present-mode switches and `composite-timing` become worth a run.
7. Small fixes: test tags on the two route buttons; a Mali source for `memory.csv`'s `gpu_mb` that includes
   `ION_heap`; `pacing`/`frames.csv` marked when gamescope composites every frame.
