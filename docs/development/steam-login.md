# Steam sign-in and test-build updates

Question from use: installing is slow (Steam downloads), and the Steam sign-in is painful on the
built-in keyboard (no paste, no password manager, an email code). Branch `ux/steam-login`, base
`7e53838`. VERIFIED means read in source, in CI logs or in Valve's own pages; INFERRED means
reasoned from those and not yet seen on a device.

## 1. Does installing a new test APK over an old one keep the sign-in and the games?

**Yes.** Nothing in the app wipes, re-extracts or re-downloads the runtime, the Steam client, the
sign-in or `steamapps` because the APK changed. Only uninstalling Kestrel (or Clear storage) does.

- VERIFIED. All CI APKs are signed with the committed AOSP testkey (`keystore/testkey.p12`,
  `app/build.gradle:132-157`, `.github/workflows/build.yml:547-573`) under the same
  `applicationId dev.renxida.kestrel` and `versionCode 19` (`app/build.gradle:96-99`). Android
  accepts an equal versionCode as an update and keeps the app's data; only a lower one is refused.
- VERIFIED. Everything lives in the app's internal storage:
  - Linux runtime: `filesDir/linuxfs` (`runtime/LinuxRuntime.java:45-47`).
  - Steam home, sign-in and default library: `filesDir/linuxfs/root/.local/share/Steam`
    (`config/loginusers.vdf`, `config/config.vdf`, `local.vdf`, `steamapps/`), and
    `root/.droiddeck-x64/.local/share/Steam` for the x86-64 client (`session/SessionFiles.kt:227-228`,
    `session/OfflineMode.kt:18-20`, `frontend/SessionInstalls.kt:35`). The saved sign-in token is
    `local.vdf`'s `ConnectCache` (`tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-x64:923-927`).
  - Extra libraries the user adds sit on shared or SD storage (`session/SteamLibraries.kt`), which an
    app update never touches.
  - Add-on packs: `filesDir/addons/<name>/<sha256>` (`core/AddonPack.kt:39-45`).
- VERIFIED. The runtime is (re)installed only when it is missing or the upstream catalog publishes a
  new rootfs version, never because the APK changed: `SessionActivity.kt:698`
  (`installedVersion == null`), `runtime/RuntimeAutoUpdate.kt:13-15` (`release != installed`). Even
  a rootfs update carries `/root` across (`runtime/LinuxRuntimeInstaller.java:302-320`,
  `RuntimeFileTree.carryUserData`).
- VERIFIED. What does change per APK is cheap: `SessionFiles.stage` recopies about 90 helper
  scripts and libraries from the APK when the APK file identity changes
  (`session/SessionFiles.kt:140-160`, `apkIdentity` at `:286-289`); `BootMovie.extractAsset`
  (`session/BootMovie.kt:171`) and the Venus ICD (`session/VenusComponent.kt:118`) re-extract small
  assets after `lastUpdateTime`. None of these touch the Steam home.
- VERIFIED. The Steam client is not reinstalled: `droiddeck-steam-install` exits at once when
  `steamrtarm64/steam` and its stamp exist (`tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-install:73`).
  It repairs (re-downloads the client, keeping config and games) only after two starts that never
  showed a frame (`:36-38`).
- VERIFIED. Add-on packs are keyed by content SHA-256, and fork CI builds them byte-identically:
  runs 43291058756, 43276823567 and 43274538855 all built proot `02ba2494…`, directaudio
  `fd65f0c1…` and powervr `1da18f32…`, and those three tags answer 200 on
  `Droid-Deck/DroidDeck-Components` releases. A new test APK therefore finds its packs already
  installed. A pack is downloaded again only if its inputs change (`AddonPack.kt:43,118`).

So no code change was needed for item 1. Long waits that remain, none tied to the APK: the very first
install (runtime, Steam client, Proton), Steam's own client self-update when Valve ships one, a
new upstream rootfs release, and the game download itself.

If a build ever cannot be installed over the old one (Android says the package conflicts), do not
uninstall: that is the only step that loses the sign-in and the games. Report it instead.

## 2. Paste

- VERIFIED, already present. Android's clipboard reaches the guest: `input/SessionClipboard.kt`
  publishes the clipboard text on focus and on every change to the Wayland compositor and to
  `filesDir/session/android-clipboard`; the guest's `droiddeck-clipboard`
  (`tools/linuxfs/clipboard/clipboard.c`, started by `droiddeck-session:567-568`) watches that file
  with inotify, takes the X `CLIPBOARD` selection and serves `UTF8_STRING`/`STRING` to whoever pastes.
  Its log line is `== clipboard: Android text available in Xwayland (N bytes)`.
- VERIFIED, the gap. Nothing visible sent Ctrl+V: the PC keyboard had no Paste key, and only the
  optional Paste button of the Steam touch controls (`SteamTouchConfig.PASTE`, off by default) did.
  Ctrl+V on the PC keyboard needs a latched Ctrl plus V.
- Changed: the PC keyboard's header now has **Paste**. It republishes the Android clipboard if it
  changed, waits 250 ms for the guest bridge's inotify hand-over when it did, then sends Ctrl+V
  (`SessionActivity.pasteIntoSession`). The Steam touch controls' Paste uses the same path.
  Log line: `session paste: Ctrl+V into the guest (Android clipboard republished=…)`.
- INFERRED: Steam's sign-in fields are Chromium (CEF) inputs, which read `CLIPBOARD` on Ctrl+V, so
  paste works in both the account and password fields. Not yet seen on a device.

## 3. Password manager

Android autofill cannot see inside the X surface, so the PC keyboard now has **Sign in…**, which
opens a native Android sheet (`SessionActivity.openSignInSheet`):

- Two `EditText`s with `AUTOFILL_HINT_USERNAME` and `AUTOFILL_HINT_PASSWORD` and
  `IMPORTANT_FOR_AUTOFILL_YES`, so Google Password Manager, 1Password, Bitwarden and the like offer
  to fill them; long-press paste works too. Kestrel does not claim a web domain for them, so the
  first time the manager may ask you to pick the Steam entry.
- **Type into Steam** copies both fields into char arrays, clears the fields, closes the sheet and,
  400 ms later (once the session window has focus back), types account, Tab, password as evdev
  presses through `GuestText` and the same `sendGuestKey` path every on-screen key uses. The arrays
  are zeroed in a `finally`. Nothing is stored or logged; the sheet's window is `FLAG_SECURE`; the
  autofill session is cancelled on close so Android does not offer to save. No Enter is sent.
- Characters with no key on a US layout (accents, €) are counted and reported in a toast, never
  typed wrongly. Unit tests: `app/src/test/.../input/GuestTextTest.kt`.
- Log line: `sign-in sheet: typed account=true password=true untypable=0` (presence only).
- INFERRED: Tab moves from Steam's account box to its password box (ordinary HTML form order). If
  not, leave the account box empty in the sheet and use it for the password alone.

## 4. Steam's own ways around the email code

Sources: Valve, "The updated Steam Mobile App is now available" (12 Oct 2022,
store.steampowered.com news); Valve, "Steam Deck Client Beta Update: QR code login, Steam Input, and
more" (27 Oct 2022, Steam Deck news, "Added login flow that supports new QR code functionality");
Steam Support, "I am having trouble with my Steam Guard Mobile Authenticator"
(help.steampowered.com, issue 809); Steam Support, "Steam Guard Code Help".

- VERIFIED (Valve): with the Steam Guard Mobile Authenticator in the Steam Mobile App you can
  *scan a Steam QR code* instead of typing account, password and code, or type account and
  password and *confirm with a single tap in the app* instead of a Steam Guard code. A code typed
  by hand stays available, e.g. without phone service.
- VERIFIED (Valve): email codes are the Steam Guard method of accounts without the mobile
  authenticator ("Steam Guard emails are sent to the last email address registered").
- VERIFIED (source): Kestrel starts the arm64 client as the Deck UI, `-gamepadui -steamdeck
  -steamos3` (`droiddeck-session:1321,1349-1354`), on Valve's `steamdeck_*` channels
  (`droiddeck-steam-install:4-5`), i.e. the client whose sign-in screen gained QR login.
  INFERRED: the QR code shows on Kestrel's sign-in screen; not seen on a device here.
- VERIFIED (source): a remembered sign-in is `"RememberPassword" "1"` in `loginusers.vdf` plus the
  `ConnectCache` token in `local.vdf` (`droiddeck-session:1080`, `droiddeck-steam-x64:906,923`),
  both inside the Steam home that survives updates (section 1). The x86-64 client copies the
  arm64 client's sign-in rather than asking again (`droiddeck-steam-x64:840-935`).

One phone:

| Option | Works from one phone? |
|---|---|
| QR code | No: the phone's camera would have to scan its own screen. Needs a second device showing or scanning. |
| Approve the sign-in in the Steam Mobile App | Yes: type or fill account and password in Kestrel, switch to the Steam app, tap Approve, switch back. INFERRED: if Kestrel pauses the session in the background, Steam finishes the sign-in when you return. |
| Steam Guard code from the Steam app | Yes: copy it in the Steam app, then **Paste** in Kestrel. |
| Email code | Yes, slowest: copy it from mail, then **Paste**. |

## Easiest path for one phone

1. Once, in the Steam Mobile App: turn on the Steam Guard Mobile Authenticator. Sign-ins then ask
   for an approval in the app instead of an email code.
2. In Kestrel's Steam sign-in screen, tap the account name box, open the PC keyboard and press
   **Sign in…**. Let the password manager fill both boxes, press **Type into Steam**, then press
   Steam's Sign in.
3. Switch to the Steam app, tap Approve, switch back.
4. Install new test builds over the old one; never uninstall. The sign-in and the games stay.
