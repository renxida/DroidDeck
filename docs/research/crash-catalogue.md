# What kills a game in this stack, and how to stop each cause

Research note, branch `test/crash-hardening`, 2026-11-12. Target: Slay the Spire 2 (StS2, Steam app
2868840, Godot 4 fork "MegaDot" with CoreCLR, Windows build) on a Pixel 10 (Tensor G5, PowerVR
DXT-48-1536, 12 GB; the Pro has 16 GB [P1]) under proot + FEX + Proton/Wine + DXVK + the system
PowerVR Vulkan driver.

Status words: **VERIFIED** means read in source (path and line given) or reported with logs in a
primary bug report; **INFERRED** means reasoned from verified facts but not observed on a Pixel 10.
Nothing here was measured on a Pixel 10: this worker had no device. Section 6 lists the on-device
experiments and the log lines that settle each open question.

## 1. Question

Which mechanisms end a running game (or the whole session) in this stack on a Pixel 10, how often
does each one happen, and what removes it? "Crash" here covers four different outcomes that users
all describe the same way:

| Outcome | What the user sees | Who can observe it afterwards |
|---|---|---|
| A. Whole app killed | back on the Android home screen | Android's `ApplicationExitInfo` (next app start) |
| B. Session guest died, app alive | DroidDeck's "session ended" screen | `HostProcess` exit status (`Process.waitFor`) |
| C. Game process died, Steam alive | back in Big Picture | Proton's exit status, seen only by the compat-tool wrapper |
| D. Device reset | phone reboots | boot reason, the session's last event older than the boot |

Before this branch only A had a cause (`LOW_MEMORY` notice) and B had a raw status number; C and D
were invisible. Classifying all four is therefore the first intervention (section 5, I1-I3).

## 2. System model: the process tree and who may kill it

```
com.droiddeck.launcher (app process, oom_score_adj 0 while visible, FGS while not)
 ├─ compositor + Vulkan driver (in-process: a GPU driver fault is a native crash of the app)
 └─ proot (tracer)                          <- SessionService.kt:718, HostProcess.kt:44
     └─ droiddeck-session (bash) -> Steam client (FEX) -> reaper -> steam-compatibility
         └─ droiddeck-game-env (python3, arm64)   <- execs Proton; waits for StS2 (DROIDDECK_GAME_EXIT)
             └─ proton (python) -> wine64 steam.exe -> SlayTheSpire2.exe (FEX, CoreCLR, Godot)
```

Every process below the app is a *phantom process* to Android: forked by the app but unknown to
ActivityManager. Killers, in the order they matter:

1. **lmkd** kills registered app processes only, then the whole cgroup, so a kill of the app takes
   every guest process with it (outcome A).
2. **Phantom process killer** (Android 12+) kills phantom processes directly (outcome B or C).
3. **Vendor watchdogs** (Xiaomi HyperOS) kill large native processes (B or C). Not on Pixel.
4. **The kernel OOM killer**, a last resort below lmkd (B or C).
5. **The game's own runtime** (CoreCLR FailFast, Wine unhandled exception) and **FEX** (unhandled
   host fault) end the game (C).
6. **The GPU driver**: in the game it is a fault in C; in the app's compositor it is A
   (`REASON_CRASH_NATIVE`); in the firmware it is D (Pixel `reset,warm,gnoc_error`).

## 3. Catalogue

Ranked by expected frequency on a Pixel 10 for StS2. "Signal" is the log evidence that counts an
occurrence; lines marked *new* are added by this branch.

### C1. lmkd kills the app and its whole session (outcome A) - most frequent cause in the field

**Mechanism (VERIFIED).** lmkd tracks only processes that ActivityManager registers, picks a
victim by `oom_score_adj` slot (with `ro.lmk.kill_heaviest_task`, the largest RSS of a registered
process in that slot: `proc_get_heaviest`, `/proc/<pid>/statm`), and kills its process group [A3]. The guest's gigabytes live in phantom
children, so lmkd sees DroidDeck as a ~110-180 MB process at adj 0 and still has to kill it once
everything above adj 0 is gone. Field logs show exactly that:
`Kill 'com.droiddeck.launcher' (5593), uid 10153, oom_score_adj 0 to free 109740kB rss ... reason:
device is low on swap (0kB < 419428kB) and thrashing (63%)` [D2227]. Same class in [D945] (16 GB,
loading peaks), [D1533] (12 GB, Android 16, right after the client was compressed into zram),
[D2497] (8 GB), [D2510] (16 GB, 14.4 GB used). The lmkd defaults behind those reasons:
`psi_partial_stall_ms` 70, `psi_complete_stall_ms` 700, `thrashing_limit` 100 %,
`swap_free_low_percentage` 10 % [A3]; Pixel builds can override them with `ro.lmk.*` properties.

**Pixel 10 budget (INFERRED).** 12 GB RAM, of which Android itself already used 4.8-9 GB at session
start in the reports above. Steam's client is about 1 GB resident until compressed [D1533]. StS2 is a
2D game, but CoreCLR + Godot + DXVK + FEX's code cache make 1.5-2.5 GB plausible. So on the base
Pixel 10 the session runs within ~1-2 GB of lmkd's swap/thrashing thresholds during loading, which
is where every report above died.

**What exists (VERIFIED).** zram page-out of the Steam client after game start and on pressure
(`droiddeck-session` `bl_client_pressure`, lines 800-820; `bl_memory_tight` 782); an optional RAM cap
that triggers the same page-out (line 705, 988); VRAM cap through `dxgi.maxDeviceMemory`
(`droiddeck-game-env` `vram_cap`, line 401); `oomadj.c` stops guest programs from raising their own
oom score to 900 [R oomadj.c:1-7]; a 10 s memory log (`MemoryReadout.kt` `MemoryLog`); the
`LOW_MEMORY` notice on next start (`SessionArtifacts.kt` `lastExitForMemory`, line 655).

**Fixes.** (a) *new* every app death is classified on next start: `DROIDDECK_EXIT_CAUSE scope=app
class=lmk` (I2). (b) *new* `onTrimMemory` writes `DROIDDECK_MEMORY_PRESSURE level=...
availableMb=... swapFreeMb=... psiSomeAvg10=... psiFullAvg10=...` so the lead-up to a kill is in the
logs (I3). (c) Shrinking the game's managed heap (C6) and a save-now warning before a likely lmkd kill are
in `memory-footprint.md`; stopping only the game, which issue [D2510] asks for, is shown there not
to be safe.
Nothing an app can do changes its own oom_score_adj below what Android assigns.

### C2. Phantom process killer (outcome B or C)

**Mechanism (VERIFIED, AOSP `main`).** Two independent rules, both gated on the feature flag
`settings_enable_monitor_phantom_procs` [A1, A2]:

1. *Count*: when more than `max_phantom_processes` (default 32,
   `ActivityManagerConstants.java:229`) phantom processes exist system-wide,
   `trimPhantomProcessesIfNecessary` sorts them by the oom adj of their parent app, youngest first
   within an adj, and kills the surplus with "Trimming phantom processes"
   (`PhantomProcessList.java:407-449`). A Steam + Proton session has well over 32 processes
   (proot, bash, Steam, webhelpers, FEXServer, wineserver, services.exe, winedevice, explorer, the
   game ...), so with the flag on, the youngest - the game - is killed by count alone (INFERRED from
   the sort order; E5 checks it).
2. *CPU*: `checkExcessivePowerUsage` kills a phantom *and its parent app* with
   `REASON_EXCESSIVE_RESOURCE_USAGE` "Caused by child process: excessive cpu ..."
   (`PhantomProcessList.java:480-499`, `ActivityManagerService.java:15170-15262`), but only for apps
   whose proc state is `>= PROCESS_STATE_HOME`. A running foreground service keeps DroidDeck below
   that, so the CPU rule does not apply while `SessionService` is in the foreground.

**What exists (VERIFIED).** `PhantomProcessLimit.kt` reads the flag (and the Android 12
`device_config` variant), offers the adb/wireless-debugging commands, and Steam sessions are gated
on it being off (`blocksSteam`, line 103). Upstream reports show the gate's edge cases (HyperOS,
HarmonyOS, Android 12) [D713, D1083]. The Android 14+ developer toggle reverts when Developer options
are turned off, which is why the fix text asks users to leave them on.

**Fix.** *new* a SIGKILL of the guest while the flag reads `ENABLED` or `UNSET` is logged as
`DROIDDECK_EXIT_CAUSE scope=session class=sigkill suspect=phantom_process_killer` and the end
screen explains it with the existing fix sentence (`R.string.session_end_phantom_killed`). An
`EXCESSIVE_RESOURCE_USAGE` app exit with "child process" in its description is classified
`suspect=phantom_process_killer` too. The kill itself is only in the system's event log
(`am_kill`), which an app cannot read.

### C3. Address-space exhaustion on a 39-bit kernel (outcome C, at startup)

**Mechanism (VERIFIED).** Android GKI kernels give a process 512 GiB (39-bit VA)
[`tools/proot/PATCHES.md:146-155`]. CoreCLR 7+ reserves its whole GC range up front:
`regions_range = min(VirtualMemoryLimit/2, max(256 GiB, 2 x RAM))` unless `GCRegionRange` or a hard
limit is set, and `virtual_alloc` failure returns `E_OUTOFMEMORY` from GC init
(dotnet/runtime `release/8.0` `src/coreclr/gc/gc.cpp:48600-48624`, `14489-14501`) [N1]. Under Wine
`GetVirtualMemoryLimit` is the Windows 128 TiB limit, so the request is 256 GiB - half the device's
address space, after proot, FEX and Wine have fragmented it. The same 256 GiB reservation kills
FINAL FANTASY TACTICS on a Galaxy S25 Ultra [D415].

**What exists (VERIFIED).** `GameEnvironment.defaults` sets `DOTNET_GCRegionRange=0x800000000`
(32 GiB) for every game (`GameEnvironment.kt:33`), and proot's loader layout keeps 64-336 GiB free
(`0026-arm64-kernel-program-layout.patch`). With a hard limit `H` and no region range the runtime
would reserve `5 H` (gc.cpp:48613); the explicit range wins over that.

**Signal.** *new* `DROIDDECK_GAME_EXIT ... class=out_of_memory` (exit byte 0x0E, `E_OUTOFMEMORY`) or
`0x17`. Proton log: `err:virtual:allocate_virtual_memory out of memory`.

### C4. CoreCLR under FEX: JIT/W^X, self-modifying code and GC suspension (outcome C)

**Evidence (VERIFIED, primary reports).**
- FEX's wiki rated StS2 "Unplayable: embeds CoreCLR, crashes early at startup" [F4].
- The native Linux StS2 build crashes deterministically in the multithreaded `MainMenuEssentials`
  preload on ARM64 under FEX with `AccessViolationException` in
  `ScriptManagerBridge.GetOrCreateScriptBridgeForType`; TSO and paranoid TSO did not help, CPU
  count moved the crash point [F1] (closed 2026-10-14, reason not visible).
- Barotrauma (.NET 8 CoreCLR) under FEX: random corruption in vectorised/unsafe code, exit 134/139;
  `DOTNET_EnableWriteXorExecute=0` required, `SMCChecks=full` removed the hard crashes only [F2].
- .NET 6 JIT code under ARM64EC FEX: `Invalid instruction in entry block`, `c000001d`, after
  "Handled self-modifying code" on the JIT heap [F3].

**What exists (VERIFIED).** `DOTNET_EnableWriteXorExecute=0` for every game (`GameEnvironment.kt:34`),
full TSO for any game with `coreclr.dll` (`droiddeck-game-env` `DOTNET_FEX`, line 55; owned by the
TSO worker), Godot's Sentry crash handler disabled (`godot_sentry`, line 125).

**GC suspension path (VERIFIED parts, INFERRED risk).** Under Wine, CoreCLR is a Windows process.
.NET 8 uses the special user-mode APC (`QueueUserAPC2`) for thread activation only when CET shadow
stacks are on (`src/coreclr/vm/threads.cpp:8204-8231`), so here it suspends with `SuspendThread` +
`GetThreadContext` and redirects threads by `SetThreadContext`. Wine implements `SuspendThread` by
wineserver sending `SIGUSR1` to the thread (`server/thread.c:925,1442`), whose handler parks in
`wait_suspend` (`dlls/ntdll/unix/signal_x86_64.c:2506-2543`). Under FEX that SIGUSR1 is a guest
signal: FEX either defers it to the end of a non-interruptible section or reconstructs the x86
state from the host context (`SignalDelegator.cpp:106-138`, `RestoreRIPFromHostPC`; deferral at
616-672). The GC is correct only if that reconstructed RIP/register state is exact at the
interruption point; an imprecise RIP inside a translated block would let the GC treat a thread as
being at a safe point, which yields exactly the "different crash site every run" corruption of
[F2]. This is a hypothesis to test, not a finding. Experiment E4 tests it cheaply:
`DOTNET_gcConcurrent=0` and `DOTNET_GCgen0size` change the number of suspensions, not the code.

**Signal.** *new* `DROIDDECK_GAME_EXIT ... class=dotnet_unhandled_exception` (0x52, `0xE0434352`),
`fail_fast` (0x09, `0xC0000409`), `dotnet_internal_error` (0x06, `0x80131506`),
`access_violation` (0x05), `illegal_instruction` (0x1D, an x86 instruction FEX could not run).

### C5. The game's process is killed but the logs say it exited cleanly (outcome C) - diagnosis gap

**Mechanism (VERIFIED).** wineserver initialises a thread's exit code to 0 (`server/thread.c:368`);
when a Wine process dies without telling the server (SIGKILL, host crash) its socket hangs up and
the server ends the thread as a normal death with that code (`server/thread.c:545`), so
`GetExitCodeProcess` returns 0. Proton's `steam.exe` returns that code and Proton exits with it
(`proton` script, `run_proc` `subprocess.call` at lines 1944-1947, `sys.exit(rc)` at 2035). If the
`wine64 steam.exe` process itself is signalled, `subprocess.call` returns `-n` and Python exits with
`256 - n`. For a normal Windows exit Wine passes the NTSTATUS truncated to 8 bits
(`dlls/ntdll/unix/thread.c:86-91`, `get_unix_exit_code`).

**Fix.** *new* Wherever `droiddeck-game-env` already waits for Proton (enhanced diagnostics, a
launch with a cleanup step; the telemetry work's `DROIDDECK_TIMELINE event=game_exit` comes from the
same place) it now also prints `DROIDDECK_GAME_EXIT app=2868840 status=N class=... seconds=S
detail="..."`. The normal path `exec`s Proton, so nothing above Proton sees the status; a parent has
to be the waiter, since Linux reports an exit status only to the parent (a pidfd or a sibling cannot
read it). This branch therefore makes waiting a per-game default for StS2 only (`EXIT_WATCH_GAMES`,
one ~10 MB arm64 Python parent; `DROIDDECK_GAME_EXIT_WATCH=1` or `0` in the game environment
overrides it for any game). Other games keep `exec`, so the process tree Steam sees and the existing
launch tests (`test_esync_packs`, `test_game_environment`) are unchanged. Making it the default for
every game is a one-line change, but its value is bounded by the finding above: a game killed from
outside still reads 0, so the gain is the Wine exception bytes. Status 0 within 30 s is
`clean_early`, explicitly "a quick quit or killed from outside".

### C6. Memory growth of CoreCLR, FEX and DXVK (feeds C1)

**VERIFIED knobs** (dotnet/runtime `src/coreclr/gc/gcconfig.h`): `GCHeapHardLimit` (line 104),
`GCHeapHardLimitPercent` (105), `GCRegionRange` (107), `GCgen0size` (87), `GCConserveMemory` 0-9
(138). Environment variables reach the Windows process through Wine unchanged.

**INFERRED.** A hard limit caps the managed heap but turns pressure into `OutOfMemoryException`
(a crash) instead of an lmkd kill; it is only safe once StS2's working set is measured (E3).
`GCConserveMemory=5` compacts more often to cut fragmentation at some CPU cost, a lower-risk first
step. FEX's code cache grows with executed code (CoreCLR's JIT output is re-translated after each
SMC invalidation, [F2, F3]); its RSS share is visible per process in `MemoryLog` (`game` column
counts every process with `WINEPREFIX`). No change made here.

### C7. GPU: PowerVR faults, device loss and full device resets (outcomes A, C, D)

**VERIFIED.** On a Pixel (Tensor, PowerVR) a D3D9 game twice took the whole phone down with boot
reason `reset,warm,gnoc_error` (Google's network-on-chip error) and GNOC/access-control records in
the last kernel log; cause unknown (`docs/development/american-nightmare-pixel-debug.md:99-118`,
`tools/pixel_reset_diagnostics.py`). The compositor treats an unknown fence completion as terminal
and real `VK_ERROR_DEVICE_LOST` through its device-loss path
(`docs/development/gpu-pending-completion.md`). Grayscale output on Pixel 10 Pro was a colour bug,
fixed in 0.7.1 [D2286]. No upstream report of `VK_ERROR_DEVICE_LOST` on Pixel 10 was found
(search of Droid-Deck/DroidDeck issues for "device lost", "PowerVR", "Pixel 10", 2026-11-12).

**Fix.** *new* a reset is now named: the next app start logs `DROIDDECK_EXIT_CAUSE scope=app
class=device_reboot` when the session's last event predates the current boot and Android has no
exit record for the app. A driver fault in the app's own compositor is `class=app_native_crash
suspect=gpu_driver_or_native_code` (crash.log holds the tombstone summary). A device-lost in the
game shows as a non-zero `DROIDDECK_GAME_EXIT` plus DXVK's own log line. StS2 is Vulkan-native
through Godot (`--rendering-driver vulkan` is forced by `godot_vulkan`), so on this path DXVK is not
in the loop: the game talks Vulkan through Wine's `winevulkan` to the PowerVR driver.

### C8. Android background rules (no current effect; a trap for a targetSdk bump)

**VERIFIED.** The app targets SDK 28 (`app/build.gradle`). Foreground-service types (Android 14),
the `dataSync`/`mediaProcessing` timeouts and the BOOT_COMPLETED rules (Android 15) and the
background-start ban (Android 12) apply only to apps targeting those levels; Android 16's job
quotas apply to all apps but only to jobs, which the session does not use [A4]. The cached-app
freezer applies to cached processes; an app with a running foreground service is not cached
(`REASON_FREEZER` is now classified in case a vendor does otherwise). Keep-alive today: a
foreground service started with a notification (`SessionService.kt:300`),
`android:stopWithTask="false"` (manifest), a partial wake lock with a 12 h timeout
(`SessionService.kt:2063-2069`), a Wi-Fi high-performance lock where safe, `START_NOT_STICKY`.

**Fix.** *new* `tools/tests/test_session_keepalive.py` fails the build if `targetSdkVersion` is
raised to 34+ without an `android:foregroundServiceType` (and its permission) that has no time limit.

### C9. Vendor memory watchdog (outcome B or C; not Pixel)

**VERIFIED** [D1696]: HyperOS `MiuiSentinelMemoryManager` SIGKILLs any process without a
`ProcessRecord` whose PSS stays above 6 GiB for three checks 20 s apart; status 137, no app log.
*new* classified as `suspect=vendor_memory_watchdog` on Xiaomi/Redmi/POCO, with an end-screen
explanation (`R.string.session_end_vendor_memory_killed`).

### C10. SIGHUP storm while the session is suspended (outcome B)

**VERIFIED report, cause open** [D1361]: two minutes into a manual pause every process of the app's
uid got SIGHUP and the guest exited 1. *new* a SIGHUP of the guest root is `class=sighup`; when the
guest's own script turns it into exit 1 it stays `class=exit_code`, so the `events.jsonl` timeline
(`session.suspended` followed by `exit.classified`) remains the way to count it.

### C11. StS2-specific launch failures seen upstream (outcome C)

**VERIFIED reports.** `mscoree.dll not found` / "IL-only binary sts2.dll cannot be loaded" then
`c0000005` at address 0 on Adreno devices with 0.4.0 [D678, D1120] (closed 2026-11-06 without a
visible root cause); runs from the default Steam library but not a second library [D1315]; the
working configuration there used `--accessibility disabled --rendering-driver vulkan`. Godot's
accessibility layer is worth an A/B on PowerVR (E6).

## 4. Interventions, ranked

| # | Intervention | Status | Expected effect | Cost | Risk |
|---|---|---|---|---|---|
| I1 | Classify every guest exit (`DROIDDECK_EXIT_CAUSE scope=session`), explain phantom-killer and vendor-watchdog kills on the end screen | **built** (`ExitCause.kt`, `SessionService.classifyGuestExit`, `SessionActivity.exitCauseHint`) | turns "status 137" into a named cause; removes the most common mis-diagnosis | one /proc read at exit | wrong blame if another killer sends SIGKILL while the flag is on; text says "most likely" |
| I2 | Classify the previous app death on next start, incl. device resets (`scope=app`) | **built** (`SessionArtifacts.classifyAbandoned`, `ExitCause.ofAbandoned`) | counts lmkd kills vs native crashes vs resets per run | one `getHistoricalProcessExitReasons` call | a user power-off also reads `device_reboot` |
| I3 | Memory-pressure trail (`DROIDDECK_MEMORY_PRESSURE`, `memory.trim` event) | **built** (`SessionService.onTrimMemory`) | shows how close lmkd was before C1 | negligible | Android 14+ may deliver fewer running-level trims (unverified) |
| I4 | Game exit classification (`DROIDDECK_GAME_EXIT`) | **built**: on every waited launch; waiting is the default for StS2 only, `DROIDDECK_GAME_EXIT_WATCH=1/0` per game | first per-game crash statistics for StS2 (C3-C5) | ~10 MB RSS, +1 process for StS2 | StS2's process tree under Steam's reaper gains a parent (same shape as the existing enhanced-diagnostics path) |
| I5 | Keep-alive guard test for a future targetSdk raise | **built** (`test_session_keepalive.py`) | prevents a silent SecurityException on API 34+ | none | none |
| I6 | StS2 managed-heap tuning: `DOTNET_GCConserveMemory=5`, then a measured `DOTNET_GCHeapHardLimit` | **built on `perf/memory`** (`memory-footprint.md` section 2; `DROIDDECK_DOTNET_HEAP`) | lowers peak RSS, fewer C1 kills on the 12 GB Pixel 10 | per-game env | too low a limit is an `OutOfMemoryException` crash |
| I7 | Stop only the game (not the session) when PSI and swap predict an lmkd kill | **rejected**: lmkd polls every 10 ms once swap is low and spares adj 0-200 unless stalled, so an app-side stop is either too late or kills games lmkd would spare (`memory-footprint.md` section 4); a save-now warning (`DROIDDECK_LMK_RISK`) is built instead | - | - | - |
| I8 | Test the GC-suspension hypothesis (C4) with `DOTNET_gcConcurrent=0`, FEX `SMCChecks=full` | proposed (E4) | if confirmed, a per-game default that removes the CoreCLR corruption class | env only | slower GC pauses |

## 5. What the new code does on a device

| Line (logcat tag, also in `app.log`) or event (`events.jsonl`) | When |
|---|---|
| `SessionService: DROIDDECK_EXIT_CAUSE scope=session class=<clean,exit_code,sigkill,sigterm,sighup,crash_signal,signal> suspect=<...> status=N signal=SIGx detail="..."` + `exit.classified` | the guest ends while the session is running |
| `SessionArtifacts: DROIDDECK_EXIT_CAUSE scope=app class=<lmk,excessive_cpu,app_crash,app_native_crash,anr,freezer,user,sigkill,device_reboot,unknown> ...` + `exit.classified` in the dead session's folder, and the same line appended to `ended-without-teardown.txt` | first app start after a session died with the app |
| `SessionService: DROIDDECK_MEMORY_PRESSURE level=L availableMb=... swapFreeMb=... psiSomeAvg10=... psiFullAvg10=...` + `memory.trim` | Android calls `onTrimMemory` during a session |
| `DROIDDECK_GAME_EXIT app=ID status=N class=... seconds=S detail="..."` (stderr of the compat tool, in `session.log`; also kept in the telemetry bundle's `crash-lines.txt`) | a Proton `waitforexitandrun` exit the wrapper waited for: StS2 by default, any game with `DROIDDECK_GAME_EXIT_WATCH=1`, enhanced diagnostics |

The telemetry work on main records the raw facts these lines interpret: `app.exit` events
(ApplicationExitInfo, `telemetry/AppExits.kt`), `game.exited` (gamescope's focused pid leaving
`/proc`, `telemetry/GameExitWatch.kt`) and `crash-lines.txt`. `DROIDDECK_EXIT_CAUSE` adds the verdict
(which killer, which suspect), the device-reboot inference for D, and the user-facing explanation for
the phantom process killer; the two read the same exit records independently.

## 6. On-device experiment plan (Pixel 10)

Each experiment names the line that confirms or refutes it. Run with Developer options on and the
child-process restriction disabled unless stated.

- **E1 Baseline census.** Play StS2 for 30 min, quit from the menu. Expect
  `DROIDDECK_GAME_EXIT app=2868840 status=0 class=clean`. Any other class is a crash to file under
  C3-C5 by its class. Repeat 10 times; the count per class is the frequency signal.
- **E2 lmkd margin.** Same run, record `Memory:` lines (10 s) and every `DROIDDECK_MEMORY_PRESSURE`.
  Confirms C1's risk if `available` drops under ~1 GB or `psiFullAvg10` > 10 during loading. A kill
  shows next start as `DROIDDECK_EXIT_CAUSE scope=app class=lmk`. Refuted if minimum `available`
  stays above 2 GB.
- **E3 Heap size.** From E2's `game` column and a `DOTNET_gcServer=0 DOTNET_GCConserveMemory=5` run,
  compare peak `game` MB and load time. Adopt I6 only if peak drops > 10 % with < 5 % load-time cost.
  Set a hard limit only at >= 2x the measured managed peak.
- **E4 GC-suspension hypothesis (C4).** If E1 shows `access_violation`, `dotnet_internal_error` or
  `fail_fast`: rerun 10x each with `DOTNET_gcConcurrent=0` and with `DOTNET_GCgen0size=0x4000000`
  (fewer GCs). A clear drop in crash rate with fewer suspensions supports the hypothesis; no change
  refutes it.
- **E5 Phantom killer.** Turn the restriction back on (Android 14+ toggle), start a non-gated mode,
  launch StS2. Expect the youngest processes to die and
  `DROIDDECK_EXIT_CAUSE scope=session class=sigkill suspect=phantom_process_killer` with the end
  screen naming the fix. `adb logcat -b events | grep am_kill` shows "Trimming phantom processes".
- **E6 PowerVR.** A/B `--accessibility disabled`; watch for `VK_ERROR_DEVICE_LOST` in the game log
  and `class=app_native_crash` / `class=device_reboot` lines. A `device_reboot` with boot reason
  `reset,warm,gnoc_error` (`tools/pixel_reset_diagnostics.py`) reproduces C7; stop load testing then.
- **E7 Background survival.** Start a game, switch to another app for 5 min with the session not
  paused. Expect no `class=excessive_cpu` (FGS keeps proc state below HOME, C2 rule 2).

## 7. References

Android (AOSP sources read 2026-11-12):
- [A1] `frameworks/base/services/core/java/com/android/server/am/PhantomProcessList.java`,
  https://github.com/aosp-mirror/platform_frameworks_base (branch `main`), lines 407-449, 480-499.
- [A2] same tree, `ActivityManagerService.java` 15170-15262 (`checkExcessivePowerUsage`),
  `ActivityManagerConstants.java` 229 (`DEFAULT_MAX_PHANTOM_PROCESSES = 32`).
- [A3] `system/memory/lmkd/lmkd.cpp`,
  https://android.kernel.org/platform/system/memory/lmkd/+/refs/heads/main/lmkd.cpp (`DEF_*`
  defaults, `proc_get_heaviest`).
- [A4] Android developers, "Changes to foreground services",
  https://developer.android.com/develop/background-work/services/fgs/changes
- [P1] Android Central, "Google Tensor G5" (CPU, PowerVR DXT-48-1536, 12/16 GB),
  https://www.androidcentral.com/phones/google-pixel/google-tensor-g5

Runtime sources:
- [N1] dotnet/runtime `release/8.0`: `src/coreclr/gc/gc.cpp` 14489-14501, 48600-48624;
  `src/coreclr/gc/gcconfig.h` 87-138; `src/coreclr/vm/threads.cpp` 8204-8231.
- Wine `master` (wine-mirror/wine): `server/thread.c` 368, 545, 925, 1442;
  `server/process.c` 873-885, 1022-1032; `dlls/ntdll/unix/thread.c` 86-91;
  `dlls/ntdll/unix/signal_x86_64.c` 2487-2567, 2804-2807.
- Proton `proton_10.0`, `proton` script lines 1944-1947, 1973, 2014-2035.
- FEX `main`: `Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator.cpp` 106-138, 255-270, 616-672.

Bug reports:
- [F1] FEX-Emu/FEX#5991 StS2 native build crash during multithreaded preload.
- [F2] FEX-Emu/FEX#5766 Barotrauma .NET 8 random memory corruption.
- [F3] FEX-Emu/FEX#6014 .NET 6 JIT "Invalid instruction in entry block" under ARM64EC.
- [F4] FEX wiki, "Slay the Spire 2", https://wiki.fex-emu.com/index.php/Slay_the_Spire_2
- Droid-Deck/DroidDeck issues (read-only): [D415], [D678], [D713], [D945], [D1083], [D1120], [D1315],
  [D1361], [D1533], [D1696], [D2227], [D2286], [D2497], [D2510]
  (https://github.com/Droid-Deck/DroidDeck/issues/N).

Literature:
- Y. Liang et al., "Acclaim: Adaptive Memory Reclaim to Improve User Experience in Android
  Systems", USENIX ATC 2020 - why Android's reclaim and lmkd mis-handle a foreground app's working
  set, the C1 mechanism in general form.
- Y. Liang et al., "Mismatched Memory Management of Android Smartphones", HotStorage 2019.
- J. Weiner, "PSI - Pressure Stall Information", Linux kernel documentation
  (`Documentation/accounting/psi.rst`) - the signals lmkd and I3 read.
- R. Gouicem et al., "Risotto: A Dynamic Binary Translator for Weak Memory Model Architectures",
  ASPLOS 2023 - x86-on-Arm memory-ordering correctness, context for the TSO settings in C4.
- R. Jones, A. Hosking, E. Moss, *The Garbage Collection Handbook*, 2nd ed., CRC 2023, ch. 11
  (safepoints and thread suspension) - background for the C4 hypothesis.
