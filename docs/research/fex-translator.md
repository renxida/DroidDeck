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
- **Rewritten-code tracking (SMC) is no longer worth fixing.** Off (unsafe, an upper bound) it saved ~14% with the
  disk cache alone, but **nothing on top of `fex-l1`** (788 vs 797 ms, round 3). Smaller blocks (MaxInst 500/1500) and
  caching more code (relocation filter off) don't help either.
- **Memory: a 4 MB table keeps the gain.** The full table costs ~40 MB more RSS in the bench. Kestrel's FEX now takes
  a size (patch 0004, `FEX_L1CACHEENTRIES`): 256k entries (4 MB per thread) ran 840 ms vs 833 for the full table and
  1,030 without, for +15 MB instead of +39 MB. `fex-l1` uses that size; on other FEX builds it gets the full table.
  A dynamic table that never shrinks grows too slowly to help (round 4).
- What's left is translated-code quality: with `fex-l1` and a warm cache FEX spends only 10% of wall time in its
  compile path; dispatch is 2.5× native, strings 2.3×, allocation/GC 2.0×, first calls 4.1×.
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

## Round 3: what's left on top of `fex-l1`

Run 43368666336 (three rounds, best per phase; all configs reproduced native's checksum):

| Config | Total ms | × native | first_jit | JIT share | max RSS MB |
|---|---|---|---|---|---|
| native | 416 | 1.00 | 42 | | |
| fex_game | 986 | 2.37 | 394 | 39% | 195–200 |
| fex_game_dc | 915 | 2.20 | 300 | 20% | 239–263 |
| **fex_game_dc_l1** | **788** | **1.89** | **171** | **10%** | **296–300** |
| fex_l1_nosmc | 797 | 1.92 | 158 | 20%* | 269–291 |
| fex_l1_mi500 | 837 | 2.01 | 203 | 19%* | 281–309 |
| fex_l1_mi1500 | 806 | 1.94 | 181 | 20%* | 277–300 |
| fex_l1_noreloc | 820 | 1.97 | 186 | 9% | 300–304 |

\* these change code generation, so round 1 starts with a cold disk cache and the mean JIT share is higher.

Why the dynamic L1 hurts in a long session too: FEX samples once a second; above 250 slow lookups/s it doubles
the table, below 50/s it halves it, and each change flushes the table. A thread whose table is big enough to have
few misses gets shrunk, misses again, and regrows, so the default never settles.

## Rounds 4 and 5: the memory cost of a bigger table

Warm disk cache, max RSS of the bench process (rounds 2–3), total ms best of three:

| Config | L1 per thread | Total ms | max RSS MB | vs default |
|---|---|---|---|---|
| fex_game_dc (default, dynamic from 8k) | 128 KB+ | 1,030 | 256–258 | |
| fex_l1grow (dynamic, never shrinks) | grows | 1,030 | 256–257 | no gain |
| fex_l1grow20 (same, grows at 20 misses/s) | grows | 1,013 | 257–258 | no gain |
| fex_l1_64k (patch 0004) | 1 MB | 874 | 261–262 | −15%, +4 MB |
| **fex_l1_256k (patch 0004)** | **4 MB** | **840** | **272–273** | **−18%, +15 MB** |
| fex_game_dc_l1 (full) | 16 MB | 833 | 296 | −19%, +39 MB |

Runs 43369383459 (round 4) and 43370044898 (round 5). Every config reproduced native's checksum.

## Where translated time goes, by guest library

Runs 43371497701 and 43372146309 (`fex_l1_256k`, disk cache off so every block is named; `FEX_LIBRARYJITNAMING=1`
and `FEX_BLOCKJITNAMING=1`; `tools/fex/bench/hotblocks.py` disassembles the hottest blocks). Share of all samples:

| | run 1 | run 2 |
|---|---|---|
| translated code, total | 56% | 60% |
| jitted C# (anonymous blocks) | 17% | 33% |
| libcoreclr.so (GC, helpers, type system) | 15% | 14% |
| libclrjit.so (RyuJIT compiling methods) | 11% | 10% |
| libc.so.6 (malloc, memmove) | 15% | 2% |
| FEX's dispatcher | 0.05% | 0.05% |

- No single runtime function dominates: the hottest coreclr block is 2% and RyuJIT's are under 1% each. The
  big unnamed hot spots are the bench's own C# loops.
- The dispatcher is negligible, so the 2.3–2.5× on virtual calls is in the translated call sequences themselves,
  not in FEX's lookup.
- libc swings between runs (one malloc block took 14.5% in run 1). On the phone this cost doesn't exist in the same
  form: under ARM64EC the C runtime and ntdll are Wine's native arm64 DLLs, so `malloc`/`memcpy` run natively. The
  Linux bench overstates it.

So off-device there is no cheap library-level target left in FEX. The next measurements need the phone: how often
the disk cache hits there, and `fex.csv` with `fex-l1` on vs off.

## Every approach on one frame loop (rounds 6–8)

Cedar's ask: pre-translation with caching, and a list of translator ideas, each benchmarked alone and stacked
against native. `cache-bench.sh` runs a game-like frame loop (`Bench.dll 3000 frames`): steady per-frame work in
small methods called every frame, and every 50th frame new content loads (20 game methods called for the first
time, every 100th frame 10 new expression lambdas). Each config runs cold (empty FEX code cache), then 3 warm runs,
twice. Steady p50/p99 are over ordinary frames; load frames are the ones where new code first runs. Run
43394349378 (arm64 Neoverse runner, warm medians):

| config | steady p50 ms | steady p99 | load frame ms (worst) | first frame, cold | cache hit |
|---|---|---|---|---|---|
| native, JIT | 0.39 (1.00×) | 0.67 | 1.5 (2.1) | 116 | – |
| native, ReadyToRun | 0.41 (1.06×) | 0.69 | 0.8 (1.6) | 50 | – |
| FEX, no code cache | 0.96 (2.49×) | 1.54 | 10.3 (27.3) | 1,107 | 0% |
| FEX, file-backed cache only | 0.82 (2.13×) | 1.29 | 10.1 (11.5) | 818 | 51% |
| **FEX, stock cache (files + content-keyed JIT'd code)** — the game today | 0.82 (2.13×) | 1.31 | 9.3 (10.2) | 799 | 97% |
| + ReadyToRun x64 | 0.87 (2.26×) | 1.31 | **1.7** (9.4) | 567 | 97% |
| + ReadyToRun, tiering off | 0.84 (2.18×) | 1.13 | **1.5** (6.6) | **494** | 97% |
| + framework precompiled code off too | 0.96 (2.49×) | 1.58 | 8.1 (12.8) | 1,184 | 96% |
| + memory ordering off (unsafe ceiling) | 0.80 (2.08×) | 1.24 | 8.7 | 800 | 97% |
| block by block (region formation off) | 1.71 (4.44×) | 2.46 | 14.9 | 726 | 99% |
| + transparent huge pages | 0.82 (2.13×) | 1.29 | 9.2 | 792 | 97% |
| ReadyToRun + ordering off + huge pages | 1.01 | 1.50 | 1.9 | 429 | 97% |

All configs printed the same checksum. Under FEX, both `Bench.dll`'s ReadyToRun code and CoreLib are file-backed
`r-xp` mappings, so FEX keys them by (file, offset).

What it says:

- **Content-keyed pre-translation already exists.** FEX's disk cache keys anonymous (JIT'd) code by a hash of its
  guest bytes with embedded data masked (`DiskCacheAnonCaching`, on with the cache). Warm, 97% of blocks come from
  it. It takes first run from 1.1 s to 0.8 s and steady frames from 2.5× to 2.1× native, and it is what the game uses.
  A better-normalized key could win at most the last 3%.
- **ReadyToRun is the big lever for hitches.** New-content frames drop from 9.3 ms to 1.5–1.7 ms, about native, and
  first run from 0.8 s to 0.5 s, because the .NET JIT (itself translated x86 code) no longer runs for that code. The
  .NET workstream owns the game version (experiment `dotnet-r2r`). Steady frames cost 2–6% more with it under FEX.
- **Memory-ordering relaxation: ceiling 2–4%.** The game's settings already drop most barriers; removing all of them
  (unsafe) gains 0.02 ms a frame. Escape analysis or per-access profiling can't beat that, so it is dropped.
- **Huge pages: nothing** (0.82 either way), dropped.
- **Region formation already pays 2×**: FEX's multiblock translation is on; block by block is 4.4× native. Earlier
  rounds found no gain from bigger or smaller regions (MaxInst 500/1500).
- **Library thunking (CPU side): under 0.5%** of samples are in libc in the frame-loop profile, and on the phone the
  C runtime and ntdll are already native ARM64EC Wine DLLs. The Vulkan/D3D side belongs to the graphics workstream.
- The steady gap left (2.1×) is translated-code quality spread across jitted C#, coreclr and RyuJIT. The next step
  measures what the hottest translated blocks are made of (flag keeping, ordering, state-block spills, vector code)
  to size cross-block flag liveness and SSE-to-NEON work, and to pick blocks for hand-tuning.

### What the hottest translated code is made of (round 9)

`blockmix.py` joins a FEX built with the ARM disassembler (`FEX_DISASSEMBLE=blocks,stats`, each block's guest and
host address logged) with `perf script` sample addresses, and charges each sample to the ARM instruction it hit. Same
frame loop, JIT build with tiering off (so per-thread log lines don't interleave), disk cache off. Run 43404512287:
3.64 ARM instructions per x86 instruction in the hot blocks; time in translated code by kind:

| kind | share of time in translated code |
|---|---|
| ordinary ALU and moves | 49.8% |
| **indirect-branch lookup** (inline per-thread table probe, return-stack check) | **19.1%** |
| ordinary loads and stores | 7.5% |
| memory ordering (`ldapur`/`stlur` and their padding `nop`s) | 5.5% |
| FEX state block (`[x28]`: spills, RIP, context) | 5.2% |
| direct branches | 3.7% |
| building 64-bit constants (`movk`) | 3.5% |
| flag-setting compares/ALU | 3.3% |
| flag moves (`nzcv`, `rmif`, `setf`) | 0.3% |
| vector/NEON | 0.6% |

- **Flags (idea 3): 3.6% total**, and most of that is the compare the x86 code asked for. Cross-block flag
  elimination can win well under that: dropped.
- **SSE→NEON (idea 5): 0.6%** for jitted .NET code: dropped for this workload (worth a look only if the phone
  profile shows the game's native SIMD libraries hot).
- **Indirect-branch lookups are the one big item.** The hottest block alone (6.7% of all samples) is .NET's
  interface-dispatch resolve stub: a hash probe of its own cache, then `jmp rax`, which FEX turns into a probe of its
  per-thread table (`ldp [x28,#160]; and; add; ldp; sub; cbz`) before `br`. Two dependent loads per indirect jump
  or call. Candidate fix: a per-site inline cache (compare the target against the last one seen, branch directly),
  registered with FEX's block delinker so invalidation stays correct. Expected ceiling: a few % to ~10% of steady
  frame time, depending on how monomorphic the sites are; it needs a prototype to know.
- Ordering costs 5.5% of time with the game's settings, matching the 2–4% measured with TSO fully off.

The .NET workstream's composite ReadyToRun (framework compiled with the game) with tiering off is the best row on
the same harness so far: first run 404 ms vs 771 stock, warm total −13%, load frames 1.5 ms, steady p50 2.08×.

### A per-site cache for indirect jumps and calls (round 10): no gain

Built to go after the 19%: each indirect jump or call site gets a 16-byte `{host, guest}` record after its block,
loaded with one `ldp` and compared before the per-thread table probe; an empty record is filled once through FEX's
exit linker and emptied again by a block-link delinker on invalidation (calls push their return-stack entry first so
the fill path still returns correctly). It compiled for ARM64EC and every result matched. Run 43405871544:

| config | steady p50 | p99 | dispatch phase |
|---|---|---|---|
| stock cache | 0.82 ms (2.10×) | 1.28 | 2.4× (fex-l1) |
| + site cache | 0.82 ms (2.10×) | 1.27 | 2.5× |
| ReadyToRun, tiering off | 0.84 ms (2.15×) | 1.13 | |
| + site cache | 0.90 ms (2.31×) | 1.19 | |

So the lookup instructions weren't the cost: their samples were mostly the stall of the loads that produce the
jump target (sample skid puts a load's stall on the instructions after it), and the hottest site, .NET's
interface-dispatch stub, jumps to many targets. The patch is kept at `docs/research/patches/fex-indirect-branch-cache.patch`
and is not in the game's build.

### A larger first code buffer (round 11): a clean win

`code_buffer_full` was 1 in every run: FEX's shared code buffer starts at 16 MB, and when it fills it throws away
every thread's translated code and starts over in a buffer twice the size (up to 128 MB). Patch 0006 adds
`InitialCodeBufferMB` (default 0 = stock 16 MB). Run 43530571013, same harness, checksums unchanged:

| config | warm p50 | load frame ms | first run ms | warm total ms | translated ms cold / warm | code buffer full | RSS MB |
|---|---|---|---|---|---|---|---|
| b_content | 0.82 | 8.9 | 799 | 3357 | 840 / 457 | 1 | 236 |
| b_content_cb64 | 0.82 | 6.7 | 680 (−15%) | 3232 (−3.7%) | 714 / 331 (−28%) | 0 | 220 |
| d_r2r_tc0 | 0.84 | 1.4 | 493 | 3091 | 822 / 445 | 1 | 226 |
| d_r2r_tc0_cb64 | 0.84 | 1.5 | 449 (−9%) | 2948 (−4.6%) | 680 / 308 (−31%) | 0 | 211 |

Phases harness (with `fex-l1`): total 918 → 876 ms (2.13× → 2.03× native), first JIT 272 → 228 ms, JIT 524 → 391 ms.
Steady frames don't move (the code is the same once translated); the gain is all retranslation that no longer
happens, so it lands on loads and new content. Memory goes down, not up: the 64 MB is reserved and touched as
used, and the stock path ends up holding the old 16 MB plus a fresh 32 MB. A real game translates more code than
the bench, so the phone may still fill 64 MB once; `code_buffer_full` in `fex.csv` will say.

App: experiment `fex-code-buffer` on `perf/emu-fex` sets `FEX_INITIALCODEBUFFERMB=64`; off by default.

### Where the game's own threads spend their time: fex-sample (round 12)

The counters say how much time goes to translation, SMC and softfloat, but not which guest code is hot on the
phone. Patch 0007 adds `SampleHz` (experiment `fex-sample`, `FEX_SAMPLEHZ=250`, off by default):

- Each emulated thread gets a `CLOCK_THREAD_CPUTIME_ID` timer that sends it SIGPROF, so a thread is sampled in
  proportion to the CPU it actually uses. The unixlib handler stores the interrupted PC and the thread's
  `InlineJITBlockHeader` and `rip` in a lock-free ring. It makes no system call.
- A FEX-internal thread drains the ring every 100 ms. When the PC is inside the current block,
  `Context::DecodeSampledRIP` walks the block's RIP entries to the exact guest instruction. Otherwise the sample is
  `fex` (FEX itself), `native` (an ARM64EC or Wine DLL), `jit-other` or `host`, with the guest location of the
  block the thread last entered.
- Addresses are named by module, offset and nearest export, read from the PE export table. There is no PDB lookup.
- Once a minute it appends to `/dev/shm/fex-<pid>-samples.csv`:
  - the 8 busiest threads;
  - the busiest thread's top 200 places;
  - the other threads' top 50;
  - its own cost.
- Every x87 softfloat fallback is counted, not sampled. A hook in FEXCore's F80 fallbacks puts the calling JIT
  address in the same ring. Each minute adds `x87` rows (the top 50 guest instructions that needed a fallback) and
  `x87-module` rows (fallbacks per guest module). This answers whether the game's `float_fallback` comes from Spine,
  the exe, the CRT or .NET in one play session. The bench checks the hook natively (`FEX_TEST_X87SITES`, bench-only
  patch): every site it reports must be an x87 instruction in `x87cost`.
- The app copies these rows into `fex-samples.csv` in the telemetry zip.
- Posix CPU timers fire on the kernel tick, so a thread gets at most `CONFIG_HZ` samples per second whatever rate
  is asked. The CI runner delivered the full 1000/s; an x86 dev box capped at 250. 250 stays the setting.
- Cost, measured on the CI arm64 runner with the same handler (`tools/fex/bench/sampler`, 4 busy threads, median
  of 5):

  | asked Hz | work time vs off | samples/s/thread | handler ns/sample |
  |---|---|---|---|
  | 250 | -0.19% (noise) | 246 | 125 |
  | 500 | +0.36% | 490 | 124 |
  | 1000 | +0.42% | 980 | 100 |

  At 250 Hz the handler is about 31 µs per second of thread time (0.003%). Signal delivery is in the work-time
  column, which is within run-to-run noise. The reader thread costs about 30 µs a second. That is far under the 2%
  limit.

Memory: a 2.5 MB ring, plus the decode cache, which is cleared every minute.

### What one x87 fallback costs (round 12): too little to matter

`float_fallback` counts FEX's softfloat calls. At full x87 precision, which .NET games run with, every x87
arithmetic instruction is one call. `tools/fex/bench/x87/x87cost.c` times them under FEX on the CI arm64 runner
(ns per x87 instruction, warm, median of 3):

| loop | full precision | reduced precision | native SSE2 mul+add |
|---|---|---|---|
| `fprem` loop (fmod as the x64 CRT does it) | 10–11 | 1.8–5.5 | 2.9 per op |
| `fld`/`fadd`/`fmul`/`fstp` | 15.5 | 0.7 | |
| `fsin` | 111 | 2.4 | |

The game's worst minute had about 15.7k fallbacks per second. Even if every one were an `fsin`, that is 1.7 ms per
second, 0.17% of one core. A phone core at half this runner's speed would make it about 0.35%. More likely it is
0.02–0.05%. x87 is not why the later fights slow down, and reduced precision would buy almost nothing. The fex-sample
`x87-module` rows will still say which module issues them, which is cheap to know.

## FEX's own memory (round 13)

The phone's lag tracks memory pressure (hub, a7526bd row 5: 123 to 142 frames over 50 ms a minute while swap was full,
then 10 to 19), so memory comes before CPU. `tools/fex/bench/mem` runs the frame loop (3000 frames) and the 4-thread
contended workload under FEX and samples `/proc/<pid>/smaps` every 0.5 s. FEX names its own mappings, so its share is
exact. MB resident at FEX's peak (run 43568284554):

| workload, config | threads | code buffers | FEX malloc | L2 lookup | L1 lookup | all FEX | guest anon | total time |
|---|---|---|---|---|---|---|---|---|
| frames, stock dynamic L1 | 7 | 30.9 | 19.9 | 0.0 | 3.5 | 62.7 | 81.5 | 4812 ms |
| frames, fex-l1 (fixed 4 MB) | 7 | 31.2 | 18.2 | 0.0 | 10.9 | 67.8 | 81.7 | 3900 ms |
| frames, fex-l1 + 64 MB first code buffer | 7 | 19.5 | 14.6 | 0.0 | 7.2 | 47.7 | 81.3 | 3460 ms |
| frames, fixed 1 MB L1, L2 off | 7 | 30.9 | 17.8 | 0.0 | 4.1 | 60.6 | 81.5 | 4840 ms |
| 4 threads, stock dynamic L1 | 13 | 15.5 | 13.0 | 0.0 | 1.6 | 37.5 | 5.6 | 14.5 ms |
| 4 threads, fex-l1 | 14 | 18.0 | 15.9 | 0.0 | 15.9 | 60.0 | 5.9 | 12.1 ms |

- The lookup tables are per thread. L2 (64 KB of entries per guest code page a thread runs, up to 128 MB) stayed at 0 MB
  resident: it only fills on an L1 miss that hits the shared map, and turning it off changed neither memory nor time.
- fex-l1's fixed 4 MB L1 costs what a thread touches: up to 4 MB on a busy thread, 0.4 to 1 MB on the median one
  (+14 MB over 14 threads). A fixed 1 MB L1 loses all of fex-l1's speed, so the 4 MB is what buys it.
- The 64 MB first code buffer *lowers* memory (−12 MB code, −4 MB FEX malloc): without it the first buffer fills, and
  the old and new buffers live side by side until every thread moves over. It only reserves address space up front.
- On this stand-in FEX is 48 to 68 MB of a 165 to 186 MB process. The game's 2.5 GB is mostly elsewhere: fex-smaps
  (smaps.csv) and patch 0008 (NameWindowsMemory) are there to say where on the phone.

## Ranked next fixes (in FEX)

0. **ReadyToRun for the game's assemblies** (.NET workstream, `dotnet-r2r`): new-content hitches 9.3 → 1.5 ms and
   first run −40% on the frame loop. The largest measured win for hitches.
1. **Ship `fex-l1`** (4 MB table with Kestrel's FEX) after the phone confirms lower `cache_miss` and `jit_ms` in
   `fex.csv` with no memory regression in `memory.csv` / `processes.csv`.
1b. **Ship `fex-code-buffer`** (64 MB first buffer) if the phone's `fex.csv` shows `code_buffer_full` drop and
   `jit_ms` fall, with no memory regression. Stacks with `fex-l1`.
2. **Make sure the disk cache actually hits on the phone** (`disk_hit` / `disk_miss` in `fex.csv`).
3. **Translated-code quality**, now most of the remaining 1.9×. Spread across jitted C#, coreclr and RyuJIT with no
   single hot function (profile above), so it is general codegen work in FEX, not a targeted fix.
4. SMC, block size and the relocation filter: measured, no gain; dropped. Memory-ordering relaxation (ceiling 2–4%),
   huge pages (0%) and CPU-library thunking (<0.5%): measured, dropped.

## What changed on the branch

- `tools/fex/arm64ec/`: our own build of the game's FEX — upstream FEX `ab135df` plus three patches:
  - 0001: vector TSO half barrier follows the op, so forced-TSO vector ranges keep their ordering.
  - 0002: `VolatileMetadataWholeBlocks` (default off).
  - 0003: keep `STLUR` on cores whose `LDAPUR` has erratum 3877900 (Cortex-X4 is one; Tensor G5 has one).
  - 0004: `L1CacheEntries` (`FEX_L1CACHEENTRIES`), a chosen starting or fixed size for the per-thread lookup table.
  - 0005: counts each time a thread's code buffer fills and all its translated code is thrown away (stats column
    `code_buffer_full` in `fex-stats` and `fex.csv`; the struct's padding slot, so the layout is unchanged).
- `.github/workflows/build-fex-arm64ec.yml`: builds it and publishes `Kestrel-FEXCore-<date>.<run>-<upstream>-<patch set>.wcp`
  to the `kestrel-fex` prerelease. First: `Kestrel-FEXCore-20261113.3-ab135df09-664c5b38.wcp` (1.5 MB).
- Experiment `fex-kestrel` (off by default): installs the newest of those through the Auto component line, with the
  usual rollback after a failed boot.
- Experiment `fex-stats` (on by default): sets `FEX_PROFILESTATS=1` for the game, and the app writes FEX's counters for
  every emulated process to `fex.csv` in the session folder every 5 s (`FexStatsLog.kt`), included in the telemetry
  bundle. Columns: `t_wall_ms, interval_ms, pid, process, threads, jit_ms, jit_blocks, smc, sigbus, signal_ms,
  float_fallback, cache_miss, cache_rlock_ms, cache_wlock_ms, disk_hit, disk_miss, disk_lookup_ms, code_buffer_full`; every value is
  the change over the interval, so `jit_ms / interval_ms` is the share of one core spent translating.
- Experiment `fex-l1` (off by default): sets `FEX_DYNAMICL1CACHE=0 FEX_L1CACHEENTRIES=262144` for games, so FEX's
  per-thread block lookup table is a fixed 4 MB with Kestrel's FEX (16 MB with other builds).
- `.github/workflows/fex-bench.yml` and `tools/fex/bench/`: the off-device bench above, plus `profile.sh` (perf),
  `cache-bench.sh` (the frame-loop table: `cache-configs.txt`, `frames_summary.py`, `gen_cards.py`) and
  `blockmix.py` (instruction mix of the hottest blocks, from a FEX built with the ARM disassembler).

## Notes

- `fex_game_ldapr` (`FEX_HOSTFEATURES=disablelrcpc2`) segfaults on Linux FEX at this commit before the program
  starts. It's a host-feature override, not something the game uses; recorded, not chased.
- The bench's artifacts can't be downloaded from here, so `run.sh` echoes every run into the job log.

## Idle threads hold old code and lookup memory (round 14)

The first phone smaps pass (stock FEX-2611-45-gab135df, Slay the Spire 2) put FEX's own memory at about 465 of the
game's 2,369 MB (resident + swap): code buffers 30 + 201 MB, per-thread L1 16 + 111, FEX malloc 16 + 66, block links
2 + 20. The rest (1,861 MB) is unlabeled anon until patch 0008 runs.

Why so much, when the off-device bench showed 48-68 MB:

- Code: 231 MB is more than the 128 MB maximum buffer, so older buffers are still alive. Buffers grow 16, 32, 64, 128 MB;
  a thread switches to the newest only when it next compiles (`AllocateCodeBufferInSharedCache`). Threads parked in waits
  never compile again and keep their buffer, so the 112 MB of old buffers stay, cold and in swap. The bench shows the
  same thing at small scale: stock frames held the first 16 MB buffer after moving to 32 (30.9 MB vs 19.5 with a 64 MB start).
- L1: a dynamic L1 only shrinks in `UpdateDynamicL1Stats`, which runs on the thread's own lookups. A thread that grew
  to 16 MB and then went idle keeps it. 111 of 127 MB in swap means most of it belongs to idle threads.

Patch 0009 (`TrimIdleThreads`, experiment fex-trim at 30 s): a FEX thread checks every 30 s for threads whose CPU time
did not change since the last check. Each is suspended the way upstream's `ThreadTerm` does it (NtSuspendThread then
NtGetContextThread: the thread is outside the JIT and holds no JIT locks), checked for `InSimulation == 0`, and with the
code invalidation mutex and its lookup map's write lock taken without waiting, its L1, L2, page map and call-return
stack are decommitted and it is pointed at the newest code buffer. Nothing is allocated or freed while it is suspended;
old buffers are dropped after it resumes, and the emptied ranges are renamed so smaps still files them under FEX.

Expected on the phone: up to 112 MB of old code buffers, most of the 111 MB of swapped L1, and the old buffers' block
links and block maps, about 240 MB, almost all of it swap. Cost: a woken thread refills its L1 from the shared block
map, and a thread that was moved retranslates what it runs (it would have on its next compile anyway). A pass suspends
only threads newly idle, one at a time; each is trimmed once until it runs again.

Not testable off-device: the bench runs FEX on Linux, where a guest syscall returns into JIT code, so a thread blocked
in one cannot be moved. Under ARM64EC, syscalls and calls to native code leave the JIT without a host return address
(`JumpSetStack`, `ExitFunctionEC`), which is what makes this safe there. Check on the phone: the log line
`TrimIdleThreads: N threads, M newly idle, T trimmed, S skipped, K moved, B old code buffers freed`, and smaps.csv
fex-code and fex-lookup-l1 falling after the first minute.
