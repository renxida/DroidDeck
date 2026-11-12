import contextlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
_loader = importlib.machinery.SourceFileLoader("droiddeck_scenario_under_test", str(TOOLS / "droiddeck-scenario"))
_spec = importlib.util.spec_from_loader(_loader.name, _loader)
scenario = importlib.util.module_from_spec(_spec)
_loader.exec_module(scenario)


class PureHelpersTest(unittest.TestCase):
    def test_a_whole_variable_keeps_its_type(self):
        self.assertEqual(7, scenario.substitute("${n}", {"n": 7}))
        self.assertEqual("app 7", scenario.substitute("app ${n}", {"n": 7}))
        self.assertEqual("${missing}", scenario.substitute("${missing}", {}))
        self.assertEqual({"a": [7, "x7"]}, scenario.substitute({"a": ["${n}", "x${n}"]}, {"n": 7}))
        self.assertEqual(3.5, scenario.substitute(3.5, {"n": 7}))

    def test_lookup_walks_dicts_and_list_indexes(self):
        data = {"a": {"b": [{"c": 1}, {"c": 2}]}}
        self.assertEqual(2, scenario.lookup(data, "a.b.1.c"))
        self.assertIsNone(scenario.lookup(data, "a.b.5.c"))
        self.assertIsNone(scenario.lookup(data, "a.x.c"))
        self.assertIsNone(scenario.lookup({"a": 3}, "a.b"))

    def test_checks(self):
        last = {"v": 5, "s": "hello", "l": [1, 2], "b": True}
        for step in ({"assert": "v", "equals": 5}, {"assert": "v", "in": [4, 5]}, {"assert": "s", "contains": "ell"},
                     {"assert": "l", "contains": 2}, {"assert": "v", "exists": True}, {"assert": "v", "not": 4},
                     {"assert": "v", "min": 5, "max": 5}):
            scenario.check(step, last)
        for step in ({"assert": "v", "equals": 4}, {"assert": "v", "in": [1]}, {"assert": "v", "contains": 5},
                     {"assert": "x", "exists": True}, {"assert": "v", "not": 5}, {"assert": "v", "min": 6},
                     {"assert": "v", "max": 4}, {"assert": "b", "min": 0}, {"assert": "s", "max": 9}):
            with self.assertRaises(scenario.StepFailed, msg=str(step)):
                scenario.check(step, last)

    def test_summary_uses_each_runs_metrics(self):
        runs = [{"metrics": {"fps": 30}}, {"metrics": {"fps": 60, "ms": 2}}, {"metrics": {"fps": 45}}]
        self.assertEqual({"fps": {"values": [30, 60, 45], "min": 30, "median": 45, "max": 60},
                          "ms": {"values": [2], "min": 2, "median": 2, "max": 2}}, scenario.summarize(runs))


class MainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.commands = []
        self.answers = {}

    def execute(self, args, adb, serial):
        self.commands.append(args.command)
        answer = self.answers.get(args.command, {"ok": True})
        if isinstance(answer, Exception):
            raise answer
        return answer

    def run_main(self, body, *extra, resolve=None):
        path = self.dir / "s.json"
        path.write_text(json.dumps(body) if not isinstance(body, str) else body)
        out = io.StringIO()
        resolve = resolve or (lambda adb, serial, log=False: serial or "S1")
        with mock.patch.object(scenario.ctl, "execute", self.execute), \
                mock.patch.object(scenario.ctl, "adb_path", lambda configured: configured or "adb"), \
                mock.patch.object(scenario.ctl, "resolve_serial", resolve), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = scenario.main([str(path), "--out", str(self.dir / "runs"), *extra])
        text = out.getvalue()
        return code, (json.loads(text) if text.strip() else None)

    def test_an_unreadable_scenario_exits_2(self):
        code, report = self.run_main("{not json")
        self.assertEqual(2, code)
        self.assertFalse(report["ok"])
        self.assertEqual([], self.commands)

    def test_a_failed_setup_exits_2_skips_the_runs_and_still_tears_down(self):
        self.answers["wake"] = scenario.ctl.ControlError("screen locked", "LOCKED")
        code, report = self.run_main({"name": "t", "setup": [["wake"]], "steps": [["state"]], "teardown": [["stop"]]})
        self.assertEqual(2, code)
        self.assertEqual([], report["runs"])
        self.assertIn("LOCKED", report["setupError"])
        self.assertEqual(["wake", "stop"], self.commands)

    def test_a_failed_before_fails_the_run_and_after_still_runs(self):
        self.answers["start"] = scenario.ctl.ControlError("no runtime", "NO_RUNTIME")
        self.answers["focus"] = {"ok": True}
        self.answers["logs"] = {"ok": True}
        with mock.patch.object(scenario.ctl, "screenshot", lambda *a: {"ok": True}):
            code, report = self.run_main({"name": "t", "before": [["start", "steam"]], "steps": [["state"]],
                                          "after": [["stop"]]})
        self.assertEqual(1, code)
        self.assertNotIn("state", self.commands)
        self.assertEqual("stop", self.commands[-1])
        self.assertIn("NO_RUNTIME", report["runs"][0]["error"])

    def test_an_unknown_step_fails_the_run(self):
        with mock.patch.object(scenario.ctl, "screenshot", lambda *a: {"ok": True}):
            code, report = self.run_main({"name": "t", "steps": [{"wait": 1}]})
        self.assertEqual(1, code)
        self.assertIn("Unknown step", report["runs"][0]["error"])

    def test_the_repeat_flag_and_typed_vars_override_the_file(self):
        self.answers["state"] = {"ok": True, "n": 12}
        code, report = self.run_main({"name": "t", "repeat": 5, "vars": {"want": 1},
                                      "steps": [["state"], {"assert": "n", "equals": "${want}"}]},
                                     "--repeat", "2", "--var", "want=12")
        self.assertEqual(0, code)
        self.assertEqual(2, report["passed"])
        self.assertEqual({"want": 12}, report["vars"])

    def test_no_device_is_a_failed_setup_not_a_failed_run(self):
        def resolve(adb, serial, log=False):
            raise scenario.ctl.DeviceResolutionError("No authorized Android device found")

        code, report = self.run_main({"name": "t", "steps": [["state"]]}, resolve=resolve)
        self.assertEqual(2, code)
        self.assertFalse(report["ok"])
        self.assertIn("No authorized Android device", report["error"])
        self.assertEqual([], self.commands)


if __name__ == "__main__":
    unittest.main()
