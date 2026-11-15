#!/usr/bin/env python3
"""Triage a Kestrel telemetry zip into one verdict (docs/development/autofix-loop.md).

    triage.py ZIP... [--jsonl]          one verdict per zip (indented JSON, or one line each)
    triage.py --root KESTREL_TELEMETRY  every zip under <root>/runs/, one JSON line each

Library use: triage_zip(path) -> dict with exactly the verdict schema:
  run, slug, commit, branch, device, started_utc, kind, experiments, stage, outcome, signatures,
  metrics {steam_ui_s, game_first_frame_s, fps_p50, frametime_p99_ms, stutters_per_min,
           thermal_max, zram_used_pct_at_launch, run_s}

Definitions:
  session start  session.created, else frames.csv t0_wall_ms, else the clocks.log start line
  steam_ui_s     steam.ui_first_frame (else session.ready / frame.first) minus guest.started (else
                 session start); else the session.log "interface up after N ms" figure
  game_first_frame_s  first frame while the game had focus minus the game_exec mark (else the
                 launch request); game focus = a frames.csv focus line with steam=0 and a pid that
                 is neither 0 nor Steam's, after Steam first had focus
  fps_p50, frametime_p99_ms, stutters_per_min  over game-focus frames, frames of 1 s or more
                 (pauses) left out; a stutter is a frame over 2x the median
  zram_used_pct_at_launch  swap used at the game_exec mark / swap total (memory.lmk_risk, or swap
                 used + the "swap free N MB" the zRAM steps print)
  stage          furthest of app, setup, steam_ui, steam_signed_in, launch_requested, game_exec,
                 game_first_frame, in_game (30 s or more of game frames)
  outcome        fail (any fail signature), incomplete (a checkpoint or a bundle with no end, and no
                 fail signature), user_stop (stopped cleanly before in_game), degraded, ok
Python 3 standard library only.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import re
import statistics
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import signatures as sigs  # noqa: E402

STAGES = sigs.STAGES
TEXT_EXT = (".txt", ".log", ".csv", ".jsonl", ".json")
SESSION_DIR = re.compile(r"^\d{4}-\d{2}-\d{2}-\d{2,}-[A-Za-z0-9-]+$")
RUN_NAME = re.compile(r"(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2})Z")
STEP = re.compile(r"== STEP (\d{2}):(\d{2}):(\d{2})\.(\d{3}) (.*)$")
TIMELINE = re.compile(r"DROIDDECK_TIMELINE event=(\S+)(.*?)\bwall_ms=(\d+)")
EXIT_LINE = re.compile(r"DROIDDECK_TIMELINE event=game_exit app_id=(\S+) status=(-?\d+) signal=(\d+) run_ms=(\d+) wall_ms=(\d+)")
PLACEMENT = re.compile(r"thread placement: (\S+) (?:pid (\d+), (\d+) threads.*?after (\d+) s|not running after (\d+) s)")
WINE_TS = re.compile(r"^(\d+\.\d+):[0-9a-f]{4}:[0-9a-f]{4}:")
FOCUS = re.compile(r"# t_ms=(-?\d+) engine=(\S+) pid=(\d+) steam=(\d)")
PAUSE_US = 1_000_000
MAX_EVIDENCE = 6
# A game's own managed assembly, by app id (compose failing on it breaks the native path).
GAME_ASSEMBLIES = {"2868840": ("sts2.dll", "SlayTheSpire2.dll")}
CRASH_SOURCE = re.compile(r"(^|/)(crash-lines\.txt|crash\.log|session\.log|steam-x64\.log|native-engine\.log)$|"
                          r"(^|/)proton/.*\.log$|(^|/)game-logs/.*\.log$")
NOISE = re.compile(r"ld\.so: object '.*gameoverlayrenderer\.so'")


def _int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _num(value):
    if value is None or value == "":
        return None
    try:
        return int(value)
    except ValueError:
        try:
            return float(value)
        except ValueError:
            return value


def _kv(text: str) -> dict:
    out = {}
    for line in (text or "").splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            out.setdefault(key.strip(), value.strip())
    return out


def _clip(line: str, n: int = 300) -> str:
    line = line.strip()
    return line if len(line) <= n else line[: n - 3] + "..."


def read_bundle(path: str):
    """(files {relative path: text}, error or None). A share bundle's <session>/ folder is stripped."""
    files, error = {}, None
    try:
        with zipfile.ZipFile(path) as archive:
            infos = [i for i in archive.infolist() if not i.is_dir()]
            tops = {i.filename.split("/", 1)[0] for i in infos if "/" in i.filename}
            strip = None
            if len(tops) == 1 and all("/" in i.filename for i in infos):
                top = next(iter(tops))
                if SESSION_DIR.match(top):
                    strip = top + "/"
            for info in infos:
                name = info.filename[len(strip):] if strip and info.filename.startswith(strip) else info.filename
                if not name.lower().endswith(TEXT_EXT):
                    continue
                try:
                    files[name] = archive.read(info).decode("utf-8", errors="replace")
                except (OSError, zipfile.BadZipFile, EOFError, ValueError) as exc:  # truncated member
                    error = error or f"{name}: {exc}"
    except (OSError, zipfile.BadZipFile, EOFError, ValueError) as exc:
        error = str(exc) or exc.__class__.__name__
    return files, error


class Run:
    """Everything triage and the signature matchers read from one bundle."""

    def __init__(self, path: str, files: dict, read_error=None):
        self.path = str(path)
        self.files = files
        self.read_error = read_error
        self.upload = _kv(self.file_text("upload.txt"))
        self.build = _kv(self.file_text("build.txt"))
        self.settings = self.file_text("settings.txt")
        self.events = self._events()
        self.log = self.file_text("session.log").splitlines()
        self.has_session_log = "session.log" in self._basenames()
        self.crash_lines = self.file_text("crash-lines.txt").splitlines()
        self.native_log = self.file_text("native-engine.log").splitlines()
        try:
            self.native_launch = json.loads(self.file_text("native-launch.json") or "{}")
        except ValueError:
            self.native_launch = {}
        self._frames_text = self.file_text("frames.csv")
        self.frames_t0_wall = None
        m = re.search(r"t0_wall_ms=(\d+)", self._frames_text[:400])
        if m:
            self.frames_t0_wall = int(m.group(1))
        self.clocks_start = None
        clocks = self.file_text("clocks.log")
        m = re.search(r"^start: wall_ms=(\d+)", clocks, re.M)
        if m:
            self.clocks_start = int(m.group(1))
        self.thermal = [int(v) for v in re.findall(r"\| thermal (-?\d+)", clocks)]
        self.memory = self._csv(self.file_text("memory.csv"))
        self.smaps = self._csv(self.file_text("smaps.csv"), numeric=("size_mb", "rss_mb", "swap_mb"))
        self.t0 = self._t0()
        self._parse_log()
        self.wine_logs = {rel: text.splitlines() for rel, text in self.files.items()
                          if re.search(r"(^|/)proton/[^/]+/steam-[^/]*\.log$", rel)}
        self._stamps = {}
        self.experiments = self._experiments()
        self.kind = self._kind()
        self.end_ms = self._end()
        self._parse_frames()
        self.stage = self._stage()
        self.metrics = {}

    # -------------------------------------------------------------- files
    def _basenames(self):
        return {rel.rsplit("/", 1)[-1] for rel in self.files}

    def file_text(self, name: str) -> str:
        if name in self.files:
            return self.files[name]
        for rel in sorted(self.files, key=len):
            if rel.rsplit("/", 1)[-1] == name:
                return self.files[rel]
        return ""

    @staticmethod
    def _csv(text: str, numeric=None):
        lines = text.splitlines()
        if not lines:
            return []
        header = [h.strip() for h in lines[0].split(",")]
        rows = []
        for line in lines[1:]:
            values = line.split(",")
            if len(values) != len(header):
                continue
            row = {}
            for k, v in zip(header, values):
                row[k] = _num(v.strip()) if numeric is None or k in numeric or k == "t_wall_ms" else v
            if isinstance(row.get("t_wall_ms"), int):
                rows.append(row)
        return rows

    def grep_text_files(self, pattern, limit=6, where=None):
        rx = re.compile(pattern)
        out = []
        for rel in sorted(self.files):
            if where and not where.search(rel):
                continue
            for line in self.files[rel].splitlines():
                if rx.search(line) and not NOISE.search(line):
                    clipped = _clip(line)
                    if clipped not in out:
                        out.append(clipped)
                    if len(out) >= limit:
                        return out
        return out

    def grep_crash_sources(self, pattern, limit=MAX_EVIDENCE):
        return self.grep_text_files(pattern, limit, CRASH_SOURCE)

    def wine_stamps(self, rel):
        if rel not in self._stamps:
            stamps = []
            for idx, line in enumerate(self.wine_logs.get(rel, [])):
                m = WINE_TS.match(line)
                if m:
                    stamps.append((float(m.group(1)), idx))
            self._stamps[rel] = stamps
        return self._stamps[rel]

    def wine_grep(self, pattern, limit=6):
        rx = re.compile(pattern)
        out = []
        for rel in sorted(self.wine_logs):
            for line in self.wine_logs[rel]:
                if rx.search(line):
                    clipped = _clip(line)
                    if clipped not in out:
                        out.append(clipped)
                    if len(out) >= limit:
                        return out
        return out

    # -------------------------------------------------------------- events and timeline
    def _events(self):
        out = []
        for line in self.file_text("events.jsonl").splitlines():
            try:
                e = json.loads(line)
            except ValueError:
                continue  # truncated last line of a checkpoint
            if isinstance(e, dict) and isinstance(e.get("t"), (int, float)) and e.get("event"):
                out.append(e)
        return sorted(out, key=lambda e: e["t"])

    def events_named(self, name):
        return [e for e in self.events if e["event"] == name]

    def _live(self, name):
        """Events of this session: not a before-session mark, not stale (written with an old time)."""
        return [e for e in self.events_named(name)
                if not e.get("before_session") and e["t"] >= self.t0 - 2000]

    def _first_live(self, *names):
        hits = [e for name in names for e in self._live(name)]
        return min(hits, key=lambda e: e["t"]) if hits else None

    def _t0(self):
        created = [e["t"] for e in self.events_named("session.created")]
        if created:
            return created[0]
        for candidate in (self.frames_t0_wall, self.clocks_start):
            if candidate:
                return candidate
        if self.memory:
            return self.memory[0]["t_wall_ms"]
        live = [e["t"] for e in self.events if not e.get("before_session")
                and e["event"] in ("session.logs_ready", "service.started", "compositor.starting", "guest.starting")]
        if live:
            return min(live)
        return self.events[0]["t"] if self.events else 0

    def _step_wall(self, h, mi, s, ms):
        day = _dt.datetime.fromtimestamp(self.t0 / 1000, _dt.timezone.utc).replace(hour=0, minute=0, second=0,
                                                                                  microsecond=0)
        t = int(day.timestamp() * 1000) + ((h * 60 + mi) * 60 + s) * 1000 + ms
        if t < self.t0 - 3_600_000:
            t += 86_400_000
        return t

    def _parse_log(self):
        self.steps = []          # (wall_ms, text)
        self.timeline = []       # (event, wall_ms, line)
        self.interface_up_ms = None
        self.interface_up_idx = None
        self.launch_line_idx = None
        self.clean_exit_asked = False
        self.thread_placement = []
        self.game_exits = []
        self.native_lines = []
        self.fex_cache_mb = None
        self.swap_free_marks = []  # (wall_ms, free_mb)
        for idx, line in enumerate(self.log):
            m = STEP.search(line)
            if m and self.t0:
                wall = self._step_wall(*(int(m.group(i)) for i in range(1, 5)))
                self.steps.append((wall, m.group(5)))
                f = re.search(r"swap free (\d+) MB|\((\d+) MB free\)", m.group(5))
                if f:
                    self.swap_free_marks.append((wall, int(f.group(1) or f.group(2))))
            m = TIMELINE.search(line)
            if m:
                self.timeline.append((m.group(1), int(m.group(3)), line))
            m = EXIT_LINE.search(line)
            if m:
                self.game_exits.append({"app_id": m.group(1), "status": int(m.group(2)), "signal": int(m.group(3)),
                                        "run_ms": int(m.group(4)), "wall": int(m.group(5)), "line": _clip(line)})
            m = re.search(r"== steam: interface up after (\d+) ms", line)
            if m and self.interface_up_ms is None:
                self.interface_up_ms, self.interface_up_idx = int(m.group(1)), idx
            if self.launch_line_idx is None and re.search(r"== steam: launching requested game", line):
                self.launch_line_idx = idx
            if "== steam: the app asked for a clean exit" in line:
                self.clean_exit_asked = True
            m = PLACEMENT.search(line)
            if m:
                self.thread_placement.append({"exe": m.group(1), "pid": _int(m.group(2)), "threads": _int(m.group(3)),
                                              "after_s": _int(m.group(4) or m.group(5)), "line": _clip(line)})
            if re.search(r"native-engine\b", line) and "droiddeck-game-env" in line and "experiments:" not in line:
                self.native_lines.append(_clip(line))
            m = re.search(r"FEX disk cache on for app \S+ \((?:[^,]*, )?(?:\d+ files, ([\d.]+) MB|(no cache yet))", line)
            if m and self.fex_cache_mb is None:
                self.fex_cache_mb = float(m.group(1)) if m.group(1) else 0.0
        if not self.thread_placement:
            for line in self.crash_lines:
                m = PLACEMENT.search(line)
                if m:
                    self.thread_placement.append({"exe": m.group(1), "pid": _int(m.group(2)), "line": _clip(line)})
        # game_exit lines also live in crash-lines.txt when session.log was dropped
        known = {g["wall"] for g in self.game_exits}
        for line in self.crash_lines:
            m = EXIT_LINE.search(line)
            if m and int(m.group(5)) not in known:
                self.game_exits.append({"app_id": m.group(1), "status": int(m.group(2)), "signal": int(m.group(3)),
                                        "run_ms": int(m.group(4)), "wall": int(m.group(5)), "line": _clip(line)})
        exec_marks = [w for name, w, _ in self.timeline if name == "game_exec"]
        exec_marks += [int(m.group(1)) for line in self.crash_lines
                       for m in [re.search(r"event=game_exec .*wall_ms=(\d+)", line)] if m]
        if not exec_marks:
            exec_marks = [e["t"] for e in self._live("memory.freed") if e.get("reason") == "game_exec"]
        if not exec_marks:
            exec_marks = [e["t"] for e in self._live("pacing.cap") if e.get("why") == "game started"]
        self.game_exec_wall = min(exec_marks) if exec_marks else None
        launch = launch_fx = None
        for line in self.file_text("launch.txt").splitlines():
            if line.startswith("fex:") and launch_fx is None:
                launch_fx = line.strip()
            if line.startswith("exe:") and launch is None:
                launch = line.split(":", 1)[1].strip()
        self.fex_build_line = launch_fx or ""
        self.game_exe = launch or ""
        starting = self._first_live("steam.starting", "guest.started")
        steam_step = next((w for w, text in self.steps if text.lower().startswith("starting the steam client")), None)
        self.steam_started_wall = starting["t"] if starting else steam_step
        self._steam_step_wall = steam_step
        self.steam_download_seen = any(e.get("active") for e in self._live("steam.client_download"))

    def game_assembly_names(self):
        names = set(GAME_ASSEMBLIES.get(self.app_id() or "", ()))
        if self.game_exe:
            stem = self.game_exe.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0]
            names.add(stem + ".dll")
        if isinstance(self.native_launch, dict):
            for key in ("assembly", "main_assembly", "game_assembly"):
                if self.native_launch.get(key):
                    names.add(str(self.native_launch[key]).rsplit("/", 1)[-1])
        return names

    def app_id(self):
        for _, _, line in self.timeline:
            m = re.search(r"app_id=(\d+)", line)
            if m:
                return m.group(1)
        m = re.search(r"^app: (\d+)", self.file_text("launch.txt"), re.M)
        return m.group(1) if m else None

    def steam_ui_wall(self):
        e = self._first_live("steam.ui_first_frame") or self._first_live("frame.first")
        if e:
            return e["t"]
        if self.interface_up_ms is not None:
            base = self._steam_step_wall or self.steam_started_wall
            if base:
                return base + self.interface_up_ms
        return None

    def steam_ui_s(self):
        e = self._first_live("steam.ui_first_frame") or self._first_live("frame.first")
        if e:
            g = self._first_live("guest.started")
            base = g["t"] if g and g["t"] <= e["t"] else self.t0
            return round((e["t"] - base) / 1000, 1)
        if self.interface_up_ms is not None:
            return round(self.interface_up_ms / 1000, 1)
        return None

    @property
    def launch_requested_wall(self):
        e = self._first_live("steam.game_launch_requested")
        if e:
            return e["t"]
        if self.launch_line_idx is not None:
            return self.steam_ui_wall() or self.steam_started_wall or self.t0
        return None

    def login1_pauses(self, before_ui=False):
        lines = self.log
        if before_ui:
            if self.interface_up_idx is None:
                return sum("login1: pausing the session" in line for line in lines) if self.steam_ui_s() is None else 0
            lines = lines[: self.interface_up_idx]
        return sum("login1: pausing the session" in line for line in lines)

    def suspended_spans(self):
        spans, start = [], None
        for e in self.events:
            if e.get("before_session") or e["t"] < self.t0 - 2000:
                continue
            if e["event"] == "session.suspended" and start is None:
                start = e["t"]
            elif e["event"] in ("session.resumed", "session.resume_requested") and start is not None:
                spans.append((start, e["t"]))
                start = None
        if start is not None:
            spans.append((start, self.end_ms))
        return spans

    def _experiments(self):
        raw = self.build.get("experiments_at_session_start")
        if not raw:
            e = next((e for e in self.events_named("session.experiments")), None)
            raw = e.get("on", "") if e else ""
        return [x for x in (s.strip() for s in raw.split(",")) if x]

    def _kind(self):
        kind = self.upload.get("kind", "final")
        if kind != "final":
            return "checkpoint"
        m = re.search(r"^autotest: id=(\S+)", self.settings, re.M)
        if self.events_named("autotest.start") or (m and m.group(1) not in ("null", "-", "")):
            return "autotest"
        return "session"

    def _end(self):
        stops = [e["t"] for e in self.events
                 if e["event"] in ("session.stopping", "session.stopped", "guest.exited")
                 and not e.get("before_session") and e["t"] >= self.t0 - 2000]
        if stops:
            return min(stops)
        times = [e["t"] for e in self.events if not e.get("before_session") and e["t"] >= self.t0 - 2000]
        if self.memory:
            times.append(self.memory[-1]["t_wall_ms"])
        times += [w for w, _ in self.steps] + [w for _, w, _ in self.timeline]
        return max(times) if times else self.t0

    @property
    def stopped(self):
        return any(e["t"] >= self.t0 - 2000 for name in ("session.stopping", "session.stopped", "guest.exited")
                   for e in self.events_named(name))

    @property
    def stopped_cleanly(self):
        if self.clean_exit_asked or self._live("session.stop_requested") or self._live("autotest.stop") \
                or self._live("session.idle_stop"):
            return True
        return any(e.get("status") in (0, None) for name in ("session.stopping", "guest.exited")
                   for e in self._live(name))

    # -------------------------------------------------------------- frames
    def _parse_frames(self):
        markers, rows = [], []
        for line in self._frames_text.splitlines():
            if line.startswith("#"):
                m = FOCUS.match(line)
                if m:
                    markers.append((int(m.group(1)), int(m.group(3)), m.group(4) == "1"))
                continue
            parts = line.split(",")
            if len(parts) == 2:
                try:
                    rows.append((int(parts[0]), int(parts[1])))
                except ValueError:
                    continue
        rows.sort()
        markers.sort()
        game = []
        if markers:
            steam_pid, steam_seen, focus, i = None, False, False, 0
            for t, ft in rows:
                while i < len(markers) and markers[i][0] <= t:
                    mt, pid, steam = markers[i]
                    if steam:
                        steam_seen = True
                        steam_pid = pid if steam_pid is None else steam_pid
                    focus = (not steam) and pid != 0 and steam_seen and pid != steam_pid
                    i += 1
                if focus:
                    game.append((t, ft))
        elif rows and self.game_exec_wall is not None:
            game = rows  # no focus lines (cut from the front): every frame is the game's
        base = self.frames_t0_wall
        self.game_rows = game
        self.frames_total = len(rows)
        self.game_first_frame_wall = None
        if game:
            if base is not None:
                self.game_first_frame_wall = base + game[0][0]
            else:
                self.game_first_frame_wall = self.game_exec_wall or self.t0
        ev = next((e for e in self._live("game.first_frame") if e.get("engine") not in (None, "GAMESCOPE")), None)
        if ev and (self.game_first_frame_wall is None or ev["t"] < self.game_first_frame_wall):
            self.game_first_frame_wall = ev["t"]
        live = [ft for _, ft in game if 0 < ft < PAUSE_US]
        self.game_live_us = live
        self.game_seconds = (game[-1][0] - game[0][0]) / 1000 if len(game) > 1 else 0.0
        spans = self.suspended_spans()
        self.freezes = []
        if base is not None:
            for t, ft in game:
                if ft >= sigs.FREEZE_MS * 1000:
                    wall = base + t
                    if not any(a - 2000 <= wall <= b + 5000 for a, b in spans):
                        self.freezes.append((wall, ft / 1000))

    def _settled_rows(self, warmup_s):
        if not self.game_rows:
            return []
        start = self.game_rows[0][0] + warmup_s * 1000
        return [(t, ft) for t, ft in self.game_rows if t >= start]

    def fps_settled(self, warmup_s):
        """Median fps of game frames after the game's first warmup_s seconds."""
        live = [ft for _, ft in self._settled_rows(warmup_s) if 0 < ft < PAUSE_US]
        return 1e6 / statistics.median(live) if len(live) >= 2 else None

    def over50_by_minute(self, warmup_s):
        rows = self._settled_rows(warmup_s)
        if not rows:
            return []
        start, counts = rows[0][0], {}
        for t, ft in rows:
            if 50_000 < ft < PAUSE_US:
                counts[(t - start) // 60_000] = counts.get((t - start) // 60_000, 0) + 1
        last = (rows[-1][0] - start) // 60_000
        return [counts.get(i, 0) for i in range(int(last) + 1)]

    def fps_by_cap(self):
        """[(cap, median fps, seconds)] of game frames grouped by the frame cap in force."""
        if self.frames_t0_wall is None:
            return []
        changes = []
        for e in self.events:
            if e["event"] == "pacing.cap" and e["t"] >= self.t0 - 2000:
                changes.append((e["t"], "cap", _int(e.get("cap")) or 0))
            elif e["event"] == "pacing.pace" and e["t"] >= self.t0 - 2000:
                changes.append((e["t"], "pace", _int(e.get("fps")) or 0))
        if not changes:
            return []
        changes.sort()
        groups, state, i = {}, {"cap": 0, "pace": 0}, 0
        for t, ft in self.game_rows:
            wall = self.frames_t0_wall + t
            while i < len(changes) and changes[i][0] <= wall:
                state[changes[i][1]] = changes[i][2]
                i += 1
            if not 0 < ft < PAUSE_US:
                continue
            caps = [c for c in (state["cap"], state["pace"] if state["cap"] else 0) if c]
            groups.setdefault(min(caps) if caps else 0, []).append(ft)
        out = []
        for cap, fts in sorted(groups.items()):
            if len(fts) < 2:
                continue
            out.append((cap, 1e6 / statistics.median(fts), sum(fts) / 1e6))
        return out

    # -------------------------------------------------------------- stage and metrics
    def _stage(self):
        stage = "app"
        if any(self._live(n) for n in ("session.created", "service.started", "compositor.starting", "guest.starting",
                                       "session.logs_ready", "session.failed")) or self.log:
            stage = "setup"
        if self.steam_ui_s() is not None:
            stage = "steam_ui"
        if self._live("steam.signed_in"):
            stage = "steam_signed_in"
        if self.launch_requested_wall is not None:
            stage = "launch_requested"
        if self.game_exec_wall is not None or self.native_lines:
            stage = "game_exec"
        if self.game_first_frame_wall is not None:
            stage = "game_first_frame"
        if self.game_seconds >= sigs.MIN_GAME_S:
            stage = "in_game"
        return stage

    def stage_at_least(self, stage):
        return STAGES.index(self.stage) >= STAGES.index(stage)

    @property
    def swap_total_mb(self):
        totals = [e.get("swapTotalMb") for e in self.events_named("memory.lmk_risk") if e.get("swapTotalMb")]
        if totals:
            return max(totals)
        estimates = []
        for wall, free in self.swap_free_marks:
            row = self._memory_at(wall)
            if row and isinstance(row.get("swap_used_mb"), int):
                estimates.append(row["swap_used_mb"] + free)
        if not estimates:
            return None
        used = [r["swap_used_mb"] for r in self.memory if isinstance(r.get("swap_used_mb"), int)]
        return max(int(statistics.median(estimates)), max(used, default=0))

    def _memory_at(self, wall):
        best = None
        for row in self.memory:
            if best is None or abs(row["t_wall_ms"] - wall) < abs(best["t_wall_ms"] - wall):
                best = row
        if best and abs(best["t_wall_ms"] - wall) <= 15_000:
            return best
        return None

    @property
    def swap_at_launch_mb(self):
        if self.game_exec_wall is None:
            return None
        row = next((r for r in self.memory if r["t_wall_ms"] >= self.game_exec_wall - 5000), None)
        if row and row.get("swap_used_mb") is not None and row["t_wall_ms"] <= self.game_exec_wall + 15_000:
            return row["swap_used_mb"]
        for e in self._live("memory.freed"):
            if e.get("reason") == "game_exec" and e.get("swapUsedBeforeMb") is not None:
                return e["swapUsedBeforeMb"]
        return None

    def compute_metrics(self):
        m = dict.fromkeys(("steam_ui_s", "game_first_frame_s", "fps_p50", "frametime_p99_ms", "stutters_per_min",
                           "thermal_max", "zram_used_pct_at_launch", "run_s"))
        m["steam_ui_s"] = self.steam_ui_s()
        base = self.game_exec_wall or self.launch_requested_wall
        if self.game_first_frame_wall is not None and base is not None:
            m["game_first_frame_s"] = round((self.game_first_frame_wall - base) / 1000, 1)
        live = self.game_live_us
        if len(live) >= 2:
            ordered = sorted(live)
            median = statistics.median(ordered)
            p99 = ordered[min(len(ordered) - 1, round(0.99 * (len(ordered) - 1)))]
            minutes = sum(live) / 60e6
            m["fps_p50"] = round(1e6 / median, 1)
            m["frametime_p99_ms"] = round(p99 / 1000, 1)
            m["stutters_per_min"] = round(sum(1 for ft in live if ft > 2 * median) / minutes, 1) if minutes else None
        if self.thermal:
            m["thermal_max"] = max(self.thermal)
        used, total = self.swap_at_launch_mb, self.swap_total_mb
        if used is not None and total:
            m["zram_used_pct_at_launch"] = min(100.0, round(100 * used / total, 1))
        m["run_s"] = round(max(0, self.end_ms - self.t0) / 1000, 1)
        self.metrics = m
        return m


def _run_rel(path: str) -> str:
    parts = Path(path).resolve().parts
    if "runs" in parts:
        idx = len(parts) - 1 - parts[::-1].index("runs")
        return "/".join(parts[idx:])
    return Path(path).name


def _started_utc(path: str, run: Run):
    m = RUN_NAME.search(Path(path).name)
    if m:
        return f"{m.group(1)}T{m.group(2)}:{m.group(3)}:{m.group(4)}Z"
    if run.t0:
        return _dt.datetime.fromtimestamp(run.t0 / 1000, _dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return None


def _identity(path: str, run: Run):
    rel = _run_rel(path)
    parts = rel.split("/")
    slug = run.build.get("build_slug") or (parts[1] if len(parts) >= 4 and parts[0] == "runs" else None)
    commit = run.build.get("commit")
    if not commit and slug and "-" in slug:
        tail = slug.rsplit("-", 1)[1]
        commit = tail if re.fullmatch(r"[0-9a-f]{7,40}", tail) else None
    branch = run.build.get("branch")
    if not branch:
        m = re.search(r"· (\S+) at [0-9a-f]+", run.build.get("build_label", ""))
        branch = m.group(1) if m else None
    device = parts[2] if len(parts) >= 4 and parts[0] == "runs" else None
    if not device and run.build.get("device"):
        device = run.build["device"].split(" (")[0].replace(" ", "-")
    return rel, slug, commit, branch, device


def match_signatures(run: Run):
    out = []
    if run.read_error:
        out.append({"id": "bundle-unreadable", "severity": "info", "owner": "main",
                    "evidence": [_clip(f"zip could not be read whole: {run.read_error}")]})
    for sig in sigs.CATALOG:
        if not run.stage_at_least(sig["stage"]):
            continue
        try:
            result = sig["match"](run)
        except Exception as exc:  # one broken matcher must not lose the verdict
            out.append({"id": "triage-matcher-error", "severity": "info", "owner": "main",
                        "evidence": [f"{sig['id']}: {exc.__class__.__name__}: {exc}"]})
            continue
        if not result:
            continue
        severity = sig["severity"]
        if isinstance(result, tuple):
            result, severity = result
        evidence = [_clip(str(x)) for x in result if str(x).strip()][:MAX_EVIDENCE]
        out.append({"id": sig["id"], "severity": severity, "evidence": evidence, "owner": sig["owner"]})
    order = {"fail": 0, "degrade": 1, "info": 2}
    out.sort(key=lambda s: order.get(s["severity"], 3))
    return out


def _outcome(run: Run, found):
    severities = {s["severity"] for s in found}
    if "fail" in severities:
        return "fail"
    if run.kind == "checkpoint" or not run.stopped:
        return "incomplete"
    if not run.stage_at_least("in_game"):
        return "user_stop" if run.stopped_cleanly else "incomplete"
    if "degrade" in severities:
        return "degraded"
    return "ok"


def triage_zip(path: str) -> dict:
    files, error = read_bundle(path)
    run = Run(path, files, error)
    run.compute_metrics()
    found = match_signatures(run)
    rel, slug, commit, branch, device = _identity(path, run)
    return {
        "run": rel, "slug": slug, "commit": commit, "branch": branch, "device": device,
        "started_utc": _started_utc(path, run), "kind": run.kind, "experiments": run.experiments,
        "stage": run.stage, "outcome": _outcome(run, found), "signatures": found, "metrics": run.metrics,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("zips", nargs="*", help="telemetry zips")
    parser.add_argument("--root", help="a kestrel-telemetry clone: triage every zip under <root>/runs/")
    parser.add_argument("--jsonl", action="store_true", help="one JSON object per line")
    args = parser.parse_args(argv)
    paths = list(args.zips)
    if args.root:
        paths += [str(p) for p in sorted((Path(args.root) / "runs").rglob("*.zip"))]
    if not paths:
        parser.error("give zips or --root")
    jsonl = args.jsonl or bool(args.root)
    status = 0
    for path in paths:
        try:
            verdict = triage_zip(path)
        except Exception as exc:  # report and go on with the other zips
            print(f"triage: {path}: {exc.__class__.__name__}: {exc}", file=sys.stderr)
            status = 1
            continue
        print(json.dumps(verdict) if jsonl else json.dumps(verdict, indent=2))
    return status


if __name__ == "__main__":
    sys.exit(main())
