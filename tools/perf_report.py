#!/usr/bin/env python3
"""Performance report for Kestrel session telemetry.

Inputs are share-bundle zips (DroidDeck-<session>-session.zip and friends), telemetry upload zips
(runs/<build>/<device>/<start>.zip), extracted session folders, or directories holding any of
these. Files of one session spread over several zips are merged. With two sessions the report
ends with a side-by-side comparison; with more, a summary table with one column per session.

Read from each session (by base name, inside an optional <session>/ folder):
  build.txt, device.txt, graphics-report.txt, upload.txt  build, device and GPU/driver
  events.jsonl                                            timeline events (t = epoch ms)
  session.log                                             "== STEP HH:MM:SS.mmm" lines (UTC) and
                                                          "DROIDDECK_TIMELINE ... wall_ms=" lines
  frames.csv                                              per-frame times (t_ms,frametime_us)
  clocks.log                                              CPU/GPU clocks, thermal status, battery
  memory.csv                                              memory samples
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import re
import sys
import zipfile
from pathlib import Path

KNOWN = (
    "build.txt", "device.txt", "graphics-report.txt", "upload.txt", "events.jsonl", "session.log",
    "frames.csv", "clocks.log", "memory.csv",
)
SNIFF = (
    ("== clocks:", "clocks.log"),
    ("# frames ", "frames.csv"),
    ("build_label:", "build.txt"),
    ("t_wall_ms,", "memory.csv"),
    ("== graphics report", "graphics-report.txt"),
)
HASHED = re.compile(r"entry-[0-9a-f]{16}\.(?:log|txt|csv|jsonl)$")
SESSION_DIR = re.compile(r"^\d{4}-\d{2}-\d{2}-\d{2,}-[A-Za-z0-9-]+$")
STEP = re.compile(r"== STEP (\d{2}):(\d{2}):(\d{2})\.(\d{3}) (.*)$")
TIMELINE = re.compile(r"DROIDDECK_TIMELINE event=(\S+).*?\bwall_ms=(\d+)")
STEP_MILESTONES = (
    ("checking the x86-64 steam client", "steam.client_check"),
    ("checking the steam client", "steam.client_check"),
    ("x86-64 steam client ready", "steam.client_ready"),
    ("steam client ready", "steam.client_ready"),
    ("starting the steam client", "steam.process_start"),
)
PHASES = (
    ("app start -> front end drawn", "app.process_start", "frontend.first_frame"),
    ("session created -> service started", "session.created", "service.started"),
    ("runtime install", "runtime.installing", "runtime.ready"),
    ("  runtime download", "setup.runtime.download", "setup.runtime.verify"),
    ("  runtime verify", "setup.runtime.verify", "setup.runtime.extract"),
    ("  runtime extract", "setup.runtime.extract", "setup.runtime.extracted"),
    ("package resume", "package.resuming", "package.ready"),
    ("Proton install", "proton.installing", "proton.ready"),
    ("compositor start", "compositor.starting", "compositor.started"),
    ("guest start", "guest.starting", "guest.started"),
    ("Steam client check", "steam.client_check", "steam.client_ready"),
    ("Steam start -> first Steam frame", "steam.process_start", "steam.ui_first_frame"),
    ("Steam start -> signed in", "steam.process_start", "steam.signed_in"),
    ("game launch request -> game exec", "steam.game_launch_requested", "game.exec"),
    ("game exec -> first game frame", "game.exec", "game.first_frame"),
    ("session created -> first game frame", "session.created", "game.first_frame"),
)
TIMELINE_SHOWN = (
    "app.process_start", "frontend.first_frame", "session.created", "service.started", "runtime.ready",
    "compositor.started", "guest.started", "steam.client_ready", "steam.process_start", "frame.first",
    "steam.ui_first_frame", "steam.signed_in", "session.ready", "steam.game_launch_requested", "game.exec",
    "game.first_frame", "session.stopping", "session.stopped", "session.failed",
)
PAUSE_US = 1_000_000


class Session:
    def __init__(self, key: str):
        self.key = key
        self.sources: list[str] = []
        self.files: dict[str, str] = {}

    def add(self, name: str, text: str, source: str):
        self.files.setdefault(name, text)
        if source not in self.sources:
            self.sources.append(source)


def _decode(data: bytes) -> str:
    return data.decode("utf-8", errors="replace")


def _identify(path: str, head: bytes):
    base = path.rsplit("/", 1)[-1]
    if base in KNOWN:
        return base
    if HASHED.match(base):
        text = _decode(head)
        for prefix, name in SNIFF:
            if text.startswith(prefix):
                return name
    return None


def _session_key(files: dict, folder, fallback: str) -> str:
    if folder:
        return folder
    upload = files.get("upload.txt", "")
    m = re.search(r"^session: (\S+)", upload, re.M)
    if m:
        return m.group(1)
    for line in files.get("events.jsonl", "").splitlines():
        try:
            sid = json.loads(line).get("sessionId")
        except (ValueError, AttributeError):
            continue
        if sid:
            return sid
    return fallback


def _read_zip(path: Path):
    files, folder = {}, None
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            parts = info.filename.split("/")
            if len(parts) > 2:
                continue
            if len(parts) == 2:
                if not SESSION_DIR.match(parts[0]):
                    continue
                folder = folder or parts[0]
            with archive.open(info) as stream:
                head = stream.read(64)
            name = _identify(info.filename, head)
            if name and name not in files:
                files[name] = _decode(archive.read(info))
    return files, folder


def _read_dir(path: Path):
    files = {}
    for child in sorted(path.iterdir()):
        if not child.is_file():
            continue
        with child.open("rb") as stream:
            head = stream.read(64)
        name = _identify(child.name, head)
        if name and name not in files:
            files[name] = _decode(child.read_bytes())
    return files, path.name if SESSION_DIR.match(path.name) else None


def load(inputs) -> list[Session]:
    sources = []
    for raw in inputs:
        path = Path(raw)
        if path.is_file() and zipfile.is_zipfile(path):
            sources.append(path)
        elif path.is_dir():
            sources.extend(sorted(p for p in path.rglob("*.zip") if zipfile.is_zipfile(p)))
            dirs = {p.parent for name in KNOWN for p in path.rglob(name)}
            sources.extend(sorted(dirs))
        else:
            raise SystemExit(f"perf_report: {raw}: not a zip or directory")
    sessions: dict[str, Session] = {}
    for source in sources:
        try:
            files, folder = _read_zip(source) if source.is_file() else _read_dir(source)
        except (OSError, zipfile.BadZipFile) as error:
            print(f"perf_report: skipping {source}: {error}", file=sys.stderr)
            continue
        if not files:
            continue
        key = _session_key(files, folder, source.stem)
        session = sessions.setdefault(key, Session(key))
        for name, text in files.items():
            session.add(name, text, str(source))
    return list(sessions.values())


def parse_kv(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            key = key.strip()
            if key and key not in out:
                out[key] = value.strip()
    return out


def parse_events(text: str) -> list[dict]:
    events = []
    for line in text.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if isinstance(event, dict) and isinstance(event.get("t"), (int, float)) and event.get("event"):
            events.append(event)
    return sorted(events, key=lambda e: e["t"])


def parse_log(text: str, t0) -> list[tuple[str, int]]:
    marks = []
    if t0 is not None:
        day = _dt.datetime.fromtimestamp(t0 / 1000, _dt.timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        midnight = int(day.timestamp() * 1000)
    for line in text.splitlines():
        m = TIMELINE.search(line)
        if m:
            marks.append((m.group(1).replace("_", ".", 1), int(m.group(2))))
            continue
        m = STEP.search(line)
        if not m or t0 is None:
            continue
        h, mi, s, ms, what = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)), m.group(5).lower()
        t = midnight + ((h * 60 + mi) * 60 + s) * 1000 + ms
        if t < t0 - 3_600_000:
            t += 86_400_000
        for needle, name in STEP_MILESTONES:
            if what.startswith(needle):
                marks.append((name, t))
                break
    return marks


def percentile(sorted_values, p):
    if not sorted_values:
        return None
    return sorted_values[min(len(sorted_values) - 1, max(0, round(p * (len(sorted_values) - 1))))]


def parse_frames(text: str) -> dict:
    t0_wall = None
    infos = []
    rows = []
    for line in text.splitlines():
        if line.startswith("#"):
            m = re.search(r"t0_wall_ms=(\d+)", line)
            if m and t0_wall is None:
                t0_wall = int(m.group(1))
            m = re.match(r"# t_ms=(-?\d+) engine=(\S+) pid=(\d+) steam=(\d)", line)
            if m:
                infos.append((int(m.group(1)), m.group(2), int(m.group(3)), m.group(4) == "1"))
            continue
        parts = line.split(",")
        if len(parts) != 2:
            continue
        try:
            rows.append((int(parts[0]), int(parts[1])))
        except ValueError:
            continue
    rows.sort()
    if infos:
        game, i, current = [], 0, None
        for t, ft in rows:
            while i < len(infos) and infos[i][0] <= t:
                current = infos[i]
                i += 1
            if current and not current[3] and current[1] not in ("none", "GAMESCOPE"):
                game.append((t, ft))
    else:
        game = rows
    stats = frame_stats(game)
    stats["frames_total"] = len(rows)
    stats["engines"] = sorted({e for _, e, _, s in infos if not s and e not in ("none", "GAMESCOPE")})
    stats["t0_wall_ms"] = t0_wall
    stats["game_start_t_ms"] = game[0][0] if game else None
    return stats


def frame_stats(frames) -> dict:
    live = [ft for _, ft in frames if 0 < ft < PAUSE_US]
    out = {"frames": len(live), "pauses": len(frames) - len(live)}
    if len(live) < 2:
        return out
    ordered = sorted(live)
    total_us = sum(live)
    median = percentile(ordered, 0.5)
    p99, p999 = percentile(ordered, 0.99), percentile(ordered, 0.999)
    out.update({
        "seconds": total_us / 1e6,
        "avg_fps": len(live) / (total_us / 1e6),
        "median_ms": median / 1000,
        "p95_ms": percentile(ordered, 0.95) / 1000,
        "p99_ms": p99 / 1000,
        "p999_ms": p999 / 1000,
        "low1_fps": 1e6 / p99,
        "low01_fps": 1e6 / p999,
        "spikes_2x_median": sum(1 for ft in live if ft > 2 * median),
    })
    minutes = {}
    start = frames[0][0]
    for t, ft in frames:
        if 0 < ft < PAUSE_US:
            bucket = minutes.setdefault((t - start) // 60_000, [0, 0])
            bucket[0] += 1
            bucket[1] += ft
    out["fps_by_minute"] = [round(n / (us / 1e6), 1) for _, (n, us) in sorted(minutes.items()) if us > 0]
    return out


CLOCK_LINE = re.compile(r"^(\d{2}):(\d{2}):(\d{2}) (.*)$")
POLICY = re.compile(r"\bp(\d+) (\?|\d+)/(\?|\d+)(?:\(of (\d+)\))?")


def parse_clocks(text: str) -> dict:
    start_wall = None
    samples = []
    first_sod = prev_sod = None
    day = 0
    for line in text.splitlines():
        m = re.match(r"start: wall_ms=(\d+)", line)
        if m:
            start_wall = int(m.group(1))
            continue
        m = CLOCK_LINE.match(line)
        if not m:
            continue
        sod = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
        if prev_sod is not None and sod < prev_sod - 3600:
            day += 1
        prev_sod = sod
        if first_sod is None:
            first_sod = sod
        rest = m.group(4)
        sample = {"s": sod + day * 86400 - first_sod, "policies": {}}
        for p in POLICY.finditer(rest):
            num = lambda v: None if v in (None, "?") else int(v)
            sample["policies"][int(p.group(1))] = (num(p.group(2)), num(p.group(3)), num(p.group(4)))
        for key, pattern in (("gpu", r"\| gpu (\d+) MHz"), ("thermal", r"\| thermal (-?\d+)"),
                             ("headroom", r"headroom ([\d.]+)"), ("battery", r"\| battery ([\d.]+) C")):
            g = re.search(pattern, rest)
            if g:
                sample[key] = float(g.group(1))
        samples.append(sample)
    out = {"start_wall_ms": start_wall, "samples": len(samples)}
    if not samples:
        return out
    out["seconds"] = samples[-1]["s"]
    thermals = [(s["s"], s["thermal"]) for s in samples if "thermal" in s]
    if thermals:
        out["thermal_max"] = int(max(v for _, v in thermals))
        out["thermal_first_s"] = next((t for t, v in thermals if v > 0), None)
    heads = [s["headroom"] for s in samples if "headroom" in s]
    if heads:
        out["headroom_max"] = max(heads)
    batt = [s["battery"] for s in samples if "battery" in s]
    if batt:
        out["battery_start"], out["battery_max"], out["battery_end"] = batt[0], max(batt), batt[-1]
    gpu = [s["gpu"] for s in samples if "gpu" in s]
    if gpu:
        out["gpu_mhz_avg"], out["gpu_mhz_max"] = sum(gpu) / len(gpu), max(gpu)
    caps = {}
    first_cap = None
    for s in samples:
        for pol, (cur, mx, hw) in s["policies"].items():
            changes = caps.setdefault(pol, [])
            if mx is not None and (not changes or changes[-1][1] != mx):
                changes.append((s["s"], mx, hw))
            if first_cap is None and mx is not None and hw is not None and mx < hw:
                first_cap = (s["s"], pol, mx, hw)
    out["first_cap"] = first_cap
    out["caps"] = {pol: changes for pol, changes in sorted(caps.items())}
    return out


def parse_memory(text: str) -> dict:
    lines = text.splitlines()
    if not lines:
        return {}
    header = lines[0].split(",")
    rows = []
    for line in lines[1:]:
        values = line.split(",")
        if len(values) != len(header):
            continue
        rows.append({k: int(v) for k, v in zip(header, values) if v.strip().lstrip("-").isdigit()})
    out = {"samples": len(rows)}
    for key, agg in (("game_mb", max), ("droiddeck_mb", max), ("app_mb", max), ("swap_used_mb", max), ("available_mb", min)):
        values = [r[key] for r in rows if key in r]
        if values:
            out[f"{key}_{agg.__name__}"] = agg(values)
    return out


def summarize(session: Session) -> dict:
    f = session.files
    build = parse_kv(f.get("build.txt", ""))
    device = parse_kv(f.get("device.txt", ""))
    graphics = parse_kv(f.get("graphics-report.txt", ""))
    events = parse_events(f.get("events.jsonl", ""))
    t0 = next((e["t"] for e in events if e["event"] == "session.created"), events[0]["t"] if events else None)
    milestones: dict[str, list[int]] = {}
    for e in events:
        milestones.setdefault(e["event"], []).append(int(e["t"]))
    for name, t in parse_log(f.get("session.log", ""), t0):
        milestones.setdefault(name, []).append(t)
    for times in milestones.values():
        times.sort()
    if t0 is None and milestones:
        t0 = min(t for times in milestones.values() for t in times)
    phases = []
    for label, start, end in PHASES:
        s = milestones.get(start, [None])[0]
        e = next((t for t in milestones.get(end, []) if s is not None and t >= s), None)
        phases.append((label, (e - s) / 1000 if s is not None and e is not None else None))
    frames = parse_frames(f["frames.csv"]) if "frames.csv" in f else {}
    clocks = parse_clocks(f["clocks.log"]) if "clocks.log" in f else {}
    game_first = milestones.get("game.first_frame", [None])[0]
    if clocks.get("start_wall_ms") is not None and t0 is not None:
        clocks["offset_s"] = (clocks["start_wall_ms"] - t0) / 1000
        if game_first is not None:
            clocks["game_offset_s"] = (game_first - clocks["start_wall_ms"]) / 1000
    label = build.get("build_label") or device.get("App build") or graphics.get("build") or "?"
    return {
        "session": session.key,
        "sources": session.sources,
        "build": label,
        "build_slug": build.get("build_slug"),
        "commit": build.get("commit"),
        "device": build.get("device") or device.get("Model") or "?",
        "soc": build.get("soc") or device.get("SoC"),
        "android": build.get("android") or device.get("Android"),
        "gpu": build.get("gpu") or device.get("KGSL gpu_model"),
        "vulkan": " ".join(v for v in (build.get("vulkan_device"), build.get("vulkan_driver_version")) if v) or None,
        "graphics_path": graphics.get("path"),
        "driver_mode": build.get("gpu_driver_mode") or graphics.get("driver mode"),
        "display_driver": graphics.get("display driver (compositor)"),
        "runtime_driver": graphics.get("runtime driver"),
        "t0": t0,
        "timeline": {name: (times[0] - t0) / 1000 for name, times in milestones.items() if t0 is not None},
        "phases": phases,
        "frames": frames,
        "clocks": clocks,
        "memory": parse_memory(f["memory.csv"]) if "memory.csv" in f else {},
        "files": sorted(f),
    }


def _num(value, fmt="{:.1f}"):
    return "-" if value is None else fmt.format(value)


def _cap_text(clocks: dict) -> str:
    cap = clocks.get("first_cap")
    if not cap:
        return "none"
    t, pol, mx, hw = cap
    return f"+{t}s p{pol} {mx}/{hw} MHz"


def report(s: dict) -> str:
    out = [f"== {s['session']}"]
    out.append(f"build:   {s['build']}" + (f"  [{s['build_slug']}]" if s.get("build_slug") else ""))
    out.append(f"device:  {s['device']}" + "".join(f", {v}" for v in (s.get("soc"), s.get("android")) if v))
    out.append(f"gpu:     {s.get('gpu') or '?'}" + (f"; vulkan {s['vulkan']}" if s.get("vulkan") else ""))
    for key, label in (("graphics_path", "path"), ("driver_mode", "driver mode"), ("display_driver", "display"),
                       ("runtime_driver", "runtime")):
        if s.get(key):
            out.append(f"  {label}: {s[key]}")
    out.append(f"files:   {', '.join(s['files'])}")
    if s["timeline"]:
        out.append("timeline (s from session created):")
        shown = [(t, n) for n, t in s["timeline"].items() if n in TIMELINE_SHOWN]
        for t, name in sorted(shown):
            out.append(f"  {t:+9.1f}  {name}")
    timed = [(label, d) for label, d in s["phases"] if d is not None]
    if timed:
        out.append("phases (s):")
        for label, d in timed:
            out.append(f"  {label:<38} {d:8.1f}")
    fr = s["frames"]
    if fr:
        out.append(f"frames:  {fr.get('frames', 0)} in game of {fr.get('frames_total', 0)}"
                   + (f" ({', '.join(fr['engines'])})" if fr.get("engines") else "")
                   + (f", {fr['pauses']} pauses >1s" if fr.get("pauses") else ""))
        if "avg_fps" in fr:
            out.append(f"  avg {fr['avg_fps']:.1f} fps over {fr['seconds']:.0f} s; frametime median {fr['median_ms']:.2f}"
                       f" p95 {fr['p95_ms']:.2f} p99 {fr['p99_ms']:.2f} ms")
            out.append(f"  1% low {fr['low1_fps']:.1f} fps, 0.1% low {fr['low01_fps']:.1f} fps,"
                       f" {fr['spikes_2x_median']} frames > 2x median")
            if fr.get("fps_by_minute"):
                out.append("  fps by minute: " + " ".join(str(v) for v in fr["fps_by_minute"][:40]))
    ck = s["clocks"]
    if ck.get("samples"):
        out.append(f"clocks:  {ck['samples']} samples over {ck.get('seconds', 0)} s"
                   + (f", started {ck['offset_s']:+.1f} s from session created" if ck.get("offset_s") is not None else ""))
        if "thermal_max" in ck:
            first = ck.get("thermal_first_s")
            out.append(f"  thermal status max {ck['thermal_max']}" + (f", first >0 at +{first} s" if first is not None else "")
                       + (f", headroom max {ck['headroom_max']:.2f}" if "headroom_max" in ck else ""))
        out.append(f"  first CPU cap: {_cap_text(ck)}"
                   + (f" (game started at clocks +{ck['game_offset_s']:.0f} s)" if ck.get("game_offset_s") is not None else ""))
        if "battery_start" in ck:
            out.append(f"  battery {ck['battery_start']:.1f} C start, {ck['battery_max']:.1f} C max, {ck['battery_end']:.1f} C end")
        if "gpu_mhz_avg" in ck:
            out.append(f"  gpu {ck['gpu_mhz_avg']:.0f} MHz avg, {ck['gpu_mhz_max']:.0f} max")
        for pol, changes in ck.get("caps", {}).items():
            if len(changes) > 1 or (changes and changes[0][2] and changes[0][1] < changes[0][2]):
                steps = " -> ".join(f"{mx}@+{t}s" for t, mx, _ in changes[:10]) + (" ..." if len(changes) > 10 else "")
                out.append(f"  p{pol} max: {steps}")
    mem = s["memory"]
    if mem.get("samples"):
        out.append("memory:  " + ", ".join(f"{k} {v} MB" for k, v in mem.items() if k != "samples"))
    return "\n".join(out)


def _metrics(s: dict) -> list[tuple[str, object, bool]]:
    """(label, value, higher_is_better or None) rows for the comparison table."""
    fr, ck, mem = s["frames"], s["clocks"], s["memory"]
    rows: list = [(f"phase: {label.strip()}", d, False) for label, d in s["phases"]]
    rows += [
        ("frames in game", fr.get("frames"), None),
        ("seconds in game", fr.get("seconds"), None),
        ("avg fps", fr.get("avg_fps"), True),
        ("median frametime ms", fr.get("median_ms"), False),
        ("p95 frametime ms", fr.get("p95_ms"), False),
        ("p99 frametime ms", fr.get("p99_ms"), False),
        ("1% low fps", fr.get("low1_fps"), True),
        ("0.1% low fps", fr.get("low01_fps"), True),
        ("frames > 2x median", fr.get("spikes_2x_median"), False),
        ("thermal status max", ck.get("thermal_max"), False),
        ("thermal >0 at s", ck.get("thermal_first_s"), True),
        ("first CPU cap at s", ck["first_cap"][0] if ck.get("first_cap") else None, True),
        ("battery start C", ck.get("battery_start"), False),
        ("battery max C", ck.get("battery_max"), False),
        ("game memory max MB", mem.get("game_mb_max"), False),
        ("app memory max MB", mem.get("app_mb_max"), False),
        ("available min MB", mem.get("available_mb_min"), True),
    ]
    return rows


def compare(summaries: list[dict]) -> str:
    names = [s.get("build_slug") or s["build"] for s in summaries]
    width = min(26, max(12, *(len(n) for n in names), *(len(s["session"]) for s in summaries))) + 2
    label_width = 44

    def row(label, cells):
        return f"  {label[:label_width]:<{label_width}}" + "".join(f"{c[:width - 2]:>{width}}" for c in cells)

    out = ["== comparison", row("build", names) + ("    delta (2nd - 1st)" if len(summaries) == 2 else "")]
    out.append(row("session", [s["session"] for s in summaries]))
    out.append(row("device", [s["device"].split(" (")[0] for s in summaries]))
    columns = [_metrics(s) for s in summaries]
    for i, (label, _, higher) in enumerate(columns[0]):
        values = [c[i][1] for c in columns]
        if all(v is None for v in values):
            continue
        line = row(label, [_num(v, "{:d}" if isinstance(v, int) else "{:.1f}") for v in values])
        if len(values) == 2 and None not in values:
            a, b = values
            delta = b - a
            pct = f" ({delta / a * 100:+.0f}%)" if a else ""
            better = (delta > 0) == higher if delta and higher is not None else None
            line += f"  {delta:+.1f}{pct}" + ("" if better is None else (" better" if better else " worse"))
        out.append(line)
    return "\n".join(out)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("inputs", nargs="+", help="share or upload zips, session folders, or directories of them")
    parser.add_argument("--json", action="store_true", help="print the parsed summaries as JSON")
    args = parser.parse_args(argv)
    sessions = load(args.inputs)
    if not sessions:
        print("perf_report: no session telemetry found", file=sys.stderr)
        return 1
    summaries = sorted((summarize(s) for s in sessions), key=lambda s: (s["t0"] or 0, s["session"]))
    if args.json:
        print(json.dumps(summaries, indent=2, default=str))
        return 0
    print("\n\n".join(report(s) for s in summaries))
    if len(summaries) >= 2:
        print()
        print(compare(summaries))
    return 0


if __name__ == "__main__":
    sys.exit(main())
