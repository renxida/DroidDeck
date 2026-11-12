# Does relaxing FEX's TSO switches break .NET? An on-device litmus test

Companion to `docs/research/dotnet-under-relaxed-tso.md` (branch perf/fex-fast), which argues from source that FEX's
vector TSO is what keeps .NET's bulk reference copies safe, and that memcpy/memset TSO can go. This chapter describes
a test that measures that claim on the phone. VERIFIED = read in source, or measured where stated; INFERRED = reasoned,
not observed. Nothing here was run on Android: the device numbers come from Cedar's one tap.

Sources read (downloaded to the scratchpad, not run): dotnet/runtime release/9.0 (`src/coreclr/classlibnative/bcltype/arraynative.inl`,
`src/coreclr/jit/lower.cpp`, `src/coreclr/gc/gc.cpp`, `src/libraries/System.Private.CoreLib/src/System/Threading/Volatile.cs`,
`src/native/corehost/fxr_resolver.cpp`, `.../hostmisc/runtime_config.cpp`, `.../fxr/fx_muxer.cpp`, `.../hostpolicy/deps_resolver.cpp`),
Godot 4.5-stable `modules/mono/mono_gd/gd_mono.cpp`.

## 1. Question

With `FEX_VECTORTSOENABLED=0` and/or `FEX_MEMCPYSETTSOENABLED=0` (and optionally `DOTNET_gcConcurrent=0`), does an x64
.NET 9 program running through proot + FEX + Proton on the Pixel 10 (Tensor G5) observe outcomes x86-TSO forbids, and
what does each switch cost or buy in throughput?

## 2. Background

**x86-TSO.** x86 allows only one reordering visible to software: a store followed by a load of another location
(store buffering, SB). Message passing (MP) and independent reads of independent writes (IRIW) are forbidden.
Owens, Sarkar, Sewell, *A better x86 memory model: x86-TSO*, TPHOLs 2009; Sewell et al., *x86-TSO: a rigorous and
usable programmer's model for x86 multiprocessors*, CACM 53(7), 2010. Intel SDM Vol. 3A §9.2 states the same for
ordinary loads and stores, and §9.2.4 notes that fast-string (`rep movs/stos`) stores may be performed out of order
among themselves.

**Arm.** Armv8 permits load-load, load-store, store-store and store-load reordering; it is other-multi-copy-atomic
(Pulte et al., *Simplifying ARM concurrency*, POPL 2018), so IRIW is forbidden only when each reader's two loads are
ordered. Translating x86 to Arm soundly needs a barrier or acquire/release mapping per access
(Rocha et al., *Lasagne*, PLDI 2022; Gouveia et al., *Risotto*, ASPLOS 2023). Litmus testing on real hardware finds
reorderings at low and machine-dependent rates, so many iterations are needed and absence of a result is weak evidence
(Alglave, Maranget, Sarkar, Sewell, *Litmus: running tests against hardware*, TACAS 2011; Alglave, Maranget,
Tautschnig, *Herding cats*, TOPLAS 2014).

**FEX.** With TSO on, integer loads/stores become `ldapr`/`stlr` (RCpc acquire / release). Vector TSO adds barriers
around SSE/AVX accesses; with it off they are plain `ldr q`/`str q`. Memcpy TSO makes `rep movs/stos` an ordered loop
and hides ERMS in CPUID. A release store orders *earlier* accesses only, so `stlr` followed by a plain vector store can
become visible in either order (INFERRED from the Arm model; this is what the test measures).

**The .NET hazard (VERIFIED in source).** `InlinedMemmoveGCRefsHelper` copies object references with SSE
(`arraynative.inl:64,94-96,202,234-236`: `_mm_storeu_ps`/`_mm_store_ps`). `Array.Copy` of references, `List<T>` growth,
and JIT struct copies with four or more GC references (`lower.cpp:8366-8422`, `TryLowerBlockStoreAsGcBulkCopyCall`
→ `CORINFO_HELP_BULK_WRITEBARRIER`) reach it. Object construction stores are integer (`stlr`), so with vector TSO off
a reference copied by SSE can become visible before the object's fields. `Volatile.Read/Write(ref float)` goes through
a `volatile float` field (`Volatile.cs:140-150`), i.e. `movss`, so it loses acquire/release with vector TSO off.
Background GC only runs while the latency mode is Interactive or SustainedLowLatency (`gc.cpp:24479`), so
`GCSettings.LatencyMode = Batch` turns it off inside one process.

## 3. Design (VERIFIED by building and running on x86-64 Linux, .NET 8)

`tools/tso-litmus/` is a win-x64 console program (C#, `net9.0`). Every test runs for a fixed share of `--seconds`
(default 60 per FEX setting), counts outcomes and writes one JSON line per test (`--out`) plus a summary line on stdout.

| group | test | what it does | forbidden on x86; needs |
|---|---|---|---|
| a | refs-array-copy | producers build objects (magic, checksum, array), `Array.Copy` 6 refs into a shared array; consumers verify type/magic/checksum/array | vector TSO |
| a | refs-struct-copy | same, through a 6-reference struct copy (48 bytes, so at least one 16-byte SSE store) | vector TSO |
| a | refs-list-add, refs-plain-store | controls: single-reference stores (`stlr`) | none |
| b | refs-gen2-churn-background / -blocking | groups a's two hazards under a 32K-object gen2 workload and a gen2 collection every 20 ms, background GC on (Interactive) then off (Batch); counts background GCs | vector TSO |
| c | volatile-float-flag / -data | MP with `Volatile.Write/Read(ref float)` as flag / as data | vector TSO / none |
| d | mp-int, mp-sse, mp-int-data-sse-flag, mp-sse-data-int-flag | MP, with `Vector128` or `int` for data and flag | none, vector, vector, none |
| d | sb-int, sb-sse | SB: *allowed* on x86; a contrast showing the harness can see reorderings | allowed |
| d | iriw-int, iriw-sse | IRIW, 4 threads (2 groups with ≥8 CPUs) | none / vector TSO |
| e | memcpy 256 B–16 MB, memset 16 KB/1 MB, refcopy 8 KB, SIMD128/256 saxpy, scalar sum | single-thread MB/s, best of 50 ms windows | — |

The `needs` column is the design's prediction; a failure in a "none" test is reported as `UNEXPECTED=` because it
would mean the integer mapping, not the vector one, is at fault. Vector cells are 64-byte aligned and every SSE load is
checked for tearing so torn reads are not counted as reorderings.

**Local run (VERIFIED, x86-64, 4 CPUs, .NET 8, 20 s and 10 s):** every forbidden test 0 failures; `sb-int` observed
491 and 97 701 times, `sb-sse` 0 and 13 513, so the harness does see the one reordering x86 allows. The JIT on .NET 8
copies the 6-reference struct with `CORINFO_HELP_ASSIGN_BYREF`.

**CI run (VERIFIED, GitHub x86-64 runner, .NET 9 SDK, 20 s, run 43269884615):** every forbidden test 0 failures over
29–287 million samples each (`refs-array-copy` 165 M, `refs-struct-copy` 267 M, `mp-sse` 96 M, `iriw-sse` 29 M);
`sb-int` observed 193 885 times, `sb-sse` 619 852; 28 background GCs during `refs-gen2-churn-background`, 0 during
`-blocking`. The .NET 9 JIT compiles the 6-reference struct copy to `CORINFO_HELP_BULK_WRITEBARRIER`
(`DOTNET_JitDisasm=Produce`), so `refs-struct-copy` does exercise the runtime's SSE reference copy.

**Running on the game's runtime, not a bundled one (VERIFIED in source).** The APK ships only `tso-litmus.exe`
(apphost, 153 KB) and `tso-litmus.dll` (42 KB), +0.09 MiB compressed in the APK. `droiddeck-tso-litmus` finds an installed game with a .NET runtime
(Slay the Spire 2 first; Godot 4.5 exports ship `hostfxr.dll` beside `coreclr.dll`, `gd_mono.cpp` loads it), links the
runtime pack's files into `~/.cache/droiddeck/tso-litmus/app`, and writes a runtimeconfig with no `framework(s)`, which
makes the app self-contained (`runtime_config.cpp:184-219`); the apphost then uses the app-local `hostfxr`
(`fxr_resolver.cpp:54-79`), and with no `.deps.json` every assembly in the folder is on the TPA (`deps_resolver.cpp`).
The game's own assemblies are not linked, so droiddeck-game-env's Godot fixes do not apply.

**Launch path.** Each FEX setting runs `droiddeck-proton-run --prefix <appid> --compatdata <library>/steamapps/compatdata/<appid> -- tso-litmus.exe …`,
i.e. the compatibility tool, droiddeck-game-env and Proton as a game launch, in the game's existing prefix. Because
droiddeck-game-env's .NET fix (`dotnet_memory_ordering`) would overwrite the FEX switches, the launcher sets
`DROIDDECK_KEEP_ENV` and game-env keeps the caller's values for the names listed (a 15-line addition to
`droiddeck-game-env`, which another worker owns). The Diagnostics tab's *Advanced: run memory-ordering test* starts a
`MODE_RUN` session for `/usr/local/bin/droiddeck-tso-litmus`; results go to `$BL_DEBUG_DIR/tso-litmus/`
(`<config>.jsonl`, `<config>.log`, `results.json`) and the lines below go to the session log.

## 4. Findings

- VERIFIED (source): the hazard path, the float-volatile path and the GC latency switch, as in §2.
- VERIFIED (x86 runs, local and CI): no forbidden outcome on hardware TSO; SB observed; background GC runs only in
  the Interactive half of group b; .NET 9 routes the struct copy through the bulk reference-copy helper.
- INFERRED: on the Pixel 10, `vector-tso-off` and `both-off` show failures in `mp-sse`, `mp-int-data-sse-flag`,
  `iriw-sse`, `volatile-float-flag` and, more rarely, `refs-array-copy`/`refs-struct-copy`; `full-tso` and
  `memcpy-tso-off` show none.
- INFERRED: `both-off-gc-blocking` still fails the group a tests (the reordering is in user code, not the GC), so
  `DOTNET_gcConcurrent=0` alone does not make vector TSO off safe; it may only remove the background-GC crash
  (`Fatal error. Internal CLR error. (0x80131506)`).
- INFERRED: `memcpy-tso-off` speeds up memcpy/memset benches several-fold for sizes ≥16 KB with `erms=yes`.

## 5. Interventions, ranked

1. **Keep vector TSO on for .NET games** unless the test shows zero group a/b failures with `vector-tso-off` *and*
   `mp-sse` fails (so the hardware did reorder). Effect: no crash risk. Cost: the `vector-tso-off` throughput ratio.
   Risk: none.
2. **Turn memcpy/memset TSO off for .NET games** if `memcpy-tso-off` has `failures=0` and `applied=yes`. Effect: the
   memcpy/memset speedup in the `speed` lines. Risk: low — `rep movs/stos` carries only non-reference data in .NET
   (static analysis, claim 2), and the test cannot reach every native copy.
3. **Do not rely on `DOTNET_gcConcurrent=0` to make vector TSO off safe** if `both-off-gc-blocking` still fails.
   Cost of using it anyway: longer gen2 pauses.
4. Research only: per-region TSO (FEX's volatile metadata does not apply to Wine-loaded code, per the static doc) or
   an x64 runtime build whose reference copy ends with a full barrier.

## 6. On-device experiment plan

One tap: Settings → Diagnostics → *Advanced: run memory-ordering test* (≈10 min with Slay the Spire 2 installed).
In the uploaded session log, look for:

- `tso-litmus: start: runtime … (.NET 9.0.x, app 2868840, N files) …` — the game's runtime was found. `.NET` other
  than 9.x or `no installed game ships a .NET runtime` means the run says nothing.
- per setting `tso-litmus: config=<name> kept the caller's FEX_…` (echoed from droiddeck-game-env) — the switches survived game-env.
- per setting `tso-litmus: config=<name> failures=<n> state=finished|CRASHED|no-start throughput=<MB/s> (x<r> vs full-tso) erms=yes|no gc=concurrent|blocking bgc=<n> failed=<test:n,…> UNEXPECTED=<…> allowed=sb-int:<n>,sb-sse:<n> applied=yes|NO(…)`.
  `applied=yes` and `erms=yes` only under the memcpy-off settings confirm the setting reached the process.
  `bgc>0` under `full-tso` confirms background GC ran.
- `tso-litmus: speed <bench>: full-tso=<v>(x1.00) memcpy-tso-off=<v>(x…) …` — the cost of each switch.
- `tso-litmus: verdict: full-tso=ok memcpy-tso-off=ok vector-tso-off=FAILED …`.

Reading it: claim 1 of the static doc is **confirmed** if `refs-array-copy` or `refs-struct-copy` appear in `failed=`
under `vector-tso-off`/`both-off` and never under `full-tso`/`memcpy-tso-off`; **unsupported but not refuted** if they
are absent while `mp-sse` fails (the window is narrow; raise `DROIDDECK_TSO_LITMUS_SECONDS`); the run **has no
power** if `mp-sse` is 0 too. Any failure under `full-tso`, or `UNEXPECTED=`, means the integer mapping or the harness
is wrong and must be looked at first. `state=CRASHED` with `0x80131506` under `vector-tso-off` but not
`both-off-gc-blocking` supports the background-GC symptom.

## 7. Limits

Not run on Android, Wine or FEX here. Wine's console output and Z: path mapping, symlinked runtime files under Wine,
and FEX honouring per-process environment through Proton are INFERRED from how game launches work. Failure rates on
Arm are probabilistic: zero is not proof of safety.
