# Off-device profile

What can be learned about size and speed without a phone: the APK's contents, the work on the path from
tapping Play to a running session, and the per-frame work in the native compositor. Everything here is read
from the source at fork/main 55e67e6 or from the CI build log; nothing was timed on a device, so every cost
below is a class or an estimate, not a measurement. Stores, VR, glasses, the desktop mode and the home
launcher are left out because they are being removed.

## APK size

`tools/release/apk_size_report.py` prints the table below for any APK; the "APK size report" step in
`build.yml` runs it on every release build, so the numbers for a branch are in its "Compile APK and Android
tests" job log. (The artifact itself could not be downloaded from this environment: the proxy refuses the
Actions blob host. The figures here come from that log for run 43164441811, job "Compile APK and Android tests".)

Release APK at c0b3885 (fork/main plus the report step): **613 entries, 92.61 MiB compressed,
206.19 MiB uncompressed.**

| Group | Compressed MiB | Uncompressed MiB | Entries | What it is |
|---|---:|---:|---:|---|
| `lib/arm64-v8a/` | 25.93 | 67.30 | 35 | Native libraries (see below) |
| `*.dex` | 21.38 | 68.73 | 5 | App and library bytecode; release is not shrunk |
| `assets/linuxfs/` | 13.23 | 36.31 | 177 | Files staged into the guest |
| `assets/graphics_driver/` | 11.50 | 11.53 | 5 | Bundled Turnip driver builds |
| `resources.arsc` | 8.72 | 8.72 | 1 | Resource table, stored uncompressed (32 `values-*` locales) |
| `assets/desktop-removal/` | 4.56 | 4.59 | 6 | File lists for removing the desktop install |
| `assets/steam-startup/` | 2.27 | 2.27 | 2 | Startup movies |
| `assets/droiddeck-esync/` | 2.15 | 2.17 | 6 | Bundled esync Proton packs |
| `org/` | 1.28 | 1.56 | 135 | Java resources from dependency jars (BouncyCastle tables) |
| everything else | 1.59 | 3.01 | 241 | |

Top 30 entries by compressed size (MiB):

| Compressed | Uncompressed | Entry | Feature |
|---:|---:|---|---|
| 12.73 | 33.55 | `lib/arm64-v8a/libsyncthingnative.so` | Syncthing save sync (`sync/`) |
| 8.72 | 8.72 | `resources.arsc` | all UI |
| 6.88 | 30.26 | `classes.dex` | app + libraries |
| 5.10 | 10.85 | `lib/arm64-v8a/libjingle_peerconnection_so.so` | WebRTC for Xbox cloud streaming (`stores/xbox`): **removed with stores** |
| 4.69 | 12.46 | `classes3.dex` | app + libraries |
| 4.08 | 10.71 | `classes2.dex` | app + libraries |
| 3.27 | 3.28 | `assets/graphics_driver/adrenotools-turnip-sdk36.tzst` | GPU driver (core) |
| 2.96 | 8.13 | `classes4.dex` | app + libraries |
| 2.77 | 7.17 | `classes5.dex` | app + libraries |
| 2.77 | 2.78 | `assets/desktop-removal/lxqt-r1.tsv.gzip` | desktop mode removal |
| 2.45 | 2.47 | `assets/graphics_driver/adrenotools-turnip25.1.0.tzst` | GPU driver (core) |
| 2.11 | 4.18 | `lib/arm64-v8a/libdroiddeckstores.so` | other stores: **removed with stores** |
| 1.97 | 1.98 | `assets/graphics_driver/adrenotools-turnip26.3.0-quest2.tzst` | Quest driver: **removed with VR** |
| 1.97 | 1.97 | `assets/graphics_driver/adrenotools-turnip26.3.0-a6xx.tzst` | GPU driver, A6XX (core) |
| 1.83 | 1.83 | `assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst` | Quest driver: **removed with VR** |
| 1.83 | 2.03 | `assets/linuxfs/usr/local/lib/droiddeck/uruntime` | AppImage runtime (core) |
| 1.58 | 4.01 | `assets/linuxfs/usr/local/bin/gamescope` | gamescope (core) |
| 1.38 | 1.38 | `assets/steam-startup/droiddeck-startup-60.webm` | startup movie, 60 fps |
| 1.20 | 2.94 | `assets/linuxfs/usr/local/lib/droiddeck-msitools/7z.so` | installer unpacking |
| 1.18 | 2.86 | `assets/linuxfs/usr/local/lib/mangoapp/mangoapp` | performance overlay |
| 1.18 | 1.18 | `assets/desktop-removal/kde-remove.tsv.gzip` | desktop mode removal |
| 1.09 | 1.09 | `assets/linuxfs/usr/local/lib/droiddeck-pipewire.tzst` | guest audio (core) |
| 1.00 | 2.85 | `lib/arm64-v8a/libvkbridge_jni.so` | Mali Vulkan bridge |
| 0.94 | 2.58 | `lib/arm64-v8a/libvkbridge_server.so` | Mali Vulkan bridge |
| 0.89 | 0.89 | `assets/steam-startup/droiddeck-startup.webm` | startup movie |
| 0.73 | 2.73 | `lib/arm64-v8a/libvirglrenderer.so` | Venus/virgl path |
| 0.71 | 0.72 | `org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties` | unused post-quantum tables from a transitive BouncyCastle jar |
| 0.63 | 1.76 | `assets/venus/libvulkan_virtio.so` | Venus/virgl path |
| 0.55 | 2.32 | `lib/arm64-v8a/libvirgl_render_server.so` | Venus/virgl path |
| 0.54 | 0.54 | `assets/droiddeck-esync/packs/ge-GE-Proton11-7-...-r1.tzst` | esync pack |

The top 30 by uncompressed size are the same entries in a different order, plus three VR files
(`droiddeck-vr/.../driver_droiddeck.so` 1.83 MiB, `libopenxr_loader.so` 1.52 MiB, a hand model `.obj`
1.31 MiB) and `libc++_shared.so` (1.36 MiB) and `libfakeinput.so` (1.32 MiB).

What this says:

- **The release build is not shrunk** (`minifyEnabled false`, `app/build.gradle:198`): five dex files,
  68.7 MiB uncompressed. Turning on R8 is the largest single size lever but needs keep rules for anything
  loaded by reflection or JNI, and a device run to check; not attempted here.
- **Native libraries are stored compressed and extracted at install** (`useLegacyPackaging = true`,
  `app/build.gradle:216`, `extractNativeLibs="true"` in the manifest). The phone keeps the 25.9 MiB in the
  APK and another 67.3 MiB in `nativeLibraryDir`. This is probably deliberate (binaries are executed from
  that directory), so it is a cost to know about rather than a fix.
- **Syncthing is the largest single entry** at 12.7 MiB compressed, 14% of the APK.
- **About 15 MiB belongs to features being removed**: WebRTC and the stores library (7.2 MiB), the two
  Quest drivers (3.8 MiB), the desktop-removal lists (4.6 MiB, if desktop cleanup goes too), and the VR
  guest files and OpenXR loader.
- Two startup movies ship (60 fps and normal, 2.3 MiB together).

## Startup path

From the Play tap to the guest's Steam window. "Every start" means it runs on each session start, not once
per install. Cost classes: **net** (network, seconds), **heavy** (100 ms and up or scales with installed
content), **medium** (10-100 ms), **light** (under 10 ms), **wait** (blocks on another process or a timer).

### Host side (Kotlin)

| Step | Where | When | Class | Note |
|---|---|---|---|---|
| Store backends registered, agent state loaded, store logs pruned | `App.kt:45-72` | every process start, main thread | medium | 9 store backends and `AgentHost.refresh` (file IO + JSON) before the first frame; the store part goes away with the stores. |
| Library cache read | `FrontEndActivity.kt:1118` | onCreate, main thread | medium | Synchronous file read and parse before first composition. |
| `refresh()` on resume | `FrontEndActivity.kt:2847-2859` | every resume, main thread | light-medium | `OfflineMode.account` reads loginusers.vdf; `DesktopCatalog.desktopInstalled/Present` stat files. |
| Orphan reaper | `SessionService.kt:399` | every start | light-medium | Walks `/proc` and reads every process's status. |
| Wait for Proton extras | `SessionService.kt:400`, `ProtonExtras.kt:206` | every start | wait | Polls every 50 ms, up to 120 s, before anything else runs. |
| Add-on packs | `SessionService.kt:477-480`, `core/AddonPack.kt:85` | every start; extract on first | wait / heavy once | `await` polls every 250 ms. First install copies the asset to disk, hashes it, then extracts it: three passes over the same bytes. |
| Stage guest files | `SessionService.kt:521`, `SessionFiles.kt:220-235` | every start | medium | Builds the optional list by calling `assets.list("linuxfs/<dir>")` once per optional file (60+ calls, each listing a whole APK directory). A per-directory cache makes this one call per directory. The ledger then stats ~90 files (light). |
| Re-stage after an update | `SessionFiles.kt` StageLedger | first start after every APK update | heavy | The ledger is keyed by APK identity, so any update re-copies every staged file even when the bytes are the same. |
| Wait for last session's artifact collection | `SessionService.kt:527`, `SessionArtifacts.kt:49` | every start | wait | Up to 30 s when a quick restart overlaps the previous collection. |
| Game environment published twice | `SessionFiles.stage` and `SessionService.kt:536` | every start | light | Two AtomicFile writes with fsync for the same content. |
| Components republish | `SessionService.kt:554`, `ComponentsManager.kt:548-595` | every start | heavy | `prepareLaunchState` sha256-hashes every file of every selected or per-game component just to rebuild `desired.tsv` and compare it. Scales with Protons x components (DXVK and VKD3D trees are tens of MB). Zero when nothing is selected. Caching by size+mtime would remove it. |
| Forced packages | `SessionService.kt:558`, `ComponentsManager.kt:1216-1255` | every Steam/Desktop start on A6XX | net when missing | Can fetch the GitHub release catalog and download a DXVK .wcp on the session-start thread. |
| Boot movie | `BootMovie.prepare` | first start after a movie/volume/length change | heavy once | MediaCodec transcode, seconds. |
| Esync packs | `EsyncPacks.stageBundled` | every start | light-medium | Signature check and asset listing. |
| Session exit waits | `SessionService.kt:1420-1479`, constants `:2404-2414` | every stop | wait | `waitForExit` polls every 50 ms; GRACE 1.2 s, STEAM_PICKUP 1.5 s, STEAM_EXIT 10 s, WINE_SAVE 3 s ceilings. |
| Session UI loops | `SessionActivity.kt:896-909, 1083-1086` | while a session is shown | light, constant | A 100 ms loop for glasses tracking recovery on the main dispatcher (goes with glasses), a 2 s SteamVR check, a 1.5 s import-prompt read. |

### Guest side (`tools/linuxfs/overlay/usr/local/bin/droiddeck-session`)

Every guest process starts under proot, so each `exec` pays ptrace overhead on top of its normal cost.

| Step | Line | Class | Note |
|---|---|---|---|
| Wait for the compositor | 468-471 | wait | `wayland-info` every 0.1 s, up to 10 s. Each try is a fresh process under proot. |
| Wait for the system bus | 1322-1326 | wait | Socket check every 0.1 s, up to 5 s. |
| vkbridge socket and self-test | ~250-310 | wait / heavy | Socket polled every 50 ms up to 10 s, then a smoke test (60 s timeout) and `vulkaninfo --summary` (30 s timeout) on the bridge path; Venus runs `vulkaninfo` again (317-318). |
| x86-64 client sync | 684 | net | `droiddeck-steam-install --sync` fetches the manifest every start (connect 10 s, max 30 s). |
| Steam prep helpers | 1111-1133 | heavy | `droiddeck-steam-library` (191 lines), `steam-compatibility` (1749 lines), `droiddeck-steam-ui-scale`, `droiddeck-steam-shortcuts` run one after another, each a new Python interpreter that compiles its script from source (scripts run as `__main__` are never cached as .pyc). Estimated 1-4 s of every Steam start on a phone under proot. Moving the bodies into importable modules (so .pyc is cached) or one process that runs all four would cut most of it. |
| Launch a steam:// game | 1236-1262 | wait | Polls Steam's focus every 1 s (up to 120 s), then `droiddeck-agent --steam-ready` every 1 s (up to 30 s), then up to 20 s more for shortcuts. The 1 s granularity adds about 0.5 s average latency per stage; each poll is an `xprop` process under proot. |

## Native per-frame work

`scanout` is one 82-line header with no per-frame code. `gpuclock` samples KGSL on its own thread every
1 ms (`kgsl_busy.c:18,59`), with a context rescan every 200 samples; it allocates only when opened. The
framegen engines log telemetry every 120 planned frames (`lsfg_engine.cpp:21,213`) and allocate at chain
build, not per frame. The compositor and presenter are where per-frame costs are:

| Finding | Where | Per | Note |
|---|---|---|---|
| The CPU waits for the GPU at the end of every frame | `vk_present.c:2191-2243` | frame | One fence, reset and waited with `UINT64_MAX` before `render_impl` returns. There is no second frame in flight, so CPU recording of frame N+1 never overlaps GPU work on frame N. Two or three fences and command buffers in a ring would let them overlap; this is the largest structural cost found. |
| The game-layer copy waits too | `vk_present.c:2349-2418`, `sc_layer.c:863,990` | frame on the layer path | `vkp_blit_image` submits one blit and waits on the same fence (1 s timeout) before `present_slot`. The SurfaceControl layer could take the blit's completion as its acquire fence (export a sync_fd) instead of the CPU waiting. |
| Barrier array allocated every frame | `vk_present.c:2066,2096` | frame | `calloc(n + 1)` and `free` for image barriers; `n` is small, so a static array or one grown on demand avoids the allocator. The dedup loop above it is O(n^2) over draws (small n). |
| Paced buffer release allocates | `compositor.c:483` | committed buffer, when release pacing is on | One `calloc` per pending release node. A free list would avoid it. |
| HDR flag array allocated | `compositor.c:2000,2009` | frame with an HDR surface | `calloc(n + CURSOR_MAX_DRAWS)`. |
| Draw list | `compositor.c:1871-1876` | amortised | Grows by doubling and is reused; fine. |
| Logging | `compositor.c:53-72` | event | `droiddeck_log` formats twice, calls `localtime_r`, writes logcat and the session file under a mutex. Every per-frame call site checked is guarded by a `said` flag, a trace counter or a modulo (for example `compositor.c:2180`), so it is not on the steady-state frame path. |
| Locks | `vk_present.c:286-354` (`g_req_lock`), `compositor.c:755-788` (HUD times) | frame | Uncontended mutexes taken once or twice per frame; negligible. |
| CPU-side fence polls | `vk_present.c:1934`, `sc_layer.c:540` | frame, only when sync_fd import is unsupported | `poll()` up to 100 ms on a release fence on the render thread. |
| Cursor readback | `compositor.c:1138-1143` | cursor change with a dmabuf cursor | Waits on the dmabuf and reads it back to the CPU. |

## Top findings

1. **No frames in flight in the presenter** (`vk_present.c:2243`): every frame ends with a blocking fence
   wait, so CPU and GPU work never overlap. Likely the biggest frame-time win available; needs a device to
   measure.
2. **The zero-copy game layer still does a GPU copy plus a CPU fence wait per frame**
   (`vk_present.c:2414`).
3. **Four Python helpers compile from source before every Steam start** (droiddeck-session:1111-1133):
   about 2,500 lines across four interpreters under proot, estimated 1-4 s.
4. **Every session start hashes every file of the selected components** (`ComponentsManager.kt:595`), tens
   to hundreds of ms per start, more with several Protons.
5. **The release APK is not shrunk** (`minifyEnabled false`): 68.7 MiB of dex in a 92.6 MiB APK. Syncthing
   (12.7 MiB) is the largest entry, and about 15 MiB belongs to features being removed.
6. **Every APK update re-copies every staged guest file** (StageLedger keyed by APK identity).
7. **60+ `assets.list()` calls per start** for optional files (`SessionFiles.kt:233`).
8. **1 s polling in the steam:// launch handshake** (droiddeck-session:1243-1260), about 0.5 s average
   latency per stage, and a 0.1 s `wayland-info` process loop at startup (468-471).
9. **The x86-64 client syncs over the network every start** (droiddeck-session:684), up to 30 s on a bad
   network.
10. **Network on the session-start thread** for forced packages on A6XX (`ComponentsManager.kt:1255`).

None of these are measured. A device run of the `perf/*` scenarios through `tools/ci_apk_loop.sh` would
confirm the order.
