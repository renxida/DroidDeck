# Godot accessibility, Proton's tabtip and the Slay the Spire 2 start-up crash

**Question.** Cedar reported that Slay the Spire 2 (StS2, Steam app 2868840) sat on a black
screen for a long time, and only worked after an "accessibility off switch". What is that switch,
why does accessibility matter under Proton, and what should Kestrel do by default?

**Answer, short.** The switch is Godot's own command-line option `--accessibility disabled`.
Godot 4.5 added AccessKit, which gives each game window a UI Automation (UIA) provider. Proton
starts `tabtip.exe` in every prefix, and tabtip is a UIA client that follows focus. When tabtip
asks the game's window for its provider, AccessKit answers, and from then on tabtip's COM calls
run inside the game. On a phone (FEX, Adreno) a third party saw the game fault and exit 19-52 s
after start in every run with tabtip, and in no run with tabtip off or with
`--accessibility disabled`. Kestrel now does both for Godot builds that contain AccessKit.

Marks: **VERIFIED** = read in source or in a primary record; **INFERRED** = reasoned from verified
facts, not observed. Nothing here was run on a device for this work.

## 1. Background

### 1.1 Godot 4.5's accessibility modes (VERIFIED)

Source: godotengine/godot tag `4.5-stable` (876b2903).

- `--accessibility <mode>` takes `auto`, `always` or `disabled` (`main/main.cpp` 624, parsed at
  1308-1329). Without it, the mode comes from the project setting
  `accessibility/general/accessibility_support` (`main/main.cpp` 3193-3204), whose default is 0,
  "Auto (When Screen Reader is Running)" (`core/config/project_settings.cpp` 1577).
- On Windows the AccessKit driver is created in every mode except `disabled`
  (`platform/windows/display_server_windows.cpp` 6806-6817). In `auto`, nothing waits for a
  screen reader first: the driver exists and every window gets an adapter in `WM_NCCREATE`
  (4656-4663).
- "Auto" only decides whether Godot *pushes tree updates*: `SceneTree::is_accessibility_enabled`
  (`scene/main/scene_tree.cpp` 205-216) needs `accessibility_screen_reader_active()`, which reads
  `SPI_GETSCREENREADER` and the `NarratorRunning` mutex (`display_server_windows.cpp` 2831-2844).
  The UIA provider itself is installed regardless.
- Godot 4.4.1 has neither the option nor the UIA link (`platform/windows/detect.py` and
  `main/main.cpp` at `4.4.1-stable` contain no `accesskit`, `uiautomationcore` or
  `--accessibility`).

### 1.2 How AccessKit hooks a window (VERIFIED)

Source: AccessKit/accesskit-c `f69571e` (0.17.0, the version in Godot's `thirdparty/README.md`
8-11) and AccessKit/accesskit `accesskit_windows-v0.29.0` (its dependency).

- Official Windows templates link AccessKit statically (`accesskit_sdk_path` in
  `.github/workflows/windows_builds.yml` 13), which adds `uiautomationcore` to the link
  (`detect.py` 415-433, 792-815). The import library names `UIAutomationCore.DLL`
  (`platform/windows/uiautomationcore.x86_64.def` 6). A build without the SDK path loads
  `accesskit.<arch>.dll` or `accesskit.dll` beside the executable instead
  (`drivers/accesskit/accessibility_driver_accesskit.cpp` 1566-1580).
- The Windows adapter subclasses the window procedure (`platforms/windows/src/subclass.rs`
  95-116). Every message now passes through AccessKit's procedure, which looks up its state with
  `GetPropW` (45). `WM_GETOBJECT` for the client area or `UiaRootObjectId` activates the tree and
  returns a provider through `UiaReturnRawElementProvider` (`adapter.rs` 454-512, 541-545). Godot's
  activation callback hands over a one-node "Godot Engine" window marked busy
  (`accessibility_driver_accesskit.cpp` 201-218).
- `QueuedEvents::raise` unwraps the result of `UiaRaiseAutomationEvent` and
  `UiaRaiseAutomationPropertyChangedEvent` (`adapter.rs` 566, 573-582), and accesskit-c builds with
  `panic = "abort"` (`Cargo.toml` 34). Any failed HRESULT there ends the game process.

### 1.3 Who sends `WM_GETOBJECT` under Proton (VERIFIED)

Source: ValveSoftware/wine `proton_11.0` (e60892cc) and wine-mirror/wine master (7384c8ba).

- Proton's `explorer.exe` starts `C:\windows\system32\tabtip.exe` whenever it opens a desktop,
  marked "FIXME: hack, run tabtip.exe on startup" (`programs/explorer/desktop.c` 1196-1226,
  1412-1413). Upstream Wine has no `programs/tabtip`.
- tabtip registers an `IUIAutomation` focus-changed handler with a cache request for bounding
  rectangle, control type, name, keyboard focus and read-only state
  (`programs/tabtip/tabtip.c` 285-331). It opens Steam's on-screen keyboard only when
  `SteamDeck=1` (345-368, 192-226); otherwise it still listens and reads the properties.
- Wine's UIA client turns `EVENT_OBJECT_FOCUS` into `create_uia_node_from_hwnd`
  (`dlls/uiautomationcore/uia_com_client.c` 1223-1244), which sends `WM_GETOBJECT` with
  `UiaRootObjectId` to the focused window (`uia_client.c` 2697-2719). `UiaHasServerSideProvider`
  does the same (2782-2791).
- In the game, `UiaReturnRawElementProvider` starts Wine's provider thread in the multithreaded
  apartment (`uia_provider.c` 2154-2160, 2264, 2282), so tabtip's property reads arrive as COM calls
  on that thread, beside Godot's main thread.
- Kestrel turns Proton's Xalia off by default (`PROTON_USE_XALIA=0`,
  `SessionService.kt` 925, `SessionPrefs.kt` 548), so tabtip is the UIA client left. No screen
  reader runs in this stack.

### 1.4 Field evidence

- **VERIFIED record, third party.** jason5545/DroidDeck `PATCHES.md` at `05a129d` (Slay the Spire 2
  entry) and commits `cc5078f`, `05a129d`, POCO F8 Ultra, 2026-10-04/05:
  - with tabtip running, all 4 logged crashes happened when tabtip's call first reached the game,
    19-41 s after the executable loaded: a fault packet, a jump to `0x726F74632E` (ASCII `.ctor`),
    `stub_manager_delete Got page fault when releasing stub`, then .NET `0x80131506`,
    `c0000409` in GodotFmod, or the process vanished;
  - minimal settings plus tabtip: 3 of 3 crashed (35, 51, 52 s); tabtip disabled: 10 of 10 passed;
    tabtip running plus `--accessibility disabled`: 3 of 3 passed;
  - re-tested on upstream's fixes (#168): 4 of 4 launches that reached the game logged
    `I_RpcReceive we got fault packet with status 0x3e6` at tabtip and `UiaReturnRawElementProvider`
    in the game 32-39 s in; 3 died within 18 s, with `nested exception on signal stack` across the
    prefix. tabtip off ran about 620 s with no fault.
  - `tabtip.exe=d` stops the process at load and explorer logs `Couldn't start tabtip.exe: error 126`,
    then carries on.
- **VERIFIED report.** Droid-Deck/DroidDeck#1315 (Adreno 830): StS2 needed
  `--accessibility disabled --rendering-driver vulkan` to start.
- **VERIFIED reports, other platforms.** Linux Godot 4.5 crashes inside AccessKit's Rust code with
  accessibility in `auto`, fixed by `--accessibility disabled`: godotengine/godot#111028,
  forum.godotengine.org thread 137203, Fedora discussion 164967. These are the AT-SPI path, not
  UIA; they show that AccessKit failures are fatal and that the option is the accepted workaround.
- Steam's StS2 threads on Linux black screens (e.g. 802341195824169699, 802341195824315003) are
  about the D3D12 renderer and are fixed by `--rendering-driver vulkan`/`opengl3`; none mentions
  accessibility. Kestrel already forces Vulkan.

## 2. Findings

| # | Finding | Status |
| --- | --- | --- |
| F1 | "Accessibility off" for StS2 is Godot's `--accessibility disabled`; StS2 has no in-game switch for it that I could find | VERIFIED option; in-game absence INFERRED |
| F2 | In the default `auto` mode a Godot 4.5 window carries a UIA provider even with no screen reader | VERIFIED |
| F3 | Proton always runs tabtip, a UIA focus client, so the provider is activated and called across processes | VERIFIED |
| F4 | That cross-process path faults inside StS2 under FEX within a minute in most runs; either switch removes it | VERIFIED record (third party, Adreno, 23 runs) |
| F5 | Exact cause of the fault (a released COM stub, `.ctor` jump) | open; INFERRED to be a lifetime race between Wine's provider thread and the game, not reproduced here |
| F6 | Cedar's long black screen is the same mechanism | INFERRED: the record shows stalls and exits after the first tabtip call; PowerVR not tested |
| F7 | `--accessibility` is unknown to Godot ≤ 4.4, where 4.0/4.1 may read the stray `disabled` as a scene path | VERIFIED absence; scene-path risk INFERRED |
| F8 | No Wine registry key or environment variable turns tabtip off; a DLL override on the executable does | VERIFIED (`desktop.c` has no condition; record shows `=d` works) |
| F9 | Overriding `uiautomationcore` itself would stop AccessKit games from loading (it is a load-time import) | INFERRED from §1.2 |

## 3. Interventions, ranked

| # | Intervention | Expected effect | Cost | Risk | Done |
| --- | --- | --- | --- | --- | --- |
| 1 | `--accessibility disabled` for Godot builds that contain AccessKit | no UIA provider on the window; tabtip falls back to Wine's MSAA proxy | none | screen readers can't use the game, but none works here | yes |
| 2 | `WINEDLLOVERRIDES=tabtip.exe=d` for the same games | no UIA client in the prefix, so no cross-process COM even if 1 is overridden | one process less | Steam's on-screen keyboard can't pop up on text focus with `SteamDeck=1`; Godot's window doesn't expose text fields to UIA once 1 is on anyway | yes |
| 3 | `tabtip.exe=d` for every game unless `SteamDeck=1` | removes the same path for other UIA-exposing engines (WPF, WinForms, CEF, Unity's screen-reader module) | one line | unknown games may have relied on the OSK popup | no: no evidence outside Godot yet |
| 4 | Patch Proton's tabtip to skip windows of other processes, or AccessKit to not unwrap event results | root cause | rebuilds Proton or the game | out of this repo's reach | no |

Detection for 1 and 2 is a feature test, not a version test: the game is Godot (`<exe>.pck` or
`GodotSharp.dll`, as before) and its executable imports `UIAutomationCore.dll` in the import or
delay-import table, or `accesskit.x86_64.dll`/`accesskit.dll` sits beside it. That is exactly the
set of builds whose window gets a provider (§1.2), and it never adds the option for Godot ≤ 4.4
(F7). Reading the import table costs a few small reads of the executable.

User control: a launch option that sets `--accessibility` wins (Godot reads the last value, and
Kestrel then adds nothing); `tabtip.exe=d` stays, because it is what keeps an `auto`/`always` window
from crashing. A per-game `WINEDLLOVERRIDES` replaces the engine fixes' overrides entirely
(`apply_config`), which brings tabtip back.

## 4. Implementation

`tools/linuxfs/overlay/usr/local/bin/droiddeck-game-env`: `pe_imports`, `accesskit_source` and the
`godot_accessibility` fix, registered after `godot_sentry` so the overrides read
`libsentry.windows.release.x86_64=d;tabtip.exe=d` for StS2. Tests:
`tools/tests/test_game_environment.py` `GodotAccessibilityTest` (synthetic PE32+ images; import and
delay-import detection; dynamic AccessKit; launch-option override; Godot 4.4-like, unreadable and
non-Godot executables untouched; an end-to-end run through `main` checking argv, the overrides and
the log line).

Merging with `perf/godot-render`: the branches merge without conflicts on their shared base. That
branch's `godot_vulkan` also appends `--accessibility disabled` for packs stamped 4.5+; together
the argument appears twice, which Godot accepts (each occurrence just sets the mode). The
coordinator may drop those three lines there, since this fix covers every AccessKit build,
including ones whose pack can't be read.

## 5. On-device experiment plan (Pixel 10, PowerVR)

Each run: start StS2 from Steam, wait for the main menu, start a run, play 10 minutes.

- **E1 Default.** `session.log` must show
  `droiddeck-game-env: godot accessibility: off (--accessibility disabled, tabtip.exe=d; AccessKit: SlayTheSpire2.exe imports UIAutomationCore.dll)`.
  The Proton log (`PROTON_LOG=1`) must show `Couldn't start tabtip.exe` and `Effective
  WINEDLLOVERRIDES` containing `tabtip.exe=d`, and must not contain `UiaReturnRawElementProvider` or
  `fault packet`. Expect `DROIDDECK_GAME_EXIT app=2868840 ... class=clean` after quitting. If the
  line says nothing about accessibility, the executable didn't match (check its imports).
- **E2 Time to menu.** Compare `DROIDDECK_TIMELINE event=game_exec` to the first frame (HUD or
  `godot.log` renderer line) against a run with launch option `--accessibility auto` and per-game
  `WINEDLLOVERRIDES` without tabtip. A gap of tens of seconds in the second run, together with
  `UiaReturnRawElementProvider` in its log, confirms F6 on PowerVR; no gap refutes it.
- **E3 Crash rate.** 5 runs of the E2 control (accessibility `auto`, tabtip on) against 5 default
  runs. The record predicts most control runs exit within a minute with `fault packet` /
  `nested exception on signal stack`; zero default runs should.
- **E4 Isolation.** 3 runs with only `--accessibility auto` in the launch options (tabtip still
  off, log line `auto from the launch options, tabtip.exe=d`): no fault expected, which shows
  intervention 2 alone suffices on PowerVR.

## 6. References

- godotengine/godot `4.5-stable` 876b2903: `main/main.cpp`, `platform/windows/display_server_windows.cpp`,
  `platform/windows/detect.py`, `platform/windows/uiautomationcore.x86_64.def`,
  `drivers/accesskit/accessibility_driver_accesskit.cpp`, `scene/main/scene_tree.cpp`,
  `core/config/project_settings.cpp`, `thirdparty/README.md`; `4.4.1-stable` for the absence check.
- AccessKit/accesskit-c f69571e (`Cargo.toml`); AccessKit/accesskit `accesskit_windows-v0.29.0`
  f87db98 (`platforms/windows/src/subclass.rs`, `adapter.rs`).
- ValveSoftware/wine `proton_11.0` e60892cc: `programs/explorer/desktop.c`, `programs/tabtip/tabtip.c`,
  `dlls/uiautomationcore/uia_client.c`, `uia_com_client.c`, `uia_event.c`, `uia_provider.c`.
- jason5545/DroidDeck commits cc5078f22f573f95df326225f5501cc1aea5e5b7 and
  05a129d493f074383744551d555ca6f4bc0cb128 (`PATCHES.md`).
- https://github.com/Droid-Deck/DroidDeck/issues/1315, https://github.com/Droid-Deck/DroidDeck/pull/168
- https://github.com/godotengine/godot/issues/111028
- https://forum.godotengine.org/t/crashing-constantly-called-result-unwrap-on-an-err-value-interfacenotfound/137203
- https://discussion.fedoraproject.org/t/godot-engine-games-and-editor-do-not-run-at-allg/164967
- https://steamcommunity.com/app/2868840/discussions/0/802341195824169699/,
  https://steamcommunity.com/app/2868840/discussions/0/802341195824315003/
