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


def double_source(serve_old, init_mode=0, typed_null=False):
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
    # typed_null: the client's typed accessors answer nothing, whatever the version (a guess at the Nov 15 phone).
    for iface, own in OWN.items():
        args = "void *s, int32_t p, const char *v" if iface == "Utils" else "void *s, int32_t u, int32_t p, const char *v"
        out.append("void *SteamAPI_ISteamClient_GetISteam%s(%s)"
                   " { seen(v); return (%d || !(serve_old || num(v) == %d)) ? 0 : obj(v); }"
                   % (iface, args, int(typed_null), own))
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
            "int SteamAPI_InitFlat(char *m) { return SteamInternal_SteamAPI_Init(\"FLAT\\0\", m); }",
            # The library's newer names the bridge forwards dropped ones to; each records its call.
            "char forwarded[128];",
            "void SteamAPI_ISteamTimeline_ClearTimelineTooltip(void *s, float d) { strcpy(forwarded, \"ClearTimelineTooltip\"); }",
            "void SteamAPI_ISteamTimeline_SetTimelineTooltip(void *s, const char *t, float d) { strcpy(forwarded, t); }",
            "uint64_t SteamAPI_ISteamTimeline_AddInstantaneousTimelineEvent(void *s, const char *t, const char *d,"
            " const char *i, uint32_t p, float o, int c) { strcpy(forwarded, \"instant:\"); strcat(forwarded, t); return 1; }",
            "uint64_t SteamAPI_ISteamTimeline_AddRangeTimelineEvent(void *s, const char *t, const char *d,"
            " const char *i, uint32_t p, float o, float n, int c) { strcpy(forwarded, \"range:\"); strcat(forwarded, t); return 2; }",
            "void SteamAPI_ReleaseCurrentThreadMemory(void) { strcpy(forwarded, \"ReleaseCurrentThreadMemory\"); }",
            # What Valve's library reads at RestartAppIfNecessary: the launch's SteamAppId.
            "char seen_appid[64]; int restart_calls, running_calls;",
            "int SteamAPI_RestartAppIfNecessary(uint32_t a) { restart_calls++; const char *e = getenv(\"SteamAppId\");"
            " strncpy(seen_appid, e ? e : \"(unset)\", sizeof seen_appid - 1); return a == 7; }",
            "int SteamAPI_IsSteamRunning(void) { running_calls++; return 1; }"]
    return "\n".join(out) + "\n"


@unittest.skipUnless(shutil.which("gcc"), "gcc required")
class SteamBridgeTest(unittest.TestCase):
    def build(self, serve_old, init_mode=0, typed_null=False):
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        (d / "double.c").write_text(double_source(serve_old, init_mode, typed_null))
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
        if iface == "Utils":  # GetISteamUtils takes no user: (self, pipe, version)
            f.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_char_p]
            return f(None, 1, version.encode())
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

    def test_a_shared_version_the_client_answers_null_gets_the_librarys_own(self):
        bridge, valve = self.build(serve_old=False, typed_null=True)
        for iface, version in GAME.items():
            with self.subTest(iface=iface):
                self.assertTrue(self.fetch(bridge, iface, version), version)
        self.assertEqual(self.own(valve, "SteamAPI_SteamNetworking_v006"),
                         self.fetch(bridge, "Networking", "SteamNetworking006"))

    def test_utils_reads_its_version_from_the_third_argument(self):
        bridge, valve = self.build(serve_old=True)
        self.assertTrue(self.fetch(bridge, "Utils", "SteamUtils011"))
        self.assertEqual(b"SteamUtils011", (ctypes.c_char * 128).in_dll(valve, "last_version").value)

    def test_dropped_flat_functions_answer_or_forward(self):
        bridge, valve = self.build(serve_old=False)
        fwd = (ctypes.c_char * 128).in_dll(valve, "forwarded")
        deck = bridge.SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck
        deck.restype, deck.argtypes = ctypes.c_bool, [ctypes.c_void_p]
        self.assertFalse(deck(None))
        search = bridge.SteamAPI_ISteamGameSearch_SearchForGameSolo
        search.restype = ctypes.c_int
        self.assertEqual(9, search(None, 1, 4))  # k_EGameSearchErrorCode_Failed_Unknown_Error
        clear = bridge.SteamAPI_ISteamTimeline_ClearTimelineStateDescription
        clear.argtypes = [ctypes.c_void_p, ctypes.c_float]
        clear(None, 0.0)
        self.assertEqual(b"ClearTimelineTooltip", fwd.value)
        tip = bridge.SteamAPI_ISteamTimeline_SetTimelineStateDescription
        tip.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_float]
        tip(None, b"boss", 0.0)
        self.assertEqual(b"boss", fwd.value)
        add = bridge.SteamAPI_ISteamTimeline_AddTimelineEvent
        add.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint32,
                        ctypes.c_float, ctypes.c_float, ctypes.c_int]
        add(None, b"icon", b"won", b"desc", 0, 0.0, 0.0, 0)
        self.assertEqual(b"instant:won", fwd.value)
        add(None, b"icon", b"fight", b"desc", 0, 0.0, 5.0, 0)
        self.assertEqual(b"range:fight", fwd.value)
        bridge.SteamGameServer_ReleaseCurrentThreadMemory()
        self.assertEqual(b"ReleaseCurrentThreadMemory", fwd.value)

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


# Run in a child process: the hold blocks, changes the environment and fires once per process.
HOLD_CHILD = r"""
import ctypes, os, sys
bridge = ctypes.CDLL(sys.argv[1]); valve = ctypes.CDLL(sys.argv[2])
print("restart", bridge.SteamAPI_RestartAppIfNecessary(ctypes.c_uint32(int(sys.argv[3]))), flush=True)
print("appid", (ctypes.c_char * 64).in_dll(valve, "seen_appid").value.decode(), flush=True)
libc = ctypes.CDLL(None); libc.getenv.restype = ctypes.c_char_p  # os.environ is Python's own copy
env = lambda k: (libc.getenv(k.encode()) or b"(unset)").decode()
print("gone", env("GONE"), "hold", env("DROIDDECK_NATIVE_HOLD_DIR"), "game", env("SteamGameId"), flush=True)
print("running", bridge.SteamAPI_IsSteamRunning(), flush=True)
m = ctypes.create_string_buffer(1024)
print("init", bridge.SteamInternal_SteamAPI_Init(b"SteamUser023\0\0", m), flush=True)
"""


@unittest.skipUnless(shutil.which("gcc"), "gcc required")
class PrestartHoldTest(unittest.TestCase):
    """The bridge's prestart hold: the first Steam call waits for <DROIDDECK_NATIVE_HOLD_DIR>/release and applies
    the launch's environment from it before Valve's function runs."""

    def setUp(self):
        self.lib = None

    def child(self, hold_dir, env_extra=None, app=1):
        bridge, valve = self.built
        env = {k: v for k, v in os.environ.items() if k not in ("SteamAppId", "DROIDDECK_NATIVE_HOLD_DIR")}
        env["GONE"] = "here"
        if hold_dir:
            env["DROIDDECK_NATIVE_HOLD_DIR"] = str(hold_dir)
        env.update(env_extra or {})
        return subprocess.Popen(["python3", "-c", HOLD_CHILD, bridge, valve, str(app)], env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    @property
    def built(self):
        if self.lib is None:
            d = Path(tempfile.mkdtemp())
            self.addCleanup(shutil.rmtree, d)
            (d / "double.c").write_text(double_source(False))
            subprocess.run(["gcc", "-shared", "-fPIC", "-o", str(d / "libsteam_api.so"), str(d / "double.c")], check=True)
            subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared", "-fvisibility=hidden",
                            "-o", str(d / "libsteam_api64.so"), str(BRIDGE), "-Wl,-rpath,$ORIGIN", "-Wl,--no-as-needed",
                            str(d / "libsteam_api.so"), "-Wl,--as-needed", "-ldl", "-Wl,-z,defs"], check=True)
            self.lib = (str(d / "libsteam_api64.so"), str(d / "libsteam_api.so"))
        return self.lib

    def wait_for(self, path, proc, seconds=10):
        import time
        end = time.monotonic() + seconds
        while not path.exists():
            self.assertIsNone(proc.poll(), "the child ended before holding")
            self.assertLess(time.monotonic(), end, f"no {path.name} after {seconds} s")
            time.sleep(0.02)

    def test_without_a_hold_folder_nothing_waits(self):
        proc = self.child(None)
        out, err = proc.communicate(timeout=20)
        self.assertEqual(0, proc.returncode, err)
        self.assertIn("appid (unset)", out)
        self.assertNotIn("holding", err)

    def test_the_first_steam_call_waits_for_the_release_and_takes_its_environment(self):
        hold = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, hold)
        proc = self.child(hold, app=7)
        self.wait_for(hold / "held", proc)
        pid, call = (hold / "held").read_text().split()
        self.assertEqual((str(proc.pid), "SteamAPI_RestartAppIfNecessary"), (pid, call))
        import time
        time.sleep(0.3)
        self.assertIsNone(proc.poll(), "the call returned before the release")
        (hold / "release.tmp").write_bytes(b"SteamAppId=2868840\0GONE\0SteamGameId=a=b\0")
        os.rename(hold / "release.tmp", hold / "release")
        out, err = proc.communicate(timeout=20)
        self.assertEqual(0, proc.returncode, err)
        self.assertIn("restart 1", out)  # Valve's answer, passed through
        self.assertIn("appid 2868840", out)  # applied before Valve's function ran
        self.assertIn("gone (unset) hold (unset) game a=b", out)
        self.assertIn("running 1", out)  # later calls don't hold again
        self.assertIn("init 0", out)
        self.assertIn("steam-bridge: released after", err)
        self.assertIn("3 environment changes applied", err)
        self.assertTrue((hold / "released").exists())
        self.assertFalse((hold / "release").exists())

    def test_the_init_holds_when_it_is_the_first_call(self):
        hold = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, hold)
        bridge, valve = self.built
        code = ("import ctypes, sys; b = ctypes.CDLL(sys.argv[1]); ctypes.CDLL(sys.argv[2]); m = ctypes.create_string_buffer(64);"
                " print('init', b.SteamInternal_SteamAPI_Init(b'SteamUser023\\0\\0', m), flush=True)")
        env = dict(os.environ, DROIDDECK_NATIVE_HOLD_DIR=str(hold))
        proc = subprocess.Popen(["python3", "-c", code, bridge, valve], env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True)
        self.wait_for(hold / "held", proc)
        self.assertEqual("SteamAPI_Init", (hold / "held").read_text().split()[1])
        (hold / "release").write_bytes(b"")
        out, err = proc.communicate(timeout=20)
        self.assertIn("init 0", out, err)
        self.assertIn("0 environment changes applied", err)

    def test_no_release_quits_after_the_limit(self):
        hold = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, hold)
        proc = self.child(hold, {"DROIDDECK_NATIVE_HOLD_MAX_S": "1"})
        out, err = proc.communicate(timeout=20)
        self.assertEqual(75, proc.returncode)
        self.assertIn("no release came", err)
        self.assertNotIn("appid", out)


if __name__ == "__main__":
    unittest.main()
