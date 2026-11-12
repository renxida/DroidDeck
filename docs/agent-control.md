# Agent control

`tools/droiddeckctl` drives DroidDeck on a real device over ADB, for agents and scripts that test
the app end to end: start and stop sessions, launch and quit Steam games, wait for a game to have
focus, send input, inspect the app's UI and gamescope's windows, run commands inside the session,
and collect logs, screenshots and recordings. `tools/droiddeck-scenario` repeats a scripted test
and reports every run.

## How it works

- **The provider** `content://com.droiddeck.launcher.agent` (`agent/AgentBridgeProvider.kt`)
  answers each command with JSON. It and the session-start Activity (`AgentStartActivity`) are in
  every build and require `android.permission.DUMP`, held by the ADB shell and privileged or
  explicitly granted callers. The provider checks the calling permission before decoding requests.
  A build shipped under another package (`tools/release/variants.txt`) answers at
  `content://<package>.agent`: set `DROIDDECK_PACKAGE` to that package for `droiddeckctl`.
- **The guest agent** `droiddeck-agent` runs inside every session (started by
  `droiddeck-session`). It runs commands with the session's own environment, publishes gamescope's
  focus, and talks to the Steam client's DevTools, which only accepts guest processes. The app and
  the agent exchange request and answer files under the session root (`files/session/agent`).
- **The UI dump** reads the app's Compose semantics from inside the app (`agent/AgentUi.kt`), so it
  works on every display and window. UI Automator, by contrast, dumps whichever window has focus,
  which on a dual-screen device is often another app's.

## Access

Player controls and observations work over authorized ADB on any build: `state`, `start`, `stop`,
`resume`, `wait`, `launch`, `quit`, `focus`, `input`, `ui`, `logs`, `screenshot`, `record`, `pacing`,
`thermal`, `displays`, `wake`, and `access`.

**Setup > Diagnostics > Debugging tools** is off by default in every build, including debug builds.
It enables `run`, `guest`, `cdp`, all `env` operations, `override`, and `prefs`. The
toggle switches directly without a confirmation dialog. The bridge refuses to change it through `ui`
or preference writes. The existing preference key, `setting-agent-commands` test tag, JSON command
names, and `AGENT_COMMANDS_DISABLED` error code are unchanged. Refusals exit 7; `droiddeckctl access`
reports whether commands are enabled.

Disabling the toggle clears queued and persistent agent environment settings and installed binary
overrides. Disabled settings cannot apply at the next session start. Already running commands and
effects already applied to a session are not undone; stop and restart that session to discard them.

Program overrides prepend their directory to the normal session PATH, including `/usr/games`
where Ubuntu installs `gamescopereaper`. An explicit `PATH=...` environment entry replaces that
default. If an old persistent PATH hides runtime tools, inspect `tools/droiddeckctl env list`
and correct only PATH with `env set --persistent`, which preserves other persistent entries.
For example, to restore the normal runtime search path:

```sh
tools/droiddeckctl env set --persistent 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games'
```

Retain any custom directories you still need alongside these runtime directories, then restart
the session. `env clear persistent` removes **all** persistent environment settings, not just
PATH; there is no single-entry clear operation. Before clearing that scope, record any other
entries you want to keep and reapply them afterward.

Authorized command execution runs inside the runtime containing Steam's saved login. CDP evaluates
JavaScript in the authenticated Steam client, and preferences can contain private values. These
capabilities remain unrestricted when enabled: the toggle is an explicit opt-in, not credential
isolation or a sandbox against the authorized caller. Main already supported arbitrary guest
programs through `run`; restricting credential access would require addressing that route too.
ADB access remains a trust boundary, and ADB input can operate the device's own toggle.

The helper exchanges private files with the app and adds no network listener. The guarded Steam
DevTools port is bound to loopback and accepts only peers with a fresh private registration for a
live socket descriptor, matching socket inode, destination port, and address family. The existing
x86_64 Decky loader exception remains: that mode does not enable the guest-only acceptance guard.

Requests are limited to 256 KiB of encoded JSON. Guest responses and DevTools messages are limited
to 1 MiB; provider replies above 384 Ki characters return `RESPONSE_TOO_LARGE` to avoid overflowing
Binder. Command output retains at most 256 KiB per stream while continuing to drain both streams.
Timeouts must be finite, positive, and no greater than 600 seconds, and terminate the command's
process group. Override installation refuses path traversal and symbolic links.

Automatic process logs record environment keys and argument counts rather than values or full
argument lists. UI nodes marked as passwords redact text, editable text, descriptions, and state
text. This does not redact arbitrary authorized command results, CDP data, preferences, or screen
captures. Scenario directories are owner-only and JSON reports are written with mode 0600. Review
artifacts before sharing. `logs` downloads the archive Share logs builds (scrubbed, Steam logs over
8 MiB left out) from the app's external files folder, since adb cannot read the session folder itself.
When the installed app predates archive export, the CLI falls back to pulling the private session folder;
that legacy path is available only with root and is not scrubbed before download.

## Device selection and output

The CLI resolves one authorized device using `ADB_SERIAL` or `ANDROID_SERIAL` when set. Otherwise it
de-duplicates transports that report the same device serial and asks for an explicit serial if
multiple devices remain. Pass `--serial` before the command to choose directly; set `ADB` (or pass
`--adb`) to choose an adb executable. `tools/deploy_local.sh` uses the same resolver.

Every command writes one JSON object to stdout and its resolved serial to stderr. Exit codes: 0
success, 2 invalid or rejected command, 3 ADB or device error, 4 session error, 5 timeout, 6
artifact or file error, 7 debugging tools disabled.

When upgrading the CLI, use a new destination for the first pull if the old destination contains
archives created by an older CLI without a `.droiddeckctl-managed-logs.json` manifest and their
filenames overlap. The upgraded CLI refuses to overwrite those files to preserve unrelated data.

## Commands

```sh
tools/droiddeckctl state
tools/droiddeckctl start steam --wait
tools/droiddeckctl start steam --wait --reuse
tools/droiddeckctl start desktop
tools/droiddeckctl run /usr/bin/foo -- arg1 arg2
tools/droiddeckctl wait ready|idle|failed
tools/droiddeckctl stop
tools/droiddeckctl resume

tools/droiddeckctl launch 489830 --wait
tools/droiddeckctl wait focused 489830
tools/droiddeckctl wait game
tools/droiddeckctl quit 489830
tools/droiddeckctl focus

tools/droiddeckctl input key ENTER
tools/droiddeckctl input text 'hello world'
tools/droiddeckctl input tap 640 360
tools/droiddeckctl input swipe 100 600 100 100 --duration 400
tools/droiddeckctl input pad a
tools/droiddeckctl input stick left 0 -1 --hold 500
tools/droiddeckctl input trigger right 1.0

tools/droiddeckctl ui dump
tools/droiddeckctl ui click rail-steam
tools/droiddeckctl ui click --match "Play"
tools/droiddeckctl ui set-text some-field --text abc

tools/droiddeckctl guest -- xprop -root GAMESCOPE_FOCUSED_APP
tools/droiddeckctl cdp targets
tools/droiddeckctl cdp eval 'SteamClient.Apps.GetMyRunningApps?.()'
tools/droiddeckctl env set DXVK_HUD=fps
tools/droiddeckctl env list
tools/droiddeckctl env clear [next|persistent|all]
tools/droiddeckctl override gamescope ./gamescope
tools/droiddeckctl override libblfastpath.so ./libblfastpath.so
tools/droiddeckctl proot-profile enable --slow-us 1000
tools/droiddeckctl proot-profile disable
tools/droiddeckctl proot-profile report ./session.log --sort wall
tools/droiddeckctl prefs get session
tools/droiddeckctl prefs set session some_key true --type bool

tools/droiddeckctl logs latest ./session-artifacts
tools/droiddeckctl screenshot ./screen.png --display 4
tools/droiddeckctl record ./clip.mp4 --seconds 8 --display 0
tools/droiddeckctl pacing --seconds 30
tools/droiddeckctl thermal
tools/droiddeckctl wait cool --below 38 --timeout 900
tools/droiddeckctl displays
tools/droiddeckctl wake
```

A sleeping device suspends the session and hides the app's windows from `ui dump`; `wake` first.

`pacing` reads SurfaceFlinger's timestats for the game's layer (the session's SurfaceView when the
compositor has no layer of its own) and reports the frames that reached the panel, `fpsOnScreen`,
the present-to-present histogram and the share of intervals off the usual cadence. A game's own fps
counter counts frames handed over, not frames shown, so it can read 60 while one frame in four
stays on screen for two refreshes.

`thermal` reports the battery temperature (`dumpsys battery`), the thermal status
(`dumpsys thermalservice`, 0 NONE to 6 SHUTDOWN) and each CPU policy's `scaling_max_freq` against
its `cpuinfo_max_freq`, with `capped` when the first is lower. `wait cool --below <°C>` polls it
every 10 s until the battery is under that temperature, so runs being compared start equally cool.

`input` needs a session that has drawn its first frame. Pad input goes through the session screen's
virtual pad, the same one the on-screen controls drive, so games that ignore touch still respond.
`launch`, `quit` and `cdp` need a Steam session; `focus` and `wait focused` need gamescope (every
mode except the desktop).

## State (schema 2)

`state` returns `schema`, `build`, `appVersion`, `runtime {installed, version}`,
`agent {guest, guestFocus, commands}` and `session`:

- `id`, `phase`, `running`, `mode`, `program`, `steamUi`, `steamUrl`, `suspended`, `pip`,
  `firstFrame`, `output` [w, h], `refreshHz`, `lastTransitionAt`, `guestPid`, `installing`
- `logDir`, `eventsFile`, `artifactsAvailable`, `artifactsComplete`, `failure {code, message, status}`
- `focus`: gamescope's focus as the guest agent last published it, or null:
  `focusedApp`, `focusedWindow`, `focusableApps`, `baselayerAppIds`, `seq`, `t`.

Phases: `IDLE`, `PREPARING`, `INSTALLING_RUNTIME`, `STARTING_COMPOSITOR`, `STARTING_GUEST`,
`STARTING_STEAM`, `READY`, `SUSPENDED`, `STOPPING`, `FAILED`. Schema 2 only added `agent` and
`session.focus`; every schema 1 field is unchanged.

A game the player can see is `focusedApp == appId` **and** `appId in focusableApps`.
`GAMESCOPE_FOCUSABLE_WINDOWS` is what the Steam client orders the base layer from: a game missing
there stays behind the client's loading screen however healthy its window looks
(docs/development/steam-focus-debugging.md). `focus` returns the full snapshot, including each
server's windows with map state, override-redirect, app id, `WM_STATE`, pid, class, opacity and
`_NET_WM_STATE`.

## Events

Each session writes `events.jsonl` beside its other artifacts: the lifecycle (`session.created`,
`compositor.started`, `guest.exited`, ...) plus `agent.ready` (the guest agent is up),
`focus.changed` (the focused app, focusable apps or base-layer order changed),
`keyboard.changed` (Steam's on-screen keyboard opened or closed) and `agent.launch_requested`.

## UI test tags

Compose test tags are exposed as resource ids (`ui/TestTags.kt`), so both `ui dump` and UI
Automator show them:

| Tag | Where |
|---|---|
| `rail-steam`, `rail-games` (the Library), `rail-desktop`, `rail-store`, `rail-components`, `rail-android-apps`, `rail-setup`, `rail-updates` | the front end's rail |
| `play-steam` | Play on the Steam page, and on the Library when it has no games |
| `library-source-<source>` | the Library's source filter chips (`all`, `steam`, `gog`, ...) |
| `game-tab-<about\|achievements\|launch\|saves\|install>` | the open tab of a game page |
| `stores-profile`, `stores-epic-launcher` | a store's profile strip, and the Epic Games Launcher row in Epic's |
| `setting-<key>` | every toggle and choice row, by its key (`setting-logs`, `setting-agent-commands`, ...) |
| `tab-<n>` | a page's tab strip (Setup's Overview, Controller, Session, Launcher...), by position |
| `drawer-tab-controller`, `-second_screen`, `-display`, `-effects`, `-games`, `-session` | the session drawer's pages |
| `drawer-stop`, `drawer-stop-confirm`, `drawer-stop-cancel` | stopping a session from the drawer |

## Scenarios

`tools/droiddeck-scenario` runs a JSON scenario: `setup` once, `steps` `repeat` times, `teardown`
once. Each run starts with `before` (typically `start steam --wait --reuse`, so a session that died
in one run, to a crash or the low-memory killer, is started again for the next) and ends with
`after`, whether it passed or not, so a failed run cannot leave a game running into the next. A list step is a droiddeckctl command line; `{"assert": "dotted.path", "equals"|"in"|
"contains"|"not"|"exists": ...}` checks the last answer; `{"sleep"}`, `{"screenshot"}` and
`{"record"}` do what they say. A failing run keeps a screenshot, the focus snapshot and the
session's logs in its folder, and `report.json` sums up the runs.

```sh
tools/droiddeck-scenario tools/scenarios/steam-game-focus.json --var appId=489830 --repeat 10 --keep-going
```

`tools/scenarios/steam-game-focus.json` launches a game, checks it has focus the way a player
sees it, and quits it: the test that catches a game left behind Steam's loading screen, which only
shows up in some fraction of launches. A game with a launcher of its own needs the launcher pressed
through first, since the launcher window carries the game's app id too:
`tools/scenarios/skyrim-launcher-focus.json` taps Skyrim's Play before checking.

## PRoot performance experiments

`proot-profile enable` queues handler profiling for the next session using the existing debugging
consent gate. Counters and bounded slow samples appear in `session.log`; `proot-profile report`
ranks a saved local log without ADB. This measures PRoot handler work, not full syscall latency.
`override libblfastpath.so FILE` selects a glibc ARM64 shared library in the next session's preload
list, reports its SHA-256, and refuses installation until the current session stops. It lasts for
one session; the next session stages the bundled library again. See
[the profiling workflow](development/proot-performance.md#finding-the-next-fast-path) for timing
boundaries, build commands, path diagnostics, and A/B validation.
