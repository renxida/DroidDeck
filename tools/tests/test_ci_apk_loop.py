import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

TOOLS = Path(__file__).resolve().parents[1]
SCRIPT = TOOLS / "ci_apk_loop.sh"

FAKE_GH = textwrap.dedent('''\
    #!/usr/bin/env python3
    import json, os, sys
    from pathlib import Path
    args = sys.argv[1:]
    with open(os.environ["FAKE_LOG"], "a") as log:
        log.write(json.dumps(["gh"] + args) + "\\n")
    if args[:2] == ["run", "list"]:
        run = os.environ.get("FAKE_RUN_ID", "")
        print(run)
    elif args[:2] == ["run", "download"]:
        folder = Path(args[args.index("-D") + 1])
        if os.environ.get("FAKE_EMPTY_ARTIFACT") != "1":
            (folder / "nested").mkdir(parents=True, exist_ok=True)
            (folder / "nested" / "DroidDeck.apk").write_bytes(b"apk-" + args[2].encode())
        else:
            folder.mkdir(parents=True, exist_ok=True)
    elif args[:2] == ["pr", "view"]:
        print(os.environ["FAKE_PR_BRANCH"])
    else:
        sys.exit(9)
''')

FAKE_ADB = textwrap.dedent('''\
    #!/usr/bin/env python3
    import json, os, sys
    args = sys.argv[1:]
    with open(os.environ["FAKE_LOG"], "a") as log:
        log.write(json.dumps(["adb"] + args) + "\\n")
    if args == ["devices", "-l"]:
        print("List of devices attached")
        print("SER1           device usb:1-1 product:p model:m")
        sys.exit(0)
    if args[:2] == ["-s", "SER1"]:
        rest = args[2:]
        if rest == ["get-state"]:
            print("device"); sys.exit(0)
        if rest[:2] == ["shell", "getprop"]:
            print("SER1"); sys.exit(0)
        if rest[:1] == ["install"]:
            failure = os.environ.get("FAKE_INSTALL_FAILURE")
            if failure:
                print("Failure [" + failure + "]"); sys.exit(1)
            print("Success"); sys.exit(0)
    sys.exit(9)
''')


class CiApkLoopTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.bin = self.dir / "bin"
        self.bin.mkdir()
        for name, body in (("gh", FAKE_GH), ("adb", FAKE_ADB)):
            path = self.bin / name
            path.write_text(body)
            path.chmod(0o755)
        self.log = self.dir / "calls.jsonl"
        self.cache = self.dir / "cache"

    def run_loop(self, *args, **env):
        environ = {k: v for k, v in os.environ.items() if k not in ("ADB_SERIAL", "ANDROID_SERIAL")}
        environ.update({
            "PATH": f"{self.bin}{os.pathsep}{environ.get('PATH', '')}",
            "ADB": str(self.bin / "adb"),
            "XDG_CACHE_HOME": str(self.cache),
            "DROIDDECK_CI_REPO": "owner/repo",
            "FAKE_LOG": str(self.log),
            "FAKE_RUN_ID": "77",
        })
        environ.update(env)
        return subprocess.run(["bash", str(SCRIPT), *args], capture_output=True, text=True, env=environ, timeout=60)

    def calls(self, tool=None):
        if not self.log.exists():
            return []
        found = [json.loads(line) for line in self.log.read_text().splitlines()]
        return [c[1:] for c in found if tool is None or c[0] == tool]

    def test_installs_the_newest_successful_run_and_caches_it(self):
        result = self.run_loop("perf/x")
        self.assertEqual(0, result.returncode, result.stderr)
        listing = self.calls("gh")[0]
        self.assertEqual(["run", "list", "-R", "owner/repo", "--workflow", "build.yml", "--branch", "perf/x",
                          "--status", "success"], listing[:10])
        apk = self.cache / "droiddeck-ci-apks" / "77" / "app-release.apk"
        self.assertEqual(b"apk-77", apk.read_bytes())
        self.assertFalse((self.cache / "droiddeck-ci-apks" / "77.part").exists())
        installs = [c for c in self.calls("adb") if "install" in c]
        self.assertEqual([["-s", "SER1", "install", "-r", "--no-incremental", str(apk)]], installs)
        self.assertIn("Installed on SER1.", result.stderr)

        again = self.run_loop("perf/x")
        self.assertEqual(0, again.returncode, again.stderr)
        self.assertEqual(1, sum(1 for c in self.calls("gh") if c[:2] == ["run", "download"]))
        self.assertEqual(2, sum(1 for c in self.calls("adb") if "install" in c))

    def test_a_pr_number_resolves_to_its_head_branch(self):
        result = self.run_loop("123", FAKE_PR_BRANCH="ux/pr-head")
        self.assertEqual(0, result.returncode, result.stderr)
        gh = self.calls("gh")
        self.assertEqual(["pr", "view", "123", "-R", "owner/repo"], gh[0][:5])
        self.assertIn("ux/pr-head", gh[1])

    def test_no_successful_run_stops_before_any_device_work(self):
        result = self.run_loop("cleanup/none", FAKE_RUN_ID="")
        self.assertEqual(1, result.returncode)
        self.assertIn("No successful Build APK run for cleanup/none on owner/repo.", result.stderr)
        self.assertEqual([], self.calls("adb"))

    def test_an_artifact_without_an_apk_is_an_error_and_not_cached(self):
        result = self.run_loop("perf/x", FAKE_EMPTY_ARTIFACT="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("Run 77 has no APK in droiddeck-apk.", result.stderr)
        self.assertFalse((self.cache / "droiddeck-ci-apks" / "77" / "app-release.apk").exists())
        self.assertEqual([], [c for c in self.calls("adb") if "install" in c])

    def test_a_signer_mismatch_explains_and_does_not_uninstall(self):
        result = self.run_loop("perf/x", FAKE_INSTALL_FAILURE="INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures")
        self.assertEqual(1, result.returncode)
        self.assertIn("another signer", result.stderr)
        self.assertFalse(any("uninstall" in c for c in self.calls("adb")))

    def test_other_install_failures_print_adb_output_only(self):
        result = self.run_loop("perf/x", FAKE_INSTALL_FAILURE="INSTALL_FAILED_INSUFFICIENT_STORAGE")
        self.assertEqual(1, result.returncode)
        self.assertIn("INSTALL_FAILED_INSUFFICIENT_STORAGE", result.stderr)
        self.assertNotIn("another signer", result.stderr)

    def test_scenario_arguments_go_to_droiddeck_scenario_with_the_serial(self):
        path = self.dir / "empty.json"
        path.write_text(json.dumps({"name": "empty", "steps": []}))
        result = self.run_loop("perf/x", str(path), "--repeat", "2", "--out", str(self.dir / "runs"))
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual("SER1", report["serial"])
        self.assertEqual(2, report["passed"])

    def test_an_unreadable_scenario_exits_2_after_installing(self):
        result = self.run_loop("perf/x", str(self.dir / "missing.json"))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("Cannot read scenario", json.loads(result.stdout)["error"])
        self.assertEqual(1, sum(1 for c in self.calls("adb") if "install" in c))


if __name__ == "__main__":
    unittest.main()
