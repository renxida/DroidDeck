# What the x86 translator (FEX) costs Slay the Spire 2, and what to fix first

Workstream: FEX inside Proton (ARM64EC). Branch `perf/emu-fex`. Nov 13, 2026.

## Short version

- Under FEX with the game's current settings (including FEX's disk cache, which `droiddeck-warmup` turns on for
  Slay the Spire 2), a .NET 9 workload that mirrors the game (Godot + coreclr + jitted C#) takes **2.29× native**
  time on an arm64 Neoverse runner (992 ms vs 434 ms per round).
- The biggest single cost is **translating x86 code**: 30–40% of wall time goes to FEX's JIT, and the first call
  of freshly jitted .NET code is **9–10× native**. Steady-state code is only 1.1–2.3× native.
- **Memory ordering (TSO) at the game's settings costs only +5.5%** over no ordering at all. Full TSO would cost +40%
  (memmove 11× native, vector math 6.6×); the game already avoids that.
- **Rewritten-code tracking (SMC)** accounts for about a quarter of first-call time without the disk cache
  (437 → 325 ms with it off). How much of that the disk cache already wins back is the next measurement
  (`fex_game_dc_nosmc`).
- **FEX's disk cache** cuts translation time by ~30% (1,755 → 1,215 ms of JIT), partly within a single run: code
  that SMC tracking threw away is found again by content hash instead of retranslated.
- Multiblock must stay on (+56% without it); turning .NET's tiered compilation off is worse (+36%).

So the high-leverage targets in FEX are, in order: translation volume and cost (cache more, translate less), SMC
handling for .NET's code heap, then dispatch. TSO tuning is mostly done.

## How it was measured

Off-device, on GitHub's `ubuntu-24.04-arm` runner (Neoverse), workflow `.github/workflows/fex-bench.yml`:

- `tools/fex/bench/Bench.cs`: one .NET 9 program with phases that match what the game does — first call of new
  methods (`first_jit`), allocation and GC, collections, virtual/interface dispatch, `Vector<T>` math, memmove of
  references and bytes, strings, LINQ, contended locks, and tiering churn (methods promoted from tier 0 to tier 1).
  Every phase feeds a checksum; every config must reproduce native's checksum (all did: -552291798).
- Native: arm64 .NET 9.0.23. Emulated: x64 .NET 9.0.23 in an Ubuntu 24.04 x86-64 rootfs, under Linux FEX built from
  the same source and patches as the game's ARM64EC FEX (`tools/fex/arm64ec/FEX_REF` + `patches/`).
- `fex_game` uses the game's FEX and .NET environment from `droiddeck-game-env` (fex-fast row): TSOENABLED=1,
  VECTORTSO=0, MEMCPYSETTSO=0, HALFBARRIERTSO=1, MULTIBLOCK=1; TieredPGO=0, EnableWriteXorExecute=0,
  gcConcurrent=0, GCConserveMemory=5. Other rows change one thing (`tools/fex/bench/configs.txt`).
- FEX's own counters come from `FEX_PROFILESTATS=1` (`/dev/shm/fex-<pid>-stats`), read by `tools/fex/stats/fex-stats.c`.
- Two rounds; best per phase. Run 43363579071 (commit 4ec2b46).

Linux FEX is not the ARM64EC FEX the game runs, but the translator (FEXCore), its JIT, TSO code and caches are the
same code. What differs is the frontend: syscalls vs Wine, and how SMC is detected (mprotect tracking on Linux,
the ARM64EC InvalidationTracker in Wine). The phone numbers come from `fex.csv` (below).

## Results

Total per round, ms, and multiple of native:

| Config | What changes | Total | × native | vs TSO off |
|---|---|---|---|---|
| native | arm64 .NET | 434 | 1.00 | |
| fex_notso | FEX, no memory ordering | 968 | 2.23 | 0 |
| fex_game | the game's FEX and .NET settings, disk cache off | 1,021 | 2.36 | +5.5% |
| fex_fulltso | full TSO incl. vector and memcpy | 1,360 | 3.14 | +40.4% |
| fex_game_nomb | multiblock off | 1,510 | 3.48 | +56.0% |
| fex_game_tc0 | .NET tiered compilation off | 1,315 | 3.03 | +35.9% |
| **fex_game_dc** | **+ FEX disk cache: what the game runs** | **992** | **2.29** | **+2.5%** |
| fex_game_nosmc | SMC tracking off (upper bound, unsafe) | 989 | 2.28 | +2.1% |
| fex_game_ldapr | LRCPC2 disabled | crashed (SIGSEGV) on Linux FEX | | |

Per phase, `fex_game` vs native: first_jit 10.3×, dispatch 2.3×, alloc_gc 1.9×, collections 1.7×, strings 1.7×,
vector_math 1.6×, threads 1.2×, memmove 1.1–1.2×, linq 1.1×, tiering_churn 1.1×.

FEX's counters (mean of two rounds; whole process including .NET startup):

| Config | wall s | JIT ms | JIT share | blocks translated | SMC faults | SIGBUS | signal ms |
|---|---|---|---|---|---|---|---|
| fex_notso | 4.1 | 1,788 | 44% | 25,698 | 1,346 | 0 | 68 |
| fex_game | 4.4 | 1,755 | 40% | 24,782 | 1,346 | 2,134 | 80 |
| fex_fulltso | 6.2 | 1,722 | 28% | 24,809 | 1,382 | 2,130 | 79 |
| fex_game_nomb | 6.1 | 1,251 | 20% | 205,994 | 1,534 | 1,894 | 47 |
| fex_game_tc0 | 4.5 | 1,538 | 34% | 21,646 | 1,142 | 1,860 | 26 |
| fex_game_dc | 4.3 | 1,215 | 29% | 6,806 | 1,400 | 1,972 | 54 |
| fex_game_nosmc | 4.4 | 1,656 | 38% | 23,010 | 0 | 2,002 | 0 |

`fex_game_dc` round 1 already found 11,538 blocks in the cache (code thrown away by SMC tracking and found again by
hash); round 2 found 23,387 and translated only 1,167.

## What it means for the game

1. **Translation is the bill.** ~25k blocks at ~70 µs each is 1.75 s of one core for a 4 s run. The game is far
   bigger (Godot, coreclr, RyuJIT, FMOD, DXVK, ICU and every jitted C# method) and keeps jitting new methods as
   new cards, relics and screens appear, which is where hitches come from. Every block FEX doesn't have to
   translate again is a direct win; when the disk cache is warm it finds about 95% of blocks instead of translating them, yet JIT time only drops ~30%,
   so lookups, loading and linking cached code are now a real share of the bill (disk_lookup_ms in `fex.csv`).
2. **SMC is a quarter of first-call cost.** With W^X off, .NET writes new methods into RWX pages next to code that
   already ran. Each write to a page FEX has translated faults, invalidates the whole 4 KB page, and retranslates
   what was on it. In ARM64EC that fault is handled under a global mutex. ~1,350 faults per run here.
3. **TSO is fine at current settings.** +5.5%; don't spend more there unless the phone says otherwise.
4. **Unaligned atomics (SIGBUS) are cheap**: ~2k per run, tens of ms.
5. Keep multiblock on and .NET tiered compilation on.

## Ranked next fixes (in FEX)

1. **Make sure the disk cache actually hits on the phone.** It hashes anonymous code by content, so .NET's jitted
   code can hit across sessions when the bytes match. `fex.csv` now shows disk_hit / disk_miss per process per
   5 s, so the first phone run answers this directly. If it's low, find out why (cache location inside proot,
   per-run address differences baked into code).
2. **Cheaper SMC for .NET's code heap under ARM64EC.** Options, smallest first: invalidate only the blocks that
   overlap the written range instead of the whole page; don't re-protect a page that only ever gets appended to;
   take the invalidation off the global mutex. Upper bound from this bench: −25% first-call time, −2% overall.
3. **Translate less per block or faster.** Profile FEX's JIT itself on the phone (simpleperf on the game process)
   to see whether the time is in the frontend, IR passes or register allocation.
4. Dispatch (2.3×): indirect-branch lookup cost; look at the L1/L2 lookup cache hit rate once 1–3 are done.

## What changed on the branch

- `tools/fex/arm64ec/`: our own build of the game's FEX — upstream FEX `ab135df` plus three patches:
  - 0001: vector TSO half barrier follows the op, so forced-TSO vector ranges keep their ordering.
  - 0002: `VolatileMetadataWholeBlocks` (default off).
  - 0003: keep `STLUR` on cores whose `LDAPUR` has erratum 3877900 (Cortex-X4 is one; Tensor G5 has one).
- `.github/workflows/build-fex-arm64ec.yml`: builds it and publishes `Kestrel-FEXCore-<date>.<run>-<upstream>-<patch set>.wcp`
  to the `kestrel-fex` prerelease. First: `Kestrel-FEXCore-20261113.3-ab135df09-664c5b38.wcp` (1.5 MB).
- Experiment `fex-kestrel` (off by default): installs the newest of those through the Auto component line, with the
  usual rollback after a failed boot.
- Experiment `fex-stats` (on by default): sets `FEX_PROFILESTATS=1` for the game, and the app writes FEX's counters for
  every emulated process to `fex.csv` in the session folder every 5 s (`FexStatsLog.kt`), included in the telemetry
  bundle. Columns: `t_wall_ms, interval_ms, pid, process, threads, jit_ms, jit_blocks, smc, sigbus, signal_ms,
  float_fallback, cache_miss, cache_rlock_ms, cache_wlock_ms, disk_hit, disk_miss, disk_lookup_ms`; every value is
  the change over the interval, so `jit_ms / interval_ms` is the share of one core spent translating.
- `.github/workflows/fex-bench.yml` and `tools/fex/bench/`: the off-device bench above.

## Notes

- `fex_game_ldapr` (`FEX_HOSTFEATURES=disablelrcpc2`) segfaults on Linux FEX at this commit before the program
  starts. It's a host-feature override, not something the game uses; recorded, not chased.
- The bench's artifacts can't be downloaded from here, so `run.sh` echoes every run into the job log.
