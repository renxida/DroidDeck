#!/usr/bin/env python3
"""Launch the stand-in the way the phone launches a game with the native-engine experiment on, and fail unless
the native engine ran it.

    decide.py --game-env PATH --wrapper PATH --game-dir DIR --work DIR [--exe StandIn.exe] [--expect-skip REASON]
              -- ENGINE ARGS...

This is droiddeck-game-env's own native_engine() (the step droiddeck-proton-run takes before Proton), with its
droiddeck-native-godot pointed at the checked-out wrapper. The wrapper runs as on the phone: the pinned
component (--fetch, which reads the build's pin file), its Steam preflight on, its cached-check skip. A launch
that would have gone on to Proton ("Native engine skipped (...)" on the phone) fails, with the reason the app
would show. The run must also exit 0 and the stand-in must report FIRST_FRAME and MAINLOOP_OK.
With --expect-skip it is a self-test that must
see the launch go on to Proton for that reason (an engine release without the Steam bridge must be refused).
Writes <work>/decide.json. Stand-in only: never point it at a real game.
"""
import argparse
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
import re
import sys


def load(path, wrapper):
    loader = importlib.machinery.SourceFileLoader("droiddeck_game_env", path)
    spec = importlib.util.spec_from_loader("droiddeck_game_env", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    module.NATIVE_GODOT = wrapper
    return module


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    engine_args = argv[argv.index("--") + 1:] if "--" in argv else []
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--game-env", required=True)
    ap.add_argument("--wrapper", required=True)
    ap.add_argument("--game-dir", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--exe", default="StandIn.exe")
    ap.add_argument("--expect-skip", metavar="REASON",
                    help="self-test: pass only if the launch goes on to Proton for this reason (e.g. 'Steam library gap')")
    a = ap.parse_args(argv[:argv.index("--")] if "--" in argv else argv)
    os.makedirs(a.work, exist_ok=True)
    wrapper = os.path.abspath(a.wrapper)
    ge = load(os.path.abspath(a.game_env), wrapper)
    ng = ge.load_native_godot()
    assert ng, f"{wrapper} did not load"
    env = {k: v for k, v in os.environ.items() if not k.startswith("DROIDDECK_EXP_")}
    env[ge.experiment_name("native-engine")] = "1"
    env["DROIDDECK_GAME_LOG_DIR"] = os.path.abspath(a.work)
    log = os.path.join(a.work, "native-engine.log")
    if os.path.exists(log):
        os.remove(log)
    scheduled = []
    command = ["proton", "waitforexitandrun", os.path.join(os.path.abspath(a.game_dir), a.exe)] + engine_args
    err = io.StringIO()
    with contextlib.redirect_stderr(err):
        rc, notes = ge.native_engine(command, env, popen=lambda *args, **kw: scheduled.append(args[0]), ng=ng)
    lines = [l for l in err.getvalue().splitlines() if l.startswith(ge.ENGINE_LINE)]
    try:
        with open(log, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        text = ""
    if a.expect_skip:
        why = ge.native_skip_reason(notes) if rc is None else None
        ok = why == a.expect_skip
        print(f"self-test: expected skip ({a.expect_skip}), got {'skip (%s)' % why if rc is None else 'native, exit %s' % rc}")
        for note in notes:
            print("  note: " + note[:240])
        with open(os.path.join(a.work, "decide.json"), "w") as f:
            json.dump({"passed": ok, "expect_skip": a.expect_skip, "rc": rc, "skip": why, "notes": notes}, f, indent=1)
        print("PASS: skipped as expected" if ok else "FAIL: did not skip as expected")
        return 0 if ok else 1
    reasons = []
    if rc is None:
        reasons.append("the phone would show: Native engine skipped (%s) -> Proton"
                       % (ge.native_skip_reason(notes) or "; ".join(notes[-1:]) or "no reason"))
    elif rc != 0:
        reasons.append(f"native run exited {rc}")
    want = ge.engine_line("native", ng["COMPONENT"]["tag"])
    if lines != [want]:
        reasons.append(f"engine line {lines!r}, expected [{want!r}]")
    for marker in ("FIRST_FRAME", "MAINLOOP_OK"):
        if not re.search(rf"^{marker}\b", text, re.M):
            reasons.append(f"no {marker} from the stand-in")
    if "not starting:" in text:
        reasons.append("the wrapper refused to start: " + re.search(r"not starting:.*", text).group(0)[:200])
    steam = [l for l in text.splitlines() if "droiddeck-native-godot: Steam" in l]
    result = {"passed": not reasons, "rc": rc, "component": ng["COMPONENT"]["tag"], "engine_lines": lines,
              "notes": notes, "steam": steam, "reasons": reasons}
    with open(os.path.join(a.work, "decide.json"), "w") as f:
        json.dump(result, f, indent=1)
    print(f"component {ng['COMPONENT']['tag']}; engine line: {lines}")
    for line in steam:
        print("  " + line[:240])
    for note in notes:
        print("  note: " + note[:240])
    if reasons:
        print("FAIL: " + "; ".join(reasons))
        print("native-engine.log, last 60 lines:")
        for line in text.splitlines()[-60:]:
            print("    " + line)
        return 1
    print("PASS: the native engine ran the stand-in, Steam preflight on, nothing skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
