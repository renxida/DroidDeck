# Mali native 1: the native ARM engine for Slay the Spire 2 on a Pixel 10a (build ae32618, engine 134)

**Question.** `mali-10a-baseline-1.md` and `mali-10a-memory-1.md` measured Slay the Spire 2 on the Pixel 10a
only through Proton, because the `native-engine` experiment is off by default and a native launch was
suspected of opening a second, empty profile beside Cedar's real saves. Does Lanner's native engine (134) run
on this phone's Mali GPU, which profile does it use, does it leave the real saves alone, and how does it
compare with Proton at the main menu?

**Short answer.** It runs, natively, on PanVK, in both launches, with no fallback. Its Steam bridge initialised
against the real Steam client while Steam was offline. It shows **Cedar's real profile**, not an empty one:
the game itself keeps its saves in Steam's remote storage (`userdata/<id>/2868840/remote/`), which both paths
share, and mirrors them into a local folder that differs per path. On its first launch the native game copied
105 files from that storage into a new local folder; it wrote nothing back. **All 220 files of the real saves
and settings were byte-identical to the backup after the Proton control and after each native run.**

At the menu the native path does not draw faster (44.8 and 40.9 fps against 47.4), but it removes the memory
problem: 690 to 800 MB available instead of 280, 2.2 GB of swap free instead of 0.36 GB, no `memory.lmk_risk`,
1 to 2 lmkd kills instead of 17. The menu is reached about 20 to 30 s after the launch request instead of 49 s.
GPU memory is unchanged (2.9 to 3.0 GB), so that item stays the binding one.

Marks: **VERIFIED** = read in a run's artifacts, a screenshot I looked at, a file listing or hash from the
phone, or source; **INFERRED** = reasoned from verified facts, not observed. Times are the phone's clock (PST),
Nov 27 2026. Artifacts (outside the repo): `~/projects/droiddeck/output/mali-10a-native/<run>/` (`header.txt`,
`summary.json`, `sampler.txt`, `gpumem.txt`, `pacing.json`, `shots/`, `logs/`, `native-engine.log`),
`post-verify.txt`, `post-newfiles.txt`, `scripts/`; the save backup is in
`~/projects/droiddeck/output/sts2-save-backup-20261128T050207Z/`.

## 1. Where the saves are (Proton against native)

Steam user 155889176 (SteamID64 7656…4904). `S` = `/root/.local/share/Steam` in the guest.

| What | Proton path | Native path | Shared? |
|---|---|---|---|
| Steam remote storage for the game: `profile.save`, `profile1/saves/{progress.save (289 KB), prefs.save, history/*.run (100), current_run.*.corrupt (2)}`, `profile2/…`, `modded/profile1,2/…`; 111 files, plus Steam's index `remotecache.vdf` | `S/userdata/155889176/2868840/remote/` | the same folder, through the real Steam client | **yes** (VERIFIED) |
| The game's `user://` | `S/steamapps/compatdata/2868840/pfx/drive_c/users/steamuser/AppData/Roaming/SlayTheSpire2/` | `/root/.local/share/SlayTheSpire2/` | no (VERIFIED) |
| Local mirror of the saves, `user://steam/<SteamID64>/` (`profile.save`, `profile1/…`, `profile2/…`; 105 files) | under the Proton `user://` | under the native `user://`, created at the first native launch | no; both are copies of the remote storage (VERIFIED: same hashes) |
| Game settings, `user://steam/<SteamID64>/settings.save` and `.backup` | Proton `user://` only | native `user://` only | **no**, and not in remote storage (VERIFIED) |
| Godot logs, `shader_cache/`, `vulkan/pipelines.*.cache`, `localization_override/` | Proton `user://` | native `user://` | no |
| `override.cfg` written by Lanner in the game folder | read by the Windows engine | not checked whether engine 134 reads it | same file |

How this was established:

- **Source (VERIFIED).** `droiddeck-native-godot` and `native_engine()` in `droiddeck-game-env` set no user-data
  variable (`engine_env` only touches ICU, locale and `LD_PRELOAD`), and nothing under `tools/native-engine`
  redirects `user://`. The game's project settings, read on the phone with game-env's own parser:
  `use_custom_user_dir = True`, `custom_user_dir_name = 'SlayTheSpire2'`. On Windows that is
  `%APPDATA%\SlayTheSpire2` (`godot_user_dir()` returns the prefix path above); on Linux Godot puts it at
  `$XDG_DATA_HOME/SlayTheSpire2`.
- **Before any native launch (VERIFIED on the phone).** `/root/.local/share/SlayTheSpire2`,
  `/root/.cache/droiddeck` and `/root/.local/share/godot` did not exist. The Proton mirror's `progress.save`,
  `prefs.save` and `profile.save` had the same sha256 as the copies in `remote/`.
- **Native launch 1 (VERIFIED, `native-engine.log`).** `Steam is enabled, we will write saves to steam
  storage`, then `Syncing cloud save files to the local save directory` and 105 lines of `Copying <file> from
  cloud to local. Local file exists: False`, each followed by `Wrote N bytes to path=user://steam/…`. After the
  run, every file in the native mirror hashed the same as the Proton mirror's file of the same name, except
  `settings.save` and `settings.save.backup`.
- The earlier notes' inference ("a second, empty local profile") was half right: the local folder is separate
  and started empty, but the game fills it from Steam's remote storage, so the profile shown is the real one.
- `settings.save`: the native one was written fresh (650 bytes growing to 3221); the Proton one is 3326 bytes.
  Parsed as JSON the two have the same 23 keys and equal values; the 105 bytes are line endings (INFERRED from
  the equal content). So today the two paths happen to agree, but a setting changed on one path will not show
  on the other.

## 2. Backup, and what changed (rule: saves outrank everything)

- No save export exists in the app (the game page has About, Launch and Install tabs only, VERIFIED in
  `GameDetail.kt`), so the files were read with `droiddeckctl native`, which needs Debugging tools. That
  switch was turned on at 21:00 by a tap on the phone's own screen (`adb shell input tap` on
  `setting-agent-commands`), stayed on for all three runs including the Proton control, and was turned off the
  same way at the end (`access`: `commands: false`).
- Backup made at 05:02:07 UTC with no game running, before any native launch:
  `/root/lanner-save-backup-20261128T050207Z/` on the phone (inside the app's container, 11 MB, left there),
  and the same tree pulled to the desktop. Contents: all of `userdata/155889176/2868840` (112 files), the
  Proton `user://steam/` and `localization_override/` (107 files), `override.cfg` (1 file). No game binaries,
  no `.pck`, no shader caches. sha256 of every source file is in the three `SRC-*.sha256` lists; both copies
  were checked against the lists (`sha256sum -c`, all OK), and the pulled archive had the sha256 the phone
  reported (`da6d16b6…2388`).
- Key hashes: `remote/profile1/saves/progress.save` `29d08792…451a81`, `remote/profile.save` `1a68ed91…9973`,
  `remote/profile1/saves/prefs.save` `3eb32f03…5feb`, `remotecache.vdf` `f1914e2b…8a99`, Proton
  `settings.save` `fae1bd0d…7aa2`.
- **Re-hash after the Proton control, during native run 1 at the menu, after native run 1 and after native run
  2: every one of the 220 files matched, no file added or missing** in `userdata/…/2868840` or the Proton
  mirror (`post-verify.txt`). Steam rewrote `remotecache.vdf` at each launch and exit (cloud log:
  `YldWriteCacheDirectoryToFile`) with the same bytes.
- New files from the native runs (VERIFIED, `post-newfiles.txt`): `/root/.local/share/SlayTheSpire2/` (12 MB,
  185 files: the mirror, its own `settings.save`, Godot logs, shader and pipeline caches) and
  `/root/.cache/droiddeck/native/` (`native-engine-134` 182 MB, `launch/2868840` 196 MB of mostly hardlinks,
  `icu` 39 MB, `steam-api`, `steam-probe`). Nothing was written to the game folder. Steam's usual per-session
  files (`localconfig.vdf`, `playtimelog.vdf`, logs) changed as in any session.
- Writes the native game logged (`Wrote N bytes to path=user://…`): run 1, the 105 mirror files and its own
  `settings.save` five times (the last after Cancel on "Load Mods?"); run 2, none at all.
- Nothing was copied between the two mirrors, and nothing was restored. No run was started, no Continue, no
  profile or settings screen opened.

## 3. Method

- Same phone, build and rules as the two earlier notes: `chat.comb.lanner` at ae32618, PanVK, Venus and the
  Mali bridge off, `pe-align` off, Steam offline, sound off (game sound Off, boot-movie sound Off, volume 0).
  The one difference from those notes is Debugging tools **on** in all three runs (section 2).
- Pre-test before every launch as in `mali-10a-memory-1.md`: force-stop Chrome and
  `chat.comb.familiar.voice`, MemAvailable over 2.5 GB, header, switches read from the UI, `start steam`,
  screenshot, **Steam's offline icon looked at**, then `launch 2868840`.
- One run: launch, answer the game's start-up prompt, 120 s window from 240 s after the launch request
  (`frames.csv` the `fp.sh` way, `pacing --seconds 120`, a 10 s sampler of `/proc/meminfo` and `dumpsys gpu
  --gpumem`), then `quit`, `stop`, `logs latest`. lmkd kills from a filtered `adb logcat` stream.
- Runs: Proton control (`native-engine` Off), native 1 (first native launch on this phone), native 2.
  `native-engine` was switched in Settings > Advanced > Experiments and is Off again.
- Steam stayed offline throughout (VERIFIED per session: the offline icon in the screenshot; `Start offline`,
  `User is offline` and `Failed sync for 'AC Launch,Sync Disabled,'` in Steam's logs).

Run headers:

| | Proton control | Native 1 | Native 2 |
|---|---|---|---|
| Pre-test at | 21:03:09 | 21:11:08 | 21:19:22 |
| MemAvailable | 4087 MB | 4176 MB | 4182 MB |
| SwapFree of 3785 MB | 2691 | 2876 | 2849 |
| Battery °C, thermal status | 28.3, 0 | 32.0, 0 | 32.9, 0 |
| lmkd kills since the previous run | 0 | 17 (the control's) | 2 (native 1's) |
| Session id | `2026-11-27-16-steam` | `-17-` | `-18-` |

## 4. Results

### 4.1 Did it start natively (VERIFIED)

- `events.jsonl`: `game.engine {"path":"native","build":"native-engine-134","native_skipped":null}` in both
  native runs; `session.log`: `droiddeck-game-env: engine: native native-engine-134`. No fallback line.
- `native-engine.log`: `composed sts2: 16 game files (2 relabelled for arm64), 185 kit files`; `run
  …/launch/2868840/sts2 --main-pack …/Slay the Spire 2/SlayTheSpire2.pck --print-fps`; `Vulkan 1.4.363 -
  Forward+ - Using Device #0: ARM - Mali-G715 MC7` on PanVK (`BC emulation on, lowmem`); `first frame after
  5.1 s` in both runs.
- Steam: `Steam probe: 27 of 27 interface versions non-null; client non-null` (typed accessors NULL for HTTP
  003 and Video 007), `SteamAPI_Init(): Loaded '…/linuxarm64/steamclient.so' OK`, the bridge's 22
  `steam-bridge: … serving …` lines, `Steamworks initialization succeeded!`. That is Valve's library talking
  to the real client, offline. `Global stats request failed with result k_EResultNoConnection` is the offline
  state showing.
- The engine reads the game's own `.pck`. The ASTC build appended to it by the earlier `tex-astc` experiment
  was parked: the control launch just before logged `textures: original textures this launch; the ASTC build
  is kept (929 MB)`, and the native menu looked the same as Proton's. On a native launch that line is not
  logged at all (4.4, item 3). INFERRED: the appended build does not matter to the engine while `tex-astc` is
  off, because the pack's directory does not point at it.
- Menu (screenshots looked at): "Profile 1" top left, Singleplayer, Multiplayer, Timeline, Settings,
  Compendium, Quit, no top-level Continue: the same as on Proton. `Progress parse` warned about an unknown
  epoch id at index 63, so the progress file it read has at least 64 epochs: the real one.

### 4.2 Launch timeline (VERIFIED: events.jsonl, frames.csv, screenshots, native-engine.log)

| Stage (from the launch request, L) | Proton control | Native 1 (first) | Native 2 |
|---|---|---|---|
| Engine chosen (`game.engine`) | L+8.4 s | L+4.1 s | L+2.9 s |
| First game frame (first `VULKAN` mark in frames.csv) | L+18.4 s | L+14.7 s | L+7.4 s |
| Pauses over 1 s before the menu | 1.2, 10.6, 4.6 s | 3.4, 3.0 s | 2.6, 2.6 s |
| Main menu drawn (behind the game's prompt) | about L+49 s (last mode switch) | about L+28 s (last pause ends L+27.7; on screen at L+40, the first screenshot) | L+19 to 26 s (black at L+18, menu at L+26) |
| The game's own "Time to main menu" | not logged | 14.6 s | 12.5 s |

Native 1 includes staging the engine (the cache folder did not exist a minute before the launch), composing
the launch folder, compiling shaders into an empty cache and copying 105 save files. Whether the component
came from the APK or a download was not determined.

### 4.3 Main menu, 120 s from L+240 s

| | Proton control | Native 1 | Native 2 |
|---|---|---|---|
| frames.csv fps | 47.4 | 44.8 | 40.9 |
| frames.csv p50 / p95 / p99, max | 21.2 / 27.6 / 30.9, 37 ms | 22.3 / 27.0 / 29.9, 39 ms | 24.7 / 30.9 / 34.4, 42 ms |
| Frames over 50 ms per minute | 0.0 | 0.0 | 0.0 |
| `pacing`: fps on screen, p50 / p95 / p99 | 47.4, 24 / 25 / 33 ms | 44.8, 24 / 25 / 33 | 40.9, 24 / 33 / 33 |
| `pacing`: off the usual cadence | 53.4 % | 35.4 % | 26.1 % |
| MemAvailable mean / min (memory.csv) | 279 / 254 MB | 690 / 658 | 803 / 770 |
| Lowest MemAvailable after launch | 198 MB (L+60 s) | 587 (L+77 s) | 559 (L+34 s) |
| Swap free (sampler) / used (memory.csv) | 357 / 3427 MB | 2260 / 1524 | 2176 / 1606 |
| zram physical | 1142 MB | 440 | 440 |
| Game resident + swap | 193 + 1929 MB | 456 + 7 | 457 + 8 |
| Steam side resident + swap | 156 + 643 MB | 205 + 603 | 221 + 593 |
| GPU memory total (`Gpu:`) | 2974 MB | 2993 | 2859 |
| GPU per owner: game / Xwayland of the Steam interface / web helper | 1207 / 1027 / 388 | 1381 / 902 / 388 | 1383 / 763 / 388 |
| `memory.lmk_risk` | yes, L+44 s at 301 MB | none | none |
| lmkd kills in the run (lowest adj) | 17 (500) | 2 (905) | 1 (915) |
| Game process CPU (one core = 100) | 87 | 58 | 62 |
| gamescope / Lanner / steam / pulseaudio CPU | 22 / 26 / 14 / 12 | 28 / 26 / 14 / 4 | 27 / 28 / 15 / 5 |
| Battery °C, thermal status at end; CPU caps | 31.8, 0; none | 33.5, 0; none | 33.6, 0; none |

- The control repeats the earlier Proton runs (46 to 47 fps, p99 31 ms, 260 to 300 MB available, GPU 2974
  MB), so having Debugging tools on did not move the numbers.
- **Memory.** The game process is 0.46 GB instead of 2.1 GB (no Wine copy of the `.exe`'s sections, no FEX
  code, no x86 CoreCLR), and nearly none of it is in swap. That leaves 2.2 GB of swap free, a third of the
  zram, and 0.4 to 0.5 GB more available. Native 2 touches the 800 MB target of `mali-10a-memory-1.md` (803
  mean, 770 minimum); native 1 does not (690). `lmk_risk` did not fire in either native run, and the only
  lmkd kills were one or two cached apps (adj 905, 915) at session start.
- **GPU memory is not better.** The native game holds about 175 MB more of it than the Windows build (1381
  against 1207 MB); the Steam interface's Xwayland held less (902 and 763 against 1027 MB), which INFERRED is
  run-to-run variation of that item (it was 1022 to 1156 MB in the nine Proton runs of the memory note, so
  763 is outside that range and worth a second look). The total stays at 2.9 to 3.0 GB.
- **Frame rate is not better at the menu.** 44.8 and 40.9 fps against 47.4; native 2's p99 is 34.4 ms against
  30.9. The engine's own counters say the main thread is 46 to 48 % busy (`main thread cpu 10.5 ms`, `process
  peak 26 ms`), and the game process uses 0.6 of a core instead of 0.9, so the CPU is not what holds the menu
  at 41 to 47 fps on either path. INFERRED: the limit is on the GPU or present side (PanVK, gamescope), the
  same open question as in the baseline note. Two native runs 4 fps apart are not enough to call native
  slower; they are enough to say it is not faster here.
- Fewer frames landed on the 16 ms slot and more on 24/25 ms on native, which is what "off cadence" reports;
  with the lower fps that is a steadier but slower cadence, not a gain.

### 4.4 What broke or misled

1. **The game's start-up prompts repeat on the native path**, because `settings.save` is per path: native 1
   showed "Load Mods?" (Cancel chosen), native 2 "What's Early Access?" (Proceed, its only button), as on
   Proton. The log lists three Workshop mods found in `steamapps/workshop/content/2868840`
   (`UnifiedSavePathForked`, `BaseLib`, `BaseLibCompatibilityHelper`), all `Skipping loading mod …, user has
   not yet seen the mods warning` in both runs. No mod was loaded.
2. `File release_info.json not found in any of the expected locations`, then `Game doesn't have ReleaseInfo.
   We can't check version compatibility, so assuming all mods are supported`. The file is in the game folder;
   the game looks for it beside the engine binary (INFERRED), which is the launch folder. Harmless without
   mods, wrong with them.
3. On a native launch `droiddeck-game-env` returns before it prints its notes, so `session.log` has no
   `Godot …`, `textures:` or `free memory:` lines and `events.jsonl` has no game-start `memory.freed`.
   INFERRED: the free-memory step at game start does not run on the native path.
4. `logs latest` exports `native-engine.log` under a hashed name (`entry-a3e4c0d4a821cf16.log`); copies are
   in each run folder.
5. Log noise: three `GDExtension dynamic library not found` errors (fmod, sentry, spine: expected, the
   stand-ins replace them), repeated `EntryPointNotFoundException … csharp_interop_is_main_thread …
   sentry-godot` stack traces, `Started the engine as root`, an `ld.so` line about Steam's x86 overlay.
6. The audio stand-in starts the menu music's timeline with game sound off (`fmod timeline start
   event:/music/menu_update`); pulseaudio's CPU fell from 12 % to 4 % all the same. Nothing was audible to
   check, by design.
7. `game.exited` on native says `how: process gone` with no status; on Proton `status 130, abnormal: true`.
   `quit` ends the game by signal on both paths, and the native log ends with `Steam is no longer running.
   Stopping callbacks.` Whether the game flushes anything at a normal Quit from its menu was not tested.
8. The dock still reports battery current as a discharge (1.9 W control, 2.9 W native 1), so power was not
   compared.

## 5. Is native safe to make the default on this phone, and what a correct save mapping needs

**Reading is safe; writing is untested.** VERIFIED: a native launch to the menu and back changes no byte of
the real saves, shows the real profile, and needs no mapping for progress, because the game's own cloud layer
(Steam remote storage) is the shared store and each path's local folder is a mirror the game fills itself.
NOT tested: anything that writes a save from the native engine (finishing or abandoning a run, unlocking,
changing a setting). Those writes go through the bridge's `STEAMREMOTESTORAGE_INTERFACE_VERSION016` ("serving
the client's, as asked") into the shared `remote/` folder, and from there to Steam Cloud the next time Steam
is online. A wrong write there would reach Cedar's other computers.

Recommendation: do **not** add `native-engine` to `Experiments.defaults` on the strength of this note. Turn it
on for this phone after one write test that Cedar agrees to (section 6, item 1). The memory result is the
reason to want it: it is the first configuration on this phone without `lmk_risk`.

What a correct mapping needs, for Cedar to decide (nothing was linked or migrated here):

- **Progress and run history: nothing**, as long as Steam's remote storage works on both paths. The game's
  sync copies whichever side is newer per file, so the two mirrors can drift only in files that never reached
  remote storage.
- **`settings.save`** is local to each `user://`. To share it (and stop the repeated prompts) the native
  `user://steam/` would have to be the Proton one, for example a link from
  `/root/.local/share/SlayTheSpire2/steam` to the prefix's `…/Roaming/SlayTheSpire2/steam`. Open points before
  doing that: the two builds write different line endings; game-env's `sts2_settings_cap` edits the Proton
  file only; a Proton prefix reset would take the settings with it; and the mirror already made on the native
  side has to be removed first, which is a deletion of save copies and so Cedar's call.
- Do **not** point the whole native `user://` at the prefix folder (`XDG_DATA_HOME`): `shader_cache/` and
  `vulkan/pipelines.forward_plus.mali-g715_mc7.cache` have the same names on both paths and are written by
  different engine builds.
- **Mods.** Cedar's Workshop mods include a save-path mod. Mods were off on both paths in these runs (prompt
  cancelled), so the vanilla `profile1` was used. With mods on, the game uses `modded/profileN`; the "seen the
  mods warning" flag lives in the per-path `settings.save`, so the two paths can disagree about whether mods
  load, and then about which profile they show. Fix item 2 of 4.4 before running native with mods.
- `CloudConflicts` (the app's pre-start step that moves a conflicting phone copy to
  `root/lanner-cloud-backups/`) acts on files in `remote/`, so it covers both paths alike. No such folder
  exists on this phone.

## 6. Next experiments, by expected value

1. **Native write test, offline, with Cedar's go.** With the backup in place: on the native path make the
   smallest save write the game offers (Cedar to pick: an empty profile slot, or one abandoned run), quit from
   the game's menu, then hash `remote/` and both mirrors, and start once on Proton to see the same state.
   Confirmed if only the expected files changed in `remote/` and Proton shows it; refuted by any other changed
   file or a parse warning on Proton. Only after that should Steam go online with native-written files.
2. **Fight P99 on native against Proton** (the reason for the engine): two minutes each, `fp.sh` numbers, once
   item 1 says a native run is safe. The menu cannot show the CPU gain; a fight can.
3. **GPU memory on native**: why the native game holds 1.38 GB against 1.21 GB, and why the Steam
   interface's Xwayland varied from 763 to 1027 MB. With native freeing 1.6 GB of swap, the Steam interface's
   1.2 to 1.4 GB of GPU memory (memory note, section 9 item 1) is now the whole remaining gap to a comfortable
   budget.
4. **Why 41 to 47 fps at the menu on both paths**: the native engine is half idle on its main thread, so try
   gamescope's `-r 60` and the present path first (baseline note, item 4), now with the native engine as the
   cheaper test subject.
5. Small fixes: put `release_info.json` where the native game looks; print game-env's notes (and run the
   free-memory step, if wanted) on the native path; export `native-engine.log` under its own name; share or
   seed `settings.save` so the start-up prompts do not repeat; make `quit` ask the native game to quit before
   signalling it.
