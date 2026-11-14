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
    "stand-in-game": ["start_requested", "guest_started", "standin_first_frame", "standin_mainloop_ok"],
    # runtime_ready and proton_ready only fire when something was installed, so they are not required.
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session-dir", required=True)
    ap.add_argument("--scenario", choices=sorted(SCENARIOS), required=True)
    ap.add_argument("--rig-events")
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
    log = os.path.join(a.session_dir, "session.log")
    if os.path.isfile(log):
        text = open(log, encoding="utf-8", errors="replace").read()
        for stage, pattern in (("standin_first_frame", r"^FIRST_FRAME"), ("standin_mainloop_ok", r"^MAINLOOP_OK")):
            if stage not in seen and re.search(pattern, text, re.M):
                seen[stage] = None  # present, time unknown from the log alone

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
    }
    with open(a.out, "w") as f:
        json.dump(verdict, f, indent=2)
        f.write("\n")
    print(json.dumps(verdict, indent=2))
    return 0 if verdict["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
