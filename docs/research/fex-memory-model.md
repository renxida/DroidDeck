# Cheaper x86-TSO emulation in FEX on Tensor G5

Branch `perf/fex-codegen`. FEX sources cited at upstream `main` @ `ab135df` (2026-11-11) unless a tag is
named; the Linux FEX this repo builds is `FEX-2610` (`tools/fex/build-in-arch.sh:3`).
Status marks: **VERIFIED** = read in source, in a binary, or measured offline here; **INFERRED** = reasoned,
not observed; **UNMEASURED** = needs the phone.

## 1. Question

Slay the Spire 2 (Godot 4 + .NET 9, x86-64 Windows build) runs on the Pixel 10 through Valve's ARM64
Proton with FEX loaded into Wine as the ARM64EC emulator (`libarm64ecfex.dll`). For a .NET game we run
FEX with every TSO knob on (`droiddeck-game-env:54-60`, `:106-108`). How can that ordering emulation be
made much cheaper without making a correct program incorrect, and what of it can this project ship?

## 2. Background

### 2.1 The models

- **x86-TSO** (Sewell, Sarkar, Owens, Zappa Nardelli, Myreen, *x86-TSO: a rigorous and usable programmer's
  model for x86 multiprocessors*, CACM 53(7), 2010): every core has a FIFO store buffer; the only
  reordering visible to software is a later load passing an earlier store to a different address.
  Locked RMWs and `MFENCE` drain the buffer.
- **Arm** (other-multicopy-atomic, weak): loads and stores may reorder freely unless ordered by
  dependencies, barriers (`DMB`), or acquire/release instructions. `LDAR`/`STLR` are RCsc (an `LDAR` may not
  pass an earlier `STLR`), FEAT_LRCPC's `LDAPR` is RCpc (it may), FEAT_LRCPC2 adds `LDAPUR`/`STLUR` with a
  signed 9-bit offset, FEAT_LRCPC3 adds SIMD&FP acquire/release (`LDAP1`/`STL1`, `LDAPUR q`) and pair forms.
- The sound and tight mapping of TSO onto Arm is therefore *load → `LDAPR`, store → `STLR`, RMW → LSE
  `…AL`*: acquire-RCpc on loads and release on stores give exactly load→load, load→store and store→store
  order while still letting a load bypass an earlier store, which is the one relaxation TSO allows.

### 2.2 Literature

| Work | What it does | Numbers |
|---|---|---|
| ArMOR, Lustig, Trippel, Pellauer, Martonosi, ISCA 2015 | Memory Ordering Specification Tables; derives the minimal fences when translating between models (e.g. x86 → Arm/Power) | Framework; shows the naive mapping over-fences |
| Lasagne, Rocha et al., PLDI 2022 | Static x86→Arm translator; lifts to LLVM IR, places fences, then removes fences on accesses whose pointer use-def chain reaches the stack, merges adjacent fences | Fence count −45.5 % on average, up to ≈ −65 % (abstract) |
| Risotto, Gouicem et al., ASPLOS 2023 | Verified TSO→Arm mapping inside QEMU TCG (`LDR; DMB LD` for loads, `DMB ST; STR` for stores, `CASAL` for CAS), fence merging, host-library linking | Fences are 48 % of runtime on average and up to 75 % (freqmine) under QEMU's mapping; the verified mapping is 6.7 % faster on average, up to 19.7 % (PARSEC/Phoenix, ThunderX2) |
| Khayet & Morrison, ASPLOS 2026 (*Arm weak memory consistency on Apple silicon: what is it good for?*) | Measures Apple's hardware TSO mode (the one Rosetta 2 uses) against Arm mode | Harmonic-mean slowdown of TSO mode 4.2 % over 49 apps (1.9 % after code fixes); outliers 19-70 % |
| Apple Rosetta 2 | Hardware TSO bit per thread on M-series; no barriers in translated code. Linux exposes it as `PR_SET_MEM_MODEL` (Asahi patches); FEX uses it when present (`Source/Windows/UnixLib/FEXUnixLib.cpp:29-57`) | Not available on any Cortex core |
| Microsoft Prism / XTA | x64 emulation on Windows on Arm; honours MSVC `/volatileMetadata` (instructions that need acquire/release, plus the code ranges where the table is complete) and offers per-app "multi-core settings": Fast (default), Strict, Very strict, Force single core (Microsoft Learn, *Program compatibility troubleshooter*) | Default Fast trusts the metadata |
| MSVC `/volatileMetadata` | Emitted by default for x86/x64 from VS 2019 16.10 until MSVC 14.51 (VS 2026 18.6), where it became opt-in (Microsoft Learn, `/volatile` reference) | — |
| FEX, *The scourge of x86 emulation* (fex-emu.com blog) | FEX's own measurements of acquire/release costs | Cortex-X4: `LDR` ≈ 21.3 GB/s, `LDAR` ≈ 18.1, `LDAPR` ≈ 23.1, `STLR` ≈ 21.4; unaligned TSO accesses cost ≈ 50 %; a split-lock atomic 209 ns vs 5.6 ns aligned; TSO stores to write-combined memory ≈ 0.03 GB/s vs 41.6 GB/s plain (X4) |

Two lessons carry over. First, the per-access mapping is already as tight as the hardware allows (FEX
uses `LDAPR`/`STLR`, which is better than Risotto's barrier mapping); the remaining wins are in *not
ordering accesses that cannot race* (Lasagne's stack analysis, Prism's volatile metadata) and in avoiding
the pathological cases (unaligned, split-lock, write-combined, vector barriers). Second, the ASPLOS 2026
result bounds what is possible: even hardware TSO costs ≈ 4 %, so anything that removes most barriers
from hot code lands within a few percent of the no-TSO speed.

## 3. Which code is emulated here

- **VERIFIED (repo):** Windows games run `droiddeck-game-env → droiddeck-proton → Valve ARM64 Proton →
  wine (arm64ec, FEX loaded in-process as a DLL)` (`docs/development/proot-performance.md:51-52`). The FEX
  files are `files/lib/wine/aarch64-windows/libarm64ecfex.dll` (+ `libwow64fex.dll` and the `aarch64-unix`
  helpers) inside the Proton (`app/src/main/java/com/droiddeck/launcher/session/ComponentsManager.kt:47-53`). They come with the Proton or, on Auto, from
  the `DD-FEXCore-*` nightlies of DroidDeck-Components (`ComponentsManager.kt:39`,
  `docs/development/game-environment.md:36-42`). This repo does **not** build the ARM64EC FEX; it builds
  only the Linux FEX for the x86-64 Steam client (`.github/workflows/build-fex.yml`,
  `tools/fex/build-in-arch.sh`).
- **INFERRED:** Wine's own DLLs and the ARM64EC DXVK/vkd3d builds run natively; FEX only translates x64
  code. So "turn TSO off for Wine and DXVK modules" is moot under ARM64EC: they never see FEX. The x64 code
  of StS2 is the Godot engine executable, `coreclr.dll`/`clrjit.dll`/`hostfxr.dll`/`hostpolicy.dll`, the
  ReadyToRun code in the framework assemblies, JIT-generated C# code, `steam_api64.dll`, and any x64 VC++
  runtime copies the game ships (`droiddeck-wine-pe:79-81` lets those load in an ARM64EC prefix).

## 4. How FEX emulates TSO (VERIFIED in source)

**Options** (`FEXCore/Source/Interface/Config/Config.json.in:605-663`): `TSOEnabled` (default on),
`VectorTSOEnabled` (off), `MemcpySetTSOEnabled` (off), `HalfBarrierTSOEnabled` (on),
`StrictInProcessSplitLocks` (off), `KernelUnalignedAtomicBackpatching`, `VolatileMetadata` (on), and
`ExtendedVolatileMetadata` (`:735-755`). With hardware TSO all emulation flags are cleared
(`Context.h:474-485`).

**Per-access decision.** `OpDispatchBuilder::IsTSOEnabled(Class)` (`OpcodeDispatcher.h:2580-2590`) returns
a forced value from volatile metadata if there is one, else the global GPR or vector flag. The
`_Load/_StoreMemAutoTSO` helpers (`:2592-2713`) emit `LoadMemTSO`/`StoreMemTSO` only when that is true and
the address is not flagged `NonTSO`. `NonTSO` is set for accesses whose base or index register is RSP
(`OpcodeDispatcher.h:1603-1605`, `OpcodeDispatcher.cpp:4440-4526`): the stack heuristic, a cheaper form
of Lasagne's stack analysis. TSO pairs are split (`ldp`/`stp` only when non-TSO, `:2655-2713`).

**Code generation** (`FEXCore/Source/Interface/Core/JIT/MemoryOps.cpp`):

| Access | LRCPC2 usable | LRCPC only | ARMv8.0 |
|---|---|---|---|
| GPR load (`LoadMemTSO`, `:768-830`) | `LDAPUR [Xn, #simm9]` + `nop` | `LDAPR [Xn]` + `nop` (offset needs an `ADD`) | `LDAR` + `nop` |
| GPR store (`StoreMemTSO`, `:1792-1838`) | `nop` + `STLUR` | `nop` + `STLR` | same |
| Vector load (`:831-852`) | `LDR q` then `DMB ISHLD` if *global* `VectorTSOEnabled` | same | same |
| Vector store (`:1839-1845`) | `DMB ISH` if *global* `VectorTSOEnabled`, then `STR q` | same | same |

The `nop` is the slot the alignment-fault handler backpatches to a half barrier (`DMB` + plain access) or a
plain access (`HalfBarrierTSOEnabled=0`) when an access turns out unaligned
(`Source/Windows/Common/TSOHandlerConfig.h:13-28`). `REP MOVS/STOS` become element loops of acquire/release
accesses when `MemcpySetTSOEnabled`, and then CPUID hides ERMS (`FEXCore/Source/Interface/Core/CPUID.cpp:695-699`). Vector TSO has no
LRCPC3 path (`Addressing.cpp:88-112`).

**LRCPC selection** (`Source/Common/HostFeatures.cpp`): features come from `ID_AA64ISAR1_EL1.LRCPC`
(`:240-242`, `:502-503`); `HandleErrata` (`:407-476`) clears `SupportsTSOImm9` (LRCPC2) if *any* core's
MIDR is Neoverse V2/V3/V3AE or Cortex-X3/X4/X925/C1-Ultra/C1-Premium, citing Arm erratum 3877900 (`:451`)
and LLVM PR 124274 (`:460`). The override
`FEX_HOSTFEATURES=disablelrcpc2|enablelrcpc2` is applied after the errata (`:604-605`, `:362-363`).
Under Wine the MIDRs come from the registry (`Source/Windows/Common/CPUFeatures.cpp:60-80`, `CP 4000`
per `CentralProcessor\N`), which Wine's ntdll fills from
`/sys/devices/system/cpu/cpuN/regs/identification/midr_el1`, writing nothing when that read fails (INFERRED from Wine's ntdll as read for this note; not checked against the exact Wine in Valve's ARM64 Proton). A missing value reads as 0, so the erratum check silently does not fire.

**Volatile metadata** (`Source/Windows/Common/ImageTracker.cpp:43-80`, `:180-201`): on every image map FEX
reads the PE load config's `VolatileMetadataPointer`, adds the listed accesses to `ForceTSOInstructions`
and the listed code ranges to `ForceTSOValidRanges`, plus any `FEX_EXTENDEDVOLATILEMETADATA` entries for
that module (`Source/Common/VolatileMetadata.{h,cpp}`). When a block is compiled
(`FEXCore/Source/Interface/Core/Core.cpp:582-588`, `:680-691`) a block that lies inside a valid range
*and contains at least one listed instruction* gets per-instruction treatment: listed → forced TSO,
everything else → forced non-TSO. A block inside a valid range with no listed instruction gets
`NoOverride`, i.e. the global setting. The `VolatileMetadata` option was only wired to the ARM64EC
loader on 2026-11-05 (`feee41a76`, after FEX-2611); before that the metadata was always applied.

| Feature | First tag |
|---|---|
| RSP-relative accesses non-TSO | ≤ FEX-2503 |
| ARM64EC loads MSVC volatile metadata (`51281f6a3`) | FEX-2504 |
| `VolatileMetadata` option | FEX-2506 |
| `ExtendedVolatileMetadata` (`f05057169`) | FEX-2509 |
| LDAPUR erratum rule (`HandleErrata`) | FEX-2512 |
| `FEX_VOLATILEMETADATA` actually honoured on ARM64EC | after FEX-2611 |

**Per-application config.** FEX layers Global/Main config, `AppConfig/<exe>.json`,
`AppConfig/Steam_<SteamAppId>_<exe>.json`, an optional `FEX_APP_CONFIG` file and finally the environment,
which wins (`Source/Common/Config.cpp:487-527`). On ARM64EC the config directory is under the prefix's
`%LOCALAPPDATA%` and the program name is the exe base name (`Source/Windows/ARM64EC/Module.cpp:759-764`).
Our Linux FEX writes only `Config.json` into `FEX_APP_CONFIG_LOCATION`
(`droiddeck-fex:395-402`, `:603-608`); nothing writes AppConfig files. Per-game policy therefore already
exists through `game-environment.json` and the presets (`FexPreset.kt`), whose `FEX_*` variables reach the
ARM64EC FEX through Wine's environment and override any AppConfig. FEX has **no per-thread TSO policy**;
the only per-code-region mechanism is (extended) volatile metadata.

## 5. Findings

**F1 (VERIFIED offline): .NET's native runtime carries complete volatile metadata.** Parsing the
`Microsoft.NETCore.App.Runtime.win-x64` 9.0.23 package (the version StS2 ships per
`docs/research/dotnet-under-relaxed-tso.md`): `coreclr.dll` lists 13,421 volatile accesses with ranges
covering 99.7 % of its code; `clrjit.dll` 106 accesses / 99.8 %; `clrgc.dll` 1,598 / 99.4 %;
`hostfxr.dll` 98.5 %; `hostpolicy.dll` 98.6 %. System.Private.CoreLib (ReadyToRun) has none, and JIT output
has none by construction.

**F2 (VERIFIED in source, static count offline): FEX applies that metadata to only ~8 % of coreclr's
ordered accesses.** Because of the block rule above, a block gets relaxed only if it also contains a
volatile access. A linear disassembly of `coreclr.dll` split into basic blocks gives 403,205 memory
instructions, 201,468 (50 %) RSP-based and therefore already non-TSO; of the remaining 201,737, the
current rule relaxes 15,613 (7.7 %), a whole-block rule would relax 191,254 (94.8 %). FEX's own option
text ("Default disables TSO in the module") and the module-only `ExtendedVolatileMetadata` form
(`VolatileMetadata.h`, `ModuleTSODisabled`) cannot work under the current rule: a module with no listed
instructions never relaxes. This is either a bug or a deliberately conservative choice; nothing in the
history says which.

**F3 (VERIFIED in source): forcing TSO on a vector access does not emit a barrier.** The JIT gates the
vector `DMB` on the global flag (`MemoryOps.cpp:848-851`, `:1840-1844`), not on the op being a TSO op. So
with `VectorTSOEnabled=0`, volatile metadata (MSVC or extended) cannot keep vector TSO for a narrow range
such as coreclr's `InlinedMemmoveGCRefsHelper`; it silently becomes a plain `LDR q`/`STR q`.

**F4 (VERIFIED offline): where the GC-reference SSE copies live in coreclr 9.0.23.** Using the `.pdata`
function table, the 32-byte `movups` copy loops sit in seven functions:
RVA `0x7448-0x77da`, `0x440b0-0x44351` (contains the `0x4417e` site), `0xf9460-0xf96ad`,
`0x188834-0x188933`, `0x194356-0x194597`, `0x2035e4-0x203993`, `0x2ab988-0x2ac010`. Listing every non-RSP
memory instruction in them takes ≈ 2.5 kB of `FEX_EXTENDEDVOLATILEMETADATA`, under the 8 kB value limit of
`apply_config` (`droiddeck-game-env:564`). Without symbols (the Microsoft symbol server is blocked here)
which of these are GC-reference copies rather than plain memmoves is INFERRED from shape.

**F5 (INFERRED, partly from press): Tensor G5 = 1× Cortex-X4 + 5× Cortex-A725 + 2× Cortex-A520.** Google did
not name the cores (Android Authority, *Google Tensor G5*); the leak it cites, and every benchmark
listing, give X4/A725/A520. All three are Armv9.2 cores with FEAT_LRCPC2 (mandatory since v8.4) and,
INFERRED, no FEAT_LRCPC3. Consequences:
- FEX upstream (≥ FEX-2512) will **not** use LDAPUR/STLUR on this phone because of the X4, so TSO loads are
  `LDAPR` (+ an `ADD` when there is a displacement) and stores `STLR`.
- That only holds if Wine could read the per-core MIDRs. Inside our proot, as an Android app, the sysfs
  read may be denied (UNMEASURED); then FEX sees MIDR 0, enables LDAPUR, and on the X4 every TSO load
  becomes a full RCsc acquire that cannot pass the preceding `STLR`s: precisely the store-buffer bypass TSO
  is allowed. FEX's chart puts `LDAR` at ≈ 78 % of `LDAPR` load bandwidth on the X4 before counting that
  store→load serialisation. The X4 is the core the game's main thread usually runs on.

**F6 (INFERRED): what TSO costs this game, in order of likely size.** (1) Vector barriers: a `DMB` per SSE
load/store; .NET's `Span`/`Buffer.Memmove`/vectorised string and collection code, and Godot's math, are
SSE-heavy (owned by the perf/fex-fast analysis). (2) Every non-stack GPR access of JIT code and the Godot
engine as `LDAPR`/`STLR`. (3) Element-wise `REP MOVS` when MemcpySetTSO is on. (4) Pathological cases:
unaligned TSO accesses (≈ 50 % each, plus a one-time fault and backpatch per site), split-lock atomics
(≈ 209 ns each on the X4, and on the stock Android kernel each is a SIGBUS round-trip through Wine's
exception dispatcher since FEX's kernel unaligned-atomic prctl is absent), and TSO stores into
write-combined GPU mappings (≈ 1,000× slower on the X4 per FEX's chart; Godot writes staging and uniform
buffers through mapped Vulkan memory from emulated code).

## 6. Interventions, ranked

| # | Intervention | Expected effect | Cost | Risk | State |
|---|---|---|---|---|---|
| 1 | Keep LDAPUR off on erratum cores even when Wine cannot read the MIDRs (`FEX_HOSTFEATURES=disablelrcpc2`) | 0 % if Wine already sees the X4; otherwise removes RCsc store→load serialisation on the X4 for every TSO load: INFERRED several % of main-thread time | done | none for correctness (codegen choice only, FEX's own policy) | **shipped** (§7.1) |
| 2 | Report, at each launch, which x64 modules carry volatile metadata and which FEX build runs | evidence for #3-#5 | done | none | **shipped** (§7.1) |
| 3 | Vector TSO only where needed: drop global VectorTSO, force it for the seven coreclr copy functions via `FEX_EXTENDEDVOLATILEMETADATA` | most of the vector-barrier cost (the largest TSO cost for .NET); Risotto/Lasagne-scale wins apply here: barriers are tens of % of runtime in barrier-heavy code | patch 0005 + an ARM64EC FEX build | needs F3 fixed, otherwise the forced ranges get no barrier | patch **written, compile-checked via build-fex.yml**; ARM64EC build not done |
| 4 | Whole-block volatile metadata (relax ~95 % instead of ~8 % of coreclr's non-stack accesses), opt-in `FEX_VOLATILEMETADATAWHOLEBLOCKS=1` | proportional to time in coreclr/clrgc (GC, type system, stubs); UNMEASURED share | patch 0006 + ARM64EC build | x64-only C++ in coreclr that relies on TSO without `volatile` (e.g. code paths that skip a barrier under `HOST_AMD64`) would become racy; Prism's default takes the same risk | patch **written**, off by default |
| 5 | Build our own ARM64EC FEX (llvm-mingw, `Data/CMake/toolchain_mingw.cmake`) with 0005/0006 and the STLUR-without-LDAPUR split (keep `STLUR` on erratum cores, only loads are affected by 3877900) and import it as a component | enables #3, #4; STLUR split saves an `ADD` per displaced TSO store | upstream builds it on self-hosted runners with a prebuilt llvm-mingw; we would need that toolchain in CI and a way to publish the package | toolchain download in CI | not done |
| 6 | Host-cached Vulkan memory for mapped buffers under FEX (prefer `HOST_CACHED|HOST_COHERENT` types; FEX's article recommends the same for Mesa) | removes the write-combine × TSO cliff for uploads | Vulkan layer work; owned by the layer workers | coherency if a cached type is not coherent | proposal |
| 7 | LRCPC3 vector acquire/release | — on Tensor G5 (no LRCPC3) | — | — | not applicable |
| 8 | Lasagne-style stack escape analysis beyond RSP (RBP frames, `lea` from RSP) | small; RSP already covers 50 % of coreclr's memory instructions | FEX frontend change | escaping stack addresses | not pursued |

Not useful here: per-module TSO-off for Wine/DXVK (native under ARM64EC); FEX AppConfig files (the
environment wins and our presets already use it); hardware TSO (none on Cortex).

## 7. What this branch changes

### 7.1 `droiddeck-fex-memory` (new), called from `droiddeck-game-env` for every Steam game launch

- Reads `/proc/cpuinfo` and the per-core `midr_el1` files exactly where Wine reads them, decides what FEX
  will emit for TSO loads, and if an erratum-3877900 core is present and the user has not set
  `FEX_HOSTFEATURES`, sets `FEX_HOSTFEATURES=disablelrcpc2`. Log line:
  `droiddeck-game-env: FEX memory model: Cortex-A520 x2, Cortex-A725 x5, Cortex-X4 x1; LRCPC yes, LRCPC2 yes,
  LRCPC3 no; TSO loads use LDAPR (LDAPUR has erratum 3877900 on Cortex-X4; FEX_HOSTFEATURES=disablelrcpc2);
  per-core MIDR readable N/8`
- Scans the game folder (two levels) for x64 PE modules and their volatile metadata. Log line:
  `droiddeck-game-env: FEX volatile metadata: K of M x64 modules (coreclr.dll 13421 accesses, 99.7% of code; …);
  none in SlayTheSpire2.exe, …`
- Reads the FEX version tag from the Proton's `libarm64ecfex.dll`. Log line:
  `droiddeck-game-env: FEX in this Proton: FEX-26xx…`
- Tests: `tools/tests/test_fex_memory.py` (synthetic PE images with load config and volatile tables,
  Tensor G5 cpuinfo, user override kept, non-Arm hosts silent).
- Staged into the rootfs by `SessionFiles.kt`.

The `DOTNET_FEX` values are untouched (owned by perf/fex-fast).

### 7.2 FEX patches (`tools/fex/patches/0005`, `0006`), applied to our FEX-2610 build

- `0005-forced-vector-tso-barriers.patch`: vector `LoadMemTSO`/`StoreMemTSO` always emit their half
  barrier. The dispatcher only creates those ops when vector TSO is on or volatile metadata forces TSO, so
  the default output is unchanged; with `VectorTSOEnabled=0` a forced range now really gets its barriers
  (fixes F3).
- `0006-volatile-metadata-whole-blocks.patch`: new option `VolatileMetadataWholeBlocks` (default off;
  `FEX_VOLATILEMETADATAWHOLEBLOCKS=1`) that treats every block inside a metadata range as covered (F2).

They change nothing by default. They take effect for games only once an ARM64EC FEX is built with them
(#5); the Linux build validates that they apply and compile.

## 8. On-device experiment plan

All runs: StS2, same save, 3 minutes of a fixed combat, Session logs on. Frame-time telemetry is from the
telemetry worker's HUD/upload; compare median and 99th-percentile frame time.

| Experiment | How | Confirms / refutes |
|---|---|---|
| E1 MIDR visibility | First launch on this branch: read `per-core MIDR readable N/8` | N = 8: Wine sees the X4, FEX already avoids LDAPUR, #1 is a no-op. N < 8: #1 is active; FEX would otherwise have used LDAPUR on the X4 |
| E2 LDAPUR cost on the X4 | If N < 8: two runs, game env `FEX_HOSTFEATURES` = `disablelrcpc2` vs `enablelrcpc2` | Frame-time delta is the erratum cost; FEX's choice is confirmed if `disablelrcpc2` is not slower |
| E3 Metadata coverage | Read the `FEX volatile metadata:` line | Confirms coreclr/clrjit/clrgc coverage on the real install and whether `SlayTheSpire2.exe` (Godot, likely MinGW-built) has none |
| E4 FEX version | Read `FEX in this Proton:` | ≥ FEX-2512 has the erratum rule; ≥ FEX-2509 has extended metadata; after FEX-2611 `FEX_VOLATILEMETADATA=0` works |
| E5 Is coreclr hot? | One run with `FEX_VOLATILEMETADATA=0` (needs a FEX after FEX-2611) vs default | Delta bounds what #4 can give (current rule relaxes ~8 % of coreclr's accesses) |
| E6 FEX's own view | One run with `FEX_SILENTLOG=0`: Wine debug output gets `Load module coreclr.dll …` and `Loaded volatile metadata for … : 13421 entries` | Confirms the metadata reaches FEX |
| E7 Vector TSO per range (#3) | With an ARM64EC FEX carrying 0005: `FEX_VECTORTSOENABLED=0` plus the coreclr ranges vs full TSO; run the GC stress the dotnet analysis proposes | Speedup with no GC-heap corruption (`DOTNET_GCStress`-style checks or long play without crash) |
| E8 Whole-block metadata (#4) | With 0006: `FEX_VOLATILEMETADATAWHOLEBLOCKS=1` | Frame time; any crash or hang is a TSO-dependent spot in coreclr |

## 9. Risks and open questions

- The Tensor G5 core list is not from Google; the cpuinfo log settles it.
- §5 F2/F4 counts come from a linear disassembly; FEX's block boundaries differ slightly (it may not split
  at calls), so the percentages are estimates.
- Patch 0005 also adds the vector barrier for forced ranges on hosts with hardware TSO; irrelevant on
  Android.
- Nothing here was run on a phone.

## Sources

- Sewell et al., x86-TSO, CACM 53(7), 2010.
- Lustig et al., ArMOR, ISCA 2015.
- Rocha et al., Lasagne, PLDI 2022: research.tudelft.nl/en/publications/lasagne-a-static-binary-translator-for-weak-memory-model-architec
- Gouicem et al., Risotto, ASPLOS 2023: research.tudelft.nl/files/144252582/3567955.3567962.pdf
- Khayet & Morrison, ASPLOS 2026: www.cs.tau.ac.il/~mad/publications/asplos2026-TSO.pdf
- FEX, The scourge of x86 emulation: fex-emu.com/Scourge-of-emulation/
- Microsoft Learn: /volatileMetadata reference; Windows on Arm program compatibility troubleshooter.
- Android Authority, Google Tensor G5.
- FEX-Emu/FEX @ ab135df and FEX-2610 (paths above).
