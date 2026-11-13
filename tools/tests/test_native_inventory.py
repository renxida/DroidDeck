import json
from pathlib import Path
import runpy
import struct
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
INV = runpy.run_path(str(BIN / "droiddeck-native-inventory"))


def pack(entries, version=3, embedded_prefix=b""):
    """A Godot pack in the layout the inventory reads; entries are (name, bytes)."""
    header_size = 4 + 4 + 16 + 8 + (8 if version >= 3 else 0) + 64
    blobs, offset = [], 0
    for _, data in entries:
        blobs.append((offset, data))
        offset += len(data)
    directory = struct.pack("<I", len(entries))
    for (name, data), (off, _) in zip(entries, blobs):
        raw = ("res://" + name).encode()
        raw += b"\0" * (-len(raw) % 4)
        directory += struct.pack("<I", len(raw)) + raw + struct.pack("<QQ", off, len(data)) + b"\0" * 16 + struct.pack("<I", 0)
    body = b"".join(d for _, d in blobs)
    if version >= 3:
        file_base = header_size
        dir_offset = header_size + len(body)
        head = b"GDPC" + struct.pack("<I4IQQ", version, 4, 5, 1, 2, file_base, dir_offset) + b"\0" * 64
        out = head + body + directory
    else:
        file_base = header_size + len(directory)
        head = b"GDPC" + struct.pack("<I4IQ", version, 4, 3, 0, 2, file_base) + b"\0" * 64
        out = head + directory + body
    if embedded_prefix:
        return embedded_prefix + out + struct.pack("<Q", len(out)) + b"GDPC"
    return out


def fsb5(samples, codec, data_size=1000):
    """An FSB5 (version 1, 0x3C header) whose samples are 44.1 kHz stereo Vorbis, setup ids 0xA1B2C3D4 and
    0x11223344 alternating, each with a rate chunk saying 48000 for the odd ones."""
    table = b""
    for i in range(samples):
        mode = 1 | (8 << 1) | (1 << 5) | ((i * 64 // 32) << 7) | (1000 << 34)
        crc = 0xA1B2C3D4 if i % 2 == 0 else 0x11223344
        chunks = []
        if i % 2:
            chunks.append((2, struct.pack("<I", 48000)))
        chunks.append((11, struct.pack("<I", crc) + b"\0" * 4))
        table += struct.pack("<Q", mode)
        for n, (kind, body) in enumerate(chunks):
            more = 1 if n + 1 < len(chunks) else 0
            table += struct.pack("<I", more | (len(body) << 1) | (kind << 25)) + body
    head = b"FSB5" + struct.pack("<6I", 1, samples, len(table), 0, data_size, codec) + b"\0" * 32
    return head + table + b"\x5a" * data_size


def bank():
    inner = b"LIST" + struct.pack("<I", 4 + 8 + 4) + b"PROJ" + b"EVNT" + struct.pack("<I", 4) + b"\0" * 4
    payload = fsb5(12, 15)
    snd = b"SND " + struct.pack("<I", len(payload)) + payload
    body = b"FEV " + inner + snd
    return b"RIFF" + struct.pack("<I", len(body)) + body


class NativeInventoryTest(unittest.TestCase):
    def build(self, version=3, embedded=False):
        skeleton = json.dumps({"skeleton": {"spine": "4.2.43"}, "bones": [{"name": "root"}, {"name": "a", "parent": "root", "inherit": "noScale"}],
                               "physics": [{"name": "hair", "bone": "a", "inertia": 0.5}],
                               "skins": [{"name": "default", "attachments": {"s": {"m": {"type": "mesh"}, "r": {}}}}],
                               "animations": {"idle": {"bones": {"a": {"rotate": [{"time": 0}]}}, "physics": {}}}}).encode()
        entries = [
            ("monsters/cultist.json", skeleton),
            ("monsters/jaw.skel", b"\x8a\x91hashbytes\x064.2.43\x00\x00rest"),
            ("monsters/jaw.atlas", b"jaw.png\nsize: 64,64\n"),
            ("monsters/other.atlas", b"other.png\n"),
            ("addons/fmod/fmod.gdextension", b'[configuration]\nentry_symbol = "fmod_library_init"\n'),
            ("banks/Master.strings.bank", b"RIFF\0\0\0\0FEV event:/sfx/attack\0event:/music/act1\0bus:/SFX\0"),
            ("banks/Master.bank", bank()),
            ("scenes/combat.scn", b"RSRC...SpineSprite...SpineAnimationState...FmodEventEmitter2D..."),
            ("monsters/jaw.skel.import", b'[remap]\nimporter="spine.skel"\ntype="SpineSkeletonFileResource"\n'),
        ]
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        game = Path(tmp.name) / "Slay the Spire 2"
        game.mkdir()
        data = pack(entries, version, embedded_prefix=b"MZ" + b"\0" * 100 if embedded else b"")
        (game / ("SlayTheSpire2.exe" if embedded else "SlayTheSpire2.pck")).write_bytes(data)
        return game, tmp

    def run_inventory(self, game, tmp):
        out = Path(tmp.name) / "report.json"
        self.samples = Path(tmp.name) / "samples.zip"
        self.assertEqual(0, INV["main"](["--game-dir", str(game), "--no-cache", "--json", str(out), "--samples", str(self.samples)]))
        return INV["read_report"](out.read_text())

    def test_reads_pack_skeletons_banks_and_scenes(self):
        for version, embedded in ((3, False), (2, False), (3, True)):
            with self.subTest(version=version, embedded=embedded):
                game, tmp = self.build(version, embedded)
                report = self.run_inventory(game, tmp)
                self.assertNotIn("error", report["pack"])
                self.assertEqual("4.5.1" if version >= 3 else "4.3.0", report["pack"]["godot"])
                skeletons = {s["name"]: s for s in report["skeletons"]}
                js = skeletons["monsters/cultist.json"]
                self.assertEqual("json", js["format"])
                self.assertEqual("4.2.43", js["version"])
                self.assertEqual(1, js["sections"]["physics"])
                self.assertIn("inherit", js["section_keys"]["bones"])
                self.assertEqual(1, js["attachment_types"]["mesh"])
                self.assertIn("physics", js["animation_keys"])
                binary = skeletons["monsters/jaw.skel"]
                self.assertEqual(("binary", "4.2.43"), (binary["format"], binary["version"]))
                banks = {b["name"]: b for b in report["banks"]}
                self.assertEqual(["event:/sfx/attack", "event:/music/act1", "bus:/SFX"], banks["banks/Master.strings.bank"]["paths"])
                fsb = banks["banks/Master.bank"]["fsb5"]
                self.assertEqual([("vorbis", 12)], [(f["codec"], f["samples"]) for f in fsb])
                self.assertEqual({"a1b2c3d4 44100 2": 6, "11223344 48000 2": 6}, fsb[0]["vorbis_setups"])
                self.assertIn("SND ", banks["banks/Master.bank"]["chunk_counts"])
                self.assertEqual(["addons/fmod/fmod.gdextension"], [g["name"] for g in report["gdextensions"]])
                self.assertIn("SpineSprite", report["resource_class_hits"])
                self.assertIn("FmodEventEmitter2D", report["resource_class_hits"])
                self.assertEqual({"spine.skel -> SpineSkeletonFileResource": 1}, report["importers"])
                summary = INV["summary"](report)
                self.assertEqual(2, summary["skeletons"])
                self.assertEqual({"vorbis": 12}, summary["audio_samples_by_codec"])
                self.assertEqual(2, summary["bank_events"])

    def test_samples_take_skeletons_atlases_and_banks_within_budget(self):
        import zipfile
        game, tmp = self.build()
        self.run_inventory(game, tmp)
        with zipfile.ZipFile(self.samples) as z:
            manifest = json.loads(z.read("manifest.json"))
            names = [f["name"] for f in manifest["files"]]
            self.assertEqual(sorted(["banks/Master.strings.bank", "banks/Master.bank", "monsters/cultist.json",
                                     "monsters/jaw.skel", "monsters/jaw.atlas", "monsters/jaw.skel.import"]), sorted(names))
            jaw = next(f for f in manifest["files"] if f["name"] == "monsters/jaw.skel")
            self.assertEqual(b"\x8a\x91hashbytes\x064.2.43\x00\x00rest", z.read(jaw["file"]))
        small = INV["pick_samples"](INV["game_sources"](str(game)), budget=40)
        self.assertLessEqual(sum(s.size for s in small), 40)

    def test_report_lines_stay_short_and_read_back(self):
        report = {"schema": 1, "pack": {"godot": "4.5.1"},
                  "banks": [{"name": "b", "paths": ["event:/sfx/%04d" % i for i in range(4000)],
                             "chunk_counts": {"C%03d" % i: i for i in range(900)}}],
                  "managed": {"disk:a.dll": {"literal_count": 3}}}
        lines = list(INV["report_lines"](report))
        self.assertTrue(all(len(line) <= INV["REPORT_LINE_MAX"] for line in lines))
        self.assertGreater(len(lines), 5)
        self.assertEqual(report, INV["read_report"]("\n".join(lines)))

    def test_imported_skeletons_pair_with_their_atlases(self):
        key = INV["stem_key"]
        self.assertEqual(key(".godot/imported/axe_bot.skel-c463f96be77e24ccb76061b5d3b68508.spskel"),
                         key(".godot/imported/axe_bot.atlas-0123456789abcdef0123456789abcdef.spatlas"))
        self.assertNotEqual(key(".godot/imported/axe_bot.skel-c463f96be77e24ccb76061b5d3b68508.spskel"),
                            key(".godot/imported/axe.atlas-0123456789abcdef0123456789abcdef.spatlas"))

    def test_a_bank_too_big_to_send_gives_the_front_of_its_fsb5(self):
        import zipfile
        game, tmp = self.build()
        old = INV["main"].__globals__["SAMPLE_FILE_MAX"]
        INV["main"].__globals__["SAMPLE_FILE_MAX"] = 300
        try:
            self.run_inventory(game, tmp)
        finally:
            INV["main"].__globals__["SAMPLE_FILE_MAX"] = old
        with zipfile.ZipFile(self.samples) as z:
            manifest = json.loads(z.read("manifest.json"))
            cut = next(f for f in manifest["files"] if f["name"] == "banks/Master.bank.fsb5-head")
            data = z.read(cut["file"])
            self.assertEqual(b"FSB5", data[:4])
            self.assertEqual(len(bank()) - bank().index(b"FSB5"), len(data))

    def test_user_strings_heap(self):
        heap = b"\0" + bytes([2 * 5 + 1]) + "event".encode("utf-16-le") + b"\0"
        self.assertEqual(["event"], INV["user_strings"](heap))

    def test_game_env_starts_it_only_under_the_experiment(self):
        env = runpy.run_path(str(BIN / "droiddeck-game-env"))
        calls = []
        exe = "/games/Slay the Spire 2/SlayTheSpire2.exe"
        self.assertEqual([], env["native_inventory"](exe, {}, popen=lambda *a, **k: calls.append(a)))
        notes = env["native_inventory"](exe, {"DROIDDECK_EXP_NATIVE_INVENTORY": "1"}, popen=lambda *a, **k: calls.append(a))
        self.assertEqual(["native-inventory: started for Slay the Spire 2"], notes)
        self.assertEqual(["--game-dir", "/games/Slay the Spire 2"], calls[0][0][-2:])
        self.assertIn("native-inventory", env["EXPERIMENTS"])

    def test_missing_folder_is_reported(self):
        self.assertEqual(1, INV["main"](["--game-dir", "/nonexistent/folder"]))


if __name__ == "__main__":
    unittest.main()
