"""tools/autofix/triage.py and signatures.py over small hand-written telemetry zips."""
import datetime as dt
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS / "autofix"))
import signatures  # noqa: E402
import triage  # noqa: E402

T0 = 1794671520000  # 2026-11-14 15:52:00 UTC
APP = "2868840"
STEAM_PID, GAME_PID = 4100, 4200


def at(s):
    return T0 + int(s * 1000)


def ev(name, s, **fields):
    return json.dumps({"t": at(s), "event": name, "sessionId": "s", **fields})


def step(s, text):
    return "== STEP " + dt.datetime.fromtimestamp(at(s) / 1000, dt.timezone.utc).strftime("%H:%M:%S.%f")[:-3] + " " + text


def frame_rows(start_s, seconds, ft_ms):
    """(t_ms, frametime_us) rows from start_s for seconds; ft_ms is a number or a function of the index."""
    rows, t, i = [], start_s * 1000, 0
    while t < (start_s + seconds) * 1000:
        ft = ft_ms(i) if callable(ft_ms) else ft_ms
        t += ft
        rows.append((round(t), round(ft * 1000)))
        i += 1
    return rows


def bundle(kind="final", steam_ui=12, download=False, launch=True, exec_s=20, game=(30, 130, 33.333),
           stop=170, cap=30, events=(), log=(), log_before_ui=(), wine=None, launch_txt=(), memory=None,
           thermal=(0, 1), files=None, drop=(), experiments="warmup,all,fps-30", cache="2 files, 301.5 MB",
           placement="pid 4200, 40 threads, allowed 2-7 (40), cpu ticks by last core 7:3000 after 60 s",
           clean_exit=True, swap_free=3000):
    """A synthetic telemetry zip's files. game=(start_s, seconds, frame ms or fn) or None."""
    e = [ev("session.created", 0, mode="steam"), ev("compositor.starting", 0.1), ev("compositor.started", 0.1),
         ev("guest.starting", 0.1), ev("service.started", 0.2), ev("guest.started", 1, pid=1),
         ev("steam.starting", 1)]
    if download:
        e.append(ev("steam.client_download", 1.2, active=True))
    if steam_ui is not None:
        e += [ev("frame.first", steam_ui), ev("session.ready", steam_ui), ev("steam.ui_first_frame", steam_ui)]
    if exec_s is not None:
        e.append(ev("memory.freed", exec_s, reason="game_exec", swapUsedBeforeMb=3000))
        e.append(ev("pacing.cap", exec_s + 0.5, app_id=APP, cap=cap, game_cap=cap, why="game started"))
    e += list(events)
    if stop is not None:
        e += [ev("guest.exited", stop, status=0), ev("session.stopping", stop, status=0),
              ev("session.stopped", stop + 5, status=0), ev("session.artifacts_collected", stop + 6)]
    lines = ["starting", step(0.5, "checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)"),
             step(0.6, "Steam client ready"), step(1, "starting the Steam client")]
    lines += list(log_before_ui)
    if steam_ui is not None:
        lines.append(f"== steam: interface up after {int((steam_ui - 1) * 1000)} ms (Steam in front after 9000 ms); "
                     f"launching the requested game {APP}")
        if launch:
            lines += [f"== steam: launching requested game {APP} in the running client",
                      f"== steam: the client took the launch of {APP}"]
    if exec_s is not None:
        lines.append(f"droiddeck-game-env: warmup: FEX disk cache on for app {APP} (FEX-2611-1-g0000000, {cache}, "
                     "folder /root/.local/share/Steam/steamapps/compatdata/2868840/droiddeck-fex-cache)")
        lines.append(step(exec_s - 1, f"zRAM: memory pressure, compressing the client (available 1000 MB, swap free {swap_free} MB)"))
        lines.append(f"DROIDDECK_TIMELINE event=game_exec app_id={APP} wall_ms={at(exec_s)}")
        lines.append("ERROR: ld.so: object '/root/.steam/bin32/gameoverlayrenderer.so' from LD_PRELOAD cannot be "
                     "preloaded (cannot open shared object file): ignored.")
        if placement:
            lines.append(f"droiddeck-game-env: thread placement: SlayTheSpire2.exe {placement}")
    lines += list(log)
    if stop is not None and clean_exit:
        lines.append("== steam: the app asked for a clean exit")
        if exec_s is not None:
            lines.append(f"DROIDDECK_TIMELINE event=game_exit app_id={APP} status=130 signal=2 "
                         f"run_ms={int((stop - exec_s) * 1000)} wall_ms={at(stop)}")
    frames = [f"# frames t0_wall_ms={T0} t0_mono_ns=1 source=gamescope", "t_ms,frametime_us",
              "# t_ms=0 engine=GAMESCOPE pid=999 steam=0 output=1280x720@60000mHz",
              "# t_ms=3000 engine=GAMESCOPE pid=0 steam=0 output=1280x720@60000mHz"]
    if steam_ui is not None:
        frames.append(f"# t_ms={steam_ui * 1000} engine=GAMESCOPE pid={STEAM_PID} steam=1 output=1280x720@60000mHz")
        frames += [f"{t},{ft}" for t, ft in frame_rows(steam_ui, 5, 16.7)]
    if game:
        start, seconds, ft = game
        frames.append(f"# t_ms={start * 1000 - 1} engine=GAMESCOPE pid={STEAM_PID} steam=0 output=1280x720@60000mHz")
        frames.append(f"# t_ms={start * 1000} engine=GAMESCOPE pid={GAME_PID} steam=0 output=1280x720@60000mHz")
        frames += [f"{t},{f}" for t, f in frame_rows(start, seconds, ft)]
    end = stop if stop is not None else 200
    if memory is None:
        memory = []
        for s in range(0, int(end) + 1, 5):
            in_game = exec_s is not None and s >= exec_s
            memory.append(dict(t=s, available_mb=2000, swap_used_mb=3000, game_mb=1500 if in_game else 0,
                               game_swap_mb=100 if in_game else 0, game_majflt_s=1.0))
    mem = ["t_wall_ms,used_mb,available_mb,swap_used_mb,game_mb,droiddeck_mb,app_mb,gpu_mb,game_swap_mb,"
           "droiddeck_swap_mb,top_process,top_mb,game_majflt_s,droiddeck_majflt_s,swapin_mb_s,swapout_mb_s"]
    for r in memory:
        mem.append(f"{at(r['t'])},9000,{r['available_mb']},{r['swap_used_mb']},{r['game_mb']},100,100,,"
                   f"{r.get('game_swap_mb', 0)},20,renxida.kestrel,100,{r['game_majflt_s']},1.0,,")
    clocks = ["== clocks: Google Pixel 10 (pixel), every 2s", f"start: wall_ms={T0} tz_offset_min=0",
              "cpu policies: policy0 0 1, policy2 2 3 4, policy5 5 6, policy7 7"]
    for i, th in enumerate(thermal):
        clocks.append(f"15:52:{i * 2:02d} p0 1000/2000 p2 2000/3000 | thermal {th} headroom 0.70 | battery 30.0 C")
    out = {
        "upload.txt": f"session: 2026-11-14-01-steam\nkind: {kind}\nbuilt_ms: {T0}\npath: runs/x/y/z.zip\n",
        "build.txt": "build_label: remote clean · perf/example at 1234567\nbranch: perf/example\n"
                     "commit: 1234567890abcdef1234567890abcdef12345678\nbuild_slug: perf-example-1234567\n"
                     f"device: Google Pixel 10 (pixel, pixel)\nexperiments_at_session_start: {experiments}\n",
        "settings.txt": "autotest: id=null attempts=0 completed=false result=null skipped=null overlay=true\n",
        "events.jsonl": "\n".join(e) + "\n",
        "session.log": "\n".join(lines) + "\n",
        "frames.csv": "\n".join(frames) + "\n",
        "memory.csv": "\n".join(mem) + "\n",
        "clocks.log": "\n".join(clocks) + "\n",
        "crash.log": "",
        "proton/20261114-000000-abc-2868840/launch.txt": "\n".join(
            [f"app: {APP}", "exe: /root/.local/share/Steam/steamapps/common/Slay the Spire 2/SlayTheSpire2.exe",
             *launch_txt]) + "\n",
    }
    if wine is not None:
        out["proton/20261114-000000-abc-2868840/steam-2868840.log"] = wine
    out.update(files or {})
    for name in drop:
        out.pop(name, None)
    return out


class Fixture:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def write(self, files, name="2026-11-14T15-52-00Z.zip", slug="perf-example-1234567", prefix=""):
        path = self.root / "runs" / slug / "Google-Pixel-10" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as z:
            for rel, text in files.items():
                z.writestr(prefix + rel, text)
        return str(path)


def wine_lines(*rows):
    return "\n".join(f"{ts:.3f}:0030:0034:{text}" for ts, text in rows) + "\n"


def slow_every(n, slow_ms, base=33.333):
    return lambda i: slow_ms if i % n == n - 1 else base


# Every catalog signature with a bundle that shows it, and the severity it should get there.
CASES = {
    "powervr-runtime-missing": (dict(steam_ui=None, exec_s=None, game=None, stop=0.7, events=[
        ev("session.failed", 0.6, code="POWERVR_MISSING", message="The PowerVR graphics runtime could not be downloaded.",
           status=-1)]), "fail"),
    "session-failed": (dict(steam_ui=None, exec_s=None, game=None, stop=2, events=[
        ev("session.failed", 1.5, code="RUNTIME_BROKEN", message="x", status=-1)]), "fail"),
    "steam-ui-never": (dict(steam_ui=None, exec_s=None, game=None, stop=300), "fail"),
    "steam-ui-stall": (dict(steam_ui=150, exec_s=160, game=(170, 60, 33.333), stop=240), "fail"),
    "steam-ui-slow": (dict(steam_ui=60, exec_s=70, game=(80, 60, 33.333), stop=150), "degrade"),
    "login1-pause-storm": (dict(log_before_ui=["== login1: pausing the session", "== login1: session resumed"] * 6),
                           "degrade"),
    "app-backgrounded-at-startup": (dict(events=[ev("session.suspended", 3), ev("session.resumed", 9 + 5)]),
                                    "degrade"),
    "session-resume-stuck": (dict(events=[ev("session.resume_stuck", 50)]), "degrade"),
    "game-never-execd": (dict(exec_s=None, game=None, stop=200), "fail"),
    "game-exe-not-running": (dict(game=None, stop=200, placement="not running after 60 s"), "fail"),
    "game-no-window": (dict(game=None, stop=200,
                            placement="pid 4200, 2 threads, allowed 2-6 (2), cpu ticks by last core 6:100 after 60 s"),
                       "fail"),
    "wine-explorer-failed": (dict(game=None, stop=200, wine=wine_lines(
        (100.0, "trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\explorer.exe\""),
        (104.2, "err:winediag:nodrv_CreateWindow L\"The explorer process failed to start.\""))), "fail"),
    "fex-hang-after-memory-names": (dict(game=None, stop=220, placement="not running after 60 s", wine=wine_lines(
        (100.0, "trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\libarm64ecfex.dll\" : builtin"),
        (100.05, "warn:threadname:NtSetInformationThread Thread ID 0100 renamed to L\"FEX memory names\""),
        (263.0, "err:virtual:virtual_setup_exception nested exception on signal stack addr 0x1 stack 0x2"))),
        "fail"),
    "wine-startup-stall": (dict(game=None, stop=220, placement="not running after 60 s", wine=wine_lines(
        (100.0, "trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\kernelbase.dll\" : builtin"),
        (190.0, "trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\user32.dll\" : builtin"))), "fail"),
    "fex-nonntdll-imports": (dict(experiments="warmup,fex-kestrel", launch_txt=[
        "fex: FEX-Kestrel-ab135df09-00000000 4000000 bytes sha256 000000000000"], events=[
        ev("components.fex", 2, state="fex: DroidDeck-Proton 11-1 (Compatible) fex-kestrel wants "
                                      "Kestrel-FEXCore-20261114.22-ab135df09-00000000.wcp, has "
                                      "Kestrel-FEXCore-20261114.22-ab135df09-00000000.wcp")]), "degrade"),
    "fex-kestrel-without-experiment": (dict(launch_txt=["fex: FEX-Kestrel-0123456-89abcdef 4000000 bytes"]),
                                       "degrade"),
    "fex-cold-cache": (dict(cache="2 files, 0.0 MB", game=(30, 200, 80)), "degrade"),
    "wine-nested-exception": (dict(wine=wine_lines(
        (100.0, "trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\ntdll.dll\" : builtin"),
        (300.0, "err:virtual:virtual_setup_exception nested exception on signal stack addr 0x1 stack 0x2"))),
        "info"),
    "native-watchdog-fallback": (dict(log=[
        "droiddeck-game-env: native-engine: exited with 1 after 3 s; starting on Proton"]), "degrade"),
    "native-mixed-mode-game": (dict(log=[
        "droiddeck-game-env: native-engine log: left out sts2.dll: mixed-mode (native C++) assembly, named by "
        "SlayTheSpire2.dll", "droiddeck-game-env: native-engine: no first frame after 90 s; starting on Proton"]),
        "degrade"),
    "native-no-display": (dict(log=[
        "droiddeck-game-env: native-engine log: ERROR: Unable to create DisplayServer, all display drivers failed."]),
        "degrade"),
    "native-steam-init-failed": (dict(log=[
        "droiddeck-game-env: native-engine log: Steam: Valve's libsteam_api.so lacks flat-API names "
        "SteamAPI_ISteamUser_GetSteamID"]), "fail"),
    "native-gdextension-missing": (dict(log=[
        "droiddeck-game-env: native-engine log: ERROR: GDExtension dynamic library not found: "
        "'res://addons/example/example.gdextension'."]), "info"),
    "native-component-has-stub": (dict(files={"native-engine.log": "asset files: engine/godot stubs/libsteam_api64.so\n"}),
                                  "info"),
    "dotnet-bad-image-format": (dict(files={
        "game-logs/2868840/AppData/Roaming/SlayTheSpire2/logs/godot.log":
            "System.BadImageFormatException: Bad IL format.\n"}), "fail"),
    "dotnet-unhandled-exception": (dict(files={
        "crash-lines.txt": "steam-x64.log: Unhandled exception. System.NullReferenceException: x\n"}), "fail"),
    "fex-sigill": (dict(files={"crash-lines.txt": "session.log: droiddeck-fex: SIGILL at 0x10000 (ud2)\n"}), "fail"),
    "wine-unhandled-page-fault": (dict(wine=wine_lines(
        (120.0, "err:seh:NtRaiseException Unhandled page fault on read access to 0000000000000000"))), "fail"),
    "godot-crash": (dict(files={"game-logs/2868840/AppData/Roaming/SlayTheSpire2/logs/godot.log":
                                "CrashHandlerException: Program crashed with signal 11\n"}), "fail"),
    "game-abnormal-exit": (dict(events=[ev("game.exited", 100, app_id=APP, pid=GAME_PID, run_s=80, how="wrapper",
                                           status=0, signal=11, abnormal=True,
                                           evidence=["Segmentation fault"])]), "fail"),
    "game-segfault": (dict(files={"crash-lines.txt": "session.log: /bin/sh: line 1: 4200 Segmentation fault "
                                                     "(core dumped) wine SlayTheSpire2.exe\n"}), "fail"),
    "steam-segfault-at-exit": (dict(log=[
        "/usr/local/bin/droiddeck-session: line 100:  1234 Segmentation fault         \"${steam_client[@]}\""]), "info"),
    "app-killed": (dict(events=[ev("app.exit", 100, reason="low_memory", abnormal=True, status=9, description="",
                                   process="dev.renxida.kestrel", pid=1)]), "fail"),
    "helper-process-killed": (dict(events=[ev("app.exit", 100, reason="low_memory", abnormal=True, status=9,
                                              process="dev.renxida.kestrel:shortcuts_sync", pid=2)]), "info"),
    "zram-full-at-launch": (dict(swap_free=100, memory=[dict(t=s, available_mb=900, swap_used_mb=5700, game_mb=1200,
                                              game_majflt_s=2.0) for s in range(0, 171, 5)]), "info"),
    "memory-thrash": (dict(swap_free=100, memory=[dict(t=s, available_mb=100 if 60 <= s <= 80 else 900, swap_used_mb=5700,
                                        game_mb=1200, game_majflt_s=900.0 if 60 <= s <= 80 else 2.0)
                                   for s in range(0, 171, 5)]), "degrade"),
    "game-memory-high": (dict(memory=[dict(t=s, available_mb=900, swap_used_mb=3000, game_mb=2300 if s > 60 else 900,
                                           game_majflt_s=2.0) for s in range(0, 171, 5)]), "degrade"),
    "game-freezes": (dict(game=(30, 130, lambda i: 1500 if i in (2000, 2400, 3000) else 33.333)), "degrade"),
    "lmk-risk": (dict(events=[ev("memory.lmk_risk", 80, swapFreeMb=5, swapTotalMb=6000, availableMb=400,
                                 totalMb=12000)]), "info"),
    "smaps-large-ro-anon": (dict(files={"smaps.csv":
        "t_wall_ms,pid,process,kind,category,owner,vmas,size_mb,rss_mb,pss_mb,swap_mb,anon_mb\n"
        f"{at(90)},4200,SlayTheSpire2.e,top,7000000000-702ff00000,anon r--p,1,767.0,6.0,6.0,0.0,767.0\n"}), "info"),
    "thermal-severe": (dict(thermal=(1, 2, 3, 3, 3)), "degrade"),
    "fps-over-cap": (dict(game=(30, 130, 16.667)), "degrade"),
    "fps-low": (dict(game=(30, 130, 66.0)), "degrade"),
    "stutter-high": (dict(game=(30, 130, slow_every(10, 150))), "degrade"),
    "stutter-burst": (dict(game=(30, 240, lambda i: 60 if 3000 <= i < 3150 else 33.333), stop=280), "degrade"),
    "astc-reverted": (dict(files={"texpack.txt": '{\n "made": 1794600000,\n "phase": "reverted"\n}\n'}), "degrade"),
    "bundle-wrong-session-log": (dict(files={"resource-loads.txt":
        "# from game-logs/2868840/AppData/Roaming/SlayTheSpire2/logs/godot2026-11-14T09.00.00.log\n"
        "# resources Godot loaded: 3 distinct, 4 loads\n"}), "degrade"),
    "session-log-missing": (dict(drop=["session.log"]), "info"),
}


class TriageTest(unittest.TestCase):
    def setUp(self):
        self.fx = Fixture()

    def tearDown(self):
        self.fx.tmp.cleanup()

    def verdict(self, **kw):
        return triage.triage_zip(self.fx.write(bundle(**kw)))

    @staticmethod
    def sig(v, sig_id):
        return next((s for s in v["signatures"] if s["id"] == sig_id), None)

    def test_catalog_is_valid_and_every_signature_has_a_case(self):
        signatures.validate()
        self.assertEqual(set(CASES), {s["id"] for s in signatures.CATALOG})

    def test_each_signature_fires_with_its_severity_and_evidence(self):
        for sig_id, (kw, severity) in CASES.items():
            with self.subTest(sig_id):
                v = self.verdict(**kw)
                s = self.sig(v, sig_id)
                self.assertIsNotNone(s, f"{sig_id} missing; got {[x['id'] for x in v['signatures']]}")
                self.assertEqual(s["severity"], severity)
                self.assertTrue(s["evidence"])
                self.assertLessEqual(len(s["evidence"]), 6)
                self.assertIn(s["owner"], signatures.OWNERS)
                if severity == "fail":
                    self.assertEqual(v["outcome"], "fail")

    def test_healthy_session_is_ok_with_schema_and_metrics(self):
        v = self.verdict()
        self.assertEqual(v["signatures"], [])
        self.assertEqual(v["outcome"], "ok")
        self.assertEqual(v["stage"], "in_game")
        self.assertEqual(set(v), {"run", "slug", "commit", "branch", "device", "started_utc", "kind", "experiments",
                                  "stage", "outcome", "signatures", "metrics"})
        self.assertEqual(v["run"], "runs/perf-example-1234567/Google-Pixel-10/2026-11-14T15-52-00Z.zip")
        self.assertEqual((v["slug"], v["branch"], v["device"], v["kind"]),
                         ("perf-example-1234567", "perf/example", "Google-Pixel-10", "session"))
        self.assertEqual(v["started_utc"], "2026-11-14T15:52:00Z")
        self.assertEqual(v["experiments"], ["warmup", "all", "fps-30"])
        m = v["metrics"]
        self.assertEqual(m["steam_ui_s"], 11.0)
        self.assertEqual(m["game_first_frame_s"], 10.0)
        self.assertAlmostEqual(m["fps_p50"], 30.0, delta=0.1)
        self.assertAlmostEqual(m["frametime_p99_ms"], 33.3, delta=0.1)
        self.assertEqual(m["stutters_per_min"], 0.0)
        self.assertEqual(m["thermal_max"], 1)
        self.assertAlmostEqual(m["zram_used_pct_at_launch"], 50.0, delta=0.1)
        self.assertEqual(m["run_s"], 170.0)

    def test_zram_full_is_a_degradation_only_with_thrash(self):
        v = self.verdict(**CASES["memory-thrash"][0])
        self.assertEqual(self.sig(v, "zram-full-at-launch")["severity"], "degrade")
        self.assertEqual(v["outcome"], "degraded")

    def test_cold_cache_is_info_when_the_game_ran_fine(self):
        v = self.verdict(cache="no cache yet")
        self.assertEqual(self.sig(v, "fex-cold-cache")["severity"], "info")
        self.assertEqual(v["outcome"], "ok")

    def test_mixed_mode_library_that_is_not_the_game_is_info(self):
        v = self.verdict(log=["droiddeck-game-env: native-engine log: left out FmodWrapper.dll: mixed-mode assembly"])
        self.assertEqual(self.sig(v, "native-mixed-mode-game")["severity"], "info")

    def test_native_failure_without_a_game_afterwards_is_a_failure(self):
        v = self.verdict(game=None, placement="", stop=60,
                         log=["droiddeck-game-env: native-engine: exited with 1 after 3 s; starting on Proton"])
        self.assertEqual(self.sig(v, "native-watchdog-fallback")["severity"], "fail")

    def test_steam_ui_slow_while_downloading_the_client_is_info(self):
        v = self.verdict(steam_ui=150, download=True, exec_s=160, game=(170, 60, 33.333), stop=240)
        self.assertIsNone(self.sig(v, "steam-ui-stall"))
        self.assertEqual(self.sig(v, "steam-ui-slow")["severity"], "info")
        self.assertEqual(v["outcome"], "ok")

    def test_negative_markers_do_not_fail_a_run(self):
        v = self.verdict(**CASES["wine-nested-exception"][0])
        self.assertEqual(v["outcome"], "ok")
        self.assertFalse(any("gameoverlayrenderer" in e for s in v["signatures"] for e in s["evidence"]))

    def test_checkpoint_of_a_running_session_is_incomplete(self):
        v = self.verdict(kind="checkpoint", stop=None, game=(30, 60, 33.333))
        self.assertEqual((v["kind"], v["stage"], v["outcome"]), ("checkpoint", "in_game", "incomplete"))

    def test_checkpoint_with_a_fail_signature_is_a_failure(self):
        v = self.verdict(kind="abnormal game exit", stop=None, game=None, placement="not running after 60 s")
        self.assertEqual((v["kind"], v["outcome"]), ("checkpoint", "fail"))

    def test_final_bundle_without_an_end_is_incomplete(self):
        self.assertEqual(self.verdict(stop=None)["outcome"], "incomplete")

    def test_stop_before_the_game_is_user_stop(self):
        v = self.verdict(launch=False, exec_s=None, game=None, stop=30)
        self.assertEqual((v["stage"], v["outcome"]), ("steam_ui", "user_stop"))

    def test_autotest_kind(self):
        v = self.verdict(files={"settings.txt": "autotest: id=native-1 attempts=1 completed=false\n"})
        self.assertEqual(v["kind"], "autotest")

    def test_stale_events_from_an_earlier_session_are_ignored(self):
        v = self.verdict(steam_ui=None, exec_s=None, game=None, stop=40,
                         events=[ev("steam.ui_first_frame", -160), ev("game.first_frame", -200, engine="GAMESCOPE", pid=9)])
        self.assertIsNone(v["metrics"]["steam_ui_s"])
        self.assertEqual(v["stage"], "setup")

    def test_dropped_session_log_still_triages_from_events_and_crash_lines(self):
        files = bundle(game=None, stop=200, drop=["session.log"], files={"crash-lines.txt":
            f"session.log: DROIDDECK_TIMELINE event=game_exec app_id={APP} wall_ms={at(20)}\n"
            "session.log: droiddeck-game-env: thread placement: SlayTheSpire2.exe not running after 60 s\n"})
        v = triage.triage_zip(self.fx.write(files))
        ids = {s["id"] for s in v["signatures"]}
        self.assertIn("session-log-missing", ids)
        self.assertEqual(v["stage"], "game_exec")
        self.assertEqual(v["outcome"], "fail")

    def test_truncated_files_do_not_break_triage(self):
        files = bundle()
        files["events.jsonl"] += '{"t": 17946715'
        files["frames.csv"] += "150000,33"
        files["memory.csv"] += "1794671,9000"
        files["clocks.log"] += "07:59:01 p0 1036/22"
        v = triage.triage_zip(self.fx.write(files))
        self.assertEqual(v["outcome"], "ok")

    def test_frames_cut_from_the_front_count_as_game_frames(self):
        files = bundle()
        files["frames.csv"] = "\n".join(f"{t},{ft}" for t, ft in frame_rows(30, 130, 33.333)) + "\n"
        v = triage.triage_zip(self.fx.write(files))
        self.assertEqual(v["stage"], "in_game")
        self.assertAlmostEqual(v["metrics"]["fps_p50"], 30.0, delta=0.1)

    def test_share_bundle_session_folder_is_read(self):
        path = self.fx.write(bundle(), name="DroidDeck-2026-11-14-01-steam-session.zip", prefix="2026-11-14-01-steam/")
        v = triage.triage_zip(path)
        self.assertEqual((v["outcome"], v["stage"]), ("ok", "in_game"))
        self.assertEqual(v["started_utc"], "2026-11-14T15:52:00Z")

    def test_unreadable_zip_gives_a_verdict(self):
        path = self.fx.root / "runs" / "broken.zip"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"PK\x03\x04 not really a zip")
        v = triage.triage_zip(str(path))
        self.assertEqual((v["stage"], v["outcome"]), ("app", "incomplete"))
        self.assertEqual(v["signatures"][0]["id"], "bundle-unreadable")

    def test_cli_root_and_jsonl(self):
        self.fx.write(bundle(), name="2026-11-14T15-52-00Z.zip")
        self.fx.write(bundle(**CASES["game-never-execd"][0]), name="2026-11-14T16-00-00Z.zip")
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(triage.main(["--root", str(self.fx.root)]), 0)
        verdicts = [json.loads(line) for line in out.getvalue().splitlines()]
        self.assertEqual([v["outcome"] for v in verdicts], ["ok", "fail"])
        out = io.StringIO()
        with redirect_stdout(out):
            triage.main([self.fx.write(bundle()), "--jsonl"])
        self.assertEqual(len(out.getvalue().splitlines()), 1)


if __name__ == "__main__":
    unittest.main()
