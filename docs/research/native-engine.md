# Native ARM64 run for Slay the Spire 2 (workstream 1, branch emu/native)

Question: can Kestrel run StS2's engine and C# natively on the Pixel 10 (arm64), skipping FEX and
Wine/Proton, and what would it save?

Labels: **VERIFIED** = read in source or a primary page cited here. **INFERRED** = reasoned, not observed.
**MEASURED** = numbers from the bench in this branch.

## 1. Verdict

Feasible. A community Android port already runs the game's own files natively on arm64 phones
(VERIFIED, github.com/Ekyso/StS2-Launcher @1e97cf8, MIT, active to Nov 2026):

- It takes `SlayTheSpire2.pck` and the managed assemblies from **the Windows depot**
  (`data_sts2_windows_x86_64`, i.e. exactly what Kestrel already has installed), skips the `.so`/native
  files, and runs `sts2.dll` on a Godot 4.5.1 Android arm64 engine with .NET
  (`android/src/com/game/sts2launcher/GodotApp.java` `setupAssemblies`, lines ~107-180).
- The engine is stock Godot 4.5.1 (`scripts/build-godot.sh`, `scons platform=android arch=arm64
  target=template_release module_mono_enabled=yes`) plus the **Spine** runtime module and an **FMOD**
  GDExtension built for arm64, plus two render patches (pipeline-cache save, canvas ubershaders).
- Steamworks and Sentry are replaced by no-op C stubs (`src/stubs/steam_stub*.c`, `sentry_stub.c`);
  the Sentry GDExtension has no arm64 build and is disabled by a Harmony patch.
- So MegaDot's own changes are not required to load and play the game. Known rough edges in that port
  after newer game updates: scrambled card/relic images (issue #64), locale/font black screens
  (PRs #38, #57, #60). These may be MegaDot-specific engine behaviour the stock engine lacks.

MegaDot itself is closed: megadot.megacrit.com ships editor builds (its `install.sh` fetches
`megadot-<ver>-macos-universal-editor-csharp.zip`); no source or Linux/Android arm64 runtime is public.
The macOS game build ships `data_sts2_macos_arm64` (jiegec/STS2FirstMod README), which confirms that
`sts2.dll` is architecture-neutral IL.

The cheaper middle step (StS2's Linux x86-64 build under FEX's Linux mode, dropping Wine) is weak: the
FEX wiki lists the game as unplayable ("embeds CoreCLR"), and FEX issue #5991 reports a deterministic
crash at main-menu asset preload on ARM64 even with full TSO.

## 2. What blocks a Kestrel build

| Piece | Source | Licence | Status |
| --- | --- | --- | --- |
| Godot 4.5.1 engine, linux arm64, .NET (CoreCLR) | godotengine/godot (official linux arm64 mono templates exist) | MIT | free, buildable in CI |
| .NET 9 runtime linux-arm64 | dotnet/runtime | MIT | free |
| Spine runtime module (spine-godot) | EsotericSoftware/spine-runtimes | Spine Editor licence per user who integrates it | **needs Cedar's decision** (Spine Essential, personal, $69 on esotericsoftware.com/spine-purchase, Nov 2026) |
| FMOD core/studio libs linux arm64 + fmod-gdextension | fmod.com SDK (login) + utopia-rise/fmod-gdextension (MIT) | FMOD EULA, free tier for small/personal | **needs a fmod.com account** to download |
| Steamworks | stub (ours, MIT) or Valve's libsteam_api arm64 | stub: none | stub is enough to boot (port does it) |
| Sentry | disable | n/a | trivial |

Spine and FMOD binaries cannot go into the public renxida forks; they would live only in a private
artifact (e.g. a private release) pulled by Kestrel at install time.

## 3. How Kestrel would launch it (design, not built yet)

- Hook: the compat-tool launcher (`droiddeck-proton`, written by `steam-compatibility`). When the program
  is `SlayTheSpire2.exe` and `DROIDDECK_EXP_NATIVE_ENGINE=1`, exec `droiddeck-native-godot` instead of
  Proton. Steam keeps installing the Windows depot and tracking the session; Auto-Cloud still syncs.
- `droiddeck-native-godot` builds a launch dir in `~/.cache/droiddeck/native/<buildid>/`: symlink the
  `.pck`, `data_sts2_linuxbsd_arm64/` with the .NET linux-arm64 runtime + the game's managed DLLs
  (never copied out of the device), our engine binary, the stubs, and the FMOD/Spine-enabled extension.
- Engine patch (MIT, public): GDExtension library lookup falls back to an override map when the pck's
  `.gdextension` has no `linux.arm64` entry (the game's FMOD extension lists only its shipped platforms).
- Runs as a normal X11/Wayland Vulkan client under gamescope, using the same glibc Vulkan loader ->
  libhybris -> `vulkan.powervr.so` path winevulkan uses today, minus Wine and FEX.
- Fallback: if the native process exits within 60 s, the launcher logs it and starts the Proton path,
  so a test run is still normal play.

## 4. Measurements

Run 2026-11-13 on a GitHub `ubuntu-24.04-arm` runner (job 131679200006, commit d6a02c44),
workflow `.github/workflows/native-engine-bench.yml`. The same Godot 4.5.1 C# stand-in
(`tools/native-engine/bench`, 1200 measured frames after 240 warmup, headless) exported
twice: linux-arm64 run natively, and linux-x86_64 run under FEX 2611 (Linux mode, Ubuntu 24.04
amd64 rootfs). Three runs each.

| Variant | Frame mean | p50 | p95 | p99 | First frame | User CPU (whole run) | Max RSS |
|---|---|---|---|---|---|---|---|
| native arm64 | 0.80–0.82 ms | 0.76–0.77 | 0.83–0.84 | 0.95–0.96 | 0.21–0.29 s | 2.07 s | 214 MB |
| FEX, Kestrel's env (TSO on) | 1.15–1.19 ms | 1.05–1.08 | 1.18–1.56 | 1.67–1.91 | 7.3 s | 10.3 s | 520–528 MB |
| FEX, TSO off | 1.06–1.11 ms | 0.96–1.01 | 1.06–1.16 | 1.58–1.65 | 7.3 s | 10.0 s | 517–525 MB |

Frame time is the C# `_Process` work measured with a Stopwatch, not engine or GPU time.

Reading it:

- Steady-state managed work is about **1.45x faster native** (mean), **1.8–2x** at p99, so
  hitches shrink more than the average.
- Startup to first frame is **about 30x faster** (0.25 s vs 7.3 s): FEX translates CoreCLR and
  its JIT output cold every launch.
- Memory is **about 310 MB lower** (214 vs 525 MB RSS) for the same scene.
- TSO off crashed once in three runs (exit 134), so it stays off the table for CoreCLR.

What this underestimates on the phone: the phone stack also runs Wine, ARM64EC thunks and
proot, which the native path drops entirely, and Spine and FMOD are C++ that FEX translates
too. What it overestimates: cloud Neoverse cores are not the Pixel 10's, and the stand-in is
not the game. GPU and present cost are unchanged by this path (headless here).
