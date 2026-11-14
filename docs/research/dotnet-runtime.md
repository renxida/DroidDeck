# The game's .NET runtime under FEX: GC cost and how to measure it

Branch `emu/dotnet`. Slay the Spire 2 is MegaDot (Godot 4.5.1 mono) with a self-contained x64 .NET 9,
run by FEX inside Wine ARM64EC. This note covers what the runtime's garbage collector costs there, the
three experiments this branch adds, and how normal play will measure them. Nothing here is measured on
the Pixel yet.

## 1. What the telemetry already shows

Frames per 30 s from the uploaded sessions (INFERRED from `frame-log` timestamps):

| run | experiments | fps after minute 2 |
|---|---|---|
| main-bccccaf | none (full TSO, default GC) | 36-37, steady from 750 s to 1500 s |
| 040264f, 2796c7b, 92f7e10 | include `memory` and `fex-fast` | 11-16 from about 90 s |

Every slow run had both `memory` (`DOTNET_GCConserveMemory=5`) and `fex-fast`, which sets
`DOTNET_gcConcurrent=0` with relaxed TSO. In 2796c7b, swap-in during the slow part was low
(6-60 major faults a second) while about 2.2 GB of the game was in swap, so the drop does not look like
the GC walking swapped-out pages. The runs differ in other ways too, so this is a lead, not a cause.

## 2. Why the GC's count matters more than its pause total here

- **Suspension is expensive under Wine (INFERRED).** Each GC suspends every managed thread through
  `SuspendThread`/`GetThreadContext`, which go through wineserver and then FEX's signal handling. The
  cost is per GC, so 10x more GCs is roughly 10x more of it, even when the collector's own work stays
  the same.
- **Gen0 size depends on the cache size Wine reports (VERIFIED in source, unverified on the Pixel).**
  On workstation GC, gen0's minimum budget is 0.8 x the largest cache from
  `GetLogicalProcessorInformation`, at least 256 KB. Wine fills that from
  `/sys/devices/system/cpu/cpuN/cache/index*` (`dlls/ntdll/unix/system.c:1316`). If proot or Android
  hides those files, gen0 starts at 256 KB.
- **The maximum depends on background GC (VERIFIED, `release/9.0` `gc.cpp:43976-43986`).** With
  concurrent GC on, gen0's maximum is 6 MB. With `gcConcurrent=0`, as `fex-fast` sets, it is up to
  200 MB. `DOTNET_GCgen0size` raises the minimum, and the maximum becomes at least that.

The local bench (section 4) shows the scale on x64 hardware: with a 256 KB gen0 the game-like loop runs
1,950 GCs in 15 s, against 174 with a 64 MB gen0 and `fex-fast`'s GC settings, for about the same total pause. Under Wine the first
case pays the suspension cost 11 times more often.

## 3. Experiments (all off by default, set in `droiddeck-game-env`, CoreCLR games only)

| id | sets | for |
|---|---|---|
| `dotnet-trace` | EventPipe file output into the game's log folder, plus a watcher | measurement, see section 5 |
| `dotnet-gen0` | `DOTNET_GCgen0size=800000` (8 MB; 64 MB until Nov 14) | fewer GCs and suspensions; 64 MB made each gen1 a 120-170 ms hitch on the phone |
| `dotnet-gc-stock` | `DOTNET_gcConcurrent=1`, `DOTNET_GCConserveMemory=0` | undoes `fex-fast`'s and `memory`'s GC settings, to test section 1's lead |

`dotnet-gc-stock` runs after `fex-fast`'s fix, so it wins, and its conserve value of 0 stops
`dotnet_heap` from setting 5. `fex-fast`'s TSO settings stay.

## 4. Off-device bench

`tools/dotnet-gc-bench` is a stand-in for the game's managed side: a 300 MB long-lived object graph,
a frame loop that allocates 512 KB of strings and replaces 0.2 % of the graph each frame, and two
allocating worker threads. `run.sh` runs it under each experiment's settings with the same tracing
that `dotnet-trace` sets, and fails if the parser's GC count differs from the runtime's.
`.github/workflows/dotnet-gc-bench.yml` runs it with .NET 9 on x64 and arm64 runners.

On a 4-core x64 server with a 260 MB L3, 15 s each (.NET 8):

| config | fps | p95 ms | p99 ms | GCs | paused ms |
|---|---|---|---|---|---|
| default | 204.9 | 5.1 | 123.6 | 91 | 9,759 |
| shipped (`fex-fast` + `memory` GC settings) | 203.6 | 5.1 | 115.6 | 93 | 9,888 |
| `dotnet-gen0` | 187.3 | 49.9 | 76.8 | 173 | 10,150 |
| `dotnet-gc-stock` | 198.3 | 5.1 | 128.4 | 91 | 9,709 |
| 256 KB gen0 (a phone with no cache info) | 169.2 | 14.6 | 19.8 | 1,950 | 10,806 |

This host reports a large cache, so its default gen0 is already larger than 64 MB, and `dotnet-gen0`
shrinks it here. The bench cannot show Wine's suspension cost; it shows how many GCs each setting
makes and checks that the trace reads them right. Only the phone can say which setting wins.

## 5. Measuring it in normal play

With `dotnet-trace` on, the runtime streams GC and counter events to
`dotnet-<pid>.nettrace` in `DROIDDECK_GAME_LOG_DIR`, and `droiddeck-dotnet-trace --watch 60` turns
them into three tables there every minute, which the telemetry upload already picks up:

- `dotnet-gc.txt`: one row per GC with generation, type, reason, compaction, pause, suspension time and
  generation sizes.
- `dotnet-counters.txt`: System.Runtime counters per second (time in GC, heap size, allocation rate,
  JIT time, thread pool).
- `dotnet-summary.txt`: per minute, GC counts, pause and suspension totals and p95, time in GC, JIT
  time and heap size.

The trace is about 14-21 KB a second for the bench and is read incrementally, one block at a time; the
watcher stops reading past 512 MB.

Recommendation for the rotation: put `dotnet-trace` in "always" and rotate `dotnet-gen0` and
`dotnet-gc-stock`. Then, against the fps per minute: suspension time per minute says whether the GC
is the 45-to-14 fps drop; the GC count in runs without `dotnet-gen0` says what gen0 size the Pixel
really gets.

## 6. Next

- Read the first traced sessions. If GCs are few and suspension is small, the runtime is not the
  bottleneck and this workstream moves to JIT (tiering, `TC_BackgroundWorkerTimeoutMs`, ReadyToRun).
- If gen0 starts at 256 KB, also set the cache size through `DOTNET_GCgen0size` for every CoreCLR game,
  not just as an experiment.
- A patched x64 coreclr only if config cannot reach what the traces point at.
