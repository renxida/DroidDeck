# What the x86 translator (FEX) costs Slay the Spire 2, and what to fix first

Workstream: FEX inside Proton (ARM64EC). Branch `perf/emu-fex`. Nov 13, 2026.

## Short version

- Under FEX with the game's current settings (including FEX's disk cache, which `droiddeck-warmup` turns on for
  Slay the Spire 2), a .NET 9 workload that mirrors the game (Godot + coreclr + jitted C#) takes **2.3–2.4× native**
  time on an arm64 Neoverse runner (992–1,013 ms vs 424–434 ms per round).
- **Round 2 win: a full-size block lookup cache.** FEX ships with a small per-thread lookup cache (8k entries) that
  only grows after a second of misses and is flushed on every resize. Starting it at full size
  (`FEX_DYNAMICL1CACHE=0`) cut total time **19%** (1,013 → 822 ms, 2.39× → 1.94× native) and **first-call time 2×**
  (401 → 189 ms), and slow lookups fell ~8× (452k → 60k). The shared L2 lookup (`FEX_DISABLEL2CACHE=0`) gives the
  same; both together add nothing. It is experiment **`fex-l1`** (off by default, for the phone rotation).
- Profile (warm disk cache): 76% of time is in translated code, 16% in FEX itself, 7% kernel. FEX's top costs are
  linking cached blocks and hash-table lookups (CacheBlockMapping, the block map, AddBlockMapping), not translating.
  Without the cache, FEX's own share is 34%, led by register allocation, flag elimination and decoding.
- **Memory ordering (TSO) at the game's settings costs only +5.5%** over no ordering at all. Full TSO would cost +40%;
  the game already avoids that.
- **Rewritten-code tracking (SMC)**: turning it off (unsafe, an upper bound) saves ~14% on top of the disk cache
  (1,013 → 868 ms). Cheaper SMC for .NET's code heap is the next FEX change.
- Multiblock must stay on (+56% without it); turning .NET's tiered compilation off is worse (+36%).

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
   translate again is a direct win; when the disk cache is warm it finds about 95% of blocks instead of translating them, yet JIT time only halves (1,726 → 908 ms),
   so lookups, loading and linking cached code are now a real share of the bill (disk_lookup_ms in `fex.csv`).
2. **SMC is a quarter of first-call cost.** With W^X off, .NET writes new methods into RWX pages next to code that
   already ran. Each write to a page FEX has translated faults, invalidates the whole 4 KB page, and retranslates
   what was on it. In ARM64EC that fault is handled under a global mutex. ~1,350 faults per run here.
3. **TSO is fine at current settings.** +5.5%; don't spend more there unless the phone says otherwise.
4. **Unaligned atomics (SIGBUS) are cheap**: ~2k per run, tens of ms.
5. Keep multiblock on and .NET tiered compilation on.

## Round 2: profile and lookup caches

Run 43366998791 (`perf record` on the bench under FEX, `tools/fex/bench/profile.sh`), share of samples:

| Config | Translated code | FEX | Kernel | Top FEX symbols |
|---|---|---|---|---|
| fex_game_dc (warm cache) | 75.7% | 15.9% | 6.9% | CacheBlockMapping 1.5%, block map find 1.2%, register allocation 1.0%, block entry search 0.9%, decode 0.9% |
| fex_game (no cache) | 57.7% | 34.4% | 5.6% | register allocation 4.4%, dead-flag pass 2.8%, decode 2.8%, IR generation 1.8%, code emission 1.8% |

Run 43367728801, three rounds, best per phase:

| Config | Total ms | × native | first_jit ms | slow lookups | JIT ms |
|---|---|---|---|---|---|
| native | 424 | 1.00 | 42 | | |
| fex_game | 1,009 | 2.38 | | | |
| fex_game_dc | 1,013 | 2.39 | 401 | ~452k | ~670 |
| fex_game_dc_nosmc | 868 | 2.05 | | | |
| fex_game_dc_l2 (`FEX_DISABLEL2CACHE=0`) | 820 | 1.93 | 188 | ~52k | ~525 |
| fex_game_dc_l1 (`FEX_DYNAMICL1CACHE=0`) | 822 | 1.94 | 189 | ~60k | ~525 |
| fex_game_dc_l1l2 | 819 | 1.93 | 191 | ~40k | ~525 |

All configs reproduced native's checksum. Why it helps: with the disk cache, blocks are found rather than
translated, so far more jumps leave a block and look up the next one; with an 8k-entry per-thread table those miss
into the slow path (counted as JIT time). A full table is 1M entries × 16 B = 16 MB of address space per thread,
already reserved by FEX either way and backed only as touched, so the memory cost is bounded. The L2 option backs
64 KB per guest code page, so `fex-l1` is the one to ship.

Caveat: the bench is 4 s; in a long game session the dynamic table may have grown by itself. The phone answers it:
`fex.csv`'s `cache_miss` per 5 s with `fex-l1` on vs off.

## Ranked next fixes (in FEX)

1. **Ship `fex-l1`** after the phone confirms lower `cache_miss` and `jit_ms` in `fex.csv` and no memory regression.
2. **Make sure the disk cache actually hits on the phone** (`disk_hit` / `disk_miss` in `fex.csv`).
3. **Cheaper SMC for .NET's code heap under ARM64EC** (up to ~14% here). Options, smallest first: invalidate only the
   blocks that overlap the written range instead of the whole page; don't re-protect a page that only ever gets
   appended to; take the invalidation off the global mutex.
4. **Cheaper linking of cached blocks** (CacheBlockMapping and the block map are FEX's top self-time with the cache).
5. Translated-code quality: 76% of time with a warm cache, so dispatch and codegen are the long-term lever.

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
- Experiment `fex-l1` (off by default): sets `FEX_DYNAMICL1CACHE=0` for games, so FEX's per-thread block lookup
  cache is full size from the start.
- `.github/workflows/fex-bench.yml` and `tools/fex/bench/`: the off-device bench above, plus `profile.sh` (perf).

## Notes

- `fex_game_ldapr` (`FEX_HOSTFEATURES=disablelrcpc2`) segfaults on Linux FEX at this commit before the program
  starts. It's a host-feature override, not something the game uses; recorded, not chased.
- The bench's artifacts can't be downloaded from here, so `run.sh` echoes every run into the job log.
