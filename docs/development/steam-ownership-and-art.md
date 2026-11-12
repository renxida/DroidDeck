# Steam ownership probe and launcher artwork

`droiddeck-steam-games probe <steam_root> <appid>...` asks the running native ARM64
client whether the signed-in account owns each app, through `BIsSubscribedApp` on the
versioned SteamClient020 / SteamUser021 / STEAMAPPS_INTERFACE_VERSION008 interfaces, and
prints `DROIDDECK_OWNERSHIP=<json>` with the account and an `owned` map. No API key,
password, profile visibility change, or CEF debugging port is needed. The session uses it
to check Lossless Scaling (993090) ownership. The handles it opens are released on every
path.

The importer that used this probe to turn the app's added Windows games into Steam
installs was removed with added games (`ux/simplify`); the app no longer sets
`BL_ADDED_GAMES`.

## Launcher artwork

The installed Steam game scan lists each game's `appcache/librarycache/<appid>`
directory once for its cover, background, icon and logo. Filename preference is
checked before directory preference: the app folder, then hash folders sorted by
name. Hash-named fallback icons are still taken only from the app folder in its
listing order. The directory snapshot is not kept between scans, so the next
scan sees new artwork folders.

## Validation

`python3 -m unittest tools/tests/test_steam_games.py` covers the probe's call and release
contract with fake interfaces. Android unit tests cover the artwork scan.

References: [Steam Apps
API](https://partner.steamgames.com/doc/api/ISteamApps#BIsSubscribedApp).
