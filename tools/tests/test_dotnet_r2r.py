import json
from pathlib import Path
import runpy
import struct
import subprocess
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
R2R = runpy.run_path(str(BIN / "droiddeck-dotnet-r2r"))
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))


def pe(ilonly=True, r2r=False, managed=True, payload=b""):
    """A minimal PE32+ image: one section holding the CLR header and, for ReadyToRun, its native header."""
    section_rva, raw = 0x2000, 0x200
    optional = bytearray(240)
    struct.pack_into("<H", optional, 0, 0x20B)
    if managed:
        struct.pack_into("<II", optional, 112 + 14 * 8, section_rva, 72)
    header = b"PE\0\0" + struct.pack("<HHIIIHH", 0x8664, 1, 0, 0, 0, len(optional), 0x22) + bytes(optional)
    table = b".text\0\0\0" + struct.pack("<IIIIIIHHI", 0x200, section_rva, 0x200, raw, 0, 0, 0, 0, 0)
    dos = bytearray(0x40)
    dos[:2] = b"MZ"
    struct.pack_into("<I", dos, 0x3C, 0x40)
    image = bytearray(bytes(dos) + header + table)
    image += bytes(raw - len(image))
    cor = bytearray(72)
    struct.pack_into("<I", cor, 16, 1 if ilonly else 0)
    if r2r:
        struct.pack_into("<I", cor, 64, section_rva + 72)
    image += bytes(cor) + (struct.pack("<I", 0x00525452) if r2r else b"\0\0\0\0") + payload
    image += bytes(0x400 - len(image) % 0x400)
    return bytes(image)


def game(folder, major="9.0.11", own=("Game.dll",), framework=("System.Runtime.dll",), deps=True):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    for name in own:
        (folder / name).write_bytes(pe(payload=name.encode()))
    for name in framework:
        (folder / name).write_bytes(pe(ilonly=False, r2r=True))
    (folder / "coreclr.dll").write_bytes(pe(managed=False))
    if deps:
        key = "runtimepack.Microsoft.NETCore.App.Runtime.win-x64/" + major
        data = {"targets": {".NETCoreApp,Version=v9.0/win-x64": {key: {"runtime": {n: {} for n in framework}}}}}
        (folder / "Game.deps.json").write_text(json.dumps(data))
    return folder


def fake_crossgen(command, **kwargs):
    out = Path(command[command.index("-o") + 1])
    out.write_bytes(pe(ilonly=False, r2r=True, payload=Path(command[1]).read_bytes()[-64:]))
    return subprocess.CompletedProcess(command, 0, b"", None)


class PeTest(unittest.TestCase):
    def test_reads_il_only_and_ready_to_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.dll"
            for ilonly, r2r in ((True, False), (False, True), (False, False)):
                path.write_bytes(pe(ilonly=ilonly, r2r=r2r))
                self.assertEqual(R2R["pe_info"](path), {"ilonly": ilonly, "r2r": r2r})
            path.write_bytes(pe(managed=False))
            self.assertIsNone(R2R["pe_info"](path))
            path.write_bytes(b"not a dll")
            self.assertIsNone(R2R["pe_info"](path))


class R2RTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.cache = R2R["Cache"](self.root / "cache")

    def tearDown(self):
        self.tmp.cleanup()

    def test_only_the_games_own_il_assemblies_are_candidates(self):
        folder = game(self.root / "g", own=("Game.dll", "Lib.dll"), framework=("System.Runtime.dll", "System.Facade.dll"))
        (folder / "System.Facade.dll").write_bytes(pe())
        self.assertEqual(R2R["runtime_pack"](folder)[0], 9)
        self.assertEqual(sorted(R2R["candidates"](folder)), ["Game.dll", "Lib.dll"])

    def test_runtimeconfig_and_names_when_deps_json_is_missing(self):
        folder = game(self.root / "g", deps=False, framework=("System.Runtime.dll", "System.Facade.dll"))
        (folder / "System.Facade.dll").write_bytes(pe())
        config = {"runtimeOptions": {"includedFrameworks": [{"name": "Microsoft.NETCore.App", "version": "9.0.7"}]}}
        (folder / "Game.runtimeconfig.json").write_text(json.dumps(config))
        self.assertEqual(R2R["runtime_major"](folder), 9)
        self.assertEqual(R2R["candidates"](folder), ["Game.dll"])

    def test_compile_needs_the_matching_runtime(self):
        folder = game(self.root / "g", major="8.0.3")
        run = mock.Mock(side_effect=fake_crossgen)
        self.assertEqual(R2R["compile_missing"](folder, self.cache, run=run, log=lambda _: None), ([], []))
        run.assert_not_called()

    def test_compile_then_swap_in_and_back(self):
        folder = game(self.root / "g")
        original = (folder / "Game.dll").read_bytes()
        run = mock.Mock(side_effect=fake_crossgen)
        logs = []
        self.assertEqual(R2R["compile_missing"](folder, self.cache, run=run, log=logs.append), (["Game.dll"], []))
        command = run.call_args[0][0]
        self.assertEqual(command[command.index("--targetos") + 1], "windows")
        self.assertEqual(command[command.index("--targetarch") + 1], "x64")
        self.assertIn(str(folder.resolve() / "System.Runtime.dll"), command)
        self.assertEqual(R2R["compile_missing"](folder, self.cache, run=run, log=logs.append), ([], []))
        self.assertEqual(run.call_count, 1)

        self.assertEqual(R2R["apply"](folder, True, self.cache), (["Game.dll"], []))
        self.assertTrue(R2R["pe_info"](folder / "Game.dll")["r2r"])
        self.assertIn("swapped_in=1: Game.dll", R2R["status"](folder, self.cache))
        self.assertEqual(R2R["apply"](folder, False, self.cache), (["Game.dll"], []))
        self.assertEqual((folder / "Game.dll").read_bytes(), original)
        self.assertEqual(self.cache.manifest(), {})

    def test_off_leaves_a_file_that_changed_since(self):
        folder = game(self.root / "g")
        R2R["compile_missing"](folder, self.cache, run=fake_crossgen, log=lambda _: None)
        R2R["apply"](folder, True, self.cache)
        updated = pe(payload=b"new version")
        (folder / "Game.dll").write_bytes(updated)
        self.assertEqual(R2R["apply"](folder, False, self.cache), ([], []))
        self.assertEqual((folder / "Game.dll").read_bytes(), updated)

    def test_a_failed_compile_keeps_nothing(self):
        folder = game(self.root / "g")

        def broken(command, **kwargs):
            Path(command[command.index("-o") + 1]).write_bytes(b"half")
            return subprocess.CompletedProcess(command, 1, b"error: boom", None)
        logs = []
        self.assertEqual(R2R["compile_missing"](folder, self.cache, run=broken, log=logs.append), ([], ["Game.dll"]))
        self.assertIn("boom", logs[-1])
        self.assertEqual(list(self.cache.root.glob("*.dll*")), [])
        self.assertEqual(R2R["apply"](folder, True, self.cache), ([], ["Game.dll"]))

    def test_shipped_ready_to_run_is_reported(self):
        folder = game(self.root / "g", own=())
        (folder / "Game.dll").write_bytes(pe(ilonly=False, r2r=True))
        text = R2R["status"](folder, self.cache)
        self.assertIn("the game's own: Game.dll", text)
        self.assertIn("game_il_only=0", text)


class GameEnvTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.exe = self.root / "Game.exe"
        self.exe.write_bytes(b"MZ")

    def tearDown(self):
        self.tmp.cleanup()

    def call(self, experiments=(), manifest=False, log_dir=True):
        env = {GAME_ENV["experiment_name"](n): "1" for n in experiments}
        if log_dir:
            env["DROIDDECK_GAME_LOG_DIR"] = str(self.root / "logs")
        run = mock.Mock(return_value=subprocess.CompletedProcess([], 0, b"", b"r2r: swapped in 1 (Game.dll)\n"))
        popen = mock.Mock()
        path = self.root / ("manifest.json" if manifest else "none.json")
        path.write_text("{}")
        with mock.patch.dict(GAME_ENV["dotnet_r2r"].__globals__, DOTNET_R2R_MANIFEST=str(self.root / "manifest.json")):
            if not manifest:
                (self.root / "manifest.json").unlink(missing_ok=True)
            notes = GAME_ENV["dotnet_r2r"](["proton", "waitforexitandrun", str(self.exe)], env, run=run, popen=popen)
        return notes, run, popen

    def test_only_dotnet_games(self):
        notes, run, popen = self.call(["dotnet-r2r"])
        self.assertEqual(notes, [])
        run.assert_not_called()
        popen.assert_not_called()

    def test_finds_the_runtime_one_folder_down(self):
        (self.root / "data_Game").mkdir()
        (self.root / "data_Game/coreclr.dll").write_bytes(b"MZ")
        self.assertEqual(GAME_ENV["dotnet_folder"](str(self.exe)), str(self.root / "data_Game"))

    def test_off_only_writes_the_status(self):
        (self.root / "coreclr.dll").write_bytes(b"MZ")
        notes, run, popen = self.call()
        run.assert_not_called()
        command = popen.call_args[0][0]
        self.assertEqual(command[:2], ["nice", "-n"])
        self.assertIn("status", command)
        self.assertEqual(command[-1], str(self.root / "logs/dotnet-r2r.txt"))
        _, _, popen = self.call(log_dir=False)
        popen.assert_not_called()

    def test_off_restores_after_an_earlier_swap(self):
        (self.root / "coreclr.dll").write_bytes(b"MZ")
        _, run, _ = self.call(manifest=True)
        self.assertIn("--off", run.call_args[0][0])

    def test_on_swaps_in_then_compiles_the_rest(self):
        (self.root / "coreclr.dll").write_bytes(b"MZ")
        notes, run, popen = self.call(["dotnet-r2r"])
        self.assertIn("--on", run.call_args[0][0])
        self.assertEqual(notes, ["dotnet-r2r: r2r: swapped in 1 (Game.dll)"])
        self.assertIn("compile", popen.call_args[0][0])
        self.assertIn("dotnet-r2r", GAME_ENV["EXPERIMENTS"])


if __name__ == "__main__":
    unittest.main()
