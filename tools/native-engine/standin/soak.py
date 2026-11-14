#!/usr/bin/env python3
"""Soak the stand-in game through droiddeck-native-godot: run it for a while, sample the engine's resident
memory and open file descriptors, and fail on steady growth.

    soak.py --wrapper PATH --game-dir DIR --work DIR [--minutes 45] [--every 5] [--input] -- ENGINE ARGS...

Meant to run under Xvfb (xvfb-run -a soak.py ...). With --input it moves the pointer and presses keys in the
game's window with xdotool every sample. Writes <work>/soak.csv (t, rss_kb, fds) and <work>/soak.json.
Growth is judged on the second half of the run (after warm-up): RSS may climb at most --max-rss-mb-per-hour by
least squares, and the fd count may end at most --max-fd-growth above the half-way count.
"""
import argparse
import csv
import json
import os
import shutil
import signal
import subprocess
import sys
import time


def children(pid):
    try:
        with open(f"/proc/{pid}/task/{pid}/children") as f:
            return [int(p) for p in f.read().split()]
    except OSError:
        return []


def engine_pid(root, name):
    """The engine: the descendant of the wrapper whose executable is named like the game assembly."""
    todo = [root]
    while todo:
        pid = todo.pop()
        try:
            exe = os.path.basename(os.readlink(f"/proc/{pid}/exe"))
        except OSError:
            exe = ""
        if pid != root and exe == name:
            return pid
        todo += children(pid)
    return None


def sample(pid):
    rss = None
    with open(f"/proc/{pid}/status") as f:
        for line in f:
            if line.startswith("VmRSS:"):
                rss = int(line.split()[1])
    return rss, len(os.listdir(f"/proc/{pid}/fd"))


def slope(points):
    n = len(points)
    if n < 2:
        return 0.0
    mx = sum(x for x, _ in points) / n
    my = sum(y for _, y in points) / n
    den = sum((x - mx) ** 2 for x, _ in points)
    return sum((x - mx) * (y - my) for x, y in points) / den if den else 0.0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    engine_args = argv[argv.index("--") + 1:] if "--" in argv else []
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--wrapper", required=True)
    ap.add_argument("--game-dir", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--component", default="pinned")
    ap.add_argument("--exe", default="StandIn.exe")
    ap.add_argument("--minutes", type=float, default=45)
    ap.add_argument("--every", type=float, default=5)
    ap.add_argument("--input", action="store_true")
    ap.add_argument("--max-rss-mb-per-hour", type=float, default=20)
    ap.add_argument("--max-fd-growth", type=int, default=8)
    a = ap.parse_args(argv[:argv.index("--")] if "--" in argv else argv)
    os.makedirs(a.work, exist_ok=True)
    src = ["--fetch"] if a.component == "pinned" else ["--component", a.component]
    cmd = [sys.executable, os.path.abspath(a.wrapper), "run"] + src + [
        "--game-dir", os.path.abspath(a.game_dir), "--exe", a.exe, "--out", os.path.join(a.work, "launch"), "--"] + engine_args
    log = open(os.path.join(a.work, "soak.log"), "w")
    wrapper = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    xdotool = shutil.which("xdotool") if a.input else None
    began = time.monotonic()
    rows, pid, verdict = [], None, None
    try:
        while time.monotonic() - began < a.minutes * 60:
            time.sleep(a.every)
            if wrapper.poll() is not None:
                verdict = f"the game ended early (wrapper exit {wrapper.returncode})"
                break
            pid = pid or engine_pid(wrapper.pid, "StandIn")
            if pid is None:
                continue
            try:
                rss, fds = sample(pid)
            except OSError:
                verdict = "the engine went away"
                break
            t = round(time.monotonic() - began, 1)
            rows.append((t, rss, fds))
            if xdotool:
                n = len(rows)
                subprocess.run([xdotool, "mousemove", str(50 + n * 37 % 600), str(50 + n * 53 % 400),
                                "click", "1", "key", "space", "Right", "Escape"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        if wrapper.poll() is None:
            wrapper.send_signal(signal.SIGTERM)
            try:
                wrapper.wait(30)
            except subprocess.TimeoutExpired:
                wrapper.kill()
        log.close()
    with open(os.path.join(a.work, "soak.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["t_s", "rss_kb", "fds"])
        w.writerows(rows)
    half = rows[len(rows) // 2:]
    rss_slope = slope([(t / 3600, rss / 1024) for t, rss, _ in half])  # MB per hour
    fd_growth = (half[-1][2] - half[0][2]) if half else 0
    if verdict is None:
        if len(rows) < 10:
            verdict = f"only {len(rows)} samples"
        elif rss_slope > a.max_rss_mb_per_hour:
            verdict = f"RSS grows {rss_slope:.1f} MB/h over the second half"
        elif fd_growth > a.max_fd_growth:
            verdict = f"fd count grows by {fd_growth} over the second half"
    result = {"passed": verdict is None, "verdict": verdict or "steady", "samples": len(rows),
              "minutes": a.minutes, "rss_mb_first": rows[0][1] / 1024 if rows else None,
              "rss_mb_last": rows[-1][1] / 1024 if rows else None, "rss_slope_mb_per_hour_second_half": round(rss_slope, 2),
              "fds_first": rows[0][2] if rows else None, "fds_last": rows[-1][2] if rows else None,
              "fd_growth_second_half": fd_growth, "input": bool(xdotool)}
    with open(os.path.join(a.work, "soak.json"), "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
