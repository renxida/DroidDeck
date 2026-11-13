# Memory footprint of a Slay the Spire 2 session on the Pixel 10

Branch `perf/memory`. Follows `crash-catalogue.md` C1 (lmkd kills the whole app) and C6 (CoreCLR,
FEX and DXVK memory growth), and builds its interventions I6 and I7. Nothing here has been measured
on a device yet; section 6 says which log lines will measure it.

## 1. Question

The base Pixel 10 has 12 GB of RAM. Android already uses 4.8-9 GB of it at session start in the
upstream reports, and every lmkd kill in `crash-catalogue.md` C1 happened while the session was
within about 1-2 GB of lmkd's swap thresholds. Which parts of the process tree can shrink cheaply and
safely, and can the app ever step in before lmkd kills it?

The tree, from largest to smallest share (INFERRED, from C1 and the memory log's columns): the game
(CoreCLR managed heap, Godot, FEX's code cache, driver memory), the Steam client (already paged out
to zram under pressure), the Wine helper processes (each under FEX), and the app process itself
(compositor, front-end caches).

## 2. The .NET heap of the game (I6)

**Mechanism (VERIFIED, dotnet/runtime `release/8.0` `src/coreclr/gc/gc.cpp` [N1]).**
`GCConserveMemory` (`GCConserveMem`, 0-9, gcconfig.h:138) does two things when set to N:

- **Growth.** It caps how much gen2 may grow before the next full GC. The factor is
  $f = (10/N - 1) \cdot 0.5 + 1$, where $f$ is (live data + new allocation) / live data
  (gc.cpp:43510-43519). For N = 5 that is $f = 1.5$, against the unconstrained growth rule
  otherwise.
- **Compaction.** A gen2 GC becomes a blocking, compacting one when gen2 plus large-object
  fragmentation exceeds $1 - N/10$, which is 50 % at N = 5 (gc.cpp:21193-21215).

The runtime itself picks N = 5 whenever its own size-adaptation mode (DATAS) is on and no level is
set (gc.cpp:14416-14432). That is the level chosen here: it is the runtime team's default for "keep
the heap near the live size", not a number tuned on this device.

**Why it fits StS2 (INFERRED).** Its cost is extra blocking gen2 compactions, which are CPU time
under FEX and can show as pauses. StS2 is a turn-based 2D game, so a pause matters far less than in
an action game. The level is therefore a per-game default for StS2 only, not for every CoreCLR
game. Godot's .NET SDK sets no GC options (`Godot.NET.Sdk/Sdk/Sdk.props` and `Sdk.targets`,
4.5-stable, contain no `ServerGarbageCollection` or `ConcurrentGarbageCollection`), so StS2 runs the
workstation concurrent GC and needs no `gcServer` change.

**Hard limit.** `GCHeapHardLimit` caps the managed heap's commit. Near it, at 90 % of the limit, the
GC turns to full compacting collections (gc.cpp:21159). Over it, allocation throws
`OutOfMemoryException`, which turns an lmkd kill of everything into a game crash. Two facts settle
how it is set here:

- **Reservation.** The explicit `DOTNET_GCRegionRange=800000000` the app already sets
  (`GameEnvironment.kt`) takes precedence, so a limit does not change the address-space reservation
  (gc.cpp:48601-48622).
- **Format.** A `DOTNET_GC*` environment value takes precedence over runtimeconfig and is read as
  hexadecimal (`src/coreclr/vm/gcenv.ee.cpp:1229-1254`, v8.0.11).

**Choosing the limit (INFERRED rule).** `memory.csv`'s `game_mb` is the RssAnon+RssShmem of every
process with `WINEPREFIX` (`MemoryReadout.split`), so it contains the managed heap and more. Swapped
pages are not resident, so `tools/dotnet_heap_limit.py` adds the growth of `swap_used_mb` since the
game appeared. That gives
$B = \max_t \big(\text{game}(t) + \text{swap}(t) - \min_{s \le t} \text{swap}(s)\big)$.
The managed heap's touched commit cannot exceed $B$, unless other apps' swap was freed during the
run, which would understate it. The suggested limit is $\lceil (1.25B + 256) / 256 \rceil \cdot 256$ MB. By
construction it lies above every measured run, so it only binds on runaway growth, and the 90 %
compaction trigger sits near the observed peak. When the suggestion reaches the device's RAM the
tool says there is no useful cap.

**Built.** `droiddeck-game-env` `dotnet_heap` handles game launches (`waitforexitandrun` with an app id):

| Setting (per game in the game environment) | Effect |
|---|---|
| none, game 2868840 | `DOTNET_GCConserveMemory=5` |
| `DROIDDECK_DOTNET_CONSERVE=0`..`9` | that level for any game; `0` turns it off |
| `DROIDDECK_DOTNET_HEAP_LIMIT_MB=256`..`16384` | `DOTNET_GCHeapHardLimit` in hex bytes |
| `DOTNET_`/`COMPlus_GCConserveMemory`, `GCHeapHardLimit(Percent)` set by hand | left alone |

Each launch that sets something prints
`DROIDDECK_DOTNET_HEAP app=2868840 conserve=5 limit_mb=- source=default` to the compat tool's
stderr (`session.log`). `limit_mb` reads `-`, a number, `env` or `ignored`, and `source` reads
`default`, `setting` or `env`.

## 3. FEX, Wine and the app process

**FEX (VERIFIED, FEX-2610, the version `droiddeck-fex-update` pins).**

- **JIT code buffer.** It starts at 16 MiB and doubles up to 128 MiB per process
  (`FEXCore/Source/Interface/Core/SharedCodeBufferManager.cpp:15-17, 104-106`). Both sizes are
  compile-time constants with no config option, and pages count only once code is written into them.
- **Lookup caches.** The L2 lookup cache is off and the L1 is dynamically sized by default
  (`DisableL2Cache`, `DynamicL1Cache` in `Config.json.in`). The app's FEX presets also set both
  (`FexPreset.kt:20-22`).
- **Page-pointer table.** The L3 page-pointer table is reserved for 128 MiB of code and touched on
  use (`LookupCache.h:465-470`, `LookupCache.cpp:27-60`).

**Conclusion:** FEX offers no further memory knob. `Multiblock=0` would shrink compiled blocks at a
throughput cost nobody has measured, so it is left alone.

**Wine (INFERRED).** Every Wine helper (wineserver, services, winedevice, explorer, Proton's
`steam.exe`) is a separate FEX process with its own code buffer. Cutting helpers is not safe, and
`memory.csv` has no per-process column to size them. Experiment M6 proposes measuring them first.

**The app process (VERIFIED, coil 2.6.0).**
- **Size.** Coil's memory cache may hold 20 % of `largeMemoryClass` (the app sets `largeHeap`) in
  bitmaps (`coil/util/Utils.kt` `defaultMemoryCacheSizePercent`, `calculateMemoryCacheSize`).
- **Trim behaviour.** While the app is in the foreground, Coil only halves the cache from
  `TRIM_MEMORY_RUNNING_LOW` and clears it from `TRIM_MEMORY_BACKGROUND`
  (`coil/memory/StrongMemoryCache.kt` `trimMemory`).

The front end and the session share one process (the manifest names only `:shortcuts_sync` apart).
*Built:* `AppCaches.drop` clears Coil's memory cache when a session starts, on
`TRIM_MEMORY_RUNNING_MODERATE` or worse during it, and when the lmkd warning below fires. Each
clear logs `DROIDDECK_APP_CACHE dropped=coil reason=<session_start|trim_N|lmk_risk> mb=N`. The images
reload from Coil's disk cache.

**Steam client.** Its memory is already handled: `droiddeck-session` `bl_client_pressure` pages it
out to zram under pressure (`crash-catalogue.md` C1). Nothing is changed there.

## 4. Stopping only the game before lmkd kills everything (I7): not safe, so warn instead

**When lmkd may kill a foreground or perceptible app (VERIFIED).** The source is lmkd
`__mp_event_psi` [L1]. The app is at adj 0 in the foreground, or adj 200 with its foreground
service. lmkd sets `min_score_adj = PERCEPTIBLE_APP_ADJ + 1` for low swap, low memory and
thrashing (lines 3071-3139), so it spares such an app unless one of these holds:
- a complete PSI stall (`NOT_RESPONDING`, line 3062);
- a min-watermark breach right after a kill (`PRESSURE_AFTER_KILL`, `pressure_after_kill_min_score`
  default 0);
- the min watermark itself, or thrashing at 3 × `thrashing_limit` (300 % by default).

"Swap is low" means free swap, capped by easily available memory, is under 10 % of total
(`swap_free_low_percentage`, lines 2917-2920).

**Why an automatic stop is not safe:**
1. **lmkd always acts first.** It polls every 10 ms once swap is low (`PSI_POLL_PERIOD_SHORT_MS`,
   lines 3213-3215). An app reading `/proc` every second or two cannot act at lmkd's own threshold
   before lmkd does.
2. **An earlier trigger kills games that would have survived.** Acting at an earlier, looser
   threshold stops games lmkd would have spared. While swap is low, lmkd first kills everything above
   adj 200, and that often frees enough. The app can't see lmkd's watermark state or its
   thrashing baseline, which resets after every kill and decays (lines 2905-3010), so it can't tell
   these cases apart.
3. **The game's save is at risk either way.** A stop is still a SIGKILL or SIGTERM of a game that
   may be writing its save. A graceful close is game-specific, and StS2's response to one is
   unknown. Steam itself is not in the game's Wine prefix, so its state would survive. But a false
   positive costs the player progress that lmkd would not have taken.

**Built instead: a warning.** `LmkRisk` reads `/proc/meminfo` and `/proc/pressure/memory` every 2 s
during a session. It flags a risk when lmkd's swap-low predicate holds (free swap, capped by
`MemAvailable`, under 10 % of swap) together with PSI `some` avg10 ≥ 10. If PSI can't be read, it
uses `MemAvailable` under 5 % of RAM instead. It warns once per episode: the episode ends after 60 s
without risk, and warnings are at least 5 minutes apart. On a warning it:
- logs `DROIDDECK_LMK_RISK action=warn swapFreeMb=... swapTotalMb=... availableMb=... totalMb=...
  psiSomeAvg10=... psiFullAvg10=...` and records a `memory.lmk_risk` event;
- drops the app's image cache;
- shows "Memory is almost full. Android may soon close DroidDeck together with Steam and the game:
  save your game now."

Nothing is killed. Revisit an automatic stop only if experiment M5 shows warnings with a long enough
lead time and few false alarms.

## 5. Interventions, ranked

| # | Intervention | State | Expected effect | Cost | Risk |
|---|---|---|---|---|---|
| I6a | `DOTNET_GCConserveMemory=5` for StS2 | **built**, per-game default | smaller gen2 (growth factor 1.5, compaction above 50 % fragmentation) | more blocking gen2 GCs under FEX | pauses in a turn-based game |
| I6b | `DROIDDECK_DOTNET_HEAP_LIMIT_MB` + `tools/dotnet_heap_limit.py` | **built**, off until set | caps runaway managed growth; GC compacts near 90 % of the cap | none until set | too low a cap turns into `OutOfMemoryException` (`DROIDDECK_GAME_EXIT ... class=out_of_memory`) |
| I9 | Drop Coil's memory cache during sessions | **built** | up to 20 % of the large memory class (about 100 MB at 512 MB) | images reload from disk on return | none known |
| I7 | lmkd-risk warning (not a stop) | **built** | the player saves before a kill | one 2 s poll | false alarms only annoy |
| I10 | Close other apps' background processes (experiment `free-memory`) | **built**, remote config turns it on | cached apps' RAM and zram back before Steam starts and before a game | up to ~10 s at session start or game launch; killed apps start fresh | balloon pressure reaches lmkd's low-swap kills (adj > 200), so home or the previous app may restart too |
| - | FEX code-cache or lookup caps | not possible | none left: sizes are compile-time, savers are already on | - | - |
| - | Stop only the game on predicted lmkd kill | rejected (section 4) | - | - | kills games lmkd would spare |

## 5a. Closing other apps' background processes (I10)

Telemetry of one session: 8.9 GB used and 4 GB of swap held by other apps before the game; with the game
running, swap full, `MemAvailable` 300-700 MB, the game at ~500 MB resident and 2.2 GB swapped, 42 → 11 fps.

**What the platform allows (VERIFIED, `ActivityManagerService.killBackgroundProcesses`, Android 14+).** A caller
without the privileged `KILL_ALL_BACKGROUND_PROCESSES` only reaches its own package; for any other it logs
`Invalid packageName` and returns. The app still makes the calls and measures, so an older or different build
shows up in the numbers.

**The balloon.** `FreeMemory` binds `MemoryBalloonService` in the `:balloon` process with
`BIND_NOT_PERCEPTIBLE`. From an app at adj ≤ 200 that pins the balloon to `PERCEPTIBLE_LOW_APP_ADJ` 250
(`OomAdjuster`), and the app refuses to grow it unless its `oom_score_adj` is above the app's. It maps 256 MB
steps of shared memory filled with random bytes (zram cannot compress them away). lmkd [L1] then kills on its
own rules: free pages under the high watermark kill adj ≥ 701 (cached apps, `LOW_MEM`); low swap or thrashing
reach adj ≥ 201; a complete stall reaches 0, still highest adj first, so the balloon (250) dies before this app
(0) or a perceptible one (200). Growth stops when two steps in a row bring nothing back after kills began, at a
third of RAM or 4 GB, after 6 s, under 256 MB available, or when PSI `full` exceeds 25 % of a step. Then the
memory is released and the process exits.

**When.** Once per session after the session folder opens, joined before the session's own processes start, when
under 4 GB is available; and before a game, when `droiddeck-game-env` writes `free-memory` to `$BL_LAUNCH_DIR`
and waits up to 15 s for `free-memory-done`, if under 3 GB is available. Each run writes a `memory.freed` event
and a `DROIDDECK_FREE_MEMORY` line in `session.log`; the lmkd-risk warning stays quiet for 30 s after it.

**To verify on the device:** `balloonAdj=250 appAdj=0`, `balloonStop=reclaimed` rather than `cap`/`time`/`floor`,
`freedMb` and `swapUsedAfterMb` against before, and the game's resident share and fps against a run with the
experiment off.

## 6. On-device experiment plan

Run StS2 on the Pixel 10 with telemetry upload on, then compare runs with
`tools/perf_report.py` (frames, `memory.csv`, crashes) and `tools/dotnet_heap_limit.py`.

- **M1 Conserve A/B.** Three 30-minute runs with `DROIDDECK_DOTNET_CONSERVE=0` and three with the
  default. Each launch's `session.log` must show `DROIDDECK_DOTNET_HEAP app=2868840 conserve=5` (or
  no line for 0).
  - *Confirms:* `game_mb_max` and late-run `game_mb` are lower with 5, while frame-time p99 in
    `frames.csv` stays close.
  - *Refutes:* no `game_mb` difference, meaning the managed heap is a small share; then I6b matters
    more.
- **M2 Hard limit.** Run `tools/dotnet_heap_limit.py` on M1's default runs, set the printed
  `DROIDDECK_DOTNET_HEAP_LIMIT_MB` for game 2868840, and play three runs.
  - *Confirms:* the `DROIDDECK_DOTNET_HEAP ... limit_mb=N source=default` line, no
    `DROIDDECK_GAME_EXIT ... class=out_of_memory`, and `game_mb_max` no higher than M1.
  - *Refutes:* any `out_of_memory` exit, which means the bound missed committed-but-swapped heap;
    raise the limit or drop it.
- **M3 App cache.** Read `DROIDDECK_APP_CACHE dropped=coil reason=session_start mb=N` in `app.log`
  after opening the library first.
  - *Confirms:* `memory.csv` `app_mb` falls by about N MB within two samples.
  - *Refutes:* `mb=0` on every run, meaning the front end does not fill the cache enough to matter.
- **M4 Warning precision.** On the 12 GB phone with other apps open, count `DROIDDECK_LMK_RISK`
  lines. For each, check whether `DROIDDECK_EXIT_CAUSE scope=app class=lmk` follows on the next app
  start, and use the `app.exit` timestamp to see whether the kill came within 5 minutes.
  - A warning followed by a kill is a hit; a warning with none is a false alarm; a kill with no
    warning is a miss.
  - *Refutes the thresholds:* misses outnumber hits.
- **M5 Lead time.** For M4's hits, the time from `DROIDDECK_LMK_RISK` to the kill. Only a median of
  tens of seconds with few false alarms would justify revisiting an automatic stop (section 4).
- **M6 Wine helpers.** Take one `ps -o rss,args` snapshot of the guest during M1 (not built: the
  memory log keeps only the `game` total). This sizes the per-process FEX overhead before anything
  is cut.

## 7. References

- [N1] dotnet/runtime `release/8.0` `src/coreclr/gc/gc.cpp` 14416-14432, 21159, 21193-21215,
  43510-43519, 48601-48622; `gcconfig.h` 104-105, 138; v8.0.11 `src/coreclr/vm/gcenv.ee.cpp`
  1229-1254.
- [L1] AOSP `system/memory/lmkd/lmkd.cpp`, read from the AxionAOSP mirror
  (`AxionAOSP/android_system_memory_lmkd`, branch `lineage-23.2`): defaults 119-150, `__mp_event_psi`
  2800-3220, properties 4240-4290. The kill reasons match the AOSP `main` strings quoted in
  `crash-catalogue.md` [A3] and the field logs there.
- [X1] FEX-Emu/FEX `FEX-2610`: `FEXCore/Source/Interface/Core/SharedCodeBufferManager.cpp` 15-17,
  104-106; `LookupCache.h` 465-470; `LookupCache.cpp` 27-60; `FEXCore/Source/Interface/Config/Config.json.in`
  (`Multiblock` line 4, `DisableL2Cache` 316, `DynamicL1Cache` 325, all default true).
- [C1] coil-kt/coil `2.6.0`: `coil-base/src/main/java/coil/util/Utils.kt`
  (`defaultMemoryCacheSizePercent`, `calculateMemoryCacheSize`), `memory/StrongMemoryCache.kt`
  (`trimMemory`).
- [G1] godotengine/godot `4.5-stable`: `modules/mono/editor/Godot.NET.Sdk/Godot.NET.Sdk/Sdk/Sdk.props`,
  `Sdk.targets`.
- Repo: `tools/linuxfs/overlay/usr/local/bin/droiddeck-game-env` (`dotnet_heap`),
  `app/src/main/java/com/droiddeck/launcher/session/LmkRisk.kt`, `AppCaches.kt`, `SessionService.kt`
  (`startLmkWatch`, `onTrimMemory`), `MemoryReadout.kt` (`split`, memory.csv), `core/GameEnvironment.kt`,
  `core/FexPreset.kt`, `tools/dotnet_heap_limit.py`.
- Liang et al., "Acclaim: Adaptive Memory Reclaim to Improve User Experience in Android
  Systems", USENIX ATC 2020 (foreground working sets under Android reclaim); Weiner, "PSI - Pressure
  Stall Information", Linux `Documentation/accounting/psi.rst` (the `some`/`full` avg10 used above).
