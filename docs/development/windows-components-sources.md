# Windows components by source

A game's Windows components (VC++ runtimes, DirectX's d3dx9, XNA, PhysX...) are picked for it by
itself from the list Steam keeps for it (`WinCompSources`):

| Source | List | On by itself |
| --- | --- | --- |
| Steam | The Steamworks Shared redistributables Steam installs with the game: the appmanifest's `SharedDepots`, else the client's app info (`SteamAppInfo`) | yes |
| Folder scan | the game folder's own installers (`DependencyDetector`) | no, Recommended |

The folder scan is offered as Recommended for every game.

## Steam's data

Never scraped from a website. `SteamAppInfo` reads the Steam client's own app-info cache in the
runtime (`Steam/appcache/appinfo.vdf`, versions 27-29): the app's depots that point at app 228980
(Steamworks Shared) and its name. Each appid's answer is kept in `files/steam-appinfo.json`, so it
works offline; an app the client has not seen is looked for again after a week. A Steam game with
its appmanifest beside it uses the manifest first. `droiddeck-seed-redists` still marks Steam's
install scripts for those redistributables as already run, so the client never runs the real
installers.

## Mapping

In `WinCompSources`, Steam's list is the Steamworks Shared depots 228981-228990 (`SteamRedists.DEPOTS`).
Anything that maps to nothing is listed under "Also listed" with its own name. A mapped component
that needs a Windows installer here, or is not in the catalog, stays Recommended with its status.

## On by itself, and the user's switches

All of it runs on one background thread (`AutoComponents`), low priority (the runtime helpers an
install starts run under `nice -n 19` and `ionice -c3`), one job at a time from one queue: a game's
list (worked out again only when the files it is read from change, by modification time, and kept
in `files/wincomp-findings.json`, so a page opens on it at once) and a component's download (each
component once, for every game that wants it). What the user asked for goes first: the page of a
game (on a metered network too). A game's list is queued when it is
installed, when its page opens, when it is launched from DroidDeck (the launch
itself only takes what is on already), and for every Steam game once per app start, ten seconds
after the launcher is up (Steam installs them without a word to DroidDeck). The queue waits while
a session runs, and a download also waits for a validated network (the page then greys what is not
downloaded yet, "Available when online", and follows the network live); what nobody asked for also waits on battery saver, thermal status moderate or
worse, or a metered network. A component turns on for its games when its download lands, for the
next launch, and an open page shows the progress and reads the picks again. One log line per
queued and per finished download.

`wincomponents.json` keeps, per game,
`auto` (each with its reason) and `user` (each switch the user pressed, on or off) beside `games`,
the effective list the launch reads (still version 1). A user switch always wins over an automatic
pick, either way, and a new automatic list never touches it. Picks saved before this build count
as the user's own.

The page shows "On for this game" with each reason ("From GOG's list for this game", "Steam
installs VC++ 2015 with <game> (app N)"), then Recommended, then "Also listed".

## Older store installs

A GOG or Epic game installed before its sidecar kept the store's list gets it once, on the worker
(`StoreListBackfill`): GOG's build manifest for the installed build (`dependencies`), Epic's
manifest (prerequisite name and path), fetched with the saved sign-in - the manifest alone, never a
game file - and kept in the sidecar as an install keeps it. Fresh installs now keep the keys even
when empty. Without a sign-in or when the store refuses: skipped quietly, tried again a week later;
offline is no try. Like any work nobody asked for it waits for an unmetered network, unless the
game's page is open.

## Apps the client never saw

A Steam app the client in the runtime never saw (not owned, never viewed: a Custom game's
steam_appid.txt) is missing from its `appinfo.vdf`. `SteamPics` asks Steam for it anonymously:
Steam's public CM list (`ISteamDirectory/GetCMListForConnect`), a WebSocket connection to a
connection manager (`wss://<cm>/cmsocket/`, the transport Steam's web clients use), an anonymous
logon (an AnonUser SteamID, no account, nothing of the user's Steam sign-in), a
`ClientPICSAccessTokenRequest` and a `ClientPICSProductInfoRequest` (at most 25 apps each, all in
one connection), the app KeyValues read for depots with `depotfromapp` 228980 and the name, and a
logoff. Kotlin only, no library (TLS from the platform, a minimal WebSocket and protobuf reader).
Answers go into `steam-appinfo.json` like the client's (a miss is asked again after a week).

Order for an appid: the client's `appinfo.vdf` first (free), then the anonymous lookup. It runs on
the components' worker: one batch for every game in the start/after-session pass (before their
lists), or the one app of a game whose page is open; never during a session, and as work nobody
asked for only on an unmetered, favourable device. Log: `steam pics: N apps, M with shared redists`.

## A game installed during a session

`SessionInstalls` looks at the Steam libraries' appmanifests every five seconds while a session
runs (a manifest re-read only when its time changes); a game whose StateFlags turn to 4 (fully
installed) becomes one job allowed to run in the session (`AutoComponents.queueInSession`): its
list (local appmanifest, appinfo.vdf, the kept PICS answer; one small anonymous lookup only for an
app nothing knows), its automatic picks written to `wincomponents.json` (the runtime's copy too) and
only its missing components downloaded, at low priority. It waits while a game runs in the session
(Steam's reaper `SteamLaunch AppId=`, or Proton's `waitforexitandrun`). All other work stays
paused. While its components download, the app lists them per game in the runtime's
`~/.config/droiddeck/wincomponents-pending.json`; `droiddeck-wincomponents` at launch waits up to
10 s for those (no wait when nothing is pending), then applies what is there: log
`wincomp: <appid> waited Nms for <ids>` or `... launched without <ids> (not ready)`.
