# Kestrel FEX: Godot stand-in page fault (Nov 14, 2026)

The Godot 4.5.1 mono stand-in (`native-standin-win-x64`, StandIn.exe) runs 600 frames headless under
Hangover 11.16's own FEX (FEX-2608) in ~16 s. Under `Kestrel-FEXCore-20261114.24-ab135df09-618bd252.wcp` it died
~10 s in, every run:

    wine: Unhandled page fault on read access to 0045338BF900037A at address 00000001429208CF (thread 0024)

Found and fixed off-device with the ARM64EC harness (Hangover 11.16 on `ubuntu-24.04-arm`, game FEX settings).

## Not Kestrel's patches

`.github/workflows/fex-godot-bisect.yml` built ab135df (Kestrel's upstream base) with Kestrel's patches up to each
one. Run 43636809777: 0000 (upstream alone) through 0009 and the published .wcp all crash 6/6 at the same
address; stock 5/5. The .NET app (`clr.exe`) passes 2/2 on every row.

## Upstream FEX PR #6094

`.github/workflows/fex-upstream-bisect.yml`, no patches, stand-in 5x per commit:

| Round | Run | Result |
|---|---|---|
| 1 | 43635698654 | f18599d … a6ba3e2 pass; e67bbd3 (FEX-2611-3) and ab135df crash. FEX-2608 to late Sept doesn't build with today's llvm-mingw (fmt needs `<atomic>`; round 2 adds it). |
| 2 | 43636809784 | fb68b0e 5/5; effbda6 (PR #6094, "Feature flag fixes and alignment between targets") 0/5, and every later commit 0/5. |
| 3 | 43637676703 | ab135df with each part of #6094 undone, 8x: preserve_all off 8/8; cache-maintenance ops back off 0/8; Wow64 host type back 0/8; as is 0/8. |

#6094 changed `CMakeLists.txt` to stop ignoring clang's `preserve_all` under mingw when clang >= 22.1. Kestrel's
llvm-mingw 20261104 is clang 23.1.5, so FEX's JIT helper calls switched to the preserve_all convention in the
ARM64EC build. The crash is inside StandIn.exe reading a garbage pointer, so most likely a register the game
relies on is not preserved across one of those calls (not confirmed). Older toolchains skip it, which is likely why FEX's own CI and the phone's stock FEX don't see it.

## Fix

`tools/fex/arm64ec/build.sh` passes `-DHAS_CLANG_PRESERVE_ALL=FALSE` (the check is skipped when its result is
already set) and fails the build if `FEX_HAS_PRESERVE_ALL_ATTR=0` is not in the generated build. The gate's Godot
row (`fex-wine-gate.yml`) now fails any candidate on which the stand-in crashes or hangs; its speed is report-only.

## The phone's stock FEX

The phone's games FEX is `DD-FEXCore-2611+47-nightly.wcp` (FEX-2611-47-gea43314, from DroidDeck-Components'
linux-nightly release). `.github/workflows/fex-phone-fex.yml`, run 43636809795: stand-in 5/5, .NET app 2/2, same
as Hangover's stock. The phone's fallback is not affected.

## Still open

- "SuspendThread failed with error 5" in `clr.exe` showed once (run 43635612966, row 0001, upstream + patch 0001)
  and not in 28 later runs. 0001 doesn't touch threads, so it is likely upstream or the harness; unexplained.
- The root cause in FEX or clang (which preserve_all helper, and why) is not chased here; the build flag is enough
  for Kestrel.
