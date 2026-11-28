import contextlib
import io
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest import mock


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
BRANCH = runpy.run_path(str(BIN / "droiddeck-steam-branch"))
APPINFO = runpy.run_path(str(Path(__file__).with_name("test_droiddeck_fex.py")))["AppInfoTest"].appinfo
APP = "2868840"

MANIFEST = '''"AppState"
{
\t"appid"\t\t"2868840"
\t"name"\t\t"Slay the Spire 2"
\t"StateFlags"\t\t"%s"
\t"installdir"\t\t"Slay the Spire 2"
\t"buildid"\t\t"%s"
\t"InstalledDepots"
\t{
\t\t"2868841"
\t\t{
\t\t\t"manifest"\t\t"123"
\t\t}
\t}
\t"UserConfig"
\t{
\t\t"language"\t\t"english"%s
\t}
\t"MountedConfig"
\t{
\t\t"language"\t\t"english"
\t}
}
'''


def branches(**listed):
    return {
        "common": {"type": "Game", "oslist": "windows"},
        "depots": {"2868841": {"maxsize": "1"}, "branches": listed},
    }


class SteamBranchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "Steam"
        self.steamapps = self.root / "steamapps"
        (self.steamapps / "common").mkdir(parents=True)
        (self.root / "appcache").mkdir()
        self.requests = Path(self.temp.name) / "steam-branch"
        self.requests.mkdir()
        BRANCH["compat"]()["APPINFO_CACHE"].clear()
        self.listing(**{
            "public": {"buildid": "100", "timeupdated": "10"},
            "public-beta": {"buildid": "200", "timeupdated": "30", "description": "Public beta"},
            "old-beta": {"buildid": "150", "timeupdated": "20"},
            "qa-beta": {"buildid": "300", "timeupdated": "40", "pwdrequired": "1"},
            "previous": {"buildid": "90", "timeupdated": "50"},
        })

    def listing(self, **listed):
        BRANCH["compat"]()["APPINFO_CACHE"].clear()
        (self.root / "appcache" / "appinfo.vdf").write_bytes(APPINFO({int(APP): branches(**listed)}))

    def install(self, branch="public", flags=4, build="100", content="stable"):
        key = '' if branch == "public" else '\n\t\t"BetaKey"\t\t"%s"' % branch
        (self.steamapps / ("appmanifest_%s.acf" % APP)).write_text(MANIFEST % (flags, build, key))
        game = self.steamapps / "common" / "Slay the Spire 2"
        game.mkdir(exist_ok=True)
        (game / "SlayTheSpire2.pck").write_text(content)

    def want(self, branch):
        (self.requests / APP).write_text(branch + "\n")

    def run_main(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = BRANCH["main"](list(args))
        return code, out.getvalue()

    def apply(self):
        return self.run_main("apply", str(self.root), str(self.requests))

    def live(self):
        manifest = BRANCH["Manifest"](str(self.steamapps / ("appmanifest_%s.acf" % APP)))
        return manifest, (self.steamapps / "common" / "Slay the Spire 2" / "SlayTheSpire2.pck").read_text()

    def parked(self, branch):
        return self.steamapps / "kestrel-branches" / APP / branch

    def test_manifest_round_trips(self):
        text = MANIFEST % (4, "100", '\n\t\t"BetaKey"\t\t"public-beta"')
        self.assertEqual(BRANCH["parse"](BRANCH["dump"](BRANCH["parse"](text))), BRANCH["parse"](text))
        self.assertEqual(BRANCH["dump"](BRANCH["parse"](text)), text)

    def test_branches_are_read_in_the_quick_appinfo_mode_too(self):
        with mock.patch.dict(os.environ, {"DROIDDECK_EXP_ALL": "1"}):
            self.listing(public={"buildid": "1"}, **{"public-beta": {"buildid": "2"}})
            self.assertEqual(BRANCH["resolve"](str(self.root), APP, "beta"), "public-beta")

    def test_beta_resolves_to_the_newest_open_beta_branch(self):
        self.assertEqual(BRANCH["resolve"](str(self.root), APP, "beta"), "public-beta")
        self.assertEqual(BRANCH["resolve"](str(self.root), APP, "public"), "public")
        self.assertIsNone(BRANCH["resolve"](str(self.root), APP, "qa-beta"))
        self.listing(public={"buildid": "1"}, previous={"buildid": "2"})
        self.assertIsNone(BRANCH["resolve"](str(self.root), APP, "beta"))

    def test_first_switch_keeps_a_copy_and_asks_steam_for_the_beta(self):
        self.install()
        self.want("beta")
        self.assertEqual(self.run_main("pending", str(self.root), str(self.requests), APP), (0, "public-beta\n"))
        self.apply()
        manifest, content = self.live()
        self.assertEqual(manifest.branch, "public-beta")
        self.assertEqual(manifest.value("buildid"), "100")
        self.assertEqual(content, "stable")
        self.assertEqual((self.parked("public") / "files" / "SlayTheSpire2.pck").read_text(), "stable")
        kept = BRANCH["Manifest"](str(self.parked("public") / "appmanifest.acf"))
        self.assertEqual(kept.branch, "public")
        self.assertEqual(self.run_main("pending", str(self.root), str(self.requests), APP)[0], 1)

    def test_switching_back_swaps_the_parked_install_without_a_download(self):
        self.install()
        self.want("beta")
        self.apply()
        self.install("public-beta", build="200", content="beta")
        self.want("public")
        self.apply()
        manifest, content = self.live()
        self.assertEqual((manifest.branch, manifest.value("buildid"), content), ("public", "100", "stable"))
        self.assertFalse(self.parked("public").exists())
        self.assertEqual((self.parked("public-beta") / "files" / "SlayTheSpire2.pck").read_text(), "beta")
        self.want("beta")
        self.apply()
        manifest, content = self.live()
        self.assertEqual((manifest.branch, manifest.value("buildid"), content), ("public-beta", "200", "beta"))
        self.assertTrue((self.parked("public") / "files").is_dir())
        self.assertFalse(self.parked("public-beta").exists())

    def test_an_install_mid_update_switches_in_place(self):
        self.install(flags=6)
        self.want("beta")
        self.apply()
        self.assertEqual(self.live()[0].branch, "public-beta")
        self.assertFalse(self.parked("public").exists())

    def test_low_space_switches_in_place(self):
        self.install()
        self.want("beta")
        with mock.patch.dict(BRANCH["switch"].__globals__, free_bytes=lambda _path: 1 << 20):
            self.apply()
        self.assertEqual(self.live()[0].branch, "public-beta")
        self.assertFalse(self.parked("public").exists())

    def test_nothing_changes_without_an_open_beta_or_a_request(self):
        self.install()
        self.apply()
        self.assertEqual(self.live()[0].branch, "public")
        self.listing(public={"buildid": "1"})
        self.want("beta")
        code, out = self.apply()
        self.assertEqual(self.live()[0].branch, "public")
        self.assertIn("no open beta branch", out)
        self.assertEqual(self.run_main("pending", str(self.root), str(self.requests), APP)[0], 1)

    def test_a_stale_parked_copy_of_the_live_branch_is_removed(self):
        self.install("public-beta", build="200", content="beta")
        (self.parked("public-beta") / "files").mkdir(parents=True)
        self.want("beta")
        self.apply()
        self.assertFalse(self.parked("public-beta").exists())
        self.assertEqual(self.live()[1], "beta")

    def test_staging_for_the_app_is_dropped_on_a_switch(self):
        self.install()
        staged = self.steamapps / "downloading" / APP
        staged.mkdir(parents=True)
        self.want("beta")
        self.apply()
        self.assertFalse(staged.exists())


if __name__ == "__main__":
    unittest.main()
