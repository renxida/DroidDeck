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
