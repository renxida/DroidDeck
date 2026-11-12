import io
import json
import os
from pathlib import Path
import runpy
import shutil
import sys
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LITMUS = runpy.run_path(str(BIN / "droiddeck-tso-litmus"))["main"].__globals__
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))

RUNTIME = ["coreclr.dll", "clrjit.dll", "hostfxr.dll", "hostpolicy.dll", "System.Private.CoreLib.dll",
           "System.Runtime.dll", "System.Console.dll", "Microsoft.Win32.Primitives.dll", "netstandard.dll",
           "mscorlib.dll", "api-ms-win-core-file-l1-1-0.dll"]
GAME = ["GodotSharp.dll", "sts2.dll", "libsentry.windows.x86_64.dll", "sts2.runtimeconfig.json", "sts2.deps.json"]

FAKE_PROTON_RUN = r'''#!{python}
import json, os, sys
args = sys.argv[1:]
out = args[args.index("--out") + 1]
path = out[2:].replace("\\", "/")
config = args[args.index("--config") + 1]
env = {{k: v for k, v in os.environ.items() if k.startswith(("FEX_", "DOTNET_"))}}
with open(os.environ["FAKE_LOG"], "a") as log:
    log.write(json.dumps({{"args": args, "keep": os.environ.get("DROIDDECK_KEEP_ENV"), "cwd": os.getcwd()}}) + "\n")
records = [{{"kind": "env", "config": config, "env": env, "erms": env.get("FEX_MEMCPYSETTSOENABLED") == "0",
            "gc_concurrent": env.get("DOTNET_gcConcurrent") != "0", "version": "9.0.4", "wine": "9.0", "cpus": 8}}]
vector_off = env.get("FEX_VECTORTSOENABLED") == "0"
records.append({{"kind": "test", "name": "refs-array-copy", "group": "a", "expect": "forbidden", "needs": "vector-tso",
                "samples": 100, "observed": 2 if vector_off else 0, "failures": 2 if vector_off else 0, "fresh": 10, "detail": {{}}}})
records.append({{"kind": "test", "name": "sb-int", "group": "d", "expect": "allowed", "needs": "none",
                "samples": 100, "observed": 7, "failures": 0, "detail": {{}}}})
records.append({{"kind": "bench", "name": "memcpy-1m", "value": 2000.0 if env.get("FEX_MEMCPYSETTSOENABLED") == "0" else 1000.0}})
with open(path, "w") as f:
    for record in records:
        f.write(json.dumps(record) + "\n")
    if env.get("DOTNET_gcConcurrent") != "0" or not vector_off:
        f.write(json.dumps({{"kind": "done", "failures": 0}}) + "\n")
print("tso-litmus: test=refs-array-copy group=a failures=0")
if env.get("DOTNET_gcConcurrent") == "0" and vector_off:
    print("Fatal error. Internal CLR error. (0x80131506)")
    sys.exit(134)
'''


class Temp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def files(self, folder, names):
        folder.mkdir(parents=True, exist_ok=True)
        for name in names:
            (folder / name).write_bytes(b"MZ " + name.encode())
        return folder

    def library(self, games, name="library"):
        library = self.tmp / name
        (library / "steamapps").mkdir(parents=True, exist_ok=True)
        for appid, installdir, runtime in games:
            (library / "steamapps" / ("appmanifest_%s.acf" % appid)).write_text(
                '"AppState" { "appid" "%s" "installdir" "%s" }' % (appid, installdir))
            install = library / "steamapps/common" / installdir
            self.files(install, ["game.exe"])
            if runtime:
                data = self.files(install / runtime, RUNTIME + GAME)
                (data / "sts2.runtimeconfig.json").write_text(json.dumps({"runtimeOptions": {
                    "tfm": "net9.0", "includedFrameworks": [{"name": "Microsoft.NETCore.App", "version": "9.0.4"}]}}))
        return library


class ConfigTest(unittest.TestCase):
    def test_the_five_settings_differ_only_in_what_they_name(self):
        plan = dict(LITMUS["configs"](dict(GAME_ENV["DOTNET_FEX"])))
        self.assertEqual(list(plan), ["full-tso", "memcpy-tso-off", "vector-tso-off", "both-off", "both-off-gc-blocking"])
        full = plan["full-tso"]
        for name in LITMUS["TSO_NAMES"]:
            self.assertEqual(full[name], "1")
        self.assertEqual(full["DOTNET_gcConcurrent"], "1")

        def changed(name):
            return {k: v for k, v in plan[name].items() if full.get(k) != v}

        self.assertEqual(changed("memcpy-tso-off"), {"FEX_MEMCPYSETTSOENABLED": "0"})
        self.assertEqual(changed("vector-tso-off"), {"FEX_VECTORTSOENABLED": "0"})
        self.assertEqual(changed("both-off"), {"FEX_MEMCPYSETTSOENABLED": "0", "FEX_VECTORTSOENABLED": "0"})
        self.assertEqual(changed("both-off-gc-blocking"),
                         {"FEX_MEMCPYSETTSOENABLED": "0", "FEX_VECTORTSOENABLED": "0", "DOTNET_gcConcurrent": "0"})

    def test_settings_start_from_the_game_environments_dotnet_names(self):
        base = LITMUS["dotnet_fex"]()
        self.assertEqual(base, GAME_ENV["DOTNET_FEX"])
        relaxed = dict(base, FEX_VECTORTSOENABLED="0", FEX_MEMCPYSETTSOENABLED="0", DOTNET_gcConcurrent="0", FEX_X87REDUCEDPRECISION="0")
        full = dict(LITMUS["configs"](relaxed))["full-tso"]
        self.assertEqual((full["FEX_VECTORTSOENABLED"], full["FEX_MEMCPYSETTSOENABLED"], full["DOTNET_gcConcurrent"]), ("1", "1", "1"))
        self.assertEqual(full["FEX_X87REDUCEDPRECISION"], "0")

    def test_the_caller_keeps_every_setting_it_passes(self):
        env = LITMUS["environment"]({"HOME": "/root", "FEX_TSOENABLED": "0"}, {"FEX_TSOENABLED": "1", "DOTNET_gcConcurrent": "0"})
        self.assertEqual(env["FEX_TSOENABLED"], "1")
        self.assertEqual(env["DROIDDECK_KEEP_ENV"], "DOTNET_gcConcurrent FEX_TSOENABLED")
        self.assertEqual(env["HOME"], "/root")


class RuntimeTest(Temp):
    def test_slay_the_spire_2_comes_before_other_dotnet_games(self):
        library = self.library([("1000", "Other", "Other_Data/managed"), ("2868840", "Slay the Spire 2", "data_sts2_windows_x86_64"),
                                ("20", "NoDotnet", None)])
        with mock.patch.dict(LITMUS, {"libraries": lambda root: [str(library)]}):
            appid, found_library, folder, version = LITMUS["find_runtime"]("/steam")
        self.assertEqual(appid, "2868840")
        self.assertEqual(found_library, str(library))
        self.assertEqual(folder, str(library / "steamapps/common/Slay the Spire 2/data_sts2_windows_x86_64"))
        self.assertEqual(version, "9.0.4")

    def test_any_dotnet_game_is_used_without_slay_the_spire_2(self):
        library = self.library([("20", "NoDotnet", None), ("1000", "Other", "Other_Data/managed")])
        with mock.patch.dict(LITMUS, {"libraries": lambda root: [str(library)]}):
            self.assertEqual(LITMUS["find_runtime"]("/steam")[0], "1000")
        empty = self.library([("20", "NoDotnet", None)], "empty")
        with mock.patch.dict(LITMUS, {"libraries": lambda root: [str(empty)]}):
            self.assertIsNone(LITMUS["find_runtime"]("/steam"))

    def test_the_app_folder_holds_the_runtime_and_the_test_but_not_the_game(self):
        runtime = self.files(self.tmp / "data", RUNTIME + GAME)
        litmus = self.files(self.tmp / "litmus", ["tso-litmus.exe", "tso-litmus.dll"])
        farm = self.tmp / "work/app"
        self.files(farm, ["stale.dll"])
        linked = LITMUS["prepare"](str(runtime), "9.0.4", str(litmus), str(farm))
        self.assertEqual(linked, len(RUNTIME))
        names = sorted(os.listdir(farm))
        self.assertEqual(names, sorted(RUNTIME + ["tso-litmus.exe", "tso-litmus.dll", "tso-litmus.runtimeconfig.json"]))
        self.assertEqual(os.readlink(farm / "coreclr.dll"), str(runtime / "coreclr.dll"))
        self.assertFalse((farm / "tso-litmus.exe").is_symlink())
        options = json.loads((farm / "tso-litmus.runtimeconfig.json").read_text())["runtimeOptions"]
        # Self-contained: no "framework(s)", so the apphost's own hostfxr runs the game's runtime from this folder.
        self.assertNotIn("frameworks", options)
        self.assertNotIn("framework", options)
        self.assertEqual(options["includedFrameworks"], [{"name": "Microsoft.NETCore.App", "version": "9.0.4"}])
        self.assertTrue(options["configProperties"]["System.Globalization.Invariant"])
        self.assertNotIn("System.GC.Concurrent", options["configProperties"])
        game = GAME_ENV["Game"](str(farm / "tso-litmus.exe"), [])
        self.assertFalse(GAME_ENV["is_godot"](game))
        self.assertIsNone(GAME_ENV["godot_sentry"](game))

    def test_the_command_runs_in_the_games_prefix_and_writes_into_the_logs(self):
        cmd = LITMUS["command"]("/root/.cache/app", "2868840", "/lib/steamapps/compatdata/2868840", 60, "both-off",
                                "/data/sessions/s1/tso-litmus/both-off.jsonl")
        self.assertEqual(cmd[:6], [LITMUS["PROTON_RUN"], "--prefix", "2868840", "--compatdata", "/lib/steamapps/compatdata/2868840", "--"])
        self.assertEqual(cmd[6:], ["/root/.cache/app/tso-litmus.exe", "--seconds", "60", "--config", "both-off",
                                   "--out", "Z:\\data\\sessions\\s1\\tso-litmus\\both-off.jsonl"])


class SummaryTest(unittest.TestCase):
    ENV = {"kind": "env", "env": {"FEX_VECTORTSOENABLED": "0", "DOTNET_gcConcurrent": "1"}, "erms": True, "gc_concurrent": True}

    def test_failures_and_allowed_reorderings_are_kept_apart(self):
        items = [self.ENV,
                 {"kind": "test", "name": "refs-array-copy", "expect": "forbidden", "needs": "vector-tso", "observed": 3, "failures": 3},
                 {"kind": "test", "name": "mp-int", "expect": "forbidden", "needs": "none", "observed": 1, "failures": 1},
                 {"kind": "test", "name": "sb-int", "expect": "allowed", "needs": "none", "observed": 50, "failures": 0},
                 {"kind": "bench", "name": "a", "value": 100.0}, {"kind": "bench", "name": "b", "value": 400.0},
                 {"kind": "done"}]
        summary = LITMUS["summarize"](items)
        self.assertEqual(summary["failures"], 4)
        self.assertEqual(summary["unexpected"], ["mp-int"])
        self.assertEqual(summary["allowed"], {"sb-int": 50})
        self.assertAlmostEqual(summary["throughput"], 200.0)
        line = LITMUS["config_line"]("vector-tso-off", summary, 100.0, {"FEX_VECTORTSOENABLED": "0", "DOTNET_gcConcurrent": "1"})
        self.assertTrue(line.startswith("config=vector-tso-off failures=4 state=finished throughput=200.0MB/s (x2.00 vs full-tso)"), line)
        self.assertIn("failed=mp-int:1,refs-array-copy:3", line)
        self.assertIn("UNEXPECTED=mp-int", line)
        self.assertIn("applied=yes", line)

    def test_a_run_without_its_last_record_is_a_crash(self):
        summary = LITMUS["summarize"]([self.ENV], 134, "Fatal error. Internal CLR error. (0x80131506)")
        self.assertTrue(summary["crashed"])
        line = LITMUS["config_line"]("both-off", summary, None, {"FEX_VECTORTSOENABLED": "1"})
        self.assertIn("state=CRASHED", line)
        self.assertIn("0x80131506", line)
        self.assertIn("applied=NO(FEX_VECTORTSOENABLED=0)", line)
        self.assertIn("state=no-start", LITMUS["config_line"]("x", LITMUS["summarize"]([], "timeout")))

    def test_the_check_rejects_forbidden_outcomes_on_real_x86(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run.jsonl"
            ok = [{"kind": "env"}, {"kind": "test", "name": "mp-int", "failures": 0}, {"kind": "done"}]
            path.write_text("\n".join(json.dumps(r) for r in ok) + "\n")
            with mock.patch("sys.stderr", io.StringIO()):
                self.assertEqual(LITMUS["check"](str(path)), 0)
                path.write_text("\n".join(json.dumps(r) for r in ok[:1] + [{"kind": "test", "name": "mp-int", "failures": 2}] + ok[2:]))
                self.assertEqual(LITMUS["check"](str(path)), 1)
                path.write_text("tso-litmus-json: " + json.dumps(ok[0]) + "\n")
                self.assertEqual(LITMUS["check"](str(path)), 1)


class EndToEndTest(Temp):
    def test_each_setting_runs_once_and_lands_in_the_session_logs(self):
        library = self.library([("2868840", "Slay the Spire 2", "data_sts2_windows_x86_64")])
        litmus = self.files(self.tmp / "litmus", ["tso-litmus.exe", "tso-litmus.dll"])
        fake = self.tmp / "droiddeck-proton-run"
        fake.write_text(FAKE_PROTON_RUN.format(python=sys.executable))
        fake.chmod(0o755)
        session = self.tmp / "session"
        calls = self.tmp / "calls.jsonl"
        stderr = io.StringIO()
        env = {"BL_DEBUG_DIR": str(session), "FAKE_LOG": str(calls), "FEX_VECTORTSOENABLED": "1"}
        with mock.patch.dict(os.environ, env), mock.patch("sys.stderr", stderr), \
                mock.patch.dict(LITMUS, {"libraries": lambda root: [str(library)], "PROTON_RUN": str(fake),
                                         "LITMUS_DIR": str(litmus), "WORK": str(self.tmp / "work")}):
            self.assertEqual(LITMUS["main"](["--seconds", "5"]), 0)
        log = stderr.getvalue()
        runs = [json.loads(line) for line in calls.read_text().splitlines()]
        self.assertEqual([r["args"][r["args"].index("--config") + 1] for r in runs],
                         ["full-tso", "memcpy-tso-off", "vector-tso-off", "both-off", "both-off-gc-blocking"])
        self.assertTrue(all("FEX_VECTORTSOENABLED" in r["keep"].split() for r in runs))
        self.assertIn("tso-litmus: config=full-tso failures=0 state=finished throughput=1000.0MB/s (x1.00 vs full-tso)", log)
        self.assertIn("tso-litmus: config=memcpy-tso-off failures=0 state=finished throughput=2000.0MB/s (x2.00 vs full-tso) erms=yes", log)
        self.assertIn("tso-litmus: config=both-off failures=2 state=finished", log)
        self.assertIn("tso-litmus: config=both-off-gc-blocking failures=2 state=CRASHED", log)
        self.assertIn("applied=yes", log)
        self.assertIn("tso-litmus: speed memcpy-1m: full-tso=1000(x1.00) memcpy-tso-off=2000(x2.00)", log)
        self.assertIn("tso-litmus: verdict: full-tso=ok memcpy-tso-off=ok vector-tso-off=FAILED both-off=FAILED both-off-gc-blocking=FAILED", log)
        results = json.loads((session / "tso-litmus/results.json").read_text())
        self.assertEqual(results["app"], "2868840")
        self.assertEqual(results["runtime_version"], "9.0.4")
        self.assertEqual([c["name"] for c in results["configs"]][-1], "both-off-gc-blocking")
        self.assertTrue((session / "tso-litmus/both-off.jsonl").is_file())
        self.assertIn("0x80131506", (session / "tso-litmus/both-off-gc-blocking.log").read_text())

    def test_nothing_runs_without_a_dotnet_game(self):
        library = self.library([("20", "NoDotnet", None)])
        litmus = self.files(self.tmp / "litmus", ["tso-litmus.exe", "tso-litmus.dll"])
        stderr = io.StringIO()
        with mock.patch.dict(os.environ, {"BL_DEBUG_DIR": str(self.tmp / "s")}), mock.patch("sys.stderr", stderr), \
                mock.patch.dict(LITMUS, {"libraries": lambda root: [str(library)], "LITMUS_DIR": str(litmus),
                                         "WORK": str(self.tmp / "work")}):
            self.assertEqual(LITMUS["main"]([]), 1)
        self.assertIn("install Slay the Spire 2", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
