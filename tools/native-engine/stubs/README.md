# Native-path stubs

`steam_stub.c` and `steam_stub_auto.c` come unchanged from github.com/Ekyso/StS2-Launcher @1e97cf8
(`src/stubs/`, MIT, licence in `LICENSE.StS2-Launcher`). They answer Steamworks.NET's P/Invoke calls with
safe defaults so a game that calls Steamworks starts without Valve's library. Steam still tracks the session
and Auto-Cloud still syncs files; achievements and other live Steam features do nothing on this path.

Built as `libsteam_api64.so` (the name a Windows build of Steamworks.NET asks for) and `libsteam_api.so`.
