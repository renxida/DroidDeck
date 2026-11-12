# .NET under relaxed TSO: which FEX ordering switches Slay the Spire 2 needs

Question: the launcher runs .NET games with full FEX TSO (`TSO`, `VectorTSO`, `MemcpySetTSO`, `HalfBarrierTSO` all on), added upstream "for Slay the Spire 2" with no recorded reason. Which of these does the game's runtime actually rely on, and what can be turned off for speed?

Sources read: FEX main @ab135df; dotnet/runtime release/9.0 @4647bc6; Godot 4.5-stable; disassembly of `coreclr.dll` from Microsoft.NETCore.App.Runtime.win-x64 9.0.23 (NuGet). VERIFIED = read in source or binary; INFERRED = reasoned, not observed.

## The game's runtime
.NET 9 (INFERRED, confident in the major version): the StS2 modding docs target `net9.0` and name the engine "MegaDot", a custom Godot 4.5.1. Confirm on device from `coreclr.dll`'s file version or the game's `.runtimeconfig.json`.

## What each FEX switch emits (VERIFIED)
- `TSOEnabled`: integer loads/stores become `ldapur`/`stlur` with LRCPC2 (`ldapr`/`stlr` with LRCPC, `ldar`/`stlr` otherwise). RSP-relative stack accesses stay plain. Non-temporal stores (MOVNT*) are never ordered. `FEXCore/Source/Interface/Core/JIT/MemoryOps.cpp:767-830,1790-1838`; `OpcodeDispatcher.h:1603`.
- `VectorTSOEnabled` (FEX default off): `dmb ishld` after every SSE/AVX/x87 load, full `dmb ish` before every vector store; AVX-256 and pairs lose `ldp`/`stp` and split into barriered 128-bit ops; no immediate-offset addressing (FEX has no LRCPC3 support). `MemoryOps.cpp:848-851,1841-1844`; `OpcodeDispatcher.h:2580-2713`; `Addressing.cpp:88-112`.
- `MemcpySetTSOEnabled` (FEX default off): `rep movs/stos` become an element-at-a-time `ldapr`/`stlr` loop instead of a `stp q` (or MOPS) loop, and CPUID hides ERMS, which pushes the game's CRT memcpy/memset onto AVX loops, where VectorTSO adds a full barrier per 16-byte half-store. `OpcodeDispatcher.cpp:3299-3400`; `CPUID.cpp:694-699`.
- `HalfBarrierTSOEnabled` (default on): unaligned ordered accesses that cross 16 bytes get `ldr; dmb ishld` / `dmb ish; str` instead of becoming unordered. Keep on.
- `ExtendedVolatileMetadata` could force TSO on only risky instructions, but applies only to file-backed mappings (`SyscallsSMCTracking.cpp:721-734`); Wine most likely copies `coreclr.dll`'s `.text` (INFERRED), so it would not take effect.

With vector and memcpy TSO off, all integer accesses are still acquire/release and all `lock` ops and `mfence` stay ordered. What is lost: a SIMD or string store that is itself the publishing write can become visible early, and a SIMD load can be satisfied after a later load.

Pixel 10 (INFERRED): Tensor G5 is 1x Cortex-X4, 5x A725, 2x A520, Armv9.2, so LRCPC, LRCPC2 and LSE2 are present. Integer TSO is cheap there; vector TSO (a DMB per SIMD access) is the expensive part. Check `/proc/cpuinfo` Features for `lrcpc ilrcpc`.

## Where x64 .NET relies on SIMD or string-instruction ordering
1. **The one real hazard: bulk copies of object references (VERIFIED).** `Array.Copy` / `List<T>` growth / span copies of references, and JIT struct copies with 4 or more GC refs into the heap (`TryLowerBlockStoreAsGcBulkCopyCall`, `jit/lower.cpp:8380`), go to `InlinedMemmoveGCRefsHelper`, which copies with SSE `movups` (`classlibnative/bcltype/arraynative.inl:60-96,195-236`). On x64 its pre-copy barrier is a no-op ("Strong memory model", `vm/common.h:333-346`); confirmed in the 9.0.23 binary at 0x18004417e. With VectorTSO off, the copied reference to a freshly built object can become visible before the object's method table and fields. Symptoms: NullReferenceException / AccessViolation / InvalidCast in another thread, or an AV inside a background GC ("Fatal error. Internal CLR error (0x80131506)"). Blocking GCs are safe (suspension issues full barriers).
2. **The JIT never SIMD-stores references to the heap (VERIFIED).** Zeroing and copying structs with refs on the heap use a register loop or `JIT_ByRefWriteBarrier` (`lowerxarch.cpp:378,416,459`; `codegenxarch.cpp:4244-4370`). `rep movsq` only ever carries non-reference data.
3. **Write barriers, card table, Volatile, Interlocked use integer stores (VERIFIED)** and stay ordered under base TSO. Exception (INFERRED, `Volatile.cs:140-150`): `Volatile.Read/Write(ref float)` uses `movss` and loses acquire/release with VectorTSO off.
4. **Allocation zeroing is safe (VERIFIED):** the allocating thread clears its own memory, and the method-table and publishing stores after it are `stlr`.
5. **Godot primitives** (SpinLock, CommandQueueMT, RingBuffer) synchronise through atomics, mutexes or integer positions (VERIFIED for those; not an exhaustive audit of game code).

## Interventions, ranked
| Change | Effect | Safety |
|---|---|---|
| `FEX_MEMCPYSETTSOENABLED=0` | `rep movs/stos` and the CRT memcpy/memset fast path come back (ERMS unhidden) | High confidence safe |
| `FEX_VECTORTSOENABLED=0` | removes a DMB per SIMD load/store and lets AVX pair | Medium: hazard 1 between game threads, and `Volatile` on float |
| `DOTNET_gcConcurrent=0` | closes hazard 1 for the background GC; gen2 becomes blocking (occasional pause) | Medium-high for the GC side |
| `DOTNET_EnableHWIntrinsic=0` etc. | none for safety (the native copy path is unaffected) and a large slowdown | Don't |

Shipped as experiments: `perf/fex-memcpy-tso` (memcpy off only), `perf/fex-vector-tso` (vector off only), `perf/fex-tso` (both off, FEX defaults), `perf/fex-fast` (both off plus `DOTNET_gcConcurrent=0`). Each launch logs `droiddeck-game-env: FEX TSO for this game: ...` with the values in effect.

## On-device experiment plan
- Run each build for the same 5+ minute session. Compare frame times from telemetry; count crashes and their kinds.
- Crash signals: "Internal CLR error (0x80131506)" or "Process terminated" on stderr; NullReference/InvalidCast/AccessViolation exceptions in the Godot log; FEX "Unhandled JIT SIGBUS".
- If `perf/fex-tso` crashes and `perf/fex-fast` does not, the background GC side of hazard 1 is the cause. If both crash and `perf/fex-memcpy-tso` doesn't, VectorTSO is needed; ship memcpy-off only.
- Soak test: `DOTNET_HeapVerify=1` with `DOTNET_GCgen0size=0x200000` (release-build switches; checks the heap around every GC) under the relaxed config.
- Litmus test (not yet built): a net9.0 win-x64 console app where producer threads `Array.Copy` freshly built objects into a shared array and consumers check a magic field, run under each config.
