# Graphics and present path on PowerVR (Pixel 10)

Workstream 4 of the emulation push: what the frame path from the game to the screen costs, and what can be cut.

## The path today

Godot (Vulkan, Forward Mobile) → winevulkan → loader → VK_LAYER_window_system_integration (ginkage/vulkan-wsi-layer 40cfec09, X11 backend)
→ VK_LAYER_HYBRIS_compat → libhybris → vulkan.powervr.so.

Present: the WSI layer's X11 backend sends each image to the game's own Xwayland (:1) with DRI3 PresentPixmap
(zero-copy dma-buf from the system heap) and CPU-waits the present fence (implicit sync). Xwayland forwards the buffer
as a wl_surface commit to gamescope (nested, `--backend wayland`, `--expose-wayland`). Gamescope composites offscreen
and copies into its FIFO swapchain on Kestrel's own Wayland compositor (wayland-0), waiting for the composite before
presenting (patch 0925).

## What it costs now (telemetry, perf/switchboard runs 2796c7b and 92f7e10)

| Process | CPU, % of one core |
|---|---|
| Game's Xwayland (:1) | 9.5 (8.5 to 10.9) |
| Steam's Xwayland (:0) | about 1.3 |
| gamescope-wl | 8.5 (7.4 to 9.4); 6.9 in 92f7e10 |
| Kestrel app (incl. compositor) | 18.6 |
| Game | 112 to 140 |

Frame rate falls from about 29 fps (p50 17.7 ms) in minute 0 to 13.8 to 14.6 fps (p50 61 to 73 ms) after two minutes,
so the falloff is mostly the game's own CPU under heat. At 14 fps the game's Xwayland and gamescope each burn about
6 to 7 ms of CPU per game frame. Gamescope's "commit fences" log shows roughly a third of commits arriving with an
unsignalled fence (for example 147 waited of 501).

Not yet measured: Vulkan calls and submits per frame, time in each layer, the GPU/CPU split and BC decode cost.
Those need an instrumentation layer (next round) and the per-thread CPU the hub is adding to telemetry.

## Experiment `wayland-present`

Wine's Wayland driver (winewayland.drv, built into DroidDeck-Proton) presents the game straight to gamescope's
own Wayland socket. The game's Xwayland, its DRI3 round trip and the X11 window plumbing drop out of every frame.

- `droiddeck-game-env`: when the experiment is on, the PowerVR WSI path is in use, `GAMESCOPE_WAYLAND_DISPLAY` is set
  and the WSI manifest lists `VK_KHR_wayland_surface`, it sets `WAYLAND_DISPLAY` to gamescope's socket and
  `PROTON_ENABLE_WAYLAND=1`. The socket is named explicitly because the session clears `WAYLAND_DISPLAY`, and an empty
  one makes libwayland fall back to wayland-0 (Kestrel's compositor). Any guard failing leaves the game on Xwayland
  and prints why on stderr.
- Nov 13 session 16-12-52Z: gamescope's Wayland socket offers no `wl_subcompositor`, so winewayland.drv's
  `wayland_process_init` failed, the driver unloaded and explorer.exe never started (`nodrv_CreateWindow`), on both
  the launch and the watchdog's relaunch. The guard now reads the socket's globals over the wire first and needs
  `wl_compositor`, `wl_subcompositor`, `xdg_wm_base` and `wl_shm`; and the explorer watchdog's relaunch takes
  wayland-present back out (`game_relaunch ... display=x11`). The experiment is off in the remote config until
  gamescope offers `wl_subcompositor`.
- `tools/vulkan-wsi/build.sh` now builds the Wayland surface too (`BUILD_WSI_WAYLAND=ON`; same source revision
  40cfec09; one new dependency, libwayland-client.so.0, which both runtimes ship; same GLIBC/GLIBCXX floor). The
  X11 path is unchanged, and with the experiment off nothing calls the Wayland code.
  `.github/workflows/build-vulkan-wsi.yml` builds it on arm64 and publishes it (release
  wsi-40cfec0-wayland-73eceb9a on renxida/DroidDeck), and `tools/powervr/release.json` pins that zip.

What to look for in a run with the experiment on:

1. `processes.csv`: the game's Xwayland (:1) near 0% CPU (it may still start, idle). gamescope should be flat or lower.
2. `frames.csv` and fps by minute against temperature-matched control rows.
3. Proton log: `winewayland` loaded rather than `winex11`; the droiddeck-game-env stderr line naming the socket.
4. gamescope log: the game shows up as an xdg window and gets focus.

Known risks, all of which fail loudly in the logs:

- gamescope 3.16 focus for xdg_toplevel windows (appid comes from the client pid; should work).
- Touch: winewayland sends WM_POINTER from wl_touch, which win32u promotes to mouse input. If taps don't land in game,
  that is the first suspect.
- dma-buf format and modifier negotiation over zwp_linux_dmabuf_v1 (v3+) with linear system-heap buffers; explicit
  sync is optional and falls back to the same CPU fence wait as today.
- Fullscreen and size: the game window settles at 1616x720 today; Wine's Wayland driver may pick a different size.

## Experiment `vk-count`

`tools/linuxfs/vkcount-layer`: VK_LAYER_DROIDDECK_vkcount, built from source in CI like the Xclipse layer. With the
experiment on, droiddeck-game-env puts it first in the PowerVR game's layer list. Every 10 s of presents
(`DROIDDECK_VKCOUNT_SECONDS`) it prints one line to the game's stderr:

```
droiddeck-vkcount: window 10.0s 142 frames 14.2 fps | per frame: 3.1 submits 3.1 cmdbufs 412 draws (0 indirect) ...
  | ms/frame: submit 1.20 present 4.30 acquire 0.20 fence-wait 6.10 idle 0.00 | pipelines 12 in 340 ms, allocations 2
```

Being first, its times cover everything below it: the WSI layer, the libhybris compat layer and the driver.
`present` includes the WSI layer's CPU wait on the present fence. `present + acquire + fence-wait + idle` is time the
render thread spent blocked on the GPU or the compositor; the rest of the frame interval is CPU work. That is the
GPU/CPU split, per window, with no GPU timestamps needed. `pipelines` catches shader-compile stutter.

Counting is relaxed atomics and one clock read around each blocking call, so the overhead is tens of nanoseconds per
command. Tested on lavapipe with a headless swapchain (tools/tests/test_vkcount_layer.py).

## Experiment `hybris-index`

The libhybris compat layer (VK_LAYER_HYBRIS_compat) sits under every PowerVR game call. Reading its source showed
per-call work that grows with the number of live objects:

- each bind, push and barrier looks its command buffer up in a linked list under a global mutex;
- each image barrier walks every live image, and each push walks every pipeline layout;
- each hooked call fetches the next entry point with GetDeviceProcAddr by string;
- each push or bind adds a calloc'd journal entry that is replayed later.

`tools/hybris/compat-layer/0001-indexed-lookups.patch` adds open-addressed hash indexes beside those lists, a
per-device proc table read without the lock, and coalesces a journal entry into the previous one when it has the
same shape (replay gives the same state). The experiment swaps the runtime's layer for this build in the game's
`VK_INSTANCE_LAYERS`. It is built separately by `compat-layer/build.sh` because the layer links only libc, which
leaves the pinned runtime tarball untouched.

Lavapipe, `HYBRIS_BC_TEXTURES=dxvk` (the phone's policy), 64 command buffers, 3000 images, 300 layouts:

| call | before | after |
| --- | --- | --- |
| push constants | 1886 ns | 138 ns |
| bind descriptor sets | 334 ns | 106 ns |
| image barrier | 10192 ns | 202 ns |

After the patch these costs stay flat as object counts grow. The BC decode probe's output is byte-identical, and a
deliberately broken coalescing build fails the probe. The hash map passed 20M random operations against a reference
under ASan and UBSan. What this saves on the phone depends on calls per frame, which vk-count reports.

## Gamescope on PowerVR: where the frame waits (design, no change yet)

PowerVR sessions run gamescope's SDL backend (`BL_GAMESCOPE_BACKEND=sdl`, SystemVulkanDriver), not the Wayland
backend, so every frame takes the full-composite path even for one fullscreen game. Per frame, the paint thread does:

1. `vulkan_composite`: the compute composite into an offscreen image, then a copy into the swapchain image. The copy
   happens because the hybris Wayland swapchain lacks storage usage or mutable format (patch 0920 picks it).
2. `vulkan_wait`: a CPU block on the composite's timeline value (patch 0925, because present has no
   render-finished semaphore).
3. `vkQueuePresentKHR` → libhybris `queue_present` → `vkQueueSignalReleaseImageANDROID` → the Wayland window's
   `queueBuffer`, which `sync_wait`s the release fence on the calling thread before attach and commit.
4. `acquire_next_image`: dequeue (FIFO) plus a fence wait.

What this means:

- Replacing step 2 with a semaphore (0925's own TODO) gains nothing alone, because step 3 waits on the same GPU work
  on the same thread.
- Getting the composite off the paint thread's critical path needs both changes: a semaphore present in gamescope,
  and a presenter thread in libhybris's `wayland_window.cpp` so that `queueBuffer` hands off (buffer, fence) and
  returns while that thread waits and commits. The gain is latency per frame, about the GPU time of composite plus
  copy, not CPU.
- Steps 2 to 4 are blocking waits, not CPU burn. So gamescope's 6 to 7 ms of CPU per game frame is real work
  somewhere else: the paint thread's command recording and the compat layer under it, wlserver, or the X window
  manager. Choosing a fix needs gamescope's CPU split by thread.
- Removing the copy would need a graphics-pipeline composite or a swapchain with storage usage. That's a large
  change, to weigh only if GPU time turns out to bind.

## Next rounds

- Read the first vk-count run: draws and submits per frame, and how the 60+ ms frames split between CPU and waits.
- Gamescope's offscreen composite plus copy and its wait before present on PowerVR.
- BC texture decode in the hybris compat layer (vk-count's copies and the compat layer's own decode time).
