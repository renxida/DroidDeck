# Lanner hand-off (Nov 28, 2026)

> **Update, same day (written by the session that took over).** The repo is now `familiar-of-cedar/lanner`.
> `perf/switchboard` was merged into `main` on Nov 28 and **`main` is the trunk again**: start new work from
> `main`. The install link is https://github.com/familiar-of-cedar/lanner/releases/download/latest/lanner.apk
> (`kestrel.apk` is published alongside as an alias). The application id is changing to `chat.comb.lanner`
> (branch `perf/lanner-id`). The scripts under `docs/handoff/phone-tests/` still use the old id, old paths and
> Pixel 10 coordinates; they are kept as a record, not as working tools. On the docked 10a the phone is driven
> over adb with `tools/droiddeckctl`, so the telemetry-repo remote control is not needed there.

Lanner (earlier "Kestrel") is Cedar's fork of DroidDeck: an Android app that runs Steam games, tuned for
Slay the Spire 2 (Steam app 2868840). This note is for the Claude Code session taking it over. It covers
where everything lives, how builds reach the phone, and what was in flight.

## Read this first

- **Target change.** All the work so far ran on a **Pixel 10** (Tensor G5, **PowerVR** GPU). The new
  target is the **Pixel 10a** (Tensor G4, **Mali** GPU). Mali is "experimental" in `GpuInfo.kt`, and
  the PowerVR-specific parts won't carry over: the PowerVR driver pack, the Steam CEF
  `-cef-disable-gpu-compositing` path (BL_STEAM_GL_PRESENT in `droiddeck-session`) and every perf number
  below. Upstream's Mali notes: `docs/development/mali-venus.md` (about 40 fps StS2 on a Pixel 9 Pro
  via Venus).
- **Repo moved.** The repo was transferred off `renxida`. GitHub redirects the old URLs for now, but
  these hard-coded names need updating:
  - `UpdateCheck.REPO` (self-updater)
  - `ComponentsManager.KESTREL_REPO`
  - `TelemetryUploadSettings.DEFAULT_REPO` (`renxida/kestrel-telemetry`)
  - `tools/native-engine/resolve_component.py`
  - `tools/native-engine/standin/component.sh`
  - `tools/linuxfs/overlay/usr/local/bin/droiddeck-native-godot`
  - `.github/workflows/native-startup-gate.yml` (`SELFTEST_54`)
- **Telemetry and remote-control repo.** `renxida/kestrel-telemetry` is private and was **not** moved.
  You need access to it (or a copy under the new owner) for session logs and remote control. See
  "Phone harness".
- **Remote-control key.** The app only obeys commands signed with an ECDSA P-256 key. Its public half
  is `AgentRemote.PUBLIC_KEY`. The private half belongs to the previous operator and is **not** in this
  repo or this hand-off. To drive the phone remotely:
  1. Generate your own key with `openssl ecparam -name prime256v1 -genkey -noout -out key.pem`.
  2. Put its public key, as SubjectPublicKeyInfo base64, in `AgentRemote.PUBLIC_KEY`.
  3. Rebuild and install.

  Never commit the private key.

## Cedar's standing preferences

- **Sound off in every phone test.** Set the `gameSound` and `bootMovieSound` prefs to false, check
  they took, and restore them afterwards. Sound on a test interrupts their music and can wake them up.
  `docs/handoff/phone-tests/night2.sh` does this.
- **Always send the install link with each new build:**
  https://github.com/renxida/DroidDeck/releases/download/latest/kestrel.apk (update it once
  `latest` lives under the new owner).
- **Autonomous commits to `main` are fine.** No PRs needed. "Don't ask, just do": pick the fix, try
  better ones in parallel, and only bring Cedar what really needs them.
- **P99 frame time first.** "Important thing is not lagging." Battery life second.
- **Don't touch the phone while Cedar is using it.** Off the charger counts as in use. The test scripts
  wait for it to be charging and idle for two checks 5 min apart.
- **Steam offline.** Tests on Cedar's Steam account run with Steam offline, because Cedar streams on
  their living-room PC.
- **Keep native.** The native ARM engine is the main path. Proton is the fallback.
- **Keep replies short.** Cedar's time is the scarce resource.
- **Don't fake anything.** Nothing about ownership, licences, DLC, tickets or Steam auth may be faked or
  stubbed. `tools/native-engine/stubs/steam_stub*.c` never ships.
- **Game files stay off GitHub.** No game files, `.pck` extracts or `.spskel` in any repo or release.
- **Never ask for or keep Cedar's Steam password.**

## Branches

There are about 100 branches. `perf/switchboard` is the integration branch the phone build comes from.
`main` carries the workflow changes, including `release-apk.yml`. Everything else is a topic branch
that was merged into `perf/switchboard` or parked. As of this hand-off, no work by the fork thread
exists only in a container.

| Branch | What it holds |
|---|---|
| `perf/switchboard` | **Active.** Every APK Cedar installs is built from here. Head `7d4d8c25`, carrying engine 134. |
| `main` | Fork CI fixes (authorship skip, fork-safe sign/packs), `release-apk.yml`, branch-build triggers. |
| `perf/emu-native` | Native ARM engine wrapper and patches. Pushes build `native-engine-N` (see "Native engine"). |
| `perf/emu-fex`, `perf/emu-fex-wine`, `emu/fex`, `emu/winetest-*` | FEX (x86 translator) tuning and Wine/FEX test rigs. Proton path. |
| `perf/emu-dotnet`, `perf/emu-dotnet-generics`, `emu/dotnet`, `emu/fex-dotnet-aot` | .NET runtime tuning for the game under FEX. |
| `perf/emu-graphics`, `emu/graphics` | Graphics and present path (gamescope, Vulkan WSI). |
| `native/*` | Native engine prototypes: ARM64 ReadyToRun, startup gate, first-frame watchdog. |
| `perf/fex-*`, `perf/combo-*`, `perf/all`, `perf/adpf-all`, `perf/two-frames`, `perf/layer-fence`, `perf/compat-quiet`, `perf/godot-*`, `perf/sched`, `perf/memory`, `perf/warmup`, `perf/latency`, `perf/live`, `perf/free-memory`, `perf/pso-save`, `perf/appinfo*`, `perf/early-buses`, `perf/component-hashes` | Early Proton-path perf experiments, A/B'd by telemetry rotation. Winners are in switchboard. |
| `cleanup/*` | The big cut: stores, VR, desktop and launcher removed; app renamed to `dev.renxida.kestrel`. Merged. |
| `ux/*` | Core flow, Steam login sheet, swipe pad, pad layout, boot overlay, gamepad. Merged. |
| `test/*` | Telemetry, crash hardening, off-device profile, TSO litmus, update flow, memory diagnostics, gate stand-in, etc. |
| `autofix/*` | Self-fixing CI loop experiments (`docs/development/autofix-loop.md`, `tools/autofix/PLAYBOOK.md`). |
| `ci/offphone-*` | Off-phone rig: runs Steam UI and a stand-in game on CI with llvmpipe (`tools/offphone`). |
| `rebrand/lanner`, `perf/brand` | Kestrel to Lanner naming and artwork. |
| `claude-phone-proto` | Unrelated: prototype for a separate Claude-driven phone project. |

## CI and release flow

1. Push to `main`, `perf/**`, `cleanup/**`, `ux/**` or `test/**` runs **Build APK** (`build.yml`). It
   produces the `droiddeck-apk` artifact, testkey-signed, so builds install over each other and keep
   the Steam login. `*.md` and `docs/**` changes don't trigger it.
2. On `perf/switchboard`, a green Build APK run triggers `release-apk.yml` (on `main`). It republishes
   the release `latest` with the asset `kestrel.apk`. Cedar's own Claude Code wrote `release-apk.yml`,
   and the fork thread never edited it.
3. The app's self-updater (Setup, or the remote `update` method) checks that release. It still needs
   the system install confirmation on the phone.
4. A local Gradle build may fail behind restrictive proxies (AGP needs `dl.google.com`). CI always
   works.
5. Known transient failures to re-run, not "fix": Maven Central 429 on the Robolectric jar, ECR
   `toomanyrequests`, and a `test_zram_preload` flake.

## Native engine (native ARM Godot plus .NET for StS2)

- **Source and patches:** `perf/emu-native`, under `tools/native-engine/`. Write-up:
  `docs/research/native-engine.md`.
- **Releases:** `native-engine-build.yml` builds `native-engine-N` releases (component tarball plus
  `component.json` with a sha256). `native-startup-gate.yml` gates each one (startup, Steam bridge,
  ICU and export checks). Only releases whose `component.json` says gate passed are eligible.
- **How the APK picks an engine:** each APK embeds the **highest gated `native-engine-N` at CI build
  time** (`tools/native-engine/resolve_component.py`, called from `build.yml`). Otherwise it uses the
  committed pin in `droiddeck-native-godot`. Pins edited on the phone are restaged from the APK every
  session, so test a new engine by building an APK.
- **Latest:** engine **134** (from `9108ba91`, sha256
  `754808fff61db4f76967c1dfe9b9f67d550743940d53acda1fa17b388767172c`). It adds boot profiling and
  boot-window switches, all off by default:
  - `DROIDDECK_NATIVE_PROF_OTHER=1`
  - `DROIDDECK_NATIVE_BOOT_MAX_FPS`
  - `DROIDDECK_NATIVE_BOOT_TIME_SCALE`
  - `DROIDDECK_NATIVE_BOOT_MAX_S` (default 30)
  - stall reports
- **Earlier milestones:**
  - 65 to 73: Steam bridge (Steamworks.NET to the real arm64 Steam client).
  - 95: audio.
  - 105 to 109: accuracy fixes, plus the `DROIDDECK_NATIVE_PROF=250` profiler.
  - 127 to 131: pipeline cache, census, prestart and bridge hold.
- **Proton fallback:** if the game's Steam init fails, or the first frame doesn't come in time, the
  wrapper falls back to Proton. The guard skips time the engine is frozen.

## Phone harness

- **Telemetry:** Setup > Diagnostics > Telemetry upload, with the repo and a fine-grained token (that
  repo only, Contents read/write). The token lives only in the app setting, never in logs or commits.
  - Sessions upload `runs/<build>/<device>/<utc>.zip` at the end, plus 5-minute checkpoints and a
    live file every 30 s (`tools/telemetry_live.py --follow`).
  - `config/kestrel.json` in that repo holds remote config: experiments on and off, rotation rows and
    perf settings. It is read at session start.
- **Remote control:** Setup > Diagnostics > Remote control, off by default, and shown in a
  notification while on.
  - The app polls `remote/inbox/` in the telemetry repo every 30 s, and every 5 s for 10 min after a
    command. Signed commands run and answer in `remote/outbox/`.
  - Operator CLI: `tools/lanner-remote METHOD '{json}' --wait N`. It needs `LANNER_REMOTE_KEY` and
    `LANNER_REMOTE_CLONE`.
  - Methods: `ping`, `state`, `prefs`, `env`, `start`, `stop`, `wake`, `hide`, `screenshot`, `ui`,
    `input`, `layout`, `update`.
  - Shell (`native`) also needs the **Debugging tools** switch, which can only be changed on the phone.
    That is by design: never make it remotely operable.
- **Test scripts:** `docs/handoff/phone-tests/` holds the last batch.
  - `rn.sh` runs a shell command in the phone's Linux container.
  - `night2.sh` waits for the APK, charging and idle, forces sound off, then runs `boot3.sh`,
    `prestarttest.sh` and `resumetest.sh`.
  - `fight3.sh`, `fp.sh` and `ab4.sh` measure fight frame times.
  - `backup.sh` and `restore3.sh` back up and restore the game saves.
  - `pin131.sh` and `unpin131.sh` pin an engine on the phone, but the pin is restaged each session
    (see "Native engine").
  - Paths and screen coordinates are for the Pixel 10. Re-measure on the 10a.

## Where the evidence lives

- **Crash catalogue:** `docs/research/crash-catalogue.md`, plus telemetry crash classification (`test/telemetry`).
- **Baselines:**
  - `docs/research/baseline-run-1.md`
  - `docs/development/offdevice-profile.md`
  - `docs/research/memory-footprint.md`
  - `docs/research/startup-and-warmup.md`
  - `docs/research/scheduling-and-thermals.md`
  - raw session zips under `runs/` in `kestrel-telemetry`
- **Proton-path research:**
  - `docs/research/fex-*.md`
  - `docs/research/dotnet-*.md`
  - `docs/research/graphics-present-path.md`
  - `docs/research/godot-render-path.md`
  - `docs/research/tso-litmus.md`
  - `docs/development/perf-backlog.md`

## Numbers from the Pixel 10 (PowerVR, engine 105 to 134)

These won't transfer to the 10a, but they're useful as a reference:

- **Fight, engine 109, 120 fps cap:** about 46 to 48 fps, p50 22 ms, p99 about 40 ms, 3.5 to 7.5 frames
  over 50 ms per minute, phone at 43 to 46 °C on the charger.
  - 30 fps cap: p99 64 ms. Rejected.
  - 45 fps cap: about 25% less CPU but p99 about 5 ms worse. The game's own `fps_limit` stays at 120.
- **Godot Mobile renderer:** worse (p99 90 vs 80 ms on the test scene). Dropped.
- **Cold launch to menu:** about 46 to 60 s.
  - The app reaches Steam UI's first frame at about 18 s. The game engine starts at about 29 s, and the
    menu shows at about 46 s.
  - The game's own load is 13.9 s. About 9 s of that was the intro splash while a loader thread
    saturates a core. Setting `skip_intro_logo` to true in the game's settings fixes that part.

## Open work, in priority order

1. **Bring-up on the 10a (Mali).** Install `latest` and see whether it boots Steam and the game. Check
   the GPU pack and present path first.
2. **Boot and resume (Cedar asked for "instant").** Built in `perf/switchboard` but **never verified
   on a phone**, because Cedar never installed the build:
   - Freeze a hidden game and page out other processes (`freezeGameAfterMs`, default 10 s; needs
     `suspendPolicy.steam` = `native` or `auto`, while Cedar's phone has `never`).
   - `standbyAfterGame`: relaunch the game hidden after exit, then freeze it.
   - `steam_native_prestart` / `DROIDDECK_NATIVE_PRESTART=1`: start the engine in parallel with Steam,
     with the bridge held until Steam is ready.
   - The remote `hide` method.
   - The engine 134 boot-window arms.

   `night2.sh` / `resumetest.sh` is the test plan. Restoring after process death is infeasible on stock
   Android 17 / GKI 6.6 (no CRIU primitives, no AVF snapshot). Keeping processes alive and frozen is the
   path.
3. **Steam toast blackout.** Steam's notification window draws black over the game with software CEF
   compositing on PowerVR. Toasts are hidden by default (`BL_STEAM_TOASTS=1` shows them). The real fix
   is GPU compositing for CEF, or alpha on the notification layer, and it may differ on Mali.
4. **Steam's roughly 18 s start.** Idea: keep a warm, frozen Steam session pre-warmed on the charger.
5. **Engine fight A/B for P99, and battery.** The phone sits on a wireless charger reporting
   "not_charging", so battery power can't be read there. Use CPU% and clocks as proxies.

## Other threads' local leftovers (low value, possibly unpushed)

Other Claude sessions in Cedar's project did the FEX, .NET, graphics and native work. Their notes
mention a few local-only items:

- a timeline-log dedupe commit
- the native thread's decoded-name-table layout notes (`stdt/LAYOUT.md`)
- an older FEX idle-trim commit, superseded by patch files already on the fork

Everything substantive from them is on the fork branches above.
