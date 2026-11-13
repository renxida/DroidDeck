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
| Skeleton animation (Spine stand-in) | ours, clean-room, `tools/native-engine/modules/native_compat/skel` | MIT | **Cedar chose to write our own** (Nov 13). Core, JSON and atlas reading done; binary `.skel` reader waits on the inventory |
| Audio (FMOD stand-in) | ours, clean-room event player | MIT | not started; needs the bank inventory first |
| Steamworks | stub (ours, MIT) or Valve's libsteam_api arm64 | stub: none | stub is enough to boot (port does it) |
| Sentry | disable | n/a | trivial |

Clean-room rule: work from public format notes and the game's own data as read on Cedar's phone. Never
copy or read Esoteric's runtimes, the plugin that wraps them, or FMOD's SDK. No game files in any repo.

### Clean-room progress (Nov 13)

- Engine: CI builds a Godot 4.5.1 linux-arm64 mono export template from source with our module compiled
  in (`native-engine-build.yml`, about 17 min cold). It runs a real C# export (hostfxr and GodotPlugins
  load).
- Skeleton core (`skel/`): setup data, world transforms for every inherit mode, region and weighted mesh
  vertices, linear/stepped/bezier keys (3.x normalised and 4.x absolute controls), attachment, colour,
  two-colour, deform, draw order, event and IK timelines, crossfading animation state, text atlas with
  trimmed regions. Host tests run under ASan/UBSan.
- `NativeSkeleton2D` node: loads JSON + atlas, plays and mixes, emits signals, draws. CI renders a test rig
  on our template under Xvfb and checks two frames.
- Open questions that need the game's files: binary `.skel` layout (4.2), whether the game uses physics,
  transform or path constraints, the 90-degree atlas turn direction and mesh uv origin, the class and
  method names the game's scenes and `sts2.dll` call (compatibility wrappers take those names), and the
  FMOD codecs. If the banks are Vorbis, FSB5 strips the Vorbis setup header and keeps a checksum;
  the plan is to regenerate candidate headers with libvorbis (BSD) at the matching rate, channels and
  quality and pick the one whose checksum matches, rather than copy any table from FMOD.
- Managed side: `sts2.dll` and its managed dependencies are portable IL and stay on the phone. The
  native data dir pairs them with stock 4.5.1 `GodotSharp`/`GodotPlugins` (built from the same source
  as our template) and the .NET linux-arm64 runtime. Risk: if MegaDot changed GodotSharp's API, calls
  into it fail with missing-member errors; the community port running on stock 4.5.1 suggests it mostly
  doesn't.

## 3. How Kestrel launches it (experiment `native-engine`, off by default)

- Hook: `droiddeck-game-env`. For a Godot game with one `data_*_windows_x86_64` folder and
  `DROIDDECK_EXP_NATIVE_ENGINE=1`, it runs `droiddeck-native-godot run --fetch` before any Proton work.
  Steam keeps installing the Windows depot and tracking the session; Auto-Cloud still syncs.
- Fallback: an error exit within 60 s is "did not start"; the last 30 lines of `native-engine.log` go to the
  session log and the game starts on Proton in the same launch. A later exit ends the launch with its code.
- `droiddeck-native-godot` fetches the pinned component (a release on this repository, sha256-checked,
  cached in `~/.cache/droiddeck/native/<tag>/`): our engine, the kit (the linux-arm64 data folder of a stock
  4.5.1 C# export: .NET runtime, GodotSharp, GodotPlugins) and a Steamworks stub. It then builds
  `~/.cache/droiddeck/native/launch/<appid>/` with the engine hardlinked in and
  `data_<asm>_linuxbsd_arm64/` holding the kit, the stub and the game's managed DLLs (hardlinked; they never
  leave the phone), and runs `engine --main-pack <game>.pck`.
- Three things learned on the CI runner (VERIFIED, run 43374974943, the bench's Windows export):
  - the .NET host resolves the main assembly's real path, so symlinks send it to the Windows folder;
    hardlinks work;
  - the Windows `deps.json` describes the win-x64 runtime, so the launch folder gets the kit's
    `deps.json` with its app entry renamed to the game and listing the game's assemblies, and the kit's
    `runtimeconfig.json` with the game's runtime switches on top;
  - a Windows build stamps its IL assemblies AMD64, which arm64 CoreCLR refuses; the launch copy is
    relabelled ARM64 (ReadyToRun x64 code, if any, is dropped so the IL is JIT-compiled).
- The pck's `.gdextension` entries (Spine, FMOD, Sentry) have no `linux.arm64` library, so Godot logs an
  error and skips them. Our module will register stand-in classes under the names the game's scenes use.
- Runs as a normal X11/Wayland client under gamescope (Vulkan via the guest's glibc loader, the same driver
  path winevulkan uses), minus Wine and FEX. INFERRED until a phone run.
- Expected first-run gaps: no Spine drawing and no audio until the stand-ins match the game's names;
  Steam features beyond file sync do nothing (stub); Sentry absent.

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
