# Steam's interface out of the way while a game runs (branch perf/steam-out-of-the-way)

**Question.** On the Pixel 10a (8 GB, Mali-G715, PanVK) about 3 GB of the 7.6 GB is GPU memory at Slay the
Spire 2's main menu, and no existing switch touches it (`mali-10a-memory-1.md`). The game holds 1.2 to 1.4 GB;
Steam's interface, hidden behind the game, holds 1.15 to 1.5 GB. On the native-engine path the game needs the
Steam **client** process (Steamworks through the bridge), not the interface. How much of the interface's
memory can be returned, by what mechanism, and at what risk?

**Short answer.** Two experiment switches, both default off, both new on this branch:

- **`steam-x-soft`** (static). The X server Steam's interface draws into is started without glamor, so it
  holds no GPU memory at all; the games' X server is untouched. Expected on the 10a: **0.76 to 1.16 GB of GPU
  memory back, from session start, including the load peak** where `memory.lmk_risk` fires today. No process
  is stopped and nothing has to be restored. Cost: Steam's interface reaches gamescope as shared-memory
  buffers that gamescope uploads on every commit, a path this app already uses on every Mali-through-Venus
  session.
- **`steam-ui-park`** (dynamic). Once a Steam game has had focus for 20 s, Steam's web helper (the browser
  that draws the interface) is ended and the client's immediate restart of it is held until the game exits,
  loses focus, a cloud sync starts or the Steam button is pressed. Expected: **388 MB of GPU memory**, about
  330 MB of resident-plus-swapped memory and the helper's CPU, while the game is in front. The Steam client is
  never signalled.

Together: **1.15 to 1.54 GB of the 2.9 to 3.0 GB**, which would take the native menu from 690-803 MB
available to roughly 1.8 to 2.3 GB (INFERRED, section 6). GPU memory cannot be measured off the phone; what
the off-phone rig did prove is in section 5, and the phone test plan is section 8.

Marks: **VERIFIED** = read in source, in a primary document, in a phone run's artifacts or in an off-phone rig
run; **INFERRED** = reasoned from verified facts; **UNVERIFIED** = no source found. Phone artifacts are the
ones of the two Mali notes (`~/projects/droiddeck/output/mali-10a-memory/`, `mali-10a-native/`).

## 1. What the phone runs already say (VERIFIED, re-read from `sampler.txt` of twelve runs)

Per-process GPU memory (`dumpsys gpu --gpumem`, every 10 s), by event:

| Event | Xwayland of Steam's display | Web helper | Total `Gpu:` |
|---|---|---|---|
| Steam's interface up, no game | 1019 to 1020 MB | 388 | 1721 to 1732 |
| Game takes focus (native-1 / native-2 / webhelper-trim) | 902 / 763 / 761 | 388 | |
| Game takes focus (control-1) | **rises** to 1156 to 1161 | 388 | |
| Web helper killed mid-game (`webhelper-trim`) | 761 to **610** | 388 to 0 | 2710 to 2634 |
| Client restarts the helper (same sample, 10 s later) | 610 to 1020 | 0 to 388 (new pid) | 2975 |
| Game exits, interface back in front | 1023, 1160, 1317 | 388 | |

Three things follow.

1. **The web helper's 388 MB is constant** (every sample of every run) and goes to zero the moment the helper
   dies. The helper runs with `--in-process-gpu` (Lanner's default layout, `tools/linuxfs/preload/webhelper.c`),
   so this is its ANGLE-on-Vulkan context in the browser process.
2. **The X server's share moves in steps of about 141 MB**: 610, 761, 902, 1020, 1161, 1317 fit
   `46 + N x 141` for N = 4 to 9 within 13 MB. Window pixmaps cannot explain that (a full-screen 1616x720
   buffer is 4.65 MB). PanVK allocates a 128 MiB polygon heap per device and lets a tiler heap grow to 64
   chunks of 2 MiB, 128 MiB, per queue (VERIFIED in Mesa: `PANVK_POLY_HEAP_SIZE (1 << 27)` in
   `panvk_device.h`; `chunk_size = 2 MiB, max_chunks = 64` in `panvk_physical_device.c`), and Zink's batch
   states are never freed before the context is destroyed (VERIFIED: `zink_batch.c`, "called only on context
   destruction"). INFERRED: the X server's GPU memory is driver bookkeeping that scales with how much drawing
   went through glamor, not window contents. That is why killing the helper only returned one step (151 MB),
   and why nothing short of not using glamor returns the rest.
3. **A helper killed mid-game comes back by itself and the game stays in front** (the `webhelper-trim` run
   measured its pacing after the restart). So restoring the interface is something the client already does;
   what was missing is a way to stop it from happening at once.

## 2. Design space

What the game needs from Steam on the native path (VERIFIED, `native-engine.md`, `mali-10a-native-1.md`):
`libsteam_api.so` loads the client's `steamclient.so` and talks to the running client process; the bridge
serves 22 interfaces from it; saves go through `ISteamRemoteStorage` into `userdata/<id>/2868840/remote/`,
which the client syncs to Steam Cloud at launch and exit (`cloud_log.txt`: `Starting sync (AC Launch` /
`(AC Exit`). None of that is in the web helper. What does live in the web helper: everything drawn (library,
overlay, quick access menu, toasts, the on-screen keyboard, the cloud-conflict dialog).

| # | Candidate | GPU saving on the 10a | Other saving | What the game loses | Time to get the UI back | Verdict |
|---|---|---|---|---|---|---|
| 1a | Freeze the web helper (SIGSTOP or cgroup freezer) | **0**: a stopped process keeps its GPU objects, and the X server keeps its windows | its CPU (part of the client's 14 % of a core) | nothing while it lasts | instant | Rejected: returns none of the measured memory, and the client has a hang watchdog for the helper (the "Steamwebhelper is not responding" dialog exists on Linux, steam-for-linux #10431; `-cef-disable-hang-timeouts` exists; timeout UNVERIFIED) |
| 1b | Kill the helper and let the client restart it lazily | 0 after 10 s: the client restarts it at once (VERIFIED, table above; also SteamOS #2730) | none | none | seconds | This is `webhelper-trim`, measured as no gain |
| 1c | **Kill the helper and hold its restart** | 388 MB, plus one 141 MB step of the X server when `steam-x-soft` is off | about 330 MB resident + swapped; helper CPU | overlay, keyboard, toasts until restored | one helper start (5 to 35 s on the phone, to be measured) | **Built: `steam-ui-park`** |
| 2a | Unmap or minimise Steam's window | small: Xwayland disposes a window's buffers on unmap (VERIFIED, `xwayland-window.c`, `xwayland-window-buffers.c` `BUFFER_TIMEOUT` 1 s) but the buffers are megabytes, the driver heaps stay; gamescope keeps the last commit referenced and its own TODO says so (VERIFIED, `steamcompmgr.cpp` `finish_unmap_win`) | none | none | instant | Rejected: aims at the wrong memory (section 1, item 2) |
| 2b | Restart Steam's X server mid-session | all of it | | the client is an X client of it and dies with it | a client restart | Rejected |
| 2c | **Steam's X server without glamor** | 0.76 to 1.16 GB, all of the X server's | | nothing | nothing to restore | **Built: `steam-x-soft`** |
| 3 | Web helper without the GPU (`-cef-disable-gpu`) for the whole session | 388 MB, always (INFERRED: no ANGLE context) | | nothing | nothing to restore | Wired as `BL_STEAM_CEF_GPU=0` for one measurement, not a switch: the interface would raster on the CPU all the time, and `steam-ui-park` returns the same 388 MB only while a game runs |
| 3b | Software drawing only while a game runs | | | | | Not possible as stated: CEF's flags are fixed when the helper starts. Parking and restarting the helper is the only way to change them, and then it may as well stay away |
| 4 | Game-first launch: client with no or a minimal UI | up to all 1.5 GB | boot time (no interface to wait for) | first-run dialogs, cloud conflicts, EULAs have nowhere to show | a full interface start | Not built; see section 9. `-no-browser` was removed in 2023 (VERIFIED, steam-for-linux #9031); `-silent` in gamepad mode is UNVERIFIED and reported fragile (#9500) |
| 5 | Freeze the UI tree and page it out | 0 | the helper is already 90 % in zram (32 MB resident, 301 MB swapped) | | instant | Rejected: the memory that matters is not pageable |
| 6 | A purpose-built host for Steam's windows instead of Xwayland | same as 2c | could drop the per-commit upload by handing gamescope dma-bufs it fills on the CPU | | | Not needed if 2c measures as expected: Xwayland without glamor **is** a software X host, already shipped and already used on other Mali phones. Worth building only if 2c's per-commit copies show up in frame times (section 9) |

Sources for the Steam and Chromium rows: Valve's community wiki "Command line options (Steam)" and "Steam
browser protocol" (read through web.archive.org; the wiki blocks automated fetches), the ValveSoftware issue
trackers named in the table, gamescope `src/steamcompmgr.cpp`, Chromium `cc/trees/layer_tree_host_impl.cc`
and `content/public/common/content_switches.cc`, Mesa `src/gallium/drivers/zink` and `src/panfrost/vulkan`,
xorg-xserver `hw/xwayland`. UNVERIFIED and left as such: what `-silent` does in gamepad mode, the web
helper watchdog's timeout and retry limits, whether Steam's CEF build treats an X unmap as hidden, and
whether a user report of `chmod -x steamwebhelper` keeping the client alive and "web features instantly
start working again" after `chmod +x` (#9031, desktop UI, 2023) still describes today's client. The off-phone
rig run in section 5 is the evidence for the last point on the client this app ships.

## 3. `steam-x-soft`: how it works

- gamescope starts its X servers through wlroots, which honours `WLR_XWAYLAND` (VERIFIED: the string and
  "Using Xwayland binary '%s' due to WLR_XWAYLAND" are in the shipped `gamescope-3.16.29-p24k` binary) and
  runs `Xwayland :N -rootless -core -terminate -listenfd ...` (VERIFIED: `processes.txt` of a phone session).
- With the switch on, `droiddeck-session` sets `WLR_XWAYLAND=/usr/local/libexec/droiddeck-steam/Xwayland`
  (`droiddeck-steam-xwayland`). The wrapper sets `XWAYLAND_NO_GLAMOR=1` for the first server of that
  gamescope only and execs `/usr/bin/Xwayland` with the same arguments and descriptors. "First" is decided
  from the lock files gamescope writes before it forks (`/tmp/.X<N>-lock` holds its pid): the server with no
  lower-numbered lock of the same owner is Steam's. No race, no guess from the display number.
- Inside gamescope the session checks that the server that lost glamor is `$DISPLAY`, and says so:
  `== steam-x-soft: Steam's X server :0 draws without the GPU; the games' X server :1 keeps glamor` and
  `DROIDDECK_TIMELINE event=steam_x_soft applied=1 display=:0 game_display=:1` (event `steam.x_soft`).
- Applied only where Steam already composites in software (`BL_STEAM_SOFT_COMPOSITING=1`, the open driver
  pair, which is the 10a): there CEF hands the X server finished pixels and never asks it for a GPU
  presentation path. Elsewhere it prints `not applied: Steam's interface composites on the GPU here`.
  `BL_STEAM_X_SOFT=1` applies it anyway, `=0` never. Not applied with one X server (games would share it),
  on the desktop UI, or when `XWAYLAND_NO_GLAMOR` is already set for the session.
- Why this is not new ground: with no DRM node (Mali through Venus) Xwayland has no glamor at all, gamescope
  patch `0927-shm-buffers-upload-every-commit` exists for exactly that, and the app already sets
  `XWAYLAND_NO_GLAMOR=1` for a whole session after an Xwayland crash (`SessionService.lastXwaylandCrashed`).
  The new part is doing it for Steam's server only, so games keep DRI3 and glamor on theirs.

Costs and risks (INFERRED until a phone run):

- Each frame of the interface is copied by Xwayland into a shared-memory buffer and uploaded by gamescope
  (4.65 MB at 1616x720). While a game is in front the interface still commits whenever it repaints, and
  gamescope does not throttle a hidden window (VERIFIED: frame callbacks go to every window in
  `steamcompmgr.cpp`). If Steam's hidden interface repaints often, those uploads share gamescope's thread
  with the game's frames. This is the P99 question of the phone plan; `steam-ui-park` removes it entirely
  while parked.
- The window buffers move from GPU memory to shared memory: a few 4.65 MB buffers per Steam window, which
  the kernel can swap. Tens of MB against hundreds.
- A program that needs DRI3 on Steam's display gets none. Known clients there: the web helper (software
  compositing, so no), the client itself (46 MB of GPU memory of its own, kept), mangoapp (`no_display`).

## 4. `steam-ui-park`: how it works

`droiddeck-steam-ui-park` (Python, started by `droiddeck-session` beside the client) and a hold in
`droiddeck-steam-taskset`.

- **Park.** Conditions, all of them: a `reaper SteamLaunch AppId=N` process exists; gamescope's focused app
  (the agent's `focus.json`) is that N and has been for 20 s (`BL_STEAM_UI_PARK_DWELL_S`); no viewable Steam
  window has `STEAM_OVERLAY` or `STEAM_INPUT_FOCUS` set; no Steam Cloud sync is open. Then the gate file
  `$BL_LAUNCH_DIR/steam-ui-gate` is written (it holds the controller's pid) and the helper's browser process
  gets SIGTERM, which is how `webhelper-trim` already ends it. Children that outlive a dead browser get
  SIGKILL; a browser still alive after 15 s is left alone and parking goes off for the session.
- **Hold.** The client restarts the helper at once through `steamwebhelper.sh`, which calls
  `taskset 0x7c .../steamwebhelper`. Lanner's `taskset` wrapper is put ahead on the client's PATH; while the
  gate exists and its owner is alive, the wrapper waits there. It leaves a marker `steam-ui-gate.held.<pid>`
  and prints `event=steam_ui_park_hold`. Nothing of Valve's is modified, and no helper process exists while
  held. If the script that started it goes away, the wrapper exits without starting anything.
- **Restore.** The gate is removed when: no game runs (`game_exit`), the focused app is no longer the game
  (`focus_left_game`), a cloud sync starts (`cloud_sync`), Steam / Guide or quick access is pressed on any pad
  (`steam_button`, read from the app's pad rings without consuming events; parking then stays off for 60 s
  and while an overlay is up), the request file says `restore` (`asked`), or the controller ends for any
  reason, including the session's own shutdown (`controller_exit`). The held start then runs as if nothing
  had happened. `event=steam_ui_restored` is printed once a new browser process exists **and** the client's
  DevTools lists `SharedJSContext` again, with the time it took.
- **Fallback.** No interface 60 s after the gate went: with a game running, `steam_ui_restore_late` every
  30 s and nothing else (a client restart would end the game); with no game running, the controller writes
  `steam-ui-restart`, and the session's request loop shuts the client down and starts it again
  (`== steam: its interface did not come back (steam-ui-park); restarting the client`). The user is not left
  with a dead interface.
- **Hold not in effect.** If a helper appears while the gate is up (Valve changed how the helper is started),
  the controller prints `steam_ui_park_hold_missed`, removes the gate and stops parking for the session. The
  worst case is one extra helper restart, which is `webhelper-trim`'s behaviour.
- **Steam Cloud.** The app's own evidence for syncs is `logs/cloud_log.txt` (`CloudConflicts` parses the same
  file). The controller tails it: an app's sync is open from `[AppID N] Starting sync` to that app's
  `YldWriteCacheDirectoryToFile`, or 180 s after its last line. An open sync blocks parking and restores a
  parked interface. The launch sync is over before the game has focus, and the exit sync starts after the
  game process is gone, when the interface is already being restored, so in the normal order neither
  overlaps a park; the guard is for the abnormal ones. The sync itself is the client's work, not the
  helper's (INFERRED from the log's origin; the conflict **dialog** is the helper's, and `CloudConflicts`
  already settles conflicts before the client starts).
- **Lines.** `steam_ui_park_ready`, `park_begin` (with the helper's processes, resident and swapped MB, and
  `/proc/meminfo`'s MemAvailable, SwapFree and `Gpu:` before), `parked`, `park_settled` (10 s later, with
  `held=` and the same numbers after), `park_waits`, `restore_begin`, `restored`, `restore_late`,
  `restore_failed`, `park_hold_missed`, `park_failed`, `park_request`. The app records each as an
  `events.jsonl` event `steam.ui_<step>` with the line's fields.
- **Not applied** on the x86-64 client (it starts its helper another way) or the desktop UI.
- `droiddeck-agent`'s `quit` no longer waits its full timeout for a game nobody asked to end when the
  client's DevTools does not answer; it signals at once. Before, `quit` during a park would have sat 15 s.

Interaction with the existing features:

- **Whole-session freeze** (`freezeGameAfterMs`, `suspendPolicy`): the controller is frozen and thawed with
  everything else. A park in progress stays parked; nothing is held open that the freeze needs.
- **`standbyAfterGame`**: the game exits (restore), is launched again hidden, gets focus, and is parked
  again after the dwell; then the session freezes. The frozen session is then smaller by the helper.
- **`steam_native_prestart`**: unaffected; parking needs a focused game with a reaper.
- **Offline mode**: nothing here uses the network. Offline syncs open and close in the same second
  (`Failed sync for 'AC Launch,Sync Disabled,'` then `YldWriteCacheDirectoryToFile`).
- **`webhelper-trim`**: do not combine; its helper restart would be held by the gate, which is the point of
  this switch, but its own "back" line would then never print.
- **zRAM client compression** (`bl_client_pressure`): it signals `steam` and `steamwebhelper` processes with
  SIGURG; with no helper there is less to compress.

What a parked interface costs the player: the Steam overlay, quick access menu, toasts and Steam's on-screen
keyboard do nothing until the interface is back. The first press of Steam / quick access restores it and is
itself lost; the second one works. A game that asks Steam for text input while parked gets none.

## 5. What the off-phone rig proved

Runs of `.github/workflows/offphone-rig.yml` on this branch (redroid on `ubuntu-24.04-arm`, the real arm64
Steam client build 1793740892 at its sign-in screen, GL on llvmpipe, nothing signed in, no game through
Steam). GPU memory does not exist there (`gpu_mb=-1` in every line), so none of the savings were measured.

**`steam-x-soft`** (scenario `steam-ui-x-soft`, run 46118945996, VERIFIED):

- `== steam-x-soft: Xwayland :0 (Steam's interface) starts without glamor`, `Xwayland :1 keeps glamor: the
  games' server (:0 is Steam's)`; read back from the two servers' environments: `XWAYLAND_NO_GLAMOR=1` in
  `:0` only. The app recorded `steam.x_soft {applied: 1, display: ":0", game_display: ":1"}`.
- Steam's interface still reached the screen and its sign-in page through the shared-memory path: the app's
  own `steam.ui_first_frame` at 70.6 s and the sign-in page at 77.3 s (control run of the same build: 77.3 s
  and 83.3 s), with 44 frame rows in `frames.csv` against the control's 42.
- The first run (46116889411) took glamor from **both** servers: wlroots writes its lock files without a
  final newline, `read` returned non-zero and the wrapper skipped the comparison. Fixed in efe11bd3, with a
  test. A unit test with a newline had passed; the rig caught it.
- Not shown: what the interface looks like (the rig's screenshot is black in the control too), its speed, or
  any memory number that transfers. On the rig the control's glamor is already software.

**`steam-ui-park`** (scenarios `steam-ui-park`, `steam-ui-park-kill`; probes `steam-ui-helper-term`,
`steam-ui-helper-kill`), VERIFIED:

- The controller starts with the session (`steam_ui_park_ready`), takes a request, ends the helper in 0.5 s
  (`steam_ui_parked took_ms=507`, 4 processes and 1.67 to 1.83 GB resident to 0; the rig's helper is that
  large because it renders in software), and the client's **immediate restart of the helper is caught by
  the hold**: the marker `steam-ui-gate.held.<pid>` appeared within 2 to 6 s in all three runs (46116887446,
  46118942793 with SIGTERM, 46118944403 with SIGKILL). So Valve's `steamwebhelper.sh` on this client does go
  through `taskset` on the PATH, and the hold is in the start path.
- When the session ended, the controller removed the gate on its way out (`restore_begin
  reason=controller_exit held=1`).
- **The rig cannot show a long park.** Signed out, this client shuts down within 10 s of its web helper
  ending, **with or without the experiment**: the two probes, which have no experiment on and hold nothing,
  lost the client the same way after SIGTERM (46117780338) and after SIGKILL (46117781935), with no new
  helper. In the park runs the client's log says `Shutdown` and it exits 0 two to six seconds after the
  helper ended. On the phone, signed in with a game running, the same SIGTERM was followed by a restart and
  the game stayed up (section 1). So the signed-out state is what the rig can reach, and it is the wrong
  state for this question: whether a **signed-in** client stays up for an hour with its helper held is
  UNVERIFIED and is the first thing the phone plan checks, with no game running.
- A consequence for the request file: `park` at the sign-in screen ends the session. The automatic path
  cannot do that (it needs a running game), but do not ask for a park by hand before signing in.
- Not exercised: the stand-in game does not run through Steam on the rig, so no game was in front, no focus,
  dwell, button or cloud-sync path ran there. Those are covered by the unit tests
  (`tools/tests/test_steam_ui_park.py`, 27 tests: the state machine over a fake clock and process table,
  the cloud log, the pad rings, the real `droiddeck-steam-taskset` hold and the real Xwayland wrapper) and
  by nothing else. No Steam API call was made through the bridge while parked; the rig signs in nowhere.

Build APK run 46115957663 (commit 6a839213) was green with the switches off, including the rig's own
`steam-ui`, `stand-in-game` and `stand-in-native` scenarios and the Android unit tests
(`ExperimentsTest`, `GameExitWatchTest`).

## 6. Expected numbers on the 10a (arithmetic from the measured budget)

Native path at the menu today (`mali-10a-native-1.md`): available 690 and 803 MB, `Gpu:` 2993 and 2859 MB, of
which game 1381, Steam's X server 902 and 763, web helper 388, client 46, gamescope 62 to 78, the games' X
server 62.

| Switch | GPU memory | Why | Mark |
|---|---|---|---|
| `steam-x-soft` | -763 to -1156 MB at the menu; -1020 MB before the game starts | the X server makes no GL context, so none of its 46 + N x 141 MB exists | mechanism VERIFIED in principle (glamor is its only GPU user), amount INFERRED |
| | +5 to +25 MB in gamescope | one texture per Steam window it uploads | INFERRED |
| `steam-ui-park` | -388 MB while parked | the helper's in-process GPU context dies with it | VERIFIED on the phone (`webhelper-trim`, 388 to 0) |
| | -151 MB more if `steam-x-soft` is off | one driver step of the X server, seen once | single observation |
| both | **-1.15 to -1.54 GB** | | INFERRED |

Other memory with `steam-ui-park`: the helper's 32 MB resident and 301 MB in swap go, which is about 100 MB
of zram at the measured 3.1 : 1 and 0.3 GB of swap space.

GPU pages are ordinary pages once freed, so available memory should rise by about the same amount: native
menu from 690-803 MB to about **1.45-1.95 GB with `steam-x-soft` alone** and **1.85-2.35 GB with both**. On
Proton (260 to 300 MB available, swap full) the first gigabyte would go to un-swapping the game before it
shows up as available. The load peak (`lmk_risk` at L+40 to 52 s in every Proton run) only benefits from
`steam-x-soft`: the park waits for the game to have focus.

Confidence: high that `steam-x-soft` removes the X server's GPU memory if the session comes up at all; medium
that the interface is as smooth as before and that nothing on Steam's display needed glamor; high that
`steam-ui-park` returns 388 MB for as long as the helper is away; **low to medium that the signed-in client
stays up with its helper held**, because the only client the rig could test (signed out) shuts down when its
helper ends and the phone evidence covers a helper that was back within seconds. `steam-x-soft` is therefore
the one to try first and the larger of the two.

## 7. What could go wrong

1. `steam-x-soft`: Steam's interface is black, slow, or tears on this driver pair. Seen at once at the
   library screen. The switch is per session; off restores today's behaviour.
2. `steam-x-soft`: the interface's commits while hidden cost the game frame time (section 3). Shows as P99.
3. `steam-ui-park`: the client reacts to a missing helper by shutting down, as the signed-out client does
   on the rig within 10 s, or later by a native "not responding" window or a restart with other flags. A
   client that shuts down takes the session and the game with it: this is the failure that matters, and it
   is why the first phone check parks with no game running. The controller
   notices a helper it did not release (`park_hold_missed`) and a client that is gone ends the session as it
   does today; a native dialog over the game would be new. Watch for a second 769 window in
   `droiddeckctl focus` while parked.
4. `steam-ui-park`: something the game does through Steam needs the interface (text input, an invite, a
   purchase overlay). StS2's menu and a run do not ask for any of these (INFERRED from the native log's
   Steam calls).
5. `steam-ui-park`: the game window loses focus for a moment (a mode change) and the interface is restored,
   then parked again 20 s later: a helper start in the middle of play. Count `restore_begin
   reason=focus_left_game` lines.
6. Both: on a phone that is not the 10a. `steam-x-soft` does not apply where Steam composites on the GPU;
   `steam-ui-park` has no device-specific part.

## 8. On-phone test plan (not run: no phone was available to this branch)

Same rules as the two Mali notes: sound off, Steam offline with the offline icon looked at, Chrome and
`chat.comb.familiar.voice` force-stopped, MemAvailable over 2.5 GB before each launch, `native-engine` on (the
path this is for; one Proton pair at the end), Debugging tools on for the sampler. Switches are
`setting-exp-steam-x-soft` and `setting-exp-steam-ui-park` in Settings > Advanced > Experiments; both need a
new session.

```
ctl=tools/droiddeckctl
$ctl ui click rail-setup; $ctl ui click --match Advanced --index 0
$ctl ui click setting-exp-steam-x-soft          # and/or setting-exp-steam-ui-park; read the state back
adb shell 'while true; do echo "@ $(date +%s)"; grep -E "^(MemAvailable|SwapFree|Gpu|Zram):" /proc/meminfo;
  dumpsys gpu --gpumem | grep -E "Global|Proc"; sleep 10; done' > sampler.txt &
$ctl start steam --wait; $ctl screenshot steam.png      # offline icon; the library drawn correctly
$ctl launch 2868840 --wait
# sit at the main menu; measure 120 s from 240 s after the launch request
$ctl pacing --seconds 120 > pacing.json
$ctl focus > focus-parked.json
$ctl quit 2868840; $ctl wait focused 769 --timeout 120; $ctl screenshot back.png
$ctl stop; $ctl logs latest
```

Runs, one session each, in this order: control, `steam-x-soft`, the park pre-check, `steam-ui-park`, both,
control again.

**Park pre-check, before any game is risked** (switch on, signed in, offline, at the library, no game):
`$ctl native -- sh -c 'echo park > /data/user/0/chat.comb.lanner/files/session/steam-ui-park'`, then watch
`session.log` for five minutes. Pass: `steam_ui_parked`, `steam_ui_park_settled held=1`, no `steam exited`,
`$ctl state` still READY, `gpu_mb` about 390 lower (540 if the X-server step repeats). Then `echo restore`
the same way: `steam_ui_restored took_ms=` and the library back on screen. If the client exits here, the
switch is not usable as built: stop, keep `steam-x-soft`, and read section 9 item 2 for what to try instead.

| Check | Where | Pass |
|---|---|---|
| Switch took | `session.log` | `event=steam_x_soft applied=1 display=:0`; `event=steam_ui_park_ready` |
| X server's GPU memory | sampler, the Xwayland pid of `:0` (`processes.txt`) | under 50 MB with `steam-x-soft`, from session start; control 760 to 1160 |
| Interface before the game | sampler `Gpu:` with Steam up, no game | about 0.7 GB with `steam-x-soft` (control 1.72 to 1.73 GB) |
| Park happened | `session.log` | `steam_ui_park_begin` 20 to 30 s after the game took focus, `steam_ui_parked`, `steam_ui_park_settled held=1`, and `gpu_mb` 350 to 400 lower in `after_` than `before_` |
| Helper's GPU memory | sampler | its pid gone from `--gpumem`, no new steamwebhelper pid while parked |
| Total | sampler `Gpu:` at the menu | control 2.86 to 2.99 GB; x-soft about 1.9 to 2.1; park about 2.3 to 2.5 (2.45 to 2.6 on the first one, less the 151 MB step if it repeats); both about 1.5 to 1.7 |
| Available at the menu | `memory.csv` | over 1.4 GB with x-soft, over 1.8 GB with both (control 690 to 803) |
| Pacing | `frames.csv` the `fp.sh` way, `pacing.json` | p99 within 2 ms of the control (29.9 to 34.4 ms on native); no rise in frames over 50 ms |
| Game still talks to Steam while parked | `native-engine.log` | no `Steam is no longer running`, no new bridge errors after the park; `Steamworks initialization succeeded!` earlier as usual |
| Nothing new on screen | `focus-parked.json` | no 769 window other than none; focused app 2868840 |
| Steam button | press Steam on the pad while parked | `restore_begin reason=steam_button`, `restored took_ms=` (record it), second press opens the menu, no park for 60 s |
| Quit | `session.log`, `back.png` | `restore_begin reason=game_exit` within 3 s of the game's exit, `restored`, the library on screen; record seconds from quit to library |
| Cloud sync at exit | Steam's `cloud_log.txt` | `Starting sync (AC Exit` followed by its `YldWriteCacheDirectoryToFile`; offline: `Failed sync for 'AC Exit,Sync Disabled,'` as in the control. One online run only with the owner's go, after the native write test of `mali-10a-native-1.md` section 6 |
| Saves | the 220-file hash list of `mali-10a-native-1.md` | all identical after every run |
| No fallback | `session.log` | no `park_hold_missed`, `park_failed`, `restore_late`, `restore_failed`, no `restarting the client` |
| Long park | one 30 minute menu sit with `steam-ui-park` | client alive, `held=1` marker still there, no helper pid, then quit as above |
| Hidden app | send Lanner home for 30 s while parked (with `suspendPolicy.steam` as the owner has it, then `auto`) | on return the game resumes; state still parked or cleanly restored |
| Proton pair | `native-engine` off, control and both | same checks; expect the gain to show in swap first |

If `steam-x-soft` draws wrongly: turn it off and try `BL_STEAM_CEF_GPU=0` alone (`$ctl env set
BL_STEAM_CEF_GPU=0`) to learn whether the helper without the GPU is an acceptable interface, which decides
whether candidate 3 is worth a switch. If the X server's memory stays near 1 GB with `applied=1`, the memory
is not glamor's and section 1's reading is wrong: capture `/proc/<pid>/environ` of both Xwaylands and stop.

## 9. Next, and what was rejected as too bold for one branch

1. **Run section 8.** Everything below depends on its numbers.
2. **Game-first launch** (candidate 4), the largest remaining prize: with `steam-ui-park`'s gate up *before*
   the client starts, the client would come up with no interface at all, the game would launch through the
   client's own launch call (`droiddeck-agent --steam-run-game` needs DevTools, so this needs a launch path
   that does not: `steam://rungameid` to the client's command pipe), and the interface would be built only
   when asked for. It would take Steam's roughly 18 s interface start out of a direct Play and keep the load
   peak clear of the helper's 388 MB. Not built: whether the client finishes signing in, takes a launch and
   runs the launch-time cloud sync with no helper at all is UNVERIFIED, first-run dialogs would have nowhere
   to show, and the rig cannot sign in to try it. The hold built here is the piece it needs; the experiment
   is one phone session with the gate pre-closed.
3. **Replay the lost Steam button press** after a restore, so one press is enough.
4. **Park earlier**: at game start instead of after 20 s of focus, once the phone runs show no launch-time
   dialog is lost, so the load peak benefits too.
5. **A purpose-built host for Steam's windows** (candidate 6) only if `steam-x-soft` costs frame time: a
   small X server or a patch to Xwayland that fills dma-bufs on the CPU would remove gamescope's per-commit
   upload. Weeks of work against a wrapper script; not justified before a measurement says so.
6. **The game's own 1.2 to 1.4 GB** is now the larger half; `mali-10a-memory-1.md` section 9 item 2 stands.

Rejected outright: anything that replaces, patches or impersonates the Steam client or its web helper;
starting the client with flags Valve removed (`-no-browser`); restarting Steam's X server under a running
client; freezing instead of ending the helper.

## 10. What broke or misled

1. `webhelper-trim`'s "no gain" was read as "the helper cannot be evicted". The same run's sampler shows the
   helper's 388 MB and one X-server step going away for one sample; the client simply restarted it.
2. The X server's memory was taken for window contents in the earlier notes ("the pixmaps are Xwayland's
   own"). Its 141 MB steps say otherwise.
3. The rig's client is signed out, and a signed-out client quits when its web helper ends. Three rig runs
   went into finding out that the park had not broken the client; the two probe scenarios now say so in one.
4. The scratch directory and `git stash` are shared between worktrees of one repository. A log file name
   collided with another branch's, and a `git stash` / `git stash pop` pair in a clean worktree popped another
   branch's stash; it was put back with `git stash store` on the same commit within two minutes. Do not use
   `git stash` in these worktrees.
5. `tools/ci/run-python-tests.sh` is not stable while other branches run it on the same machine:
   `test_login1` timed out waiting for its bus in one run and passed alone.
