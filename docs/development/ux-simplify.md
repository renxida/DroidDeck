# ux/simplify: what went and why

Goal: open the app, see your games, tap Play. No motion.

## Taps to play

| Path | Before | After |
|---|---|---|
| Fresh install (icon, Allow storage, Play) | 3 | 3 |
| Return to the last game | 4 (icon, Library, card, Play) | 2 (icon, **Play <game>**); 1 if Kestrel is open |
| Any other installed game | 4 | 2 (its **Play** in Home's list) |

The fresh-install Allow tap stays: the storage permission still guards /sdcard binds.

## Animations removed

All of them. State changes are instant; the only motion left is a Material progress bar or
spinner while real work runs (runtime install, session loading, resuming, component checks).

- The global `Motion` scaler and the "Animations" setting (it has nothing left to control).
- Launch and return "floods", the page flood, the pause/resume orb with its glint and shake,
  the loading screen's capsule wall, gradients and logo throbber.
- The focus glide (ball/stretch flights between controls), chip glides, rail previews,
  card landing pops, pulse rings, edge fades. Focus is a static outline; selection a static tint.
- Lift sheet and hero, sheet beats, page rise, AnimatedVisibility/AnimatedContent/Crossfade,
  animate*AsState, press-scale on buttons and cards, animated scrolls.
- Inner Gamer hue cycling (and its speed setting), the HUD hue cycle, HUD snap/dock tweens.
- Activity transitions (`windowAnimationStyle` is null; `overridePendingTransition(0, 0)`)
  and the `res/anim` session transitions.
- Rotating loading tips.

## Screens merged

- **Steam** page and **Library** rail page became **Home**: Play the last game, Open Steam,
  and the installed games, each with Play. The featured/owned capsule wall is gone.
- **Components** left the rail; it opens from Settings → Advanced and the GPU drivers link.
- **Setup** became **Settings** with one tab row: Steam, Display, Controls, Audio, Storage,
  Advanced. The old Steam settings sheet lives in those tabs. Advanced holds system checks,
  Steam integration, launcher look and device options, downloads, Diagnostics and Memory.
  The telemetry setting merges into the Diagnostics group beside "Latest logs".
- Gone: readiness card, "Needs you" list, duplicate Controller tab (Controls links to the
  mapping editor).

## Stores and desktop traces removed

`store_*`/`stores_*` strings renamed to neutral `common_*`/`lib_*`; GOG title lookup in
`GameIdentifier`; the desktop loading tips, "touchpad on Desktop" note, "once in Desktop"
shortcut label, second-screen "Desktop" hint; `main_wait_desktop_install` → `main_wait_install`;
store-named prefs and functions (`storesSort` → `librarySort` etc.). Steam's own desktop UI
mode, Wine's virtual desktop and the Desktop input profile (Steam desktop UI) stay.

## Added games removed

"I just want steam and steam games": the non-Steam **Add a Windows game** feature is gone, with
its pickers (folder, EXE), the added-games scan, cache and publisher, SteamMatch, the per-game
Saves tab (GameHub/Winlator save zips, reachable only for added games), the Library source
filter and badges, the shortcut fields on game pages, the art toggle for added games, and
their strings in every locale, tests and `docs/development/added-games.md`.

Kept because Steam games use them: the art chooser (now `GameArt`/`GameArtEditor`, Steam and
SteamGridDB images and a local image file), `SteamLive` (launch options, art and logo position
in the running client), the image picker, launching without Steam (`--app <id>`), and the
SteamGridDB key. Folders added as games folders before this build stay bound in the guest
(`GameFolders`), so a Steam library inside one keeps working; nothing else is read from them.

Guest side, the app no longer sets `BL_ADDED_GAMES`, so the session's shortcut writer, the
import watcher (`droiddeck-steam-games watch`) and its "restart Steam to add games" prompt
are removed. `droiddeck-steam-games` keeps only `probe` (Lossless Scaling ownership) and
`droiddeck-steam-shortcuts` only the account and `shortcuts.vdf` helpers that
`droiddeck-proton-run` reads for Steam launch options of the user's own Steam shortcuts.
The import doc became `steam-ownership-and-art.md`.

## Storage permission

Asked at start only while `WRITE_EXTERNAL_STORAGE` is not granted (`StartupPermissions`,
with a unit test). The app targets SDK 28, so the grant is permanent: storage has no
"only this time" choice, and a return visit asks nothing. If the user denies it, Android
asks again on the next start until they pick "Don't ask again" (API 26-29) or deny twice
(API 30+), after which the request returns without a dialog.
