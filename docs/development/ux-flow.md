# From install to Slay the Spire 2: the Kestrel flow

First read from the code on `ux/core-flow` (base c1c6961), then updated for `ux/simplify`
(see `ux-simplify.md`). Taps count presses on Kestrel's own screens; taps inside Steam's own UI
are listed but are Steam's, not Kestrel's.

## Fresh install to an in-game Slay the Spire 2

Device: Adreno tablet, Android 14, online on Wi-Fi, child-process restrictions still on.

1. Tap the Kestrel icon. `FrontEndActivity` opens on **Home** (`SteamHome`).
2. Android's storage permission dialog (target SDK 28 asks on every launch until granted): **Allow**.
3. Nothing to tap while the Linux runtime downloads: `autoUpdateRuntime` installs it on its own
   when online, unmetered and the GPU can draw. A runtime chip on Home shows progress and
   **Open Steam** stays disabled until it finishes.
4. Tap **Open Steam**.
5. Only while Android's child-process limit blocks Steam: the "One Android setting to change"
   page (`PhantomProcessGatePage`). Either Developer options → "Disable child process
   restrictions", or **Fix it for me** → Wireless debugging pairing through a notification.
   About 4 to 8 taps, once per device; then **Open Steam** again.
6. Non-Adreno only, first install: "Unsupported GPU" confirm (**Install anyway**).
   Under 10 GB free: a low-space dialog (**Play anyway** / **Free up space**).
7. `SessionActivity` loading screen: installs the runtime if step 3 did not, the Steam client
   and Steam's ARM64 Proton. No taps.
8. Steam's own UI: sign in (QR or typed credentials), find Slay the Spire 2, **Install**,
   confirm, wait, **Play**. About 8 taps in Steam, plus typing if not using the QR code.
9. In game. The session overlay (Quick Access / hold Back) holds the in-session settings.

Kestrel taps on the critical path: icon, Allow, Open Steam = **3** (plus the one-time gate).
The Allow tap stays until the storage permission is deferred (proposal 5).

## Returning to play an installed game

- Icon → **Play <last game>** on Home = **2** taps (1 tap once Kestrel is open). Before
  `ux/simplify`: icon → Library → card → Play = 4.
- Any other installed game: icon → its **Play** button in Home's game list = 2 taps.

A game link or home-screen shortcut (game page → Shortcut → Add to home screen) starts the
game in one tap from the launcher.

## Screens and settings exposed

Rail: **Home** and **Settings** (plus **Resume** while a session runs).

- **Home** (`SteamHome`): wordmark, runtime chip while busy, **Play <last game>** and
  **Open Steam** (only **Open Steam** before any game is installed), then every installed game
  with its own **Play**. A row opens the game page (About, Launch, Saves, Install).
- **Settings** (`SetupPanel`), one row of tabs:
  - Steam: account/offline, Steam client options, session behaviour, Performance page link.
  - Display: resolution, scaling, HDR, frame generation, GPU drivers link.
  - Controls: touch mode, on-screen controls, controller type, mapping editor link.
  - Audio.
  - Storage: game storage and libraries, usage and clean-up.
  - Advanced: system checks (GPU, runtime, child-process limit, Steam account, Venus/bridge
    switches), Steam integration, Components link, launcher look/device/art/download options,
    Diagnostics (logs, share/save/delete session logs, debug toggles, reset) and Memory.
- **Components** (from Advanced or the GPU drivers link): GPU drivers, Proton, DXVK, VKD3D, FEX.

## What the cuts left pointless

- A **Steam** source badge on every Library card and on each game page, when Steam is the only
  source: one-option labelling. (Fixed here.)
- The Setup rail badge for a runtime that is merely not installed yet: Play and the automatic
  install both handle it. (Fixed here.)
- The Overview readiness card repeated the runtime row that sits directly under it. (Fixed here.)
- Copy that still names the desktop ("both modes"), stores, Battle.net, store accounts, or
  "Stores" as a launcher section; a "first run sets up the desktop" loading hint; and the Proton
  empty state sending people to Setup, where Proton isn't. (Fixed here.)
- "Open at launch" in the Steam sheet is a two-value choice (Off / Steam) shown as a menu, and
  the sheet keeps generic multi-mode plumbing. Left to `cleanup/leftovers`, which owns
  `ModeSettingsDialog.kt`.
- `FrontEndActions.onPlayDesktopUi` is wired but nothing calls it. Dead code; left to
  `cleanup/leftovers`.
- `com.oculus.supportedDevices` in the manifest is a store-listing leftover from the VR build.

## Proposals

Need a design decision or a device to judge; not implemented.

1. *(Done in `ux/simplify`: Home lists installed games.)* **Open on Library when a game is installed.** The Steam page is the right first-run landing,
   but once Slay the Spire 2 is installed, opening on Library cuts the return path from 4 taps
   to 3. Needs care so a late library scan doesn't move the user mid-navigation.
2. *(Done in `ux/simplify`.)* **"Continue" on the Steam page.** Make the most recently played game a one-tap button next to
   Play Steam (return path 2 taps). New UI, so a design call.
3. **Play from the Library card.** A controller button (X/Y) or long-press on a card that starts
   the game without opening its page.
4. **Carry on after the child-process gate.** When the limit clears, start the launch the user
   asked for instead of returning them to press Play again (one fewer tap). Must not start a
   session while Kestrel is in the background (the fix runs in Android Settings).
5. **Ask for storage only when needed.** The storage permission is requested on every launch
   until granted; it is only needed for exports to Download/Pictures. Deferring it removes a
   first-run tap, but needs checking which paths break without it on Android 10 and older.
6. *(Done in `ux/simplify`.)* **One home for settings.** Settings live in four places: the Steam sheet, the Performance
   page, Setup and Components, and controller settings appear both in Setup → Controller and
   the sheet's Controls tab. Proposed: Setup keeps app-wide settings, the Steam sheet keeps
   session settings, and Components moves under Setup (it is power-user material and the rail
   would drop to three entries).
7. *(Done in `ux/simplify`.)* **Merge the Steam and Library rail pages.** With one store, a single home with Play Steam on
   top and installed games below would remove a rail entry. Big visual change; judge on a device.
8. **Rename Setup → Launcher.** "Launcher" was also the name of the removed home-screen launcher;
   "Interface" or "App" would be clearer. Touches translations in every locale.
9. *(Done in `ux/simplify`: the readiness card is gone; checks sit under Advanced.)* **Fold the Steam account and integration checks.** On a fresh install the Overview shows
   "Not ready to play yet" with a Steam-account warning that has no action besides pressing
   Play. Consider counting only checks the user can act on in Kestrel.
10. **Translations of the corrected copy.** The strings fixed here now fall back to English in
    other locales until they are retranslated.
