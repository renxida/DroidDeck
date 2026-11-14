#!/usr/bin/env python3
"""Run the stand-in game through droiddeck-native-godot and judge whether it started.

    gate.py --wrapper PATH --game-dir DIR --work DIR [--component pinned|DIR] [--deps on|off]
            [--repeat N] [--prefix CMD] [--old-converter FILE] [--expect-fail compose|load] [--name NAME] -- ENGINE ARGS...

A run passes when: the wrapper exits 0, it logged "first frame", stdout has FIRST_FRAME and MAINLOOP_OK, the
deps line matches --deps, and nothing shows a compose rejection, a missing or malformed assembly or a .NET
host error. Every run must pass. With --expect-fail every run must instead fail before its first frame:
"compose" at compose on a mixed-mode rejection (the converter from before ReadyToRun support, 7754c57b),
"load" when .NET loads the game ("Bad IL format" / BadImageFormatException: the converter that stripped
ReadyToRun code, d68b35ad). With --old-converter the wrapper runs with that file's portable_image() in place
of its own; nothing else changes.

Each line is logged with the time it arrived, so the log also shows whether Godot's "Project FPS" line (the
watchdog's signal) arrives while the engine runs or only when it exits (stdout buffered). Writes
<work>/<name>.json (per-run verdicts and timings) and <work>/<name>-<i>.log, prints a summary, exits 0 on a
pass, 1 otherwise. Stand-in only: never point it at a real game.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time

BAD = [
    ("compose rejection", re.compile(r"mixed-mode|expected one data_|no Godot pack found|Traceback \(most recent")),
    ("assembly load", re.compile(r"Could not load file or assembly|FileNotFoundException|FileLoadException")),
    ("bad image", re.compile(r"BadImageFormatException|Bad IL format")),
    ("hostfxr", re.compile(r"hostfxr.*(error|fail|not found)|Failed to (load|resolve|find) .*hostfxr|"
                           r"\.NET: (Failed|failed)|Failed to initialize the \.NET|Could not find the \.NET", re.I)),
    ("missing method/type", re.compile(r"MissingMethodException|TypeLoadException|EntryPointNotFoundException")),
]
STEAM = re.compile(r"SteamAPI_Init|steam_api.*(fail|error)|libsteam_api.*(cannot|not found)", re.I)

# Runs the wrapper with an older converter's portable_image(); everything else is the wrapper as checked out.
DRIVER = """
import runpy, sys
ng = runpy.run_path(sys.argv[1])
old = runpy.run_path(sys.argv[2])
ng["main"].__globals__["portable_image"] = old["portable_image"]
sys.exit(ng["main"](sys.argv[3:]))
"""


def run_once(cmd, log_path, timeout):
    began = time.monotonic()
    lines = []
    proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def stop():
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()

    timer = threading.Timer(timeout, stop)
    timer.start()
    with open(log_path, "w") as log:
        for raw in proc.stdout:
            t = time.monotonic() - began
            line = raw.decode("utf-8", "replace").rstrip("\n")
            lines.append((t, line))
            log.write(f"[{t:8.3f}] {line}\n")
            log.flush()
    rc = proc.wait()
    timer.cancel()
    return rc, lines, time.monotonic() - began


def first(lines, pred):
    return next((t for t, line in lines if pred(line)), None)


def judge(rc, lines, deps):
    text = "\n".join(line for _, line in lines)
    reasons = []
    if rc != 0:
        reasons.append(f"exit {rc}")
    if not re.search(r"droiddeck-native-godot: first frame after", text):
        reasons.append("wrapper logged no first frame")
    for marker in ("FIRST_FRAME", "MAINLOOP_OK"):
        if not re.search(rf"^{marker}\b", text, re.M):
            reasons.append(f"no {marker}")
    m = re.search(r"^STANDIN_DEPS loaded=(\d+)", text, re.M)
    if not m:
        reasons.append("no STANDIN_DEPS line")
    elif deps == "on" and int(m.group(1)) == 0:
        reasons.append("deps on but none loaded")
    elif deps == "off" and int(m.group(1)) != 0:
        reasons.append("deps off but some loaded")
    found = [label for label, pat in BAD if pat.search(text)]
    reasons += [f"log shows {label}" for label in found]
    if reasons and STEAM.search(text):
        reasons.append("stopped at Steam")
    timing = {
        "fps_first": first(lines, lambda s: s.startswith("Project FPS:")),
        "wrapper_first_frame": first(lines, lambda s: "droiddeck-native-godot: first frame after" in s),
        "FIRST_FRAME": first(lines, lambda s: s.startswith("FIRST_FRAME")),
        "MAINLOOP_OK": first(lines, lambda s: s.startswith("MAINLOOP_OK")),
        "end": lines[-1][0] if lines else None,
    }
    fps_lines = sum(1 for _, s in lines if s.startswith("Project FPS:"))
    return reasons, found, timing, fps_lines


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    engine_args = argv[argv.index("--") + 1:] if "--" in argv else []
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--wrapper", required=True)
    ap.add_argument("--game-dir", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--component", default="pinned", help="pinned (--fetch) or a component folder")
    ap.add_argument("--steam-api", help="with a component folder: Valve's libsteam_api.so to place")
    ap.add_argument("--exe", default="StandIn.exe")
    ap.add_argument("--deps", choices=("on", "off", "any"), default="any")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--prefix", default="", help="command to run the wrapper under, e.g. 'xvfb-run -a'")
    ap.add_argument("--old-converter")
    ap.add_argument("--expect-fail", choices=("compose", "load"))
    ap.add_argument("--timeout", type=float, default=300)
    ap.add_argument("--name", default="gate")
    a = ap.parse_args(argv[:argv.index("--")] if "--" in argv else argv)
    os.makedirs(a.work, exist_ok=True)
    wrapper = os.path.abspath(a.wrapper)
    if a.old_converter:
        head = [sys.executable, "-c", DRIVER, wrapper, os.path.abspath(a.old_converter)]
    else:
        head = [sys.executable, wrapper]
    src = ["--fetch"] if a.component == "pinned" else ["--component", a.component]
    if a.steam_api:
        src += ["--steam-api", a.steam_api]
    runs, ok = [], True
    for i in range(a.repeat):
        out = os.path.join(a.work, f"{a.name}-launch")
        cmd = shlex.split(a.prefix) + head + ["run"] + src + [
            "--game-dir", os.path.abspath(a.game_dir), "--exe", a.exe, "--out", out, "--"] + engine_args
        log_path = os.path.join(a.work, f"{a.name}-{i + 1}.log")
        rc, lines, took = run_once(cmd, log_path, a.timeout)
        reasons, found, timing, fps_lines = judge(rc, lines, a.deps)
        if a.expect_fail:
            want = "compose rejection" if a.expect_fail == "compose" else "bad image"
            passed = rc != 0 and want in found and timing["MAINLOOP_OK"] is None
            verdict = (f"failed as expected ({want})" if passed
                       else f"did NOT fail with {want} as expected (found: {', '.join(found) or 'nothing'})")
        else:
            passed = not reasons
            verdict = "pass" if passed else "FAIL: " + "; ".join(reasons)
        ok &= passed
        fps_live = (timing["fps_first"] is not None and timing["MAINLOOP_OK"] is not None
                    and timing["fps_first"] < timing["MAINLOOP_OK"] - 0.5)
        runs.append({"run": i + 1, "rc": rc, "seconds": round(took, 3), "passed": passed, "verdict": verdict,
                     "reasons": reasons, "fps_lines": fps_lines, "fps_line_arrived_while_running": fps_live,
                     "timing": {k: (round(v, 3) if v is not None else None) for k, v in timing.items()},
                     "log": log_path})
        print(f"{a.name} run {i + 1}: {verdict} (rc {rc}, {took:.1f} s, {fps_lines} FPS lines, "
              f"first FPS line at {runs[-1]['timing']['fps_first']} s, MAINLOOP_OK at {runs[-1]['timing']['MAINLOOP_OK']} s)",
              flush=True)
        if not passed:
            for t, line in lines[-60:]:
                print(f"    [{t:8.3f}] {line}")
    summary = {"name": a.name, "passed": ok, "expect_fail": a.expect_fail, "engine_args": engine_args,
               "prefix": a.prefix, "old_converter": bool(a.old_converter), "runs": runs}
    with open(os.path.join(a.work, f"{a.name}.json"), "w") as f:
        json.dump(summary, f, indent=1)
    print(f"{a.name}: {'PASS' if ok else 'FAIL'} ({sum(r['passed'] for r in runs)}/{len(runs)} runs)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
