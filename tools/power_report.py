#!/usr/bin/env python3
"""Battery cost per run and per experiment set from Kestrel telemetry bundles.

Inputs are what tools/perf_report.py reads: upload zips (runs/<build>/<device>/<start>.zip), share
zips, session folders or directories of them, e.g. a clone of the telemetry repository's runs/.
For each session it prints the game phase's minutes, average fps, average power drawn from the
battery (mW), battery %/hour and mWh per minute of play, then the same added up per run (the
build directory under runs/) and per experiment set, cheapest energy per minute first.

Power comes from power.csv (written by the app's battery sampler every 5 s): only intervals that
are unplugged at both ends count, a gap over 30 s (app stopped) counts as nothing, and game time
is rows marked game=1 at both ends, the same rules as the app's power-summary.txt. Bundles from
before power.csv have no power figures; their game minutes come from frames.csv instead and they
show the battery's peak temperature from clocks.log, the only battery reading they hold.

Experiment sets are the switches on when the game started (game.experiments, else
session.experiments, else build.txt), minus the always-on ones listed in config/kestrel.json when
that file is found above an input or given with --config.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import perf_report  # noqa: E402

MAX_GAP_MS = 30_000
RUN_DIR = re.compile(r"(?:^|/)runs/([^/]+)/([^/]+)/([^/]+)\.zip$")


def _int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def parse_power_rows(text: str) -> list[dict]:
    """power.csv rows by column name; the header may come back after a restart, comments are skipped."""
    header, rows = None, []
    for line in text.splitlines():
        line = line.rstrip("\r")
        if line.startswith("t_wall_ms,"):
            header = line.split(",")
            continue
        if not header or not line or not line[0].isdigit():
            continue
        cells = dict(zip(header, (c.strip() for c in line.split(","))))
        t = _int(cells.get("t_wall_ms"))
        if t is None:
            continue
        rows.append({
            "t": t,
            "current_ua": _int(cells.get("current_ua")),
            "charge_uah": _int(cells.get("charge_uah")),
            "power_mw": _float(cells.get("power_mw")),
            "level": _int(cells.get("level_pct")),
            "unplugged": (cells.get("plugged") or "none") == "none",
            "game": cells.get("game") == "1",
            "temp_c": _float(cells.get("temp_c")),
        })
    return rows


def _mean(a, b):
    if a is not None and b is not None:
        return (a + b) / 2
    return a if a is not None else b


def power_stats(rows: list[dict], max_gap_ms: int = MAX_GAP_MS) -> dict:
    """The game phase's power, mirroring PowerAccumulator in the app (telemetry/PowerLog.kt)."""
    game_ms = unplugged_ms = charging_ms = 0
    energy = ua_ms = unplugged_energy = 0.0
    counter_uah, counter_seen, level_drop, level_seen = 0, False, 0, False
    fulls = [r["charge_uah"] / 1000 * 100 / r["level"] for r in rows
             if r["charge_uah"] and r["charge_uah"] > 0 and r["level"] and r["level"] > 0]
    for p, r in zip(rows, rows[1:]):
        dt = r["t"] - p["t"]
        if dt <= 0 or dt > max_gap_ms:
            continue
        game = p["game"] and r["game"]
        if not (p["unplugged"] and r["unplugged"]):
            if game:
                charging_ms += dt
            continue
        mw = _mean(p["power_mw"], r["power_mw"])
        ua = _mean(*(abs(x["current_ua"]) if x["current_ua"] is not None else None for x in (p, r)))
        if mw is not None:
            unplugged_ms += dt
            unplugged_energy += mw * dt
        if not game or mw is None:
            continue
        game_ms += dt
        energy += mw * dt
        if ua is not None:
            ua_ms += ua * dt
        if p["charge_uah"] and r["charge_uah"] and p["charge_uah"] > 0 and r["charge_uah"] > 0:
            counter_uah += p["charge_uah"] - r["charge_uah"]
            counter_seen = True
        if p["level"] is not None and r["level"] is not None:
            level_drop += p["level"] - r["level"]
            level_seen = True
    out = {
        "rows": len(rows),
        "game_ms": game_ms,
        "game_energy_mwh": energy / 3_600_000,
        "game_mah": ua_ms / 3.6e9,
        "game_counter_mah": counter_uah / 1000 if counter_seen else None,
        "game_charging_ms": charging_ms,
        "unplugged_ms": unplugged_ms,
        "unplugged_avg_mw": unplugged_energy / unplugged_ms if unplugged_ms else None,
        "full_mah": sum(fulls) / len(fulls) if fulls else None,
        "game_avg_mw": energy / game_ms if game_ms else None,
        "game_pct_per_hour": None,
    }
    if game_ms:
        hours = game_ms / 3_600_000
        if out["full_mah"]:
            out["game_pct_per_hour"] = out["game_mah"] / out["full_mah"] * 100 / hours
        elif level_seen:
            out["game_pct_per_hour"] = level_drop / hours
    temps = [r["temp_c"] for r in rows if r["temp_c"] is not None]
    out["temp_max_c"] = max(temps) if temps else None
    return out


def find_config(paths) -> Path | None:
    """config/kestrel.json in an input's folder or any folder above it."""
    for raw in paths:
        p = Path(raw).resolve()
        for folder in [p] + list(p.parents):
            candidate = folder / "config" / "kestrel.json"
            if candidate.is_file():
                return candidate
    return None


def always_on(config: Path | None) -> set:
    if not config:
        return set()
    try:
        data = json.loads(config.read_text())
    except (OSError, ValueError):
        return set()
    return set((data.get("experiments") or {}).get("always") or [])


def experiments(events: list[dict], build: dict) -> list[str]:
    for name in ("game.experiments", "session.experiments"):
        found = [e for e in events if e["event"] == name and e.get("on")]
        if found:
            return sorted(x for x in str(found[-1]["on"]).split(",") if x)
    return sorted(x for x in (build.get("experiments_at_session_start") or "").split(",") if x)


def summarize(session: perf_report.Session, always: set) -> dict:
    f = session.files
    build = perf_report.parse_kv(f.get("build.txt", ""))
    events = perf_report.parse_events(f.get("events.jsonl", ""))
    run = start = None
    for source in session.sources:
        m = RUN_DIR.search(source.replace("\\", "/"))
        if m:
            run, start = m.group(1), m.group(3)
            break
    run = run or build.get("build_slug") or "?"
    frames = perf_report.parse_frames(f["frames.csv"]) if "frames.csv" in f else {}
    power = power_stats(parse_power_rows(f["power.csv"])) if "power.csv" in f else None
    on = experiments(events, build)
    rotating = [x for x in on if x not in always]
    if power and power["game_ms"]:
        minutes, source = power["game_ms"] / 60_000, "power"
    else:
        minutes, source = (frames.get("seconds") or 0) / 60, "frames"
    temp = power.get("temp_max_c") if power else None
    if temp is None and "clocks.log" in f:
        temp = perf_report.parse_clocks(f["clocks.log"]).get("battery_max")
    avg_mw = power.get("game_avg_mw") if power else None
    return {
        "session": session.key,
        "start": start or session.key,
        "run": run,
        "t0": events[0]["t"] if events else 0,
        "experiments": on,
        "experiment_set": "+".join(rotating) if rotating else ("(always-on only)" if on else "(not recorded)"),
        "game_minutes": minutes,
        "minutes_from": source,
        "frames": frames.get("frames", 0),
        "frame_seconds": frames.get("seconds") or 0.0,
        "avg_fps": frames.get("avg_fps"),
        "p95_ms": frames.get("p95_ms"),
        "power": power,
        "avg_mw": avg_mw,
        "pct_per_hour": power.get("game_pct_per_hour") if power else None,
        "mwh_per_min": avg_mw / 60 if avg_mw is not None else None,
        "charging_minutes_excluded": (power["game_charging_ms"] / 60_000) if power else None,
        "battery_temp_max_c": temp,
    }


def aggregate(rows: list[dict], key: str) -> list[dict]:
    """Sums per [key]: fps over all game frames, power weighted by game minutes with power data."""
    groups: dict[str, list[dict]] = {}
    for r in rows:
        groups.setdefault(r[key], []).append(r)
    out = []
    for name, group in groups.items():
        frames = sum(r["frames"] for r in group)
        seconds = sum(r["frame_seconds"] for r in group)
        powered = [r for r in group if r["power"] and r["power"]["game_ms"]]
        p_ms = sum(r["power"]["game_ms"] for r in powered)
        energy = sum(r["power"]["game_energy_mwh"] for r in powered)
        avg_mw = energy * 3_600_000 / p_ms if p_ms else None
        pct = [(r["pct_per_hour"], r["power"]["game_ms"]) for r in powered if r["pct_per_hour"] is not None]
        temps = [r["battery_temp_max_c"] for r in group if r["battery_temp_max_c"] is not None]
        out.append({
            key: name,
            "sessions": len(group),
            "game_minutes": sum(r["game_minutes"] for r in group),
            "avg_fps": frames / seconds if seconds else None,
            "avg_mw": avg_mw,
            "pct_per_hour": sum(v * w for v, w in pct) / sum(w for _, w in pct) if pct else None,
            "mwh_per_min": avg_mw / 60 if avg_mw is not None else None,
            "battery_temp_max_c": max(temps) if temps else None,
        })
    # cheapest energy per minute of play first, then the rest by fps
    return sorted(out, key=lambda g: (g["mwh_per_min"] is None, g["mwh_per_min"] or 0, -(g["avg_fps"] or 0)))


def _n(value, fmt="{:.1f}"):
    return "-" if value is None else fmt.format(value)


COLUMNS = (("game_min", "game_minutes", "{:.2f}"), ("avg_fps", "avg_fps", "{:.1f}"), ("avg_mW", "avg_mw", "{:.0f}"),
           ("%/h", "pct_per_hour", "{:.1f}"), ("mWh/min", "mwh_per_min", "{:.1f}"), ("batt_C", "battery_temp_max_c", "{:.1f}"))


def table(title: str, label: str, rows: list[dict], key: str, width: int = 36) -> list[str]:
    out = [f"== {title}", f"{label:<{width}}" + "".join(f"{h:>10}" for h, _, _ in COLUMNS)]
    for r in rows:
        name = str(r[key])
        if len(name) > width - 1:
            name = name[:width - 2] + "~"
        out.append(f"{name:<{width}}" + "".join(f"{_n(r[k], fmt):>10}" for _, k, fmt in COLUMNS))
    return out


def report(rows: list[dict], with_sessions: bool = True) -> str:
    out = []
    if with_sessions:
        out += table("sessions (oldest first)", "run / session start", [
            dict(r, label=f"{r['run']} {r['start']}") for r in rows], "label", 48)
        missing = [r for r in rows if not r["power"]]
        if missing:
            out.append(f"  no power.csv in {len(missing)} of {len(rows)} session(s): game minutes from frames.csv, "
                       "battery temperature from clocks.log, no power figures")
        excluded = sum(r["charging_minutes_excluded"] or 0 for r in rows)
        if excluded:
            out.append(f"  {excluded:.1f} min of game time while plugged in left out")
        out.append("")
    out += table("per run (cheapest mWh per minute of play first)", "run", aggregate(rows, "run"), "run")
    out.append("")
    out += table("per experiment set (switches on beyond the always-on ones)", "experiment set",
                 aggregate(rows, "experiment_set"), "experiment_set", 60)
    return "\n".join(out)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("inputs", nargs="+", help="upload or share zips, session folders, or directories of them")
    parser.add_argument("--config", help="config/kestrel.json for the always-on switches (default: found above the inputs)")
    parser.add_argument("--min-minutes", type=float, default=0.0, help="leave out sessions with less game time")
    parser.add_argument("--json", action="store_true", help="print the per-session figures as JSON")
    args = parser.parse_args(argv)
    sessions = perf_report.load(args.inputs)
    if not sessions:
        print("power_report: no session telemetry found", file=sys.stderr)
        return 1
    config = Path(args.config) if args.config else find_config(args.inputs)
    always = always_on(config)
    rows = sorted((summarize(s, always) for s in sessions), key=lambda r: (r["t0"], r["session"]))
    rows = [r for r in rows if r["game_minutes"] >= args.min_minutes]
    if not rows:
        print("power_report: no session with enough game time", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(rows, indent=2, default=str))
        return 0
    print(report(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
