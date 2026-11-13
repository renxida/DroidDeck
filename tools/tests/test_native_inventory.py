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


def spine42():
    """A small Spine 4.2 binary skeleton in the layout the survey walks: two bones, one slot with a region,
    one animation with a bezier rotate key and an attachment key."""
    f = lambda v: struct.pack(">f", v)
    s = lambda t: bytes([len(t) + 1]) + t.encode()
    out = b"\x01" * 8 + s("4.2.43") + f(0) * 4 + f(100) + b"\x00"  # hash, version, bounds, reference scale, nonessential off
    out += b"\x01" + s("body")  # shared strings
    out += b"\x02" + s("root") + f(0) * 3 + f(1) * 2 + f(0) * 3 + b"\x00\x00"
    out += s("hip") + b"\x00" + f(10) * 3 + f(1) * 2 + f(0) * 3 + b"\x03\x00"  # inherit noScale
    out += b"\x01" + s("body") + b"\x01" + b"\xff" * 4 + b"\xff" * 4 + b"\x01" + b"\x00"
    out += b"\x00\x00\x00\x00"  # ik, transform, path, physics
    out += b"\x01\x00\x01\x01" + b"\x80" + f(90) + f(0) * 2 + f(1) * 2 + f(64) * 2  # default skin: region with rotation
    out += b"\x00\x00"  # skins, events
    out += b"\x01" + s("idle") + b"\x02"
    out += b"\x01\x00\x01" + b"\x00\x01" + f(0) + b"\x01"  # slot 0 attachment timeline
    out += b"\x01\x01\x01" + b"\x00\x02\x01" + f(0) + f(0) + f(1) + f(45) + b"\x02" + f(0.3) + f(10) + f(0.6) + f(40)
    out += b"\x00" * 7  # ik, transform, path, physics, attachment, draw order, event timelines
    return out


class Bin:
    """Writes the 4.2 binary layout piece by piece (big-endian floats, 7-bit varints, length+1 strings)."""

    def __init__(self):
        self.b = bytearray()

    def u8(self, *vs):
        self.b += bytes(vs)

    def u32(self, v):
        self.b += struct.pack(">I", v & 0xFFFFFFFF)

    def f(self, *vs):
        for v in vs:
            self.b += struct.pack(">f", v)

    def v(self, *xs):
        for x in xs:
            while True:
                c, x = x & 0x7F, x >> 7
                self.u8(c | (0x80 if x else 0))
                if not x:
                    break

    def s(self, t):
        if t is None:
            self.v(0)
        else:
            self.v(len(t) + 1)
            self.b += t.encode()

    def bone(self, name, parent, rot, x, y, length):
        self.s(name)
        if parent >= 0:
            self.v(parent)
        self.f(rot, x, y, 1, 1, 0, 0, length)
        self.u8(0, 0)


def spine42_loader_sample(deform_start=2, deform_end=4, named_skin=False):
    """The synthetic skeleton of the C++ loader's own test (skel_test.cpp, synthetic_binary): IK and transform
    constraints, a region with a sequence, a mesh and a linked mesh, an event, and an animation with every
    timeline kind the loader reads (deform keys hold end - start floats). [named_skin] adds a skin with a
    weighted bounding box whose flags also carry bit 8, a point and a clipping attachment, and a deform key
    on the bounding box."""
    w = Bin()
    w.u8(*range(8))
    w.s("4.2.43")
    w.f(0, 0, 100, 100, 100)
    w.u8(0)
    w.v(4)
    for t in ("body", "skin", "mesh", "linked"):
        w.s(t)
    w.v(5)
    w.bone("root", -1, 0, 0, 0, 0)
    w.bone("upper", 0, 90, 0, 0, 10)
    w.bone("lower", 1, 90, 10, 0, 10)
    w.bone("target", 0, 0, -10, 10, 0)
    w.bone("follow", 0, 0, 0, -5, 0)
    w.v(2)
    w.s("a"); w.v(0); w.u32(0xFFFFFFFF); w.u32(0xFFFFFFFF); w.v(1); w.v(0)
    w.s("b"); w.v(2); w.u32(0xFF000080); w.u32(0x10203040); w.v(3); w.v(1)
    w.v(1)
    w.s("ik"); w.v(0, 2, 1, 2, 3); w.u8(2 | 32)
    w.v(1)
    w.s("tc"); w.v(1, 1, 4, 3)
    w.u8(8 | 16 | 32); w.f(10, 1, 2)
    w.u8(2 | 4 | 8); w.f(1, 1, 0.5)
    w.v(0, 0)  # path, physics constraints
    w.v(2)  # default skin
    w.v(0, 1, 1)
    w.u8(0 | 32 | 64 | 128); w.u32(0x00FF00FF)  # region with color, a 4-image sequence and rotation
    w.v(4, 1, 2, 0); w.f(90)
    w.f(1, 2, 1, 1, 20, 10)
    w.v(1, 2)
    w.v(3); w.u8(2); w.v(3, 3)  # mesh: hull 3, 3 vertices
    w.f(0, 0, 10, 0, 0, 10)
    w.f(0, 1, 1, 1, 0, 0)
    w.v(0, 1, 2)
    w.v(4); w.u8(3 | 128); w.v(0, 3)  # linked mesh sharing deform keys with "mesh"
    if named_skin:
        w.v(1)
        w.v(2)  # named "skin"
        w.v(1, 4)  # bones
        w.v(1, 0, 0, 0, 0)  # ik, transform, path, physics constraint lists
        w.v(1, 0, 3)
        w.v(1); w.u8(1 | 8 | 16)  # weighted bounding box: one vertex on two bones
        w.v(1, 2); w.v(0); w.f(1, 2, 0.5); w.v(1); w.f(3, 4, 0.5)
        w.v(2); w.u8(5 | 8); w.f(45, 1, 2)  # point
        w.v(4); w.u8(6 | 8); w.v(1, 3); w.f(0, 0, 1, 0, 0, 1)  # clipping to slot 1
    else:
        w.v(0)
    w.v(1)
    w.s("hit"); w.v(5); w.f(1.5); w.s("str"); w.s(None)
    w.v(1)
    w.s("anim"); w.v(0)
    w.v(1, 1, 2)
    w.u8(0); w.v(2); w.f(0); w.v(4); w.f(0.75); w.v(3)
    w.u8(5); w.v(2, 0); w.f(0); w.u8(255); w.f(1); w.u8(0, 0)
    w.v(1, 0, 2)
    w.u8(0); w.v(2, 1); w.f(0, 0, 1, 90); w.u8(2); w.f(0.25, 0, 0.75, 90)
    w.u8(1); w.v(2, 0); w.f(0, 0, 0, 1, 10, 20); w.u8(1)
    w.v(1, 0, 2, 0)
    w.u8(1 | 8 | 64); w.f(0)
    w.u8(4 | 16); w.f(1, 0.25)
    w.v(1, 0, 1, 0); w.f(0, 0.5, 0, 0, 0, 0, 0)
    w.v(0, 0)  # path, physics timelines
    w.v(2 if named_skin else 1)
    w.v(0, 2, 1, 1, 3); w.u8(0); w.v(2, 0)  # deform on "mesh"
    w.f(0); w.v(0)
    w.f(1); w.v(deform_end, deform_start); w.f(*range(deform_end - deform_start)); w.u8(0)
    w.v(0, 1, 1); w.u8(1); w.v(1); w.f(0); w.u32(0 << 4 | 1); w.f(0.1)  # sequence on "body"
    if named_skin:
        w.v(1, 1, 0, 1, 1); w.u8(0); w.v(1, 0); w.f(0); w.v(4, 0); w.f(1, 2, 3, 4)  # deform on the bounding box
    w.v(1); w.f(0.25); w.v(1, 1, 0xFFFFFFFF)  # draw order: slot 1 moves back one
    w.v(1); w.f(0.75); w.v(0, 14); w.f(2); w.u8(0)
    return bytes(w.b)


class NativeInventoryTest(unittest.TestCase):
    def build(self, version=3, embedded=False):
        skeleton = json.dumps({"skeleton": {"spine": "4.2.43"}, "bones": [{"name": "root"}, {"name": "a", "parent": "root", "inherit": "noScale"}],
                               "physics": [{"name": "hair", "bone": "a", "inertia": 0.5}],
                               "skins": [{"name": "default", "attachments": {"s": {"m": {"type": "mesh"}, "r": {}}}}],
                               "animations": {"idle": {"bones": {"a": {"rotate": [{"time": 0}]}}, "physics": {}}}}).encode()
        entries = [
            ("monsters/cultist.json", skeleton),
            ("monsters/jaw.skel", b"\x8a\x91hashbytes\x064.2.43\x00\x00rest"),
            ("monsters/hip.skel", spine42()),
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
                self.assertEqual(3, summary["skeletons"])
                self.assertEqual(1, summary["skel_survey"]["ok"])
                self.assertEqual(1, summary["skel_survey"]["skeletons_with"]["bone_inherit_3"])
                self.assertEqual({"vorbis": 12}, summary["audio_samples_by_codec"])
                self.assertEqual(2, summary["bank_events"])

    def test_samples_take_skeletons_atlases_and_banks_within_budget(self):
        import zipfile
        game, tmp = self.build()
        self.run_inventory(game, tmp)
        with zipfile.ZipFile(self.samples) as z:
            manifest = json.loads(z.read("manifest.json"))
            names = [f["name"] for f in manifest["files"]]
            self.assertEqual(sorted(["banks/Master.strings.bank", "banks/Master.bank.meta-head", "monsters/cultist.json", "monsters/hip.skel",
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

    def test_a_bank_sample_stops_before_its_audio(self):
        import zipfile
        game, tmp = self.build()
        self.run_inventory(game, tmp)
        with zipfile.ZipFile(self.samples) as z:
            manifest = json.loads(z.read("manifest.json"))
            head = next(f for f in manifest["files"] if f["name"] == "banks/Master.bank.meta-head")
            data = z.read(head["file"])
            self.assertEqual(bank()[:len(data)], data)
            fsb = data.index(b"FSB5")
            version, _, headers, names, size = struct.unpack("<5I", data[fsb + 4:fsb + 24])
            self.assertEqual(fsb + INV["fsb5_header_size"](version) + headers + names, len(data))
            self.assertGreater(size, 0)

    def test_skeletons_with_unread_layouts_are_sampled_first(self):
        report = {"skeletons": [
            {"name": "a.skel", "survey": {"ok": False, "stage": "path constraints (layout unknown)"}},
            {"name": "b.skel", "survey": {"ok": False, "stage": "path constraints (layout unknown)"}},
            {"name": "c.skel", "survey": {"ok": False, "stage": "skins", "error": "ValueError: attachment type 4 (layout unknown)"}},
            {"name": "d.skel", "survey": {"ok": True}}]}
        self.assertEqual({"path constraints": ["a.skel", "b.skel"], "attachment type 4": ["c.skel"]},
                         INV["unread_skeletons"](report))

    def test_spine42_survey_walks_a_whole_skeleton(self):
        survey = INV["skel_survey"](spine42())
        self.assertTrue(survey["ok"], survey)
        self.assertEqual(1, survey["bone_inherit_3"])
        self.assertEqual(1, survey["bezier_segments"])
        self.assertEqual(1, survey["slot_timeline_0"])
        broken = INV["skel_survey"](spine42()[:-3])
        self.assertFalse(broken["ok"])

    def test_spine42_survey_reads_the_loader_test_skeleton(self):
        for named in (False, True):
            blob = spine42_loader_sample(named_skin=named)
            survey = INV["skel_survey"](blob)
            self.assertTrue(survey["ok"], survey)
            self.assertEqual(("end", len(blob)), (survey["stage"], survey["at"]))
            self.assertEqual(2 if named else 1, survey["deform_timelines"])
            self.assertEqual(1, survey["sequence_timelines"])
            self.assertEqual(1, survey["sequences"])
            self.assertEqual(1, survey["attachment_3"])
            self.assertEqual(2, survey["stepped_segments"])  # translate, then the IK key's own flag
            self.assertEqual(1, survey["bezier_segments"])
            self.assertEqual(1, survey["draw_order_timelines"])
            self.assertEqual(1, survey["event_timelines"])
        self.assertEqual(1, survey["attachment_1"])
        self.assertEqual(1, survey["attachment_5"])
        self.assertEqual(1, survey["attachment_6"])
        self.assertEqual(2, survey["skins"])

    def test_spine42_survey_fails_where_the_loader_does(self):
        blob = spine42_loader_sample()
        self.assertFalse(INV["skel_survey"](blob[:-1])["ok"])
        extra = INV["skel_survey"](blob + b"\0")
        self.assertFalse(extra["ok"])
        self.assertIn("1 bytes left", extra["error"])
        self.assertTrue(INV["skel_survey"](spine42_loader_sample(deform_start=0, deform_end=6))["ok"])
        # A deform key reaching past the mesh's 6 vertex floats is a misread, not a bigger mesh.
        past = INV["skel_survey"](spine42_loader_sample(deform_start=0, deform_end=8))
        self.assertEqual(("animations: attachment timelines", "ValueError: deform 0..8 past 6 floats"),
                         (past["stage"], past["error"]))

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
