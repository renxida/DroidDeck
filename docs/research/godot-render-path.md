# Godot 4 render path for Slay the Spire 2 on PowerVR

Question: what is the fastest and most stable graphics path for a Godot 4 .NET
Windows game (Slay the Spire 2, Steam app 2868840) running under Proton and FEX on
the Pixel 10's PowerVR Vulkan driver, and how should Kestrel select it?

Short answer: Godot's Vulkan driver (never D3D12 or OpenGL), with the Mobile
renderer instead of Forward+, a pipeline cache that is saved before the game is
killed, and in-game MSAA left off. The first is required for the game to draw at
all. The others are expected improvements that have not been measured yet. All
are selected per game by `droiddeck-game-env` and can be turned off with
`DROIDDECK_GODOT_RENDERER=off`.

Labels: **VERIFIED** means read in source code or a primary document cited here.
**INFERRED** means reasoned from verified facts but not observed. Nothing here was
measured on a device: no device was available for this work.

## 1. Background

### 1.1 What StS2 ships

- **Engine version: Godot 4.5.1.** Confidence medium-high, INFERRED from two
  independent public sources. The community modding guide
  (github.com/fresh-milkshake/Modding-Tutorial, "Verified Baseline") gives the
  main PCK format as Godot 4.5.1. The community Android port
  (github.com/Ekyso/StS2-Launcher @1e97cf8, README line 3) is "built on a custom
  Godot 4.5.1 engine" and writes a v3 PCK stamped 4.5.1
  (`scripts/make-bootstrap-pck.py` lines 15-19). Steam and SteamDB pages do not
  name an engine version.
- **.NET:** the Windows build ships `coreclr.dll` and `GodotSharp.dll`. The FEX
  wiki page for the game lists it as unplayable because of CoreCLR under FEX. That
  is another worker's topic.
- **Renderer: Forward+ (INFERRED).** The Android port's bootstrap project declares
  `config/features=PackedStringArray("4.5", "Forward Plus", "C#")` and no
  `rendering_method` (`make-bootstrap-pck.py` line 32). Forward+ is Godot's
  desktop default.
- **Windows driver: D3D12 (INFERRED).** Upstream DroidDeck PR #168
  (github.com/Droid-Deck/DroidDeck/pull/168) reports StS2 showing a black window
  after the Steam logo, because "Godot's D3D12 renderer fails to create its swap
  chain through VKD3D-Proton". Godot's own Windows default is Vulkan
  (`main/main.cpp` ~2165), so the game must set `driver.windows=d3d12`. The new
  launch line prints the project's real setting (section 5), so the device log
  turns this into a VERIFIED fact.
- **Content:** a mostly 2D game, with Spine skeletal animation and FMOD audio.
  Almost all of its GPU time runs through Godot's canvas renderer, which Forward+
  and Mobile share. Back-buffer copies and the post effects around it differ
  between the two.
- **MSAA:** the game has an in-game MSAA setting. The Android port forces
  `Msaa = 0` on first launch (`src/STS2Mobile/Patches/SettingsPatches.cs` lines
  55-63, VERIFIED).

### 1.2 The three Windows graphics paths under Wine

| Godot driver | Path under Proton on Kestrel | Status |
| --- | --- | --- |
| `vulkan` | Godot's SPIR-V goes through winevulkan (a thin thunk to the host loader), the libhybris compat layer, then `vulkan.powervr.so` | Works on Adreno (PR #168, StS2 reaches the main menu on a Galaxy Z Fold8) |
| `d3d12` | Godot compiles its shaders to DXIL. vkd3d-proton translates DXIL back to SPIR-V (dxil-spirv) and maps D3D12 onto Vulkan | Black window (PR #168) |
| `opengl3` | Only valid with the Compatibility renderer. Wine's opengl32 goes to the host GL, which is Zink on Vulkan here | Would change the renderer's whole feature set |

Overhead (INFERRED, qualitative). The Vulkan path has one API layer: winevulkan
marshals the calls and adds no translation, and the hybris layer only rewrites
SPIR-V in a few known patterns (`tools/hybris`, dxvk profile). D3D12 adds two
compilers to every pipeline: Godot's SPIR-V to DXIL step running under FEX
emulation, then dxil-spirv. It also adds vkd3d-proton's descriptor-heap emulation
on every draw. OpenGL through Zink adds a whole GL state tracker. No source gives
numbers for these layers on this stack. The relative order is clear, but the size
of each gap is unmeasured.

### 1.3 How Godot 4.5.1 picks its renderer (VERIFIED, godot @ f62fdbde, 4.5.1-stable)

- Command-line options (`main/main.cpp`, help at ~600-630):
  - `--rendering-method forward_plus|mobile|gl_compatibility`
  - `--rendering-driver vulkan|d3d12|opengl3|...`
  - `--accessibility auto|always|disabled` (line 624)
  - `--render-thread safe|separate`
  - `--single-threaded-scene` and `--disable-render-loop`
- With a driver given and no method, Godot picks `forward_plus` for any
  RenderingDevice driver and ignores the project's own method (`main.cpp`
  2500-2511). The upstream fix (`--rendering-driver vulkan` alone) therefore
  silently forces Forward+ on every Godot 4 game, Compatibility projects included.
- An unknown method or driver, or a method and driver that don't match (for
  example `gl_compatibility` with `vulkan`), aborts at startup (`main.cpp`
  2430-2480).
- On Android, `rendering/renderer/rendering_method.mobile` defaults to `mobile`
  (`main.cpp` 2409, 2565). The StS2 Android port sets no method, so it runs the
  Mobile renderer on phones (INFERRED).
- **override.cfg:** once the project pack is found, Godot loads
  `res://override.cfg` and then `<exe dir>/override.cfg`
  (`core/config/project_settings.cpp` 694-701). This is the same code path for
  exported .NET games. It is not read from the user data directory, and
  `--main-pack` reads it next to the pack instead (line 649).
  `_load_settings_text` converts by `config_version`, so the file declares
  `config_version=5`.

### 1.4 Shader compilation and the pipeline cache (VERIFIED)

- The Vulkan pipeline cache is
  `user://vulkan/pipelines.<method>.<device>.cache` (`rendering_device.cpp` 6878).
  It is checked against vendor, device, driver version and UUID, and it is saved:
  - when the new data since the last save reaches
    `rendering/rendering_device/pipeline_cache/save_chunk_size_mb` (default 3.0,
    lines 6936-6939);
  - on clean exit (line 7242).
- SPIR-V compiled from Godot's shader sources goes to `user://shader_cache` as
  soon as it is compiled (`shader_rd.cpp` ~596).
- `user://` on Windows is `%APPDATA%/<custom_user_dir_name>` or
  `%APPDATA%/Godot/app_userdata/<name>` (`platform/windows/os_windows.cpp`
  `get_user_data_dir`). In the Proton prefix, that is
  `pfx/drive_c/users/steamuser/AppData/Roaming/...`, which stays on device
  storage between runs.
- Godot 4.4 and later use ubershaders so 3D draws never wait for a specialised
  pipeline:
  - Forward+: `ubershader_iterations = 2` (`render_forward_clustered.cpp` 492);
  - Mobile: the same (`render_forward_mobile.cpp` 2386);
  - canvas renderer: `ubershader_iterations = 1` (`renderer_canvas_render_rd.cpp`
    474, 1739). Every new 2D material or blend state compiles synchronously the
    first time it is drawn (line 493).

  The StS2 Android port patched exactly these two gaps in its engine build:
  canvas ubershaders, and a pipeline-cache save on focus loss (README lines
  20, 37-40).
- So for a 2D game, the pipeline cache is the only thing that removes repeat
  first-draw stalls. If one session compiles less than 3 MiB of pipeline data
  and the game is killed rather than quit, Godot never saves the cache. Android
  stops games by killing them. INFERRED: each such run pays the full compile cost
  again.

### 1.5 Forward+ vs Mobile on a tiler (VERIFIED in source, effect INFERRED)

- `RenderForwardMobile::_render_buffers_can_be_storage()` returns false
  (`render_forward_mobile.cpp` 430). The comment there names framebuffer
  compression (AFBC) as the reason. The scene renderer then builds
  `CopyEffects(prefer_raster = true)` (`renderer_scene_render_rd.cpp` 1705).
- The canvas back buffer, used by `BackBufferCopy`, `SCREEN_TEXTURE` blur and
  mipmaps, is copied with `copy_to_fb_rect` and blurred with raster passes on
  Mobile. On Forward+, compute `copy_to_rect` and a compute Gaussian blur do the
  same work (`storage_rd/texture_storage.cpp` 4130-4145).
- Render-target colour textures carry STORAGE usage under both renderers
  (`texture_storage.cpp` 481, 3442). Mobile therefore doesn't remove storage
  usage from 2D targets. It removes compute dispatches in the middle of the
  frame. On a tile-based GPU, each switch between raster and compute flushes the
  tile memory to DRAM. Imagination's performance guidance ("PowerVR Performance
  Recommendations") treats needless render-pass breaks and stores as a main cost.
  Mesa's own `pvr` driver doesn't cover this GPU (docs.mesa3d.org/drivers/powervr,
  no D-Series), so the vendor driver is the only option.
- Godot's documentation describes Mobile as having "a lower base overhead than
  Forward+". Mobile has no clustered light culling and no depth prepass, and it
  compiles fewer startup pipelines.
- For 3D, Mobile puts post-processing in a subpass (`using_subpass_post_process`,
  `render_forward_mobile.cpp` 836).
- Godot 4 Android games that don't pick Compatibility run the Mobile renderer
  by default, on the Pixel 10 too. On this driver, Mobile is far better exercised than Forward+
  (INFERRED).

### 1.6 Pixel 10 PowerVR driver reports

- Unreal Engine forum, 2025, "Pixel 10 PowerVR DXT-48-1536 ... crashes in PowerVR
  pipeline destruction path": crashes in `IMG_vkDestroyPipeline` in
  `vulkan.powervr.so`, on newer Android 16 and 17 builds only. Fewer pipeline
  creations and destructions reduce exposure. A warm pipeline cache and Mobile's
  smaller pipeline set both help here (INFERRED).
- Android Authority, "Pixel 10 game emulation test" (article 3605149): Vulkan did
  worse than GL in two of three emulators, and Vulkan with MSAA showed missing
  textures.
- This repo's `docs/development/american-nightmare-pixel-debug.md` documents MSAA
  corruption on the same GPU.
- **Kestrel path** (VERIFIED in repo):
  - `SystemVulkanDriver.java` loads `/vendor/lib64/hw/vulkan.powervr.so` through
    libhybris (`HYBRIS_VULKAN_HAL`).
  - The hybris compat layer emulates BC textures by GPU decode and caps the API
    version at 1.3.
  - `droiddeck-game-env` adds the ARM WSI layer, which presents through X11 DRI3
    and logs `X11 swapchain using DRI3 presentation`.

### 1.7 Accessibility (VERIFIED)

Godot 4.5 added AccessKit. Unless the mode is `disabled`, the Windows display
server creates an AccessKit UI Automation provider for every window
(`display_server_windows.cpp` 6807-6808). A screen-reader poll still runs once per
frame in every mode (`scene_tree.cpp` 211;
`DisplayServerWindows::accessibility_screen_reader_active`, 2831-2844). That poll
is a `SystemParametersInfoA` call and an `OpenMutexW`, which is a wineserver round
trip. PR #168's follow-up mentions a separate `godot_tabtip` fault, 32-39 s into
launches. Whether it is tied to UI Automation is unverified.

## 2. Findings

| # | Finding | Status |
| --- | --- | --- |
| F1 | Godot's D3D12 driver fails on this stack, and the Vulkan driver works | VERIFIED by PR #168 on Adreno; PowerVR unverified |
| F2 | `--rendering-driver vulkan` alone forces Forward+ for any project | VERIFIED (`main.cpp` 2503-2511) |
| F3 | override.cfg next to the exe is honoured by exported games, .NET included | VERIFIED (`project_settings.cpp` 694-701) |
| F4 | Pipeline cache saves only every 3 MiB and at clean exit, so a killed session can lose all of it | VERIFIED save rule; loss INFERRED |
| F5 | 2D first-draw pipeline compiles block the frame in 4.5.1 | VERIFIED (canvas `ubershader_iterations = 1`) |
| F6 | Mobile keeps back-buffer copies and blurs in raster passes and has lower base cost | VERIFIED in source; speed INFERRED |
| F7 | MSAA is unreliable on this GPU | INFERRED from two independent reports |
| F8 | `--accessibility disabled` skips AccessKit's per-window provider | VERIFIED; cost under Wine unmeasured |
| F9 | The project's method and driver can be read from the pack without running it | VERIFIED: implemented and unit-tested against Godot's PCK v2 and v3 and project.binary formats |

## 3. Interventions, ranked

| Rank | Intervention | Expected effect | Cost | Risk | Done |
| --- | --- | --- | --- | --- | --- |
| 1 | Vulkan driver for every Godot 4 game | Game draws at all; no DXIL/dxil-spirv round trip | none | low: already shipped upstream | yes (kept; now explicit) |
| 2 | Mobile renderer for Forward+ projects on PowerVR | Fewer mid-frame compute passes, fewer pipelines, the driver's well-tested Godot path. Guess: a few % to tens of % GPU frame time on post-heavy scenes; little on plain sprite scenes | none | medium: unverified visual differences (Mobile limits the 3D HDR range and lacks some 3D features) | yes, `auto` on PowerVR only |
| 3 | Save the pipeline cache every 0.5 MiB (override.cfg) | Second and later runs skip compiles for pipelines seen before, even after a kill. Fewer `vkCreate/DestroyPipeline` calls on a driver with destroy-path crashes | small file in the game folder; a save every 0.5 MiB of new pipeline data | low; a game's own override.cfg is never touched | yes |
| 4 | `--accessibility disabled` on Godot ≥ 4.5 | Removes AccessKit's UIA provider and its thread from Wine | none | low: screen readers don't work under this stack anyway | yes |
| 5 | Put added args before a user `--` | Godot reads them at all | none | none | yes |
| 6 | Respect launch options (`--rendering-driver` wins; `--rendering-method` only gets the driver) | No abort from a mismatched pair | none | none | yes |
| 7 | Keep in-game MSAA off | Avoids MSAA corruption on PowerVR and the tile-memory cost | user setting in the game | none | documented only (setting lives in the game's save) |
| 8 | Canvas ubershaders, cache save on focus loss | Removes the remaining first-draw stalls | needs an engine patch | not possible for a shipped game | no |
| 9 | `--render-thread separate` | Overlap of scene and render threads | none | high: Godot marks it experimental, with known crashes | no; experiment only |
| 10 | `rendering_device/vsync/frame_queue_size`, swapchain image count through override.cfg | Latency/throughput trade | none | overlaps the perf/two-frames work | no; experiment only |

## 4. Implementation (branch `perf/godot-render`)

`tools/linuxfs/overlay/usr/local/bin/droiddeck-game-env`:

- **Reads the project's own settings.** It parses the Godot pack: `<game>.pck`,
  or a pack embedded at the end of the `.exe`; v2 and v3; `res://` and bare
  paths. It returns `project.binary`'s
  - `rendering/renderer/rendering_method`;
  - `rendering_device/driver[.windows]`;
  - `application/config/name` and the custom user dir;
  - the log path.

  Encrypted packs and files yield only the version. A broken file yields
  nothing, and the fix falls back to the old behaviour.
- **The `godot_vulkan` fix takes `DROIDDECK_GODOT_RENDERER`:**
  - `auto` (the default): Mobile on PowerVR for Forward+ or Mobile projects. On
    other GPUs the project keeps its own method, now explicit. A Compatibility
    or unreadable project gets the old `--rendering-driver vulkan` alone.
  - `mobile` or `forward_plus`: that renderer.
  - `vulkan`: only the driver.
  - `off`: nothing.

  Godot 3 projects are skipped.
- **override.cfg next to the exe.** It is recognised by its first line,
  rewritten only when changed, and written atomically. It is removed with `off`.
  A foreign file is left alone.
- **Launch log** for each Godot launch: the pipeline-cache files and their sizes,
  and the renderer header line from the last run's `godot.log`.

The Kotlin environment editor offers `DROIDDECK_GODOT_RENDERER` as a choice. It
is unset by default, which the script reads as `auto`. Tests:

- `tools/tests/test_game_environment.py`, `GodotRendererTest`: pack parsing,
  choice handling, launch-option precedence, override.cfg lifecycle, user dir and
  log reading, and an end-to-end `main()` run.
- `GameEnvironmentTest.godotRendererIsAnOptionalChoiceDefaultingToAuto`.

## 5. On-device experiment plan

Each run's launch log (`droiddeck-game-env:` lines) shows the choice:

```
droiddeck-game-env: Godot 4.5.1 (project: forward_plus on d3d12): mobile on Vulkan (DROIDDECK_GODOT_RENDERER=auto)
droiddeck-game-env: Godot: pipeline cache saved every 0.5 MiB (override.cfg)
droiddeck-game-env: Godot pipeline cache in .../AppData/Roaming/<dir>: pipelines.mobile.<device>.cache 1.20 MiB
droiddeck-game-env: Godot last run: Vulkan 1.3.x - Forward Mobile - Using Device #0: ImgTec - PowerVR ...
```

1. **Confirm F1 and the project facts.** On the first launch, the first line
   must show `project: forward_plus on d3d12`. If it shows anything else, update
   section 1.1. The second launch must print a `Godot last run: Vulkan ...` line.
   A `D3D12` line there would mean the arguments were lost.
2. **Mobile vs Forward+ (F6).**
   - Runs: the same save, the combat screen and the map screen, 3 runs each of
     `DROIDDECK_GODOT_RENDERER=forward_plus` and of `mobile`.
   - Measure: frame time p50/p99 from the performance HUD or telemetry, and GPU
     busy %.
   - Confirm: the `Godot last run` header must show `Forward+` or
     `Forward Mobile` to match.
   - Refuted if Mobile's p99 is not lower, or if it shows visual differences
     (screenshots of card glow, shader VFX, map fog). In that case, switch `auto`
     to keep the project's method.
3. **Pipeline cache (F4).**
   - Launch, play one combat, then stop the game from Android without quitting.
   - Before this change, the cache line reads `none yet`, or the size is
     unchanged.
   - With it, the size grows by at least 0.5 MiB steps.
   - The next launch's first combat should show fewer frame-time spikes over
     50 ms.
   - If the size stays under 0.5 MiB after a full session, the PowerVR driver
     returns little cache data, and this intervention does little.
4. **Accessibility (F8).**
   - Compare runs with `--accessibility auto` in the launch options (which turns
     this off) against runs with the default.
   - Count `tabtip` and UIA-related lines in the Proton log, and check frame-time
     p50.
5. **Stability.** Over 30 minutes per renderer, count crashes and any
   `IMG_vkDestroyPipeline` frame in logcat tombstones.
6. **MSAA (F7).** Set in-game MSAA to 2x, then 4x. Look for missing textures and
   for the frame-time cost.

## 6. Risks

- Mobile on StS2 has not been seen on any device here. Mobile's HDR range and some
  3D features differ from Forward+. `DROIDDECK_GODOT_RENDERER=forward_plus`
  for the game restores the old behaviour in one setting.
- The override.cfg file lands in the Steam game folder. Steam's file
  verification doesn't remove extra files. `off` deletes it.
- On non-PowerVR GPUs, `auto` now passes the project's own method explicitly. A
  Mobile project therefore runs Mobile there, where before it was silently
  forced to Forward+. This is a behaviour change, and a correct one.
- Compatibility projects still get the upstream `--rendering-driver vulkan` and
  so Forward+. This is kept on purpose to avoid regressing games that work today.
