import json
import os
from pathlib import Path
import runpy
import struct
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
NG = runpy.run_path(str(BIN / "droiddeck-native-godot"))


def pe(managed):
    """A minimal PE32+ image; managed ones carry a CLI data directory entry."""
    opt = bytearray(240)
    struct.pack_into("<H", opt, 0, 0x20B)
    if managed:
        struct.pack_into("<II", opt, 112 + 14 * 8, 0x2008, 0x48)
    coff = struct.pack("<HHIIIHH", 0xAA64, 1, 0, 0, 0, len(opt), 0x22)
    head = bytearray(0x80)
    head[:2] = b"MZ"
    struct.pack_into("<I", head, 0x3C, 0x80)
    return bytes(head) + b"PE\0\0" + coff + bytes(opt) + b"\0" * 64


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
        (win / "0Harmony.dll").write_bytes(pe(True))
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
        self.assertFalse(any((data / n).is_symlink() for n in names))
        self.assertIn("steam_api64.dll", r["skipped"])
        self.assertTrue((out / "sts2").is_file())
        # Composing again is idempotent.
        NG["compose"](str(self.game), str(self.kit), str(self.engine), str(out), "SlayTheSpire2.exe")
        self.assertEqual(names, sorted(os.listdir(data)))

    def test_run_passes_engine_args(self):
        out = self.root / "launch"
        seen = []
        NG["os"].execv, real = (lambda path, args: seen.append(args)), NG["os"].execv
        try:
            NG["main"](["run", "--game-dir", str(self.game), "--kit", str(self.kit), "--engine", str(self.engine),
                        "--out", str(out), "--exe", "SlayTheSpire2.exe", "--", "--headless", "--verbose"])
        finally:
            NG["os"].execv = real
            os.chdir(Path(__file__).resolve().parent)
        self.assertEqual([str(out / "sts2"), "--main-pack", str(self.game / "SlayTheSpire2.pck"), "--headless", "--verbose"], seen[0])

    def test_relative_paths_become_absolute(self):
        old = os.getcwd()
        os.chdir(self.root)
        try:
            r = NG["compose"]("Slay the Spire 2", "kit", "godot.template", "launch", "SlayTheSpire2.exe")
        finally:
            os.chdir(old)
        self.assertTrue(os.path.isabs(r["pack"]) and os.path.isabs(r["engine"]))

    def test_embedded_pack(self):
        (self.game / "SlayTheSpire2.pck").unlink()
        self.assertTrue(NG["find_pack"](str(self.game), "SlayTheSpire2.exe").endswith("SlayTheSpire2.exe"))


if __name__ == "__main__":
    unittest.main()
