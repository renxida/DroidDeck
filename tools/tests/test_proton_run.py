import os
from pathlib import Path
import runpy
import shutil
import struct
import tempfile
import unittest
from unittest import mock
import zlib

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
RUN = runpy.run_path(str(BIN / "droiddeck-proton-run"))["main"].__globals__


class Temp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def write(self, relative, data, mode=0o644):
        path = self.tmp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode())
        path.chmod(mode)
        return path


def appinfo(apps):
    strings = []

    def key(name):
        if name not in strings:
            strings.append(name)
        return struct.pack("<I", strings.index(name))

    def section(items):
        out = b""
        for name, value in items.items():
            if isinstance(value, dict):
                out += b"\x00" + key(name) + section(value)
            else:
                out += b"\x01" + key(name) + value.encode() + b"\0"
        return out + b"\x08"

    body = b""
    for app, info in apps.items():
        data = section({"appinfo": dict(info, appid=str(app))})
        header = struct.pack("<IIQ", 2, 0, 0) + bytes(20) + struct.pack("<I", 1) + bytes(20)
        body += struct.pack("<II", app, len(header) + len(data)) + header + data
    body += struct.pack("<I", 0)
    table = struct.pack("<I", len(strings)) + b"".join(s.encode() + b"\0" for s in strings)
    return struct.pack("<IIq", 0x07564429, 1, 16 + len(body)) + body + table


STARDEW = {"common": {"type": "Game", "oslist": "windows,macos,linux"}, "config": {"launch": {
    "0": {"executable": "Stardew Valley.exe", "type": "default", "config": {"oslist": "windows"}},
    "1": {"executable": "StardewValley", "type": "default", "config": {"oslist": "linux"}},
    "2": {"executable": ".\\Tools\\Setup.exe", "type": "option1"},
}}}


TOOL_VDF = '''"compatibilitytools"
{
  "compat_tools"
  {
    "%s"
    {
      "install_path" "."
      "display_name" "%s"
      "from_oslist" "%s"
      "to_oslist" "linux"
    }
  }
}
'''

CONFIG = '''"InstallConfigStore"
{
  "Software"
  {
    "Valve"
    {
      "Steam"
      {
        "CompatToolMapping"
        {
          "0"
          {
            "name" "droiddeck-proton-arm64"
            "config" ""
            "priority" "75"
          }
          "413150"
          {
            "name" "droiddeck-proton-11-arm64"
            "config" ""
            "priority" "250"
          }
        }
      }
    }
  }
}
'''


class ProtonRunTest(Temp):
    def setUp(self):
        super().setUp()
        self.root = self.tmp / "Steam"
        for name, oslist in (("droiddeck-proton-arm64", "windows"), ("droiddeck-proton-11-arm64", "windows"),
                             ("droiddeck-fex", "linux")):
            folder = "compatibilitytools.d/%s" % name
            self.write("Steam/%s/compatibilitytool.vdf" % folder, TOOL_VDF % (name, name, oslist))
            self.write("Steam/%s/toolmanifest.vdf" % folder, '"manifest"\n{\n  "commandline" "/droiddeck-proton %verb%"\n}\n')
            self.write("Steam/%s/droiddeck-proton" % folder, b"#!/bin/sh\n", 0o755)
        self.write("Steam/config/config.vdf", CONFIG)
        patcher = mock.patch.dict(RUN, {"EXTRA_LIBRARIES": (), "SYSTEM_TOOLS": (), "STATE": str(self.tmp / "state.json")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def game(self, appid, installdir, relative):
        self.write("Steam/steamapps/appmanifest_%s.acf" % appid, '"AppState"\n{\n  "appid" "%s"\n  "installdir" "%s"\n}\n'
                   % (appid, installdir))
        return self.write("Steam/steamapps/common/%s/%s" % (installdir, relative), b"MZ")

    def test_only_windows_tools_that_can_start_are_offered(self):
        found = RUN["tools"](str(self.root))
        self.assertEqual(sorted(found), ["droiddeck-proton-11-arm64", "droiddeck-proton-arm64"])
        self.assertEqual(found["droiddeck-proton-arm64"],
                         (str(self.root / "compatibilitytools.d/droiddeck-proton-arm64"), "droiddeck-proton"))

    def test_a_valve_depot_is_registered_through_the_droiddeck_wrapper(self):
        name = "Proton Experimental (ARM64)"
        folder = "steamapps/common/%s" % name
        shutil.rmtree(self.root / "compatibilitytools.d/droiddeck-proton-arm64")
        self.write("Steam/steamapps/appmanifest_4427310.acf", '"AppState" { "appid" "4427310" }')
        self.write("Steam/%s/toolmanifest.vdf" % folder, '"manifest"\n{\n  "commandline" "/proton %verb%"\n}\n')
        self.write("Steam/%s/proton" % folder, b"#!/bin/sh\n", 0o755)
        self.write("Steam/%s/files/bin-arm64/proton" % folder, b"#!/bin/sh\n", 0o755)
        found = RUN["tools"](str(self.root))
        wrapper = self.root / "compatibilitytools.d/droiddeck-proton-arm64"
        self.assertEqual(found["droiddeck-proton-arm64"], (str(wrapper), "droiddeck-proton"))
        self.assertNotIn(name, found)

    def test_an_incomplete_valve_depot_is_not_registered(self):
        name = "Proton Experimental (ARM64)"
        folder = "steamapps/common/%s" % name
        shutil.rmtree(self.root / "compatibilitytools.d/droiddeck-proton-arm64")
        self.write("Steam/steamapps/appmanifest_4427310.acf", '"AppState" { "appid" "4427310" }')
        self.write("Steam/%s/toolmanifest.vdf" % folder, '"manifest"\n{\n  "commandline" "/proton %verb%"\n}\n')
        self.write("Steam/%s/files/bin-arm64/proton" % folder, b"#!/bin/sh\n", 0o755)
        found = RUN["tools"](str(self.root))
        self.assertNotIn("droiddeck-proton-arm64", found)
        self.assertFalse((self.root / "compatibilitytools.d/droiddeck-proton-arm64").exists())

    def test_a_failed_wrapper_build_does_not_hide_existing_tools(self):
        shutil.rmtree(self.root / "compatibilitytools.d/droiddeck-proton-arm64")
        self.write("Steam/steamapps/appmanifest_4427310.acf", '"AppState" { "appid" "4427310" }')
        folder = "steamapps/common/Proton Experimental (ARM64)"
        self.write("Steam/%s/proton" % folder, b"#!/bin/sh\n", 0o755)
        self.write("Steam/%s/files/bin-arm64/proton" % folder, b"#!/bin/sh\n", 0o755)
        build = mock.Mock(side_effect=OSError("read-only"))
        with mock.patch.dict(RUN["compat"](), {"build_tool": build}):
            found = RUN["tools"](str(self.root))
        build.assert_called_once()
        self.assertIn("droiddeck-proton-11-arm64", found)

    def test_a_runnable_depot_is_chosen_over_a_newer_incomplete_one(self):
        shutil.rmtree(self.root / "compatibilitytools.d/droiddeck-proton-arm64")
        self.write("Steam/steamapps/appmanifest_4427310.acf", '"AppState" { "appid" "4427310" }')
        good = "Steam/steamapps/common/Proton Experimental (ARM64)"
        self.write("%s/proton" % good, b"#!/bin/sh\n", 0o755)
        self.write("%s/files/bin-arm64/proton" % good, b"#!/bin/sh\n", 0o755)
        broken = "Library/steamapps/common/Proton Experimental (ARM64)"
        self.write("Library/steamapps/libraryfolders.vdf", b"")
        self.write("%s/version" % broken, b"2\n")
        self.write("%s/files/bin-arm64/proton" % broken, b"#!/bin/sh\n", 0o755)
        self.write("Steam/steamapps/libraryfolders.vdf", '"path" "%s"' % (self.tmp / "Library"))
        compat = RUN["compat"]()
        source = compat["find_source"]
        self.assertEqual(source(str(self.root), runnable=True), str(self.tmp / good))

    def test_a_registered_wrapper_without_its_launcher_is_rebuilt(self):
        wrapper = self.root / "compatibilitytools.d/droiddeck-proton-arm64"
        self.write("Steam/steamapps/appmanifest_4427310.acf", '"AppState" { "appid" "4427310" }')
        folder = "Steam/steamapps/common/Proton Experimental (ARM64)"
        self.write("%s/proton" % folder, b"#!/bin/sh\n", 0o755)
        self.write("%s/files/bin-arm64/proton" % folder, b"#!/bin/sh\n", 0o755)
        launcher = wrapper / RUN["compat"]()["LAUNCHER"]
        if launcher.exists():
            launcher.unlink()
        found = RUN["tools"](str(self.root))
        self.assertTrue(os.access(launcher, os.X_OK))
        self.assertIn("droiddeck-proton-arm64", found)

    def test_a_registered_wrapper_without_its_manifest_is_rebuilt(self):
        wrapper = self.root / "compatibilitytools.d/droiddeck-proton-arm64"
        self.write("Steam/steamapps/appmanifest_4427310.acf", '"AppState" { "appid" "4427310" }')
        folder = "Steam/steamapps/common/Proton Experimental (ARM64)"
        self.write("%s/proton" % folder, b"#!/bin/sh\n", 0o755)
        self.write("%s/files/bin-arm64/proton" % folder, b"#!/bin/sh\n", 0o755)
        manifest = wrapper / "toolmanifest.vdf"
        if manifest.exists():
            manifest.unlink()
        found = RUN["tools"](str(self.root))
        self.assertTrue(manifest.is_file())
        self.assertIn("droiddeck-proton-arm64", found)

    def test_steams_choice_for_the_title_then_its_default(self):
        available = RUN["tools"](str(self.root))
        current = RUN["mapping"](str(self.root))
        self.assertEqual(RUN["choose_tool"]("413150", current, available), ("droiddeck-proton-11-arm64", "Steam's choice for this title"))
        self.assertEqual(RUN["choose_tool"]("70", current, available), ("droiddeck-proton-arm64", "Steam's default"))
        self.assertEqual(RUN["choose_tool"]("70", {"0": "GE-Proton"}, available), ("droiddeck-proton-arm64", "the default ARM64 Proton"))
        self.assertEqual(RUN["choose_tool"]("70", {}, {}), (None, None))

    def test_a_removed_droiddeck_proton_build_is_replaced_by_the_newest_one(self):
        for version in ("11-12.2", "11-12.3"):
            folder = "compatibilitytools.d/DroidDeck-Proton %s" % version
            self.write("Steam/%s/compatibilitytool.vdf" % folder, TOOL_VDF % ("DroidDeck-Proton-" + version, version, "windows"))
            self.write("Steam/%s/toolmanifest.vdf" % folder, '"manifest"\n{\n  "commandline" "/proton %verb%"\n}\n')
            self.write("Steam/%s/proton" % folder, b"#!/bin/sh\n", 0o755)
        available = RUN["tools"](str(self.root))
        newest = "DroidDeck-Proton-11-12.3"
        self.assertEqual(RUN["choose_tool"]("70", {"0": "DroidDeck-Proton-11-12.1"}, available)[0], newest)
        self.assertEqual(RUN["choose_tool"]("70", {"70": "DroidDeck-Proton-11-12.1", "0": "droiddeck-proton-arm64"}, available)[0], newest)
        self.assertEqual(RUN["choose_tool"]("70", {"0": "DroidDeck-Proton-11-12.2"}, available), ("DroidDeck-Proton-11-12.2", "Steam's default"))
        self.assertEqual(RUN["choose_tool"]("70", {"0": "GE-Proton"}, available), ("droiddeck-proton-arm64", "the default ARM64 Proton"))
        (self.tmp / "state.json").write_text('{"default": "DroidDeck-Proton-11-12.1"}')
        self.assertEqual(RUN["choose_tool"]("70", {}, available)[0], newest)
        (self.tmp / "state.json").write_text('{"default": "droiddeck-proton-11-arm64"}')
        self.assertEqual(RUN["choose_tool"]("70", {}, available), ("droiddeck-proton-11-arm64", "the default ARM64 Proton"))

    def test_a_steam_games_program_uses_its_prefix(self):
        program = self.game("413150", "Stardew Valley", "Stardew Valley.exe")
        target = RUN["locate"](str(program), str(self.root))
        self.assertEqual((target.appid, target.kind, target.compatdata, target.install),
                         ("413150", "steam", str(self.root / "steamapps/compatdata/413150"),
                          str(self.root / "steamapps/common/Stardew Valley")))

    def test_a_tool_in_the_library_is_not_a_game(self):
        program = self.game("1493710", "Proton - Experimental", "files/bin/wine.exe")
        self.assertEqual(RUN["locate"](str(program), str(self.root)).kind, "own")

    def test_a_program_in_a_prefix_uses_that_prefix(self):
        program = self.write("Steam/steamapps/compatdata/2870/pfx/drive_c/Setup/setup.exe", b"MZ")
        target = RUN["locate"](str(program), str(self.root))
        self.assertEqual((target.appid, target.kind, target.compatdata),
                         ("2870", "prefix", str(self.root / "steamapps/compatdata/2870")))

    def test_any_other_program_gets_the_id_steam_would_give_its_shortcut(self):
        program = str(self.write("Download/winemine.exe", b"MZ"))
        target = RUN["locate"](program, str(self.root))
        appid = zlib.crc32(('"%s"winemine' % program).encode()) | 0x80000000
        self.assertEqual((target.appid, target.kind), (str(appid), "own"))
        env = RUN["environment"]({}, target, "/tool", str(self.root))
        self.assertEqual((env["SteamAppId"], env["STEAM_COMPAT_APP_ID"], env["SteamGameId"]),
                         ("0", "0", str(appid << 32 | 0x02000000)))
        self.assertEqual(env["STEAM_COMPAT_DATA_PATH"], str(self.root / ("steamapps/compatdata/%d" % appid)))

    def test_a_steam_game_keeps_its_own_id(self):
        target = RUN["Target"]("413150", "/c", "/i", str(self.root), "steam")
        env = RUN["environment"]({"PROTON_USE_WINED3D": "0"}, target, "/tool", str(self.root))
        self.assertEqual((env["SteamAppId"], env["SteamGameId"], env["PROTON_USE_WINED3D"]), ("413150", "413150", "0"))

    def test_an_installer_package_goes_to_msiexec(self):
        self.assertEqual(RUN["arguments"]("/root/x/setup.msi", ["/qn"]), ["msiexec", "/i", "Z:\\root\\x\\setup.msi", "/qn"])
        self.assertEqual(RUN["arguments"]("/root/x/a.exe", ["-w"]), ["/root/x/a.exe", "-w"])

    def test_a_virtual_desktop_runs_the_program_by_its_windows_path(self):
        pfx = "/root/Steam/steamapps/compatdata/2596113170"
        self.assertEqual(RUN["arguments"](pfx + "/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe", [], "1280x720", pfx),
                         ["explorer.exe", "/desktop=droiddeck,1280x720", "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"])
        self.assertEqual(RUN["arguments"]("/root/.cache/setup.exe", ["--lang=enUS"], "1920x1080", pfx),
                         ["explorer.exe", "/desktop=droiddeck,1920x1080", "Z:\\root\\.cache\\setup.exe", "--lang=enUS"])

    def test_a_games_own_program_is_left_to_steam(self):
        self.write("Steam/appcache/appinfo.vdf", appinfo({413150: STARDEW}))
        self.assertEqual(RUN["compat"]()["launch_programs"](STARDEW["config"]),
                         ["stardew valley.exe", "stardewvalley", "tools/setup.exe"])
        root = str(self.root)
        for relative, expected in (("Stardew Valley.exe", True), ("StardewValley", True), ("Tools/Setup.exe", True),
                                   ("createdump.exe", False)):
            with self.subTest(relative):
                program = str(self.game("413150", "Stardew Valley", relative))
                self.assertEqual(RUN["steam_launches"](RUN["locate"](program, root), program, root), expected)
        own = str(self.write("Download/Stardew Valley.exe", b"MZ"))
        self.assertFalse(RUN["steam_launches"](RUN["locate"](own, root), own, root))

    def test_the_tool_is_started_as_steam_starts_it(self):
        program = self.game("413150", "Stardew Valley", "Stardew Valley.exe")
        calls = []
        with mock.patch.dict(os.environ, {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.root)}), \
                mock.patch.object(RUN["os"], "execve", lambda *a: calls.append(a)), \
                mock.patch.object(RUN["os"], "chdir"), mock.patch.dict(RUN, {"say": lambda message: None}):
            RUN["main"]([str(program), "-fullscreen"])
        entry, argv, env = calls[0]
        self.assertEqual(entry, str(self.root / "compatibilitytools.d/droiddeck-proton-11-arm64/droiddeck-proton"))
        self.assertEqual(argv, [entry, "waitforexitandrun", str(program), "-fullscreen"])
        self.assertEqual(env["SteamAppId"], "413150")
        self.assertTrue((self.root / "steamapps/compatdata/413150").is_dir())

    def run_main(self, argv):
        calls, dirs = [], []
        with mock.patch.dict(os.environ, {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.root)}), \
                mock.patch.object(RUN["os"], "execve", lambda *a: calls.append(a)), \
                mock.patch.object(RUN["os"], "chdir", dirs.append), mock.patch.dict(RUN, {"say": lambda message: None}):
            code = RUN["main"](argv)
        return code, calls, dirs

    def test_without_steam_a_steam_game_starts_its_default_windows_launch(self):
        info = {"common": {"type": "Game", "oslist": "windows,linux"}, "config": {"launch": {
            "0": {"executable": "Bin\\Game.exe", "arguments": "-dx11 \"-name=Two Words\"", "workingdir": "bin",
                  "type": "default", "config": {"oslist": "windows"}},
            "1": {"executable": "game.sh", "type": "default", "config": {"oslist": "linux"}},
            "2": {"executable": "Beta.exe", "type": "default", "config": {"oslist": "windows", "betakey": "beta"}},
            "3": {"executable": "Tools\\Editor.exe", "type": "option1"},
        }}}
        self.write("Steam/appcache/appinfo.vdf", appinfo({413150: info}))
        program = self.game("413150", "Some Game", "bin/game.exe")
        self.game("413150", "Some Game", "Tools/Editor.exe")
        code, calls, dirs = self.run_main(["--app", "413150", "--options", "-windowed"])
        self.assertIsNone(code)
        entry, argv, env = calls[0]
        self.assertEqual(argv[1:], ["waitforexitandrun", str(program), "-dx11", "-name=Two Words", "-windowed"])
        self.assertEqual((env["SteamAppId"], env["STEAM_COMPAT_DATA_PATH"]),
                         ("413150", str(self.root / "steamapps/compatdata/413150")))
        self.assertEqual(dirs, [str(program.parent)])

    def test_without_steam_a_game_steam_cannot_start_is_refused(self):
        self.write("Steam/appcache/appinfo.vdf", appinfo({413150: STARDEW}))
        self.assertEqual(self.run_main(["--app", "413150"])[0], 2)
        self.assertEqual(self.run_main(["--app", "3384071994"])[0], 2)
        self.assertEqual(self.run_main(["--app", "0"])[0], 2)
        self.assertEqual(self.run_main(["--app", "game"])[0], 2)
        linux = {"common": {"type": "Game"}, "config": {"launch": {"0": {"executable": "run.sh", "config": {"oslist": "linux"}}}}}
        self.write("Steam/appcache/appinfo.vdf", appinfo({70: linux}))
        self.game("70", "Linux Game", "run.sh")
        self.assertEqual(self.run_main(["--app", "70"])[0], 2)

    def test_without_steam_a_shortcut_reuses_a_signed_prefix_folder(self):
        bat = self.write("Games/Epic/Game/.droiddeck-launch.bat", b"@echo off")
        start = self.tmp / "Games/Epic/Game/Binaries"
        start.mkdir()
        signed = self.root / "steamapps/compatdata/-1561598272"
        signed.mkdir(parents=True)
        code, calls, dirs = self.run_main(["--app", "2733369024", "--dir", str(start), "--", str(bat)])
        self.assertIsNone(code)
        env = calls[0][2]
        self.assertEqual(env["STEAM_COMPAT_DATA_PATH"], str(signed))
        self.assertFalse((self.root / "steamapps/compatdata/2733369024").exists())

    def test_a_launcher_run_keeps_the_resolved_compatdata_folder(self):
        bat = self.write("Games/Epic/Game/.droiddeck-launch.bat", b"@echo off")
        signed = self.root / "steamapps/compatdata/-1561598272"
        signed.mkdir(parents=True)
        other = self.tmp / "Library2/steamapps/compatdata/555"
        other.mkdir(parents=True)
        for compatdata, appid in ((signed, "2733369024"), (other, "555")):
            with self.subTest(compatdata=str(compatdata)):
                code, calls, dirs = self.run_main(["--prefix", appid, "--compatdata", str(compatdata), str(bat)])
                self.assertIsNone(code)
                self.assertEqual(calls[0][2]["STEAM_COMPAT_DATA_PATH"], str(compatdata))
        code, calls, dirs = self.run_main(["--prefix", "555", str(bat)])
        self.assertEqual(calls[0][2]["STEAM_COMPAT_DATA_PATH"], str(self.root / "steamapps/compatdata/555"))

    def test_prefix_folder_prefers_initialized_unsigned_over_empty_signed(self):
        signed = self.root / "steamapps/compatdata/-1561598272"
        signed.mkdir(parents=True)
        initialized = self.root / "steamapps/compatdata/2733369024/pfx"
        initialized.mkdir(parents=True)

        self.assertEqual(RUN["prefix_folder"](str(self.root), 2733369024), str(initialized.parent))

    def test_prefix_folder_prefers_initialized_signed_over_empty_unsigned(self):
        unsigned = self.root / "steamapps/compatdata/2733369024"
        unsigned.mkdir(parents=True)
        initialized = self.root / "steamapps/compatdata/-1561598272/pfx"
        initialized.mkdir(parents=True)

        self.assertEqual(RUN["prefix_folder"](str(self.root), 2733369024), str(initialized.parent))

    def test_prefix_folder_prefers_unsigned_when_initialization_ties(self):
        unsigned = self.root / "steamapps/compatdata/2733369024/pfx"
        unsigned.mkdir(parents=True)
        signed = self.root / "steamapps/compatdata/-1561598272/pfx"
        signed.mkdir(parents=True)

        self.assertEqual(RUN["prefix_folder"](str(self.root), 2733369024), str(unsigned.parent))

    def test_prefix_folder_skips_numeric_names_too_long_to_convert(self):
        from unittest.mock import patch
        folder = self.root / "steamapps/compatdata"
        folder.mkdir(parents=True)
        with patch.object(RUN["os"], "listdir", return_value=["9" * 5000]):
            self.assertEqual(RUN["prefix_folder"](str(self.root), 2733369024),
                             str(folder / "2733369024"))

    def test_without_steam_a_shortcut_keeps_its_prefix_folder_and_launch_options(self):
        bat = self.write("Games/Epic/Game/.droiddeck-launch.bat", b"@echo off")
        start = self.tmp / "Games/Epic/Game/Binaries"
        start.mkdir()
        code, calls, dirs = self.run_main(["--app", "2733369024", "--dir", str(start), "--options",
                                           "PROTON_LOG=1 %command% -epicportal", "--", str(bat)])
        self.assertIsNone(code)
        entry, argv, env = calls[0]
        self.assertEqual(argv[1:], ["waitforexitandrun", str(bat), "-epicportal"])
        self.assertEqual((env["STEAM_COMPAT_DATA_PATH"], env["SteamAppId"], env["SteamGameId"], env["PROTON_LOG"]),
                         (str(self.root / "steamapps/compatdata/2733369024"), "0", str(2733369024 << 32 | 0x02000000), "1"))
        self.assertTrue((self.root / "steamapps/compatdata/2733369024").is_dir())
        self.assertEqual(dirs, [str(start)])

    def write_account(self):
        self.write("Steam/config/loginusers.vdf",
                   '"users"\n{\n\t"76561198352563669"\n\t{\n\t\t"MostRecent"\t\t"1"\n\t}\n}\n')
        return "Steam/userdata/392297941/config/"

    def test_without_steam_and_options_a_game_takes_its_steam_launch_options(self):
        config = self.write_account()
        self.write(config + "localconfig.vdf", '"UserLocalConfigStore"\n{\n\t"Software"\n\t{\n\t\t"Valve"\n\t\t{\n'
                   '\t\t\t"Steam"\n\t\t\t{\n\t\t\t\t"apps"\n\t\t\t\t{\n\t\t\t\t\t"413150"\n\t\t\t\t\t{\n'
                   '\t\t\t\t\t\t"cloud"\n\t\t\t\t\t\t{\n\t\t\t\t\t\t\t"LaunchOptions"\t\t"-wrong"\n\t\t\t\t\t\t}\n'
                   '\t\t\t\t\t\t"LaunchOptions"\t\t"DXVK_HUD=1 %command% -d3d9 \\"-name=Two Words\\""\n'
                   '\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n')
        shortcuts = runpy.run_path(str(BIN / "droiddeck-steam-shortcuts"))
        self.write(config + "shortcuts.vdf", shortcuts["write"]({"shortcuts": {
            "0": {"appid": -1561598272, "AppName": "Batman", "LaunchOptions": "-d3d9"},
            "1": {"appid": -1, "AppName": "Other", "LaunchOptions": "-x"},
        }}))
        root = str(self.root)
        self.assertEqual(RUN["steam_options"]("413150", root), 'DXVK_HUD=1 %command% -d3d9 "-name=Two Words"')
        self.assertEqual(RUN["steam_options"]("2733369024", root), "-d3d9")
        self.assertEqual(RUN["steam_options"]("70", root), "")
        self.assertEqual(RUN["steam_options"]("2733369025", root), "")
        info = {"common": {"type": "Game"}, "config": {"launch": {
            "0": {"executable": "Game.exe", "type": "default", "config": {"oslist": "windows"}}}}}
        self.write("Steam/appcache/appinfo.vdf", appinfo({413150: info}))
        program = self.game("413150", "Some Game", "Game.exe")
        code, calls, _ = self.run_main(["--app", "413150"])
        self.assertIsNone(code)
        _, argv, env = calls[0]
        self.assertEqual(argv[1:], ["waitforexitandrun", str(program), "-d3d9", "-name=Two Words"])
        self.assertEqual(env["DXVK_HUD"], "1")
        bat = self.write("Games/GOG/Batman/Batman.exe", b"MZ")
        code, calls, _ = self.run_main(["--app", "2733369024", "--", str(bat)])
        self.assertEqual(calls[0][1][1:], ["waitforexitandrun", str(bat), "-d3d9"])
        code, calls, _ = self.run_main(["--app", "2733369024", "--options", "", "--", str(bat)])
        self.assertEqual(calls[0][1][1:], ["waitforexitandrun", str(bat)])

    def test_launch_options_split_like_steam_does(self):
        split = RUN["split_options"]
        self.assertEqual(split(""), ({}, []))
        self.assertEqual(split("-windowed -w 1280"), ({}, ["-windowed", "-w", "1280"]))
        self.assertEqual(split("A=1 B=\"x y\" gamemoderun %command% -dx12"), ({"A": "1", "B": "x y"}, ["-dx12"]))
        self.assertEqual(split("\"unterminated -x"), ({}, ["\"unterminated", "-x"]))


if __name__ == "__main__":
    unittest.main()
