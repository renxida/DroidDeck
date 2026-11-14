"""droiddeck-texpack and droiddeck-ctex-astc: ASTC textures appended to a Godot pack, switched on, and undone."""
import hashlib
import json
import os
from pathlib import Path
import random
import runpy
import shutil
import struct
import subprocess
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / "linuxfs/overlay/usr/local/bin"
BUILD = ROOT / "linuxfs/texpack/build.sh"

DXT1, DXT5, RGTC_RG, BPTC, ASTC, RGBA8 = 17, 19, 21, 22, 35, 5


def godot_size(w, h, block_bytes_per_px, shift, mips):
    """Image::_get_dst_image_size for a 4x4 block format (core/io/image.cpp, Godot 4.5)."""
    size = 0
    while True:
        bw, bh = (w + 3) // 4 * 4, (h + 3) // 4 * 4
        size += (bw * bh * block_bytes_per_px) >> shift
        if not mips or (w == 1 and h == 1):
            return size
        w, h = max(1, w >> 1), max(1, h >> 1)


def levels(w, h, mips):
    out = [(w, h)]
    while mips and (w > 1 or h > 1):
        w, h = max(1, w >> 1), max(1, h >> 1)
        out.append((w, h))
    return out


def rgb565(r, g, b):
    return (r >> 3) << 11 | (g >> 2) << 5 | b >> 3


def bc1_block(x, y):
    """A smooth 4x4 BC1 block: two endpoints from the block position, indices ramping left to right."""
    c0 = rgb565(min(255, 40 + x * 8), min(255, 60 + y * 8), 200)
    c1 = rgb565(10, min(255, 20 + y * 4), min(255, 30 + x * 4))
    if c0 <= c1:
        c0, c1 = c1 + 1, c0
    indices = 0
    for row in range(4):
        for col in range(4):
            indices |= [0, 2, 3, 1][col] << (2 * (row * 4 + col))
    return struct.pack("<HHI", c0, c1, indices)


def bc3_block(x, y):
    alpha = bytes([255, 0]) + (0).to_bytes(6, "little")  # a0 > a1: all texels index 0 = opaque
    return alpha + bc1_block(x, y)


def bc_data(w, h, mips, block):
    out = b""
    for lw, lh in levels(w, h, mips):
        for by in range((lh + 3) // 4):
            for bx in range((lw + 3) // 4):
                out += block(bx, by)
    return out


def ctex(w, h, data_format, fmt, mips, body):
    return (b"GST2" + struct.pack("<IIIIIIII", 1, w, h, 0x800000 if mips else 0, 0, 0, 0, 0)
            + struct.pack("<IHHII", data_format, w, h, len(levels(w, h, mips)) - 1 if mips else 0, fmt) + body)


def png(w, h):
    rows = b"".join(b"\0" + bytes(v for x in range(w) for v in (x * 9 % 256, y * 17 % 256, 128, 255 if x > 2 else 100))
                    for y in range(h))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def pack(entries):
    """A Godot 4.5 pack (format 3, relative file base) as PCKPacker lays it out: header, files, directory."""
    header = 4 + 4 + 16 + 8 + 8 + 64
    body, offsets = b"", []
    for _, data in entries:
        offsets.append(len(body))
        body += data + b"\0" * (-len(data) % 32)
    directory = struct.pack("<I", len(entries))
    for (name, data), off in zip(entries, offsets):
        raw = ("res://" + name).encode()
        raw += b"\0" * (-len(raw) % 4)
        directory += (struct.pack("<I", len(raw)) + raw + struct.pack("<QQ", off, len(data))
                      + hashlib.md5(data).digest() + struct.pack("<I", 0))
    return (b"GDPC" + struct.pack("<I4IQQ", 3, 4, 5, 1, 2, header, header + len(body)) + b"\0" * 64
            + body + directory)


def read_pack(path):
    """{name: bytes} through the header's current directory, the way FileAccessPack finds files."""
    blob = Path(path).read_bytes()
    flags, file_base, dir_offset = struct.unpack("<I", blob[20:24])[0], *struct.unpack("<QQ", blob[24:40])
    count = struct.unpack("<I", blob[dir_offset:dir_offset + 4])[0]
    at, files = dir_offset + 4, {}
    for _ in range(count):
        n = struct.unpack("<I", blob[at:at + 4])[0]
        name = blob[at + 4:at + 4 + n].rstrip(b"\0").decode()[6:]
        at += 4 + n
        off, size = struct.unpack("<QQ", blob[at:at + 16])
        md5 = blob[at + 16:at + 32]
        at += 36
        data = blob[file_base + off:file_base + off + size]
        assert hashlib.md5(data).digest() == md5, name
        files[name] = data
    return files


@unittest.skipUnless(shutil.which("g++"), "needs g++ to build droiddeck-ctex-astc")
class TexpackTest(unittest.TestCase):
    tool = None

    @classmethod
    def setUpClass(cls):
        cls.build_dir = tempfile.TemporaryDirectory()
        cls.tool = os.path.join(cls.build_dir.name, "droiddeck-ctex-astc")
        subprocess.run(["bash", str(BUILD), cls.tool], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build_dir.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mod = runpy.run_path(str(BIN / "droiddeck-texpack"))
        cache = Path(self.tmp.name) / "cache"
        g = self.mod["main"].__globals__
        g["CACHE_DIR"], g["STATE_DIR"], g["REPORT"] = cache, cache / "texpack", cache / "texpack.txt"
        cache.mkdir()
        self.game = Path(self.tmp.name) / "Game"
        self.game.mkdir()
        rng = random.Random(7)
        noise = bytes(b for _ in range(16) for b in (bytes([0x40]) + bytes(rng.randrange(256) for _ in range(15))))
        png_body = png(20, 12)
        self.entries = [
            ("project.binary", b"config" * 10),
            (".godot/imported/a.png-1.s3tc.ctex", ctex(64, 64, 0, DXT1, False, bc_data(64, 64, False, bc1_block))),
            (".godot/imported/b.png-2.s3tc.ctex", ctex(32, 16, 0, DXT5, True, bc_data(32, 16, True, bc3_block))),
            (".godot/imported/c.png-3.bptc.ctex", ctex(16, 16, 0, BPTC, False, noise)),
            (".godot/imported/d.png-4.ctex", ctex(20, 12, 1, RGBA8, False, struct.pack("<I", len(png_body)) + png_body)),
            (".godot/imported/e.png-5.s3tc.ctex", ctex(8, 8, 0, RGTC_RG, False, b"\0" * 64)),
            ("scenes/main.tscn", b"[gd_scene]\n"),
        ]
        self.pck = self.game / "Game.pck"
        self.pck.write_bytes(pack(self.entries))
        self.original = self.pck.read_bytes()
        os.utime(self.pck, ns=(1_700_000_000_000_000_000, 1_700_000_000_000_000_000))

    def run_tool(self, *argv):
        return self.mod["main"](list(argv) + ["--pack", str(self.pck), "--tool", self.tool, "--threads", "2",
                                               "--effort", "10"])

    def state(self):
        return self.mod["load_state"](self.pck)

    def test_build_appends_astc_switches_on_at_prepare_and_undoes_to_the_same_bytes(self):
        self.assertEqual(0, self.run_tool("build"))
        self.assertEqual("appended", self.mod["classify"](self.pck, self.state()))
        self.assertEqual(self.original[:40], self.pck.read_bytes()[:40], "the header waits for the next launch")
        summary = self.state()["summary"]
        self.assertEqual(5, summary["textures"])
        self.assertEqual({"format": 1}, summary["kept"])
        self.assertEqual({}, summary["failed"])

        self.assertEqual(0, self.run_tool("prepare"))
        self.assertEqual("active", self.mod["classify"](self.pck, self.state()))
        files = read_pack(self.pck)
        before = dict(self.entries)
        self.assertEqual(before["project.binary"], files["project.binary"])
        self.assertEqual(before["scenes/main.tscn"], files["scenes/main.tscn"])
        self.assertEqual(before[".godot/imported/e.png-5.s3tc.ctex"], files[".godot/imported/e.png-5.s3tc.ctex"])
        converted = [n for n in files if n.endswith(".ctex") and files[n] != before[n]]
        self.assertGreaterEqual(len(converted), 3)
        for name in converted:
            data = files[name]
            lw, lh = struct.unpack("<II", data[8:16])
            data_format, w, h, mips, fmt = struct.unpack("<IHHII", data[36:52])
            self.assertEqual((0, ASTC), (data_format, fmt), name)
            self.assertEqual(struct.unpack("<II", before[name][8:16]), (lw, lh), name)
            self.assertEqual(before[name][:36][16:20], data[16:20], "flags kept")
            self.assertEqual(52 + godot_size(w, h, 1, 0, bool(mips)), len(data), name)
        b = files[".godot/imported/b.png-2.s3tc.ctex"]
        self.assertEqual((32, 16, 5), struct.unpack("<HHI", b[40:48]))

        self.assertEqual(0, self.run_tool("revert"))
        self.assertEqual(self.original, self.pck.read_bytes())
        self.assertEqual(1_700_000_000_000_000_000, os.stat(self.pck).st_mtime_ns)
        self.assertIsNone(self.state())

    def test_half_above_halves_big_textures_but_keeps_their_logical_size(self):
        self.assertEqual(0, self.run_tool("build", "--half-above", "32"))
        self.assertEqual(0, self.run_tool("prepare", "--half-above", "32"))
        a = read_pack(self.pck)[".godot/imported/a.png-1.s3tc.ctex"]
        self.assertEqual((64, 64), struct.unpack("<II", a[8:16]))
        self.assertEqual((32, 32, 0, ASTC), struct.unpack("<HHII", a[40:52]))
        self.assertEqual(52 + 32 * 32, len(a))
        self.assertEqual(1, self.state()["summary"]["halved"])

    def test_a_different_variant_undoes_and_rebuilds(self):
        self.assertEqual(0, self.run_tool("build"))
        self.assertEqual(0, self.run_tool("prepare"))
        started = []
        g = self.mod["main"].__globals__
        g["start_build"] = lambda pack, args, popen=None: started.append(args.half_above)
        self.assertEqual(0, self.run_tool("prepare", "--half-above", "32"))
        self.assertEqual([32], started)
        self.assertEqual(self.original, self.pck.read_bytes())

    def test_a_texture_error_in_the_game_log_turns_the_textures_off(self):
        self.assertEqual(0, self.run_tool("build"))
        self.assertEqual(0, self.run_tool("prepare"))
        log = Path(self.tmp.name) / "godot.log"
        log.write_text("Godot Engine v4.5.1\nERROR: Compressed texture file is corrupt (Bad header).\n")
        self.assertEqual(0, self.run_tool("prepare", "--godot-log", str(log)))
        self.assertEqual(self.original, self.pck.read_bytes())
        state = self.state()
        self.assertIn("Compressed texture file is corrupt", state["failed_reason"])
        started = []
        self.mod["main"].__globals__["start_build"] = lambda pack, args, popen=None: started.append(1)
        self.assertEqual(0, self.run_tool("prepare"))
        self.assertEqual([], started, "the same build is not tried again")

    def test_a_steam_update_drops_the_state_and_leaves_the_pack_alone(self):
        self.assertEqual(0, self.run_tool("build"))
        self.assertEqual(0, self.run_tool("prepare"))
        fresh = pack(self.entries[:3])
        self.pck.write_bytes(fresh)
        self.assertEqual(0, self.run_tool("revert"))
        self.assertEqual(fresh, self.pck.read_bytes())
        self.assertIsNone(self.state())

    def test_an_interrupted_build_is_cut_off_before_the_next_one(self):
        state = self.mod["fresh_state"](self.pck, self.mod["variant_of"](
            type("A", (), {"half_above": 0, "effort": 10.0})()))
        state["phase"] = "building"
        self.mod["save_state"](self.pck, state)
        with open(self.pck, "ab") as f:
            f.write(b"half a texture")
        started = []
        self.mod["main"].__globals__["start_build"] = lambda pack, args, popen=None: started.append(1)
        self.assertEqual(0, self.run_tool("prepare"))
        self.assertEqual(self.original, self.pck.read_bytes())
        self.assertEqual([1], started)


class GameEnvTextureTest(unittest.TestCase):
    """droiddeck-game-env's side: prepare under tex-astc / tex-half, revert when off, nothing without state."""

    def setUp(self):
        self.env_mod = runpy.run_path(str(BIN / "droiddeck-game-env"))
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.state_dir = Path(self.tmp.name) / "texpack"
        self.state_dir.mkdir()
        self.env_mod["texture_pack"].__globals__["TEXPACK_STATE"] = str(self.state_dir)
        prefix = Path(self.tmp.name) / "compat"
        log = prefix / "pfx/drive_c/users/steamuser/AppData/Roaming/SlayTheSpire2/logs/godot.log"
        log.parent.mkdir(parents=True)
        log.write_text("x")
        self.log = str(log)
        self.prefix = str(prefix)
        self.calls = []

    def run_(self, cmd, **kwargs):
        self.calls.append(cmd[2:])
        return subprocess.CompletedProcess(cmd, 0, "", "DROIDDECK_TEXPACK texture build started in the background\n")

    def test_tex_half_prepares_with_half_resolution_and_the_game_log(self):
        env = {"DROIDDECK_EXP_TEX_HALF": "1", "STEAM_COMPAT_DATA_PATH": self.prefix}
        notes = self.env_mod["texture_pack"]("/games/Slay the Spire 2/SlayTheSpire2.exe", env, run=self.run_)
        self.assertEqual([["prepare", "--game-dir", "/games/Slay the Spire 2", "--half-above", "1024",
                           "--godot-log", self.log]], self.calls)
        self.assertEqual(["textures: texture build started in the background"], notes)

    def test_off_reverts_only_when_something_was_changed(self):
        self.assertEqual([], self.env_mod["texture_pack"]("/g/Game.exe", {}, run=self.run_))
        self.assertEqual([], self.calls)
        (self.state_dir / "abc.json").write_text("{}")
        self.env_mod["texture_pack"]("/g/Game.exe", {}, run=self.run_)
        self.assertEqual([["revert", "--game-dir", "/g"]], self.calls)


class ConverterOutputTest(unittest.TestCase):
    def test_godot_sizes_match_the_engine_rules(self):
        self.assertEqual(16, godot_size(1, 1, 1, 0, False))
        self.assertEqual(32 * 16 + 16 * 8 + 8 * 4 + 16 + 16 + 16, godot_size(32, 16, 1, 0, True))
        self.assertEqual(8, godot_size(4, 4, 1, 1, False))


if __name__ == "__main__":
    unittest.main()
