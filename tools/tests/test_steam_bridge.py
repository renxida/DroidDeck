"""The Steam bridge's version handling, on the build host, against a test double of Valve's library.

The double exists only inside this test's temporary folder: it is never built into a component. It plays a
Steam client that serves only the versions Valve's linux-arm64 library is built for (the worst case for a
game whose Steamworks.NET asks for older ones), and records what the bridge passes on.
"""
import ctypes
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "tools/native-engine/steam-bridge/steam_bridge.c"

# What Slay the Spire 2's Steamworks.NET (2024.8.0) asks for at SteamAPI.Init, accessor by accessor.
GAME = {
    "User": "SteamUser023", "Friends": "SteamFriends017", "Utils": "SteamUtils010",
    "Matchmaking": "SteamMatchMaking009", "MatchmakingServers": "SteamMatchMakingServers002",
    "UserStats": "STEAMUSERSTATS_INTERFACE_VERSION012", "Apps": "STEAMAPPS_INTERFACE_VERSION008",
    "Networking": "SteamNetworking006", "RemoteStorage": "STEAMREMOTESTORAGE_INTERFACE_VERSION016",
    "Screenshots": "STEAMSCREENSHOTS_INTERFACE_VERSION003", "HTTP": "STEAMHTTP_INTERFACE_VERSION003",
    "UGC": "STEAMUGC_INTERFACE_VERSION020", "Music": "STEAMMUSIC_INTERFACE_VERSION001",
    "HTMLSurface": "STEAMHTMLSURFACE_INTERFACE_VERSION_005", "Inventory": "STEAMINVENTORY_INTERFACE_V003",
    "Video": "STEAMVIDEO_INTERFACE_V007", "ParentalSettings": "STEAMPARENTALSETTINGS_INTERFACE_VERSION001",
    "Input": "SteamInput006", "Parties": "SteamParties002", "RemotePlay": "STEAMREMOTEPLAY_INTERFACE_VERSION002",
}
USER = {"SteamNetworkingUtils004", "SteamNetworkingSockets012", "SteamNetworkingMessages002",
        "STEAMTIMELINE_INTERFACE_V001"}
# The library's own versions (the pinned linux-arm64 libsteam_api's exported SteamAPI_Steam*_vNNN accessors).
OWN = {"User": 23, "Friends": 18, "Utils": 11, "Matchmaking": 9, "MatchmakingServers": 3, "UserStats": 13,
       "Apps": 9, "Networking": 6, "RemoteStorage": 16, "Screenshots": 3, "HTTP": 3, "UGC": 21, "Music": 1,
       "HTMLSurface": 5, "Inventory": 3, "Video": 7, "ParentalSettings": 1, "Input": 7, "Parties": 2,
       "RemotePlay": 4}
OWN_USER = {"SteamNetworkingSockets": 13, "SteamNetworkingMessages": 2, "SteamNetworkingUtils": 4,
            "STEAMTIMELINE_INTERFACE_V": 4}
OWN_NAMES = {"SteamNetworkingSockets": "SteamAPI_SteamNetworkingSockets_SteamAPI_v013",
             "SteamNetworkingMessages": "SteamAPI_SteamNetworkingMessages_SteamAPI_v002",
             "SteamNetworkingUtils": "SteamAPI_SteamNetworkingUtils_SteamAPI_v004",
             "STEAMTIMELINE_INTERFACE_V": "SteamAPI_SteamTimeline_v004"}


# The list Steamworks.NET 2024.8.0's SteamAPI.InitEx hands Valve's SteamInternal_SteamAPI_Init, in its order.
GAME_INIT = ["SteamUtils010", "SteamNetworkingUtils004", "STEAMAPPS_INTERFACE_VERSION008", "SteamFriends017",
             "SteamMatchGameSearch001", "STEAMHTMLSURFACE_INTERFACE_VERSION_005", "STEAMHTTP_INTERFACE_VERSION003",
             "SteamInput006", "STEAMINVENTORY_INTERFACE_V003", "SteamMatchMakingServers002", "SteamMatchMaking009",
             "STEAMMUSICREMOTE_INTERFACE_VERSION001", "STEAMMUSIC_INTERFACE_VERSION001", "SteamNetworkingMessages002",
             "SteamNetworkingSockets012", "SteamNetworking006", "STEAMPARENTALSETTINGS_INTERFACE_VERSION001",
             "SteamParties002", "STEAMREMOTEPLAY_INTERFACE_VERSION002", "STEAMREMOTESTORAGE_INTERFACE_VERSION016",
             "STEAMSCREENSHOTS_INTERFACE_VERSION003", "STEAMUGC_INTERFACE_VERSION020",
             "STEAMUSERSTATS_INTERFACE_VERSION012", "SteamUser023", "STEAMVIDEO_INTERFACE_V007"]
# What the library is built for, for each of those (None: the library has no such interface).
LIB_INIT = ["SteamUtils011", "SteamNetworkingUtils004", "STEAMAPPS_INTERFACE_VERSION009", "SteamFriends018", None,
            "STEAMHTMLSURFACE_INTERFACE_VERSION_005", "STEAMHTTP_INTERFACE_VERSION003", "SteamInput007",
            "STEAMINVENTORY_INTERFACE_V003", "SteamMatchMakingServers003", "SteamMatchMaking009", None,
            "STEAMMUSIC_INTERFACE_VERSION001", "SteamNetworkingMessages002", "SteamNetworkingSockets013",
            "SteamNetworking006", "STEAMPARENTALSETTINGS_INTERFACE_VERSION001", "SteamParties002",
            "STEAMREMOTEPLAY_INTERFACE_VERSION004", "STEAMREMOTESTORAGE_INTERFACE_VERSION016",
            "STEAMSCREENSHOTS_INTERFACE_VERSION003", "STEAMUGC_INTERFACE_VERSION021",
            "STEAMUSERSTATS_INTERFACE_VERSION013", "SteamUser023", "STEAMVIDEO_INTERFACE_V007"]


def double_source(serve_old, init_mode=0):
    """C for the test double: each interface is a distinct static object; old versions are served only when
    serve_old. Valve's own accessors go through FindOrCreateUserInterface with their version, as a real one may,
    so the bridge's reentrancy guard is exercised."""
    out = ["#include <stdint.h>", "#include <string.h>", "#include <stdlib.h>",
           "static int serve_old = %d;" % int(serve_old),
           "static char objs[64][8]; static const char *names[64]; static int n;",
           "static void *obj(const char *v) { for (int i = 0; i < n; i++) if (!strcmp(names[i], v)) return objs[i];"
           " names[n] = strdup(v); return objs[n++]; }",
           "static long num(const char *v) { size_t i = strlen(v); while (i && v[i-1] >= '0' && v[i-1] <= '9') i--;"
           " return strtol(v + i, 0, 10); }",
           "char last_version[128]; int generic_calls;",
           "static void seen(const char *v) { strncpy(last_version, v, sizeof last_version - 1); }",
           "void *SteamAPI_ISteamClient_GetISteamGenericInterface(void *s, int32_t u, int32_t p, const char *v)"
           " { generic_calls++; seen(v); return serve_old ? obj(v) : 0; }"]
    for iface, own in OWN.items():
        out.append("void *SteamAPI_ISteamClient_GetISteam%s(void *s, int32_t u, int32_t p, const char *v)"
                   " { seen(v); return (serve_old || num(v) == %d) ? obj(v) : 0; }" % (iface, own))
    out.append("void *SteamInternal_FindOrCreateUserInterface(int32_t u, const char *v) { seen(v);"
               " int own = !strncmp(v, \"OWN_\", 4); %s return (serve_old || own) ? obj(v) : 0; }"
               % " ".join('if (!strncmp(v, "%s", %d) && num(v) == %d) own = 1;' % (p, len(p), k)
                          for p, k in OWN_USER.items()))
    for iface, k in OWN.items():
        name = "SteamAPI_Steam%s_v%03d" % (iface, k)
        out.append('void *%s(void) { return SteamInternal_FindOrCreateUserInterface(1, "OWN_%s%03d"); }'
                   % (name, iface, k))
    for prefix, name in OWN_NAMES.items():
        out.append('void *%s(void) { return SteamInternal_FindOrCreateUserInterface(1, "%s%03d"); }'
                   % (name, prefix, OWN_USER[prefix]))
    # Init: mode 0 takes any list; 1 takes a list only if every entry is one the library knows (the phone's
    # case as read on Nov 15); 2 takes none, only Valve's own list (InitFlat, sent as "FLAT").
    lib = [v for v in LIB_INIT if v]
    out += ["static int init_mode = %d;" % init_mode,
            "char last_list[4096]; int init_calls;",
            "static const char *lib[] = {%s};" % ", ".join('"%s"' % v for v in lib),
            "int SteamInternal_SteamAPI_Init(const char *l, char *m) { init_calls++; last_list[0] = 0;"
            " int ok = 1; for (const char *v = l; *v; v += strlen(v) + 1) {"
            " strcat(last_list, v); strcat(last_list, \" \"); int known = !strcmp(v, \"FLAT\");"
            " for (unsigned i = 0; i < sizeof lib / sizeof *lib; i++) known |= !strcmp(v, lib[i]);"
            " if (init_mode == 2 ? strcmp(v, \"FLAT\") != 0 : (init_mode == 1 && !known)) ok = 0; }"
            " if (!ok) { strcpy(m, \"refused\"); return 1; } m[0] = 0; return 0; }",
            "int SteamAPI_InitFlat(char *m) { return SteamInternal_SteamAPI_Init(\"FLAT\\0\", m); }"]
    return "\n".join(out) + "\n"


@unittest.skipUnless(shutil.which("gcc"), "gcc required")
class SteamBridgeTest(unittest.TestCase):
    def build(self, serve_old, init_mode=0):
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        (d / "double.c").write_text(double_source(serve_old, init_mode))
        subprocess.run(["gcc", "-shared", "-fPIC", "-o", str(d / "libsteam_api.so"), str(d / "double.c")], check=True)
        subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared", "-fvisibility=hidden",
                        "-o", str(d / "libsteam_api64.so"), str(BRIDGE), "-Wl,-rpath,$ORIGIN", "-Wl,--no-as-needed",
                        str(d / "libsteam_api.so"), "-Wl,--as-needed", "-ldl", "-Wl,-z,defs"], check=True)
        # What .NET does for "steam_api64": dlopen it (RTLD_LOCAL), then dlsym on that handle.
        bridge = ctypes.CDLL(str(d / "libsteam_api64.so"))
        valve = ctypes.CDLL(str(d / "libsteam_api.so"))
        return bridge, valve

    def fetch(self, lib, iface, version):
        f = getattr(lib, "SteamAPI_ISteamClient_GetISteam" + iface)
        f.restype = ctypes.c_void_p
        f.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_int32, ctypes.c_char_p]
        return f(None, 1, 1, version.encode())

    def find(self, lib, version):
        f = lib.SteamInternal_FindOrCreateUserInterface
        f.restype = ctypes.c_void_p
        f.argtypes = [ctypes.c_int32, ctypes.c_char_p]
        return f(1, version.encode())

    def own(self, lib, name):
        f = getattr(lib, name)
        f.restype = ctypes.c_void_p
        return f()

    def test_every_interface_the_game_asks_for_is_served_when_the_client_only_serves_the_librarys(self):
        bridge, valve = self.build(serve_old=False)
        for iface, version in GAME.items():
            with self.subTest(iface=iface):
                p = self.fetch(bridge, iface, version)
                self.assertTrue(p, f"{version} came back NULL")
                if int(version[-3:]) != OWN[iface]:
                    # The library's own object, in the layout its flat functions use.
                    self.assertEqual(p, self.own(valve, "SteamAPI_Steam%s_v%03d" % (iface, OWN[iface])))
        for version in USER:
            with self.subTest(version=version):
                self.assertTrue(self.find(bridge, version), f"{version} came back NULL")
        prefix = "STEAMTIMELINE_INTERFACE_V"
        self.assertEqual(self.find(bridge, "STEAMTIMELINE_INTERFACE_V001"), self.own(valve, OWN_NAMES[prefix]))
        for iface, version in (("GameSearch", "SteamMatchGameSearch001"),
                               ("MusicRemote", "STEAMMUSICREMOTE_INTERFACE_VERSION001")):
            self.assertTrue(self.fetch(bridge, iface, version))

    def test_a_version_the_library_shares_is_passed_on_unchanged(self):
        bridge, valve = self.build(serve_old=False)
        last = (ctypes.c_char * 128).in_dll(valve, "last_version")
        for iface, version in (("User", "SteamUser023"), ("Video", "STEAMVIDEO_INTERFACE_V007")):
            p = self.fetch(bridge, iface, version)
            self.assertEqual(version.encode(), last.value)
            self.assertEqual(p, self.fetch(valve, iface, version))
        p = self.find(bridge, "SteamNetworkingMessages002")
        self.assertEqual(b"SteamNetworkingMessages002", last.value)
        self.assertEqual(p, self.find(valve, "SteamNetworkingMessages002"))
        self.assertEqual(self.find(bridge, "SteamUser023"), self.find(valve, "SteamUser023"))

    def test_the_dropped_two_go_to_the_client_first(self):
        bridge, valve = self.build(serve_old=True)
        calls = ctypes.c_int.in_dll(valve, "generic_calls")
        before = calls.value
        p = self.fetch(bridge, "GameSearch", "SteamMatchGameSearch001")
        self.assertEqual(before + 1, calls.value)
        self.assertEqual(b"SteamMatchGameSearch001", (ctypes.c_char * 128).in_dll(valve, "last_version").value)
        self.assertTrue(p)

    def init(self, lib, versions):
        f = lib.SteamInternal_SteamAPI_Init
        f.restype = ctypes.c_int
        f.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        message = ctypes.create_string_buffer(1024)
        return f(b"\0".join(v.encode() for v in versions) + b"\0\0", message), message.value

    def test_init_passes_the_games_list_on_unchanged_when_steam_takes_it(self):
        bridge, valve = self.build(serve_old=False, init_mode=0)
        self.assertEqual((0, b""), self.init(bridge, GAME_INIT))
        self.assertEqual(1, ctypes.c_int.in_dll(valve, "init_calls").value)
        self.assertEqual(" ".join(GAME_INIT) + " ", (ctypes.c_char * 4096).in_dll(valve, "last_list").value.decode())

    def test_init_retries_with_the_librarys_versions_when_the_games_list_is_refused(self):
        bridge, valve = self.build(serve_old=False, init_mode=1)
        self.assertEqual(0, self.init(bridge, GAME_INIT)[0])
        self.assertEqual(2, ctypes.c_int.in_dll(valve, "init_calls").value)
        self.assertEqual(" ".join(v for v in LIB_INIT if v) + " ",
                         (ctypes.c_char * 4096).in_dll(valve, "last_list").value.decode())

    def test_init_falls_back_to_valves_own_list(self):
        bridge, valve = self.build(serve_old=False, init_mode=2)
        self.assertEqual(0, self.init(bridge, GAME_INIT)[0])
        self.assertEqual(3, ctypes.c_int.in_dll(valve, "init_calls").value)  # game's, rewritten, InitFlat (no loop)
        self.assertEqual("FLAT ", (ctypes.c_char * 4096).in_dll(valve, "last_list").value.decode())


if __name__ == "__main__":
    unittest.main()
