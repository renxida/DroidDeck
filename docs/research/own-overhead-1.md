# Our own CPU in a fight: what each piece is, and what was cut (branch `perf/own-overhead`)

**Question.** In the first fight baseline (`mali-10a-fight-1.md`, section 8) the code we own around a native
game on the Mali bridge burns about 1.1 cores next to a game that uses 0.78, and the phone's system time is as
large as its user time. What is each of those costs, which are cheap to remove, and what is left?

**Short answer.**

- **Lanner's `Thread-18` is the Wayland compositor thread** (9.3 % of a core): one GPU blit of the game's frame
  into the screen swapchain per frame (1.2 ms of wall time each, 60 a second) plus 120 screen ticks a second.
  It is not logging, a CSV writer, input or audio. It lost its name when it attached to the Java VM; fixed.
- **Steam's controller threads (11.5 %) are fed by us at 1000 reports a second each**, from two loops in our
  preload inside the Steam client, whether or not anything changed, because "low latency" is on by default.
  Switch `pad-idle-reports` keeps the 1 ms reaction and sends unchanged reports at the hardware's own rate.
- **Our session script pages the game out in the middle of a fight.** Its zRAM watcher treats under a quarter
  of memory available as critical; a native bridge fight sits at 1.6 to 1.9 GB of 7.6. Both times a page-out
  fell inside a bench fight window, the window's longest frames followed within a second (181 ms and 232 ms).
  Switch `zram-game-late` halves the mark. This is the one item here aimed at p99 rather than at battery.
- **The launch regression is fixed**: a native launch runs the free-memory step only when the game would leave
  memory short (under about 1.9 GB available on this phone); the bridge launches skip it and get their 3.9 s
  back.
- Two more removals: the thread-hint scan slows down while it has nothing to do (`hints-idle`), and the app
  stops retrying a file Android refuses it every 2 s (no switch).
- **Left, in order of expected gain**: the bridge server (0.41 of a core; about 0.08 of it is gamescope's
  forced composite), the 120 Hz screen tick on a 60 fps game (about 0.1 of a core in three processes), the
  compositor's blit, pulseaudio mixing silence, and the syscall mix behind the system time, which the
  existing artifacts cannot break down.

Nothing here was measured on a phone by this track: no phone or adb was used. Marks: **VERIFIED** = visible in a
bench artifact (`~/projects/droiddeck/output/bench/<run>/`) or read in source at this branch; **INFERRED** =
reasoned from verified facts. Expected effects are predictions for the lab (section 7).

## 1. Method

`top -H` samples (16 per fight, 5 s apart) from `fight-native-bridge-3`, `fight-native-bridge-cap120`,
`fight-native-bridge-prof` and `menu-native-bridge-1`, averaged per thread id; the session's `app.log` (the
compositor's 10 s `[stats]`, `[perf]`, `[path]` and `[pacing]` lines carry the logging thread's id),
`session.log`, `events.jsonl`, `frames.csv`; and the code. The Perfetto traces were not read (no trace
processor on the desktop). One correction to the baseline: `top` charged `lmk-risk` 106 to 133 % of a core in a
single sample of three runs, together with `battery-sysfs`, `session-log-sync` and `proc-wait` in the same
sample. One thread cannot use more than 100; that sample is an accounting artefact, and the "lmk-risk 7 %" of
the baseline is that sample averaged in. In the other samples `lmk-risk` is under 0.2 %.

## 2. Attribution (native engine, Mali bridge, 60 fps cap; one core = 100)

| Cost | What it is | Why it costs that | Evidence |
|---|---|---|---|
| App `Thread-18`, 9.3 | The compositor thread (`comp_thread` in `waylandcomp_jni.c`, named `wl-compositor`). `thread_env()` attached it to the VM without a name, which renames it `Thread-N`. | Per game frame: gamescope's commit is read, `render_scene` blits the 1616x720 dma-buf into the 2424x1080 swapchain ("copied into the screen swapchain"), 1.17 to 1.21 ms mean wall time, 600 in 10 s, so about 7 of the 9.3. The rest: 1200 screen ticks in 10 s read from a pipe, and gamescope's protocol traffic (per frame one frame commit and eight commits with no buffer: "4800 empty attaches" in 10 s). | VERIFIED: tid 21122 is `Thread-18` in `top` and logs the `DroidDeckWayland` `[perf]`/`[pacing]` lines. The split of the 9.3 is INFERRED from the logged means. |
| App `droiddeck-vsync`, 5.0 | A `HandlerThread` whose only job is the Choreographer callback that writes the tick into the compositor's pipe (`CompositorHost.kt`). | 120 callbacks a second on a 120 Hz panel for a 60 fps game; each one is a vsync event from SurfaceFlinger, a `requestNextVsync` binder call back to it and a pipe write. | VERIFIED: "vsync ticks at 120 Hz", 1199 to 1200 ticks per 10 s with 600 scenes. Cost per tick INFERRED. |
| App `game-thread-hints`, 2.2 (+ `HeapTaskDaemon` 0.9) | ADPF hint thread (`GameThreadHints.kt`, on with `adpf-all`). | Every second it reads `/proc/<pid>/stat` for every process and every thread of the three busiest with `File.readText` and `split`, and reports to the hint session every 50 ms. In the fight the game holds pace, so after 10 s nothing is boosted and the scan's result is unused. | VERIFIED in code and `top`. That the garbage collector's share comes from it is INFERRED. |
| App binder threads, 3.1 | Calls into the app from the system. | Not identified: buffer and frame callbacks from SurfaceFlinger for the swapchain are the likely source. | INFERRED. |
| App, per frame, other | `droiddeck_on_game_frame` is one JNI call per frame into `WaylandCompositor.onGameFrame` (a few field writes, the HUD's frame feed). `frames.csv` is written by `frame-log` twice a second from a ring, buffered. No per-frame logging, allocation or file write was found on the frame path; the compositor logs four lines every 10 s. The on-screen pad does not redraw per frame (the main thread is at 0.7). | Small. | VERIFIED in code and `top`. |
| App `lmk-risk` | Polls `/proc/meminfo` and `/proc/pressure/memory` every 2 s. | The second file is refused by SELinux every time: an exception and an `avc: denied` line, 53 in the session. CPU is small (see section 1). | VERIFIED: `app.log`. |
| Steam `CSteamControlle`, 11.5 (two threads, about 5.7 each) | Steam Input reading the two devices our preload (`fakeinput_steam.cpp`, loaded into the Steam client) serves: a Steam Deck controller on a fake hidraw node and the Steam touch device. | `deck_report_thread` and `touch_report_thread` send a full report, then sleep; with "low latency" (default on, `SessionPrefs.lowLatency`) the sleep is 1 ms for both, so each sends about 1000 unchanged reports a second. Without it: 4 ms and 8 ms. | VERIFIED in code. That the bench phone had low latency on is INFERRED from the default (the bench does not record it). |
| Session script `bash` 4.2, proot 3.6 | pid 16645, started between the agent and the buses: the zRAM watcher subshell of `droiddeck-session`. | Each 5 s: reads `/proc/*/cmdline` of every process in bash, `meminfo` once a second, and forks `sed` and `head` to read the focused app. Bash on a little core at low frequency: 0.2 s of CPU per pass. When it decides to page out, it greps every process's `maps` (one fork each): `bash` 10.8 and proot 23 in one sample of `fight-native-bridge-3`, the run with a page-out in its window. proot's steady 1.5 to 2 is the path calls of these loops and of the 0.5 s request loop (`pgrep` every 5 s). | Which subshell: INFERRED from pid order. The spike is VERIFIED in `top`; that the page-out caused it is INFERRED. An attempt to make the scan cheaper did not reduce its time on the desktop and was dropped. |
| dbus-daemon, 2.9 | The system bus the session starts for our `login1` and NetworkManager stand-ins. | Steady traffic from a client; our `login1` stand-in is idle in `top` (0.04), so the Steam client is the likely talker. Not identified. | INFERRED. |
| gamescope, 11.6 (`gamescope-xwm` 8.4) | On the bridge the session forces `gamescope_composite_force=1` (Steam's UI flickered on separate planes), so its compositor thread does a full composite pass per frame through the bridge, and commits its eight unused planes empty every frame. | A GPU pass per frame, serialised through the bridge. On PanVK with `present-dmabuf` gamescope is at 8. | VERIFIED: session.log line, the 4800 empty attaches. |
| Bridge server, 41 | See section 5. By thread: the game's connections 15.4 + 11.6 + 2.2 and the driver's event thread 3.9; gamescope's connection 6.0 and its event thread 2.1. | | Thread split VERIFIED in `top`; which client each belongs to INFERRED from thread ids next to the client's. |
| pulseaudio, 4.9 | With game sound off the sink is a FIFO ("Silent", `module-pipe-sink`): the daemon still takes the game's stream, mixes and writes silence in real time. | 2.9 main loop + 2.0 `pipe-sink`. | VERIFIED: `audio.log`, `top`. |

**System time.** 154 of 800 against 151 user. INFERRED contributors, none measured: the bridge (every Vulkan
call crosses a Unix socket; the game's main thread spends a third of its time in the bridge's client and the
server is 41), the two 1 kHz report loops and Steam's two readers (about 4000 wake-ups and 6000 system calls a
second for an idle pad), the 120 Hz tick through binder, SurfaceFlinger and the composer (42), and proot's
stops for path calls. The traces in the run folders hold scheduling, not system calls; a 5 s trace with
`raw_syscalls` counted per process is the measurement that would split it.

## 3. Changes on this branch

| Commit | Switch | What changes | Expected |
|---|---|---|---|
| Native launch: free memory before the engine only when the game would leave memory short | none (fix) | Section 4. | `game.engine` back to launch + 2.9 to 4.1 s on the bridge. |
| Compositor thread keeps the name `wl-compositor` | none | The attach passes the thread's name. | `top` and traces show `wl-compositor`. |
| Stop opening `/proc/pressure/memory` once Android has refused it | none | One shared reader (`MemoryPressure`) for the lmk watch, memory log, balloon and trim callback; after the first refused open it returns "no PSI" without trying. All four already treated a refusal that way. | No `avc: denied { read } … name="memory"` after the first in a session (53 before). |
| Experiment `pad-idle-reports` | `pad-idle-reports`, off | The Deck and touch report loops still look at the app's state every 1 ms and send a changed report at once; an unchanged one waits for the device's own interval (4 ms, 8 ms). Only with low latency on; the DS4/DualSense and Steam Controller variants are untouched. | `CSteamControlle` 11.5 to about 3 to 5; fewer wake-ups in the Steam client. Press-to-screen unchanged. |
| Experiment `hints-idle` | `hints-idle`, off | While nothing is boosted and nothing may be (pace held 10 s, power saving, critical thermal), the per-thread scan runs every 5 s. Off pace it scans at once, as now. | `game-thread-hints` 2.2 to under 1; less garbage collection. |
| Experiment `zram-game-late` | `zram-game-late`, off | The watcher's mark for paging out a running game: an eighth of memory available instead of a quarter (946 MB on the 10a), three sixteenths with memory pressure. The Steam client's compression is unchanged. | No "compressing idle game memory" in a native bridge fight; the 180 to 230 ms frames that followed it gone; fewer frames over 50 ms. |

Tests: `FreeMemoryTest`, `MemoryPressureTest`, `GameThreadHintsTest`, `ExperimentsTest` (Kotlin);
`test_game_environment`, `test_client_zram_focus`, `test_fakeinput_idle` (Python).

**The page-out evidence** (VERIFIED, n = 2 of 2): `session.log` "zRAM: low memory (available 1693 MB),
compressing idle game memory (43 processes …)" lands 5.6 s into the window of `fight-native-bridge-3`, and the
frames of the next second are 72, 181 and 78 ms; in `fight-native-bridge-cap120` it lands at 9.1 s and the next
frames are 52, 232 and 59 ms. In `fight-native-bridge-2` and `-prof`, with no page-out there, the same seconds
show 35 to 57 ms. Later the watcher logs "the game read back most of its last page-out". That the long frames
are the game faulting its pages back is INFERRED.

## 4. The launch regression

Since `08de1214` a native launch asks for the free-memory step as a Proton launch does, with Proton's need
(3072 MB available). With Steam up about 2.7 GB is available on this phone, so the step always ran: 616
packages asked to close (5 MB gained), then the balloon, 3.9 s, for 0.3 GB (VERIFIED: `memory.freed`,
`reason=game_exec`, `ms=3868`).

Now `droiddeck-game-env` adds `engine=native` to its request, and for that request the app runs the step only
when `FreeMemory.shortAfter` says the game (1536 MB; 1.36 GB measured in the bridge fight) would leave the
device where `LmkRisk` warns: lmkd's free swap under 10 % of swap, or under 5 % of memory available. On the
10a that is under about 1.9 GB available. The bridge launches (2689 and 2751 MB) skip it and answer
`method=none` at once; the open-driver launch that later had `memory.lmk_risk` (1868 MB) still runs it. Proton
launches and the session-start step are unchanged.

## 5. The bridge server and Steam Input

**Bridge.** The submodule `external/MaliBridge` is not checked out in the worktrees; its pinned commit
(`95a1e600`) was fetched into a scratch folder and read. VERIFIED in that source: every Vulkan call of a client
is serialised over a Unix socket to a connection thread in the server (`server_conn.c`); calls that need no
answer are batched (one `recvmsg` for a burst), calls that need one are a round trip (17.8 us in the session's
self-test); calls from several connections are put back in order under a mutex and condition variable;
descriptor batching exists and is off in both modes (upstream: flicker in one game, no measured gain). The
bench ran with "Safe mode: on". The server has its own instrument that nobody has run in a fight:
`VKBRIDGE_STATS=<seconds>` in the game's environment prints synchronous and asynchronous counts per command,
and the same name in the server's `<cache dir>/env` prints time per command and time spent waiting for order.
Nothing in the bridge was changed here (another track has `perf/mali-bridge-default`). Proposals: run that
instrument in one fight; an arm with Safe mode off; and stop forcing gamescope's composite on the bridge once
the Steam UI flicker has a cause, since the game needs no composite (about 8 of the 41, plus most of
gamescope's own 8.4).

**Steam Input.** Described in section 2 and addressed by `pad-idle-reports`. The on-screen pad itself sends
nothing at a fixed rate: the app writes state into a shared ring when it changes, and it is the preload's loops
inside Steam that turn the ring into a report stream. If the switch holds, a further step is a longer idle
interval; that depends on Steam accepting a Deck slower than hardware and was not tried.

## 6. What remains, ranked

For p99 in battle animations:

1. **`zram-game-late`**, if the lab confirms it (the only item with frame evidence).
2. **Bridge server** (41) and the third of the game's main thread inside the bridge's client: per-command
   statistics first, then the Safe mode arm. Evidence: the profiler's call tree in the baseline.
3. **gamescope's forced composite** on the bridge: a GPU pass and a bridge round per frame between the game and
   the screen (gamescope 11.6 + about 8 in the server). It also sits in the 25 ms from the game's frame to the
   panel.
4. Everything else below moves battery, not p99: none of it runs on the game's thread.

For battery:

1. Bridge server, as above.
2. **`pad-idle-reports`**: about 7 to 8 of a core in Steam plus wake-ups.
3. **The 120 Hz tick for a 60 fps game**: `droiddeck-vsync` 5, part of the compositor thread's 9.3, and
   SurfaceFlinger's `app` and binder threads (3.6 + 3.5). The compositor uses the tick to render at most once
   per refresh and to time presents, so halving it is a pacing change for the present-path track, not a cheap
   removal.
4. **The compositor's blit** (about 7): removed by the direct-present work, not here.
5. **pulseaudio with sound off** (4.9): a sink that discards without mixing, or no stream at all.
6. `hints-idle` (about 2), the zRAM watcher's scan (4 on a little core), dbus (2.9, unidentified).

## 7. For the lab

Build: `perf/own-overhead`, CI run and APK in lab request "lab: our own CPU overhead in a fight". Control and
arms are native, bridge, fight; the numbers to read are the per-thread CPU table (`cpu.top_threads`), frames
over 50 ms per minute, the animation p99 and press-to-screen.

## 8. Not done

- No change to the bridge, gamescope, the screen tick, the compositor's blit or the audio server.
- The zRAM watcher's `/proc` scan is as it was; the attempted rewrite was not faster.
- The DS4/DualSense and Steam Controller report loops still send every interval.
- The Perfetto traces were not analysed; the system-time split is inferred.
