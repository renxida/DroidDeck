# From the game's renderer to the panel: what caps Slay the Spire 2 at 45 fps on the Pixel 10a, and better paths

**Question.** On the Pixel 10a (Tensor G4, Mali-G715, Android 17, 120 Hz) the native engine draws the main
menu at 41 to 45 fps with its main thread under half busy, and Proton does the same at 47. What in the path
from Godot's swapchain to the panel holds it there, what would a much better path look like, and how do we
measure frame time and input-to-screen latency well enough to choose?

**Short answer.** The guest Vulkan driver presents through Mesa's *software* window-system path. PanVK on
kbase starts Mesa's WSI as a software device unless `PANVK_KBASE_DRI3` is set, and nothing in Lanner sets it.
On that path every `vkQueuePresentKHR` copies the 1920x1080 frame to a buffer on the GPU, **waits on the
game's thread for the GPU to finish the frame**, copies 8.3 MB to a `wl_shm` buffer with the CPU, and gamescope
uploads the same 8.3 MB into a texture again. CPU work and GPU work therefore never overlap: one frame is
"10.5 ms of CPU, then 11.8 ms blocked", which is the 22.3 ms median in the logs, on a menu of three sprites.
The switch to the driver's dma-buf path is one environment variable; it is built here as experiment
`present-dmabuf`, off by default because no phone was available to prove it. Expected: 60 fps or more at the
menu with p99 under 20 ms, and about 20 ms less input latency. Beyond that, the path still has two
compositors and three full-screen GPU passes between the game and SurfaceFlinger that a purpose-built
presenter can remove (section 5).

Nothing in this note was measured on a phone by me: no phone or adb was used. Marks: **VERIFIED** = read in
source at the cited revision, or in the three phone runs of `mali-10a-native-1.md`
(`~/projects/droiddeck/output/mali-10a-native/{native-1,native-2,proton-control}`); **INFERRED** = reasoned
from verified facts; **UNVERIFIED** = from memory or a secondary source.

## 1. The frame path today (native engine, Pixel 10a)

| # | Stage | What happens | Copies, waits, queues |
|---|---|---|---|
| 1 | Godot 4.5.1, Forward+, main thread | Draws the 2D scene into a 1920x1080 swapchain image. The game sets a 1920x1080 fullscreen window (`WINDOW_SIZE: (1920, 1080)`, native-engine.log) while the session output is 1616x720; gamescope patch 0130 grows the game's X screen to fit (`Updating mode for xwayland server #1: 1920x1080@120`). VERIFIED | `frame_queue_size` 2, 3 swapchain images, V-Sync "Enabled" (Adaptive refused). VERIFIED |
| 2 | gamescope's WSI layer (`VkLayer_FROG_gamescope_wsi`) | Replaces the X11 surface with a `wl_surface` on gamescope's own socket (Xwayland is not in the frame path) and always asks the driver for MAILBOX; FIFO is done by gamescope latching one commit per vblank. There is no wait in the layer's present. VERIFIED (layer source 3.16.29, lines 1261-1306, 1547-1601) | Back-pressure only through image availability, which never binds at 45 fps on a 120 Hz gamescope. INFERRED |
| 3 | PanVK on kbase, Mesa WSI **software path** | `wsi_device_init(.sw_device = uses_kbase && !kbase_dmabuf)`, and `kbase_dmabuf` needs `PANVK_KBASE_DRI3` (panvk-kbase-android `kbase-common/006`, VERIFIED; the driver pack is built from that tree at 9be6dd6, VERIFIED in `droiddeck-nonadreno-drivers/.github/workflows/panvk-mali-kbase.yml`). Lanner sets no `PANVK_KBASE_DRI3` (VERIFIED: no occurrence in the repo). So: `WSI_WL_BUFFER_SHM_MEMCPY`; present submits a GPU copy image→buffer, then `if (wsi->sw) WaitForFences(…, ~0)` on the calling thread, then `memcpy` into the shm buffer (Mesa 5a07217f `wsi_common.c` 2882-2885, `wsi_common_wayland.c` 3335-3337, 3893-3898, VERIFIED) | 1 GPU copy (1080p), **1 blocking wait for the whole frame's GPU work**, 1 CPU copy of 8.3 MB, all on the game's main thread |
| 4 | gamescope (PanVK again), `--backend wayland` | A `wl_shm` commit is uploaded into a texture on every commit (patch 0927): a second 8.3 MB CPU copy plus a GPU buffer→image copy, on `gamescope-xwm` (25.8 % of a core in the window, VERIFIED). With one fullscreen layer and no filter that needs it, the Wayland backend does not composite: it hands each layer to the host as its own surface with a `wp_viewport` (WaylandBackend.cpp 1083-1200, VERIFIED; the app logs 7 empty attaches per game frame = the 7 unused planes, VERIFIED) | 1 CPU copy, 1 GPU copy; latches on its own timerfd vblank at the host's refresh (patch 0120); average wait half a refresh |
| 5 | Lanner's compositor (`waylandcomp`, the phone's own Mali driver) | Imports gamescope's dma-buf and blits it into a FIFO Android swapchain, 2424x1080, on its Choreographer tick: `render_scene` 0.83 ms, present 0.5 ms, every frame "copy" (VERIFIED, `[perf]` lines) | 1 GPU blit with scaling; waits for the next tick (0.95 ms average after arrival, VERIFIED); swapchain min+1 images, "two frames in flight" |
| 6 | SurfaceFlinger, HWC | The SurfaceView layer at 120 Hz (`refreshRates: [120]`, VERIFIED) | Latch at the next vsync, scanout one refresh later (typical BufferQueue path; INFERRED) |

Per game frame that is two 8.3 MB CPU copies, four full-screen GPU passes after the game's own (copy to
buffer, upload, the app's blit, and SurfaceFlinger's if the layer is not on an overlay plane), one blocking
GPU wait on the game's thread and three latch points (gamescope vblank, the app's tick, SurfaceFlinger).

The Proton path differs only above stage 2 (winevulkan in front of the same layer and driver), which is why
it lands on the same frame time (INFERRED from the shared stages; VERIFIED that both use the same ICD).

### The input side

| # | Stage | Where time can go |
|---|---|---|
| a | Touch controller / pad to Android's input dispatcher | Outside the app. `requestUnbufferedDispatch` is on (low latency default), so events are not held for the next frame. VERIFIED (`SessionActivity.kt` 4312-4325) |
| b | Touch (Steam Big Picture's DIRECT route): `DirectTouchHold` | The **down is held back** until a long-press is ruled out unless `quick-tap` is on (it is by default); the up is delayed 20 ms (quick-tap) or 50 ms. Timestamps are `event.eventTime` in ms. VERIFIED (`input/DirectTouchHold.kt`) |
| c | Touch: `nativeSendTouch` → pipe → compositor thread → `wl_touch` → gamescope (patch 0110) → Xwayland → the game's X11 events | One pipe hop, flushed at once; no coalescing. gamescope replaces the timestamp with a counter. Xwayland is in the input path even though it is not in the frame path. VERIFIED |
| d | Pad: `PadBridge` → `FakeInputWriter` ring (mmap) → `fakeinput_steam.cpp` in the Steam client | The Deck-emulation report thread samples the ring every 1 ms (low latency) or 4 ms; then Steam Input turns it into a virtual pad, re-published through a faked uinput into another ring; the game's reads poll at 250 µs to 2 ms. Two polling stages plus Steam Input's own loop. VERIFIED (`fakeinput_steam.cpp` 1511-1535, 3752-3869) |
| e | Godot reads input once per frame, at the start | Up to one frame time of wait (22 ms today), 11 ms on average |
| f | The frame that shows the answer | CPU 10.5 ms + blocked 11.8 ms today (stages 1-3) |
| g | gamescope latch, upload, hand-over | 0 to 8.3 ms to its vblank, plus the upload |
| h | Lanner's compositor tick, blit, present | 0 to 8.3 ms to the tick, about 1.5 ms of work |
| i | SurfaceFlinger latch and scanout | 1 to 2 refreshes (8 to 17 ms) |

Adding e to i gives roughly 60 to 75 ms from the input reaching the guest to the panel starting to show the
answer, before the touch controller and the panel's own response (INFERRED; the probe in section 6 measures
b to i).

## 2. Why 45 fps: the evidence

1. **The intervals are not quantised.** `frames.csv` (gamescope's commit times) in the 120 s window is one
   smooth hump: native-1 median 22.3 ms, 90 % between 18.5 and 27 ms; native-2 24.4 ms; Proton 21.1 ms.
   A limiter gives a spike at its period; a vsync divisor on a 120 Hz output gives 16.7 or 25.0. Only on
   screen do they become 16/24/25/33 (`pacing.json`), because a free-running 22 ms producer is sampled by
   8.3 ms refreshes. So nothing is pacing the game: it is as fast as its own loop. VERIFIED
2. **The loop is half waiting.** `native_compat: frame split … main thread cpu 10.54 ms (busy 47 %)` for 3
   sprites, 3 batches, 200 vertices, at 22.3 ms per frame: 11.8 ms per frame the main thread is blocked.
   VERIFIED. Stage 3 names the wait: the software WSI's `WaitForFences` on the frame's own GPU work.
   INFERRED (it is the only blocking call in a present on this path, and Godot's own fence wait is for the
   frame two back, which cannot bind when the GPU keeps up).
3. **10.5 ms of CPU for three batches** is itself the same path: the 8.3 MB `memcpy` out of GPU memory per
   frame and the driver's copy submit run on that thread. INFERRED (Venus on the same phone family measured
   1.3 to 10 ms for the same copy, `mali-venus.md`).
4. **gamescope's 26 % of a core** on `gamescope-xwm` with nothing to composite matches a per-frame 8.3 MB
   upload (373 MB/s at 45 fps). INFERRED. Its fence log reads `450 ready at import, 0 waited for` in every
   window while the game runs: a shm buffer has no fence to wait for. VERIFIED (consistent, not proof: a
   finished dma-buf reads the same).
5. **Both engines converge.** Proton's game thread is busier (87 % of a core) and native's is idler (58 %),
   yet they end 2.5 fps apart. A shared serial section explains that; a CPU limit does not. VERIFIED numbers,
   INFERRED reading.
6. Upstream's own Venus prototype on a Pixel 9 Pro, which uses the same software present by design
   (`MESA_VK_WSI_DEBUG=sw`), ran this game "at about 40 fps" (`mali-venus.md`). VERIFIED as a record.

What this does **not** establish: how long the GPU itself needs for a 1080p Forward+ menu frame on PanVK. It
is at most 11.8 ms (the blocked time includes the 1080p copy-to-buffer). If it is near that, removing the
serialisation gives about 85 fps; if PanVK's real cost is 4 ms, the game runs into gamescope's 120 Hz.
`vk-count` (section 6) reads this directly: "in present p99" near 11 ms on the current path is the proof,
and its drop to under 1 ms with `present-dmabuf` is the confirmation.

Ruled out: a frame limiter (`fps limit -1`, no cap logged, no spike); the app's compositor (0.83 ms, every
frame shown on arrival); SurfaceFlinger's rate (120 Hz throughout); thermal caps (none); memory pressure on
the native path (690 to 800 MB available); a present-mode divisor (intervals are continuous).

## 3. What was built

All on `perf/direct-present`. Nothing changes unless a switch is turned on.

| Piece | Switch | What it does |
|---|---|---|
| **`present-dmabuf`** | Settings > Advanced > Experiments, tag `setting-exp-present-dmabuf`, `DROIDDECK_EXP_PRESENT_DMABUF` | For a game on PanVK/kbase (native engine, its prestart, and Proton) sets `PANVK_KBASE_DRI3=1`: Mesa's WSI is then a normal device, swapchain images are dma-heap dma-bufs given to gamescope as they are, with no copy, no CPU wait in present and no upload in gamescope. Adds `wsi_no_afbc` to `PANVK_DEBUG` so the buffers are linear, which the app's compositor is known to import; `DROIDDECK_PRESENT_DMABUF_AFBC=1` leaves the driver's AFBC default for a second test. Steam's own UI is not changed |
| **`vk-count` on the open driver** | existing tag `setting-exp-vk-count` | The counting layer only reached PowerVR launches. It now loads for native and Proton games on an open driver pair, prints one `droiddeck-vkcount: swapchain WxH … present mode … min images … got N` line per swapchain, and adds `present interval ms: p50 p95 p99 max; in present: p99 max` to its 10 s report next to the existing submit / present / acquire / fence-wait split |
| **`droiddeckctl latency`** | Debugging tools | Input-to-response latency with a distribution, section 6 |
| **`[path]` line and `frames.focus` fields** | always | The compositor logs, when it changes, `game frames 1920x1080 XR24 linear dma-buf -> screen 2424x1080, 4 buffers, FIFO, two frames in flight, display timing on, copied into the screen swapchain, vsync ticks at 120 Hz`; the `frames.focus` event carries it with `output` and `panelHz` |

`present-dmabuf` is not made the default: the dma-buf path of this driver build has never run under gamescope
on this phone, the driver's authors left it off because "some Android X servers advertise DRI3 while rejecting
PixmapFromBuffer", and the implicit-sync step (attaching the render fence to the dma-buf through a kbase KCPU
queue, their patch 075) is the part most likely to misbehave. Failure would look like a black or flickering
game, half-drawn frames, or "failed to import sync file" in the game's log; the native path's first-frame
watchdog then falls back to Proton, which has the same switch on, so turn it off to recover.

Not built, and why: a gamescope patch that logs whether the focused window's buffers are shm or dma-buf and
whether it composited. It needs a new gamescope component (the workflow publishes under a tag and
`tools/gamescope/release.env` pins it); the app-side `[path]` line plus gamescope's existing
`commit fences` line and `vk-count`'s "in present" answer the same question without one. The existing
`DROIDDECK_ENHANCED_DIAGNOSTICS=1` frame trace (patch 0900) already counts latches and output attaches.

## 4. Alternatives, with their effect on frame time, latency and memory

Expected effects are for the 10a menu and are INFERRED unless marked. "Latency" is input-to-screen.

### 4.0 Fix the present path the driver already has (built: `present-dmabuf`)

- Frame time: from CPU + GPU in series (22 ms) to the larger of the two, about 9 to 12 ms; 60 fps held with
  margin, p99 expected under 20 ms. Latency: about 20 ms less (a 12 ms shorter frame, half as long a wait for
  the next frame start, no upload before gamescope's latch).
- CPU: about 6 ms per frame less in gamescope and 2 to 8 ms less in the game. Memory: three 8.3 MB shm
  buffers and gamescope's upload textures go; small.
- Risk: medium, all visible at once (see above). Size: done; one phone session to verify.
- Follow-ups in the same family: the same variable for the Steam client's own swapchains (its Xwayland holds
  0.8 to 1.2 GB of GPU memory and goes through the same software path, INFERRED), and AFBC swapchain images
  once linear works.

### 4.1 (a) Godot's Wayland display server straight to gamescope; gamescope direct scanout

- Godot 4.5.1 has a Wayland display server (`--display-driver wayland`), built by default; it needs only
  `wl_compositor`, `wl_shm`, `xdg_wm_base`. gamescope's client socket offers those but no `wl_subcompositor`,
  no `wp_viewporter`, no `wp_fifo_manager_v1`, so Godot runs with "FIFO protocol not found! Frame pacing will
  be degraded": MAILBOX plus frame-callback pacing. VERIFIED in both sources (godot `wayland_thread.cpp`
  4342-4366, `display_server_wayland.cpp` 1369-1400; gamescope `wlserver.cpp` 2209-2303).
- It buys nothing in the frame path: gamescope's WSI layer already bypasses Xwayland for frames. It would
  take Xwayland out of the *input* path (stage c), worth perhaps 1 ms, at the cost of gamescope's X11-based
  focus, overlay and Steam window logic for that window. The earlier `wayland-present` experiment hit the
  missing `wl_subcompositor` with Wine. Not recommended.
- gamescope "direct scanout" is already what the nested backend does (stage 4) once the client's buffers are
  dma-bufs. After 4.0 the game's own buffer reaches Lanner's compositor untouched. VERIFIED in source.

### 4.2 Match the render size to what is shown

- The game renders 1920x1080 (2.07 MP) and the panel shows it at 1920x1080 inside 2424x1080, so nothing is
  thrown away today; but the session's output is 1616x720, and if the GPU turns out to bind after 4.0, the
  cheapest lever is rendering at 1280x720 (0.92 MP, 44 % of the pixels) and letting the one blit in Lanner's
  compositor scale it. Frame time: GPU cost roughly halves. Latency: follows frame time. Memory: render
  targets about 30 MB less.
- The game sets its own window size from `settings.save`, which Lanner must not edit. The clean way is in
  our engine build (`native_compat` clamping the window to the output's 16:9 fit under an environment
  switch), which needs an engine release through `native-engine-build.yml` and the startup gate. Size: small
  patch, one engine build. Do it only if `vk-count` shows the GPU is the limit after 4.0.

### 4.3 (d) Renderer, refresh-rate vote, frame cap

- **Mobile renderer** (`DROIDDECK_NATIVE_RENDERING_METHOD=mobile`, already in the engine): same canvas
  renderer, no clustered setup, 64 instead of 201 pipelines at start, RGB10_A2 instead of half-float 3D
  buffers. For a 2D frame the per-frame gain is small; "worse on PowerVR" was a p99 result on a driver with
  different tile-flush costs and should be re-run on Mali after 4.0. Godot's own docs call Forward+ "suited
  for desktop platforms only" (VERIFIED, docs 4.5 renderers page). Risk low, size zero (a switch exists).
- **Compatibility (GLES3)**: would run on Zink over PanVK here, a second translation layer on a
  non-conformant driver; no 2D MSAA or HDR 2D; shader variants in the shipped pack are untested with it
  (UNVERIFIED whether the exported pack even loads). Not recommended in the container. It is the natural
  renderer only for option (e), on the phone's own GLES driver.
- **Refresh vote.** Today the layer stays at 120 Hz and a 45 fps producer gives the 16/24/25/33 mix. Once
  the game holds 60, asking SurfaceFlinger for 60 (`setFrameRate(60, COMPATIBILITY_DEFAULT)` on the layer,
  which `sc_layer.c` can already do, or a 60 Hz display mode through `PerfMode`) makes every interval 16.7 ms
  and saves panel power. Google's guidance for a multiple (60 on 120) is to keep 120 and give each frame a
  presentation time (VERIFIED, developer.android.com frame-rate guide). Latency cost of a true 60 Hz panel
  mode against 60 fps shown on 120 Hz: up to 8.3 ms more at the latch (average 4 ms). So: **vote 120, cap the
  game at 60 or 90 with one limiter, and present with timestamps** is the low-latency choice; a 60 Hz mode is
  the battery choice. With 4.0 working and the GPU allowing 90, a 90 fps cap on a 90 Hz mode (if the panel
  offers one; UNVERIFIED for the 10a) beats both.
- **Swapchain depth.** Godot's 3 images and `frame_queue_size` 2 cost nothing while the GPU keeps up;
  gamescope latches one commit per vblank. With a cap, keep `GAMESCOPE_WSI_MIN_IMAGE_COUNT` at 3 (as
  `frame_cap` already does): each extra queued image is one frame of latency.

### 4.4 (b) The game presents straight to an Android surface: a purpose-built presenter

The owner's scope note applies here: gamescope and Lanner's blit are legitimate rewrite targets.

After 4.0 a game frame still passes gamescope (latch, re-attach as a subsurface) and Lanner's compositor
(import, blit with scaling into a FIFO swapchain) before SurfaceFlinger. Two designs remove those:

**B1. Zero-copy display layer for the game's buffers (smallest).** Lanner's compositor already has
`sc_layer.c`: an `ASurfaceControl` child layer fed with `AHardwareBuffer`s, with frame-rate votes and
completion callbacks, used on Quest. It needs the game's buffers to *be* gralloc buffers
(`ZERO_COPY_SPIKE.md`: a dma-heap fd cannot be turned into an AHB). The kbase PanVK tree already carries
"android-surface-swapchain" and "android-swapchain-owned-ahb" WSI patches and AHB import. The missing piece
is allocation: a glibc process cannot call `libnativewindow`. So: Lanner's compositor allocates the
swapchain's AHBs (3 per swapchain), sends the dma-buf fds, stride and modifier over the existing private
`banner-ahb-v1` protocol, and a small Vulkan layer (or a Mesa WSI patch) under gamescope's layer imports
them as the swapchain's images. Then each frame is one `ASurfaceTransaction_setBuffer` with the render
fence, a `setDesiredPresentTime`/`setFrameTimeline`, and SurfaceFlinger (usually the display's overlay
plane) scales it.
  - Removes: Lanner's blit and its tick wait (stage 5), and the app's 120 Hz GPU work. gamescope stays as a
    window manager and still forwards the buffer handle, so its latch stays.
  - Frame time: no change to the game's; frees GPU time (one 2.6 MP blit per frame) and about 170 MB of
    Lanner's GPU memory for the scene swapchain. Latency: about one refresh less (4 to 8 ms).
  - Keeps: Steam overlay and toasts (as a second layer above, which `sc_layer` supports as "overlay"),
    input, focus, return to Steam, picture-in-picture (the layer is a child of the same SurfaceView),
    rotation.
  - Size: 2 to 3 weeks: compositor-side allocator and protocol (parts exist), the layer or WSI patch in the
    driver pack (a driver rebuild in another repo), fence plumbing both ways, fallback to the blit.
    Risk: gralloc layout and modifier agreement between Arm's gralloc and PanVK (AFBC flags), and kbase
    import of gralloc buffers (the kbase tree notes "never guess `native_handle_t->data[0]` as the dma-buf").

**B2. A single-client presenter in place of gamescope while a game has focus (bolder).** A Vulkan WSI layer
in the game process (replacing gamescope's layer for the game only) talks to Lanner's compositor directly
on `wayland-0`: it creates the swapchain from compositor-owned AHBs as in B1 and commits straight to it.
gamescope keeps running for Steam's UI but is not in the game's frame path.
  - Removes: gamescope's latch and re-attach (stage 4) as well as stage 5: the path is Godot → PanVK →
    SurfaceFlinger. Latency: about two refreshes less than today's path after 4.0 (8 to 17 ms), and pacing
    can be done properly once, in one place: the layer blocks `vkAcquireNextImageKHR` on the previous
    buffer's release from `ASurfaceTransaction` back-pressure, and timestamps each present on the
    Choreographer frame timeline. P99 gains come from removing two independent clocks (gamescope's timerfd
    and the app's tick) that each can add a refresh when a frame lands just after them.
  - What gamescope gives the game today and this must replace or deliberately drop:
    input and focus (the game's X11 window still lives on gamescope's Xwayland, so pointer, touch and
    keyboard keep working as now; only the picture moves); the Steam overlay (Shift+Tab UI is drawn by the
    Steam client in its own window and composited by gamescope: keep it by showing gamescope's output as a
    second SurfaceFlinger layer above the game whenever gamescope reports an overlay or a second focused
    window, and hide it otherwise); performance overlay (mangoapp: dropped while direct, the app's own HUD
    remains); frame limiter and Steam's Frame Limit slider (the layer must read `GAMESCOPE_LIMITER_FILE`,
    as the vkcount layer's pacing already reads a rate file); screenshots and Remote Play capture through
    gamescope's PipeWire (would show a black game unless the layer also commits to gamescope at a low rate:
    drop while direct, document); suspend/resume and return to Steam (when focus leaves the game the layer
    falls back to presenting through gamescope, or the compositor simply shows gamescope's layer on top).
  - Size: 5 to 8 weeks for a path that survives focus changes, resize, rotation, PiP and suspend, with the
    off-phone rig able to test the protocol on lavapipe. Risk: high on the edges (focus hand-over without a
    black frame), low on the core (it is B1 plus a direct connection).

Order: B1 first (it is a subset of B2 and measurable by itself), B2 only if the probe shows the gamescope
hop is worth a refresh.

### 4.5 (c) The phone's own Mali driver for the game

- Speed: Arm's driver has AFBC render targets, mature tiler heuristics and native ASTC; upstream says PanVK
  on v11 "is not well-tested" and gates it behind `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER` (VERIFIED,
  `panvk_physical_device.c` 400-411; v11 support first shipped in Mesa 26.3). No published PanVK-against-DDK
  benchmark exists (VERIFIED absence in Collabora's and Phoronix's posts). For a 2D frame the raster cost
  difference is probably modest; the larger gains are memory.
- Memory: the game holds 1.2 to 1.4 GB of GPU memory on PanVK, where BC textures are decoded to AFBC/AFRC
  shadows (the "BC emulation on, lowmem" line). On the phone's driver BC is not supported either, so the win
  needs ASTC textures (`tex-astc` exists) in both cases; then both drivers hold the same ASTC data. The
  proprietary driver's advantage is steadier memory accounting, not a different texture size. INFERRED.
- Through Venus or the Mali bridge the present path is the software path again by construction
  (`MESA_VK_WSI_DEBUG=sw` for Venus), plus a serialisation hop per Vulkan call: it re-creates the problem of
  section 2. The other agent is A/B-testing those switches; I expect them not to beat `present-dmabuf`.
- Running the renderer against bionic is option (e).

### 4.6 (e) The engine as an Android process

What it would be: Godot 4.5.1's Android platform with the mono module, loading the owner's installed
`SlayTheSpire2.pck` and managed assemblies from the container's Steam library, drawing with the phone's own
Vulkan (or GLES) driver into a `SurfaceView`, with Steamworks served by the real Steam client.

Facts:
- The game is Godot 4.5.1 (VERIFIED: the engine line in native-engine.log) with .NET; `sts2.dll` is
  architecture-neutral IL (VERIFIED in `native-engine.md`).
- Godot's Android C# support is "experimental", arm64 and x64 only, Mono runtime (`libmonosgen-2.0.so`)
  through the hosting API, assemblies normally embedded in the APK, TFM net9.0 for template export
  (VERIFIED, godot `export_plugin.cpp` 2779-2785, `gd_mono.cpp`). `--main-pack` with a path in app storage
  is supported by the Android host (VERIFIED, `Godot.kt` 306-338).
- A community port does exactly the engine half: custom Godot 4.5.1 Android + Mono + Harmony patches,
  loading the Windows depot's pack and DLLs (VERIFIED from its README and `native-engine.md`). It replaces
  Steamworks with no-op stubs and logs in to Steam itself with SteamKit2. **Neither is acceptable here**:
  nothing about ownership or Steam may be stubbed, and Lanner never handles credentials.
- What carries over from our engine: the `native_compat` module (skeleton and audio stand-ins) is engine
  C++ and builds for any platform; the canvas, pipeline-cache and logging patches are platform-neutral; the
  X11 patch does not apply. The wrapper's composition of the launch folder (relabelling two files,
  ReadyToRun) does not carry over: Android needs Mono-compatible assemblies and has no CoreCLR R2R.
- The hard part is Steam. Today the game's Steamworks.NET calls Valve's glibc `libsteam_api.so`, which
  loads `steamclient.so` in-process and talks to the running client; our bridge (`steam_bridge.c`, 736
  lines) sits in front of it in the same process. A bionic process cannot load those libraries. The bridge
  would have to become a remote one: a bionic `libsteam_api.so` shim exporting the 952 entry points the
  game's Steamworks.NET mentions, forwarding each call over a socket to a glibc helper in the container
  that hosts Valve's real library, with callbacks and call results pumped back, and pointer-carrying
  structs marshalled by hand. That is honest (every answer still comes from the real client) but it is a
  large, error-prone piece: remote storage (the saves) and stats are the paths that must be exact.

Effect: frame time, the phone's driver with no window-system hops at all (Godot's Android swapchain is a
`BufferQueue` straight into SurfaceFlinger): the best case of every option. Latency: Android's input
straight into Godot, no ring, no Steam Input, no Xwayland: the best case too, about 3 refreshes at 120 Hz.
Memory: no gamescope or Xwayland for the game; ASTC native.

Losses: Steam overlay and Steam Input remapping for the game (the overlay cannot draw into an Android
surface it does not know); Mono instead of CoreCLR (the earlier .NET work measured tiered JIT and GC
settings on CoreCLR; Mono's JIT is slower on hot managed code, UNVERIFIED by how much for this game); a
second engine to keep in step with game updates.

Size, honestly: 3 to 4 months for one person to reach "plays a run and saves correctly", most of it the
remote Steam bridge and Mono compatibility; and it replaces a working native path whose remaining problem is
the present path, which sections 4.0 and 4.4 fix for a fraction of that. **Not recommended now.** Revisit
only if PanVK itself proves to be the limit after 4.0 and 4.2.

## 5. Ranked recommendation for the 10a

| Rank | Step | Expected at the menu | Latency | Risk | Size |
|---|---|---|---|---|---|
| 1 | `present-dmabuf` on (built) | 45 → 60+ fps, p99 30 → under 20 ms | about −20 ms | medium, visible at once | one phone session |
| 2 | Read `vk-count` and `latency` on that run; pick a cap (60 or 90) with one limiter, layer vote 120 | even 16.7 ms cadence instead of the 16/24/25/33 mix | flat | low | switches exist |
| 3 | Same variable for the Steam client; AFBC swapchain | less Steam-side GPU memory and CPU (INFERRED) | none | medium | small |
| 4 | Render at the output's 16:9 fit if the GPU binds | GPU time roughly halved | follows frame time | low | one engine build |
| 5 | B1: game buffers on their own display layer | frees one 2.6 MP blit per frame | −4 to −8 ms | medium | 2 to 3 weeks |
| 6 | B2: presenter without gamescope in the frame path | removes two latch clocks: better p99 | −8 to −17 ms | high at the edges | 5 to 8 weeks |
| 7 | Android-native engine | best case | best case | very high | 3 to 4 months |

Confidence: high that the software present path is in use and serialises the frame (two sources agree and
five observations fit); medium (about 60 %) that `present-dmabuf` works on the first try on this phone;
if it does, high that the menu passes 60 fps.

## 6. Instruments

### 6.1 `vk-count` (game side)

With `vk-count` on, `native-engine.log` (native) or the game's log under `games/` (Proton) gets, every 10 s:

```
droiddeck-vkcount: swapchain 1920x1080 format 44, present mode FIFO (2), min images 3, got 3, result 0
droiddeck-vkcount: window 10.0s 449 frames 44.9 fps | … | ms/frame: submit 0.31 present 11.60 acquire 0.02
  fence-wait 0.05 idle 0.00 pace 0.00 | … | present interval ms: p50 22.5 p95 27.0 p99 30.0 max 39.2; in present: p99 14.5 max 21.0 | …
```

(the numbers above are what section 2 predicts, not a measurement). `present` is the per-frame average time
inside `vkQueuePresentKHR`: about the GPU's frame time on the software path, under 1 ms on the dma-buf path.

### 6.2 `droiddeckctl latency` (input to screen)

Design: `docs/agent-control.md` has the usage. In short:

- **Injection.** The app injects each input itself (`AgentLatency`), on the same code paths as
  `droiddeckctl input`: a pad button through `PadBridge` and the pad ring, a key or touch through the
  compositor's input pipe. The timestamp is `SystemClock.uptimeNanos()` taken just before the input is
  handed over (CLOCK_MONOTONIC, the clock of every later stage). A random 0 to 16 ms is added between inputs
  so they do not lock to the refresh.
- **Detection.** `response_probe.c` in Lanner's compositor. Each new game frame's watched region is reduced
  to an 8x8 grid of mean colour sums from 4096 pixel reads through a CPU mapping of the frame's dma-buf (or
  the shm data): no GPU readback, no screenshot. While idle it tracks the region's own frame-to-frame
  difference over the last 30 frames (its noise); after an input, the first frame differing from the
  pre-input frame by more than `max(12, 4 x noise)` is the answer. While an input is open the read first
  waits up to 8 ms for the buffer's fence, so a frame still rendering is not misread.
- **Timing.** For the answering frame: when the compositor received it (commit), when the compositor queued
  the screen frame carrying it (`vkQueuePresentKHR`), and when the panel showed it
  (`VK_GOOGLE_display_timing`'s `actualPresentTime`, when the phone's driver has it; the summary says
  `endsAt: display` or `present`). It also records the first frame of any content (the old `latency.csv`
  meaning) and how many frames the answer took.
- **Output.** `toScreen`, `toChangedFrame`, `toFirstFrame`, `framesToChange` as n, min, p50, p95, p99, max;
  status counts; `noise` and `smallestDelta` for judging the region. `--rows` adds every sample.
- **Safe observable.** At the game's main menu: `--input pad:down --input pad:up` with a region around the
  first two menu entries (left part of the picture; exclude the animated background). The focus highlight
  moves and moves back; nothing is selected, so no save, setting or run is touched. In Steam's UI: the same
  d-pad moves on the library row. Not `pad:a`, not a tap on a button.
- **Limits.** It starts after Android's input dispatch and ends at the display-timing timestamp: the touch
  controller's scan and the panel's response are outside it. It needs linear 32-bit buffers (with AFBC
  swapchain images it reports `unreadable`). On a zero-copy display layer there is no present id, so the row
  ends at the present.
- **Validating the probe.** (1) `tools/tests/test_response_probe.py` drives the core with synthetic frames:
  an animated band, a highlight that appears on the third frame, timeouts, unreadable frames. (2) Coarse
  cross-check on the phone: `droiddeckctl record` during a run and count frames between the on-screen pad
  button lighting and the menu highlight; it should agree within one recorded frame (screenrecord is 60 fps
  at best, so ±17 ms). (3) `toFirstFrame` must always be ≤ `toChangedFrame`, and `framesToChange` should be
  1 or 2; a p50 of 4 or more means the region is catching something later than the first response. (4) A
  240 fps phone camera on the screen and a finger is the only check that includes stages a and the panel.

## 7. On-phone test plans

Rules as in the earlier notes: phone charging and idle, Steam offline, sound off (game sound, boot-movie
sound, volume 0), saves backed up, no run started, `scripts/` from `mali-10a-native` for the pre-test
(`pre.sh`), the 120 s window (`measure.sh`) and the experiment switches (`setexp`). Debugging tools on (by a
tap on the phone) for `latency` and for reading `native-engine.log`.

### Plan A: baseline with the new instruments (no behaviour change)

```
scripts/setexp native-engine On; scripts/setexp vk-count On
droiddeckctl start steam; droiddeckctl wait ready; droiddeckctl launch 2868840
# at L+240 s, menu showing, prompt answered:
droiddeckctl pacing --seconds 120 > pacing.json
droiddeckctl latency --input pad:down --input pad:up --region 0.03,0.42,0.30,0.22 --count 60 --rows > latency.json
droiddeckctl screenshot menu.png          # to check the region covers the two menu entries
droiddeckctl quit 2868840; droiddeckctl stop; droiddeckctl logs latest logs/
```

Record: `frames.csv` fps/p50/p99, `pacing` histogram, `droiddeck-vkcount:` lines (swapchain line; "present"
and "in present p99"), the `[path]` line in `app.log`, `latency.json`. Expected if section 2 is right:
`present` 10 to 13 ms per frame, `[path]` shows a 1920x1080 linear dma-buf from gamescope, `toScreen` p50 60
to 80 ms with `framesToChange` p50 1 to 2. If `present` is under 2 ms the diagnosis is wrong: stop and read
the fence-wait and acquire columns instead. Repeat once on Proton (`native-engine` Off) for the Proton
against native latency baseline.

### Plan B: `present-dmabuf`

```
scripts/setexp present-dmabuf On      # tag setting-exp-present-dmabuf; game-level, no new session needed
```

then Plan A's commands. `session.log` must say `present-dmabuf: PanVK swapchain images are dma-bufs (linear),
no CPU copy or GPU wait in present` (native: in `native-engine.log`'s notes or `session.log`).

| Check | Pass | Fail |
|---|---|---|
| Picture | menu looks as before in the screenshot, no flicker over 60 s of `record` | black, torn or half-drawn frames; "failed to import sync file"; fallback to Proton |
| `vk-count` "present" | under 1.5 ms per frame | still near the GPU time |
| `frames.csv` | ≥ 58 fps, p99 ≤ 20 ms, no frame over 50 ms | under 50 fps |
| `pacing` | off-cadence under 15 % with a 60 cap (step 2), fps on screen = fps | — |
| gamescope CPU (`processes.csv`) | under 12 % of a core | unchanged at 26 % |
| `latency toScreen` | p50 at least 15 ms lower than Plan A, p99 under 70 ms | not lower |
| GPU memory (`dumpsys gpu --gpumem`) | game within ±50 MB of Plan A | growth over 100 MB |

Regressions to walk through with it on, each compared with it off: Steam overlay (Steam button over the
game, then back: picture returns, input returns); a toast if one can be provoked; touch and pad input on the
menu; Quit to Steam and relaunch; app to background 30 s and back; picture-in-picture if enabled; rotate the
phone both ways; screen off 60 s and on (suspend/resume); 10 minutes at the menu watching
`/proc/<game pid>/fd` count (the driver had a sync-file fd leak, patched in this pack: the count must stay
flat). If the linear run passes, one more with `DROIDDECK_PRESENT_DMABUF_AFBC=1` (`droiddeckctl env set`) for
the picture and the `latency` status (`unreadable` is expected there).

### Plan C: cap and vote, after B passes

Set the frame cap to 60 on the game's Environment page (`DROIDDECK_FPS_CAP=60`, with `one-limiter` on as by
default). Do this on the native path: on Proton the existing cap code also writes `fps_limit` into the game's
`settings.save`, which needs Cedar's go. Pass: `pacing` cadence 16 ms with under 5 % off-cadence, p99 ≤ 17 ms on
screen, `latency toScreen` p50 no more than 5 ms above Plan B's. Then Mobile renderer
(`DROIDDECK_NATIVE_RENDERING_METHOD=mobile`) against Forward+ at the same cap: same checks plus GPU memory.

### Plans for B1 and B2 (when built)

Same instruments. B1 passes when the `[path]` line reads "on its own display layer", Lanner's own GPU memory
falls by at least 100 MB, and `latency toScreen` p50 falls by 4 ms or more with p99 not worse. B2 passes at
8 ms or more with all of Plan B's regressions clean, plus: overlay opens over the game within 150 ms, and
after ten overlay open/close cycles no black frame in a 240 fps `record`.

## 8. What CI proved

See the branch's Build APK run and the off-phone rig run named in the hand-off report: unit tests for the
probe core, the vkcount layer on lavapipe (swapchain line and pacing percentiles), game-env's switches, the
`latency` subcommand against a fake adb, the Kotlin experiment list; and on the rig, that the probe arms,
reads frames and returns rows on a CPU renderer (its times mean nothing there). CI cannot show that
`present-dmabuf` works: the rig's guest driver is lavapipe, not PanVK on kbase.

## Sources

- Mesa WSI: gitlab.freedesktop.org/mesa/mesa at 5a07217f034b, `src/vulkan/wsi/wsi_common.c`,
  `wsi_common_wayland.c`.
- PanVK on kbase: github.com/zenithblue-oss/panvk-kbase-android at 9be6dd6 (`patches/kbase-common/006`,
  README release table, `sources.lock`); build recipe github.com/The412Banner/droiddeck-nonadreno-drivers.
- gamescope 3.16.29 with `tools/gamescope/patches`: `layer/VkLayer_FROG_gamescope_wsi.cpp`,
  `src/Backends/WaylandBackend.cpp`, `src/wlserver.cpp`.
- Godot 4.5.1-stable: `platform/linuxbsd/wayland/*`, `drivers/vulkan/rendering_device_driver_vulkan.cpp`,
  `platform/android/*`, `modules/mono/*`; docs.godotengine.org/en/4.5 (renderers, multiple resolutions).
- Android: `android/surface_control.h`, developer.android.com NDK reference (ASurfaceTransaction,
  AChoreographer, ANativeWindow frame rate), "Frame rate" and "Vulkan frame pacing extensions" guides.
- Upstream PanVK status: docs.mesa3d.org/drivers/panfrost.html, Mesa MR 41803, Collabora's PanVK posts.
- Phone runs: `docs/research/mali-10a-native-1.md` and its artifacts.
