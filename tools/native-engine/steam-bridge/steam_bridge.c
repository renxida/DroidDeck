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
 *  - The client answers NULL for a version the library shares (Nov 15: the game's init stopped right after
 *    STEAMAPPS): the library's own accessor, then the client's generic interface for that version.
 *  - GetISteamUtils takes no user argument (self, pipe, version); it has its own function.
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

static void *forward(const char *accessor, void *self, int32_t user, int32_t pipe, const char *version)
{
    client_get_fn f = (client_get_fn)real(accessor);
    return f ? f(self, user, pipe, version) : 0;
}

static void *own_now(own_fn own)
{
    depth++;
    void *p = own();
    depth--;
    return p;
}

/*
 * One fetch, logged once per accessor whatever the outcome (Nov 15: the game's init failed right after
 * STEAMAPPS with no word from the bridge, so every fetch is now named, NULLs included). In order:
 *  - version differs from the library's: the library's own accessor (the layout its flat functions expect);
 *  - the client, asked exactly as the game asked;
 *  - same version but the client said nothing: the library's own accessor, then the generic interface
 *    (the probe shows the client serving every version the game names through it).
 */
static void *serve(const char *accessor, int *told, long own_number, own_fn own, const char *own_name,
                   void *self, int32_t user, int32_t pipe, const char *version)
{
    if (depth > 0)
        return forward(accessor, self, user, pipe, version);
    int same = version_number(version) == own_number;
    const char *how = own_name;
    void *p = same ? 0 : own_now(own);
    if (!p && (p = forward(accessor, self, user, pipe, version)))
        how = "the client's, as asked";
    if (!p && same && (p = own_now(own)))
        how = own_name;
    if (!p && same && (p = SteamAPI_ISteamClient_GetISteamGenericInterface(self, user, pipe, version)))
        how = "the client's generic interface";
    if (!*told || !p) {
        *told = 1;
        say(version ? version : "(no version)",
            p ? ": serving " : ": NULL from the library, the client and the generic interface; tried ",
            p ? how : own_name);
    }
    return p;
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
/* GetISteamUtils takes no user: (self, pipe, version). */
EXPORT void *SteamAPI_ISteamClient_GetISteamUtils(void *self, int32_t pipe, const char *version)
{
    typedef void *(*utils_fn)(void *self, int32_t pipe, const char *version);
    static int told;
    utils_fn f = (utils_fn)real("SteamAPI_ISteamClient_GetISteamUtils");
    if (depth > 0)
        return f ? f(self, pipe, version) : 0;
    int same = version_number(version) == 11;
    void *p = same ? 0 : own_now(SteamAPI_SteamUtils_v011);
    const char *how = "SteamAPI_SteamUtils_v011";
    if (!p && f && (p = f(self, pipe, version)))
        how = "the client's, as asked";
    if (!p && same && (p = own_now(SteamAPI_SteamUtils_v011)))
        how = "SteamAPI_SteamUtils_v011";
    if (!told || !p) {
        told = 1;
        say(version ? version : "(no version)",
            p ? ": serving " : ": NULL from the library and the client; tried ", p ? how : "SteamAPI_SteamUtils_v011");
    }
    return p;
}
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

/*
 * Flat functions the game's Steamworks.NET (2024.8.0) binds that Valve's linux-arm64 library no longer exports
 * (Nov 15: the first native run past SteamAPI init quit on EntryPointNotFoundException for
 * SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck). .NET resolves a P/Invoke when it is first called, so each of
 * these is a crash the moment the game touches it. build.sh fails if any name in steamworks-net-entrypoints.txt
 * is neither here nor in Valve's library, so the next one is caught before a phone sees it.
 *
 * Where Valve's library has the same call under its newer name, it is forwarded. Otherwise the answer is the
 * one a Steam client gives when the feature is absent or not in use: false, the failure code, 0 or NULL. None of
 * these says anything about the account, ownership, licences, DLC, tickets or auth.
 */
typedef int bool_t; /* returned in w0; Steamworks.NET reads the low byte (MarshalAs I1) */

void SteamAPI_ISteamTimeline_ClearTimelineTooltip(void *self, float time_delta);
void SteamAPI_ISteamTimeline_SetTimelineTooltip(void *self, const char *description, float time_delta);
uint64_t SteamAPI_ISteamTimeline_AddInstantaneousTimelineEvent(void *self, const char *title, const char *description,
                                                               const char *icon, uint32_t priority, float start_offset,
                                                               int clip);
uint64_t SteamAPI_ISteamTimeline_AddRangeTimelineEvent(void *self, const char *title, const char *description,
                                                       const char *icon, uint32_t priority, float start_offset,
                                                       float duration, int clip);
void SteamAPI_ReleaseCurrentThreadMemory(void);

/* ISteamUtils: this is not a Steam Deck (a phone running the game natively). */
EXPORT bool_t SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck(void *self) { (void)self; return 0; }

/* ISteamUserStats::RequestCurrentStats was removed in SDK 1.61: Steam requests the current user's stats itself
 * when the game starts and posts UserStatsReceived_t, so the request is already made. */
EXPORT bool_t SteamAPI_ISteamUserStats_RequestCurrentStats(void *self) { (void)self; return 1; }

/* ISteamFriends::GetUserRestrictions was removed; k_nUserRestrictionUnknown (1): not known. */
EXPORT uint32_t SteamAPI_ISteamFriends_GetUserRestrictions(void *self) { (void)self; return 1; }

/* ISteamTimeline v001's ClearTimelineStateDescription is v004's ClearTimelineTooltip (same arguments). The game
 * gets the library's v004 interface from the bridge, so the call goes to the matching function. */
EXPORT void SteamAPI_ISteamTimeline_ClearTimelineStateDescription(void *self, float time_delta)
{
    SteamAPI_ISteamTimeline_ClearTimelineTooltip(self, time_delta);
}

/* v001's SetTimelineStateDescription is v004's SetTimelineTooltip (same arguments). */
EXPORT void SteamAPI_ISteamTimeline_SetTimelineStateDescription(void *self, const char *description, float time_delta)
{
    SteamAPI_ISteamTimeline_SetTimelineTooltip(self, description, time_delta);
}

/* v001's AddTimelineEvent (icon first) is v004's AddInstantaneousTimelineEvent, or AddRangeTimelineEvent when it
 * has a duration (title first). Argument names from Steamworks.NET (MIT) for both versions. */
EXPORT void SteamAPI_ISteamTimeline_AddTimelineEvent(void *self, const char *icon, const char *title,
                                                     const char *description, uint32_t priority, float start_offset,
                                                     float duration, int clip)
{
    if (duration > 0)
        SteamAPI_ISteamTimeline_AddRangeTimelineEvent(self, title, description, icon, priority, start_offset, duration,
                                                      clip);
    else
        SteamAPI_ISteamTimeline_AddInstantaneousTimelineEvent(self, title, description, icon, priority, start_offset,
                                                              clip);
}

/* The game-server side's thread-memory release is the library's one function for every thread. */
EXPORT void SteamGameServer_ReleaseCurrentThreadMemory(void) { SteamAPI_ReleaseCurrentThreadMemory(); }

/* No game server runs in this process (the game never calls SteamGameServer_Init natively): no client. */
EXPORT void *SteamGameServerClient(void) { return 0; }

/* ISteamFriends::SetPersonaName was removed from the SDK: no call made (k_uAPICallInvalid). */
EXPORT uint64_t SteamAPI_ISteamFriends_SetPersonaName(void *self, const char *name) { (void)self, (void)name; return 0; }

/* ISteamRemotePlay::BStartRemotePlayTogether is gone from the library: no session is started. */
EXPORT bool_t SteamAPI_ISteamRemotePlay_BStartRemotePlayTogether(void *self, bool_t show_overlay)
{
    (void)self, (void)show_overlay;
    return 0;
}

/* SteamNetworkingIdentity's Stadia ID (Stadia is gone): none. */
EXPORT uint64_t SteamAPI_SteamNetworkingIdentity_GetStadiaID(void *identity) { (void)identity; return 0; }
EXPORT void SteamAPI_SteamNetworkingIdentity_SetStadiaID(void *identity, uint64_t id) { (void)identity, (void)id; }

/* Custom P2P signaling objects the game would implement itself; the library no longer wraps them. */
EXPORT bool_t SteamAPI_ISteamNetworkingConnectionSignaling_SendSignal(void *self, uint32_t conn, void *info,
                                                                      const void *msg, int size)
{
    (void)self, (void)conn, (void)info, (void)msg, (void)size;
    return 0;
}
EXPORT void SteamAPI_ISteamNetworkingConnectionSignaling_Release(void *self) { (void)self; }
EXPORT void *SteamAPI_ISteamNetworkingSignalingRecvContext_OnConnectRequest(void *self, uint32_t conn, void *identity,
                                                                            int virtual_port)
{
    (void)self, (void)conn, (void)identity, (void)virtual_port;
    return 0; /* NULL: the connection is not accepted */
}
EXPORT void SteamAPI_ISteamNetworkingSignalingRecvContext_SendRejectionSignal(void *self, void *identity,
                                                                              const void *msg, int size)
{
    (void)self, (void)identity, (void)msg, (void)size;
}

/* ISteamGameSearch: matchmaking the library dropped. k_EGameSearchErrorCode_Failed_Unknown_Error (9). */
#define GAMESEARCH_FAILED 9
EXPORT int SteamAPI_ISteamGameSearch_SearchForGameWithLobby(void *self, uint64_t lobby, int min, int max)
{
    (void)self, (void)lobby, (void)min, (void)max;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_SearchForGameSolo(void *self, int min, int max)
{
    (void)self, (void)min, (void)max;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_RetrieveConnectionDetails(void *self, uint64_t host, char *out, int size)
{
    (void)self, (void)host;
    if (out && size > 0)
        out[0] = 0;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_AddGameSearchParams(void *self, const char *key, const char *values)
{
    (void)self, (void)key, (void)values;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_AcceptGame(void *self) { (void)self; return GAMESEARCH_FAILED; }
EXPORT int SteamAPI_ISteamGameSearch_DeclineGame(void *self) { (void)self; return GAMESEARCH_FAILED; }
EXPORT int SteamAPI_ISteamGameSearch_RequestPlayersForGame(void *self, int min, int max, int max_team)
{
    (void)self, (void)min, (void)max, (void)max_team;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_EndGameSearch(void *self) { (void)self; return GAMESEARCH_FAILED; }
EXPORT int SteamAPI_ISteamGameSearch_SetGameHostParams(void *self, const char *key, const char *value)
{
    (void)self, (void)key, (void)value;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_SetConnectionDetails(void *self, const char *details, int size)
{
    (void)self, (void)details, (void)size;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_HostConfirmGameStart(void *self, uint64_t game) { (void)self, (void)game; return GAMESEARCH_FAILED; }
EXPORT int SteamAPI_ISteamGameSearch_CancelRequestPlayersForGame(void *self) { (void)self; return GAMESEARCH_FAILED; }
EXPORT int SteamAPI_ISteamGameSearch_SubmitPlayerResult(void *self, uint64_t game, uint64_t player, int result)
{
    (void)self, (void)game, (void)player, (void)result;
    return GAMESEARCH_FAILED;
}
EXPORT int SteamAPI_ISteamGameSearch_EndGame(void *self, uint64_t game) { (void)self, (void)game; return GAMESEARCH_FAILED; }

/* ISteamMusicRemote: the library dropped it; every call reports failure (false). */
#define MUSIC_REMOTE_FALSE(name, ...) EXPORT bool_t SteamAPI_ISteamMusicRemote_##name(void *self, ##__VA_ARGS__)
MUSIC_REMOTE_FALSE(RegisterSteamMusicRemote, const char *n) { (void)self, (void)n; return 0; }
MUSIC_REMOTE_FALSE(BIsCurrentMusicRemote) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(BActivationSuccess, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(SetDisplayName, const char *n) { (void)self, (void)n; return 0; }
MUSIC_REMOTE_FALSE(SetPNGIcon_64x64, const void *b, uint32_t n) { (void)self, (void)b, (void)n; return 0; }
MUSIC_REMOTE_FALSE(EnableShuffled, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(EnableLooped, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(EnableQueue, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(EnablePlaylists, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(UpdatePlaybackStatus, int s) { (void)self, (void)s; return 0; }
MUSIC_REMOTE_FALSE(UpdateVolume, float v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(CurrentEntryWillChange) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(CurrentEntryIsAvailable, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(UpdateCurrentEntryText, const char *t) { (void)self, (void)t; return 0; }
MUSIC_REMOTE_FALSE(UpdateCurrentEntryElapsedSeconds, int s) { (void)self, (void)s; return 0; }
MUSIC_REMOTE_FALSE(UpdateCurrentEntryCoverArt, const void *b, uint32_t n) { (void)self, (void)b, (void)n; return 0; }
MUSIC_REMOTE_FALSE(CurrentEntryDidChange) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(QueueWillChange) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(ResetQueueEntries) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(SetQueueEntry, int id, int pos, const char *t) { (void)self, (void)id, (void)pos, (void)t; return 0; }
MUSIC_REMOTE_FALSE(QueueDidChange) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(SetCurrentPlaylistEntry, int id) { (void)self, (void)id; return 0; }
MUSIC_REMOTE_FALSE(DeregisterSteamMusicRemote) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(EnablePlayPrevious, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(EnablePlayNext, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(UpdateShuffled, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(UpdateLooped, bool_t v) { (void)self, (void)v; return 0; }
MUSIC_REMOTE_FALSE(SetCurrentQueueEntry, int id) { (void)self, (void)id; return 0; }
MUSIC_REMOTE_FALSE(PlaylistWillChange) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(ResetPlaylistEntries) { (void)self; return 0; }
MUSIC_REMOTE_FALSE(SetPlaylistEntry, int id, int pos, const char *t) { (void)self, (void)id, (void)pos, (void)t; return 0; }
MUSIC_REMOTE_FALSE(PlaylistDidChange) { (void)self; return 0; }
