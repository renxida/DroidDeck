/*
 * Steam bridge for the native engine (Cedar, 2026-11-15: "Build the bridge"; then, on the game's
 * "Steam failed to initialize" box: "Don't ask just do", i.e. serve whatever the client does not).
 *
 * Placed as libsteam_api64.so, the name a Windows game's Steamworks.NET loads, in front of Valve's own
 * linux-arm64 libsteam_api.so (DT_NEEDED, found beside it through RUNPATH $ORIGIN).
 *
 * The problem: Slay the Spire 2's Steamworks.NET (2024.8.0) was written for an older Steamworks SDK than
 * Valve's linux-arm64 library (SDK 1.63+). At SteamAPI.Init it fetches 26 interfaces by version string and
 * reports init as failed (FailedGeneric, empty message) if any comes back NULL. Ten of the versions it asks for
 * are older than the ones Valve's library is built for (SteamFriends017 against 018, the Timeline V001 against
 * V004, ...). Two it asks for through accessors the library no longer exports (GameSearch, MusicRemote).
 * Every later call the game makes goes through the library's flat functions (SteamAPI_ISteamFriends_...), and
 * those treat the interface pointer as the version the library was built for.
 *
 * What this does, per fetch:
 *  - The version the game asks for matches the library's: passed on unchanged to Valve's function.
 *  - It differs: the game gets the interface Valve's own accessor for the library's version returns
 *    (SteamAPI_SteamFriends_v018() and so on), i.e. the real client's interface in exactly the layout the
 *    library's flat functions expect. If that comes back NULL, the game's own request is passed on instead.
 *  - GameSearch and MusicRemote (no accessor, and no flat functions left in the library, so the game can make no
 *    call on them): Valve's GetISteamGenericInterface with the game's version; if the client serves none, a
 *    placeholder pointer, so Steamworks.NET's null check passes. Nothing can be called through it.
 * Each substitution is written once to stderr ("steam-bridge: ..."), which reaches the native engine's log.
 *
 * Nothing about the account is answered here: SteamAPI_Init, the user, ownership, licences, DLC, tickets and
 * auth are Valve's functions talking to the real Steam client. No interface is invented: every pointer handed
 * out (the placeholder aside) is one the real client returned.
 *
 * Written from the flat-API shape Steamworks.NET (MIT) uses and the library's exported names. No Valve header
 * or source was used.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

typedef void *(*client_get_fn)(void *self, int32_t user, int32_t pipe, const char *version);
typedef void *(*own_fn)(void);
typedef void *(*find_fn)(int32_t user, const char *version);

void *SteamAPI_ISteamClient_GetISteamGenericInterface(void *self, int32_t user, int32_t pipe, const char *version);

/* Valve's own accessors for the versions the pinned library is built for (link fails if one is missing). */
void *SteamAPI_SteamUser_v023(void);
void *SteamAPI_SteamFriends_v018(void);
void *SteamAPI_SteamUtils_v011(void);
void *SteamAPI_SteamMatchmaking_v009(void);
void *SteamAPI_SteamMatchmakingServers_v003(void);
void *SteamAPI_SteamUserStats_v013(void);
void *SteamAPI_SteamApps_v009(void);
void *SteamAPI_SteamNetworking_v006(void);
void *SteamAPI_SteamRemoteStorage_v016(void);
void *SteamAPI_SteamScreenshots_v003(void);
void *SteamAPI_SteamHTTP_v003(void);
void *SteamAPI_SteamUGC_v021(void);
void *SteamAPI_SteamMusic_v001(void);
void *SteamAPI_SteamHTMLSurface_v005(void);
void *SteamAPI_SteamInventory_v003(void);
void *SteamAPI_SteamVideo_v007(void);
void *SteamAPI_SteamParentalSettings_v001(void);
void *SteamAPI_SteamInput_v007(void);
void *SteamAPI_SteamParties_v002(void);
void *SteamAPI_SteamRemotePlay_v004(void);
void *SteamAPI_SteamNetworkingSockets_SteamAPI_v013(void);
void *SteamAPI_SteamNetworkingMessages_SteamAPI_v002(void);
void *SteamAPI_SteamNetworkingUtils_SteamAPI_v004(void);
void *SteamAPI_SteamTimeline_v004(void);

static __thread int depth;

static void say(const char *a, const char *b, const char *c)
{
    const char *parts[] = {"steam-bridge: ", a, b, c, "\n"};
    for (unsigned i = 0; i < sizeof parts / sizeof *parts; i++)
        if (parts[i] && write(2, parts[i], strlen(parts[i])) < 0)
            return;
}

/* The number at the end of a version string ("SteamFriends017" -> 17), or -1. */
static long version_number(const char *v)
{
    if (!v)
        return -1;
    size_t n = strlen(v), i = n;
    while (i > 0 && v[i - 1] >= '0' && v[i - 1] <= '9')
        i--;
    if (i == n)
        return -1;
    long x = 0;
    for (; i < n; i++)
        x = x * 10 + (v[i] - '0');
    return x;
}

/* Length of the version string's name part ("SteamFriends017" -> 12). */
static size_t version_prefix(const char *v)
{
    size_t i = strlen(v);
    while (i > 0 && v[i - 1] >= '0' && v[i - 1] <= '9')
        i--;
    return i;
}

static void *real(const char *name)
{
    void *f = dlsym(RTLD_NEXT, name);
    if (!f)
        say("Valve's library has no ", name, "");
    return f;
}

static void *serve(const char *accessor, int *told, long own_number, own_fn own, const char *own_name,
                   void *self, int32_t user, int32_t pipe, const char *version)
{
    if (depth == 0 && version_number(version) != own_number) {
        depth++;
        void *p = own();
        depth--;
        if (p) {
            if (!*told) {
                *told = 1;
                say(version ? version : "(no version)", " asked for; serving the library's ", own_name);
            }
            return p;
        }
        say(own_name, " returned nothing; passing on ", version ? version : "(no version)");
    }
    client_get_fn f = (client_get_fn)real(accessor);
    return f ? f(self, user, pipe, version) : 0;
}

#define ACCESSOR(Iface, number, own)                                                                      \
    EXPORT void *SteamAPI_ISteamClient_GetISteam##Iface(void *self, int32_t user, int32_t pipe,           \
                                                        const char *version)                              \
    {                                                                                                     \
        static int told;                                                                                  \
        return serve("SteamAPI_ISteamClient_GetISteam" #Iface, &told, number, own, #own, self, user,     \
                     pipe, version);                                                                      \
    }

ACCESSOR(User, 23, SteamAPI_SteamUser_v023)
ACCESSOR(Friends, 18, SteamAPI_SteamFriends_v018)
ACCESSOR(Utils, 11, SteamAPI_SteamUtils_v011)
ACCESSOR(Matchmaking, 9, SteamAPI_SteamMatchmaking_v009)
ACCESSOR(MatchmakingServers, 3, SteamAPI_SteamMatchmakingServers_v003)
ACCESSOR(UserStats, 13, SteamAPI_SteamUserStats_v013)
ACCESSOR(Apps, 9, SteamAPI_SteamApps_v009)
ACCESSOR(Networking, 6, SteamAPI_SteamNetworking_v006)
ACCESSOR(RemoteStorage, 16, SteamAPI_SteamRemoteStorage_v016)
ACCESSOR(Screenshots, 3, SteamAPI_SteamScreenshots_v003)
ACCESSOR(HTTP, 3, SteamAPI_SteamHTTP_v003)
ACCESSOR(UGC, 21, SteamAPI_SteamUGC_v021)
ACCESSOR(Music, 1, SteamAPI_SteamMusic_v001)
ACCESSOR(HTMLSurface, 5, SteamAPI_SteamHTMLSurface_v005)
ACCESSOR(Inventory, 3, SteamAPI_SteamInventory_v003)
ACCESSOR(Video, 7, SteamAPI_SteamVideo_v007)
ACCESSOR(ParentalSettings, 1, SteamAPI_SteamParentalSettings_v001)
ACCESSOR(Input, 7, SteamAPI_SteamInput_v007)
ACCESSOR(Parties, 2, SteamAPI_SteamParties_v002)
ACCESSOR(RemotePlay, 4, SteamAPI_SteamRemotePlay_v004)

/* Interfaces Steamworks.NET fetches through SteamInternal_FindOrCreateUserInterface, by name part. */
static const struct {
    const char *prefix;
    long number;
    own_fn own;
    const char *own_name;
} user_interfaces[] = {
    {"SteamNetworkingSockets", 13, SteamAPI_SteamNetworkingSockets_SteamAPI_v013,
     "SteamAPI_SteamNetworkingSockets_SteamAPI_v013"},
    {"SteamNetworkingMessages", 2, SteamAPI_SteamNetworkingMessages_SteamAPI_v002,
     "SteamAPI_SteamNetworkingMessages_SteamAPI_v002"},
    {"SteamNetworkingUtils", 4, SteamAPI_SteamNetworkingUtils_SteamAPI_v004,
     "SteamAPI_SteamNetworkingUtils_SteamAPI_v004"},
    {"STEAMTIMELINE_INTERFACE_V", 4, SteamAPI_SteamTimeline_v004, "SteamAPI_SteamTimeline_v004"},
};
static int user_told[sizeof user_interfaces / sizeof *user_interfaces];

EXPORT void *SteamInternal_FindOrCreateUserInterface(int32_t user, const char *version)
{
    static find_fn next;
    if (!next)
        next = (find_fn)real("SteamInternal_FindOrCreateUserInterface");
    if (depth == 0 && version) {
        size_t n = version_prefix(version);
        for (unsigned i = 0; i < sizeof user_interfaces / sizeof *user_interfaces; i++) {
            if (strlen(user_interfaces[i].prefix) != n || strncmp(version, user_interfaces[i].prefix, n) != 0)
                continue;
            if (version_number(version) == user_interfaces[i].number)
                break;
            depth++;
            void *p = user_interfaces[i].own();
            depth--;
            if (p) {
                if (!user_told[i]) {
                    user_told[i] = 1;
                    say(version, " asked for; serving the library's ", user_interfaces[i].own_name);
                }
                return p;
            }
            say(user_interfaces[i].own_name, " returned nothing; passing on ", version);
            break;
        }
    }
    return next ? next(user, version) : 0;
}

/* GameSearch and MusicRemote: Valve's library dropped both accessors and every flat function on them. */
static uint64_t placeholder[8];

static void *dropped(int *told, const char *what, void *self, int32_t user, int32_t pipe, const char *version)
{
    void *p = SteamAPI_ISteamClient_GetISteamGenericInterface(self, user, pipe, version);
    if (p)
        return p;
    if (!*told) {
        *told = 1;
        say(version ? version : what, " is not served by this Steam client; ",
            "a placeholder stands in (the library has no functions to call on it)");
    }
    return placeholder;
}

EXPORT void *SteamAPI_ISteamClient_GetISteamGameSearch(void *self, int32_t user, int32_t pipe, const char *version)
{
    static int told;
    return dropped(&told, "GameSearch", self, user, pipe, version);
}

EXPORT void *SteamAPI_ISteamClient_GetISteamMusicRemote(void *self, int32_t user, int32_t pipe, const char *version)
{
    static int told;
    return dropped(&told, "MusicRemote", self, user, pipe, version);
}

/*
 * SteamAPI init. Steamworks.NET's InitEx hands Valve's SteamInternal_SteamAPI_Init the list of interface
 * versions it will use, and Valve's init checks them against the client. On the phone (Nov 15) the game's list
 * failed (FailedGeneric, no message) while Valve's own InitFlat, which checks the library's own list, succeeded
 * in the same session. So, in order, stopping at the first that works:
 *   1. the game's list, unchanged;
 *   2. the same list with each older version replaced by the library's (the versions this bridge serves) and
 *      GameSearch and MusicRemote left out (the library has no interface for either);
 *   3. Valve's own SteamAPI_InitFlat.
 * Each is Valve's own init against the real client; the result of the last one tried is what the game sees.
 */
typedef int (*init_fn)(const char *versions, char *message);
int SteamAPI_InitFlat(char *message);

static const struct {
    const char *prefix;
    long number;
} library_versions[] = {
    {"SteamUtils", 11}, {"SteamFriends", 18}, {"STEAMAPPS_INTERFACE_VERSION", 9},
    {"STEAMUSERSTATS_INTERFACE_VERSION", 13}, {"STEAMUGC_INTERFACE_VERSION", 21}, {"SteamInput", 7},
    {"STEAMREMOTEPLAY_INTERFACE_VERSION", 4}, {"SteamMatchMakingServers", 3}, {"SteamNetworkingSockets", 13},
    {"STEAMTIMELINE_INTERFACE_V", 4}, {"SteamUser", 23}, {"SteamMatchMaking", 9}, {"SteamNetworking", 6},
    {"STEAMREMOTESTORAGE_INTERFACE_VERSION", 16}, {"STEAMSCREENSHOTS_INTERFACE_VERSION", 3},
    {"STEAMHTTP_INTERFACE_VERSION", 3}, {"STEAMMUSIC_INTERFACE_VERSION", 1}, {"STEAMHTMLSURFACE_INTERFACE_VERSION_", 5},
    {"STEAMINVENTORY_INTERFACE_V", 3}, {"STEAMVIDEO_INTERFACE_V", 7}, {"STEAMPARENTALSETTINGS_INTERFACE_VERSION", 1},
    {"SteamParties", 2}, {"SteamNetworkingMessages", 2}, {"SteamNetworkingUtils", 4},
};
static const char *const library_lacks[] = {"SteamMatchGameSearch", "STEAMMUSICREMOTE_INTERFACE_VERSION"};

static int prefix_is(const char *v, size_t n, const char *prefix)
{
    return strlen(prefix) == n && strncmp(v, prefix, n) == 0;
}

/* The game's list rewritten into out (same layout: entries each ended by NUL, a final NUL). 0 if it won't fit. */
static size_t rewrite_versions(const char *in, char *out, size_t cap)
{
    size_t used = 0;
    for (const char *v = in; *v; v += strlen(v) + 1) {
        size_t len = strlen(v), n = version_prefix(v);
        int skip = 0;
        for (unsigned i = 0; i < sizeof library_lacks / sizeof *library_lacks; i++)
            skip |= prefix_is(v, n, library_lacks[i]);
        if (skip)
            continue;
        if (used + len + 2 > cap)
            return 0;
        memcpy(out + used, v, len + 1);
        for (unsigned i = 0; i < sizeof library_versions / sizeof *library_versions; i++) {
            if (!prefix_is(v, n, library_versions[i].prefix) || n == len)
                continue;
            long x = library_versions[i].number;
            for (size_t d = len; d > n; d--, x /= 10)  /* same width, zero-padded */
                out[used + d - 1] = (char)('0' + x % 10);
            break;
        }
        used += len + 1;
    }
    out[used] = 0;
    return used + 1;
}

static void say_result(const char *what, int result, const char *message)
{
    char digits[12];
    int i = sizeof digits - 1;
    unsigned r = result < 0 ? 0u - (unsigned)result : (unsigned)result;
    digits[i] = 0;
    do
        digits[--i] = (char)('0' + r % 10);
    while ((r /= 10) && i > 1);
    if (result < 0)
        digits[--i] = '-';
    say(what, &digits[i], "");
    if (message && *message)
        say("  Valve's message: ", message, "");
}

EXPORT int SteamInternal_SteamAPI_Init(const char *versions, char *message)
{
    static init_fn next;
    if (!next)
        next = (init_fn)real("SteamInternal_SteamAPI_Init");
    if (!next)
        return 1;
    if (depth > 0)  /* Valve's InitFlat below may come back through here */
        return next(versions, message);
    int result = next(versions, message);
    if (result == 0 || !versions)
        return result;
    say_result("SteamAPI init with the game's interface list: result ", result, message);
    static char rewritten[4096];
    if (rewrite_versions(versions, rewritten, sizeof rewritten)) {
        if (message)
            message[0] = 0;
        result = next(rewritten, message);
        say_result("SteamAPI init with the library's interface versions: result ", result, message);
        if (result == 0)
            return 0;
    }
    if (message)
        message[0] = 0;
    depth++;
    result = SteamAPI_InitFlat(message);
    depth--;
    say_result("SteamAPI init with Valve's own list (InitFlat): result ", result, message);
    return result;
}
