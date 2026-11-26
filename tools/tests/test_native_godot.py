import io
import hashlib
import json
import re
import os
from pathlib import Path
import runpy
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import unittest.mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
NG = runpy.run_path(str(BIN / "droiddeck-native-godot"))


def pe(managed, machine=0xAA64, r2r=False, ilonly=True, fixups=False, signature=True):
    """A minimal PE32+ image with one section; managed ones carry a CLI header in it."""
    opt = bytearray(240)
    struct.pack_into("<H", opt, 0, 0x20B)
    if managed:
        struct.pack_into("<II", opt, 112 + 14 * 8, 0x2008, 0x48)
    if r2r:  # crossgen2 output also has an x64 exception table and base relocations
        struct.pack_into("<II", opt, 112 + 3 * 8, 0x21C0, 0x18)
        struct.pack_into("<II", opt, 112 + 5 * 8, 0x21E0, 0x10)
    coff = struct.pack("<HHIIIHH", machine, 1, 0, 0, 0, len(opt), 0x22)
    head = bytearray(0x80)
    head[:2] = b"MZ"
    struct.pack_into("<I", head, 0x3C, 0x80)
    hdr_len = 0x80 + 24 + len(opt) + 40
    raw = (hdr_len + 0x1FF) & ~0x1FF
    section = b".text\0\0\0" + struct.pack("<IIII", 0x200, 0x2000, 0x200, raw) + b"\0" * 16
    body = bytearray(0x200)
    cor = bytearray(0x48)
    struct.pack_into("<I", cor, 0, 0x48)
    struct.pack_into("<I", cor, 16, (1 if ilonly else 0) | (4 if r2r else 0))
    if r2r:
        struct.pack_into("<II", cor, 64, 0x2100, 0x40)
        if signature:
            body[0x100:0x104] = b"RTR\0"
    if fixups:
        struct.pack_into("<II", cor, 56, 0x2180, 0x8)
    body[8:8 + 0x48] = cor
    image = bytes(head) + b"PE\0\0" + coff + bytes(opt) + section
    return image + b"\0" * (raw - len(image)) + bytes(body)


class NativeGodotTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.game = self.root / "Slay the Spire 2"
        win = self.game / "data_sts2_windows_x86_64"
        win.mkdir(parents=True)
        (self.game / "SlayTheSpire2.exe").write_bytes(pe(False))
        (self.game / "SlayTheSpire2.pck").write_bytes(b"GDPC")
        (win / "sts2.dll").write_bytes(pe(True))
        (win / "0Harmony.dll").write_bytes(pe(True, machine=0x8664, r2r=True))
        (win / "GodotSharp.dll").write_bytes(pe(True))
        (win / "System.Private.CoreLib.dll").write_bytes(pe(True))
        (win / "coreclr.dll").write_bytes(pe(False))
        (win / "steam_api64.dll").write_bytes(pe(False))
        (win / "sts2.runtimeconfig.json").write_text('{"runtimeOptions": {"tfm": "net9.0", "configProperties": {"System.GC.Server": false}}}')
        (win / "sts2.deps.json").write_text("{}")
        self.kit = self.root / "kit"
        self.kit.mkdir()
        for n in ("libhostfxr.so", "libcoreclr.so", "System.Private.CoreLib.dll", "GodotSharp.dll", "Kit.dll"):
            (self.kit / n).write_bytes(b"k")
        rt = ".NETCoreApp,Version=v9.0/linux-arm64"
        (self.kit / "Kit.deps.json").write_text(json.dumps({
            "runtimeTarget": {"name": rt, "signature": ""},
            "targets": {".NETCoreApp,Version=v9.0": {}, rt: {
                "Kit/1.0.0": {"dependencies": {"GodotSharp": "4.5.1"}, "runtime": {"Kit.dll": {}}},
                "GodotSharp/4.5.1": {"runtime": {"GodotSharp.dll": {}}},
                "runtimepack.Microsoft.NETCore.App.Runtime.linux-arm64/9.0.0": {"native": {"libcoreclr.so": {}}}}},
            "libraries": {"Kit/1.0.0": {"type": "project"}, "GodotSharp/4.5.1": {"type": "project"},
                          "runtimepack.Microsoft.NETCore.App.Runtime.linux-arm64/9.0.0": {"type": "runtimepack"}}}))
        (self.kit / "Kit.runtimeconfig.json").write_text(json.dumps(
            {"runtimeOptions": {"tfm": "net9.0", "includedFrameworks": [{"name": "Microsoft.NETCore.App"}],
                                "configProperties": {"System.GC.Concurrent": True}}}))
        (self.kit / "kit.json").write_text(json.dumps({"app": ["Kit.dll", "Kit.deps.json", "Kit.runtimeconfig.json"],
                                                       "deps": "Kit.deps.json", "runtimeconfig": "Kit.runtimeconfig.json"}))
        self.engine = self.root / "godot.template"
        self.engine.write_bytes(b"\x7fELF engine")

    def test_managed_detection(self):
        p = self.root / "x.dll"
        p.write_bytes(pe(True))
        self.assertTrue(NG["is_managed"](str(p)))
        p.write_bytes(pe(False))
        self.assertFalse(NG["is_managed"](str(p)))
        p.write_bytes(b"not a pe")
        self.assertFalse(NG["is_managed"](str(p)))

    def test_x64_assemblies_are_relabelled(self):
        img, note = NG["portable_image"](pe(True, machine=0x8664))
        info = NG["pe_info"](img)
        self.assertEqual((0xAA64, 1, (0, 0)), (info["machine"], info["flags"], info["native_header"]))
        self.assertEqual("relabelled x64 -> arm64", note)
        self.assertEqual((None, None), NG["portable_image"](pe(True, machine=0x14C)))
        with self.assertRaises(ValueError):
            NG["portable_image"](pe(True, machine=0x8664, ilonly=False))
        with self.assertRaises(NG["MixedMode"]):
            NG["portable_image"](pe(True, machine=0x8664, ilonly=False, fixups=True))
        with self.assertRaises(NG["MixedMode"]):  # a native header that isn't ReadyToRun
            NG["portable_image"](pe(True, machine=0x8664, ilonly=False, r2r=True, signature=False))
        with self.assertRaises(NG["MixedMode"]):  # ReadyToRun beside C++/CLI fixups
            NG["portable_image"](pe(True, machine=0x8664, ilonly=False, r2r=True, fixups=True))

    def test_ready_to_run_images_become_plain_il(self):
        # .NET on Linux refuses a Windows R2R image on Godot's load path, relabelled or not; with the R2R header
        # cleared, IL-only set, IL_LIBRARY cleared and the exception and relocation directories emptied it is the
        # plain IL image it was compiled from (measured: default context and hostfxr component load).
        for ilonly in (False, True):
            src = pe(True, machine=0x8664, ilonly=ilonly, r2r=True)
            img, note = NG["portable_image"](src)
            after = NG["pe_info"](img)
            self.assertEqual((0xAA64, 0x1, (0, 0), False), (after["machine"], after["flags"], after["native_header"],
                                                           after["ready_to_run"]))
            dirs = after["directories_at"]
            for entry in (3, 5):
                self.assertEqual((0, 0), struct.unpack_from("<II", img, dirs + 8 * entry), entry)
            self.assertEqual((0x2008, 0x48), struct.unpack_from("<II", img, dirs + 8 * 14))  # CLI header kept
            self.assertEqual(len(src), len(img))
            self.assertEqual(src[0x200 + 0x100:], img[0x200 + 0x100:])  # IL, metadata and the rest untouched
            self.assertIn("ReadyToRun x64 code dropped", note)
            self.assertEqual((None, None), NG["portable_image"](img))  # already portable

    def test_compose_leaves_out_mixed_mode_assemblies(self):
        win = self.game / "data_sts2_windows_x86_64"
        (win / "Vortice.Native.dll").write_bytes(pe(True, machine=0x8664, ilonly=False, fixups=True))
        (win / "sts2.dll").write_bytes(pe(True) + b"\0Vortice.Native\0")
        (win / "Odd.dll").write_bytes(pe(True, machine=0x8664, ilonly=False, r2r=True))
        out = self.root / "launch"
        out.mkdir()
        err = io.StringIO()
        with unittest.mock.patch("sys.stderr", err):
            r = NG["compose"](str(self.game), str(self.kit), str(self.engine), str(out), "SlayTheSpire2.exe")
        data = out / "data_sts2_linuxbsd_arm64"
        self.assertFalse((data / "Vortice.Native.dll").exists())
        self.assertIn("Vortice.Native.dll", r["skipped"])
        self.assertEqual({"Vortice.Native.dll": ["sts2.dll"]}, r["mixed_mode"])
        self.assertIn("left out Vortice.Native.dll", err.getvalue())
        self.assertIn("named by sts2.dll", err.getvalue())
        self.assertIn("Odd.dll", r["game_assemblies"])
        self.assertIn("ReadyToRun x64 code dropped", r["patched"]["Odd.dll"])
        deps = json.loads((data / "sts2.deps.json").read_text())
        self.assertNotIn("Vortice.Native.dll", json.dumps(deps))

    def test_compose_stops_when_the_game_itself_is_mixed_mode(self):
        (self.game / "data_sts2_windows_x86_64" / "sts2.dll").write_bytes(
            pe(True, machine=0x8664, ilonly=False, fixups=True))
        out = self.root / "launch"
        out.mkdir()
        with self.assertRaises(SystemExit) as stop:
            NG["compose"](str(self.game), str(self.kit), str(self.engine), str(out), "SlayTheSpire2.exe")
        self.assertIn("sts2.dll", str(stop.exception))

    def test_steam_x86_overlay_preload_is_dropped(self):
        env = NG["engine_env"]({"LD_PRELOAD": "/root/.steam/bin32/gameoverlayrenderer.so:/root/.steam/bin64/gameoverlayrenderer.so"})
        self.assertNotIn("LD_PRELOAD", env)
        env = NG["engine_env"]({"LD_PRELOAD": "/a/libkeep.so /root/.steam/bin32/gameoverlayrenderer.so"})
        self.assertEqual("/a/libkeep.so", env["LD_PRELOAD"])

    def test_compose(self):
        out = self.root / "launch"
        out.mkdir()
        r = NG["compose"](str(self.game), str(self.kit), str(self.engine), str(out), "SlayTheSpire2.exe")
        data = out / "data_sts2_linuxbsd_arm64"
        self.assertEqual("sts2", r["assembly"])
        self.assertTrue(r["pack"].endswith("SlayTheSpire2.pck"))
        names = sorted(os.listdir(data))
        self.assertEqual(sorted(["0Harmony.dll", "GodotSharp.dll", "System.Private.CoreLib.dll", "libcoreclr.so",
                                 "libhostfxr.so", "sts2.dll", "sts2.deps.json", "sts2.runtimeconfig.json"]), names)
        deps = json.loads((data / "sts2.deps.json").read_text())
        rt = deps["targets"][deps["runtimeTarget"]["name"]]
        self.assertEqual({"0Harmony.dll": {}, "sts2.dll": {}}, rt["sts2/1.0.0"]["runtime"])
        self.assertNotIn("Kit/1.0.0", rt)
        self.assertIn("runtimepack.Microsoft.NETCore.App.Runtime.linux-arm64/9.0.0", rt)
        self.assertEqual("project", deps["libraries"]["sts2/1.0.0"]["type"])
        cfg = json.loads((data / "sts2.runtimeconfig.json").read_text())["runtimeOptions"]
        self.assertEqual({"System.GC.Concurrent": True, "System.GC.Server": False}, cfg["configProperties"])
        self.assertIn("includedFrameworks", cfg)
        # Engine assemblies and the runtime come from the kit; the game's IL is linked, not copied.
        self.assertTrue(os.path.samefile(self.kit / "GodotSharp.dll", data / "GodotSharp.dll"))
        self.assertTrue(os.path.samefile(self.kit / "System.Private.CoreLib.dll", data / "System.Private.CoreLib.dll"))
        self.assertTrue(os.path.samefile(self.game / "data_sts2_windows_x86_64" / "sts2.dll", data / "sts2.dll"))
        self.assertEqual(["0Harmony.dll"], list(r["patched"]))
        self.assertEqual(0xAA64, NG["pe_info"]((data / "0Harmony.dll").read_bytes())["machine"])
        self.assertEqual(0x8664, NG["pe_info"]((self.game / "data_sts2_windows_x86_64" / "0Harmony.dll").read_bytes())["machine"])
        self.assertFalse(any((data / n).is_symlink() for n in names))
        self.assertIn("steam_api64.dll", r["skipped"])
        self.assertTrue((out / "sts2").is_file())
        # Composing again is idempotent.
        NG["compose"](str(self.game), str(self.kit), str(self.engine), str(out), "SlayTheSpire2.exe")
        self.assertEqual(names, sorted(os.listdir(data)))

    def test_run_passes_engine_args(self):
        out = self.root / "launch"
        seen = []
        real = NG["main"].__globals__["watch"]
        NG["main"].__globals__["watch"] = lambda args, env, **kw: seen.append(args) or 7
        try:
            rc = NG["main"](["run", "--game-dir", str(self.game), "--kit", str(self.kit), "--engine", str(self.engine),
                             "--out", str(out), "--exe", "SlayTheSpire2.exe", "--", "--headless", "--verbose"])
        finally:
            NG["main"].__globals__["watch"] = real
            os.chdir(Path(__file__).resolve().parent)
        self.assertEqual(7, rc)
        if seen[0][1:3] == ["-oL", "-eL"]:  # line_buffered: stdbuf in front when the host has it
            seen[0] = seen[0][3:]
        self.assertEqual([str(out / "sts2"), "--main-pack", str(self.game / "SlayTheSpire2.pck"), "--print-fps",
                          "--headless", "--verbose"], seen[0])

    def fake_engine(self, body):
        """A stand-in engine: a Python script, run with this interpreter."""
        path = self.root / "fake_engine.py"
        path.write_text("import sys, time\n" + body)
        return [sys.executable, str(path)]

    def run_watch(self, body, first_frame_s=5):
        out = io.StringIO()
        began = time.monotonic()
        rc = NG["watch"](self.fake_engine(body), dict(os.environ), first_frame_s=first_frame_s, out=out)
        return rc, out.getvalue(), time.monotonic() - began

    def test_line_buffered_puts_stdbuf_in_front_when_present(self):
        self.assertEqual(["/usr/bin/stdbuf", "-oL", "-eL", "/e", "-x"],
                         NG["line_buffered"](["/e", "-x"], which=lambda n: "/usr/bin/" + n))
        self.assertEqual(["/e", "-x"], NG["line_buffered"](["/e", "-x"], which=lambda n: None))

    @unittest.skipUnless(shutil.which("cc") and shutil.which("stdbuf"), "needs a C compiler and coreutils stdbuf")
    def test_a_stdio_engine_first_frame_is_seen_while_it_runs(self):
        # Like a release Godot: printf through a pipe is block-buffered, so without stdbuf the FPS line would
        # arrive only at exit, 3 s later.
        src = self.root / "engine.c"
        src.write_text('#include <stdio.h>\n#include <unistd.h>\nint main(void){printf("Project FPS: 30 (33.33 mspf)\\n");'
                       'sleep(3);return 0;}\n')
        exe = self.root / "engine"
        subprocess.run(["cc", "-o", str(exe), str(src)], check=True)
        err = io.StringIO()
        with unittest.mock.patch("sys.stderr", err):
            rc = NG["watch"](NG["line_buffered"]([str(exe)]), dict(os.environ), first_frame_s=10, out=io.StringIO())
        self.assertEqual(0, rc)
        took = float(re.search(r"first frame after ([0-9.]+) s", err.getvalue()).group(1))
        self.assertLess(took, 2.0)

    def test_watch_passes_the_exit_code_after_a_first_frame(self):
        rc, out, _ = self.run_watch("print('booting')\nprint('Project FPS: 60 (16.67 mspf)', flush=True)\n"
                                    "print('Project FPS: 59 (16.95 mspf)')\nprint('bye')\nsys.exit(3)")
        self.assertEqual(3, rc)
        self.assertEqual(["booting", "Project FPS: 60 (16.67 mspf)", "bye"], out.splitlines())  # second FPS line thinned

    def test_watch_stops_a_game_whose_steam_init_failed_so_it_starts_on_proton(self):
        # Slay the Spire 2 natively: Steam's init failed, then its error box would wait for the player.
        seen = []
        out = io.StringIO()
        err = io.StringIO()
        began = time.monotonic()
        with unittest.mock.patch("sys.stderr", err):
            rc = NG["watch"](self.fake_engine(
                "print('[INFO] Steamworks: attempting initialization...', flush=True)\n"
                "print('[ERROR] Steamworks initialization failed! Result: k_ESteamAPIInitResult_FailedGeneric, message: ', flush=True)\n"
                "print('Project FPS: 60 (16.67 mspf)', flush=True)\ntime.sleep(60)"),
                dict(os.environ), first_frame_s=30, out=out, on_steam_failed=seen.append)
        self.assertEqual(NG["DID_NOT_START"], rc)
        self.assertLess(time.monotonic() - began, 20)
        self.assertEqual(1, len(seen))
        self.assertIn("FailedGeneric", seen[0])
        self.assertIn(NG["NATIVE_STEAM_INIT"], err.getvalue())

    def test_watch_stops_a_game_that_failed_at_startup_natively_so_it_starts_on_proton(self):
        # Nov 15, native-engine-73: Steam initialised, then Slay the Spire 2 called a flat function the library
        # lacked, logged its startup error and showed a box. The Sentry plugin's own EntryPointNotFoundException
        # (in 'sentry-godot', earlier in the same log) is harmless and must not stop it.
        seen = []
        err = io.StringIO()
        with unittest.mock.patch("sys.stderr", err):
            rc = NG["watch"](self.fake_engine(
                "print(\"System.EntryPointNotFoundException: Unable to find an entry point named "
                "'csharp_interop_is_main_thread' in shared library 'sentry-godot'.\", flush=True)\n"
                "print('Project FPS: 60 (16.67 mspf)', flush=True)\n"
                "print(\"System.EntryPointNotFoundException: Unable to find an entry point named "
                "'SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck' in shared library 'steam_api64'.\", flush=True)\n"
                "print('[ERROR] Encountered error on game startup!', flush=True)\ntime.sleep(60)"),
                dict(os.environ), first_frame_s=30, out=io.StringIO(),
                on_steam_failed=lambda line, kind="steam": seen.append((kind, line)))
        self.assertEqual(NG["DID_NOT_START"], rc)
        self.assertEqual(1, len(seen))
        self.assertEqual("game", seen[0][0])
        self.assertIn("IsSteamRunningOnSteamDeck", seen[0][1])
        self.assertIn(NG["NATIVE_GAME_FAILED"], err.getvalue())
        # The Sentry line alone runs on.
        rc, _, _ = self.run_watch(
            "print(\"System.EntryPointNotFoundException: Unable to find an entry point named "
            "'csharp_interop_is_main_thread' in shared library 'sentry-godot'.\", flush=True)\n"
            "print('Project FPS: 60 (16.67 mspf)', flush=True)\nsys.exit(0)")
        self.assertEqual(0, rc)

    def test_a_remembered_startup_failure_says_which_kind(self):
        path = str(self.root / "refused" / "x.json")
        NG["remember_steam_refusal"](path, "Encountered error on game startup!", kind="game")
        self.assertEqual("game", json.loads(Path(path).read_text())["kind"])

    def test_steam_names_next_to_each_other_are_all_found(self):
        # The scan once consumed the NUL between two names and skipped every second one (664 of 949 found), so a
        # missing name could hide behind its neighbour and reach the phone.
        dll = self.root / "sw.dll"
        dll.write_bytes(b"\0SteamAPI_Init\0SteamAPI_A\0SteamAPI_B\0SteamClient\0SteamAPI_CheckCallbackRegistered_t\0")
        lib = self.root / "lib.so"
        lib.write_bytes(b"\0SteamAPI_Init\0")
        self.assertEqual(["SteamAPI_A", "SteamAPI_B", "SteamClient"], NG["steam_api_missing"](str(dll), str(lib)))

    def test_a_failed_native_steam_init_is_remembered_per_engine_client_and_game(self):
        client = self.root / "steamclient.so"
        client.write_bytes(b"client")
        win = self.game / "data_sts2_windows_x86_64"
        win.mkdir(parents=True, exist_ok=True)
        (win / "sts2.dll").write_bytes(b"game")
        cache = str(self.root / "refused")
        path = NG["steam_refusal_path"]("native-engine-67", str(self.game), clients=[str(client)], cache_dir=cache)
        NG["remember_steam_refusal"](path, "Steamworks initialization failed!")
        self.assertTrue(os.path.exists(path))
        self.assertEqual(path, NG["steam_refusal_path"]("native-engine-67", str(self.game), clients=[str(client)],
                                                        cache_dir=cache))
        self.assertNotEqual(path, NG["steam_refusal_path"]("native-engine-68", str(self.game), clients=[str(client)],
                                                           cache_dir=cache))
        client.write_bytes(b"client updated")
        self.assertNotEqual(path, NG["steam_refusal_path"]("native-engine-67", str(self.game), clients=[str(client)],
                                                           cache_dir=cache))

    def test_steam_versions_are_every_client_interface_the_steamworks_net_names(self):
        dll = self.root / "Steamworks.NET.dll"
        heap = b""
        for v in ("SteamUtils010", "SteamClient021", "STEAMHTMLSURFACE_INTERFACE_VERSION_005", "SteamGameServer015",
                  "STEAMTIMELINE_INTERFACE_V001", "SteamUser023", "STEAMAPPTICKET_INTERFACE_VERSION001",
                  "SteamUtils010", "[Steamworks.NET] Failed"):
            heap += bytes([2 * len(v) + 1]) + v.encode("utf-16-le") + b"\0"
        dll.write_bytes(b"MZ\0" + heap)  # strings at even offsets, as utf-16 reads them
        self.assertEqual(["SteamUtils010", "STEAMHTMLSURFACE_INTERFACE_VERSION_005", "STEAMTIMELINE_INTERFACE_V001",
                          "SteamUser023"], NG["steam_versions"](str(dll)))

    def test_watch_clean_exit_after_a_first_frame(self):
        rc, _, _ = self.run_watch("print('Project FPS: 60 (16.67 mspf)')")
        self.assertEqual(0, rc)

    def test_watch_exit_before_a_first_frame_did_not_start(self):
        for code in (0, 1):
            rc, out, _ = self.run_watch(f"print('loading')\nsys.exit({code})")
            self.assertEqual(NG["DID_NOT_START"], rc)
            self.assertIn("loading", out)

    def test_watch_kills_a_hang(self):
        rc, out, took = self.run_watch("print('loading', flush=True)\ntime.sleep(60)", first_frame_s=1)
        self.assertEqual(NG["DID_NOT_START"], rc)
        self.assertLess(took, 20)

    def test_watch_kills_the_engine_s_children_on_a_hang(self):
        # The engine's own child (a shader compiler, say) holding the pipe open must not keep us waiting.
        rc, _, took = self.run_watch("import subprocess\nsubprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
                                     "time.sleep(60)", first_frame_s=1)
        self.assertEqual(NG["DID_NOT_START"], rc)
        self.assertLess(took, 20)

    def test_watch_a_crash_after_the_first_frame_passes_through(self):
        rc, _, _ = self.run_watch("import os, signal\nprint('Project FPS: 60 (16.67 mspf)', flush=True)\n"
                                  "os.kill(os.getpid(), signal.SIGSEGV)")
        self.assertEqual(128 + 11, rc)

    def test_wrapper_death_takes_the_engine_with_it(self):
        # Run watch() in a child wrapper, SIGKILL that wrapper, and check the engine is gone too.
        pidfile = self.root / "engine.pid"
        engine = self.fake_engine(f"import os\nopen({str(pidfile)!r}, 'w').write(str(os.getpid()))\n"
                                  "print('Project FPS: 60 (16.67 mspf)', flush=True)\ntime.sleep(60)")
        wrapper = subprocess.Popen([sys.executable, "-c", "import runpy, sys; NG = runpy.run_path(sys.argv[1]); "
                                    "sys.exit(NG['watch'](sys.argv[2:], None, forward_signals=True))",
                                    str(BIN / "droiddeck-native-godot")] + engine,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            if pidfile.exists() and pidfile.read_text():
                break
            time.sleep(0.1)
        pid = int(pidfile.read_text())
        wrapper.kill()
        wrapper.wait()
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.1)
        else:
            os.kill(pid, 9)
            self.fail("engine outlived its wrapper")

    def test_relative_paths_become_absolute(self):
        old = os.getcwd()
        os.chdir(self.root)
        try:
            r = NG["compose"]("Slay the Spire 2", "kit", "godot.template", "launch", "SlayTheSpire2.exe")
        finally:
            os.chdir(old)
        self.assertTrue(os.path.isabs(r["pack"]) and os.path.isabs(r["engine"]))

    def test_component_with_a_steam_stub_is_refused(self):
        # Components up to native-engine-38 carry stubs/libsteam_api64.so (a no-op Steam stand-in). They never run.
        comp = self.root / "component"
        (comp / "stubs").mkdir(parents=True)
        (comp / "stubs" / "libsteam_api64.so").write_bytes(b"stub")
        os.rename(self.kit, comp / "kit")
        os.rename(self.engine, comp / "godot")
        valve = self.root / "libsteam_api.so"
        valve.write_bytes(b"\x7fELF\0SteamAPI_Init\0")
        out = self.root / "launch"
        seen = []
        real = NG["main"].__globals__["watch"]
        NG["main"].__globals__["watch"] = lambda cmd, env, **kw: seen.append(cmd) or 0
        err = io.StringIO()
        try:
            with unittest.mock.patch("sys.stderr", err):
                for command in ("compose", "run"):
                    rc = NG["main"]([command, "--game-dir", str(self.game), "--component", str(comp), "--out", str(out),
                                     "--exe", "SlayTheSpire2.exe", "--steam-api", str(valve)])
                    self.assertEqual(NG["DID_NOT_START"], rc, command)
        finally:
            NG["main"].__globals__["watch"] = real
            os.chdir(Path(__file__).resolve().parent)
        self.assertEqual([], seen)
        self.assertFalse(out.exists())
        self.assertIn("carries a Steam stand-in (stubs/)", err.getvalue())

    def test_valve_library_is_placed_and_missing_names_are_reported(self):
        comp = self.root / "component"
        comp.mkdir()
        os.rename(self.kit, comp / "kit")
        os.rename(self.engine, comp / "godot")
        win = self.game / "data_sts2_windows_x86_64"
        (win / "Steamworks.NET.dll").write_bytes(pe(True) + b"\0SteamAPI_Init\0SteamAPI_ISteamUser_GetSteamID\0SteamAPI_Gone\0")
        out = self.root / "launch"
        self.assertEqual(0, NG["main"](["compose", "--game-dir", str(self.game), "--component", str(comp), "--out", str(out),
                                        "--exe", "SlayTheSpire2.exe"]))
        self.assertFalse((out / "data_sts2_linuxbsd_arm64" / "libsteam_api64.so").exists())  # none given, none placed
        valve = self.root / "libsteam_api.so"
        valve.write_bytes(b"\x7fELF\0SteamAPI_Init\0SteamAPI_ISteamUser_GetSteamID\0")
        err = io.StringIO()
        with unittest.mock.patch("sys.stderr", err):
            self.assertEqual(0, NG["main"](["compose", "--game-dir", str(self.game), "--component", str(comp), "--out", str(out),
                                            "--exe", "SlayTheSpire2.exe", "--steam-api", str(valve)]))
        data = out / "data_sts2_linuxbsd_arm64"
        for name in ("libsteam_api64.so", "libsteam_api.so"):
            self.assertEqual(valve.read_bytes(), (data / name).read_bytes())
        report = json.loads((out / "native-launch.json").read_text())
        self.assertEqual(["SteamAPI_Gone"], report["steam_api_missing"])
        self.assertIn("1 names the game's Steamworks.NET mentions are not exported (SteamAPI_Gone)", err.getvalue())

    def test_run_does_not_start_when_steam_init_would_miss_an_interface_accessor(self):
        # The phone run on native-engine-54: the game's Steamworks.NET calls GetISteamGameSearch in SteamAPI.Init,
        # Valve's linux-arm64 library (SDK 1.64+) has no such export, so the game quit. That is known before launch.
        comp = self.root / "component"
        comp.mkdir()
        os.rename(self.kit, comp / "kit")
        os.rename(self.engine, comp / "godot")
        win = self.game / "data_sts2_windows_x86_64"
        valve = self.root / "libsteam_api.so"
        valve.write_bytes(b"\x7fELF\0SteamAPI_Init\0SteamAPI_ISteamClient_GetISteamUser\0")
        out = self.root / "launch"
        seen = []
        real = NG["main"].__globals__["watch"]
        NG["main"].__globals__["watch"] = lambda cmd, env, **kw: seen.append(cmd) or 0
        try:
            for extra, expect_start in ((b"SteamAPI_ISteamFriends_GetUserRestrictions\0", True),
                                        (b"SteamAPI_ISteamClient_GetISteamGameSearch\0", False)):
                (win / "Steamworks.NET.dll").write_bytes(
                    pe(True) + b"\0SteamAPI_Init\0SteamAPI_ISteamClient_GetISteamUser\0" + extra)
                err = io.StringIO()
                with unittest.mock.patch("sys.stderr", err):
                    rc = NG["main"](["run", "--game-dir", str(self.game), "--component", str(comp), "--out", str(out),
                                     "--exe", "SlayTheSpire2.exe", "--steam-api", str(valve)])
                self.assertEqual(0 if expect_start else NG["DID_NOT_START"], rc, extra)
                self.assertEqual(not expect_start, "lacks SteamAPI_ISteamClient_GetISteamGameSearch" in err.getvalue())
                self.assertEqual(not expect_start, "Steam probe: " in err.getvalue())  # asked only when it matters
            # A program that never calls SteamAPI.Init (the gate's stand-in) can turn the preflight off.
            rc = NG["main"](["run", "--no-steam-preflight", "--game-dir", str(self.game), "--component", str(comp),
                             "--out", str(out), "--exe", "SlayTheSpire2.exe", "--steam-api", str(valve)])
            self.assertEqual(0, rc)
        finally:
            NG["main"].__globals__["watch"] = real
            os.chdir(Path(__file__).resolve().parent)
        self.assertEqual(2, len(seen))  # the run whose missing name is not an Init accessor, and the opted-out one

    def test_bridge_sits_in_front_of_valve_library_and_the_probe_decides(self):
        # With the component's steam-bridge/libsteam_api64.so, Valve's library is placed as libsteam_api.so and the
        # bridge (which needs it) as libsteam_api64.so, the name the game loads. The bridge's two accessors count
        # as exported; the game starts unless the real client says it does not serve the versions asked for.
        comp = self.root / "component"
        comp.mkdir()
        os.rename(self.kit, comp / "kit")
        os.rename(self.engine, comp / "godot")
        (comp / "steam-bridge").mkdir()
        (comp / "steam-bridge" / "libsteam_api64.so").write_bytes(
            b"\x7fELF bridge\0SteamAPI_ISteamClient_GetISteamGameSearch\0SteamAPI_ISteamClient_GetISteamMusicRemote\0")
        win = self.game / "data_sts2_windows_x86_64"
        (win / "Steamworks.NET.dll").write_bytes(
            pe(True) + b"\0SteamAPI_Init\0\0SteamAPI_ISteamClient_GetISteamGameSearch\0"
            b"\0SteamAPI_ISteamClient_GetISteamMusicRemote\0" + b"\x01\x2f"  # a #US heap entry's terminal and length bytes
            + "SteamMatchGameSearch001\0SteamInput006".encode("utf-16-le"))
        valve = self.root / "libsteam_api.so"
        valve.write_bytes(b"\x7fELF valve\0SteamAPI_Init\0SteamAPI_ISteamClient_GetISteamGenericInterface\0")
        out = self.root / "launch"
        seen, asked = [], []
        g = NG["main"].__globals__
        real_watch, real_probe = g["watch"], g["steam_probe"]
        g["watch"] = lambda cmd, env, **kw: seen.append(cmd) or 0
        args = ["run", "--game-dir", str(self.game), "--component", str(comp), "--out", str(out),
                "--exe", "SlayTheSpire2.exe", "--steam-api", str(valve)]
        try:
            # Unserved versions no longer stop it: the bridge hands the game the library's own (Nov 15 fix).
            for served, expect in ((True, 0), (False, 0), (None, 0)):
                def probe(library, cwd, env, versions=(), **kw):
                    asked.append(list(versions))
                    if served is None:
                        return {"unknown": "no arm64 steamclient.so found"}
                    return {"init": 0, "client": True, "versions": {v: served for v in versions}, "wall_seconds": 0.1}
                g["steam_probe"] = probe
                err = io.StringIO()
                with unittest.mock.patch("sys.stderr", err):
                    rc = NG["main"](args)
                self.assertEqual(expect, rc, err.getvalue())
                data = out / "data_sts2_linuxbsd_arm64"
                self.assertIn(b"bridge", (data / "libsteam_api64.so").read_bytes())
                self.assertIn(b"valve", (data / "libsteam_api.so").read_bytes())
                self.assertIn("Steam bridge: forwarding GameSearch, MusicRemote to the real client", err.getvalue())
                if served is False:
                    self.assertIn("does not serve SteamMatchGameSearch001, SteamInput006; the bridge hands the game "
                                  "the library's own", err.getvalue())
            self.assertEqual([["SteamMatchGameSearch001", "SteamInput006"]] * 3, asked)
            with unittest.mock.patch("sys.stderr", io.StringIO()):
                rc = NG["main"](args[:1] + ["--no-steam-bridge"] + args[1:])
            self.assertEqual(NG["DID_NOT_START"], rc)  # Valve's library alone: the accessors are missing
            self.assertIn(b"valve", (out / "data_sts2_linuxbsd_arm64" / "libsteam_api64.so").read_bytes())
        finally:
            g["watch"], g["steam_probe"] = real_watch, real_probe
            os.chdir(Path(__file__).resolve().parent)
        self.assertEqual(3, len(seen))

    def test_with_the_bridge_a_name_neither_library_has_sends_the_game_to_proton(self):
        # .NET binds each P/Invoke when it is first called, so any name left unresolved is a crash mid-game.
        comp = self.root / "component"
        comp.mkdir()
        os.rename(self.kit, comp / "kit")
        os.rename(self.engine, comp / "godot")
        (comp / "steam-bridge").mkdir()
        (comp / "steam-bridge" / "libsteam_api64.so").write_bytes(
            b"\x7fELF bridge\0SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck\0")
        win = self.game / "data_sts2_windows_x86_64"
        valve = self.root / "libsteam_api.so"
        valve.write_bytes(b"\x7fELF valve\0SteamAPI_Init\0")
        out = self.root / "launch"
        g = NG["main"].__globals__
        real_watch, real_probe = g["watch"], g["steam_probe"]
        g["watch"] = lambda cmd, env, **kw: 0
        g["steam_probe"] = lambda *a, **kw: {"unknown": "test"}
        try:
            for tail, expect in ((b"", 0), (b"SteamAPI_ISteamNew_Thing\0", NG["DID_NOT_START"])):
                (win / "Steamworks.NET.dll").write_bytes(
                    pe(True) + b"\0SteamAPI_Init\0SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck\0" + tail)
                err = io.StringIO()
                with unittest.mock.patch("sys.stderr", err):
                    rc = NG["main"](["run", "--game-dir", str(self.game), "--component", str(comp), "--out", str(out),
                                     "--exe", "SlayTheSpire2.exe", "--steam-api", str(valve)])
                self.assertEqual(expect, rc, err.getvalue())
                if expect:
                    self.assertIn(NG["NATIVE_STEAM_INIT"].split(" did")[0] + " cannot initialise every call", err.getvalue())
                    self.assertIn("SteamAPI_ISteamNew_Thing", err.getvalue())
        finally:
            g["watch"], g["steam_probe"] = real_watch, real_probe
            os.chdir(Path(__file__).resolve().parent)

    def test_steam_probe_asks_once_per_steamclient_and_treats_errors_as_unknown(self):
        import subprocess
        home = self.root / "home"
        arm = home / ".local" / "share" / "Steam" / "linuxarm64"
        arm.mkdir(parents=True)
        x64 = home / ".steam" / "sdkarm64"
        x64.mkdir(parents=True)
        (x64 / "steamclient.so").write_bytes(b"\x7fELF" + b"\0" * 14 + struct.pack("<H", 62))  # x86-64: not ours
        self.assertEqual([], NG["arm64_steamclients"](str(home), {}))
        client = arm / "steamclient.so"
        client.write_bytes(b"\x7fELF" + b"\0" * 14 + struct.pack("<H", 183) + b"one")
        self.assertEqual([str(client)], NG["arm64_steamclients"](str(home), {}))
        calls = []

        def runner(cmd, **kw):
            calls.append((cmd, kw))
            self.assertNotIn("LD_PRELOAD", kw["env"])
            self.assertEqual(list(NG["STEAM_PROBE_VERSIONS"]), cmd[5:])
            out = {"init": 0, "client": True, "seconds": 0.2,
                   "versions": {v: v != "SteamInput006" for v in cmd[5:]}}
            return subprocess.CompletedProcess(cmd, 0, ("noise\nSTEAMPROBE " + json.dumps(out) + "\n").encode())
        env = {"LD_PRELOAD": "/root/.steam/bin32/gameoverlayrenderer.so", "SteamAppId": "1"}
        cache = str(self.root / "cache")
        first = NG["steam_probe"]("/lib/libsteam_api.so", "/game", env, cache, [str(client)], runner=runner)
        self.assertTrue(first["versions"]["SteamMatchGameSearch001"])
        self.assertFalse(first["versions"]["SteamInput006"])
        line = NG["steam_probe_line"](first)
        self.assertIn("3 of 4 interface versions non-null (null: SteamInput006)", line)
        again = NG["steam_probe"]("/lib/libsteam_api.so", "/game", env, cache, [str(client)], runner=runner)
        self.assertTrue(again["cached"])
        self.assertEqual(1, len(calls))
        client.write_bytes(client.read_bytes() + b"updated")  # a new steamclient is asked again

        def hangs(cmd, **kw):
            raise subprocess.TimeoutExpired(cmd, kw["timeout"])
        self.assertIn("timed out", NG["steam_probe"]("/l", "/g", env, cache, [str(client)], runner=hangs)["unknown"])
        silent = lambda cmd, **kw: subprocess.CompletedProcess(cmd, -11, b"")
        self.assertIn("without an answer", NG["steam_probe"]("/l", "/g", env, cache, [str(client)], runner=silent)["unknown"])
        refused = lambda cmd, **kw: subprocess.CompletedProcess(cmd, 0, b'STEAMPROBE {"init": 1, "init_message": "x", "versions": {}}\n')
        result = NG["steam_probe"]("/l", "/g", env, cache, [str(client)], runner=refused)
        self.assertIn("returned 1", NG["steam_probe_line"](result))
        self.assertEqual(1, len(os.listdir(Path(cache) / "steam-probe")))  # failures are not cached
        self.assertEqual("Steam probe: unknown (no arm64 steamclient.so found)",
                         NG["steam_probe_line"](NG["steam_probe"]("/l", "/g", env, cache, [])))

    def test_run_puts_no_stub_on_any_path_and_valve_library_wins(self):
        # The engine's env and command name no stubs/ path, Steam's x86 overlay preload is dropped, and the engine's
        # data folder holds Valve's library under both names (a component with stubs/ is refused, tested above).
        comp = self.root / "component"
        comp.mkdir()
        os.rename(self.kit, comp / "kit")
        os.rename(self.engine, comp / "godot")
        valve = self.root / "libsteam_api.so"
        valve.write_bytes(b"\x7fELF\0SteamAPI_Init\0")
        out = self.root / "launch"
        seen = []
        real = NG["main"].__globals__["watch"]
        NG["main"].__globals__["watch"] = lambda cmd, env, **kw: seen.append((list(cmd), dict(env))) or 0
        before = {k: os.environ.get(k) for k in ("LD_LIBRARY_PATH", "LD_PRELOAD")}
        os.environ["LD_LIBRARY_PATH"] = "/usr/lib/aarch64-linux-gnu"
        os.environ["LD_PRELOAD"] = "/root/.steam/bin32/gameoverlayrenderer.so"
        try:
            with unittest.mock.patch("sys.stderr", io.StringIO()):
                rc = NG["main"](["run", "--game-dir", str(self.game), "--component", str(comp), "--out", str(out),
                                 "--exe", "SlayTheSpire2.exe", "--steam-api", str(valve)])
        finally:
            NG["main"].__globals__["watch"] = real
            for k, v in before.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
            os.chdir(Path(__file__).resolve().parent)
        self.assertEqual(0, rc)
        cmd, env = seen[0]
        for key in ("LD_LIBRARY_PATH", "LD_PRELOAD", "DOTNET_ROOT", "PATH"):
            self.assertNotIn("stubs", env.get(key, ""), key)
        self.assertFalse(any("stubs" in a for a in cmd), cmd)
        self.assertNotIn("gameoverlayrenderer", env.get("LD_PRELOAD", ""))
        data = out / "data_sts2_linuxbsd_arm64"
        for name in ("libsteam_api64.so", "libsteam_api.so"):
            self.assertEqual(valve.read_bytes(), (data / name).read_bytes(), name)
            self.assertFalse((data / name).is_symlink(), name)
        hits = [str(p) for p in out.rglob("*") if p.is_file() and p.read_bytes() == b"stub"]
        self.assertEqual([], hits)

    def test_icu_download_is_checked_cached_and_linked_by_soname(self):
        import zipfile
        libs = {"libicudata.so.72.1.0.3": b"data", "libicuuc.so.72.1.0.3": b"uc", "libicui18n.so.72.1.0.3": b"i18n"}
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            for name, data in libs.items():
                z.writestr("runtimes/linux-arm64/native/" + name, data)
        package = buf.getvalue()
        spec = dict(NG["ICU"], sha256=hashlib.sha256(package).hexdigest(),
                    members={n: hashlib.sha256(d).hexdigest() for n, d in libs.items()})
        calls = []
        def opener(url, timeout):
            calls.append(url)
            return io.BytesIO(package)
        cache = self.root / "cache"
        folder = NG["icu_libs"](spec, str(cache), opener)
        self.assertEqual(b"uc", (Path(folder) / "libicuuc.so.72").read_bytes())
        self.assertEqual(b"data", (Path(folder) / "libicudata.so.72").read_bytes())
        self.assertEqual(b"i18n", (Path(folder) / "libicui18n.so.72.1.0.3").read_bytes())
        self.assertEqual(folder, NG["icu_libs"](spec, str(cache), opener))
        self.assertEqual(1, len(calls))
        bad = dict(spec, members=dict(spec["members"], **{"libicuuc.so.72.1.0.3": "0" * 64}))
        with self.assertRaises(SystemExit):
            NG["icu_libs"](bad, str(self.root / "cache2"), opener)

    def test_engine_env_uses_app_local_icu_and_falls_back_to_invariant(self):
        env = NG["engine_env"]({"LD_LIBRARY_PATH": "/a"}, "/icu", system_icu="")
        self.assertEqual(NG["ICU"]["version"], env["DOTNET_SYSTEM_GLOBALIZATION_APPLOCALICU"])
        self.assertEqual("/icu:/a", env["LD_LIBRARY_PATH"])
        self.assertNotIn("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", env)
        env = NG["engine_env"]({}, None, system_icu="")
        self.assertEqual("1", env["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"])
        env = NG["engine_env"]({}, "/icu", system_icu="libicuuc.so.72")
        self.assertNotIn("DOTNET_SYSTEM_GLOBALIZATION_APPLOCALICU", env)
        self.assertNotIn("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", env)
        env = NG["engine_env"]({"DOTNET_SYSTEM_GLOBALIZATION_INVARIANT": "1"}, "/icu", system_icu="")
        self.assertNotIn("DOTNET_SYSTEM_GLOBALIZATION_APPLOCALICU", env)

    def test_engine_env_prefers_the_guest_icu_and_gives_it_a_real_locale(self):
        env = NG["engine_env"]({"LANG": "C", "LC_ALL": "C"}, "/icu", system_icu="/usr/lib/libicuuc.so.78")
        self.assertNotIn("DOTNET_SYSTEM_GLOBALIZATION_APPLOCALICU", env)
        self.assertNotIn("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", env)
        self.assertNotIn("LC_ALL", env)
        self.assertEqual("en_US.UTF-8", env["LANG"])
        env = NG["engine_env"]({"LANG": "fr_FR.UTF-8"}, None, system_icu="/usr/lib/libicuuc.so.78")
        self.assertEqual("fr_FR.UTF-8", env["LANG"])
        env = NG["engine_env"]({}, "/icu", system_icu="")
        self.assertEqual("en_US.UTF-8", env["LANG"])
        env = NG["engine_env"]({}, None, system_icu="")
        self.assertNotIn("LANG", env)

    def test_guest_icu_is_found_by_file_with_all_three_libraries(self):
        lib = self.root / "lib"
        lib.mkdir()
        self.assertIsNone(NG["guest_icu"]((str(lib),)))
        for name in ("libicuuc.so.78", "libicuuc.so.78.3", "libicui18n.so.78", "libicuuc.so.80"):
            (lib / name).write_bytes(b"")
        self.assertIsNone(NG["guest_icu"]((str(lib),)))
        (lib / "libicudata.so.78").write_bytes(b"")
        self.assertEqual(str(lib / "libicuuc.so.78"), NG["guest_icu"]((str(self.root / "none"), str(lib))))

    def test_steam_api_download_is_checked_and_cached(self):
        import hashlib, zipfile
        lib = b"\x7fELF valve"
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("runtimes/linux-arm64/native/libsteam_api.so", lib)
        pkg = buf.getvalue()
        spec = {"url": "https://example.invalid/p.nupkg", "sha256": hashlib.sha256(pkg).hexdigest(),
                "member": "runtimes/linux-arm64/native/libsteam_api.so", "member_sha256": hashlib.sha256(lib).hexdigest()}
        calls = []
        def opener(url, timeout):
            calls.append(url)
            return io.BytesIO(pkg)
        cache = self.root / "cache"
        path = NG["steam_api"](spec, str(cache), opener)
        self.assertEqual(lib, Path(path).read_bytes())
        self.assertEqual(path, NG["steam_api"](spec, str(cache), opener))
        self.assertEqual(1, len(calls))
        with self.assertRaises(SystemExit):
            NG["steam_api"](dict(spec, member_sha256="0" * 64), str(self.root / "c2"), opener)
        with self.assertRaises(SystemExit):
            NG["steam_api"](dict(spec, sha256="0" * 64), str(self.root / "c3"), opener)

    @unittest.skipUnless(shutil.which("zstd"), "zstd not installed")
    def test_fetch_checks_and_caches(self):
        import hashlib, io, subprocess
        src = self.root / "src"
        (src / "kit").mkdir(parents=True)
        (src / "godot").write_bytes(b"engine")
        archive = self.root / "c.tar.zst"
        subprocess.run(["sh", "-c", 'tar -C "$1" -c . | zstd -q -o "$2"', "sh", str(src), str(archive)], check=True)
        data = archive.read_bytes()
        comp = {"tag": "native-engine-t1", "url": "https://example.invalid/c.tar.zst", "sha256": hashlib.sha256(data).hexdigest()}
        calls = []
        def opener(url, timeout):
            calls.append(url)
            return io.BytesIO(data)
        cache = self.root / "cache"
        folder = NG["fetch"](comp, str(cache), opener)
        self.assertEqual(b"engine", (Path(folder) / "godot").read_bytes())
        NG["fetch"](comp, str(cache), opener)
        self.assertEqual(1, len(calls))
        with self.assertRaises(SystemExit):
            NG["fetch"](dict(comp, tag="native-engine-t2", sha256="0" * 64), str(cache), opener)
        self.assertFalse((cache / "native-engine-t2").exists())
        # A new engine landing removes the old engine folders and nothing else.
        for name in ("native-engine-7", "native-engine-8.orig", "check"):
            (cache / name).mkdir()
        NG["fetch"](dict(comp, tag="native-engine-9"), str(cache), opener)
        self.assertEqual({"native-engine-t1", "native-engine-8.orig", "check", "native-engine-9"}, {p.name for p in cache.iterdir()})
        with self.assertRaises(SystemExit):
            NG["fetch"]({"tag": "", "url": "", "sha256": ""}, str(cache), opener)

    def run_check(self, *extra):
        out, checks = self.root / "check-launch", self.root / "checks"
        g = NG["main"].__globals__
        old = g["CHECK_DIR"]
        g["CHECK_DIR"] = str(checks)
        err = io.StringIO()
        try:
            with unittest.mock.patch("sys.stderr", err):
                rc = NG["main"](["check", "--game-dir", str(self.game), "--kit", str(self.kit), "--engine", str(self.engine),
                                 "--out", str(out), "--exe", "SlayTheSpire2.exe"] + list(extra))
        finally:
            g["CHECK_DIR"] = old
        path = checks / "sts2.json"
        return rc, (json.loads(path.read_text()) if path.exists() else None), err.getvalue(), checks

    def test_check_passes_and_records_the_game_s_fingerprint(self):
        # A ReadyToRun image without the IL-only mark is relabelled and kept: loadable-shaped.
        (self.game / "data_sts2_windows_x86_64" / "Sentry.dll").write_bytes(pe(True, machine=0x8664, ilonly=False, r2r=True))
        rc, result, err, checks = self.run_check()
        self.assertEqual(0, rc)
        self.assertEqual("pass", result["result"])
        self.assertEqual("custom", result["component"])
        self.assertEqual([], result["rejected"])
        self.assertEqual(64, len(result["fingerprint"]))
        self.assertIn("droiddeck-native-godot: check pass sts2", err)
        fresh = NG["fresh_check"](str(self.game), "custom", check_dir=str(checks))
        self.assertEqual("pass", fresh["result"])
        self.assertIsNone(NG["fresh_check"](str(self.game), "native-engine-other", check_dir=str(checks)))
        # A game update (a managed file changes) makes the result stale.
        (self.game / "data_sts2_windows_x86_64" / "sts2.dll").write_bytes(pe(True) + b"\0update")
        self.assertIsNone(NG["fresh_check"](str(self.game), "custom", check_dir=str(checks)))

    def test_check_fails_when_the_game_itself_is_mixed_mode(self):
        (self.game / "data_sts2_windows_x86_64" / "sts2.dll").write_bytes(
            pe(True, machine=0x8664, ilonly=False, fixups=True))
        rc, result, err, checks = self.run_check("--name", "sts2")
        self.assertEqual(1, rc)
        self.assertEqual("fail", result["result"])
        self.assertEqual(["sts2.dll"], result["rejected"])
        self.assertIn("mixed-mode", result["reasons"][0])
        self.assertIn("droiddeck-native-godot: check fail sts2", err)
        self.assertEqual("fail", NG["fresh_check"](str(self.game), "custom", check_dir=str(checks))["result"])

    def test_check_left_out_helpers_do_not_fail_it(self):
        (self.game / "data_sts2_windows_x86_64" / "Helper.Native.dll").write_bytes(
            pe(True, machine=0x8664, ilonly=False, fixups=True))
        rc, result, _, _ = self.run_check()
        self.assertEqual((0, "pass"), (rc, result["result"]))
        self.assertIn("Helper.Native.dll", result["left_out"])

    def test_check_fails_on_an_assembly_the_converter_left_unloadable(self):
        real = NG["main"].__globals__["portable_image"]
        NG["main"].__globals__["portable_image"] = lambda data: (data, "untouched")  # a broken converter
        try:
            rc, result, _, _ = self.run_check()
        finally:
            NG["main"].__globals__["portable_image"] = real
        self.assertEqual((1, "fail"), (rc, result["result"]))
        self.assertEqual(["0Harmony.dll"], result["rejected"])
        self.assertIn("still labelled x64", " ".join(result["reasons"]))

    def test_loadable_shape(self):
        p = self.root / "a.dll"
        for image, why in ((pe(True), None), (pe(True, machine=0x14C), None),
                           (pe(True, ilonly=False, r2r=True), None), (pe(True, machine=0x8664), "still labelled x64"),
                           (pe(True, ilonly=False, fixups=True), "mixed-mode"), (pe(False), "not a .NET assembly")):
            p.write_bytes(image)
            got = NG["loadable_shape"](str(p))
            self.assertTrue(got is None if why is None else why in got, (why, got))

    def test_embedded_pack(self):
        (self.game / "SlayTheSpire2.pck").unlink()
        self.assertTrue(NG["find_pack"](str(self.game), "SlayTheSpire2.exe").endswith("SlayTheSpire2.exe"))


FAKE_CROSSGEN2 = """
import json, os, shutil, sys
args = sys.argv[1:]
if args == ["--version"]:
    print(os.environ.get("FAKE_CG_VERSION", "9.0.23+0123abcd"))
    sys.exit(0)
with open(os.environ["FAKE_CG_LOG"], "a") as f:
    f.write(json.dumps({"args": args, "input": open(args[0], "rb").read().hex()}) + "\\n")
mode = os.environ.get("FAKE_CG_MODE", "ok")
if mode == "fail":
    print("Emitting nothing")
    print("Error: boom in " + os.path.basename(args[0]))
    sys.exit(1)
shutil.copyfile(os.environ["FAKE_CG_IMAGE"], args[args.index("-o") + 1])
"""


class NativeR2RTest(unittest.TestCase):
    """Experiment native-r2r, with a stand-in crossgen2 that writes a fixed image."""

    setUp_game = NativeGodotTest.setUp

    def setUp(self):
        self.setUp_game()
        for name in ("System.Private.CoreLib.dll", "GodotSharp.dll"):  # managed, so they are references
            (self.kit / name).write_bytes(pe(True) + name.encode())
        self.cg_dir = self.root / "crossgen2"
        self.cg_dir.mkdir()
        cg = self.cg_dir / "crossgen2"
        cg.write_text("#!" + sys.executable + "\n" + FAKE_CROSSGEN2)
        cg.chmod(0o755)
        Path(NG["crossgen2_jit"](str(cg))).write_bytes(b"jit")
        self.cg_log = self.root / "cg.log"
        self.image = self.root / "linux-arm64-r2r.dll"
        self.image.write_bytes(pe(True, machine=0xD11D, r2r=True) + b"code")
        self.cache = self.root / "r2r-cache"
        self.pristine = self.root / "pristine"
        self.env = {"DROIDDECK_EXP_NATIVE_R2R": "1", "DROIDDECK_CROSSGEN2": str(self.cg_dir),
                    "DROIDDECK_NATIVE_R2R_CACHE": str(self.cache), "DROIDDECK_R2R_CACHE": str(self.pristine),
                    "FAKE_CG_LOG": str(self.cg_log), "FAKE_CG_IMAGE": str(self.image)}
        patch = unittest.mock.patch.dict(os.environ, self.env)
        patch.start()
        self.addCleanup(patch.stop)
        self.out = self.root / "launch"
        self.out.mkdir()
        self.data = self.out / "data_sts2_linuxbsd_arm64"

    def compose(self):
        err = io.StringIO()
        with unittest.mock.patch("sys.stderr", err):
            r = NG["compose"](str(self.game), str(self.kit), str(self.engine), str(self.out), "SlayTheSpire2.exe")
        return r, err.getvalue()

    def job(self, r2r):
        plan = self.root / "plan.json"
        plan.write_text(json.dumps(r2r))
        rc = NG["r2r_job"](str(plan))
        self.assertFalse(plan.exists())
        return rc, (self.cache / "compile.log").read_text()

    def crossgen2_calls(self):
        return [json.loads(line) for line in self.cg_log.read_text().splitlines()] if self.cg_log.exists() else []

    def test_off_by_default(self):
        with unittest.mock.patch.dict(os.environ, {"DROIDDECK_EXP_NATIVE_R2R": ""}):
            r, err = self.compose()
        self.assertNotIn("r2r", r)
        self.assertNotIn("native-r2r", err)
        self.assertIsNone(NG["r2r_after_first_frame"](r))

    def test_compiles_after_a_miss_and_uses_the_cache_next_time(self):
        stale = self.cache / "linux-arm64-single__fx9.0.0__cg9.0.1"
        stale.mkdir(parents=True)
        r, err = self.compose()
        r2r = r["r2r"]
        self.assertIn("native-r2r: 0 of 2 game assemblies from the linux-arm64 ReadyToRun cache "
                      "(linux-arm64-single__fx9.0.0__cg9.0.23); 2 to compile after the first frame", err)
        self.assertEqual(["0Harmony.dll", "sts2.dll"], sorted(m["name"] for m in r2r["missing"]))
        self.assertTrue(r2r["can_compile"])
        self.assertIsNotNone(NG["r2r_after_first_frame"](r))
        launch_copy = (self.data / "0Harmony.dll").read_bytes()
        launch_info = NG["pe_info"](launch_copy)  # compose already turned the x64 ReadyToRun image into plain IL
        self.assertEqual((0xAA64, False), (launch_info["machine"], launch_info["ready_to_run"]))
        stripped = NG["crossgen2_input"]((self.game / "data_sts2_windows_x86_64" / "0Harmony.dll").read_bytes())
        info = NG["pe_info"](stripped)
        self.assertEqual((0xAA64, (0, 0), 1), (info["machine"], info["native_header"], info["flags"] & 0x5))
        rc, log = self.job(r2r)
        self.assertEqual(0, rc)
        self.assertIn("compiled 2 of 2", log)
        calls = self.crossgen2_calls()
        self.assertEqual(2, len(calls))
        harmony = next(c for c in calls if c["args"][0].endswith("in-0Harmony.dll"))
        args = harmony["args"]
        self.assertEqual(stripped.hex(), harmony["input"])  # crossgen2 gets a stripped copy, made in its scratch folder
        for flag in (["--targetos", "linux"], ["--targetarch", "arm64"], ["--parallelism", "2"]):
            self.assertIn(flag, [args[i:i + 2] for i in range(len(args))])
        self.assertIn("-O", args)
        refs = sorted(os.path.basename(args[i + 1]) for i, a in enumerate(args) if a == "-r")
        self.assertEqual(["GodotSharp.dll", "System.Private.CoreLib.dll", "sts2.dll"], refs)
        self.assertFalse(stale.exists())  # images for another kit or compiler are dropped
        self.assertEqual([], [p for p in self.cache.iterdir() if p.name.startswith(("job-", "plan-"))])

        r, err = self.compose()
        self.assertIn("native-r2r: 2 of 2 game assemblies from the linux-arm64 ReadyToRun cache", err)
        self.assertEqual([], r["r2r"]["missing"])
        self.assertIsNone(NG["r2r_after_first_frame"](r))
        for name in ("sts2.dll", "0Harmony.dll"):
            self.assertTrue(NG["is_linux_arm64_r2r"]((self.data / name).read_bytes()))
        # The game folder is untouched and the cache file is not the game's.
        self.assertEqual(0x8664, NG["pe_info"]((self.game / "data_sts2_windows_x86_64" / "0Harmony.dll").read_bytes())["machine"])
        self.assertFalse(os.path.samefile(self.game / "data_sts2_windows_x86_64" / "sts2.dll", self.data / "sts2.dll"))
        deps = json.loads((self.data / "sts2.deps.json").read_text())
        self.assertIn("sts2.dll", json.dumps(deps))

    def test_crossgen2_input(self):
        for image in (pe(True, machine=0x8664, r2r=True), pe(True, machine=0x8664, ilonly=False, r2r=True)):
            info = NG["pe_info"](NG["crossgen2_input"](image))
            self.assertEqual((0xAA64, (0, 0), 1, 0), (info["machine"], info["native_header"], info["flags"] & 1, info["flags"] & 4))
        il = pe(True, machine=0x14C)
        self.assertEqual(il, NG["crossgen2_input"](il))
        self.assertIsNone(NG["crossgen2_input"](pe(True, machine=0x8664, ilonly=False, fixups=True)))
        self.assertIsNone(NG["crossgen2_input"](pe(False)))

    def test_a_failed_compile_keeps_the_jit_and_logs_the_error(self):
        r, _ = self.compose()
        with unittest.mock.patch.dict(os.environ, {"FAKE_CG_MODE": "fail"}):
            rc, log = self.job(r["r2r"])
        self.assertEqual(1, rc)
        self.assertIn("compiled 0 of 2", log)
        self.assertIn("Error: boom in in-sts2.dll", log)
        r, err = self.compose()
        self.assertIn("0 of 2 game assemblies", err)
        self.assertEqual(NG["pe_info"](pe(True))["machine"], NG["pe_info"]((self.data / "sts2.dll").read_bytes())["machine"])

    def test_output_that_is_not_linux_arm64_ready_to_run_is_not_cached(self):
        r, _ = self.compose()
        for bad in (pe(True, machine=0x8664, r2r=True), pe(True, machine=0xAA64, r2r=True), pe(True, machine=0xD11D)):
            self.image.write_bytes(bad)
            rc, log = self.job(r["r2r"])
            self.assertEqual(1, rc)
            self.assertIn("output is not a Linux arm64 ReadyToRun image", log.splitlines()[-1])
        self.assertEqual([], list((self.cache / r["r2r"]["tag"]).iterdir()))

    def test_a_damaged_cache_entry_is_compiled_again(self):
        r, _ = self.compose()
        self.job(r["r2r"])
        images = sorted((self.cache / r["r2r"]["tag"]).glob("*.dll"))
        os.chmod(images[0], 0o644)
        images[0].write_bytes(images[0].read_bytes()[:100])
        r, err = self.compose()
        self.assertIn("1 of 2 game assemblies", err)

    def test_one_compile_at_a_time(self):
        import fcntl
        r, _ = self.compose()
        self.cache.mkdir(exist_ok=True)
        with open(self.cache / "compile.lock", "w") as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            rc, log = self.job(r["r2r"])
        self.assertEqual(0, rc)
        self.assertIn("another compile is running; nothing compiled", log)
        self.assertEqual([], self.crossgen2_calls())

    def test_a_game_file_changed_since_the_launch_is_skipped(self):
        r, _ = self.compose()
        (self.game / "data_sts2_windows_x86_64" / "sts2.dll").write_bytes(pe(True) + b"update")
        rc, log = self.job(r["r2r"])
        self.assertEqual(1, rc)
        self.assertIn("sts2.dll (changed since the launch)", log)
        self.assertIn("compiled 1 of 2", log)

    def test_pristine_il_from_the_proton_r2r_tool_is_preferred(self):
        win = self.game / "data_sts2_windows_x86_64"
        theirs = pe(True, machine=0x8664, r2r=True) + b"their win-x64 image"
        (win / "sts2.dll").write_bytes(theirs)
        original = pe(True, machine=0x14C) + b"pristine IL"
        import hashlib
        il_sha = hashlib.sha256(original).hexdigest()
        self.pristine.mkdir()
        (self.pristine / f"{il_sha}.il.dll").write_bytes(original)
        (self.pristine / "manifest.json").write_text(json.dumps({os.path.realpath(win / "sts2.dll"): {
            "il": il_sha, "r2r": hashlib.sha256(theirs).hexdigest(), "mode": "single"}}))
        r, _ = self.compose()
        sts2 = next(m for m in r["r2r"]["missing"] if m["name"] == "sts2.dll")
        self.assertEqual(str(self.pristine / f"{il_sha}.il.dll"), sts2["source"])
        self.assertEqual(il_sha, sts2["key"])  # AnyCPU IL goes to crossgen2 as it is
        self.job(r["r2r"])
        call = next(c for c in self.crossgen2_calls() if c["args"][0].endswith("in-sts2.dll"))
        self.assertEqual(original.hex(), call["input"])
        # Without a matching manifest entry the game's own file is the source.
        (self.pristine / "manifest.json").write_text("{}")
        r, _ = self.compose()
        self.assertEqual(str(win / "sts2.dll"), next(m for m in r["r2r"]["missing"] if m["name"] == "sts2.dll")["source"])

    def test_no_crossgen2_or_the_wrong_one_runs_on_the_jit(self):
        with unittest.mock.patch.dict(os.environ, {"DROIDDECK_CROSSGEN2": str(self.root / "nowhere")}):
            r, err = self.compose()
        self.assertIsNone(r["r2r"])
        self.assertIn("no crossgen2 at", err)
        self.assertTrue((self.data / "sts2.dll").is_file())
        with unittest.mock.patch.dict(os.environ, {"FAKE_CG_VERSION": "8.0.10+abc"}):
            r, err = self.compose()
        self.assertIsNone(r["r2r"])
        self.assertIn("crossgen2 8.0.10 does not fit the kit's .NET 9.0.0", err)

    def test_without_the_arm64_code_generator_cached_images_still_count(self):
        os.unlink(NG["crossgen2_jit"](str(self.cg_dir / "crossgen2")))
        r, err = self.compose()
        self.assertFalse(r["r2r"]["can_compile"])
        self.assertIn("cannot compile the other 2: crossgen2 lacks libclrjit_universal_arm64_", err)
        self.assertIsNone(NG["r2r_after_first_frame"](r))

    def test_lookup_errors_never_stop_compose(self):
        with unittest.mock.patch.dict(NG["_r2r_compose"].__globals__, {"r2r_input": unittest.mock.Mock(side_effect=OSError("disk"))}):
            r, err = self.compose()
        self.assertIsNone(r["r2r"])
        self.assertIn("native-r2r: cache lookup failed (OSError: disk); running on the JIT", err)

    def test_the_background_compile_is_detached(self):
        r, _ = self.compose()
        err = io.StringIO()
        real = subprocess.Popen
        popen = unittest.mock.Mock(side_effect=lambda *a, **kw: real(*a, **kw))
        with unittest.mock.patch("sys.stderr", err), unittest.mock.patch("subprocess.Popen", popen):
            proc = NG["r2r_after_first_frame"](r)()
        self.assertIn("compiling 2 assemblies in the background", err.getvalue())
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        self.assertEqual(0, proc.wait(timeout=60))
        self.assertIn("compiled 2 of 2", (self.cache / "compile.log").read_text())
        r, err = self.compose()
        self.assertIn("2 of 2 game assemblies", err)

    def test_run_starts_the_compile_at_the_first_frame(self):
        seen = {}
        real = NG["main"].__globals__["watch"]
        NG["main"].__globals__["watch"] = lambda args, env, **kw: seen.update(kw) or 0
        try:
            with unittest.mock.patch("sys.stderr", io.StringIO()):
                NG["main"](["run", "--game-dir", str(self.game), "--kit", str(self.kit), "--engine", str(self.engine),
                            "--out", str(self.out), "--exe", "SlayTheSpire2.exe"])
        finally:
            NG["main"].__globals__["watch"] = real
            os.chdir(Path(__file__).resolve().parent)
        self.assertTrue(callable(seen["on_first_frame"]))

    def test_watch_calls_on_first_frame_once_and_survives_its_errors(self):
        calls = []
        script = self.root / "engine.py"
        script.write_text("print('Project FPS: 60 (16.67 mspf)')\nprint('Project FPS: 60 (16.67 mspf)')\n")

        def boom():
            calls.append(1)
            raise RuntimeError("no")
        with unittest.mock.patch("sys.stderr", io.StringIO()) as err:
            rc = NG["watch"]([sys.executable, str(script)], dict(os.environ), first_frame_s=10, out=io.StringIO(),
                             on_first_frame=boom)
        self.assertEqual((0, [1]), (rc, calls))
        self.assertIn("after the first frame: RuntimeError: no", err.getvalue())


class GameEnvHookTest(unittest.TestCase):
    def setUp(self):
        self.env_mod = runpy.run_path(str(BIN / "droiddeck-game-env"))
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.game = self.root / "Slay the Spire 2"
        (self.game / "data_sts2_windows_x86_64").mkdir(parents=True)
        self.exe = str(self.game / "SlayTheSpire2.exe")
        self.cmd = ["/proton", "waitforexitandrun", self.exe, "-force-x"]
        self.on = {"DROIDDECK_EXP_NATIVE_ENGINE": "1", "BL_DEBUG_DIR": str(self.root)}

    def hook(self, env, rc=0, took=5.0):
        calls = []
        times = iter([100.0, 100.0 + took])

        class Done:
            returncode = rc

        def run(args, **kw):
            calls.append(args)
            if kw.get("stdout"):
                kw["stdout"].write("ERROR: something\n")
            return Done()
        result = self.env_mod["native_engine"](self.cmd, env, run=run, clock=lambda: next(times))
        return result, calls

    def run_env(self, env):
        seen = []

        class Done:
            returncode = 0

        def run(args, **kw):
            seen.append(kw["env"])
            return Done()
        times = iter([100.0, 1000.0])
        _, notes = self.env_mod["native_engine"](self.cmd, env, run=run, clock=lambda: next(times))
        return seen[0], notes

    def test_engine_gets_the_powervr_vulkan_layers_proton_gets(self):
        # Phone run 18:49:56Z Nov 14: without the WSI layer Godot found no queue that can present and exited.
        runtime = self.root / "powervr"
        for name in self.env_mod["POWERVR_VULKAN_FILES"]:
            (runtime / name).parent.mkdir(parents=True, exist_ok=True)
            (runtime / name).write_bytes(b"x")
        env = dict(self.on, DROIDDECK_POWERVR_RUNTIME=str(runtime), ENABLE_GAMESCOPE_WSI="1", DISABLE_WSI_LAYER="1",
                   VK_ICD_FILENAMES="/icd.json", DISPLAY=":1")
        got, notes = self.run_env(env)
        proton = self.env_mod["powervr_vulkan_env"](runtime, env)
        for key in ("VK_LAYER_PATH", "VK_INSTANCE_LAYERS", "ENABLE_GAMESCOPE_WSI", "VK_LOADER_LAYERS_DISABLE",
                    "HYBRIS_BC_TEXTURES", "DISABLE_VK_LAYER_VALVE_steam_fossilize_1"):
            self.assertEqual(proton[key], got[key], key)
        self.assertTrue(got["VK_INSTANCE_LAYERS"].startswith("VK_LAYER_window_system_integration:"))
        self.assertIn(str(runtime / "wsi"), got["VK_LAYER_PATH"].split(":"))
        self.assertEqual("0", got["ENABLE_GAMESCOPE_WSI"])
        self.assertNotIn("DISABLE_WSI_LAYER", got)
        self.assertEqual(("/icd.json", ":1"), (got["VK_ICD_FILENAMES"], got["DISPLAY"]))  # the session's, untouched
        self.assertNotIn("PROTON_ENABLE_WAYLAND", got)
        self.assertIn("native-engine: Vulkan through the PowerVR runtime's WSI and hybris compat layers", notes)
        self.assertNotIn("VK_INSTANCE_LAYERS", env)  # the caller's env is not changed

    def test_engine_env_is_left_alone_without_a_powervr_runtime_or_its_layers(self):
        got, notes = self.run_env(dict(self.on, VK_ICD_FILENAMES="/icd.json"))
        self.assertNotIn("VK_INSTANCE_LAYERS", got)
        self.assertFalse(any("PowerVR" in n for n in notes))
        got, notes = self.run_env(dict(self.on, DROIDDECK_POWERVR_RUNTIME=str(self.root / "none")))
        self.assertNotIn("VK_INSTANCE_LAYERS", got)
        self.assertTrue(any("PowerVR Vulkan layers unavailable" in n for n in notes))

    def test_off_without_the_experiment(self):
        (rc, notes), calls = self.hook({})
        self.assertEqual((None, [], []), (rc, notes, calls))
        self.assertIn("native-engine", self.env_mod["EXPERIMENTS"])

    def test_runs_natively_and_ends_with_its_code(self):
        (rc, notes), calls = self.hook(self.on, rc=0, took=900)
        self.assertEqual(0, rc)
        self.assertEqual(["run", "--fetch", "--game-dir", str(self.game), "--exe", "SlayTheSpire2.exe"], calls[0][2:8])
        self.assertEqual(["--", "-force-x"], calls[0][-2:])

    def test_falls_back_to_proton_when_it_fails_early(self):
        (rc, notes), _ = self.hook(self.on, rc=134, took=4)
        self.assertIsNone(rc)
        self.assertIn("native-engine log: ERROR: something", notes)
        self.assertTrue(notes[-1].endswith("starting on Proton"))

    def test_no_first_frame_falls_back_to_proton_however_late(self):
        for took in (3, 95, 400):
            with self.subTest(took=took):
                (rc, notes), _ = self.hook(self.on, rc=86, took=took)
                self.assertIsNone(rc)
                self.assertIn("native-engine log: ERROR: something", notes)
                self.assertEqual("native-engine: no first frame after %d s; starting on Proton" % took, notes[-1])

    def test_a_late_failure_is_not_retried(self):
        (rc, _), _ = self.hook(self.on, rc=1, took=600)
        self.assertEqual(1, rc)

    def test_not_a_csharp_game(self):
        (self.game / "data_sts2_windows_x86_64").rmdir()
        (rc, notes), calls = self.hook(self.on)
        self.assertEqual((None, []), (rc, calls))



class CompatClassesInCSharpTest(unittest.TestCase):
    """The engine build lists every native_compat class in csharp_script.cpp's ignored_types (the phone's Nov 15
    "Wrapper class not found for type: SpineSprite"), and the gate fails a stand-in that could not take them."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_every_registered_class_is_listed_once_and_the_line_must_exist(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/csharp_compat_classes.py"))
        names = mod["compat_classes"](
            (self.ROOT / "tools/native-engine/modules/native_compat/register_types.cpp").read_text())
        for want in ("SpineSprite", "SpineAnimationState", "FmodServer", "NativeSkeleton2D"):
            self.assertIn(want, names)
        out = mod["patch"]("x\nconst Vector<String> ignored_types = {};\ny\n", names)
        self.assertIn('"SpineSprite"', out)
        self.assertNotIn("ignored_types = {};", out)
        with self.assertRaises(SystemExit):
            mod["patch"]("const Vector<String> ignored_types = { \"X\" };\n", names)
        with self.assertRaises(SystemExit):
            mod["patch"]("const Vector<String> ignored_types = {};\n", [])

    def test_gate_fails_a_stand_in_whose_csharp_could_not_take_the_compat_classes(self):
        gate = runpy.run_path(str(self.ROOT / "tools/native-engine/standin/gate.py"))
        base = ["droiddeck-native-godot: first frame after 1.0 s", "STANDIN_DEPS loaded=0 none", "FIRST_FRAME",
                "MAINLOOP_OK frames=5"]
        ok, *_ = gate["judge"](0, [(0.0, s) for s in base + ["STANDIN_COMPAT ok n=6"]], "off")
        self.assertEqual([], ok)
        bad, *_ = gate["judge"](0, [(0.0, s) for s in base + [
            "STANDIN_COMPAT failed SpineSprite InvalidOperationException Wrapper class not found for type: SpineSprite"]],
            "off")
        self.assertEqual(1, len(bad))
        self.assertIn("SpineSprite InvalidOperationException", bad[0])

class PipelineCacheSavePatchTest(unittest.TestCase):
    """The engine build can save the Vulkan pipeline cache once pipeline creation goes quiet (the wrapper stops
    the engine with SIGKILL, so stock's save-on-close never runs) and log a pipeline/shader census; both off
    unless their variables are set."""

    ROOT = Path(__file__).resolve().parents[2]

    def mod(self):
        return runpy.run_path(str(self.ROOT / "tools/native-engine/patches/rd_pipeline_cache_save.py"))

    def test_every_anchor_once_and_switches_off_by_default(self):
        mod = self.mod()
        src = "".join(old + "\n// gap\n" for _, old, _ in mod["EDITS"])
        out = mod["patch"](src)
        self.assertIn("DROIDDECK_NATIVE_PIPELINE_CACHE_QUIET_MS", out)
        self.assertIn("DROIDDECK_NATIVE_PIPELINE_CENSUS", out)
        self.assertEqual(out.count("droiddeck_pc_count_pipeline(droiddeck_t0);"), 2)
        self.assertLess(out.index("droiddeck_pc_dirty.store(true);\n\t\treturn;"), out.index("still_saving") if "still_saving" in out else len(out))
        with self.assertRaises(SystemExit):
            mod["patch"](out)  # twice
        with self.assertRaises(SystemExit):
            mod["patch"](src.replace(mod["SWAP"], ""))
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if godot:  # against the real 4.5.1 source, after the set-bind patch as the build applies it
            real = (Path(godot) / "servers/rendering/rendering_device.cpp").read_text()
            binds = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/rd_skip_redundant_set_binds.py"))
            self.assertEqual(mod["patch"](binds["patch"](real)).count("Kestrel (rd_pipeline_cache_save.py)"), 9)

    def test_build_applies_it_after_the_set_bind_patch(self):
        for rel in ("tools/native-engine/devloop/build.sh", ".github/workflows/native-engine-build.yml"):
            text = (self.ROOT / rel).read_text()
            self.assertLess(text.index("rd_skip_redundant_set_binds.py"), text.index("rd_pipeline_cache_save.py"), rel)


class RenderingMethodEnvPatchTest(unittest.TestCase):
    """DROIDDECK_NATIVE_RENDERING_METHOD picks mobile or forward_plus in place of the project's renderer, only when
    the command line names none."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_patch_sits_before_the_method_check(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/main_rendering_method_env.py"))
        src = '#include "main.h"\n' + mod["ANCHOR"]
        out = mod["patch"](src)
        self.assertLess(out.index("DROIDDECK_NATIVE_RENDERING_METHOD"), out.index('if (rendering_method != "forward_plus" &&'))
        self.assertIn("#include <stdlib.h>", out)
        with self.assertRaises(SystemExit):
            mod["patch"](out)
        with self.assertRaises(SystemExit):
            mod["patch"]("nothing")
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if godot:
            real = (Path(godot) / "main/main.cpp").read_text()
            self.assertIn(mod["MARK"], mod["patch"](real))


class LoggerTimePrefixPatchTest(unittest.TestCase):
    """DROIDDECK_NATIVE_LOG_TIME=1 puts the monotonic time before each engine log line, except "Project FPS:",
    which the wrapper reads by its line start."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_patch_guards_logv(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/logger_time_prefix.py"))
        src = mod["INCLUDE"] + mod["ANCHOR"] + "\tvprintf(p_format, p_list);\n}\n"
        out = mod["patch"](src)
        self.assertLess(out.index("DROIDDECK_NATIVE_LOG_TIME"), out.index("vprintf(p_format, p_list)"))
        self.assertIn('"Project FPS:"', out)
        with self.assertRaises(SystemExit):
            mod["patch"](out)
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if godot:
            real = (Path(godot) / "core/io/logger.cpp").read_text()
            self.assertIn(mod["MARK"], mod["patch"](real))


class SkipRedundantSetBindsPatchTest(unittest.TestCase):
    """The engine build makes RenderingDevice skip binding a uniform set already bound at its index (the phone's
    Nov 24 vkcount: 810 descriptor-set binds for 604 draws, ~25 us each), with a run-time switch back."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_patch_guards_the_bind_and_needs_its_anchor(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/rd_skip_redundant_set_binds.py"))
        src = '#include "rendering_device.h"\n' + mod["ANCHOR"]
        out = mod["patch"](src)
        self.assertIn("DROIDDECK_RD_REBIND_SETS", out)
        self.assertIn("#include <stdlib.h>", out)
        guard = out.index("draw_list.state.sets[p_index].bound && draw_list.state.sets[p_index].uniform_set == p_uniform_set")
        self.assertLess(guard, out.index("// Needs rebind."))
        with self.assertRaises(SystemExit):
            mod["patch"]("nothing here")
        with self.assertRaises(SystemExit):
            mod["patch"](src + mod["ANCHOR"])
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if godot:  # against the real 4.5.1 source when it is at hand
            real = (Path(godot) / "servers/rendering/rendering_device.cpp").read_text()
            self.assertIn("DROIDDECK_RD_REBIND_SETS", mod["patch"](real))


class CanvasBatchCensusPatchTest(unittest.TestCase):
    """The engine build counts why the 2D renderer starts each batch (every batch is at least one draw, ~37 us of
    main-thread time each on the phone), credited to the first reason, for the render census line."""

    ROOT = Path(__file__).resolve().parents[2]

    def synthetic(self, mod, calls):
        body = "".join(
            "\tswitch (c->type) {\n\t\tcase Item::Command::TYPE_RECT: {\n"
            "\t\t\tif (r_current_batch->tex_info != tex_info) {\n\t\t\t\tr_current_batch = _new_batch(r_batch_broken);\n\t\t\t}\n"
            for _ in range(calls))
        return (
            '#include "renderer_canvas_render_rd.h"\n#include "core/math/geometry_2d.h"\n\nstatic int x;\n'
            + mod["RENDER_ITEMS"] + "int a) {\n"
            "\tif (material != current_batch->material) {\n\t\tcurrent_batch = _new_batch(batch_broken);\n\t}\n}\n"
            "void RendererCanvasRenderRD::_record_item_commands() {\n" + body + "}\n"
            + mod["DEF"] + "\n\tif (x) {\n\t\treturn nullptr;\n\t}\n" + mod["PUSHED"] + "\treturn nullptr;\n}\n")

    def test_each_call_gets_a_labelled_site_and_only_real_pushes_count(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_batch_census.py"))
        out, labels = mod["patch_source"](self.synthetic(mod, 10))
        self.assertEqual(labels[:3], ["draw lists", "material", "rect: texture"])
        self.assertEqual(len(labels), 12)
        self.assertIn("_new_batch(batch_broken, 1)", out)
        self.assertIn("_new_batch(r_batch_broken, 11)", out)
        self.assertNotIn("_new_batch(r_batch_broken);", out)
        # The count sits after the early return, so a batch that was already fresh is not counted.
        definition = out[out.index(mod["DEF_NEW"]):]
        self.assertLess(definition.index("return nullptr;"), definition.index("droiddeck_canvas_batch_count(p_droiddeck_site);"))
        self.assertIn("droiddeck_canvas_batch_count(0);", out[out.index(mod["RENDER_ITEMS"]):])
        # The helpers go after the include block, before any code.
        self.assertLess(out.index('#include "core/math/geometry_2d.h"'), out.index("droiddeck_canvas_batch_labels[]"))
        self.assertLess(out.index("droiddeck_canvas_batch_labels[]"), out.index("static int x;"))
        self.assertIn("DROIDDECK_NATIVE_RENDER_CENSUS", out)
        self.assertEqual(mod["patch_header"]("x " + mod["DECL"] + " y"), "x " + mod["DECL_NEW"] + " y")
        with self.assertRaises(SystemExit):
            mod["patch_source"](self.synthetic(mod, 3))  # too few sites: the source changed under us
        with self.assertRaises(SystemExit):
            mod["patch_header"]("nothing")
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if godot:  # against the real 4.5.1 source when it is at hand
            real = (Path(godot) / "servers/rendering/renderer_rd/renderer_canvas_render_rd.cpp").read_text()
            out, labels = mod["patch_source"](real)
            self.assertIn("rect: texture", labels)
            self.assertIn("material", labels)
            self.assertFalse([l for l in labels if l.startswith("line ") or ": line " in l], labels)


class CanvasReorderPatchTest(unittest.TestCase):
    """Canvas item reordering (off unless DROIDDECK_NATIVE_CANVAS_REORDER is set) runs before a draw list records
    its items, only without 2D lights, and composes with the batch census patch."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_reorder_runs_first_in_the_draw_list_and_needs_its_anchor(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_reorder.py"))
        src = "int a;\n" + mod["RENDER_ITEMS"] + "int x) {\n\tint first;\n}\n"
        out = mod["patch_source"](src)
        body = out[out.index(mod["RENDER_ITEMS"]):]
        self.assertLess(body.index("droiddeck_canvas_reorder(items"), body.index("int first;"))
        self.assertIn("p_lights == nullptr && !using_directional_lights", body)
        self.assertLess(out.index("static void droiddeck_canvas_reorder("), out.index(mod["RENDER_ITEMS"]))
        self.assertIn("DROIDDECK_NATIVE_CANVAS_REORDER", out)
        with self.assertRaises(SystemExit):
            mod["patch_source"]("nothing")
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if godot:  # after the census patch, against the real 4.5.1 source, as CI applies them
            census = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_batch_census.py"))
            real = (Path(godot) / "servers/rendering/renderer_rd/renderer_canvas_render_rd.cpp").read_text()
            both = mod["patch_source"](census["patch_source"](real)[0])
            body = both[both.index(mod["RENDER_ITEMS"]):]
            self.assertLess(body.index("droiddeck_canvas_reorder(items"), body.index("droiddeck_canvas_batch_count(0);"))


class CanvasPolyBatchPatchTest(unittest.TestCase):
    """Small plain polygons become batched three-point primitives (off unless DROIDDECK_NATIVE_CANVAS_POLY_BATCH is
    set); the census labels the new batch starts, and the reorder treats a convertible polygon as batchable."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_conversion_is_wired_into_request_free_and_record_and_needs_every_anchor(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_poly_batch.py"))
        src = ("int a;\n" + mod["REQUEST"] + "int x) {\n" + mod["STORED"] + "}\n" + mod["FREE"] + "}\n" +
               "switch (c->type) {\n" + mod["RECORD"] + "\t\t\t\tr_current_batch = _new_batch(r_batch_broken);\n}\n")
        out = mod["patch_source"](src)
        self.assertLess(out.index("struct DroiddeckPolyCpu"), out.index(mod["REQUEST"]))
        self.assertIn("droiddeck_poly_remember(id,", out)
        self.assertLess(out.index(mod["FREE"]), out.index("droiddeck_poly_cpu.erase(p_polygon);"))
        record = out[out.index(mod["RECORD"]):]
        self.assertLess(record.index("droiddeck_poly_lookup("), record.index("r_current_batch = _new_batch(r_batch_broken);\n}"))
        for guard in ("RS::PRIMITIVE_TRIANGLES", "!use_linear_colors", "light_count == 0", "material.is_valid()"):
            self.assertIn(guard, record.replace("->material).is_valid()", "material.is_valid()"))
        self.assertIn("DROIDDECK_NATIVE_CANVAS_POLY_BATCH", out)
        for broken in ("nothing", src.replace(mod["FREE"], "")):
            with self.assertRaises(SystemExit):
                mod["patch_source"](broken)

    def test_census_labels_the_converted_batch_starts(self):
        census = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_batch_census.py"))
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if not godot:
            self.skipTest("DROIDDECK_GODOT_SRC not set")
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_poly_batch.py"))
        reorder = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_reorder.py"))
        real = (Path(godot) / "servers/rendering/renderer_rd/renderer_canvas_render_rd.cpp").read_text()
        out, labels = census["patch_source"](mod["patch_source"](real))
        self.assertIn("polygon: as triangles, command type", labels)
        self.assertIn("polygon: as triangles, texture", labels)
        out = reorder["patch_source"](out)
        self.assertLess(out.index("#define DROIDDECK_POLY_BATCH 1"), out.index("#ifdef DROIDDECK_POLY_BATCH"))


class CanvasTextureSlotsPatchTest(unittest.TestCase):
    """Several textures per 2D batch (off unless DROIDDECK_NATIVE_CANVAS_TEXTURE_SLOTS is set): the shader picks
    the texture from flags bits 29..31 only when compiled with the define, every texture break may join instead,
    and the cache key holds the slot textures."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_shader_and_header(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_texture_slots.py"))
        inc = mod["patch_shader_uniforms"]("x\n" + mod["UNIFORMS_ANCHOR"])
        self.assertIn("#ifdef DROIDDECK_TEXTURE_SLOTS\n", inc)
        self.assertIn("binding = 5) uniform texture2D droiddeck_slot_textures[7];", inc)
        frag = mod["patch_shader"]("{\n" + mod["SAMPLE"] + "}\n")
        self.assertIn("#else\n" + mod["SAMPLE"] + "#endif\n", frag)
        self.assertEqual(frag.count("droiddeck_slot_textures["), 7)
        head = mod["patch_header"](mod["TEX_INFO"] + mod["BATCH_END"] + mod["KEY_FIELDS"] + mod["KEY_EQ"] + mod["KEY_HASH"])
        self.assertIn("RID droiddeck_slots[7];\n\t\tuint32_t droiddeck_slot_count = 0;", head)
        self.assertIn("droiddeck_slots[i] != p_val.droiddeck_slots[i]", head)
        self.assertIn("droiddeck_slots[i].get_id()", head)
        self.assertIn("TextureInfo *tex_info = nullptr;", head)
        with self.assertRaises(SystemExit):
            mod["patch_header"]("nothing")
        with self.assertRaises(SystemExit):
            mod["patch_shader"]("nothing")
        # Flag bits 29..31 must be free: the shadow mask ends at bit 28.
        self.assertEqual(mod["SLOT_SHIFT"], 29)

    def test_real_source_every_break_may_join_and_census_still_labels_them(self):
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if not godot:
            self.skipTest("DROIDDECK_GODOT_SRC not set")
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_texture_slots.py"))
        poly = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_poly_batch.py"))
        census = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_batch_census.py"))
        rd = Path(godot) / "servers/rendering/renderer_rd"
        mod["patch_shader"]((rd / "shaders/canvas.glsl").read_text())
        mod["patch_shader_uniforms"]((rd / "shaders/canvas_uniforms_inc.glsl").read_text())
        mod["patch_header"]((rd / "renderer_canvas_render_rd.h").read_text())
        out, joined = mod["patch_source"](poly["patch_source"]((rd / "renderer_canvas_render_rd.cpp").read_text()))
        # rect, nine-patch, primitive, polygon as triangles and the debug redraw; never the unbatched polygon.
        self.assertEqual(joined, 5)
        self.assertEqual(out.count("!droiddeck_slot_join(r_current_batch, tex_info)"), joined)
        self.assertEqual(out.count("r_current_batch->tex_info = tex_info;\n"), out.count("droiddeck_slot_reset(r_current_batch);"))
        self.assertIn("droiddeck_slot_info = nullptr; // texture infos are freed here", out)
        self.assertIn("state.batch_texture_uniforms.resize(droiddeck_canvas_slots_max() ? 6 : 5);", out)
        _, labels = census["patch_source"](out)
        self.assertIn("rect: texture", labels)
        self.assertIn("polygon: as triangles, texture", labels)


class CanvasQuadMergePatchTest(unittest.TestCase):
    """Rects batch with nine-patches (off unless DROIDDECK_NATIVE_CANVAS_QUAD_MERGE is set): only instances marked
    as nine-patches get the margin mapping, and rects record into nine-patch batches."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_patches_shader_and_source(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_quad_merge.py"))
        frag = mod["patch_shader"]("a\n" + mod["NP_BEGIN"] + "\tuv = vec2(1);\n" + mod["NP_END"] + "\n#endif\n")
        self.assertIn("#ifdef USE_NINEPATCH\n#ifdef DROIDDECK_QUAD_MERGE\n", frag)
        self.assertLess(frag.index("if (draw_data.pad.x != 0.0) {"), frag.index("int draw_center = 2;"))
        self.assertLess(frag.index(mod["NP_END"]), frag.index("\t}\n#endif\n\n#endif"))
        src = mod["INCLUDE"] + mod["DEFINES"] + mod["RECT"] + mod["NP_MARGIN"]
        out = mod["patch_source"](src)
        self.assertIn("command_type != droiddeck_rect_batch_type()", out)
        self.assertIn("instance_data->pad[0] = 1.0;", out)
        self.assertIn("DROIDDECK_NATIVE_CANVAS_QUAD_MERGE", out)
        for broken in ("nothing", src.replace(mod["RECT"], "")):
            with self.assertRaises(SystemExit):
                mod["patch_source"](broken)

    def test_real_source(self):
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if not godot:
            self.skipTest("DROIDDECK_GODOT_SRC not set")
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_quad_merge.py"))
        rd = Path(godot) / "servers/rendering/renderer_rd"
        mod["patch_shader"]((rd / "shaders/canvas.glsl").read_text())
        mod["patch_source"]((rd / "renderer_canvas_render_rd.cpp").read_text())


class CanvasGroupHoistPatchTest(unittest.TestCase):
    """Canvas group contents drawn ahead into one back-buffer pass (off unless DROIDDECK_NATIVE_CANVAS_GROUP_HOIST is
    set): every anchor is patched once, the back buffer starts cleared, clip groups composite unpremultiplied, and a
    run waits for earlier owners before clearing the back buffer again."""

    ROOT = Path(__file__).resolve().parents[2]

    def mod(self):
        return runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_group_hoist.py"))

    def test_patches_every_anchor(self):
        mod = self.mod()
        src = "".join(mod[k] for k in ("INCLUDE", "CTOR", "DTOR", "LOOP", "GROUP", "FRAME_END", "MATERIAL", "BACKBUFFER"))
        out = mod["patch_source"](src)
        self.assertIn("DROIDDECK_NATIVE_CANVAS_GROUP_HOIST", out)
        self.assertIn("COLOR.a *= c.a;", out)
        self.assertIn("clear_color = Color(0, 0, 0, 0);", out)
        self.assertIn("droiddeck_hoisted_owners.clear();", out)
        self.assertLess(out.index("bool earlier_owner = false;"), out.index("droiddeck_bb_clear_next = true;"))
        self.assertLess(out.index("droiddeck_skip_owner = nullptr; // Kestrel"), out.index("while (ci) {"))
        self.assertLess(out.index("if (droiddeck_skip_owner != nullptr) {"), out.index(mod["GROUP"]))
        for broken in ("nothing", src.replace(mod["GROUP"], ""), src + mod["MATERIAL"]):
            with self.assertRaises(SystemExit):
                mod["patch_source"](broken)

    def test_real_source(self):
        godot = os.environ.get("DROIDDECK_GODOT_SRC")
        if not godot:
            self.skipTest("DROIDDECK_GODOT_SRC not set")
        mod = self.mod()
        rd = Path(godot) / "servers/rendering/renderer_rd"
        out = mod["patch_source"]((rd / "renderer_canvas_render_rd.cpp").read_text())
        self.assertEqual(out.count("Kestrel (canvas_group_hoist.py)"), 5)


class CanvasTraceTest(unittest.TestCase):
    """The canvas trace hooks request, free and canvas_render_items, is off without DROIDDECK_NATIVE_CANVAS_TRACE,
    and writes no vertices or pixels; it applies in either order with polygon batching."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_hooks_every_anchor_and_stays_off_by_default(self):
        mod = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_trace.py"))
        poly = runpy.run_path(str(self.ROOT / "tools/native-engine/patches/canvas_poly_batch.py"))
        src = ("int a;\n" + mod["REQUEST"] + "int x) {\n" + mod["STORED"] + "}\n" + mod["FREE"] + "}\n" +
               mod["RENDER"] + "}\nswitch (c->type) {\n" + poly["RECORD"] + "}\n")
        out = mod["patch_source"](src)
        self.assertLess(out.index("droiddeck_canvas_trace(RID p_target"), out.index(mod["REQUEST"]))
        self.assertIn("droiddeck_trace_remember_polygon(id,", out)
        self.assertIn("droiddeck_trace_polygons.erase(p_polygon);", out)
        self.assertIn(mod["RENDER"] + "\tdroiddeck_canvas_trace(", out)
        self.assertIn("DROIDDECK_NATIVE_CANVAS_TRACE", out)
        # Polygons, meshes: bounds and counts only.
        helpers = mod["HELPERS"]
        self.assertNotIn("p_points[", helpers)
        self.assertNotIn("get_data", helpers)
        both = poly["patch_source"](out)
        self.assertEqual(both.count("droiddeck_poly_remember(id,"), 1)
        self.assertEqual(both.count("droiddeck_trace_remember_polygon(id,"), 1)
        mod["patch_source"](poly["patch_source"](src))
        for broken in ("nothing", src.replace(mod["RENDER"], "")):
            with self.assertRaises(SystemExit):
                mod["patch_source"](broken)


class BatchCompareTest(unittest.TestCase):
    """compare.py judges each arm's frame against stock and reads draws and Vulkan call counts from the logs."""

    COMPARE = runpy.run_path(str(Path(__file__).resolve().parents[1] / "native-engine/batchtest/compare.py"))

    def write(self, d, name, pixels, draws, vk=None):
        Path(d, name + ".raw").write_bytes((2).to_bytes(4, "little") + (1).to_bytes(4, "little") + bytes(pixels))
        log = "BATCHTEST %s draws %d size 2x1\n" % (name, draws)
        if vk:
            log += ("droiddeck-vkcount: window 1.0s 60 frames 60.0 fps | per frame: 1.0 submits 1.0 cmdbufs %d draws (0 indirect) "
                    "0.0 dispatches 5 pipeline-binds %d set-binds 9 push-consts 3.0 passes 18.0 barriers 0.0 copies | ms/frame:\n" % vk)
        Path(d, name + ".log").write_text(log)

    def test_rules_and_draw_counts(self):
        base = [10, 20, 30, 255, 40, 50, 60, 255]
        with tempfile.TemporaryDirectory() as d:
            self.write(d, "stock-a", base, 10, (10, 14))
            self.write(d, "stock-b", base, 10)
            self.write(d, "same", base, 6, (6, 9))
            self.write(d, "near", [12] + base[1:], 5)
            self.assertEqual(self.COMPARE["main"]([d, "same=exact", "near=max=3", "--fewer", "same<stock-a", "--fewer", "near<=same"]), 0)
            self.assertEqual(self.COMPARE["main"]([d, "near=exact"]), 1)
            self.assertEqual(self.COMPARE["main"]([d, "same=exact", "--fewer", "stock-a<same"]), 1)
            self.assertEqual(self.COMPARE["vkcount"](d, "same")["set-binds"], 9)
            self.assertAlmostEqual(self.COMPARE["phone_ms"](self.COMPARE["vkcount"](d, "stock-a")), 0.72)
            self.assertIsNone(self.COMPARE["vkcount"](d, "near"))

    def test_nondeterministic_stock_passes_with_a_warning(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d, "stock-a", [1] * 8, 4)
            self.write(d, "stock-b", [2] * 8, 4)
            self.write(d, "x", [200] * 8, 4)
            self.assertEqual(self.COMPARE["main"]([d, "x=exact"]), 0)


if __name__ == "__main__":
    unittest.main()


class EnginePinTest(unittest.TestCase):
    """The app build embeds the newest gated engine release; nobody copies its tag (Nov 15: -65 was published and
    the app still pinned -54, so the phone skipped the native engine at the Steam gap)."""
    SHA = "a" * 64
    URL = "https://github.com/renxida/DroidDeck/releases/download/native-engine-70/native-engine.tar.zst"
    RESOLVE = runpy.run_path(str(Path(__file__).resolve().parents[1] / "native-engine/resolve_component.py"))

    def pin(self, **over):
        pin = {"tag": "native-engine-70", "url": self.URL, "sha256": self.SHA, "api": NG["COMPONENT_API"]}
        pin.update(over)
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(pin, f)
        self.addCleanup(os.remove, f.name)
        return f.name

    def test_the_wrapper_takes_the_build_pin_and_falls_back_to_its_own(self):
        fallback = {"tag": "native-engine-1", "url": "u", "sha256": "s"}
        self.assertEqual(NG["pinned_component"](self.pin(), fallback),
                         {"tag": "native-engine-70", "url": self.URL, "sha256": self.SHA})
        for bad in ({"api": NG["COMPONENT_API"] + 1}, {"tag": "native-engine-71"}, {"sha256": "x"},
                    {"url": "https://example.com/native-engine-70/native-engine.tar.zst"}):
            self.assertIs(NG["pinned_component"](self.pin(**bad), fallback), fallback, bad)
        self.assertIs(NG["pinned_component"]("/nonexistent/native-engine.json", fallback), fallback)

    def release(self, n, manifest=True, gate="passed", api=None, digest=None, draft=False):
        tag = f"native-engine-{n}"
        sha = hashlib.sha256(tag.encode()).hexdigest()
        assets = [{"name": "native-engine.tar.zst", "digest": "sha256:" + (digest or sha),
                   "browser_download_url": f"https://github.com/renxida/DroidDeck/releases/download/{tag}/native-engine.tar.zst"}]
        if manifest:
            assets.append({"name": "component.json", "browser_download_url": "m:" + tag,
                           "_body": {"tag": tag, "sha256": sha, "gate": gate,
                                     "api": NG["COMPONENT_API"] if api is None else api}})
        return {"tag_name": tag, "draft": draft, "assets": assets}

    def resolve(self, listing, want=None):
        bodies = {a["browser_download_url"]: a.get("_body") for r in listing or [] for a in r["assets"]}
        committed = {"tag": "native-engine-65", "url": "c", "sha256": "c" * 64}
        return self.RESOLVE["resolve"](committed, NG["COMPONENT_API"], listing, lambda url, token=None: bodies[url],
                                       want=want)

    def test_the_newest_gated_compatible_release_wins(self):
        pin, source, skipped = self.resolve([self.release(80, gate="failed"), self.release(79, api=99),
                                             self.release(78, digest="b" * 64), self.release(77, draft=True),
                                             self.release(76, manifest=False), self.release(70), self.release(66)])
        self.assertEqual((pin["tag"], source), ("native-engine-70", "release"))
        self.assertEqual(len(skipped), 5)

    def test_the_committed_pin_stands_when_nothing_newer_qualifies(self):
        pin, source, _ = self.resolve([self.release(64), self.release(70, manifest=False)])
        self.assertEqual((pin["tag"], source), ("native-engine-65", "committed"))
        pin, source, _ = self.resolve(None)
        self.assertEqual((pin["tag"], source), ("native-engine-65", "committed (releases unreadable)"))

    def test_an_exact_tag_is_that_release_or_an_error(self):
        self.assertEqual(self.resolve([self.release(70), self.release(66)], want="native-engine-66")[0]["tag"],
                         "native-engine-66")
        self.assertEqual(self.resolve([self.release(65, manifest=False)], want="native-engine-65")[0]["tag"],
                         "native-engine-65")
        with self.assertRaises(SystemExit):
            self.resolve([self.release(70, gate="failed")], want="native-engine-70")


# A stand-in engine for the prestart tests: does what the Steam bridge does at its first Steam call (held, wait for
# release, apply it), then reports what it sees, draws a "frame" and exits with 3.
FAKE_HELD_ENGINE = r"""
import os, sys, time
print("booting", flush=True)
d = os.environ.get("DROIDDECK_NATIVE_HOLD_DIR")
if d:
    with open(os.path.join(d, "held"), "w") as f:
        f.write(f"{os.getpid()} SteamAPI_Init")
    while not os.path.exists(os.path.join(d, "release")):
        time.sleep(0.02)
    for e in open(os.path.join(d, "release"), "rb").read().split(b"\0"):
        if e:
            k, eq, v = e.decode().partition("=")
            if eq:
                os.environ[k] = v
            else:
                os.environ.pop(k, None)
    os.rename(os.path.join(d, "release"), os.path.join(d, "released"))
    print("steam-bridge: released", flush=True)
print("appid", os.environ.get("SteamAppId", "(unset)"), "gone", os.environ.get("GONE", "(unset)"), flush=True)
print("Project FPS: 60 (16.67 mspf)", flush=True)
time.sleep(float(os.environ.get("FAKE_RUN_S", "0.3")))
sys.exit(3)
"""

PRESTART_RUNNER = r"""
import json, runpy, sys
ng = runpy.run_path(sys.argv[1])
sys.exit(ng["prestart"]([sys.executable, "-u", sys.argv[2]], json.loads(sys.argv[4]), sys.argv[3],
                        {"argv": ["--out", "x"], "environ": json.loads(sys.argv[4]), "refusal": None, "report": {}}))
"""


class PrestartTest(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.d)
        self.hold = self.d / "hold"
        self.hold.mkdir()
        (self.d / "engine.py").write_text(FAKE_HELD_ENGINE)
        self.env = {k: v for k, v in os.environ.items() if k not in ("SteamAppId", "DROIDDECK_NATIVE_HOLD_DIR")}
        self.env["GONE"] = "here"

    def start(self, **extra):
        env = dict(self.env, **extra)
        p = subprocess.Popen([sys.executable, "-c", PRESTART_RUNNER, str(BIN / "droiddeck-native-godot"),
                              str(self.d / "engine.py"), str(self.hold), json.dumps(env)],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(p.stderr.close)
        self.addCleanup(p.stdout.close)
        self.addCleanup(lambda: p.poll() is None and (p.kill(), p.wait()))
        end = time.monotonic() + 15
        while not (self.hold / "held").exists():
            self.assertIsNone(p.poll(), p.stderr.read() if p.poll() is not None else "")
            self.assertLess(time.monotonic(), end, "the engine never held")
            time.sleep(0.02)
        return p

    def launch_env(self, **changes):
        env = dict(self.env)
        for k, v in changes.items():
            if v is None:
                env.pop(k, None)
            else:
                env[k] = v
        return env

    def test_a_launch_takes_the_held_engine_with_steams_environment_and_its_exit_code(self):
        p = self.start()
        out = io.StringIO()
        with unittest.mock.patch.dict(NG["adopt"].__globals__, {"log": lambda m: out.write("LOG " + m + "\n")}):
            took = NG["adopt"](str(self.hold), ["--out", "x"], self.launch_env(SteamAppId="2868840", GONE=None), out=out)
            self.assertIsNotNone(took, out.getvalue())
            rc = NG["watch"](None, None, out=out, proc=took[0], first_frame_s=15)
        self.assertEqual(3, rc, out.getvalue())
        text = out.getvalue()
        self.assertIn("booting", text)  # what it printed before the launch, passed on
        self.assertIn("appid 2868840 gone (unset)", text)  # the release applied before the Steam call went on
        self.assertIn("released with 2 environment changes", text)
        self.assertIn("first frame after", text)
        self.assertEqual(3, p.wait(timeout=15))
        self.assertEqual("3", (self.hold / "exit").read_text())

    def test_other_arguments_stop_the_held_engine(self):
        p = self.start()
        with unittest.mock.patch.dict(NG["adopt"].__globals__, {"log": lambda m: None}):
            self.assertIsNone(NG["adopt"](str(self.hold), ["--out", "y"], self.launch_env()))
        p.wait(timeout=15)
        self.assertTrue((self.hold / "exit").exists())

    def test_a_variable_read_at_start_that_differs_stops_the_held_engine(self):
        p = self.start()
        logged = []
        with unittest.mock.patch.dict(NG["adopt"].__globals__, {"log": logged.append}):
            self.assertIsNone(NG["adopt"](str(self.hold), ["--out", "x"], self.launch_env(DOTNET_TieredPGO="0")))
        p.wait(timeout=15)
        self.assertIn("DOTNET_TieredPGO", " ".join(logged))

    def test_the_engine_stops_when_the_launch_that_took_it_is_gone(self):
        p = self.start(FAKE_RUN_S="60")
        owner = subprocess.Popen(["sleep", "60"])
        reader = os.open(self.hold / "out", os.O_RDONLY | os.O_NONBLOCK)
        self.addCleanup(os.close, reader)
        (self.hold / "owner").write_text(str(owner.pid))
        (self.hold / "release").write_bytes(b"")
        time.sleep(0.5)
        self.assertIsNone(p.poll())
        owner.kill()
        owner.wait()
        p.wait(timeout=15)
        self.assertIn("is gone; stopping the engine", p.stderr.read())

    def test_no_prestart_means_a_usual_start(self):
        self.assertIsNone(NG["adopt"](str(self.d / "nothing"), ["--out", "x"], self.launch_env(), wait_s=0))

    def test_release_entries(self):
        release, startup, stale = NG["env_release"](
            {"A": "1", "B": "2", "DOTNET_X": "1", "SDL_GAMECONTROLLERCONFIG": "a", "PWD": "/"},
            {"A": "1", "C": "3", "DOTNET_X": "2", "SDL_GAMECONTROLLERCONFIG": "b", "PWD": "/x",
             "DROIDDECK_NATIVE_PRESTART": "1"})
        self.assertEqual(b"B\0C=3\0DROIDDECK_NATIVE_PRESTART=1\0SDL_GAMECONTROLLERCONFIG=b\0", release)
        self.assertEqual(["DOTNET_X"], startup)
        self.assertEqual(["SDL_GAMECONTROLLERCONFIG"], stale)

    def test_prestart_dir_is_per_launch_folder(self):
        a, b = NG["prestart_dir"]("/x/a", "/c"), NG["prestart_dir"]("/x/b", "/c")
        self.assertNotEqual(a, b)
        self.assertEqual(a, NG["prestart_dir"]("/x/a/", "/c"))
