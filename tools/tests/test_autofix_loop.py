"""Autofix loop: ledger state, history bisect and flag association, CI classification, config guards,
and a sweep over a synthetic telemetry clone with a fake triage. All data here is made up."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "autofix"))
import ci  # noqa: E402
import config  # noqa: E402
import history  # noqa: E402
import ledger  # noqa: E402
import sweep  # noqa: E402

GIT_ENV = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
           "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid", "GIT_CONFIG_GLOBAL": os.devnull}


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True,
                          env=GIT_ENV).stdout.strip()


def make_repo(tmp, subjects):
    repo = Path(tmp) / "fork"
    repo.mkdir()
    git(repo, "init", "-q", "-b", "switchboard")
    shas = []
    for i, s in enumerate(subjects):
        (repo / "f.txt").write_text(str(i))
        git(repo, "add", "f.txt")
        git(repo, "commit", "-q", "-m", s)
        shas.append(git(repo, "rev-parse", "HEAD"))
    return repo, shas


def verdict(run, commit, sigs=(), stage="in_game", flags=(), metrics=None, started=None, owner="fex"):
    return {"run": run, "commit": commit[:7], "stage": stage, "experiments": list(flags),
            "outcome": "fail" if sigs else "ok", "started_utc": started or run.split("/")[-1][:-4],
            "signatures": [{"id": s, "severity": "fail", "evidence": [f"{s} line"], "owner": owner} for s in sigs],
            "metrics": metrics or {}}


class LedgerTest(unittest.TestCase):
    def test_state_follows_events(self):
        with tempfile.TemporaryDirectory() as tmp:
            led = ledger.Ledger.at(tmp)
            led.append("seen", run="runs/a/d/1.zip", blob="b1")
            led.append("triaged", run="runs/a/d/2.zip", blob="b2",
                       verdict={"signatures": [{"id": "x", "severity": "fail"}]})
            led.append("ci_failed", run_id=7, workflow="Build", branch="b", sha="abc", created_at="2026-11-14T10:00:00Z")
            led.append("item_opened", sig="x", first_seen="t", owner="fex")
            led.append("mitigated", sig="x", action="parked fex-trim")
            led.append("attempt", sig="x", branch="autofix/x/1", hypothesis="h")
            led.append("attempt_result", sig="x", branch="autofix/x/1", conclusion="success")
            led.append("ready_to_promote", sig="x", branch="autofix/x/1", sha="deadbee", summary="s", files=["a"])
            st = led.state()
            self.assertEqual(st.processed_runs, {"runs/a/d/1.zip@b1", "runs/a/d/2.zip@b2"})
            self.assertIn(7, st.ci_run_ids)
            self.assertEqual(st.ci_cursor, "2026-11-14T10:00:00Z")
            self.assertEqual(st.items["x"]["status"], "ready_to_promote")
            self.assertEqual(st.items["x"]["attempts"]["autofix/x/1"]["conclusion"], "success")
            led.append("promoted", sig="x", commit="deadbee")
            led.append("item_opened", sig="x", first_seen="t", owner="fex")   # seen again after the fix
            st = led.state()
            self.assertEqual(st.items["x"]["failed_verifies"], 1)
            led.append("verified", sig="x", run="runs/a/d/3.zip")
            led.append("closed", sig="x")
            self.assertEqual(led.state().open_items(), {})

    def test_same_path_new_blob_is_unprocessed_and_torn_line_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            led = ledger.Ledger.at(tmp)
            led.append("seen", run="runs/a/d/1.zip", blob="b1")
            with open(led.path, "a") as f:
                f.write('{"event": "seen", "run"')
            st = led.state()
            self.assertNotIn(ledger.run_key("runs/a/d/1.zip", "b2"), st.processed_runs)

    def test_rejects_bad_events(self):
        with self.assertRaises(ValueError):
            ledger.make_event("nonsense")
        with self.assertRaises(ValueError):
            ledger.make_event("escalated", sig="x", reason="r", needs="someone")
        with self.assertRaises(ValueError):
            ledger.make_event("promoted", sig="x")


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo, self.shas = make_repo(self.tmp.name, [f"c{i}" for i in range(6)])
        self.commits, self.info = history.first_parent(self.repo, need=[s[:7] for s in self.shas], fetch=False)

    def tearDown(self):
        self.tmp.cleanup()

    def test_first_parent_newest_first(self):
        self.assertEqual([c["subject"] for c in self.commits], [f"c{i}" for i in reversed(range(6))])
        self.assertEqual(self.info["missing"], [])

    def test_bisect_finds_range_between_last_good_and_first_bad(self):
        s = self.shas
        vs = [verdict("runs/x/d/1.zip", s[0]), verdict("runs/x/d/2.zip", s[1]),
              verdict("runs/x/d/3.zip", s[4], ["hang"], stage="game_exec"),
              verdict("runs/x/d/4.zip", s[5], ["hang"], stage="game_exec"),
              verdict("runs/x/d/5.zip", s[3], stage="steam_ui")]   # never got as far: not good evidence
        b = history.bisect("hang", vs, self.commits)
        self.assertEqual(b["last_good"]["subject"], "c1")
        self.assertEqual(b["first_bad"]["subject"], "c4")
        self.assertEqual([c["subject"] for c in b["range"]], ["c4", "c3", "c2"])
        self.assertEqual(b["runs_seen"], 2)
        self.assertFalse(b["intermittent"])

    def test_bisect_single_suspect_and_not_seen_since(self):
        s = self.shas
        vs = [verdict("runs/x/d/1.zip", s[1]), verdict("runs/x/d/2.zip", s[2], ["crash"]),
              verdict("runs/x/d/3.zip", s[4])]
        b = history.bisect("crash", vs, self.commits)
        self.assertEqual(b["range_size"], 1)
        self.assertEqual(b["not_seen_since"]["subject"], "c4")

    def test_flag_association_needs_support_and_split(self):
        s = self.shas[0]
        vs = [verdict(f"runs/x/d/b{i}.zip", s, ["oom"], flags=["fex-trim", "sched"]) for i in range(3)]
        vs += [verdict("runs/x/d/g0.zip", s, flags=["fex-trim", "sched", "memory"])]
        vs += [verdict(f"runs/x/d/g{i}.zip", s, flags=["sched"]) for i in range(1, 6)]
        fa = history.flag_association("oom", vs)
        self.assertEqual([r["flag"] for r in fa["suspects"]], ["fex-trim"])   # sched is in every run
        self.assertEqual(fa["suspects"][0]["on_bad"], 3)
        self.assertLess(fa["suspects"][0]["fisher_p"], 0.1)
        self.assertEqual(history.flag_association("oom", vs[2:])["suspects"], [])   # one bad run is not enough
        # two bad runs out of three with the flag against none of two without could be chance
        few = [vs[0], vs[1], vs[3], vs[4], vs[5]]
        self.assertEqual(history.flag_association("oom", few)["suspects"], [])

    def test_metric_step_change(self):
        s = self.shas
        vs = [verdict(f"runs/x/d/{i}.zip", s[i // 2], metrics={"fps_p50": 30.0, "steam_ui_s": 10})
              for i in range(6)]
        vs += [verdict("runs/x/d/9.zip", s[3], metrics={"fps_p50": 22.0, "steam_ui_s": 10.5}, flags=["memory"])]
        regs = history.metric_regressions(vs, self.commits)
        self.assertEqual([(r["metric"], r["commit"]) for r in regs], [("fps_p50", s[3][:7])])
        self.assertEqual(regs[0]["flags_added"], ["memory"])


SAMPLE_LOG = """2026-11-14T16:00:00.0000000Z ##[group]Run cmake -B build
2026-11-14T16:00:00.1000000Z shell: /usr/bin/bash -e {0}
2026-11-14T16:00:00.2000000Z ##[endgroup]
2026-11-14T16:00:01.0000000Z -- Configuring done (4.4s)
2026-11-14T16:00:01.1000000Z CMake Error:
2026-11-14T16:00:01.2000000Z   Cannot find source file:
2026-11-14T16:00:01.3000000Z     /include/c++/16.1.1/bits/std.cc
2026-11-14T16:00:02.0000000Z ##[error]Process completed with exit code 1.
2026-11-14T16:00:03.0000000Z Post job cleanup.
"""


def fake_api(runs, jobs, logs, successes=()):
    def api(path):
        if "/actions/runs?status=failure" in path:
            return {"workflow_runs": runs}
        if "/actions/runs?status=cancelled" in path:
            return {"workflow_runs": []}
        if path.endswith("/jobs?per_page=100"):
            rid = int(path.split("/runs/")[1].split("/")[0])
            return {"jobs": jobs[rid]}
        if "/workflows/" in path:
            branch = path.split("branch=")[1].split("&")[0]
            return {"workflow_runs": [r for r in successes if r["head_branch"] == branch]}
        raise AssertionError(path)

    def text(path):
        jid = int(path.split("/jobs/")[1].split("/")[0])
        if jid not in logs:
            raise RuntimeError("blob storage blocked")
        return logs[jid]
    return api, text


def ci_run(rid, branch, wf="build-fex.yml", created="2026-11-14T16:40:00Z"):
    return {"id": rid, "name": wf, "path": f".github/workflows/{wf}", "workflow_id": 1, "head_branch": branch,
            "head_sha": f"{rid:07x}", "conclusion": "failure", "created_at": created}


def ci_job(jid, step="Build in Arch Linux ARM"):
    return {"id": jid, "name": "build", "conclusion": "failure", "steps": [{"name": step, "conclusion": "failure"}]}


class CiTest(unittest.TestCase):
    def test_same_cmake_error_on_many_branches_is_one_env_signature(self):
        runs = [ci_run(i, b) for i, b in enumerate(["emu/a", "emu/b", "perf/emu-fex"], 1)]
        api, text = fake_api(runs, {i: [ci_job(100 + i)] for i in (1, 2, 3)}, {100 + i: SAMPLE_LOG for i in (1, 2, 3)})
        recs = ci.collect(since="2026-11-14", api=api, text_api=text)
        self.assertEqual({r["sig"] for r in recs}, {"ci:build-fex:build-in-arch-linux-arm:cmake-error-cannot-find-source-file"})
        self.assertEqual({r["cls"] for r in recs}, {"env"})

    def test_test_failure_merged_into_many_branches_stays_code_and_gate_job_looked_at_last(self):
        runs = [ci_run(i, b, "build.yml") for i, b in enumerate(["perf/switchboard", "perf/a", "perf/b"], 1)]
        gate = {"id": 0, "name": "Compile and test APK", "conclusion": "failure",
                "steps": [{"name": "Require compilation and every test job", "conclusion": "failure"}]}
        py = "##[group]Run tools/ci/run-python-tests.sh\n##[endgroup]\n##[group]group-0\nFAIL: test_x (test_y.Z.test_x)\n" \
             "AssertionError: 1 != 2\n##[endgroup]\n##[error]group-0.list failed\n"
        jobs = {i: [dict(gate, id=200 + i), ci_job(100 + i, "Python tests")] for i in (1, 2, 3)}
        logs = {**{100 + i: py for i in (1, 2, 3)}, **{200 + i: "##[error]Process completed with exit code 1.\n" for i in (1, 2, 3)}}
        api, text = fake_api(runs, jobs, logs)
        recs = ci.collect(api=api, text_api=text)
        self.assertEqual({r["sig"] for r in recs}, {"ci:build:python-tests:fail-test-x-test-y-z-test-x"})
        self.assertEqual({r["cls"] for r in recs}, {"code"})

    def test_classes_and_already_fixed(self):
        runs = [ci_run(1, "perf/switchboard", "build.yml"), ci_run(2, "x", "build.yml"), ci_run(3, "y", "build.yml")]
        logs = {101: "2026-11-14T16:00:00Z ##[endgroup]\nFooTest > bar FAILED\n##[error]Process completed with exit code 1.\n",
                102: "2026-11-14T16:00:00Z ##[endgroup]\nfatal: unable to access: Could not resolve host: github.com\n##[error]x\n"}
        later = {"id": 9, "head_branch": "perf/switchboard", "created_at": "2026-11-14T17:00:00Z", "head_sha": "abcdef1"}
        api, text = fake_api(runs, {1: [ci_job(101, "Unit tests")], 2: [ci_job(102, "Fetch")],
                                    3: [ci_job(103, "Set up job")]}, logs, [later])
        recs = {r["run_id"]: r for r in ci.collect(api=api, text_api=text)}
        self.assertEqual(recs[1]["cls"], "code")
        self.assertEqual(recs[1]["sig"], "ci:build:unit-tests:footest-bar-failed")
        self.assertEqual(recs[1]["already_fixed"]["run_id"], 9)
        self.assertEqual(recs[2]["cls"], "infra")
        self.assertEqual(recs[3]["cls"], "infra")          # log unreadable, setup step only
        self.assertIn("blob storage", recs[3]["log_error"])
        self.assertIsNone(recs[2]["already_fixed"])

    def test_normalize_drops_volatile_parts(self):
        a = ci.normalize("/home/runner/work/_temp/1a2b3c4d-1111-2222-3333-444455556666.sh: line 15:  2950 Aborted (core dumped)")
        b = ci.normalize("/home/runner/work/_temp/ffffffff-1111-2222-3333-444455556666.sh: line 15:  31 Aborted (core dumped)")
        self.assertEqual(a, b)
        self.assertEqual(a, "aborted-core-dumped")


CFG = {"schema": 1, "note": "pinned by Cedar",
       "experiments": {"on": ["native-engine", "fex-trim"], "always": ["sched", "fex-trim"],
                       "rotate": [["fex-trim", "memory"], ["memory"]], "parked_rotate": [["fex-trim"]]},
       "session": {"glThread": None}, "mystery": {"keep": True},
       "autotest": {"id": "native-1", "app": 2868840, "menu_seconds": 300, "branch": "beta", "repeat_hours": 2}}


class ConfigTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        (Path(self.tmp.name) / "config").mkdir()
        self.path = Path(self.tmp.name) / config.PATH
        self.path.write_text(config.dumps(CFG))

    def tearDown(self):
        self.tmp.cleanup()

    def test_native_engine_pin_refused(self):
        with self.assertRaises(config.ConfigError):
            config.park_flag(self.tmp.name, "native-engine", "suspect")
        self.assertEqual(json.loads(self.path.read_text()), CFG)

    def test_park_flag_removes_from_read_lists_only(self):
        diff = config.park_flag(self.tmp.name, "fex-trim", "oom with it on 3/3", today="2026-11-14")
        self.assertIn("-      \"fex-trim\"", diff)
        cfg = json.loads(self.path.read_text())
        self.assertEqual(cfg["experiments"]["on"], ["native-engine"])
        self.assertEqual(cfg["experiments"]["always"], ["sched"])
        self.assertEqual(cfg["experiments"]["rotate"], [["memory"], ["memory"]])
        self.assertEqual(cfg["experiments"]["parked_rotate"], [["fex-trim"]])   # not ours
        self.assertEqual(cfg["experiments"]["parked_by_autofix"]["fex-trim"]["date"], "2026-11-14")
        self.assertEqual(cfg["mystery"], {"keep": True})
        self.assertEqual(cfg["note"], "pinned by Cedar")
        self.assertEqual(config.park_flag(self.tmp.name, "fex-trim", "again"), "")

    def test_queue_autotest(self):
        config.queue_autotest(self.tmp.name, "autofix-hang-1", build="perf-switchboard-abc1234", menu_seconds=240)
        t = json.loads(self.path.read_text())["autotest"]
        self.assertEqual(t, {"id": "autofix-hang-1", "app": 2868840, "menu_seconds": 240, "branch": "beta",
                             "repeat_hours": 2, "build": "perf-switchboard-abc1234"})
        for bad in [dict(test_id="a b"), dict(test_id="ok", menu_seconds=5), dict(test_id="ok", steam_branch="perf/x"),
                    dict(test_id="autofix-hang-1")]:
            with self.assertRaises(config.ConfigError):
                config.queue_autotest(self.tmp.name, **bad)


class SweepTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo, self.shas = make_repo(self.tmp.name, ["base", "good", "risky change", "later"])
        self.tel = Path(self.tmp.name) / "tel"
        self.tel.mkdir()
        git(self.tel, "init", "-q", "-b", "main")
        self.runs = {}
        (self.tel / "config").mkdir()
        (self.tel / config.PATH).write_text(config.dumps(CFG))   # fex-trim is active there
        for name, sha, sigs, flags in [("1", self.shas[1], [], ["sched"]), ("2", self.shas[1], [], ["sched"]),
                                       ("3", self.shas[1], [], ["sched"]),
                                       ("4", self.shas[2], ["fex-hang"], ["sched", "fex-trim"]),
                                       ("5", self.shas[3], ["fex-hang"], ["sched", "fex-trim"]),
                                       ("6", self.shas[3], ["fex-hang"], ["sched", "fex-trim"]),
                                       ("7", self.shas[3], [], ["sched"])]:
            path = f"runs/perf-switchboard-{sha[:7]}/Google-Pixel-10/2026-11-14T0{name}-00-00Z.zip"
            (self.tel / path).parent.mkdir(parents=True, exist_ok=True)
            (self.tel / path).write_bytes(b"zip " + name.encode())
            self.runs[path] = (sha, sigs, flags)
        git(self.tel, "add", ".")
        git(self.tel, "commit", "-q", "-m", "runs")
        self.calls = []

    def tearDown(self):
        self.tmp.cleanup()

    def triage(self, path):
        rel = str(Path(path).relative_to(self.tel))
        self.calls.append(rel)
        sha, sigs, flags = self.runs[rel]
        v = verdict(rel, sha, sigs, flags=flags, stage="in_game")
        del v["commit"]   # sweep fills it from the path
        return v

    def run_sweep(self, **kw):
        ci_recs = kw.pop("ci_records", [])
        return sweep.sweep(self.tel, self.repo, pull=False, fetch=False, triage_fn=self.triage,
                           ci_collect=lambda since, processed: [r for r in ci_recs if r["run_id"] not in processed],
                           **kw)

    def test_dry_run_items_then_ledger_then_checkpoint_rewrite(self):
        ci_rec = {"run_id": 5, "workflow": "Build", "workflow_file": ".github/workflows/build.yml", "branch": "perf/switchboard",
                  "sha": "abc", "created_at": "2026-11-14T10:00:00Z", "cls": "code", "reason": "error: x",
                  "sig": "ci:build:unit-tests:footest-failed", "error_line": "FooTest > x FAILED", "tail": [],
                  "already_fixed": None, "jobs": []}
        out = self.run_sweep(dry_run=True, ci_records=[ci_rec])
        self.assertFalse(ledger.ledger_path(self.tel).exists())
        items = {i["sig"]: i for i in out["items"]}
        hang = items["fex-hang"]
        self.assertEqual(hang["kind"], "new")
        self.assertEqual(hang["runs"], 3)
        self.assertEqual(hang["bisect"]["first_bad"]["subject"], "risky change")
        self.assertEqual(hang["bisect"]["last_good"]["subject"], "good")
        self.assertEqual(hang["action"], "park-flag fex-trim")
        cfg = json.loads((self.tel / config.PATH).read_text())
        cfg["experiments"]["always"] = ["sched"]
        cfg["experiments"]["on"] = ["native-engine"]
        cfg["experiments"]["rotate"] = []
        (self.tel / config.PATH).write_text(config.dumps(cfg))
        off = {i["sig"]: i for i in self.run_sweep(dry_run=True)["items"]}["fex-hang"]
        self.assertEqual(off["action"], "route-to-owner fex")   # nothing to park: the flag is already off
        (self.tel / config.PATH).write_text(config.dumps(CFG))
        self.assertEqual(items["ci:build:unit-tests:footest-failed"]["action"], "fix")

        self.run_sweep(ci_records=[ci_rec])
        st = ledger.Ledger.at(self.tel).state()
        self.assertEqual(len(st.processed_runs), 7)
        self.assertEqual(st.items["fex-hang"]["status"], "open")
        self.assertIn(5, st.ci_run_ids)

        self.calls.clear()
        again = self.run_sweep(ci_records=[ci_rec])
        self.assertEqual(self.calls, [])
        self.assertEqual(again["items"], [])

        # a checkpoint rewritten in place (new blob, same path) is triaged again: new evidence
        path = next(p for p, v in self.runs.items() if v[1])
        (self.tel / path).write_bytes(b"bigger checkpoint")
        git(self.tel, "commit", "-q", "-am", "checkpoint")
        out = self.run_sweep()
        self.assertEqual(self.calls, [path])
        self.assertEqual([(i["sig"], i["kind"]) for i in out["items"]], [("fex-hang", "open")])

    def test_promoted_inferred_by_subject_then_verified(self):
        self.run_sweep()
        led = ledger.Ledger.at(self.tel)
        led.append("ready_to_promote", sig="fex-hang", branch="autofix/fex-hang/1", sha="0000000",
                   summary="fix", files=["x"], subject="Fix the hang")
        (self.repo / "f.txt").write_text("fix")
        git(self.repo, "commit", "-qam", "Fix the hang")
        fix = git(self.repo, "rev-parse", "HEAD")
        path = f"runs/perf-switchboard-{fix[:7]}/Google-Pixel-10/2026-11-14T10-00-00Z.zip"
        (self.tel / path).parent.mkdir(parents=True)
        (self.tel / path).write_bytes(b"after fix")
        git(self.tel, "add", ".")
        git(self.tel, "commit", "-q", "-m", "after fix")
        self.runs[path] = (fix, [], ["sched"])
        out = self.run_sweep()
        self.assertEqual(out["promoted_inferred"], [{"sig": "fex-hang", "commit": fix[:12]}])
        self.assertEqual(out["verified"], [{"sig": "fex-hang", "run": path}])
        self.assertEqual(ledger.Ledger.at(self.tel).state().items["fex-hang"]["status"], "verified")

    def test_missing_triage_leaves_zips_for_next_sweep(self):
        out = sweep.sweep(self.tel, self.repo, pull=False, fetch=False, use_ci=False,
                          triage_fn=lambda p: (_ for _ in ()).throw(ValueError("bad zip")))
        self.assertEqual(len(out["triage_errors"]), 7)
        self.assertEqual(ledger.Ledger.at(self.tel).state().processed_runs, set())


if __name__ == "__main__":
    unittest.main()
