# Mali memory 1: which existing switches buy headroom for Slay the Spire 2 on a Pixel 10a (build ae32618)

**Question.** `mali-10a-baseline-1.md` left the Pixel 10a (8 GB) at Slay the Spire 2's main menu with 255 to
412 MB available, swap about 90 % full, a `memory.lmk_risk` warning and 6 to 30 lmkd kills. Which of Lanner's
existing switches give the most headroom there, against a target of more than 800 MB available and no
`memory.lmk_risk`, without hurting frame pacing?

**Short answer.** None reaches the target. One switch helps, `pe-align`: it removes about **850 MB of the
game's anonymous memory** in all three runs that had it (game resident + swap 2.08 GB to 1.23 GB), which leaves
1.2 to 1.8 GB of swap free instead of 0.04 to 0.5 GB and takes 0.3 to 0.6 GB out of zram. MemAvailable at the
menu rose from 260-301 MB (five runs without it) to 362-550 MB (three runs with it): a gain of 60 to 290 MB,
so on MemAvailable alone only one of the three runs clears the 200 MB noise bar. `webhelper-trim`, `tex-astc`
and turning `free-memory` off changed nothing measurable; `vk-blocks-64` is not wired on this graphics path at
all. `memory.lmk_risk` fired in every run, 40 to 52 s after the launch request, while the game loads. Frame
pacing did not move in any configuration (46 to 47 fps, p99 31 to 34 ms).

The reason is in the budget: **3.0 GB of the 7.6 GB is GPU memory**, which cannot be swapped and which no
existing switch touches. The game holds 1.2 GB of it and the Steam interface behind the game holds 1.4 GB.
Reaching 800 MB needs a change there (section 7), not a switch.

Marks: **VERIFIED** = read in a run's artifacts, a screenshot I looked at, or source; **INFERRED** = reasoned
from verified facts, not observed. Times are the phone's clock (PST), Nov 27 2026. Artifacts (outside the
repo): `~/projects/droiddeck/output/mali-10a-memory/<run>/` (`header.txt`, `summary.json`, `sampler.txt`,
`dumpsys-meminfo.txt`, `gpumem.txt`, `pacing.json`, `shots/`, `logs/`), `logcat-stream.txt`, and the run
scripts in `scripts/`.

## 1. Method

- Same phone, build, path and rules as the baseline: `chat.comb.lanner` at ae32618, Proton + stock FEX
  (`game.engine path=proton, FEX-2611-79-g727948a` in every run), PanVK, `native-engine` off, Venus and the
  Mali bridge off, Debugging tools off, Steam offline, sound off. Driven with `tools/droiddeckctl`.
- Pre-test before every launch: force-stop Chrome and `chat.comb.familiar.voice`, wait for MemAvailable over
  2.5 GB, record the header, read `setting-offline` (On), `setting-game-sound` (Off),
  `setting-boot-movie-sound` (Off), media volume (0) and the experiment list from the app's UI, then
  `start steam`, screenshot, **look for Steam's offline icon**, and only then `launch 2868840`.
- One run: launch, dismiss the game's start-up notice, sit at the main menu, measure for 120 s starting 240 s
  after the launch request (`pacing --seconds 120` over the same 120 s), then `dumpsys meminfo`, `quit`,
  `stop`, `logs latest`. The baseline measured from about 177 s; 240 s was chosen so the window is past the
  session's own page-out steps. The `webhelper-trim` run was measured from 300 s, because that switch only
  finishes (two trims, then a restart of the web helper) about 265 s after the launch.
- Frame numbers: `frames.csv` over the window the way `docs/handoff/phone-tests/fp.sh` computes them.
  Memory: `memory.csv` (5 s rows) for available, swap and the game/Steam split; the per-process csv in the
  bundle; and a sampler of my own every 10 s (`/proc/meminfo` and `dumpsys gpu --gpumem`), because
  `memory.csv`'s `gpu_mb` column is empty on this phone and GPU memory turned out to be the largest item.
- lmkd kills: the baseline's method, `lowmemorykiller: Kill` lines in a filtered `adb logcat` stream kept for
  the whole campaign, counted from a run's pre-test to its end.
- Switches were changed one at a time in Settings > Advanced > Experiments (`setting-exp-<id>`), read back
  before each run from the UI and again from the session's own `experiments:` line.
- Every configuration is a single run except the control (two) and `pe-align` (two, plus one combined run).

## 2. Run headers (pre-test numbers)

| Run | Pre-test at | MemAvailable | SwapFree of 3785 MB | Battery °C, thermal status | Session id |
|---|---|---|---|---|---|
| control-1 | 19:28:31 | 4143 MB | 2866 MB | 28.6, 0 | `2026-11-27-06-steam` |
| webhelper-trim | 19:37:54 | 4094 | 2715 | 31.4, 0 | `-07-` |
| vk-blocks-64 | 19:47:30 | 4153 | 2718 | 33.5, 0 | `-08-` |
| pe-align | 19:56:36 | 4223 | 2962 | 32.7, 0 | `-09-` |
| tex-astc (build launch, not measured) | 20:04:45 | 4195 | 2744 | 33.9, 0 | `-10-` |
| tex-astc | 20:12:14 | 4227 | 2962 | 34.3, 0 | `-11-` |
| free-memory off | 20:20:16 | 4186 | 2693 | 34.3, 0 | `-12-` |
| control-2 | 20:27:58 | 4186 | 2870 | 34.9, 0 | `-13-` |
| pe-align-2 | 20:35:57 | 4223 | 2939 | 34.7, 0 | `-14-` |
| pe-align + free-memory off | 20:44:15 | 4043 | 2937 | 33.5, 0 | `-15-` |

In every run: offline On, game sound Off, boot-movie sound Off, volume 0, `native-engine` Off, Debugging tools
Off. Experiments as found (and as restored): `godot-render, warmup, all, compat-quiet, memory, adpf-all,
two-frames, one-limiter, native-hz, quick-tap, fex-codegen, fex-fast, fex-stats, free-memory`. Battery 80 to
85 %, on USB. lmkd kills between runs were only the previous run's own (column in section 4).

## 3. Where the memory goes (control-1, at the menu)

Physical memory, `/proc/meminfo` at the end of the window (VERIFIED; the rows add up to MemTotal within 20 MB):

| Item | MB | Note |
|---|---|---|
| GPU (`Gpu:`, Mali kbase) | 3109 | not swappable, not in any process's RSS |
| zram (`Zram:`) | 1213 | holds 3726 MB of swapped pages (3.1 : 1) |
| Anonymous pages resident | 674 | everything else anonymous is in zram |
| File cache (`Cached`, of which 493 mapped) | 608 | |
| Kernel: slab 488, page tables 133, stacks 60, vmalloc 236 | 917 | |
| ION / dma-buf heaps | 403 | screen and compositor buffers |
| `Misc` | 324 | |
| Free | 342 | MemAvailable 286 |
| **MemTotal** | **7571** | |

Who holds the two big items (VERIFIED: `dumpsys gpu --gpumem`, `dumpsys meminfo`, the bundle's process csv):

| Owner | GPU MB | Resident MB | In swap MB |
|---|---|---|---|
| The game (`SlayTheSpire2.exe`) | 1208 | 63 | 1883 |
| Other Wine processes (explorer, steam.exe, wineserver, services) | 35 | 0 | about 110 |
| Steam interface: the Xwayland it draws into | 1156 | 0 | 48 |
| Steam interface: `steamwebhelper` | 388 | 32 | 301 |
| Steam client, gamescope, second Xwayland, session scripts | about 180 | about 40 | about 230 |
| Lanner's app process | 172 | 62 | 59 |
| Android: SurfaceFlinger 242, launcher 64, SystemUI 38, others | about 400 | (rest) | about 1070 |

- Before the game starts, with only the Steam interface up, `Gpu:` already reads 1729 MB (198 MB with no
  session). So Steam's interface costs about **1.5 GB of GPU memory**, and it keeps 1.4 GB of it while the game
  is in front. The session log says `steam: open driver pair - software CEF compositing, GPU raster`: the web
  helper still rasters on the GPU (`-cef-force-gpu`).
- The game's GPU memory is 1207 to 1210 MB in **every** run, including the one with ASTC textures.
- The game's 1.9 GB in swap is mostly not game data. Its `.exe` is 853 MB of which about 850 MB sit in
  sections Wine cannot map from the file (`FileAlignment 0x200`; the five largest are `/45` 287 MB, `/94`
  242 MB, `/69` 154 MB, `/80` 42 MB, `/57` 36 MB, the linker's names for debug-information sections), so Wine
  copies them into anonymous memory at load (`docs/research/wine-pe-section-copy.md`). INFERRED: that copy is
  the 850 MB `pe-align` removes below; the numbers match to within 15 MB.
- With every non-essential app closed the phone starts a session with 3.9 GB available. The GPU's 3.0 GB plus
  the roughly 2.7 GB of session anonymous memory do not fit in that, so about 2.7 GB of the session and 1 GB
  of Android end up compressed, and swap (3785 MB) is full.

## 4. Results

Menu window, 120 s. "Available" is `memory.csv` (mean / minimum in the window); "lowest" is the minimum from
the launch request to the end of the run. Game and Steam side are `memory.csv`'s resident + swap.

| Run | Available mean / min | Lowest after launch | Swap used (free) | Game res + swap | Steam side res + swap | zram | GPU total (game / Xwayland / web helper) |
|---|---|---|---|---|---|---|---|
| control-1 | 275 / 226 | 158 | 3740 (43) | 63 + 2023 | 135 + 632 | 1227 | 3109 (1208 / 1156 / 388) |
| control-2 | 301 / 287 | 217 | 3269 (515) | 238 + 1842 | 168 + 608 | 1081 | 2976 (1207 / 1026 / 388) |
| webhelper-trim (from 300 s) | 368 / 329 | 257 | 3591 (199) | 80 + 1993 | 361 + 436 | 1189 | 2975 (1207 / 1036 / 388) |
| vk-blocks-64 (not applied, 5.3) | 260 / 239 | 200 | 3245 (540) | 262 + 1820 | 181 + 590 | 1072 | 2975 (1210 / 1022 / 388) |
| tex-astc | 294 / 220 | 220 | 3654 (163) | 89 + 1980 | 156 + 634 | 1196 | 2993 (1207 / 1027 / 388) |
| free-memory off | 286 / 255 | 229 | 3303 (487) | 187 + 1891 | 224 + 545 | 1084 | 2976 (1207 / 1023 / 388) |
| **pe-align** | **550 / 520** | 468 | 2554 (1231) | 184 + 1053 | 289 + 489 | 760 | 2978 (1207 / 1029 / 388) |
| **pe-align-2** | **362 / 337** | 337 | 2006 (1776) | 659 + 568 | 143 + 625 | 614 | 2974 (1207 / 1026 / 388) |
| **pe-align + free-memory off** | **402 / 381** | 239 | 2525 (1261) | 478 + 747 | 258 + 520 | 768 | 2977 (1208 / 1026 / 388) |

| Run | `memory.lmk_risk` | lmkd kills in the run (lowest adj) | frames.csv fps, p50 / p95 / p99 ms | Over 50 ms per min | `pacing` p99, off cadence | Battery °C, status at end |
|---|---|---|---|---|---|---|
| control-1 | yes, L+40 s at 370 MB | 16 (250) | 47.0, 21.3 / 28.2 / 31.5 | 0.0 | 33 ms, 55.3 % | 32.4, 0 |
| control-2 | yes, L+41 s at 335 MB | 9 (500) | 46.9, 21.4 / 28.1 / 31.6 | 0.5 | 33, 56.0 % | 34.9, 0 |
| webhelper-trim | yes, L+41 s at 325 MB | 16 (500) | 47.0, 21.3 / 27.8 / 31.0 | 0.0 | 33, 55.4 % | 34.3, 0 |
| vk-blocks-64 | yes, L+44 s at 361 MB | 11 (800) | 45.8, 21.9 / 28.4 / 32.0 | 1.0 | 33, 61.0 % | 34.4, 0 |
| tex-astc | yes, L+43 s at 372 MB | 6 (500) | 45.9, 21.8 / 29.9 / 33.6 | 0.0 | 33, 60.2 % | 35.1, 0 |
| free-memory off | yes, L+41 s at 326 MB | 7 (500) | 47.3, 21.2 / 27.6 / 31.0 | 1.0 | 33, 53.5 % | 34.9, 0 |
| pe-align | yes, L+52 s at 361 MB | 5 (201) | 46.5, 21.5 / 28.3 / 31.9 | 0.0 | 33, 57.4 % | 34.2, 0 |
| pe-align-2 | yes, L+41 s at 356 MB | 5 (250) | 47.4, 21.1 / 27.6 / 30.9 | 0.5 | 33, 53.4 % | 34.6, 0 |
| pe-align + free-memory off | yes, L+45 s at 369 MB and L+345 s at 365 MB | 0 | 46.7, 21.4 / 28.5 / 32.1 | 0.0 | 33, 56.6 % | 34.3, 0 |

The control reproduces the baseline (255 and 412 MB there; 275 and 301 MB here). Lanner and the session
survived every run. No CPU policy was capped in any run. In the kills column, "250" is Lanner's own balloon
process (`chat.comb.lanner:balloon`, killed by design); "201" in the first `pe-align` run is the keyboard and
`messaging:rcs`, killed at L+45 s with "swap is low"; every other kill was a cached or background app.

## 5. Findings

### 5.1 pe-align helps, and its effect is in swap more than in MemAvailable (VERIFIED numbers)

- `session.log`: `warmup: aligned copy of SlayTheSpire2.exe made in 4.8 s (853 MB)` on the first launch,
  `... in use (853 MB)` on the next two. The game then runs as `.droiddeck-aligned\SlayTheSpire2.exe`.
- Game resident + swap: 1237, 1227 and 1225 MB with it, 2069 to 2086 MB in the six runs without. That is
  850 MB less, with a spread of 12 MB, in three of three runs.
- Free swap in the window: 1231, 1776 and 1261 MB with it; 43 to 540 MB without. zram's physical size: 614 to
  768 MB with it, 1072 to 1227 MB without.
- MemAvailable: 550, 362 and 402 MB with it, against 260 to 301 MB in the five runs where nothing effective
  was changed (the two controls, `vk-blocks-64`, `tex-astc`, `free-memory` off; mean 283). The mean gain is
  about 155 MB and the three runs disagree by 190 MB, so **MemAvailable alone does not prove the switch**;
  the game-size and swap numbers do.
- INFERRED: the kernel spends the room differently from run to run. In pe-align-2 it left 659 MB of the game
  resident instead of 184 MB, so available memory rose less while the game had fewer pages to fault back
  (`game_majflt_s` 0 in both later pe-align runs, 3 to 31 elsewhere). Either way the phone is further from
  lmkd's "swap is low" rule, the one that reaches below cached apps (`memory-footprint.md` section 4).
- It does **not** remove the `lmk_risk` warning: that fires during the load, when available memory passes
  under 378 MB (10 % of swap) for a moment, and the load peak is GPU memory arriving (0 to 1.2 GB in about
  30 s), not the copied sections.

### 5.2 webhelper-trim does not help here (VERIFIED)

- Both trims ran (`game_webhelper_trim round=1 ... rss 487 -> 647 MB, swap 436 -> 202 MB`; round 2 no
  change): the trim pulled the helper's pages back out of swap rather than freeing them.
- The restart ran (`game_webhelper_stop ... rss_mb_after=0`, then `game_webhelper_back procs=4 rss_mb=1140
  swap_mb=41 after_s=35`): the fresh helper came back larger in resident memory than the old one, with the
  same 388 MB of GPU memory, and the Xwayland it draws into went from 761 MB back to about 1030 MB of GPU
  memory when the new helper drew its interface.
- Net at the menu: 368 MB available against 275 and 301, inside the noise, with swap as full as the control.

### 5.3 vk-blocks-64 is not applied on PanVK (VERIFIED in source and log)

`droiddeck-game-env` calls `vk_count()` (which sets `DROIDDECK_VK_BLOCK_MB`) only from the PowerVR system-driver
paths (`native_vulkan_env`, `system_graphics_launch`). On this phone the session printed no `vk-blocks-64:`
line, and the game's `VK_INSTANCE_LAYERS` was `VK_LAYER_VALVE_rpo:VK_LAYER_VALVE_fdm_injection`, without the
layer. The run is therefore a third control, and it reads like one.

### 5.4 tex-astc changes nothing at the menu on PanVK (VERIFIED)

- The build ran in the background during one launch (about 4 minutes, one core, battery 36.8 °C and thermal
  status 1 for a moment), and the next launch used it: `textures: ASTC textures switched on (1244 of 3555
  textures, 921 MB of them now ASTC)`. The menu looked the same in a screenshot.
- The game's GPU memory was 1207 MB, the same as with the original textures to the megabyte; available 294 MB.
- p95 / p99 were the worst of the campaign (29.9 / 33.6 ms against 28.2 / 31.5), a single run and within what
  the other runs span at p99 (30.9 to 32.1) by about 1.5 ms. Not a gain in any case.
- INFERRED: PanVK reports `BC emulation on, lowmem`, so it probably does not keep the decoded copy that made
  ASTC worth 5x on the PowerVR path, and the menu's textures are a small part of the 1.2 GB. What the 1.2 GB
  is has not been measured (section 8).

### 5.5 free-memory off: same menu, fewer background kills (VERIFIED, single run)

Without the balloon the menu numbers are the control's (286 MB, swap 487 MB free). lmkd killed 7 apps instead
of 9 and 16; with `pe-align` as well it killed none, but that run still raised `lmk_risk` twice. The balloon's
own numbers show why it buys nothing on this phone: at game start it reports `availableBefore 1720 to 1995,
availableAfter 2335 to 2649` while pushing 0.6 to 0.9 GB into swap (`swapUsedBefore 814 to 1057, after 1530 to
1794`), and that swap is exactly what runs out a minute later. `freedMb` was negative at all eight session
starts and at three of the eight game starts.

### 5.6 Pacing and heat

No configuration moved the menu's frame pacing: 45.8 to 47.4 fps, p50 21.1 to 21.9 ms, p99 30.9 to 33.6 ms, 0
to 1 frame over 50 ms per minute, `pacing` p99 33 ms everywhere. Battery 32 to 35 °C at the menu, thermal
status 0. The menu is not where memory pressure would show as stutter; fights were out of scope.

## 6. What broke or misled

1. `memory.csv`'s `gpu_mb` is empty on this phone, so the session's own memory log misses the largest item
   (3.0 GB). `MemoryReadout` needs a Mali source (`Gpu:` in `/proc/meminfo` is readable by the app's uid on
   this kernel; not checked from inside the app).
2. `vk-blocks-64` can be switched on and is listed in the session's `experiments:` line while doing nothing.
3. The game showed "What's Early Access?" at every one of the ten launches (Proceed, its only button, each
   time). "Load Mods?" never appeared. INFERRED: offline, the game does not keep the "seen" flag between
   launches on this phone, or Proceed is not what stores it.
4. `droiddeckctl ui click --match Steam` matches 24 nodes and clicks the first. It was the Steam tab; nothing
   else was pressed (state IDLE and all four Steam-page switches unchanged afterwards). Use `--index` or tags.
5. The Steam client ended with a segmentation fault at `stop` in four of the ten sessions
   (`droiddeck-session: line 654: ... Segmentation fault`: webhelper-trim, vk-blocks-64, tex-astc,
   pe-align-2), with no pattern by switch. Not followed up.
6. `memory.lmk_risk` compares the smaller of free swap and MemAvailable with 10 % of swap, so it fired at
   365 MB available while 1.2 GB of swap was free (combined run, L+345 s). That mirrors lmkd's rule, but it
   means the warning cannot go away on this phone below about 380 MB available, whatever swap does.

## 7. Recommendation

**For 8 GB devices today: turn `pe-align` on, leave the rest as they are.** It is the only existing switch
with a repeatable effect (850 MB less anonymous game memory, 0.7 to 1.7 GB more free swap), it costs no frame
time at the menu, and its cost is one 853 MB copy of the `.exe` in the game's folder and 5 s on the first
launch after a game update. `free-memory` can stay on: it changes nothing at the menu, and off was measured
once. Do not turn on `webhelper-trim` (no gain, a larger helper afterwards) or `tex-astc` (no gain on PanVK,
a 4-minute build and 929 MB appended to the game's pack).

This does not meet the target. With `pe-align` the menu sits at 360 to 550 MB available and still warns during
the load.

**Code change that would make it the default (not made here).** Add `PE_ALIGN` to `Experiments.defaults` in
`app/src/main/java/com/droiddeck/launcher/session/Experiments.kt` (and to `ExperimentsTest`). Two caveats:
`Experiments.enabled()` only falls back to `defaults` when nothing was ever saved, so an install where any
experiment has been switched keeps its saved set and needs the remote config or a one-time migration; and the
doc comment on `defaults` ("all but layer-fence and the frame cap") is already out of date. A narrower variant
is to key it on memory instead of on the experiment: in `droiddeck-game-env`, call `pe_aligned_launch(command,
True)` when `MemTotal` is 8 GB or less.

## 8. Not done, and why

- No fight, no run, no Compendium: menu only, the owner's real saves.
- `fex-trim`, `fex-l1`, `fex-code-buffer`: the game-env comments say stock FEX ignores them; they need
  `fex-kestrel`, which changes the translator and would not be a memory-only comparison.
- `tex-half`: same mechanism as `tex-astc`, which changed nothing.
- "Clear cached apps" and "Background apps during a session" (Settings > Advanced > Memory): they need
  Wireless debugging paired, a developer setting.
- Anything behind Debugging tools: the environment switches that would test the GPU items below
  (`steam_cef_gpu`, `DROIDDECK_VK_BLOCK_MB`), and `droiddeck-texpack revert`.
- Launch time with `pe-align` was not timed.

Left on the phone by the experiments (both are how the switches are documented to behave, and neither could
be removed without deleting files or Debugging tools): the aligned copy
`steamapps/common/Slay the Spire 2/.droiddeck-aligned/SlayTheSpire2.exe` (853 MB), unused while `pe-align` is
off; and the ASTC build appended to `SlayTheSpire2.pck` (929 MB), parked, with the pack's header pointing at
the original directory again (`textures: original textures this launch; the ASTC build is kept (929 MB)` in
the four launches after the switch went off). `/data` went from 52 to 54 GB used. A Steam file verification
would see the pack's size as changed.

## 9. Next experiments, by expected value

1. **Steam interface off the GPU while a game runs (1.4 GB).** One run with the web helper drawing in
   software (`-cef-disable-gpu`; `droiddeck-session` already does this for the Mali bridge under 4 GB,
   `steam_cef_gpu=0`), with Debugging tools on to set it. Confirmed if `Gpu:` before the game drops from
   1.7 GB to under 0.5 GB and the menu has over 800 MB available; refuted if Xwayland keeps its 1 GB (then
   the pixmaps are Xwayland's own and the next step is what it allocates: glamor, per-window buffers). Check
   that Steam's interface still scrolls acceptably and that the game's frame times do not change.
2. **What the game's 1.2 GB of GPU memory is.** It is the same with BC and ASTC textures. Wire the vkcount
   layer on the PanVK path (it already reports live and bound memory per kind), run once with `vk-count` to
   read images vs buffers vs allocator slack, then `vk-blocks-64`. If slack dominates, the block cap is the
   fix; if images do, look at Forward+ render targets at 1616x720 and PanVK's BC emulation.
3. **Native engine (baseline item 1).** No Wine means no 850 MB section copy and no FEX; the GPU items stay.
   Still needs the save decision.
4. **`pe-align` in a fight**, once a save is chosen: whether 1.2 to 1.8 GB of free swap is the difference
   between holding P99 and stalling when a fight loads its assets. Two minutes, `fp.sh` numbers, with and
   without.
5. **Load peak.** `lmk_risk` at L+40 to 52 s in all nine measured launches (and in the build launch). Starting the game with the Steam
   interface already paged out, or item 1, should move it; measure the lowest available memory in the first
   90 s.
6. Small fixes: fill `gpu_mb` on Mali; say so in the log when `vk-blocks-64` or `vk-count` is on but not
   applied; make the `lmk_risk` text say whether swap or free memory is the short one.
