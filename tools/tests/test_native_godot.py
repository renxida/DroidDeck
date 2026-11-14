import io
import json
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

    def test_ready_to_run_images_are_only_relabelled(self):
        # arm64 .NET refuses an R2R image whose R2R header is cleared or that is marked IL-only ("Bad IL
        # format"), and ignores Windows x64 R2R code, so the only change is the machine label.
        for ilonly in (False, True):
            src = pe(True, machine=0x8664, ilonly=ilonly, r2r=True)
            img, note = NG["portable_image"](src)
            before, after = NG["pe_info"](src), NG["pe_info"](img)
            self.assertEqual(0xAA64, after["machine"])
            self.assertEqual((before["flags"], before["native_header"]), (after["flags"], after["native_header"]))
            self.assertIn("ReadyToRun x64 code kept", note)
            changed = [i for i in range(len(src)) if src[i] != img[i]]
            self.assertTrue(set(changed) <= {before["machine_at"], before["machine_at"] + 1})

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
        self.assertIn("ReadyToRun x64 code kept", r["patched"]["Odd.dll"])
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

    def test_watch_passes_the_exit_code_after_a_first_frame(self):
        rc, out, _ = self.run_watch("print('booting')\nprint('Project FPS: 60 (16.67 mspf)', flush=True)\n"
                                    "print('Project FPS: 59 (16.95 mspf)')\nprint('bye')\nsys.exit(3)")
        self.assertEqual(3, rc)
        self.assertEqual(["booting", "Project FPS: 60 (16.67 mspf)", "bye"], out.splitlines())  # second FPS line thinned

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
        with self.assertRaises(SystemExit):
            NG["fetch"]({"tag": "", "url": "", "sha256": ""}, str(cache), opener)

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
        stripped = (self.data / "0Harmony.dll").read_bytes()
        rc, log = self.job(r2r)
        self.assertEqual(0, rc)
        self.assertIn("compiled 2 of 2", log)
        calls = self.crossgen2_calls()
        self.assertEqual(2, len(calls))
        harmony = next(c for c in calls if c["args"][0].endswith("in-0Harmony.dll"))
        args = harmony["args"]
        self.assertEqual(stripped.hex(), harmony["input"])  # crossgen2 gets the stripped, portable image
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


if __name__ == "__main__":
    unittest.main()
