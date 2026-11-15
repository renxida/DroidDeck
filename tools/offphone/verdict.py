#!/usr/bin/env python3
"""Turn one rig session's events into verdict.json: which launch stage it reached, and when.

  verdict.py --session-dir rig-session --scenario steam-ui|stand-in-game [--rig-events rig-events.jsonl]

Stage times are seconds after the app was asked to start (agent.start_requested). The app's own
events come from the session's events.jsonl; the rig adds what the app cannot see (Steam's login
screen through DevTools, the stand-in game's own stdout markers) to rig-events.jsonl.
Exit status 0 only when the scenario's target stage was reached.
"""
import argparse
import json
import os
import re
import sys

APP_STAGES = [
    ("start_requested", "agent.start_requested"),
    ("runtime_ready", "runtime.ready"),
    ("proton_ready", "proton.ready"),
    ("guest_started", "guest.started"),
    ("steam_starting", "steam.starting"),
    ("steam_ui", "steam.ui_first_frame"),
    ("game_first_frame", "game.first_frame"),
]
SCENARIOS = {
    "steam-ui": ["start_requested", "guest_started", "steam_starting", "steam_ui", "login_screen"],
    # The stand-in's own stdout markers (FIRST_FRAME, MAINLOOP_OK) do not survive Proton's steam.exe
    # (.NET's Console has no handle there), so the app's own frame log is the evidence: the game's
    # Vulkan frames as gamescope presented them, then a clean exit of the guest.
    "stand-in-game": ["start_requested", "guest_started", "standin_first_frame", "standin_frames",
                      "standin_exit_clean"],
    # runtime_ready and proton_ready only fire when something was installed, so they are not required.
}
STEAM = ["start_requested", "guest_started", "steam_starting", "steam_ui", "login_screen"]
SCENARIOS.update({
    "steam-ui-screen-cycle": STEAM[:3] + ["screen_cycled"] + STEAM[3:],
    # The launcher icon must bring the running session back (returned_to_session); landing on the
    # home screen instead is logged as landed_on_home and fails the run.
    "steam-ui-background": STEAM[:3] + ["backgrounded", "returned_to_session"] + STEAM[3:],
    "stand-in-background": SCENARIOS["stand-in-game"][:2] + ["backgrounded", "returned_to_session"]
                           + SCENARIOS["stand-in-game"][2:],
    # The native engine, not Proton: the session log's last engine line is "native <tag>" (game-env prints a
    # "proton ...; native skipped: ..." line when it falls back), and the stand-in's own stdout markers, which
    # natively reach native-engine.log, show it ran to its last frame.
    "stand-in-native": ["start_requested", "guest_started", "native_engine", "standin_first_frame",
                        "standin_mainloop_ok"],
    "upgrade": ["upgrade_installed", "settings_kept", "runtime_kept"] + STEAM,
})
# Seconds after the start request by which a stage must have happened. CI's Steam UI takes about
# 70 s; the cdabfbd regression made a display change cost minutes.
DEADLINES = {
    "steam-ui": {"steam_ui": 180},
    "steam-ui-screen-cycle": {"steam_ui": 180},
    "steam-ui-background": {"steam_ui": 210},
    "upgrade": {"steam_ui": 180},
}


def read_jsonl(path):
    out = []
    if not path or not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out


MIN_FRAMES = 100  # the stand-in quits after 300 frames at 30 fps; presented frames can be fewer


def game_frames(path):
    """(wall ms of the game's first Vulkan frame, Vulkan frames presented) from frames.csv."""
    if not os.path.isfile(path):
        return None, 0
    t0, engine, first, count = None, None, None, 0
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        m = re.search(r"t0_wall_ms=(\d+)", line)
        if m:
            t0 = int(m.group(1))
        m = re.match(r"# t_ms=\d+ engine=(\S+)", line)
        if m:
            engine = m.group(1)
            continue
        m = re.match(r"(\d+),\d+$", line)
        if m and engine == "VULKAN":
            count += 1
            if first is None and t0 is not None:
                first = t0 + int(m.group(1))
    return first, count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session-dir", required=True)
    ap.add_argument("--scenario", choices=sorted(SCENARIOS), required=True)
    ap.add_argument("--rig-events")
    ap.add_argument("--extra-logs", help="more folders whose logs may hold the stand-in's markers")
    ap.add_argument("--out", default="verdict.json")
    a = ap.parse_args()

    events = read_jsonl(os.path.join(a.session_dir, "events.jsonl"))
    rig = read_jsonl(a.rig_events)
    start = next((e["t"] for e in events if e.get("event") == "agent.start_requested"), None)
    if start is None and events:
        start = min(e.get("t", 0) for e in events if not e.get("before_session"))

    seen = {}
    for name, event in APP_STAGES:
        t = next((e["t"] for e in events if e.get("event") == event), None)
        if t is not None:
            seen[name] = t
    for e in rig:
        name = e.get("stage")
        if name and name not in seen and "t" in e:
            seen[name] = e["t"]

    # The stand-in prints its own markers; the session log carries the guest's stdout.
    # Under Proton the game's stdout can land in Proton's own log (games/*/steam-*.log) instead.
    logs = [os.path.join(a.session_dir, "session.log")]
    logs += [os.path.join(a.session_dir, f) for f in ("native-engine.log",)]
    for folder in (os.path.join(a.session_dir, "games"), a.extra_logs):
        if folder and os.path.isdir(folder):
            for root, _, files in os.walk(folder):
                logs += [os.path.join(root, f) for f in files if f.endswith((".log", ".txt"))]
    for log in logs:
        if not os.path.isfile(log):
            continue
        text = open(log, encoding="utf-8", errors="replace").read()
        for stage, pattern in (("standin_first_frame", r"^FIRST_FRAME"), ("standin_mainloop_ok", r"^MAINLOOP_OK")):
            if stage not in seen and re.search(pattern, text, re.M):
                seen[stage] = None  # present, time unknown from the log alone

    engine_lines = []
    session_log = os.path.join(a.session_dir, "session.log")
    if os.path.isfile(session_log):
        engine_lines = re.findall(r"droiddeck-game-env: engine: (.*)",
                                  open(session_log, encoding="utf-8", errors="replace").read())
    if engine_lines and engine_lines[-1].startswith("native ") and "skipped" not in engine_lines[-1]:
        seen["native_engine"] = None

    frames = None
    if a.scenario.startswith("stand-in") and a.scenario != "stand-in-native":
        first, frames = game_frames(os.path.join(a.session_dir, "frames.csv"))
        if first is not None:
            seen["standin_first_frame"] = first
            if frames >= MIN_FRAMES:
                seen["standin_frames"] = first
        done = next((e for e in events if e.get("event") == "guest.exited"), None)
        last = [e for e in events if e.get("event") == "exit.classified"]
        if "standin_frames" in seen and done and done.get("status") == 0 and last and last[-1].get("class") == "clean":
            seen["standin_exit_clean"] = done["t"]

    # Deadlines count from the rig's own start command, so a session the app restarted later
    # cannot look fast.
    sent = next((e["t"] for e in rig if e.get("stage") == "start_sent"), start)
    late = {}
    for stage, limit in DEADLINES.get(a.scenario, {}).items():
        t = seen.get(stage)
        if t is not None and sent is not None and (t - sent) / 1000.0 > limit:
            late[stage] = {"seconds": round((t - sent) / 1000.0, 1), "limit": limit}
            del seen[stage]
    # Stages after a restart belong to another session than the one the rig started.
    restarted = next((e["t"] for e in rig if e.get("stage") == "session_restarted"), None)
    if restarted is not None:
        for stage in [s for s, t in seen.items() if t is not None and t >= restarted]:
            del seen[stage]
    seen.pop("start_sent", None)

    order = SCENARIOS[a.scenario]
    reached = None
    for stage in order:
        if stage in seen:
            reached = stage
        else:
            break
    exits = [e for e in events if e.get("event") == "exit.classified"]
    last_exit = exits[-1] if exits else None
    seconds = {}
    for stage, t in seen.items():
        seconds[stage] = round((t - start) / 1000.0, 1) if (t is not None and start is not None) else None
    verdict = {
        "scenario": a.scenario,
        "target_stage": order[-1],
        "stage_reached": reached,
        "ok": reached == order[-1],
        "stages": order,
        "seconds_to_each_stage": {s: seconds.get(s) for s in order if s in seconds},
        "other_stages": {s: seconds[s] for s in seconds if s not in order},
        "exit": {k: last_exit.get(k) for k in ("class", "suspect", "status", "signal", "detail")} if last_exit else None,
        "next_missing_stage": next((s for s in order if s not in seen), None),
        "game_vulkan_frames": frames,
        "engine_lines": engine_lines or None,
        "too_late": late or None,
        "session_restarted": restarted is not None,
    }
    with open(a.out, "w") as f:
        json.dump(verdict, f, indent=2)
        f.write("\n")
    print(json.dumps(verdict, indent=2))
    return 0 if verdict["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
