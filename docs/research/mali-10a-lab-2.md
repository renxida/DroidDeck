# Mali lab 2: the game on the desktop's version, lab request #11, and the fight bench again (build c56cc02, engine 135)

**Questions.** (A) Can the phone's copy of Slay the Spire 2 be brought to the version the owner's desktop runs, so
a clean exit stops downgrading Profile 1? (B) Does lab request #11 (the Mali bridge as the default route, the stop
order, start after stop, Steam's browser in software) pass on the phone? (C) What does the fight bench say on the
new game version and the new build?

**Short answer.**

- **A: yes.** The phone's game is on Steam's `public-beta` branch, build id **26909018**, `release_info.json`
  **v0.114.0**, commit **a7b9a9c0**: the desktop's build exactly. A clean quit from the game's menu with
  Profile 1 selected is now safe on both paths: on Proton the three files it rewrites have the same content with
  Windows line endings (schema 25, 40 keys, nothing dropped); on the native engine they come back **byte for
  byte** (sha256 `29d08792…451a81`, as before). The online window was 2 minutes; Steam's cloud was already in
  sync, nothing was downloaded and nothing uploaded.
- **The native engine runs v0.114.0** without a rebuild or complaint, and `release_info.json` does not matter to
  it (it has never found that file).
- **B: every line of #11 that can be provoked on this phone passes.** 18 bridge sessions stopped with nothing
  after the stop line (old rate: 1 crash in 6); 3 of 3 starts after a stop without a screen reach READY with
  their screen; the experiment moves the route to the bridge with one log line and gives it back when switched
  off. One thing it does not cover: the Steam client itself still crashes on its own shutdown in about a third
  of sessions (section 5).
- **C: the fight is unchanged within yesterday's noise.** Native on the bridge: 58.5 and 58.4 fps, animation p99
  35.7 and 40.1 ms, press-to-screen p50 85.8 and 89.8 ms (yesterday 58.7 and 58.6; 36.1 and 40.0; 91.3 and
  88.4). Free memory was 1319 and 1627 MB against 1633 and 1644 MB: one run 0.3 GB lower, in graphics buffers,
  not explained. v0.114.0 deals the saved bench run a different hand, so the script was rewritten.

Marks: **VERIFIED** = read in a log, a `summary.json`, a file hash from the phone or a screenshot I looked at;
**INFERRED** = reasoned from those. Times are the phone's clock (PST), Nov 28 2026, 02:38 to 04:25. Artifacts
(outside the repo): `~/projects/droiddeck/output/lab2/` (`A/` job A screenshots and logs, `B/` job B cycles and
the logcat stream, `C/` bench logs and the exploration screenshots, `Z/` the final state, `manifests/` save
hashes between steps, `scripts/`), `~/projects/droiddeck/output/bench/lab2-*` (bench runs), and the backup
`~/projects/droiddeck/output/sts2-save-backup-20261128T104001Z/`.

## 1. Backup and the tidy-up before going online

- **Backup at 10:40:01 UTC** (02:40 PST), session IDLE, before any save-affecting step:
  `/root/lanner-save-backup-20261128T104001Z/` on the phone (16 MB) and the same tree on the desktop. Steam
  remote storage with `remotecache.vdf` (115 files), the Proton user dir (115), the native user dir (117),
  `override.cfg`. Every file hashed at the source; both copies checked against the lists (`sha256sum -c`, all
  OK); the pulled tar has the hash the phone reported (`4ff0f9f0…9472`). No binaries, no `.pck`.
- Against yesterday's `…T073323Z` backup the only differences were the bench's Profile 3 (three files in remote
  storage, seven in each mirror), the index `remotecache.vdf`, and `.backup` copies the game writes in its
  mirrors. Profile 1 was as restored yesterday.
- **Moved out before the online window** (to `/root/lab2-parked-profile3/`, all also in the backup):
  - from Steam remote storage: `remote/profile3/saves/{current_run,prefs,progress}.save`;
  - the same profile's folder from the Proton mirror and from the native mirror (7 files each). Lanner copies
    nothing between these folders (VERIFIED in source: only `CloudConflicts.kt` touches them, and only for a
    logged conflict), but the game syncs its mirror with Steam's storage at launch, so they went too;
  - `remotecache.vdf` replaced with the `…T073323Z` copy (the state before Profile 3 existed).
- One more step was needed: `remote/profile.save` had yesterday's content but a new time, and Steam compares
  times first. Its time was set back to the index's (`touch`). After that every index entry matched its file in
  size, time and hash, and the folder was **byte-identical to the `…T073323Z` listing** (112 files, VERIFIED).

## 2. The online window and the update

| Time | Event |
|---|---|
| 02:41:34 | Offline mode switched Off |
| 02:41:40 | `start steam`; 02:41:53 `RecvMsgClientLogOnResponse() : 'OK'` (the saved sign-in; no prompt) |
| 02:41:55 | Cloud: `[AppID 2868840] Currently already synced to global change number '154', should be nothing to download`; every file `File is in sync` |
| 02:41:55 to 02:42:02 | Steam fetched a new shader cache for the game (263 MB download, 501 MB on disk) by itself |
| 02:42:59 | `public-beta` selected in Steam's own interface: game page, gear, Properties, Game Versions & Betas (touch on each item, a screenshot looked at before each; `A/b1` to `b5`). The gear was tapped rather than pressed, because the pad focus sat on Play |
| 02:43:05 to 02:43:17 | Update: download 362 MB, 9 files changed, `finished update, 1 mounted depots (BuildID 26909018)`, `StateFlags 4` |
| 02:43:42 | Steam logged off (`stop`); session IDLE 02:43:52; Offline mode On |

- **Result (VERIFIED):** `appmanifest_2868840.acf` has `buildid 26909018`, `BetaKey public-beta` (twice),
  `TargetBuildID 26909018`; `release_info.json` reads `v0.114.0`, commit `a7b9a9c0`, date 2026-11-25. The same
  build as the desktop, not a newer one.
- **Cloud:** nothing changed in remote storage (still byte-identical to the listing after the window, index
  included), no upload, no download, no conflict line in `cloud_log.txt`, the game page read "Steam Cloud: up to
  date". The owner had not played on the desktop since the phone's last sync (change number 154 on both sides).
- **No game was launched while online.** Other things Steam did online: its usual sync of its own settings
  (app 7), a Workshop list check for another game. No other game updated.
- **Residue.** `SlayTheSpire2.pck` is 2 156 859 618 bytes, the depot's size (it was 2 919 955 800 with the
  texture build an earlier experiment had appended). The app did **not** re-create a texture build (`tex-astc` is
  off; the log says `textures: pack changed since (Steam update?); state dropped, pack left alone`). The
  `.exe` did not change in this update (same size and date), so the aligned copy in `.droiddeck-aligned/`
  (894 790 144 bytes, Nov 27) is still the one in use: `warmup: aligned copy of SlayTheSpire2.exe in use (853
  MB)`.
- First launch after the update: Steam's "Processing Vulkan shaders" screen. Skipped once (Proton), then left to
  finish once (6 minutes, offline) so that later launches and the bench are not held or loaded by it.

## 3. The clean-exit test (the point of job A)

Steam offline in both (the crossed-out icon looked at before each launch; `Start offline`, `User is offline`
and `Failed sync … [offlineMode=true]` in Steam's logs). Build `test/lab-1` 21a1157, PanVK route, Profile 1
selected, launch, main menu, Quit, Yes.

| | Before | After the Proton quit (02:47) | After the native quit (02:59) |
|---|---|---|---|
| `profile1/saves/progress.save` bytes | 289 106 | 302 750 | 289 106 |
| sha256 | `29d08792…451a81` | differs | `29d08792…451a81` |
| `schema_version` | 25 | 25 | 25 |
| Top-level keys | 40 | 40, none dropped, none added | 40 |
| Values | | all equal; the file with CRLF turned to LF is byte-identical (13 644 line ends) | identical |
| `prefs.save`; `profile.save` bytes | 348; 49 | 362; 52 (line endings only) | 348; 49, original hashes |

- **Proton: sound.** The Windows build writes the same JSON with CRLF line endings; the desktop's files have LF.
  Nothing is lost, but to Steam the three files are "changed" and would upload once.
- **Native: sound, and byte-identical** to the cloud's copies. The game's log: `Current save versions: …
  SerializableProgress v25 …`, `Wrote 289106 bytes to profile1/saves/progress.save in steam remote store`.
- Both paths log one warning the old build did not: `Progress parse: … Unknown encounter ID:
  ENCOUNTER.BATTLEWORN_DUMMY_EVENT_ENCOUNTER`. It is the game's own (same line on Proton and native) and the
  entry survives the rewrite (INFERRED from the unchanged file).
- **Native on v0.114.0 (VERIFIED, `native-engine.log`):** `composed sts2: 16 game files (2 relabelled for arm64),
  185 kit files`, `first frame after 5.2 s`, `ran 188 s natively, exit 0`, no fallback, no hash or version
  complaint. `File release_info.json not found in any of the expected locations` and `Game doesn't have
  ReleaseInfo …` are there as on v0.111.1: the file only feeds the game's mod-compatibility check and has no
  part in the engine starting or in the save schema. The engine reads the game's own `.pck` directly; there is
  no texture package for it to rebuild while `tex-astc` is off. `native-engine: no check of these game files
  yet; one runs after this session` was logged once.
- The two per-path `settings.save` files now have `seen_ea_disclaimer: true` (the notice was answered with
  Proceed once per path). Nothing else in them changed. They were not put back this time: with matching versions
  they are ordinary writes.

## 4. Lab request #11 on build c56cc02

Installed at 03:00:12 with `adb install -r` over 21a1157, session IDLE (package `chat.comb.lanner` 0.7.1 (19),
signer `a40da80a…f5dc`, file sha256 `54c38513…e1a0`, CI run 46135984070 green). Sign-in and data kept.

Route values:

| | `vkBridge` | `maliRouteBy` | `maliFallbackByAuto` | `bridgeDefaultUndo` |
|---|---|---|---|---|
| Before (21a1157, and c56cc02 before the flip) | false | absent | empty | absent |
| After the flip and one start | true | `auto` | empty | `false\|false\|` |
| Experiment off, one start | false | absent | empty | empty |
| Experiment on again, one start (and as left) | true | `auto` | empty | `false\|false\|` |

| # | Line in the issue | Result |
|---|---|---|
| 1 | After `== session: stopping (status 0)`: none of `lost the bridge server`, `zink: DEVICE LOST`, `(EE) Segmentation fault` | **Pass, 0 in 18 bridge sessions** whose logs were pulled (8 plain start/stop cycles, 4 others without a game, 6 with a game). None anywhere in those logs either |
| 1 | `stopped after the guest: VkBridgeComponent`; the server's exit after the guest's teardown | **Pass, 23 of 23 bridge sessions** in logcat. Order in each: `stop requested`, `session ended`, `stopped after the guest`, then `VkBridge: server exited with status 137` 0.07 to 0.15 s later |
| 1 | No `XWAYLAND_NO_GLAMOR=1` on the bridge or PanVK; the `xwayland:` warnings | **Pass**: 0 occurrences. The warnings never fired (no Xwayland crash to react to) |
| 1 | 20 cycles | Not done as 20 plain cycles: 8 plain, 23 bridge sessions in all |
| 2 | `running: true, screen: false`, then `stop`, `start steam --wait`: READY, `screen: true`, `suspended: false` | **Pass, 3 of 3.** The state was reached as described (Home, then the picture-in-picture window dragged off the bottom); the session suspends itself there, so the stop found it `SUSPENDED` in all three |
| 2 | New session's `app.log` has no `closing the session screen: stop from the notification` | **Pass**: the line appears only after each session's own `stop requested` |
| 2 | `resume` from the same state | **Pass**: `"screenOpen": false, "screenOpened": true, "openedBy": "adb"`; session screen in front, `screen: true`, still running, no `task removed` in logcat. **`openedBy` is `adb`**: Android did not let the app open its own screen from the background, the fallback did |
| 3 | `== steam: 7571 MB of RAM: the browser UI draws in software …` and `== steam: browser UI in software (Mali bridge)` | **Pass**, both, in every bridge session |
| 3 | `BL_VKBRIDGE_CEF_GPU=1` | **Pass**: `== steam: BL_VKBRIDGE_CEF_GPU=1 set by hand (7571 MB of RAM)`, no "in software" line; `Gpu:` at the library 604 MB against 256 MB without the line. `=0` not run (it is what the rule already does here) |
| 3 | Library usable | **Pass**: d-pad right and down move the focus and scroll the home page (screenshots); 7.2 to 7.6 frames a second while idle, as before |
| 3 | Menu, native, bridge, no env line: about 1.55 GB available, GPU about 1.1 GB, 60 fps | **Pass**: 1795 MB (minimum 1655), `Gpu:` 1155 MB, 60.0 fps, p99 21.3 ms (`lab2-menu-native-bridge-1`). Yesterday without the rule: 1616 MB, 1239 MB |
| 4 | One `BridgeDefault` line at the first start after the flip, none at the second | **Pass**: `route (session screen): the Mali bridge is this phone's route now (was bridge false, venus false, drivers Auto, not set by hand); the open driver pair stays installed as its fallback` at 03:01:34, and at none of the 14 starts that followed before the experiment was switched off. Leaving Settings alone did not move the route; the next start did |
| 4 | `graphics-report.txt` | **Pass**: `mali bridge setting: true, in use: true`, `mali route set by: auto, bridge-default: on` |
| 4 | Self-test and driver line | **Pass**: `== vkbridge: OK: 13 of 13 checks passed`, `== vulkan driver: Mali bridge (…)` |
| 4 | Settings > Advanced | **Pass**: `setting-gpu-bridge` reads "Mali bridge: on" |
| 4 | Switch off again | **Pass**: `route (session screen): bridge-default is off; back to bridge false, venus false`; a PanVK session followed (`path: open driver pair (PanVK / RADV)`, `== steam: open driver pair - software CEF compositing, GPU raster`, 7.6 fps at the library). On again: the first line again, bridge session |
| 4 | Explicit choice kept | **Pass, without the app restart**: the bench's PanVK arm pressed the button; `route: Mali bridge switched off by hand; Auto leaves the Mali route alone from now on` (tag `SessionPrefs`), a PanVK session, no `BridgeDefault` line, `maliRouteBy=user` |
| 4 | Fallback when the bridge fails | Not provoked (as the issue says) |
| 4 | Pass line for the flip: fight p99 no worse, over 1 GB free, no `VK_ERROR_DEVICE_LOST` in `vkbridge-client.log` | **Pass**: section 6; `vkbridge-client.log` has 15 lines per session, none of them that |
| 5 | Route button log line | **Pass** for the bridge button (above); the Venus button was not pressed |

Regressions listed in the issue: stop with the screen open closes it at once (**pass**, the screen-close line is
0.03 to 0.4 s after the request, every stop); stop of a suspended bridge session (**pass**, 3 of 3, `session
ended: 0`); start right after a stop (**pass** as far as `droiddeckctl` allows: its `stop` waits for IDLE, the
next `start` came 3 s later, `VkBridge: server ready … after 101 ms`, never `NOT usable`); returning to Steam after
a game on the bridge with the software browser (**pass**, every game session: the game quits through its own
menu and Steam's page comes back); cloud sync at exit not exercised (offline); offline stayed offline, sound
untouched, no save file written by a Steam-only session (hash manifests); PanVK control (**pass**, above).

## 5. Findings beside the checklist

1. **The Steam client crashes on its own shutdown**, on the bridge and on PanVK: `munmap_chunk(): invalid
   pointer` and `Aborted` (exit 134) or `Segmentation fault` (139) right after its `Shutdown` line, in at least 7 of 25
   sessions today (2 of 8 plain cycles, 5 of 9 sessions that ran a game). It is not the Xwayland crash and it
   is not new: yesterday's logs have exit 134 or 139 in 5 of 7 sessions. The app reports `session ended: 134`
   and carries on; the next start was always fine. Worth its own ticket, because with Steam online this is the
   moment cloud sync finishes.
2. **A route button press now makes the route "set by hand" for good**, and then the automatic fallback
   (`BridgeDefault.bridgeFailed`) no longer applies. The bench presses those buttons for a PanVK arm and presses
   them back, which left `maliRouteBy=user`. `tools/bench/run.py` now records `maliRouteBy` and
   `bridgeDefaultUndo` before it touches the route and writes them back afterwards (Debugging tools). For this
   campaign they were put back by hand the same way and checked with one more start (`mali route set by: auto`).
3. **Native launch still pays about 4 s for the free-memory step.** Three launches on c56cc02:

   | From the launch request | Setup session | `lab2-fight-native-bridge-1` | Yesterday (21a1157) |
   |---|---|---|---|
   | `memory.freed` (`game_exec`) done | 6.4 s, the step took 3.9 s | 7.2 s, took 4.7 s | took 3.9 s |
   | `game.engine` | 7.05 s | 7.68 s | 6.8 s |
   | First frame | 11.42 s | 11.57 s | 11.2 s |

   Inside the step: the balloon 2.6 and 3.4 s, the rest closing background processes over 616 packages. It
   raised MemAvailable by 227 and 293 MB from about 2.9 GB, on a launch that then needs under 1.5 GB. The
   remaining 2.5 to 3 s before the engine is Steam's own launch and the session script; the engine draws 3.9 to
   4.4 s after it is chosen. Main menu 32 to 33 s after the request, as yesterday.
4. `droiddeckctl stop` from a session screen that `resume` opened over adb leaves Android's launcher in front,
   not Lanner; the next harness run then stops on its owner-pickup check until Lanner is opened again.
5. Steam's shader pre-caching, on in Lanner's settings, blocks the first launch after a game update for about
   6 minutes offline unless skipped, and the bench does not know that screen.

## 6. The fight bench on v0.114.0 and c56cc02

**The bench profile.** Profile 3's files were put back (remote storage, both mirrors, their three index
entries). v0.114.0 opened them without complaint (`Successfully migrated SerializableProgress from v24 to v25`,
`SerializableRun from v20 to v22`), same run, same seed `KW6PMT0GVE5V`, same fight (one Nibbit, 45 HP,
`NIBBITS_WEAK`), but **a different hand**: Strike, Defend, Strike, Bash, Defend instead of Bash and four
Strikes. The first run aborted on its first check (`aborted-lab2-01-hand-changed`). The hand is the same on
every Continue (VERIFIED over 6 launches), so the same profile and run were kept and
`tools/bench/scenarios/fight.json` was rewritten after playing the fight once by hand (`C/explore/`):

| Turn | New script | Enemy after | Old script |
|---|---|---|---|
| 1 | Strike; right, Strike; left, Defend; End Turn | 39, 33 | right; Strike x3; End Turn (39, 33, 27) |
| 2 | right, Strike, Strike; right, Strike; End Turn | 27, 21, 15 | right x3; Strike, Defend, Defend; End Turn (21) |
| 3 | Strike, Strike; right, Defend; End Turn | 14, 8 | Strike x3; End Turn (20, 14, 8) |

Still 9 cards, 7 attacks, 2 Defends, 3 enemy turns, the same three enemy moves, ending on 8 HP; the same window
lengths. What moved: one attack from turn 1 to turn 2, and three more focus moves. The enemy bar is now checked
only where the focused card's tooltip does not cover it (39, 33, 27, 14, 8). All runs below are on Profile 3.

**Results** (cap 60, Steam offline, sound off, Debugging tools on, Perfetto and `top` running as yesterday):

| | Native, bridge | Native, bridge (repeat) | Yesterday, native, bridge (two runs) | Proton, bridge | Yesterday | Native, PanVK + `present-dmabuf` | Yesterday (three runs) |
|---|---|---|---|---|---|---|---|
| Run | `lab2-fight-native-bridge-1` | `-2` | | `lab2-fight-proton-bridge-1` | | `lab2-fight-native-panvk-dmabuf-1` | |
| Animation fps | 58.5 | 58.4 | 58.7, 58.6 | 51.2 | 51.5 | 59.7 | 59.7, 59.7, 59.6 |
| Animation p50 / p95 / p99 ms | 16.6 / 22.1 / **35.7** | 16.6 / 22.1 / **40.1** | p99 36.1, 40.0 | 16.5 / 34.2 / **122.2** | p99 119.3 | 16.5 / 20.0 / **30.5** | p99 30.5, 30.5, 29.6 |
| Longest frame ms | 138 | 242 | 169, 181 | 418 | 685 | 110 | 85, 125, 112 |
| Over 33 / over 50 ms per minute | 49 / 8 | 55 / 12 | 49 / 11, 54 / 17 | 162 / 86 | 147 / 76 | 32 / 8 | 27 to 31 / 5 to 9 |
| p99 card plays; enemy turns | 35.3; 38.8 | 39.9; 40.1 | 35.1; 37.8 and 40.3; 40.0 | 134.2; 59.6 | 135.5; 58.3 | 29.9; 32.0 | 29.8; 32.8 |
| `pacing` fps on screen, off cadence | 58.9, 28 % | 59.0, 25 % | 59.2, 27 % | 54.9, 28 % | 54.7, 28 % | 59.7, 34 % | 59.8, 29 % |
| **Press-to-screen p50 / p95 / p99 ms** (answered) | **85.8** / 108.7 / 134.3 (50) | **89.8** / 108.4 / 116.6 (50) | 91.3 / 106.3 / 187.6; 88.4 / 104.8 / 448.8 | 94.8 / 108.1 / 149.4 (50) | 94.0 / 108.9 / 123.3 | 83.3 / 94.4 / 101.5 (50) | 81.4 to 84.5 / 93 to 114 |
| Press to the changed game frame p50; frames | 62.9; 4 | 64.8; 4 | 65.6, 63.9; 4 | 67.1; 4 | 66.0; 4 | 55.8; 3 | 53.8; 3 |
| **MemAvailable mean / min MB** | **1319 / 1273** | **1627 / 1602** | 1633 / 1588; 1644 / 1603 | 475 / 427 | 745 / 692 | 520 / 472 | 755, 383, 522 |
| Swap free MB; zram MB | 1871; 582 | 2051; 498 | 2057; 510 | 924; 907 | 405; 1114 | 2317; 387 | 2416; 362 |
| `Gpu:` + dma-buf heap MB | 1171 + 1234 | 1126 + 983 | 1278 + 883; 1287 + 907 | 1072 + 1002 | 1262 + 881 | 2810 + 448 | 2624 to 3125 + 391 to 457 |
| Bridge server's GPU memory MB | 1757 | 1520 | 1607, 1586 | 1541 | 1616 | none | |
| `memory.lmk_risk` | none | none | none | none | at 374 MB | none | one in three |
| Engine's counter: fps, main thread ms (busy) | 58.9, 8.3 (49 %) | 58.9, 8.3 (49 %) | 59.1, 8.0 (48 %) | not logged | | 59.7, 6.4 (38 %) | 59.8, 6.4 (38 %) |
| CPU, one core = 100: game; bridge server; Lanner; gamescope | 78; 41; 24; 12 | 78; 40; 23; 11 | 78; 41; 25; 12 | 106; 31; 36; 9 | 107; 31; 35; 9 | 60; 0; 25; 8 | 60; 0; 26; 8 |
| GPU clock mean MHz | 529 | 544 | 580, 591 | 582 | 619 | 734 | 719 to 739 |
| Battery °C start, max; thermal status | 29.8, 31.2; 0 | 31.0, 31.7; 0 | | 31.3, 32.4; 0 | | 31.6, 33.3; 0 | |
| Launch request to main menu s | 32 | 32 | 32, 33 | 51 | 61 | 33 | 33 |
| Worst frame per step ms (c card, e End Turn) | c 138, 46, 68, e 52; c 34, 40, 35, e 47; c 42, 41, 40, e 102 | c 119, 46, 60, e 43; c 40, 242, 56, e 60; c 41, 47, 54, e 106 | | c 389, 140, 74, e 418; c 138, 149, 140, e 136; c 143, 177, 53, e 173 | | c 99, 40, 52, e 50; c 38, 35, 44, e 41; c 44, 42, 36, e 110 | |

Engine and route as asked in every run (`game.engine`, `graphics-report.txt`), the game's log lists the nine
cards and the three moves, every exit through the game's menu, no late check, no low-memory warning.

**Menu, native, bridge** (`lab2-menu-native-bridge-1`, 120 s from L+240 s, Profile 3): 60.0 fps, p50 / p95 / p99
16.7 / 19.3 / 21.3 ms, press-to-screen 81.1 / 105.1 / 132.7 ms, MemAvailable 1795 MB (minimum 1655), `Gpu:` 1155
+ 999 MB, bridge server 1498 MB. Yesterday: 60.0; 16.7 / 19.2 / 20.8; 82.6; 1616 MB; 1239 + 1176 MB; 1831 MB.

**Reading it against yesterday's noise** (0.1 fps, 4 ms in p99, 5 ms in press-to-screen p50; 11 MB of memory on
the bridge, 400 MB on PanVK):

- **Frame rate, p99, press-to-screen, CPU: no change on any arm.** Every difference is inside the noise, with a
  different game version, a different card order and a different build. The game update did not make the fight
  faster or slower; neither did #11 (it does not touch a running game).
- **GPU memory on the bridge fell by 110 to 190 MB in `Gpu:`** in all four bridge runs (1072 to 1171 MB against
  1262 to 1287 MB). That is the 8 GB rule: Steam's browser no longer holds GPU memory behind the game. VERIFIED
  as a difference; INFERRED as the cause (the `=1` override gives 348 MB back to the browser at the library).
- **Free memory on the bridge is no longer steady.** Yesterday two runs were 11 MB apart; today 1319 and 1627
  MB, and the menu 1795 MB. The low run has 250 MB more in the dma-buf heap (1234 against 983 MB) and 237 MB
  more owned by the bridge server. Not explained; the stop order cannot do it (it acts after the session), so it
  is either the software browser's buffers or the new game version. All three are above the 1 GB line.
- **Proton has less MemAvailable (475 against 745 MB) and more free swap (924 against 405 MB)**: 393 MB more of
  the game is resident and 409 MB less is in swap. The sum of the two is 250 MB better than yesterday, and
  there was no low-memory warning this time. INFERRED: the launch-time balloon pushed less into swap; one run.
- Proton's launch to menu was 51 s against 61 s; the menu arm yesterday was also 51 s, so that is noise.

## 7. Recommended configuration, and how the phone was left

**On:** `native-engine` (passed the clean-exit test and four fights), `bridge-default` with the route on the
bridge and owned by Auto (`vkBridge=true`, `maliRouteBy=auto`; checked with a last start: `mali route set by:
auto, bridge-default: on`). `present-dmabuf` Off (still the best frame times, still 0.5 GB free; not a default).

Also as left: the game quit; **Profile 1 selected** (the game's own profile screen, then Quit from its menu on
the native engine, 04:21); session IDLE; Offline mode On; Game sound Off, boot-movie Sound Off, media volume 0;
stay-awake on; Debugging tools Off; no env lines; build `test/lab-2` c56cc02; USB power, 30.5 °C.

**Saves at the end (VERIFIED by hash):**

- Steam remote storage is **byte-identical to the cloud's state**, index included: all 112 files match the
  `…T073323Z` list (`sha256sum -c`), every index entry matches its file's size, time and hash with `syncstate
  1`. So the next online start has nothing to upload. Profile 1's `progress.save` is `29d08792…451a81`, the
  hash after step A.4 (nothing was downloaded there, so also the hash before the campaign).
- **Profile 3 was moved out again**, so it is not uploaded to the owner's cloud: the files as v0.114.0 left
  them are in `/root/lab2-parked-profile3-v0114/` on the phone (remote storage's three files, both mirrors'
  folders, the index that lists them). To bench again: Debugging tools on, run
  `~/projects/droiddeck/output/lab2/scripts/unpark-v0114.sh` in the rootfs, select Profile 3 in the game. The
  v0.111.1 copies are in `/root/lab2-parked-profile3/` and in the backup.
- Both mirrors match the state after the online window except: each path's `settings.save` (the notice flag),
  and two `.backup` copies the game made in the Proton mirror. The Proton mirror's `profile.save` said Profile 3
  (the last Proton run was a bench run); it was replaced with remote storage's copy, which says Profile 1.

## 8. Rule-relevant events

1. **Online window 02:41:40 to 02:43:52** (2 min 12 s), no game launched in it, nothing uploaded, nothing
   downloaded to remote storage, no sign-in prompt, no conflict dialog.
2. Steam offline for every game launch (the icon looked at for the hand-driven sessions and found by the
   harness for the bench runs; `User is offline` in `timedtrial_log.txt` at each launch looked at).
3. Prompts answered: "What's Early Access?" Proceed, once on Proton and once on native; "Quit Game" Yes at each
   exit; Steam's shader screen Skip once. Steam's interface: Properties and the beta choice only.
4. Profiles and runs: none created. Profile 3 and its run from yesterday were reused; the run is still at the
   start of floor 1. One aborted bench run ended the game by signal on Profile 3. Profile 2 not opened.
5. Debugging tools on 02:39:34, off 04:24:27 (real taps). One env line (`BL_VKBRIDGE_CEF_GPU=1`) for one
   session; the list was empty after it and at the end. `prefs set` was used twice, for the route owner marks
   (section 5, item 2).
6. Lanner switches changed and left changed: `native-engine` Off to On, `bridge-default` Off to On, route PanVK
   to bridge. Everything else as found.
7. Foreground: Android's launcher was in front three times for a few seconds each, by this campaign's own Home
   presses for the start-after-stop test. No owner pickup.
8. Left on the phone: the new backup (16 MB), the two parked Profile 3 folders (0.75 MB), yesterday's backups
   and incident folder.

## 9. Not done

- 20 plain start/stop cycles (8, plus 15 other bridge sessions, 10 of those with their logs read).
- The `=0` override, the Venus button's log line, the app restart in the explicit-choice check, a start sent
  during the teardown, cloud sync at exit (needs Steam online with a game, which the rules exclude).
- A Perfetto trace was taken in each bench run as yesterday and not analysed.
- Why the bridge's free memory varied by 300 MB between two identical fights.
