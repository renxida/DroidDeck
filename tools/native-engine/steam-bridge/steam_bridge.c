/*
 * Steam bridge for the native engine (approved by Cedar, 2026-11-15: "Build the bridge").
 *
 * A Windows game's Steamworks.NET (1.62 era) fetches every Steam interface at SteamAPI.Init, including two
 * whose flat accessors Valve dropped from libsteam_api in SDK 1.63, so Valve's linux-arm64 library (SDK 1.64+)
 * does not export them and the game quits with EntryPointNotFoundException:
 *
 *   SteamAPI_ISteamClient_GetISteamGameSearch    (asks for "SteamMatchGameSearch001")
 *   SteamAPI_ISteamClient_GetISteamMusicRemote   (asks for "STEAMMUSICREMOTE_INTERFACE_VERSION001")
 *
 * This library is placed as libsteam_api64.so, the name the game loads, and needs Valve's own libsteam_api.so
 * (found beside it through RUNPATH $ORIGIN). It defines only those two accessors. Each passes the caller's
 * four arguments, unchanged, to Valve's exported SteamAPI_ISteamClient_GetISteamGenericInterface, which asks
 * the real Steam client for that interface version and returns its answer (an interface, or NULL if the
 * client does not serve it, in which case Steamworks.NET reports Steam init as failed). Every other Steam
 * call, ownership, licence, DLC, tickets and auth included, is not defined here: the dynamic linker resolves
 * it in Valve's library, which this one depends on. Nothing is faked, invented or answered here.
 *
 * Written from the public flat-API shape used by Steamworks.NET (MIT): (self, HSteamUser, HSteamPipe,
 * version) -> pointer. No Valve header or source was used.
 */
#include <stdint.h>

#define EXPORT __attribute__((visibility("default")))

void *SteamAPI_ISteamClient_GetISteamGenericInterface(void *self, int32_t user, int32_t pipe, const char *version);

EXPORT void *SteamAPI_ISteamClient_GetISteamGameSearch(void *self, int32_t user, int32_t pipe, const char *version)
{
    return SteamAPI_ISteamClient_GetISteamGenericInterface(self, user, pipe, version);
}

EXPORT void *SteamAPI_ISteamClient_GetISteamMusicRemote(void *self, int32_t user, int32_t pipe, const char *version)
{
    return SteamAPI_ISteamClient_GetISteamGenericInterface(self, user, pipe, version);
}
