# Time to playable and warm-up stutter for Slay the Spire 2

Branch `perf/warmup`. Target: Slay the Spire 2 (Steam app 2868840, Godot 4 with C# on .NET, Windows build) on
a Pixel 10 (Tensor G5, PowerVR) under proot, FEX (ARM64EC), Valve's ARM64 Proton and Wine.

## 1. Question

Why does it take so long to go from the Play tap to a playable game in this stack, and why does the game
stutter for a while once it is running? What can be cut, at what cost and risk?

"Time to playable" covers four stages:

1. Android app and session start.
2. Guest environment set-up.
3. Steam client start, up to the game hand-off.
4. Game launch, from the Proton call to the first interactive frame.

"Warm-up stutter" is the extra frame time in the first minutes of play that goes away as the game keeps
running. The central point of this chapter is that two dynamic compilers sit on top of each other:

- **RyuJIT** compiles the game's C# (CIL) to x86-64, in tiers (tier 0, an instrumented tier, then tier 1). The
  JIT itself (`clrjit.dll`) is x86-64 code, so it runs under emulation.
- **FEX** translates every x86-64 block, the JIT's output included, to ARM64 the first time it runs.

Every method the game calls costs one RyuJIT compile per tier, run under FEX. Each tier's code is then
translated again by FEX. Every tier-up also writes new code next to code FEX has already translated, which
can invalidate it.

## 2. Background

### 2.1 Dynamic binary translation warm-up

A dynamic binary translator (DBT) pays a translation cost the first time each guest block runs and amortises
it over later executions:

- Dynamo [Bala, Duesterwald, Banerjia, PLDI 2000] showed that hot-trace selection pays off only once a trace
  is reused enough.
- DynamoRIO [Bruening, Garnett, Amarasinghe, CGO 2003] made the code cache the central structure. Its
  start-up cost motivated persistent caches.
- Reddi et al. [CGO 2007, "Persistent code caching"] kept translated code across runs in Pin, keyed to the
  binary's identity. They showed large start-up savings for short-running and repeatedly started programs.
- Bruening and Kiriansky [VEE 2008] did the same for process-shared and persistent caches in DynamoRIO.
- HQEMU [Hong et al., CGO 2012] splits translation into a fast block translator and a background optimising
  translator, so hot code improves without stalling the guest thread. That is the same two-tier trade-off
  RyuJIT makes.

For this stack the hard case is **dynamically generated code**. When the guest writes code (a JIT), the DBT
has to detect the write, drop stale translations and translate again:

- Hawkins et al. [CGO 2015, "Optimizing binary translation of dynamically generated code"] measured this
  invalidation as the dominant overhead for JIT-heavy guests under DynamoRIO, and cut it with finer-grained
  invalidation.
- Hazelwood and Smith [CGO 2004] studied code-cache eviction granularity. Coarse flushes are cheap to manage
  but throw away useful translations.

### 2.2 Tiered JIT warm-up

- SELF [Hölzle and Ungar, PLDI 1994 (type feedback); OOPSLA 1994 (third-generation SELF)] introduced
  adaptive recompilation: fast code first, optimised code for hot methods.
- Jalapeño [Arnold et al., OOPSLA 2000] formalised the cost-benefit model for when to recompile.
- Barrett et al. [OOPSLA 2017, "Virtual machine warmup blows hot and cold"] measured VMs with careful
  methodology. Many benchmarks never reach a steady state, and some get slower after "warm-up". So warm-up
  has to be measured on the real workload, not assumed.

.NET's tiering is documented in `docs/design/features/tiered-compilation.md` (dotnet/runtime, release/8.0,
commit 0f36628). The relevant mechanics, VERIFIED in source:

- Tier 0 is a fast, unoptimised JIT. A method is promoted after `TC_CallCountThreshold` = 30 calls
  (`src/coreclr/inc/clrconfigvalues.h:568,580`).
- Call counting only starts once `TC_CallCountingDelayMs` = 100 ms have passed **with no tier-0 JIT
  activity**, and the timer is reset by every tier-0 JIT (`clrconfigvalues.h:569,581`, "A perpetual delay …
  while there is startup-like activity"; design doc line 69). A game that keeps reaching new code during
  play, as a card game does with each new card, relic and screen, keeps postponing tier-up of the code it
  already runs.
- `TieredPGO` = 1 by default (`clrconfigvalues.h:612`) with `TieredPGO_InstrumentOnlyHotCode` = 1 (`:618`).
  A method that gets hot is first recompiled into an **instrumented tier**, then to tier 1. That is one
  extra JIT pass for every hot method.
- `TC_QuickJitForLoops` = 1 on x64 (`:575`). Methods with loops start at tier 0 and use on-stack replacement
  (OSR) to leave a hot loop, which adds an OSR compile as well.
- `TieredCompilation` = 1 (`:572`). `ReadyToRun` = 1 (`:668`): precompiled (R2R) code is used where it
  exists.
- `EnableWriteXorExecute` = 1 (`:731`, read at `src/coreclr/utilcode/executableallocator.cpp:263`). With W^X
  on, every JIT write goes through a remapped RW view.
- Configuration is read from `DOTNET_<name>`, falling back to `COMPlus_<name>`
  (`src/coreclr/utilcode/clrconfig.cpp:173-174`), whatever runtimeconfig says.

Godot's .NET export runs `dotnet publish <project> -c <config> -r <rid> --self-contained true` with no
ReadyToRun option (`modules/mono/editor/GodotTools/GodotTools/Build/BuildSystem.cs:202-240`, godot master
e8fe0a1; VERIFIED). So by default only the .NET framework comes precompiled, and all game code starts at tier
0. A game can still set `PublishReadyToRun` in its own `.csproj`. Whether StS2 does is not known here: an R2R
image carries a ReadyToRun header (signature `RTR`) that a scan of the game's main assembly can show.

### 2.3 FEX's code caching

VERIFIED in FEX-Emu/FEX at tag FEX-2611 (8f92e80); history checked against tags FEX-2607 to FEX-2611:

- **DiskCache**, persistent and content-hashed, was added in **FEX-2609**. It is absent from the FEX-2607
  and FEX-2608 trees. In FEX-2609 it first appears in both `FEXCore/Source/Interface/Core/DiskCache.cpp` and
  `Source/Windows/ARM64EC/Module.cpp`.
  - It is off by default: `"DiskCache"` default `false`
    (`FEXCore/Source/Interface/Config/Config.json.in:180-186`), so it is enabled with `FEX_DISKCACHE=1`. FEX
    reads every `FEX_*` environment variable (`Source/Common/Config.cpp:295`).
  - `DiskCache::Init` (`DiskCache.cpp:396-479`) puts the cache under
    `<cache dir>/DiskCache/<machine-hash>/RWCacheDB_<process-hash>`. The machine hash covers the format
    version and host CPU features. The process hash covers the serialised configuration, so a different TSO
    or multiblock setting gets its own database.
  - Old buckets are pruned at start (`DiskCachePruneStaleEntries`, `Config.json.in:252`). The database
    is capped at 1 GiB (`:244`).
  - Lookups compare an XXH128 hash of the live guest bytes (`DiskCache.cpp:547ff`, mismatch logging at
    `:743`). A game update or a different DLL build misses the cache and is not run stale.
  - For anonymous (JIT-written) code the decoder records data masks so that such blocks can be looked up
    too (`Frontend.cpp:1557-1558`). Writes happen on a low-priority `FEX:DiskCache` thread
    (`DiskCache.cpp:475-478`).
  - The ARM64EC frontend wires file mapping for it (`Source/Windows/ARM64EC/Module.cpp:786-788`). `InitCore`
    calls `DiskCache.Init` (`FEXCore/Source/Interface/Core/Core.cpp:116`).
  - Cost: while the cache is being written, FEX emits relocatable constants
    (`FEXCore/Source/Interface/Context/Context.cpp:59-62`). Code generated in that mode is slightly larger
    (INFERRED; not measured).
- **Cache directory.** `FEX_APP_CACHE_LOCATION` overrides it (`Source/Common/Config.cpp:712-715`). Otherwise
  it is `$STEAM_COMPAT_SHADER_PATH/fex-emu/` in Steam builds (`:717-722`, only with `FEX_STEAM_SUPPORT`) or
  `%LOCALAPPDATA%\fex-emu\` on Windows (`:729-731`). Under Wine, `%LOCALAPPDATA%` lives inside the game's
  prefix.
- **EnableCodeCachingWIP** is a different, older mechanism: AOT "codemap" files for file-backed images
  (`Source/Windows/Common/ImageTracker.cpp:158,238`). It is marked WIP and does not cover JIT-written code.
- **Invalidation on Windows** is page-granular:
  - `FlushInstructionCache` and `BTCpu64FlushInstructionCache` (`ARM64EC/Module.cpp:1107-1116`).
  - Protection changes (`NotifyMemoryProtect`, `:1062`).
  - Section map and unmap notifications (`:1077,1091`).
  - Write traps on RWX pages, which un-protect a page and invalidate its translations on the first write
    (`Source/Windows/Common/InvalidationTracker.cpp:219-239`).

  CoreCLR's loader heaps put many small methods on one page. A tier-up therefore writes into pages whose
  older code FEX has already translated (INFERRED from the CoreCLR heap layout).
- **Version string.** `FEXCore/Source/Interface/Core/CPUID.cpp:1018` embeds `GIT_DESCRIBE_STRING` (for
  example `FEX-2611`, or `FEX-Unknown` for builds made outside git, `CMakeLists.txt:602-617`) in the binary.
  The version of FEX that a Proton ships can therefore be read from its `libarm64ecfex.dll`.

### 2.4 Proton prefix start-up

Read in ValveSoftware/Proton, branch proton_11.0 (1f089f5), file `proton`:

- `setup_prefix` (`:988`) takes `pfx.lock`. On every launch it runs `upgrade_pfx`, `migrate_user_paths`, the
  `config_info` comparison (`:1075-1104`), `create_fonts_symlinks` (`:937-953`, one `lstat` per font, every
  launch) and the Steam DLL copies (`:1113-1131`).
- The expensive `update_builtin_libs` (`:895`) runs only when `config_info` or the prefix version changed:
  after a Proton or Steam update, or a WineD3D, DXVK or NVAPI choice change.
- Proton writes `pfx/.update-timestamp` as `int(mtime(wine.inf))` when it creates a prefix (`:889-893`), so
  that Wine does **not** run `wineboot --update`. If the timestamp does not match (for example after a Proton
  update changed `wine.inf`), Wine runs the update before the game. Under FEX that is the slowest single
  step a launch can hit (INFERRED: it starts `wineboot`, `services`, `plugplay`, `rundll32` and `setupapi`
  processes, all under emulation).
- Z: is mapped to `/` (`:1014-1015`).

### 2.5 This repository's launch path (VERIFIED, base 5789ff2)

`droiddeck-game-env` wraps each Proton game launch. Before Proton starts it runs:

- `win_components` (runpy of `droiddeck-wincomponents`).
- `repair_foreign_system_dlls` (`droiddeck-game-env:897`). This lists `system32` and `syswow64` on every
  launch, with one or more `lstat`s per entry and a PE header read for every non-symlinked DLL.
- `apply_config`, the engine fixes (which apply `DOTNET_FEX`, `:55`, a dictionary this branch does not
  touch), audio, cores and the thread plan, then sync selection.

Every one of those file operations goes through proot's ptrace tracer, at 15-60 µs per path syscall
(`docs/development/proot-performance.md`).

Each Python helper (`droiddeck-game-env`, and the modules it loads with `runpy.run_path`) is compiled from
source on every run, because neither `__main__` scripts nor `run_path` write `.pyc` files.

The app's defaults already set `DOTNET_EnableWriteXorExecute=0` and `DOTNET_GCRegionRange=800000000` for
every game (`app/src/main/java/com/droiddeck/launcher/core/GameEnvironment.kt:30-38`). So W^X, which is the
most expensive .NET start-up knob under emulation, is already off.

Steam start runs four Python set-up helpers serially (`droiddeck-session:1039-1064` on this branch). The
steam:// hand-off polled focus and readiness once a second (`droiddeck-session:1178-1206` before this
branch). `docs/development/offdevice-profile.md` ("Startup path") lists the host-side waits.

Godot keeps a Vulkan pipeline cache at `user://vulkan/pipelines.<method>.<device>.cache`
(`servers/rendering/rendering_device.cpp:8853-8859`, godot master e8fe0a1). It keeps compiled shader
variants in `user://shader_cache` (`servers/rendering/renderer_rd/renderer_compositor_rd.cpp:329-343`). For a
game with a custom user dir, `user://` is `%APPDATA%\<name>`, otherwise `%APPDATA%\Godot\app_userdata\<name>`.
Both are inside the prefix. Under winevulkan on PowerVR, the driver's own pipeline cache is a third layer
that this chapter does not cover (see perf/godot-render).

## 3. Findings

Each finding is marked VERIFIED (read in source) or INFERRED (reasoned, not measured). Nothing here was
measured on a device: this worker has none.

| # | Finding | Status |
|---|---|---|
| F1 | FEX's persistent DiskCache exists from FEX-2609, works in the ARM64EC frontend, is off by default and is keyed by guest-byte hashes, so stale code is not reused. Nothing in this repo turned it on. | VERIFIED (§2.3) |
| F2 | The FEX version inside Valve's ARM64 Proton is unknown here. If it is older than 2609, `FEX_DISKCACHE` is ignored. The DLL embeds its version string, so a launch can log it. | VERIFIED mechanism; version unknown |
| F3 | With TieredPGO on, every hot game method is JIT-compiled three times (tier 0, instrumented, tier 1). Each version is also translated by FEX, and each JIT runs `clrjit.dll` under FEX. The instrumented tier's counter updates are memory writes, and under `DOTNET_FEX` full TSO every one of them is a strongly ordered access. | VERIFIED (counts); INFERRED (TSO cost) |
| F4 | The 100 ms call-counting delay restarts on every tier-0 JIT. In a game that keeps reaching new code, tier-up of code that is already hot is postponed. That stretches the period where hot code runs as unoptimised tier 0, translated by FEX. | VERIFIED (mechanism); INFERRED (effect on StS2) |
| F5 | W^X is already off by default in this app, so a "wx-off" profile adds nothing. | VERIFIED |
| F6 | Proton avoids `wineboot --update` through `.update-timestamp`. A launch after a Proton update can still trigger it, and nothing logged that. | VERIFIED (mechanism) |
| F7 | `repair_foreign_system_dlls` scans both system folders on every launch. Its cost was not logged. | VERIFIED (code); cost unmeasured |
| F8 | The steam:// hand-off added about 0.5 s of average latency per poll stage (1 s polls), on two stages that are on the critical path. | VERIFIED (code); INFERRED (latency) |
| F9 | Steam set-up runs four or five Python helpers serially, each compiled from source under proot. The off-device profile estimates 1-4 s. Running them in parallel is unsafe as they stand: `droiddeck-steam-library`, `steam-compatibility` and `droiddeck-steam-shortcuts` all rewrite Steam's `config.vdf` or `shortcuts.vdf`. | VERIFIED (serial); INFERRED (cost) |
| F10 | Godot's pipeline and shader caches persist inside the prefix, so a second launch should not recompile. Whether they are being written on this device was not visible in the logs. | VERIFIED (paths); unmeasured |

## 4. Interventions, ranked

Expected effects are estimates (INFERRED) until the experiments in §5 run. "Cost" is engineering cost. "Risk"
is the risk to stability.

| Rank | Intervention | Expected effect | Cost | Risk | State |
|---|---|---|---|---|---|
| 1 | **FEX DiskCache for StS2** (`FEX_DISKCACHE=1`, cache in the prefix) | Second and later launches skip FEX translation of Wine's DLLs, `coreclr.dll`, `clrjit.dll`, the framework's R2R code and Godot's engine code. That is the bulk of FEX's first-run work. Literature on persistent DBT caches [Reddi 2007; Bruening 2008] reports large start-up gains for repeatedly started programs. JIT-written code hits only when the bytes repeat exactly. Seconds of launch time plausible; no number until measured. | Done | Medium: DiskCache is new upstream (FEX-2609). It is a no-op on an older FEX. | **Implemented**: default on for 2868840, `DROIDDECK_FEX_DISKCACHE` toggle |
| 2 | **.NET tiering profile** `DROIDDECK_DOTNET_WARMUP=pgo-off` | Removes the instrumented tier: one JIT and one FEX translation fewer per hot method, and no counter writes under TSO. Steady-state code loses PGO-guided layout and devirtualisation, a few percent in JIT-native benchmarks; under emulation, likely a net win. | Done | Low (a supported runtime switch) | **Implemented**, opt-in per game |
| 3 | `lean` profile (pgo-off, `TC_QuickJitForLoops=0`, `TC_CallCountingDelayMs=0`) | Hot code reaches tier 1 sooner (less warm-up stutter), with no OSR versions. Costs a slower first call of loop methods and more JIT work during loading screens. | Done | Low-medium (`TC_QuickJitForLoops` is "UNSUPPORTED_" in clrconfigvalues) | **Implemented**, opt-in |
| 4 | `single-tier` (`TieredCompilation=0`) | No tier-up during play, so the least late stutter. Every method is fully optimised at its first call, so the slowest start. HQEMU-style reasoning says a single optimising tier loses where most code runs once. | Done | Low | **Implemented**, opt-in, for comparison |
| 5 | **Steam hand-off polling** 0.25 s / 0.5 s | About 0.6 s off every steam:// launch on average; same total budgets | Done | Low | **Implemented** |
| 6 | **Timing lines** for launch prep, Steam set-up, components check, Wine prefix update and Godot caches | Turns F6, F7, F9 and F10 from guesses into numbers | Done | None | **Implemented** |
| 7 | Skip `repair_foreign_system_dlls` when the system folders have not changed (directory mtime + Proton path stamp) | Saves its measured cost on every launch | Small | Medium: a DLL overwritten in place does not change the folder mtime, and this repair exists to catch exactly such files | Not done: measure first (line in §5) |
| 8 | Merge the Steam set-up helpers into one importable module, cached as `.pyc` | 1-4 s per Steam start (off-device estimate) | Medium | Low-medium | Not done: owned by the Steam start path; the timing line now measures it |
| 9 | Run `droiddeck-seed-redists` alongside the `config.vdf` writers | Its share of set-up time | Small | Medium (needs a check that it touches no file the others write) | Not done |
| 10 | Precompile `droiddeck-game-env` and the modules it loads (import from a `.pyc`-cached module) | Tens to hundreds of ms per game launch under proot | Small-medium | Low | Not done |
| 11 | Ship StS2 with an R2R/composite image of its assemblies | Would remove tier-0 JIT of game code entirely | Not ours: the game's publisher builds it | — | Out of scope |

Interventions not taken:

- Turning W^X off: already done by the app's defaults.
- `DOTNET_ReadyToRun=0`: this would force the framework to JIT too.
- `DOTNET_TC_CallCountThreshold`: lowering it moves tier-up earlier much like `TC_CallCountingDelayMs=0`, but
  promotes more cold code. Not worth a separate profile until `lean` is measured.
- `EnableCodeCachingWIP`: AOT for file-backed images only, and marked work in progress upstream.

## 5. On-device experiment plan

Each run: a cold app start, Play on StS2, wait for the main menu, start a run, play 3 minutes, quit. Repeat 3
times per configuration, alternating configurations. Collect the session log and events.

Lines to read (all on this branch):

- `droiddeck-game-env: warmup: FEX disk cache on for app 2868840 (FEX-XXXX, <state>, folder …)`.
  - The first run shows `no cache yet`. Later runs show `N files, M MB`, and the size should grow over the
    first runs and then level off.
  - `FEX version not found` or `not set: this Proton's FEX is FEX-26xx` answers F2.
- `droiddeck-game-env: warmup: .NET profile <name>: …` confirms the profile was applied.
- `droiddeck-game-env: warmup: launch prep for app 2868840: N ms before Proton starts (components a ms, prefix
  DLL check b ms, warmup c ms)` measures F7 and the wrapper's own cost. If `prefix DLL check` is above ~100
  ms, do rank 7.
- `droiddeck-game-env: warmup: Wine will update this prefix (wineboot) before the game: …` should appear
  only after a Proton update (F6).
- `droiddeck-game-env: warmup: Godot caches: pipelines …; shaders …` should be non-empty from the second
  launch on, and stable in size (F10).
- `== steam setup before the client starts: library a ms, compatibility b ms, ui-scale c ms, shortcuts d ms,
  redists e ms` sizes F9 and ranks 8 and 9.
- `== steam: interface up after N ms (Steam in front after M ms); launching the requested game 2868840`
  times the Steam stage.
- `droiddeck-proton: components: checked N file(s) in <tool> for app 2868840 in K ms` times the per-launch
  component hash.
- `== STEP hh:mm:ss.mmm …` lines and the existing `== focused app: 2868840 at …` focus line mark the stage
  boundaries.

Time to playable is the gap between the `== droiddeck-session` header line and the focus line for 2868840,
plus the gap from there to the main menu. The latter needs a person with a stopwatch or a screen recording,
since no log line marks Godot's first interactive frame.

Experiments:

| Experiment | Configurations | Confirms if | Refutes if |
|---|---|---|---|
| E1 DiskCache | `DROIDDECK_FEX_DISKCACHE=0` vs default on, launches 2-3 | Launch-to-menu time falls on launches 2-3 with the cache on; cache size grows and then levels off | No time change, or the cache stays at 0 files (FEX too old or not reading the variable) |
| E2 DiskCache stability | Default on, 10 launches, plus one launch after a game update | No new crash or `DiskCache: validate … mismatch` lines in the FEX log (`%LOCALAPPDATA%\fex-<pid>.log` with FEX logging on) | Any crash that goes away with `DROIDDECK_FEX_DISKCACHE=0`: then turn the default off |
| E3 .NET profiles | unset, `pgo-off`, `lean`, `single-tier`; cache on | `pgo-off` or `lean` lowers 1 % low frame times in the first 3 minutes (perf HUD or frame telemetry) with no worse launch time | Higher stutter or slower launch than unset |
| E4 Hand-off | This branch vs base | "interface up after" minus "Steam in front after" falls by about 0.25-0.5 s | No change |
| E5 Prep costs | Any | Numbers for ranks 7-10 come out of the lines above | — |

Interactions: E1 and E3 interact, because DiskCache hits on JIT-written code need byte-identical JIT output
across runs. `pgo-off` makes tier-1 code more deterministic across runs, since there are no profile-dependent
decisions (INFERRED). So run E3 with the cache both on and off.

## 6. What this branch changes

- `tools/linuxfs/overlay/usr/local/bin/droiddeck-warmup` (new): the FEX cache setting, the .NET profiles and
  the diagnostics. Every line starts `droiddeck-game-env: warmup:`.
- `droiddeck-game-env`:
  - times `win_components` and the prefix DLL check, and calls `warmup_launch` just before sync selection
    (`:1227-1251`, `:1289-1302`, `:1406-1407`);
  - a failure in the warmup module is logged and never stops the game;
  - `DOTNET_FEX` is not touched.
- `droiddeck-session`:
  - the hand-off polls focus every 0.25 s and readiness every 0.5 s, with the same 120 s, 30 s and 20 s
    budgets, and logs elapsed times (`:1178-1206`);
  - Steam set-up is timed (`:1039-1064`).
- `steam-compatibility`: the components check logs its elapsed time (`:118,166-168`).
- Settings: `DROIDDECK_FEX_DISKCACHE` (0/1) and `DROIDDECK_DOTNET_WARMUP` (off, pgo-off, lean, single-tier) in
  `GameEnvironmentOptions.kt`. Both can be set for all games or per game.
- Tests: `tools/tests/test_warmup.py`, and `tools/tests/test_steam_cold_launch.py` updated for the new poll
  steps.

## References

- V. Bala, E. Duesterwald, S. Banerjia. Dynamo: a transparent dynamic optimization system. PLDI 2000.
- D. Bruening, T. Garnett, S. Amarasinghe. An infrastructure for adaptive dynamic optimization. CGO 2003.
- D. Bruening, V. Kiriansky. Process-shared and persistent code caches. VEE 2008.
- V. J. Reddi, D. Connors, R. Cohn, M. D. Smith. Persistent code caching: exploiting code reuse across
  executions and applications. CGO 2007.
- K. Hazelwood, J. E. Smith. Exploring code cache eviction granularities in dynamic optimization systems.
  CGO 2004.
- B. Hawkins, B. Demsky, D. Bruening, Q. Zhao. Optimizing binary translation of dynamically generated code.
  CGO 2015.
- D.-Y. Hong et al. HQEMU: a multi-threaded and retargetable dynamic binary translator on multicores. CGO
  2012.
- U. Hölzle, D. Ungar. Optimizing dynamically-dispatched calls with run-time type feedback. PLDI 1994.
- U. Hölzle, D. Ungar. A third-generation SELF implementation: reconciling responsiveness with performance.
  OOPSLA 1994.
- M. Arnold, S. Fink, D. Grove, M. Hind, P. F. Sweeney. Adaptive optimization in the Jalapeño JVM. OOPSLA
  2000.
- E. Barrett, C. F. Bolz-Tereick, R. Killick, S. Mount, L. Tratt. Virtual machine warmup blows hot and cold.
  OOPSLA 2017.
- dotnet/runtime release/8.0: `docs/design/features/tiered-compilation.md`, `src/coreclr/inc/clrconfigvalues.h`.
- FEX-Emu/FEX FEX-2611: files cited in §2.3.
- ValveSoftware/Proton proton_11.0: `proton`.
- godotengine/godot master: files cited in §2.5.
