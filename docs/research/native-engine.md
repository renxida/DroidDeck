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
- (Community port) Steamworks and Sentry are replaced by no-op C stubs (`src/stubs/steam_stub*.c`, `sentry_stub.c`); we don't do this for Steam, see below;
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
| Skeleton animation (Spine stand-in) | ours, clean-room, `tools/native-engine/modules/native_compat/skel` | MIT | **Cedar chose to write our own** (Nov 13). JSON, binary 4.2 `.skel` (all five sampled game skeletons load and play), atlas, path constraints and physics springs |
| Audio (FMOD stand-in) | ours, clean-room event player, `native_compat/fsb` and `fmod_compat` | MIT | Vorbis sounds, path table and event metadata read; starting an event plays its sounds (598 of 600 sfx events resolve), timeline loop regions loop and timeline places delay. Parameters, buses and 3D not applied yet |
| Steamworks | Valve's libsteam_api linux-arm64 (SDK 1.63+, fetched on device from the Steamworks.NET NuGet package) | none | no stub: faking Steam bypasses Valve's and the publisher's launch and ownership check |
| Sentry | disable | n/a | trivial |

Clean-room rule: work from public format notes and the game's own data as read on Cedar's phone. Never
copy or read Esoteric's runtimes, the plugin that wraps them, or FMOD's SDK. No game files in any repo.

### Clean-room progress (Nov 14)

- Bank event metadata (`fsb/event_table.h`, worked out from the game's own `sfx.bank` bytes): every
  object's first chunk starts with its GUID; events reach waveform and multi instruments through their
  event block, timeline and (fallback) parameter sheets; a multi instrument's playlist is (GUID, weight)
  entries; a waveform names its sound's index in the FSB5 block; instrument volume is a dB float.
  `FmodEvent.start` and `play_one_shot` play the picked sounds through Godot audio players. The engine test
  hears its synthetic event at the expected level on the Master bus.
- Memory: a bank loaded from a file keeps only its metadata and FSB5 headers; each sound's bytes are read
  from the file when it is wanted, and sounds over 256 KB (music) are not cached. MEASURED with synthetic
  banks of the game's 12 audio banks' real sizes and sample counts (361 MB in all), headless: loading all of
  them cost +347 MB resident before, +0 MB after (the four boot-time banks: +64 MB before, +0 after).

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
  4.5.1 C# export: .NET runtime, GodotSharp, GodotPlugins), and Valve's linux-arm64 libsteam_api.so from the
  Steamworks.NET NuGet package (sha256-pinned; components up to -38 also carry a no-op Steam stub, which is
  ignored). It then builds `~/.cache/droiddeck/native/launch/<appid>/` with the engine hardlinked in and
  `data_<asm>_linuxbsd_arm64/` holding the kit, Valve's library (as libsteam_api64.so) and the game's managed DLLs (hardlinked; they never
  leave the phone), and runs `engine --main-pack <game>.pck --print-fps` under a first-frame watchdog: Godot's
  first "Project FPS" line (about 4 s into the main loop) is logged as "first frame"; none within 90 s of the
  engine's start (after the fetch), or an engine exit before one, kills the engine's process group and exits
  86 ("did not start"; game-env's half falls back on it). The engine dies with the wrapper (PR_SET_PDEATHSIG).
  Checked against our engine build: quits at 8 s with code 5 -> 5; quits at 1 s -> 86; idles past a 3 s
  limit -> killed, 86. A main loop that runs on a black screen still counts as started.
- Three things learned on the CI runner (VERIFIED, run 43374974943, the bench's Windows export):
  - the .NET host resolves the main assembly's real path, so symlinks send it to the Windows folder;
    hardlinks work;
  - the Windows `deps.json` describes the win-x64 runtime, so the launch folder gets the kit's
    `deps.json` with its app entry renamed to the game and listing the game's assemblies, and the kit's
    `runtimeconfig.json` with the game's runtime switches on top;
  - a Windows build stamps its IL assemblies AMD64, which arm64 CoreCLR refuses; an IL-only launch copy is
    relabelled ARM64 and nothing else. A ReadyToRun image (crossgen2 output: IL-only clear, IL_LIBRARY set, an
    RTR header, an x64 exception table and base relocations, like the game's published DLLs) is turned back into
    plain IL: relabelled, R2R header cleared, IL-only set, IL_LIBRARY cleared, exception and relocation
    directories emptied. MEASURED: .NET on Linux refuses a Windows R2R image, relabelled or not, through the
    default load context and through hostfxr `load_assembly_and_get_function_pointer`, which is how Godot's
    exported builds load the game assembly (startup gate run 43630311459: "Failed to get GodotPlugins
    initialization function pointer", SIGSEGV; arm64-r2r runs 43630745903/43631010173: BadImageFormatException).
    Each conversion step alone still gets "Bad IL format"; all of them together load and run on both paths
    (local .NET 8 linux-x64, CI arm64 .NET 9 fxrhost check). Only `AssemblyLoadContext.LoadFromAssemblyPath`
    took the relabel-only image, which is why the first r2r-probe looked good. An untouched AMD64 image gets
    "not compatible with the current process architecture". A true mixed-mode assembly (C++/CLI: vtable
    fixups, no RTR header) can't run on arm64, so it is left out and logged with the game assemblies that name
    it; only the game's own assembly being mixed-mode stops the launch. The first phone launch (0aa91a7)
    stopped at compose because the old check took R2R's missing IL-only mark for mixed mode.
- The pck's `.gdextension` entries (Spine, FMOD, Sentry) have no `linux.arm64` library, so Godot logs an
  error and skips them. Our module will register stand-in classes under the names the game's scenes use.
- Runs as a normal X11/Wayland client under gamescope (Vulkan via the guest's glibc loader, the same driver
  path winevulkan uses), minus Wine and FEX. INFERRED until a phone run.
- Expected first-run gaps: no Spine drawing and no audio until the stand-ins match the game's names;
  Steam goes through Valve's own linux-arm64 libsteam_api.so to the real arm64 client in the session; Sentry absent.

### Startup gate (CI) and phone dry run

- `.github/workflows/native-startup-gate.yml` (also `workflow_call`; input `component`: `pinned` or a
  `native-engine-N` tag; `soak`): a stand-in Godot 4.5.1 C# game, `tools/native-engine/standin` (our code plus
  public NuGet packages: Sentry, 0Harmony, MonoMod.Backports/ILHelpers, SmartFormat+ZString, Steamworks.NET
  (a managed value type only, no SteamAPI_Init), Vortice.Mathematics/DXGI+SharpGen.Runtime,
  JetBrains.Annotations, System.IO.Hashing; a Windows-only `.gdextension`), exported for Windows x86_64 with
  ReadyToRun on/off x deps on/off x extension present/missing, then run on `ubuntu-24.04-arm` through the
  wrapper from the checkout on the published component, 3x per row, plus Xvfb rows (opengl3, Vulkan on
  lavapipe). Self-test rows run older converters and must fail: 7754c57b (same file as 0aa91a7) at compose
  ("mixed-mode"), d68b35ad and f86b8550 at load (Godot: "Failed to get GodotPlugins initialization function
  pointer"). VERIFIED (run 43632103981): every R2R row starts on da7d6114 and none did on f86b8550.
- Stand-in contract: `StandIn.exe`, `StandIn.pck`, `data_StandIn_windows_x86_64/`; stdout (unbuffered, .NET
  Console) `STANDIN_DEPS loaded=N ...`, `FIRST_FRAME` on the first `_Process`, `MAINLOOP_OK frames=N` then exit
  0, with N from `--quit-after-frames N` (engine or user args) or `STANDIN_QUIT_AFTER_FRAMES`.
- `--print-fps` works in the release template, headless or not, but the first `Project FPS:` line comes about
  4 s into the main loop (Godot hides the first three). Headless runs uncapped (~145 fps here), so 300 frames
  end in ~2 s, before any FPS line: the wrapper then says 86. The gate passes `--max-fps 30` (300 frames, 10 s).
- MEASURED: Godot's stdout is block-buffered in the release template (`application/run/flush_stdout_on_print`
  is false outside debug builds), so through the wrapper's pipe the first FPS line arrives only at exit: a 30 s
  run delivered it at 30.4 s; under `stdbuf -oL` at 4.1 s (probe job, run 43632484157). On the phone a game
  that prints under ~4 KB of stdout in 90 s would be killed by the watchdog as "did not start". `gate.py
  --require-live-fps` turns this into a failure once the wrapper runs the engine line-buffered.
- `droiddeck-native-godot check` (same args as compose, plus `--name`): composes into `--out`, checks the launch
  folder is startable (game assembly there, every game assembly loadable-shaped, deps.json and
  runtimeconfig.json written, engine in place) and writes pass/fail, rejected files, component tag, converter
  hash and a fingerprint of the game's data folder (names, sizes, mtimes) to
  `~/.cache/droiddeck/native/check/<name>.json`; exit 0/1. `droiddeck-game-env` skips native up front while a
  fresh result (same fingerprint, component, converter) says fail; with no fresh result it schedules a check
  for after the session (waits for the launch's pid to exit, nice 19, detached, scratch folder removed after).

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
