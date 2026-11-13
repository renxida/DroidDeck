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


def fsb5(samples, codec):
    return b"FSB5" + struct.pack("<6I", 1, samples, 64, 0, 1000, codec) + b"\0" * 32


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
        self.assertEqual(0, INV["main"](["--game-dir", str(game), "--no-cache", "--json", str(out)]))
        return json.loads(out.read_text())

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
                self.assertIn("SND ", banks["banks/Master.bank"]["chunk_counts"])
                self.assertEqual(["addons/fmod/fmod.gdextension"], [g["name"] for g in report["gdextensions"]])
                self.assertIn("SpineSprite", report["resource_class_hits"])
                self.assertIn("FmodEventEmitter2D", report["resource_class_hits"])
                self.assertEqual({"spine.skel -> SpineSkeletonFileResource": 1}, report["importers"])
                summary = INV["summary"](report)
                self.assertEqual(2, summary["skeletons"])
                self.assertEqual({"vorbis": 12}, summary["audio_samples_by_codec"])
                self.assertEqual(2, summary["bank_events"])

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
