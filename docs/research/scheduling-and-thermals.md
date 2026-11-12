# Thread placement, pacing and thermals for Slay the Spire 2 on Tensor G5

Branch `perf/sched`, November 2026. Nothing here was measured on a Pixel 10: this environment has no
device. Every finding is marked **VERIFIED** (read in source, with file and line) or **INFERRED**
(reasoned from sources, or from specifications not confirmed on the phone). The experiment plan at
the end names the log lines that would confirm or refute each claim.

## 1. Question

How should the threads of the StS2 stack be placed and paced on a Pixel 10 (Tensor G5) so that the
game runs fast and *holds* its frame rate without thermal throttling, using only what an
unprivileged Android app can control?

The stack, as this repo runs it (VERIFIED, `docs/development/proot-performance.md:46-53`):

```
SessionService ─▶ libproot.so (one tracer, nice -6)
  └─ droiddeck-session ─▶ gamescope (nested, wayland backend) ─▶ Steam client (FEX, x86-64 Linux)
       └─ droiddeck-game-env ─▶ ARM64 Proton ─▶ wine (ARM64EC, FEX in-process as a DLL)
            └─ wineserver, SlayTheSpire2.exe (Godot 4 + coreclr) ─▶ winevulkan ─▶ PowerVR (via hybris layers)
```

## 2. Background

### 2.1 Tensor G5

- CPU: 1× Cortex-X4 (3.78 GHz), 5× Cortex-A725 (3.05 GHz), 2× Cortex-A520 (2.25 GHz); GPU
  Imagination DXT-48-1536 at 0.40–1.10 GHz; TSMC 3 nm
  ([cpu-monkey](https://www.cpu-monkey.com/en/cpu-google_tensor_g5); the 1+5+2 layout is
  Google's own statement and the core names come from leaks, per
  [Android Authority](https://www.androidauthority.com/google-tensor-g5-3583168/)). Cache sizes were
  not published in either source. **INFERRED** on the phone: three cpufreq policies, the A520s on
  the lowest CPU numbers and the X4 on cpu7, as on Tensor G4. The `clocks.log` header line
  `cpu policies: …` confirms the numbering (see §6).
- The A520 is an in-order core. Under FEX it runs translated x86-64 code with a fraction of the
  A725's throughput. The A725 and X4 are out-of-order.
- Sustained behaviour: the base Pixel 10 uses a graphite spreader, the Pros a vapour chamber
  ([Android Authority](https://www.androidauthority.com/google-tensor-g5-3583168/)). Reviews
  disagree on the Pro XL's GPU stability: 95.1% in 3DMark Wild Life Extreme Stress
  ([Notebookcheck](https://www.notebookcheck.net/Google-Pixel-10-Pro-XL-and-Tensor-G5-deliver-class-leading-stability-in-GPU-test.1097994.0.html))
  against 60% at 44.4 °C ([Android Authority](https://www.androidauthority.com/pixel-10-tensor-g5-benchmarks-3591999/)).
  The base Pixel 10 "maintained its CPU and GPU performance level above 50% at all times" and ran
  hot to the touch ([GSMArena](https://www.gsmarena.com/google_pixel_10-review-2878p4.php)).
  Assume the base model can lose up to half its peak under sustained load. No Pixel 10 `clocks.log`
  was available to this study: none is in the repo, the scratch area or the upstream issues that
  search reached.

### 2.2 Literature on mobile game power, DVFS and pacing

- Pathania, Jiao, Prakash, Mitra, *Integrated CPU-GPU Power Management for 3D Mobile Games*, DAC
  2014. Games are frame-rate-target workloads: running the CPU and GPU just fast enough for the
  target saves large amounts of energy over the stock governors.
- Prakash, Amrouch, Shafique, Mitra, Henkel, *Improving Mobile Gaming Performance through
  Cooperative CPU-GPU Thermal Management*, DAC 2016
  ([doi:10.1145/2897937.2898031](https://researchr.org/publication/PrakashASMH16)). The CPU and GPU
  share one thermal budget. Uncoordinated boosting makes the governor throttle both later, so the
  sustained frame rate ends lower than a steady, moderate setting would give.
- Hwang et al., *RAVEN: Perception-aware Optimization of Power Consumption for Mobile Games*,
  MobiCom 2017. Many frames are perceptually redundant, and lowering the rendered frame rate where
  players can't see it saves power with no visible loss. A turn-based card game is the extreme case.
- Co-Cap, cooperative CPU-GPU frequency capping for mobile games
  ([listing](https://archive.cps-vo.org/biblio/view/39094)). It is a further instance of
  frequency caps chosen per game to cut energy at an equal frame rate.
- Linux Energy Aware Scheduling (`Documentation/scheduler/sched-energy.rst`) and utilisation
  clamping (`Documentation/scheduler/sched-util-clamp.rst`): the scheduler places a task by its
  utilisation and the energy model, and the per-cluster governor picks the frequency from the sum
  of the utilisation on that cluster.
- Android ADPF (PerformanceHintManager, API 31) lets an app name its critical threads and report
  their work durations, and the PowerHAL turns that into uclamp/boost. The thermal headroom API
  (`PowerManager.getThermalHeadroom`, which this repo already calls on API ≥ 30 in
  `ClockLogComponent.kt:66-68`) forecasts how close the device is to throttling. Swappy, the
  Android Frame Pacing library, pairs a frame-rate cap with the display refresh so frames land on
  vsync boundaries. These API levels are from the Android docs and were not re-fetched here.

The common thread: **a capped, steady workload beats a bursty uncapped one for sustained frame
rate.** Power rises superlinearly with frequency, because voltage rises with it, and a throttle
event is a cliff.

## 3. What an unprivileged app can control (findings)

| lever | status | evidence |
|---|---|---|
| `sched_setaffinity` on its own threads, within its cpuset | **VERIFIED allowed** by seccomp (bionic `SYSCALLS.TXT:310`) and the kernel (same owner). AOSP creates `top-app` with all CPUs (`rootdir/init.rc:335-337`). Pixel device rc files can narrow it: **INFERRED** that `top-app` is 0-7. | Wine already calls it for every thread (below) |
| lower priority (positive nice) | VERIFIED allowed | kernel `user_check_sched_setscheduler`, v6.12 `kernel/sched/syscalls.c:468-524` |
| raise priority to nice −20 | **VERIFIED allowed**: AOSP `init.rc:43` sets `setrlimit nice 40 40` | Wine uses it (below) |
| `SCHED_FIFO`/`SCHED_RR` | **VERIFIED denied**: no `rtprio` rlimit in `init.rc`, so RLIMIT_RTPRIO=0 and `syscalls.c:478-483` requires CAP_SYS_NICE | |
| `SCHED_DEADLINE` | VERIFIED denied (`syscalls.c:497`) | |
| per-task uclamp via `sched_setattr` | **VERIFIED allowed** by mainline 6.12: no capability check on `SCHED_FLAG_UTIL_CLAMP` in `user_check_sched_setscheduler`, and `sched_setattr` is in bionic `SYSCALLS.TXT:311`. **INFERRED**: Pixel's vendor scheduler hooks may override or ignore it. | not used; see §5 |
| ADPF hint sessions | VERIFIED in use: `GameThreadHints.kt`. On `fork/main` it runs on Odin 3 only; `perf/adpf-all` widens it to API ≥ 31 | not redone here |
| display refresh vote | VERIFIED in use: `sc_layer.c:619-633` (`ASurfaceTransaction_setFrameRateWithChangeStrategy`), `PerfMode.kt:15-55` | |
| Game Mode | VERIFIED: the app declares `isGame` and supports the performance and battery modes; `allowGameFpsOverride="false"`, so Android's FPS intervention is off (`res/xml/game_mode_config.xml`) | |
| cgroup `cpu.uclamp.*` / cpuset writes | denied: `system`-owned (`init.rc:345-358`) | |

### 3.1 Wine already maps placement and priority (VERIFIED, ValveSoftware/wine `proton_10.0` and `bleeding-edge`)

- `WINE_CPU_TOPOLOGY=n:h0,h1,…` sets the logical CPU count Windows sees and the host CPU behind
  each logical CPU (`dlls/ntdll/unix/system.c:687-850`). wineserver then calls `sched_setaffinity`
  for every new thread with the mask translated through that mapping (`server/thread.c:669-697`,
  called from `init_thread` at `:1665-1666` and `init_first_thread` at `:1629-1632`). Logical
  CPUs beyond the count are dropped. **So an explicit `WINE_CPU_TOPOLOGY` both shrinks what the
  game sees and confines every game thread to the listed host cores.** The ARM64EC FEX JIT runs on
  those same threads, in-process.
- The count-only form (`WINE_CPU_TOPOLOGY=6`) picks the *first* N host CPUs unless
  `/sys/devices/cpu_core/cpus` exists, which is Intel hybrid only (`system.c:737-781`,
  `fill_performance_core_info`). On a Pixel that would pick the A520s first. The repo already
  writes the explicit, fastest-first form (`droiddeck-game-env` `cpu_topology`, `fastest_first`),
  which avoids this trap.
- Thread priority: wineserver probes RLIMIT_NICE (`server/thread.c:235-252`). With Android's 40 it
  gets `nice_limit = -20` and maps NT base priorities 1..15 linearly onto nice 20..−20
  (`:254-265`). THREAD_PRIORITY_NORMAL becomes nice 0, HIGHEST (base 10, .NET's finalizer) becomes
  nice −5, and TIME_CRITICAL (audio) becomes nice −20. **INFERRED** that this works under proot:
  proot shares the pid namespace and passes `setpriority` straight through
  (`proot-performance.md:26-28`). There is nothing to add here, and nice values matter little
  under EAS anyway, which places by utilisation.

### 3.2 Who creates threads in the game process

- **Godot main thread.** It runs game logic (C#) *and*, by default, render submission:
  `rendering/driver/threads/thread_model` defaults to Safe, meaning render on the main thread
  (godot 4.5 `main/main.cpp:2690`). VERIFIED for upstream defaults. StS2's project setting is not
  visible. This is the critical thread, and ADPF should hint it.
- **Godot WorkerThreadPool.** `threading/worker_pool/max_threads = -1`
  (`core/register_core_types.cpp:328`) gives `OS::get_default_thread_pool_size()` =
  `get_processor_count()` (`core/os/os.h:328`, `worker_thread_pool.cpp:826-827`): **one worker per
  CPU Windows reports**, plus a low-priority share of 0.3. VERIFIED.
- **.NET (coreclr).** The Godot .NET SDK sets no GC options (godot 4.5 `Godot.NET.Sdk/Sdk/Sdk.props`
  and `Sdk.targets` contain none). So the runtime defaults apply: workstation GC, concurrent
  (`gc/gcconfig.h:66-67`), one background GC thread. `GCHeapCount` and `GCHeapAffinitizeMask`
  apply only to Server GC (`gcconfig.h:85,95`), **so capping GC heaps is moot unless StS2 ships
  `System.GC.Server=true`** (INFERRED: its runtimeconfig was not inspected). The thread pool and
  `Environment.ProcessorCount` come from `GetProcessAffinityMask` (`utilcode/util.cpp:960-1010`).
  Under Wine that is the topology-mapped mask, so it follows `WINE_CPU_TOPOLOGY`.
  `DOTNET_PROCESSOR_COUNT` overrides it directly (`clrconfigvalues.h:524`, base 10).
- **FEX (ARM64EC).** It adds no compile threads: JIT happens on the guest thread itself. Its only
  utility thread is the code-cache validation thread (`FEXCore/Source/Interface/Core/CodeCache.cpp:400`,
  upstream FEX `ab135df`). VERIFIED. The *Steam client* runs as x86-64 Linux under FEXInterpreter,
  but it is pinned separately (`BL_CLIENT_CPUS`, `droiddeck-session:1610-1619`).
- **wineserver.** One native ARM64 process. Synchronisation: the repo uses its own userspace
  `/dev/ntsync` emulation (memfd + futex, `tools/linuxfs/preload/ntsync.c:1376-1401`) or esync and
  fsync packs (`droiddeck-esync`), because the Android kernel has no ntsync driver. With ntsync and
  fsync, waits don't round-trip through wineserver, so wineserver is not a hot thread (INFERRED).
- **DXVK / vkd3d-proton.** **Not in the StS2 path.** `godot_vulkan` forces
  `--rendering-driver vulkan` (`droiddeck-game-env:118-121`), so Godot calls winevulkan directly.
  `DXVK_CONFIG numCompilerThreads` doesn't apply. Pipeline compilation happens in Godot's own
  shader-compilation tasks on the WorkerThreadPool (INFERRED from Godot 4 RenderingDevice design).
- **The proot tracer.** One thread serialises every trapped syscall in the session (thread
  creation, path calls) at 15–60 µs each, and cross-core wake-ups dominate
  (`proot-performance.md:30-39,112-140`). Off the steady-state frame path; it matters for loading.
- **Audio.** PulseAudio with DirectAudio, one or two threads; Godot's WASAPI thread under Wine.

**Oversubscription.** Today the game sees all 8 cores, so Godot starts 8 workers, and .NET sizes its
pool for 8. That runs on 8 heterogeneous cores, two of them in-order A520s that also host the Steam
client, gamescope and wineserver. A Godot group task finishes when its slowest participant does.
Work stealing via an atomic index (`worker_thread_pool.cpp`) bounds the damage, but a task already
claimed by a worker on an A520 still finishes late. EAS also bills A520 time at little-core
capacity, so a worker that is busy there in bursts can stay there.

Upstream evidence that cluster mixing hurts: on SM8850, pinning the game to only the two prime
cores *raised* the prime clocks from ~1 GHz to 4.6 GHz and fps from ~85 to ~150. The prime cores
then reached ~90 °C in a minute
([Droid-Deck/DroidDeck#2437](https://github.com/Droid-Deck/DroidDeck/issues/2437)). Another
report found the frame rate followed whichever display mode Android picked
([#950](https://github.com/Droid-Deck/DroidDeck/issues/950)).

### 3.3 Frame pacing as built today (VERIFIED)

- The in-app compositor can cap by holding `wl_buffer` releases (`compositor.c:468-492`) and by
  holding scene renders (`compositor.c:2192-2204`). It votes the panel rate with
  `setFrameRate` (`sc_layer.c:619`).
- In a Steam session that cap is **off**: `outputFpsLimit` passes the overlay's FPS limit only
  for no-Steam runs (`SessionActivity.kt:1966-1967`), and otherwise `compositorFpsLimit` returns
  `FPS_UNPACED` (`SessionPrefs.kt:1203-1206`). Gamescope's limiter file is exported
  (`droiddeck-session:518-520`), but the PowerVR path disables the gamescope WSI layer
  (`ENABLE_GAMESCOPE_WSI=0`, `droiddeck-game-env` `system_graphics_launch`), and that layer is what
  enforces it (INFERRED from gamescope's design). **So StS2 on the Pixel has no working frame-rate
  cap other than vsync at the panel rate, which can be 120 Hz.**
- Godot's own limiter: `--max-fps N` (`main.cpp:1695-1703`) sets `Engine.max_fps`
  (`main.cpp:2065-2068`). `OS::add_frame_delay` (`os.cpp:684-717`) sleeps the main thread until
  the next 1/N deadline, so the CPU and GPU both idle. On Windows the sleep is `Sleep(ms)`
  (`os_windows.cpp:887-893`) with `timeBeginPeriod(1)` (`:289-293`). The deadline is clamped to
  ±1 interval, so ms truncation doesn't drift. **INFERRED risk:** if StS2 writes `Engine.MaxFps`
  from its own settings at runtime, that overrides the launch flag.
- A cap that is not a divisor of the panel rate judders. Pixel 10 panels run 60 or 120 Hz, so 60,
  40 and 30 pace evenly and 45 does not. `PerfMode.cadenceFits` encodes the same rule for the
  compositor's cap.

## 4. Ranked interventions

| # | intervention | expected effect | cost | risk | status |
|---|---|---|---|---|---|
| 1 | **60 fps cap for StS2 via Godot `--max-fps`** (`DROIDDECK_FPS_CAP`, also DXVK `maxFrameRate` and `VKD3D_FRAME_RATE` for other games) | Where StS2 renders above 60 at 120 Hz (menus, map, idle combat), CPU+GPU work roughly halves. Lower OPPs cut power by more than half (V²f), which delays or avoids throttling, so the *sustained* frame rate rises. No effect while the game is below 60. | none | the game may override `Engine.MaxFps`; a card game loses nothing visible at 60 | **implemented**, default on for StS2 |
| 2 | **Thread budget "big": WINE_CPU_TOPOLOGY over the non-little cores** (`DROIDDECK_GAME_CPU_BUDGET`) | Godot workers 8→6 and .NET's processor count 8→6. All game threads, including FEX-translated code, stay off the in-order A520s, which then host Steam, gamescope, wineserver and audio. Fewer stragglers in group tasks and less contention with the session's helpers. | none | EAS loses the option to park light game threads on A520s (slightly more power for light threads). If the Pixel cpuset for top-app excludes some cores, Wine's `sched_setaffinity` fails silently and only the count changes. | **implemented**, default on for StS2 |
| 3 | ADPF on Tensor (perf/adpf-all) plus an ADPF target derived from the cap (16.6 ms at 60, not the fixed 10 ms) | Hints only the main and render threads, so the PowerHAL boosts what matters instead of the whole cluster. Reporting 15 ms against a 10 ms target forever (`GameThreadHints.kt:80`) asks for a permanent boost, which works *against* thermals once the cap holds. | small | conflicts with the owner of perf/adpf-all | recommended, not done here |
| 4 | Vote the panel rate to the cap (60 Hz when the cap is 60) | Display and composition power; the compositor wakes half as often | small (Kotlin must learn the per-game cap) | refresh switch flicker | recommended |
| 5 | Thermal-headroom-driven cap (step 60→40→30 when `getThermalHeadroom` > 0.85, back when < 0.7) | avoids the throttling cliff and degrades smoothly, as in Prakash 2016 | medium: needs a live cap channel; Godot's `--max-fps` is launch-only | oscillation; needs hysteresis | future |
| 6 | Per-task `uclamp.max` on non-critical game threads (workers, GC) via `sched_setattr` | keeps the A725 cluster frequency down when only workers are busy | medium | vendor scheduler may ignore it; unmeasured | future |
| 7 | Pin the proot tracer near the game (same cluster) | faster loading (cross-cluster traps are 42–61 µs against 34–36 µs within a cluster) | small | could compete with the main thread | future, loading only |
| 8 | `DOTNET_GCgen0size` larger, `DOTNET_TieredPGO=0` | fewer GC suspensions and less instrumentation overhead under emulation | none | memory and startup trade-offs; owned by the .NET/TSO worker | hand to that worker |
| — | Server-GC heap caps, DXVK compiler threads | not applicable to StS2 (workstation GC; no DXVK) | | | rejected with evidence above |

## 5. What this branch implements

All of it is in `tools/linuxfs/overlay/usr/local/bin/droiddeck-game-env`, with tests in
`tools/tests/test_game_pacing.py`.

- `slay_the_spire_2_pacing` (fix, line 131): for `SlayTheSpire2.exe` with a Godot pack, it sets
  `DROIDDECK_GAME_CPU_BUDGET=big` and `DROIDDECK_FPS_CAP=60`. These are ordinary fix values, so a
  per-game profile overrides them (`all` or `0` undoes them). Log line:
  `droiddeck-game-env: Slay the Spire 2: game threads kept off the little cores, 60 fps cap (...)`.
- `thread_budget` (line 482), used by `cpu_topology`:
  - `big` keeps cores at ≥ ½ of the top `cpu_capacity` (the IPC-weighted scale), or at ≥ ⅔ of
    the top `cpuinfo_max_freq` when capacity is unreadable.
  - A number keeps that many of the fastest cores; at least 2 are always kept.
  - On Tensor G5 `big` keeps 6. On SM8750/SM8850, whose six performance cores are only clocked
    lower, it keeps all 8.
  - Log line: `droiddeck-game-env: thread budget big: game threads on 6 of 8 cores (7,2,3,4,5,6), left out 0,1`,
    followed by the existing `Windows sees 6 cores (6:7,2,3,4,5,6)`.
- `frame_cap` (line 516):
  - It adds `--max-fps N` before any Godot user-argument separator (`--`/`++`, `main.cpp:1897`),
    keeping a `--max-fps` the player put in the launch options.
  - It appends `dxvk.maxFrameRate = N` unless one is set, and sets `VKD3D_FRAME_RATE` unless set.
  - Log line: `droiddeck-game-env: frame cap 60 fps (Godot --max-fps, DXVK, VKD3D)`.
- `thread_placement` / `start_thread_check` / `thread_check` (lines 547, 582, 593): when a budget is set,
  a detached check runs 60 s and 180 s after launch and logs the game's threads, their
  `Cpus_allowed_list`, and CPU ticks by last-run core. It stops as soon as the launch ends, because
  Steam's reaper counts it as part of the game:
  `droiddeck-game-env: thread placement: SlayTheSpire2.exe pid N, M threads, allowed 2-7 (M), cpu ticks by last core 2:… 7:… after 60 s`.
  This line shows directly whether Android let Wine confine the threads.
- The app's game-environment editor offers both variables (`GameEnvironmentOptions.kt`):
  `DROIDDECK_GAME_CPU_BUDGET` (big, all, 6, 4) and `DROIDDECK_FPS_CAP` (60, 40, 30, 0).

Untouched: the DOTNET_FEX TSO values, telemetry, ADPF, and the compositor.

## 6. On-device experiment plan

Setup: Pixel 10, Kestrel APK from this branch, StS2. Use the same save, a 15-minute loop (map
screen, then three combats), the phone at 25 °C room temperature on a table, not charging, brightness
fixed. Collect `clocks.log`, the session log, the HUD frame log (or the `test/telemetry` frame CSV
plus `tools/perf_report.py` when merged), and `dumpsys thermalservice` before and after.

Arms, each run twice from a cold start (the phone below 33 °C on `battery` in `clocks.log`):

- A: baseline (`DROIDDECK_GAME_CPU_BUDGET=all`, `DROIDDECK_FPS_CAP=0` in StS2's profile)
- B: budget only (`big`, cap 0)
- C: cap only (`all`, cap 60)
- D: both (the branch default)
- E: D with cap 40

| claim | confirm | refute |
|---|---|---|
| core numbering as assumed | `clocks.log` header `cpu policies: policy0 0-1, policy2 2-6, policy7 7` | other layout. The budget follows `cpu_capacity`, so it stays correct either way; re-read the `left out` list. |
| the budget took effect | session log `thread budget big: … left out 0,1`, `Windows sees 6 cores (6:7,2,3,4,5,6)` | missing lines mean the fix didn't match (wrong exe stem or no `.pck`) |
| Wine confined the threads | `thread placement: SlayTheSpire2.exe … allowed 2-7 (M)` with M = all threads, and no ticks on cores 0,1 | `allowed 0-7`: Android or proot refused `sched_setaffinity`, or the cpuset differs; only the thread counts changed |
| the cap took effect | `frame cap 60 fps (Godot --max-fps, …)`, and the HUD fps ≤ 60 in menus where arm A shows > 60 | HUD > 60 with the log line present means StS2 overrides `Engine.MaxFps`. Fall back to a compositor-side cap for Steam sessions. |
| the cap lowers power and heat | in `clocks.log`, C/D against A: lower `p2`/`p7` current MHz in menus, `headroom` rising more slowly, `thermal` staying 0 longer, battery °C slope lower | no difference: the game was already below 60 everywhere |
| the budget helps frame time | D against C: lower p95/p99 frame time in combat, and fps equal or higher | worse p99: the two A520s were doing useful work; try budget `all` with cap |
| sustained fps improves | D/E against A: fps in minutes 12–15 divided by fps in minutes 0–3 closer to 1, and `scaling_max_freq` caps (`(of …)` in `clocks.log`) appearing later or not at all | ratio unchanged |
| ADPF interplay (with perf/adpf-all merged) | `GameThreadHints` lines `boosting SlayTheSpire2.e/<tid> …` naming the main thread, and no `boosting` churn | boosting worker threads: lower `ENTER`, or pass the main tid explicitly |

Report each arm's median fps, p95/p99 frame time, minutes to the first `thermal ≥ 1`, and mean
battery °C slope. Two runs per arm are the minimum; thermal runs are noisy.

## 7. Open questions and risks

- StS2's own settings may override `--max-fps`, and the vsync mode is not known. Both were
  unverifiable without the game files (§6 tells which).
- The Pixel's `top-app` cpuset and vendor scheduler hooks are unknown. The budget degrades to
  count-only if affinity is refused.
- The 60 fps default changes what HUD fps comparisons mean for StS2. Anyone benchmarking peak fps
  must set `DROIDDECK_FPS_CAP=0` for the game.
- All expected effects are unmeasured.
