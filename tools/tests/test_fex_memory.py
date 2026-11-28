import runpy
import struct
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-fex-memory"))
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))

FEATURES = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 " \
           "sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 flagm2 frint i8mm bf16 bti"


def cpuinfo(parts, features=FEATURES):
    blocks = []
    for index, part in enumerate(parts):
        blocks.append("processor\t: %d\nBogoMIPS\t: 49.15\nFeatures\t: %s\nCPU implementer\t: 0x41\n"
                      "CPU architecture: 8\nCPU variant\t: 0x0\nCPU part\t: %#05x\nCPU revision\t: 1\n" % (index, features, part))
    return "\n".join(blocks)


TENSOR_G5 = [0xD80] * 2 + [0xD87] * 5 + [0xD82]


def pe(volatile=None, machine=0x8664, code_size=0x1000):
    """A PE32+ with .text and .rdata; .rdata holds a load config pointing at `volatile` = (accesses, ranges)."""
    base = 0x180000000
    file_align = 0x200
    text_rva, rdata_rva = 0x1000, 0x2000
    text_raw, rdata_raw = 0x400, 0x400 + file_align * 8
    rdata = bytearray(0x400)
    load_config = bytearray(0x140)
    struct.pack_into("<I", load_config, 0, 0x140)
    if volatile is not None:
        accesses, ranges = volatile
        header_at, table_at = 0x140, 0x160
        ranges_at = table_at + 4 * len(accesses)
        struct.pack_into("<Q", load_config, 0x100, base + rdata_rva + header_at)
        struct.pack_into("<6I", rdata, header_at, 24, 0x80000003, rdata_rva + table_at, 4 * len(accesses),
                         rdata_rva + ranges_at, 8 * len(ranges))
        for index, rva in enumerate(accesses):
            struct.pack_into("<I", rdata, table_at + 4 * index, rva)
        for index, (rva, size) in enumerate(ranges):
            struct.pack_into("<2I", rdata, ranges_at + 8 * index, rva, size)
    rdata[0:0x140] = load_config

    optional = bytearray(240)
    struct.pack_into("<H", optional, 0, 0x20B)
    struct.pack_into("<Q", optional, 24, base)
    struct.pack_into("<I", optional, 108, 16)
    struct.pack_into("<2I", optional, 112 + 10 * 8, rdata_rva, 0x140)
    sections = bytearray()
    sections += b".text\0\0\0" + struct.pack("<6I", code_size, text_rva, file_align * 8, text_raw, 0, 0) + \
        struct.pack("<HHI", 0, 0, 0x60000020)
    sections += b".rdata\0\0" + struct.pack("<6I", len(rdata), rdata_rva, len(rdata), rdata_raw, 0, 0) + \
        struct.pack("<HHI", 0, 0, 0x40000040)
    coff = struct.pack("<4sHHIIIHH", b"PE\0\0", machine, 2, 0, 0, 0, len(optional), 0x22)
    head = bytearray(b"MZ" + b"\0" * 58 + struct.pack("<I", 64)) + coff + optional + sections
    image = bytearray(rdata_raw + len(rdata))
    image[:len(head)] = head
    image[text_raw:text_raw + 4] = b"\xc3\x90\x90\x90"
    image[rdata_raw:rdata_raw + len(rdata)] = rdata
    return bytes(image)


class MemoryModelTest(unittest.TestCase):
    def test_tensor_g5_keeps_ldapur_off_like_fex_does_for_the_cortex_x4(self):
        with tempfile.TemporaryDirectory() as root:
            model = MODULE["memory_model"](cpuinfo(TENSOR_G5), root)
        self.assertEqual(["Cortex-X4"], model["erratum"])
        self.assertEqual("LDAPR", model["loads"])
        self.assertEqual("disablelrcpc2", model["hostfeatures"])
        self.assertTrue(model["lrcpc"] and model["lrcpc2"] and not model["lrcpc3"])
        self.assertEqual(0, model["midr_readable"])

    def test_cores_without_the_erratum_use_ldapur(self):
        model = MODULE["memory_model"](cpuinfo([0xD80] * 4 + [0xD87] * 4), "/nonexistent")
        self.assertEqual("LDAPUR", model["loads"])
        self.assertIsNone(model["hostfeatures"])

    def test_cores_without_lrcpc_fall_back_to_ldar(self):
        model = MODULE["memory_model"](cpuinfo([0xD05] * 8, "fp asimd atomics"), "/nonexistent")
        self.assertEqual("LDAR", model["loads"])
        self.assertIsNone(model["hostfeatures"])

    def test_counts_the_per_core_midr_files_wine_reads(self):
        with tempfile.TemporaryDirectory() as root:
            for cpu in (0, 7):
                path = Path(root) / (MODULE["MIDR"] % cpu)
                path.parent.mkdir(parents=True)
                path.write_text("0x00000000411fd820\n")
            model = MODULE["memory_model"](cpuinfo(TENSOR_G5), root)
        self.assertEqual(2, model["midr_readable"])

    def test_launch_sets_the_hostfeatures_override_and_says_so(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = Path(tmp) / "cpuinfo"
            info.write_text(cpuinfo(TENSOR_G5))
            env, notes = MODULE["launch"]([], {"A": "1"}, str(info), tmp)
        self.assertEqual({"A": "1", "FEX_HOSTFEATURES": "disablelrcpc2"}, env)
        self.assertEqual(1, len(notes))
        self.assertIn("FEX memory model: Cortex-A520 x2, Cortex-A725 x5, Cortex-X4 x1", notes[0])
        self.assertIn("TSO loads use LDAPR", notes[0])
        self.assertIn("FEX_HOSTFEATURES=disablelrcpc2", notes[0])

    def test_launch_keeps_a_hostfeatures_value_the_user_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = Path(tmp) / "cpuinfo"
            info.write_text(cpuinfo(TENSOR_G5))
            env, notes = MODULE["launch"]([], {"FEX_HOSTFEATURES": "enablelrcpc2"}, str(info), tmp)
        self.assertEqual({"FEX_HOSTFEATURES": "enablelrcpc2"}, env)
        self.assertIn("FEX_HOSTFEATURES left as set", notes[0])

    def test_cores_from_other_implementers_are_named_by_id(self):
        model = MODULE["memory_model"](cpuinfo([0xD82]).replace("0x41", "0x51"), "/nonexistent")
        self.assertEqual([], model["erratum"])
        self.assertEqual("0x51:0xd82", MODULE["core_name"](model["cores"][0]))

    def test_launch_says_nothing_about_hosts_without_arm_core_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = Path(tmp) / "cpuinfo"
            info.write_text("processor\t: 0\nvendor_id\t: GenuineIntel\nflags\t\t: fpu sse2\n\n")
            self.assertEqual(({}, []), MODULE["launch"]([], {}, str(info), tmp))


class VolatileMetadataTest(unittest.TestCase):
    def write(self, folder, name, data):
        path = Path(folder) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return str(path)

    def test_reads_access_and_range_tables(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write(tmp, "coreclr.dll", pe(([0x1010, 0x1020, 0x1030], [(0x1000, 0x800), (0x1800, 0x400)])))
            self.assertEqual({"accesses": 3, "covered": 0xC00, "code": 0x1000}, MODULE["volatile_metadata"](path))

    def test_modules_without_metadata_and_other_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual({"accesses": 0, "covered": 0, "code": 0x1000},
                             MODULE["volatile_metadata"](self.write(tmp, "game.exe", pe())))
            self.assertIsNone(MODULE["volatile_metadata"](self.write(tmp, "x86.dll", pe(machine=0x14C))))
            self.assertIsNone(MODULE["volatile_metadata"](self.write(tmp, "text.dll", b"MZ not a real image")))
            self.assertIsNone(MODULE["volatile_metadata"](str(Path(tmp) / "missing.dll")))

    def test_report_lists_covered_modules_first_and_bare_ones_after(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write(tmp, "Game.exe", pe())
            self.write(tmp, "data_Game_windows_x86_64/coreclr.dll", pe(([0x1010] * 5, [(0x1000, 0xFF0)])))
            self.write(tmp, "data_Game_windows_x86_64/clrjit.dll", pe(([0x1010], [(0x1000, 0x800)])))
            self.write(tmp, "data_Game_windows_x86_64/resources.dll", pe(code_size=0))
            self.write(tmp, "data_Game_windows_x86_64/managed.dll", pe(machine=0x14C))
            report = MODULE["describe_modules"](tmp)
        self.assertEqual("FEX volatile metadata: 2 of 3 x64 modules (coreclr.dll 5 accesses, 99.6% of code; "
                         "clrjit.dll 1 accesses, 50.0% of code); none in Game.exe", report)

    def test_report_is_empty_without_x64_modules(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write(tmp, "readme.txt", b"hello")
            self.assertIsNone(MODULE["describe_modules"](tmp))


class FexVersionTest(unittest.TestCase):
    def test_reads_the_longest_fex_tag_in_the_protons_arm64ec_dll(self):
        with tempfile.TemporaryDirectory() as tmp:
            dll = Path(tmp) / MODULE["FEX_DLL"]
            dll.parent.mkdir(parents=True)
            dll.write_bytes(b"\0" * 64 + b"FEX-2611\0" + b"\0" * 10 + b"FEX-2611.1-14-gab135df0\0")
            self.assertEqual("FEX-2611.1-14-gab135df0", MODULE["fex_version"](str(Path(tmp) / "proton")))
            self.assertIsNone(MODULE["fex_version"](str(Path(tmp) / "elsewhere/proton")))

    def test_launch_reports_modules_and_fex_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            dll = Path(tmp) / "proton-dir" / MODULE["FEX_DLL"]
            dll.parent.mkdir(parents=True)
            dll.write_bytes(b"FEX-2610\0")
            game = Path(tmp) / "game"
            game.mkdir()
            (game / "Game.exe").write_bytes(pe())
            command = [str(Path(tmp) / "proton-dir/proton"), "waitforexitandrun", str(game / "Game.exe")]
            env, notes = MODULE["launch"](command, {}, str(Path(tmp) / "no-cpuinfo"), tmp)
        self.assertEqual({}, env)
        self.assertEqual(["FEX volatile metadata: 0 of 1 x64 modules; none in Game.exe", "FEX in this Proton: FEX-2610"], notes)


class GameEnvironmentHookTest(unittest.TestCase):
    def test_hook_tolerates_a_launch_without_files(self):
        env, notes = GAME_ENV["fex_memory_model"](["/missing/proton", "waitforexitandrun", "/missing/game.exe"], {"X": "1"})
        self.assertEqual("1", env["X"])
        self.assertTrue(all(isinstance(note, str) for note in notes))


if __name__ == "__main__":
    unittest.main()
