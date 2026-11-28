"""Pure parts of the standard bench: frame statistics, window selection, log parsers, screenshot
features on raw RGBA buffers and the summary schema. No adb here, so tools/tests covers it in CI."""
from __future__ import annotations

import datetime
import json
import math
import random
import re
import struct
import time
import zlib

SCHEMA_VERSION = 1


# ---------------------------------------------------------------- frames

def parse_frames(text: str) -> list[tuple[int, float]]:
    """frames.csv -> [(wall ms at the end of the frame, frame time ms)], as fp.sh reads it."""
    t0 = None
    frames: list[tuple[int, float]] = []
    for line in text.splitlines():
        if line.startswith("#"):
            found = re.search(r"t0_wall_ms=(\d+)", line)
            if found:
                t0 = int(found.group(1))
            continue
        parts = line.strip().split(",")
        if len(parts) < 2 or not parts[0].isdigit() or t0 is None:
            continue
        try:
            frames.append((t0 + int(parts[0]), int(parts[1]) / 1000.0))
        except ValueError:
            continue
    return frames


def percentile(ordered: list[float], share: float) -> float:
    return ordered[min(len(ordered) - 1, int(share * len(ordered)))]


def frame_stats(frame_times_ms: list[float], span_ms: float) -> dict:
    """span_ms is the wall time the frames were taken from, so fps counts frames that never came."""
    if not frame_times_ms or span_ms <= 0:
        return {"n": 0, "span_s": round(max(span_ms, 0) / 1000.0, 2), "fps": 0.0, "p50": None, "p95": None,
                "p99": None, "max": None, "over33_per_min": None, "over50_per_min": None}
    ordered = sorted(frame_times_ms)
    minutes = span_ms / 60000.0
    return {
        "n": len(ordered),
        "span_s": round(span_ms / 1000.0, 2),
        "fps": round(len(ordered) / (span_ms / 1000.0), 1),
        "p50": round(percentile(ordered, 0.50), 1),
        "p95": round(percentile(ordered, 0.95), 1),
        "p99": round(percentile(ordered, 0.99), 1),
        "max": round(ordered[-1], 1),
        "over33_per_min": round(sum(1 for value in ordered if value > 33.0) / minutes, 1),
        "over50_per_min": round(sum(1 for value in ordered if value > 50.0) / minutes, 1),
    }


def merge_windows(windows: list[tuple[int, int]]) -> list[tuple[int, int]]:
    merged: list[list[int]] = []
    for start, end in sorted(window for window in windows if window[1] > window[0]):
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return [(start, end) for start, end in merged]


def select_frames(frames: list[tuple[int, float]], windows: list[tuple[int, int]]) -> tuple[list[float], int]:
    """Frame times whose end falls in (start, end] of any window, and the windows' total length in ms."""
    merged = merge_windows(windows)
    picked = [value for at, value in frames if any(start < at <= end for start, end in merged)]
    return picked, sum(end - start for start, end in merged)


def window_stats(frames: list[tuple[int, float]], windows: list[tuple[int, int]]) -> dict:
    picked, span = select_frames(frames, windows)
    return frame_stats(picked, span)


INPUT_LEAD_MS = 100


def step_window(step: dict) -> tuple[int, int] | None:
    """The animation window of one scripted step: from INPUT_LEAD_MS before its first press was sent (the
    broadcast that carries it takes about that long) until the settle time after its last press."""
    if step.get("settle_ms") in (None, 0) or step.get("t_done_ms") is None:
        return None
    if step.get("t_first_sent_ms") is not None:
        start = int(step["t_first_sent_ms"])
    else:
        start = int(step.get("t_first_done_ms", step["t_done_ms"])) - INPUT_LEAD_MS
    return start, int(step["t_done_ms"]) + int(step["settle_ms"])


def animation_windows(steps: list[dict], kinds: tuple[str, ...] | None = None) -> list[tuple[int, int]]:
    windows = []
    for step in steps:
        if kinds is not None and step.get("kind") not in kinds:
            continue
        window = step_window(step)
        if window:
            windows.append(window)
    return windows


# ---------------------------------------------------------------- memory, cpu, logs

def parse_meminfo(text: str) -> dict[str, float]:
    """/proc/meminfo -> MB per key."""
    return {key: int(value) / 1024.0 for key, value in re.findall(r"^([\w()]+):\s+(\d+) kB", text, re.M)}


def parse_gpumem(text: str) -> tuple[float | None, dict[str, float]]:
    """`dumpsys gpu --gpumem` -> (global MB, {pid: MB})."""
    total = re.search(r"Global total: (\d+)", text)
    owners = {pid: int(size) / 1048576.0 for pid, size in re.findall(r"Proc (\d+) total: (\d+)", text)}
    return (int(total.group(1)) / 1048576.0 if total else None), owners


def parse_sampler(text: str) -> list[dict]:
    """The 10 s sampler stream: blocks starting '@ <epoch s>' with meminfo lines, gpumem lines and
    'P <pid> <name>' lines naming the gpumem owners."""
    samples = []
    for block in text.split("@ ")[1:]:
        lines = block.splitlines()
        try:
            sample: dict = {"t_ms": int(float(lines[0].strip()) * 1000)}
        except (ValueError, IndexError):
            continue
        body = "\n".join(lines[1:])
        sample["meminfo_mb"] = {key: round(value, 1) for key, value in parse_meminfo(body).items()}
        total, owners = parse_gpumem(body)
        names = dict(re.findall(r"^P (\d+) (\S+)", body, re.M))
        sample["gpu_global_mb"] = round(total, 1) if total is not None else None
        by_name: dict[str, float] = {}
        for pid, size in owners.items():
            name = names.get(pid, "pid" + pid)
            by_name[name] = round(by_name.get(name, 0.0) + size, 1)
        sample["gpu_owner_mb"] = by_name
        samples.append(sample)
    return samples


def mean(values: list[float]) -> float | None:
    values = [value for value in values if value is not None]
    return round(sum(values) / len(values), 1) if values else None


def sampler_summary(samples: list[dict], start_ms: int, end_ms: int) -> dict:
    inside = [sample for sample in samples if start_ms <= sample["t_ms"] <= end_ms]
    out: dict = {"samples": len(inside)}
    for key in ("MemAvailable", "MemFree", "SwapFree", "Gpu", "ION_heap", "Zram", "AnonPages", "Shmem"):
        values = [sample["meminfo_mb"].get(key) for sample in inside if key in sample["meminfo_mb"]]
        out[key + "_mb"] = round(mean(values)) if values else None
    available = [sample["meminfo_mb"]["MemAvailable"] for sample in inside if "MemAvailable" in sample["meminfo_mb"]]
    out["MemAvailable_min_mb"] = round(min(available)) if available else None
    out["gpu_global_mb"] = mean([sample.get("gpu_global_mb") for sample in inside])
    owners: dict[str, list[float]] = {}
    for sample in inside:
        for name, size in sample["gpu_owner_mb"].items():
            owners.setdefault(name, []).append(size)
    out["gpu_owner_mb"] = {name: round(sum(sizes) / len(inside)) for name, sizes in
                           sorted(owners.items(), key=lambda item: -sum(item[1])) if sum(sizes) / len(inside) >= 10}
    return out


def parse_csv(text: str) -> list[dict]:
    lines = [line for line in text.splitlines() if line and not line.startswith("#")]
    if not lines:
        return []
    header = lines[0].split(",")
    return [dict(zip(header, line.split(","))) for line in lines[1:] if line[:1].isdigit()]


def csv_cpu(rows: list[dict], start_ms: int, end_ms: int, keys: tuple[str, ...], top: int = 30) -> list[dict]:
    """Mean cpu_pct (one core = 100) per key over the samples in the span, from the session's own
    process and thread tables. A name that appears on several pids is summed per sample."""
    stamps = sorted({int(row["t_wall_ms"]) for row in rows if start_ms <= int(row["t_wall_ms"]) <= end_ms})
    if not stamps:
        return []
    totals: dict[tuple, float] = {}
    for row in rows:
        if not start_ms <= int(row["t_wall_ms"]) <= end_ms:
            continue
        try:
            value = float(row.get("cpu_pct") or 0)
        except ValueError:
            continue
        key = tuple(row.get(name, "") for name in keys)
        totals[key] = totals.get(key, 0.0) + value
    ranked = sorted(totals.items(), key=lambda item: -item[1])[:top]
    return [dict(zip(keys, key), cpu_pct=round(total / len(stamps), 1)) for key, total in ranked]


def csv_mean(rows: list[dict], start_ms: int, end_ms: int, columns: tuple[str, ...]) -> dict:
    inside = [row for row in rows if start_ms <= int(row["t_wall_ms"]) <= end_ms]
    out = {}
    for column in columns:
        values = []
        for row in inside:
            try:
                values.append(float(row[column]))
            except (KeyError, ValueError):
                continue
        out[column] = round(sum(values) / len(values), 1) if values else None
    return out


def parse_top(text: str) -> list[dict]:
    """`top -H -b -d N -n M -o PID,TID,%CPU,CMD,NAME`: mean %CPU per (process, thread) over every
    iteration but the first (toybox prints totals since boot in the first)."""
    iterations: list[dict[tuple[str, str], float]] = []
    current: dict[tuple[str, str], float] | None = None
    for line in text.splitlines():
        if re.match(r"\s*PID\s+TID", line):
            current = {}
            iterations.append(current)
            continue
        found = re.match(r"\s*(\d+)\s+(\d+)\s+([\d.]+)\s(.{15})\s(\S+)", line)
        if found and current is not None:
            key = (found.group(5), found.group(4).strip())
            current[key] = current.get(key, 0.0) + float(found.group(3))
    used = iterations[1:]
    if not used:
        return []
    totals: dict[tuple[str, str], float] = {}
    for iteration in used:
        for key, value in iteration.items():
            totals[key] = totals.get(key, 0.0) + value
    rows = [{"process": key[0], "thread": key[1], "cpu_pct": round(value / len(used), 1)} for key, value in totals.items()]
    return sorted(rows, key=lambda row: -row["cpu_pct"])


def top_by_process(rows: list[dict]) -> dict[str, float]:
    out: dict[str, float] = {}
    for row in rows:
        out[row["process"]] = round(out.get(row["process"], 0.0) + row["cpu_pct"], 1)
    return dict(sorted(out.items(), key=lambda item: -item[1]))


SPLIT = re.compile(r"frame split ([\d.]+)s (\d+) frames ([\d.]+) fps \| per frame: process peak ([\d.]+) ms, physics peak "
                   r"([\d.]+) ms, spine update ([\d.]+) ms.*?spine draw ([\d.]+) ms, main thread cpu ([\d.]+) ms \(busy (\d+)%\)")


def parse_frame_splits(log: str, last: int | None = None) -> dict | None:
    """The native engine's own 10 s counter lines (native_compat: frame split ...)."""
    rows = SPLIT.findall(log)
    if last:
        rows = rows[-last:]
    if not rows:
        return None
    column = lambda index: [float(row[index]) for row in rows]
    return {"lines": len(rows), "fps_mean": mean(column(2)), "fps_min": min(column(2)),
            "process_peak_ms_max": max(column(3)), "spine_draw_ms": mean(column(6)),
            "main_thread_cpu_ms": mean(column(7)), "main_thread_busy_pct": mean(column(8))}


def count_game_log(log: str) -> dict:
    return {"cards_played": len(re.findall(r"Player \d+ playing card (\w+)", log)),
            "cards": re.findall(r"Player \d+ playing card (\w+)", log),
            "monster_moves": re.findall(r"Monster (\w+) performing move (\w+)", log),
            "encounters": re.findall(r"encounter=(\w+)", log),
            "seeds": re.findall(r"Seed: (\w+)", log)}


def lmkd_kills(text: str, start_s: float | None = None, end_s: float | None = None) -> dict:
    """`logcat -v epoch` lines of the lowmemorykiller tag -> kills in [start, end] and the lowest adj."""
    kills = []
    for line in text.splitlines():
        if not re.search(r"lowmemorykiller.*?:\s*Kill", line):
            continue
        stamp = re.match(r"\s*(\d+\.\d+)", line)
        at = float(stamp.group(1)) if stamp else None
        if at is not None and ((start_s is not None and at < start_s) or (end_s is not None and at > end_s)):
            continue
        adj = re.search(r"oom_score_adj (\d+)", line)
        kills.append(int(adj.group(1)) if adj else None)
    known = [adj for adj in kills if adj is not None]
    return {"kills": len(kills), "lowest_adj": min(known) if known else None}


# ---------------------------------------------------------------- screenshots (raw RGBA from screencap)

class Picture:
    def __init__(self, width: int, height: int, rgba: bytes):
        self.width, self.height, self.rgba = width, height, rgba

    @classmethod
    def from_screencap(cls, data: bytes) -> "Picture":
        width, height, _format = struct.unpack_from("<III", data, 0)
        size = width * height * 4
        if len(data) < size + 12:
            raise ValueError("short screencap buffer")
        return cls(width, height, data[len(data) - size:])

    def pixel(self, x: int, y: int) -> tuple[int, int, int]:
        offset = (y * self.width + x) * 4
        return self.rgba[offset], self.rgba[offset + 1], self.rgba[offset + 2]

    def region(self, x0: int, y0: int, x1: int, y1: int, step: int = 1):
        for y in range(max(0, y0), min(self.height, y1), step):
            row = y * self.width * 4
            for x in range(max(0, x0), min(self.width, x1), step):
                offset = row + x * 4
                yield x, y, self.rgba[offset], self.rgba[offset + 1], self.rgba[offset + 2]

    def mean(self, x0: int, y0: int, x1: int, y1: int, step: int = 2) -> tuple[float, float, float]:
        total = [0, 0, 0]
        count = 0
        for _x, _y, r, g, b in self.region(x0, y0, x1, y1, step):
            total[0] += r
            total[1] += g
            total[2] += b
            count += 1
        return tuple(round(value / max(1, count), 1) for value in total)

    def share(self, x0: int, y0: int, x1: int, y1: int, test, step: int = 2) -> float:
        hits = count = 0
        for _x, _y, r, g, b in self.region(x0, y0, x1, y1, step):
            count += 1
            hits += 1 if test(r, g, b) else 0
        return hits / max(1, count)

    def png(self) -> bytes:
        stride = self.width * 4
        raw = b"".join(b"\x00" + self.rgba[y * stride:(y + 1) * stride] for y in range(self.height))

        def chunk(kind: bytes, body: bytes) -> bytes:
            return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
        header = struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 1)) + chunk(b"IEND", b"")

    def crop(self, x0: int, y0: int, x1: int, y1: int) -> "Picture":
        rows = [self.rgba[(y * self.width + x0) * 4:(y * self.width + x1) * 4] for y in range(y0, y1)]
        return Picture(x1 - x0, y1 - y0, b"".join(rows))


def is_purple(r, g, b): return r > 130 and b > 170 and g < 130 and b - g > 70
def is_teal(r, g, b): return g > 180 and b > 180 and r < 110
def is_gold(r, g, b): return r > 200 and 150 < g < 215 and b < 110
def is_bar_red(r, g, b): return r > 150 and g < 80 and b < 80


class Layout:
    """Where things are on a landscape screenshot. Points are given for the reference 2424x1080 screen
    (session output 1616x720 scaled by 1.5, the game's 16:9 picture centred in it) and scaled to others."""
    REF_W, REF_H = 2424, 1080

    def __init__(self, picture: Picture):
        self.p = picture
        self.sx = picture.width / self.REF_W
        self.sy = picture.height / self.REF_H

    def box(self, x0, y0, x1, y1):
        return int(x0 * self.sx), int(y0 * self.sy), int(x1 * self.sx), int(y1 * self.sy)

    def steam_offline_icon(self) -> int:
        """Purple pixels of the crossed-out network icon in Steam's header."""
        x0, y0, x1, y1 = self.box(1860, 15, 1980, 85)
        return sum(1 for _x, _y, r, g, b in self.p.region(x0, y0, x1, y1) if is_purple(r, g, b))

    def offline_crop(self) -> Picture:
        return self.p.crop(*self.box(1750, 0, 2424, 100))

    def dimmed(self) -> bool:
        return sum(self.p.mean(*self.box(300, 500, 340, 540))) < 90

    def early_access_prompt(self) -> bool:
        r, g, _b = self.p.mean(*self.box(1100, 840, 1150, 870))
        return self.dimmed() and r > 95 and r > 1.7 * g

    def main_menu(self) -> bool:
        r, g, b = self.p.mean(*self.box(300, 500, 340, 540))
        return abs(r - 62) < 18 and abs(g - 76) < 18 and abs(b - 91) < 18

    def profile_icon(self) -> str:
        r, g, b = self.p.mean(*self.box(280, 30, 320, 66))
        if b > 150 and r < 110:
            return "blue"
        if r > 150 and b < 110 and g < 110:
            return "red"
        if g > 130 and r < 130 and b < 110:
            return "green"
        return "other"

    def menu_focus_y(self) -> int | None:
        """Reference-y of the focused main-menu entry (its gold ornaments), or None."""
        x0, y0, x1, y1 = self.box(900, 660, 960, 990)
        rows: dict[int, int] = {}
        for _x, y, r, g, b in self.p.region(x0, y0, x1, y1):
            if is_gold(r, g, b):
                rows[y] = rows.get(y, 0) + 1
        hits = [y for y, count in rows.items() if count > 3]
        return round(sum(hits) / len(hits) / self.sy) if hits else None

    def hand_glow(self) -> float:
        """Share of the hand's band that is the teal glow of playable cards (over ~0.012 with a full hand)."""
        return round(self.p.share(*self.box(660, 860, 1800, 1080), is_teal), 4)

    def enemy_hp(self, x0=1551, x1=1831, y0=750, y1=765) -> float | None:
        """Fill of the single enemy's health bar, 0..1; None when no red bar is there (block turns it blue)."""
        bx0, by0, bx1, by1 = self.box(x0, y0, x1, y1)
        columns: dict[int, int] = {}
        for x, _y, r, g, b in self.p.region(bx0, by0, bx1, by1):
            if is_bar_red(r, g, b):
                columns[x] = columns.get(x, 0) + 1
        red = sorted(x for x, count in columns.items() if count >= 4)
        if not red or red[0] > bx0 + 12 * self.sx:
            return None
        end = red[0]
        for x in red[1:]:
            if x - end > 4:
                break
            end = x
        width = bx1 - bx0
        text_zone = bx0 + 0.36 * width
        if end >= text_zone - 6:
            end = red[-1]
        return round((end - bx0 + 1) / width, 3)


# ---------------------------------------------------------------- screen recording cross-check

def region_change(frames: list[bytes], width: int, box: tuple[int, int, int, int]) -> list[float]:
    """Mean absolute difference of a box between each gray frame and the one before it (0 for the first)."""
    x0, y0, x1, y1 = box
    out = [0.0]
    for index in range(1, len(frames)):
        total = count = 0
        before, now = frames[index - 1], frames[index]
        for y in range(y0, y1, 2):
            a = before[y * width + x0:y * width + x1:2]
            b = now[y * width + x0:y * width + x1:2]
            total += sum(abs(p - q) for p, q in zip(a, b))
            count += len(a)
        out.append(total / max(1, count))
    return out


def first_change(change: list[float], start: int, end: int, quiet: list[float], factor: float = 4.0, floor: float = 0.6) -> int | None:
    """Index of the first frame in [start, end) whose change stands out of the quiet frames' changes."""
    limit = max(floor, factor * max(quiet, default=0.0))
    for index in range(start, min(end, len(change))):
        if change[index] > limit:
            return index
    return None


def touch_to_picture(times_s: list[float], touch: list[float], answer: list[float], after_s: float, window_s: float = 1.5) -> dict | None:
    """From a screen recording of one real flick: the frame where the touch dot appears in the touch box (first
    change at or after after_s) and the first later frame where the answer box changes. Times are frame
    timestamps, so the result is good to one recorded frame at each end."""
    begin = next((index for index, at in enumerate(times_s) if at >= after_s), None)
    if begin is None or begin < 3:
        return None
    stop = next((index for index, at in enumerate(times_s) if at >= after_s + window_s), len(times_s))
    down = first_change(touch, begin, stop, touch[max(1, begin - 30):begin])
    if down is None:
        return None
    seen = first_change(answer, down + 1, stop, answer[max(1, begin - 30):begin])
    if seen is None:
        return {"touch_frame_s": round(times_s[down], 4), "answer_frame_s": None, "ms": None}
    step = times_s[seen] - times_s[seen - 1]
    return {"touch_frame_s": round(times_s[down], 4), "answer_frame_s": round(times_s[seen], 4),
            "ms": round((times_s[seen] - times_s[down]) * 1000.0, 1), "frame_ms": round(step * 1000.0, 1)}


# ---------------------------------------------------------------- summary

SUMMARY_KEYS = {
    "schema": int, "name": str, "started_utc": str, "scenario": str, "path": str, "route": str,
    "switches": list, "env": list, "build": dict, "engine": dict, "preflight": dict, "frames": dict,
    "pacing": (dict, type(None)), "latency": dict, "memory": dict, "cpu": dict, "thermal": dict,
    "lmkd": dict, "artifacts": dict, "checks": dict, "notes": list,
}
FRAME_KEYS = ("n", "span_s", "fps", "p50", "p95", "p99", "max", "over33_per_min", "over50_per_min")


def validate_summary(summary: dict) -> list[str]:
    """Problems with a summary.json, empty when it fits the documented schema."""
    problems = []
    for key, kind in SUMMARY_KEYS.items():
        if key not in summary:
            problems.append(f"missing {key}")
        elif not isinstance(summary[key], kind):
            problems.append(f"{key} is {type(summary[key]).__name__}")
    if summary.get("schema") != SCHEMA_VERSION:
        problems.append("schema version")
    if summary.get("scenario") not in ("menu", "fight"):
        problems.append("scenario")
    if summary.get("path") not in ("native", "proton"):
        problems.append("path")
    if summary.get("route") not in ("panvk", "bridge", "venus"):
        problems.append("route")
    frames = summary.get("frames") if isinstance(summary.get("frames"), dict) else {}
    for part in ("overall",) + (("animation",) if summary.get("scenario") == "fight" else ()):
        block = frames.get(part)
        if not isinstance(block, dict):
            problems.append(f"frames.{part} missing")
            continue
        problems += [f"frames.{part}.{key} missing" for key in FRAME_KEYS if key not in block]
    try:
        json.dumps(summary)
    except (TypeError, ValueError) as error:
        problems.append(f"not JSON: {error}")
    return problems


RESULTS_HEADER = ("| Date (UTC) | Build | Path | Scenario | fps | p99 ms | Fight-animation fps | Fight-animation p99 ms | "
                  "Over 33 / 50 ms per min (animation) | Press-to-screen p50/p99 | Free memory at measure | GPU memory | Note |")


def results_row(summary: dict) -> str:
    def show(value, suffix=""):
        return "n/a" if value is None else f"{value}{suffix}"
    frames = summary["frames"]
    overall = frames["overall"]
    animation = frames.get("animation") or {}
    latency = (summary["latency"].get("probe") or {}).get("toScreen") or {}
    memory = summary["memory"].get("window") or {}
    path = f"{summary['path'].capitalize()}, {summary['route']}"
    if summary["switches"] or summary["env"]:
        path += ", " + ", ".join(f"`{item}`" for item in summary["switches"] + summary["env"])
    engine = summary["engine"].get("path")
    if engine and engine != summary["path"]:
        path += f" (ran: {engine})"
    press = "not measured"
    if latency:
        press = f"{latency.get('p50Ms')} / {latency.get('p99Ms')} ms (n={latency.get('n')})"
    gpu = memory.get("Gpu_mb")
    ion = memory.get("ION_heap_mb")
    cells = [
        summary["started_utc"][:10], str(summary["build"].get("commit", "?"))[:7], path, summary["scenario"],
        show(overall.get("fps")), show(overall.get("p99")), show(animation.get("fps")), show(animation.get("p99")),
        f"{show(animation.get('over33_per_min'))} / {show(animation.get('over50_per_min'))}" if animation else "n/a",
        press, show(memory.get("MemAvailable_mb"), " MB"),
        (f"{gpu} MB" + (f" + {ion} MB dma-buf" if ion is not None else "")) if gpu is not None else "n/a",
        f"`{summary['name']}`",
    ]
    return "| " + " | ".join(cells) + " |"


# ---------------------------------------------------------------- phases (where a run's wall time goes)

class PhaseLog:
    """Wall time per named phase of a run. A phase runs from its begin() to the next begin() or end(). Sleeps
    are booked by the caller: fixed ones (a wait of a set length whatever the phone does) and poll ones (the
    pause between two looks at something the harness is waiting for)."""

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.t0 = clock()
        self.items: list[dict] = []
        self.current: dict | None = None

    def begin(self, name: str, rep: int | None = None) -> None:
        self.end()
        self.current = {"name": name, "rep": rep, "start_s": round(self.clock() - self.t0, 2), "seconds": None,
                        "fixed_sleep_s": 0.0, "poll_sleep_s": 0.0}

    def end(self) -> None:
        if self.current is not None:
            item = self.current
            item["seconds"] = round(self.clock() - self.t0 - item["start_s"], 2)
            item["fixed_sleep_s"] = round(item["fixed_sleep_s"], 2)
            item["poll_sleep_s"] = round(item["poll_sleep_s"], 2)
            self.items.append(item)
            self.current = None

    def slept(self, seconds: float, fixed: bool = True) -> None:
        if self.current is not None and seconds > 0:
            self.current["fixed_sleep_s" if fixed else "poll_sleep_s"] += seconds

    def block(self) -> dict:
        self.end()
        by_name: dict[str, dict] = {}
        for item in self.items:
            entry = by_name.setdefault(item["name"], {"count": 0, "seconds": 0.0, "fixed_sleep_s": 0.0, "poll_sleep_s": 0.0})
            entry["count"] += 1
            for key in ("seconds", "fixed_sleep_s", "poll_sleep_s"):
                entry[key] = round(entry[key] + item[key], 2)
        return {"total_s": round(self.clock() - self.t0, 2),
                "fixed_sleep_s": round(sum(item["fixed_sleep_s"] for item in self.items), 2),
                "poll_sleep_s": round(sum(item["poll_sleep_s"] for item in self.items), 2),
                "by_name": by_name, "items": self.items}


PHASE_ITEM_KEYS = ("name", "rep", "start_s", "seconds", "fixed_sleep_s", "poll_sleep_s")


def validate_phases(block) -> list[str]:
    if not isinstance(block, dict):
        return ["phases is not an object"]
    problems = [f"phases.{key} missing" for key in ("total_s", "fixed_sleep_s", "poll_sleep_s", "by_name", "items") if key not in block]
    for index, item in enumerate(block.get("items") or []):
        problems += [f"phases.items[{index}].{key} missing" for key in PHASE_ITEM_KEYS if key not in item]
        if not problems and item["fixed_sleep_s"] + item["poll_sleep_s"] > item["seconds"] + 0.5:
            problems.append(f"phases.items[{index}] slept longer than it lasted")
    return problems


# ---------------------------------------------------------------- what a switch change costs

SESSION, LAUNCH, NONE = "session", "launch", "none"
LEVEL_ORDER = {NONE: 0, LAUNCH: 1, SESSION: 2}

# When a saved experiment takes effect: "session" is read when the session starts (Experiments.session, the
# session's own env, droiddeck-session), "launch" is read by droiddeck-game-env at every game launch. A switch with
# parts at both levels is "session". None is re-read by a running game. docs/research/bench-devloop-1.md has the
# source line for each.
EXPERIMENT_LEVEL = {
    "godot-render": LAUNCH, "warmup": SESSION, "all": SESSION, "compat-quiet": SESSION, "memory": SESSION,
    "adpf-all": SESSION, "two-frames": SESSION, "layer-fence": SESSION, "sched": LAUNCH, "one-limiter": LAUNCH,
    "native-hz": LAUNCH, "quick-tap": SESSION, "fex-codegen": LAUNCH, "fex-memcpy-tso": LAUNCH,
    "fex-vector-tso": LAUNCH, "fex-tso": LAUNCH, "fex-fast": LAUNCH, "dotnet-trace": LAUNCH, "dotnet-gen0": LAUNCH,
    "dotnet-gc-stock": LAUNCH, "fex-kestrel": SESSION, "fex-stats": SESSION, "wayland-present": LAUNCH,
    "vk-count": LAUNCH, "native-inventory": LAUNCH, "free-memory": SESSION, "hybris-index": LAUNCH, "fex-l1": LAUNCH,
    "hybris-index-session": SESSION, "native-engine": LAUNCH, "dxvk-arm64ec": LAUNCH, "composite-timing": SESSION,
    "dotnet-r2r": LAUNCH, "fex-code-buffer": LAUNCH, "fex-sample": SESSION, "dotnet-r2r-generics": LAUNCH,
    "dotnet-r2r-bubble": LAUNCH, "fps-30": LAUNCH, "webhelper-trim": LAUNCH, "fex-smaps": SESSION,
    "godot-verbose": LAUNCH, "godot-render-thread": LAUNCH, "tex-astc": LAUNCH, "tex-half": LAUNCH,
    "fex-trim": LAUNCH, "fex-cache-keep": LAUNCH, "pe-align": LAUNCH, "vk-blocks-64": LAUNCH, "native-r2r": LAUNCH,
    "present-dmabuf": LAUNCH, "bridge-default": SESSION, "pad-idle-reports": SESSION, "hints-idle": SESSION,
    "zram-game-late": SESSION,
}
# The route buttons and env lines (droiddeckctl env set) are read when the session starts; a pushed file
# replaces something a game process has mapped, so it needs that process gone.
OTHER_LEVEL = {"route": SESSION, "env": SESSION, "path": LAUNCH, "push": LAUNCH}


def switch_id(tag: str) -> str:
    return tag[len("setting-exp-"):] if tag.startswith("setting-exp-") else tag


def apply_level(tag: str) -> str:
    """A switch the table does not know is treated as session-level: the expensive, always-correct answer."""
    return EXPERIMENT_LEVEL.get(switch_id(tag), SESSION)


def plan_change(current: dict | None, wanted: dict) -> tuple[str, list[str]]:
    """What must be restarted to get from the configuration that is running to the one asked for.
    Both are {"path", "route", "switches": {id: "On"|"Off"}, "env": [lines], "pushes": {guest path: sha256},
    "game_running": bool}; current also has "baseline": {id: state before the bench touched it}. A switch
    the current run set and the wanted one does not mention goes back to its baseline."""
    if current is None:
        return SESSION, ["no session of this mode's own is running"]
    reasons: list[tuple[str, str]] = []
    if current.get("route") != wanted.get("route"):
        reasons.append((OTHER_LEVEL["route"], f"route {current.get('route')} -> {wanted.get('route')}"))
    if sorted(current.get("env") or []) != sorted(wanted.get("env") or []):
        reasons.append((OTHER_LEVEL["env"], "env lines differ"))
    if current.get("path") != wanted.get("path"):
        reasons.append((OTHER_LEVEL["path"], f"path {current.get('path')} -> {wanted.get('path')}"))
    have = {switch_id(tag): state for tag, state in (current.get("switches") or {}).items()}
    baseline = {switch_id(tag): state for tag, state in (current.get("baseline") or {}).items()}
    want = {switch_id(tag): state for tag, state in (wanted.get("switches") or {}).items()}
    for tag in sorted(set(have) | set(want)):
        target = want.get(tag, baseline.get(tag, have.get(tag)))
        now = have.get(tag, baseline.get(tag))
        if tag in want and tag not in have and tag not in baseline:
            reasons.append((apply_level(tag), f"{tag} -> {target} (state unknown)"))
        elif target != now:
            reasons.append((apply_level(tag), f"{tag} {now} -> {target}"))
    if (current.get("pushes") or {}) != (wanted.get("pushes") or {}):
        reasons.append((OTHER_LEVEL["push"], "pushed files differ"))
    if not current.get("game_running"):
        reasons.append((LAUNCH, "the game is not running"))
    level = max((item[0] for item in reasons), key=LEVEL_ORDER.get, default=NONE)
    return level, [f"{item[0]}: {item[1]}" for item in reasons]


def parse_arm(text: str) -> dict:
    """An arm of --mode confirm: words separated by spaces or commas. `tag` turns an experiment on, `-tag` off,
    `KEY=VALUE` is a session env line, `path:native|proton` and `route:panvk|bridge|venus` replace --path/--route."""
    arm: dict = {"on": [], "off": [], "env": [], "path": None, "route": None}
    for word in re.split(r"[\s,]+", text.strip()):
        if not word:
            continue
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", word):
            arm["env"].append(word)
        elif word.startswith("path:") and word[5:] in ("native", "proton"):
            arm["path"] = word[5:]
        elif word.startswith("route:") and word[6:] in ("panvk", "bridge", "venus"):
            arm["route"] = word[6:]
        elif re.fullmatch(r"-[a-z0-9][a-z0-9-]*", word):
            arm["off"].append(word[1:])
        elif re.fullmatch(r"[a-z0-9][a-z0-9-]*", word):
            arm["on"].append(word)
        else:
            raise ValueError(f"cannot read arm word {word!r}")
    both = set(arm["on"]) & set(arm["off"])
    if both:
        raise ValueError(f"both on and off: {sorted(both)}")
    return arm


# ---------------------------------------------------------------- pushed files, script length, checks

PUSH_FORBIDDEN = ("/steamapps/", "/userdata/", "/compatdata/", "slaythespire", "/remote/", "/saves/", ".save",
                  "remotecache", "/pfx/", ".vdf", ".bench-orig")


def push_target_problem(path: str) -> str | None:
    """Why a guest path may not be replaced by --push-file, or None. Engine and overlay files only: nothing
    under Steam's library, its userdata, a Wine prefix or the game's own data folder."""
    if not re.fullmatch(r"/[A-Za-z0-9._+/-]+", path) or path.endswith("/"):
        return "not a plain absolute file path"
    if any(part in ("", ".", "..") for part in path.split("/")[1:]):
        return "has an empty, '.' or '..' part"
    lowered = path.lower()
    for word in PUSH_FORBIDDEN:
        if word in lowered:
            return f"looks like a game or save file ({word})"
    return None


def parse_push(spec: str) -> tuple[str, str]:
    local, sep, guest = spec.rpartition(":")
    if not sep or not local or not guest:
        raise ValueError(f"--push-file takes LOCAL:GUEST_PATH, not {spec!r}")
    problem = push_target_problem(guest)
    if problem:
        raise ValueError(f"--push-file {guest}: {problem}")
    return local, guest


def truncate_steps(steps: list[dict], turns: int | None) -> list[dict]:
    """The script up to and including its Nth End Turn (None or 0: all of it)."""
    if not turns:
        return list(steps)
    out = []
    seen = 0
    for step in steps:
        out.append(step)
        if step.get("kind") == "end_turn":
            seen += 1
            if seen == turns:
                return out
    if seen < turns:
        raise ValueError(f"the script has {seen} turns, not {turns}")
    return out


def script_seconds(steps: list[dict], press_s: float = 0.1, check_s: float = 0.9) -> float:
    """About how long the scripted steps take to play (presses, settle times, check screenshots)."""
    total = 0.0
    for step in steps:
        pads = len(step.get("pads", []))
        total += pads * press_s + max(0, pads - 1) * step.get("between_ms", 0) / 1000.0
        total += (step.get("settle_ms", 0) + step.get("pause_ms", 0)) / 1000.0
        if step.get("expect_enemy_hp") is not None or step.get("expect_hand"):
            total += check_s
    return round(total, 1)


def offline_evidence(texts: list[str], launched_local: str | None = None, slack_s: int = 120) -> dict:
    """From a pulled session archive: Lanner's own setting line (`steam_offline=true`), Steam's last `Start offline - N`
    and its `[date time] User is offline` lines, the last of which must not be older than the game launch
    (launched_local, the phone's `date '+%F %T'` at the launch request; Steam's logs keep earlier sessions)."""
    joined = "\n".join(texts)
    setting = re.findall(r"^steam_offline=(\w+)", joined, re.M)
    start = re.findall(r"Start offline - (\d)", joined)
    users = sorted(re.findall(r"\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\] User is offline", joined))
    problems = []
    if not setting or setting[-1] != "true":
        problems.append(f"steam_offline={setting[-1] if setting else 'missing'}")
    if not start or start[-1] != "1":
        problems.append(f"Steam's last 'Start offline' line: {start[-1] if start else 'none'}")
    if not users:
        problems.append("no 'User is offline' line")
    elif launched_local:
        limit = datetime.datetime.strptime(launched_local, "%Y-%m-%d %H:%M:%S") - datetime.timedelta(seconds=slack_s)
        if datetime.datetime.strptime(users[-1], "%Y-%m-%d %H:%M:%S") < limit:
            problems.append(f"the last 'User is offline' line ({users[-1]}) is older than the game launch ({launched_local})")
    return {"setting": setting[-1] if setting else None, "start_offline_last": start[-1] if start else None,
            "user_is_offline_last": users[-1] if users else None, "launched_local": launched_local,
            "ok": not problems, "problems": problems}


def reps_identical(reps: list[dict]) -> list[str]:
    """Every repetition must have played the same fight: the same cards in the same order, the same enemy
    moves and the same health checks. Returns what differs from the first repetition."""
    problems = []
    if not reps:
        return problems
    first = reps[0]
    for rep in reps[1:]:
        for key in ("cards", "monster_moves"):
            if first.get(key) is not None and rep.get(key) is not None and rep[key] != first[key]:
                problems.append(f"rep {rep.get('index')}: {key} {rep[key]} differ from rep {first.get('index')}: {first[key]}")
        a, b = first.get("enemy_hp_checks") or [], rep.get("enemy_hp_checks") or []
        if len(a) != len(b) or any((x is None) != (y is None) or (x is not None and abs(x - y) > 0.035) for x, y in zip(a, b)):
            problems.append(f"rep {rep.get('index')}: enemy health checks {b} differ from {a}")
    return problems


# ---------------------------------------------------------------- repeats: spread and A/B intervals

CONFIRM_SEED = 20261128
CONFIRM_METRICS = ("anim_p99_ms", "anim_fps", "over50_per_min", "mem_available_mb")


def spread(values: list[float]) -> dict:
    values = [float(value) for value in values if value is not None]
    if not values:
        return {"n": 0, "mean": None, "median": None, "sd": None, "min": None, "max": None, "range": None}
    ordered = sorted(values)
    count = len(ordered)
    centre = sum(ordered) / count
    middle = ordered[count // 2] if count % 2 else (ordered[count // 2 - 1] + ordered[count // 2]) / 2.0
    sd = math.sqrt(sum((value - centre) ** 2 for value in ordered) / (count - 1)) if count > 1 else None
    return {"n": count, "mean": round(centre, 3), "median": round(middle, 3), "sd": None if sd is None else round(sd, 3),
            "min": ordered[0], "max": ordered[-1], "range": round(ordered[-1] - ordered[0], 3)}


def pooled_sd(groups: list[list[float]]) -> tuple[float | None, int]:
    """Standard deviation within groups of same-configuration repeats, and its degrees of freedom."""
    total = 0.0
    freedom = 0
    for group in groups:
        group = [value for value in group if value is not None]
        if len(group) < 2:
            continue
        centre = sum(group) / len(group)
        total += sum((value - centre) ** 2 for value in group)
        freedom += len(group) - 1
    return (round(math.sqrt(total / freedom), 3) if freedom else None), freedom


def bootstrap_diff_ci(a_pairs: list[list[float]], b_pairs: list[list[float]], seed: int = CONFIRM_SEED,
                      draws: int = 10000, level: float = 0.95) -> dict:
    """B minus A over interleaved pairs: a_pairs[i] and b_pairs[i] are the repetitions of pair i. Two-stage
    percentile bootstrap (pairs with replacement, then each arm's repetitions inside a drawn pair), from a
    fixed seed so the same values always give the same interval. With one repetition per arm it is the
    paired bootstrap of the per-pair differences."""
    if len(a_pairs) != len(b_pairs) or not a_pairs or any(not a or not b for a, b in zip(a_pairs, b_pairs)):
        raise ValueError("every pair needs at least one value in each arm")
    centre = lambda values: sum(values) / len(values)
    count = len(a_pairs)
    point = centre([centre(b) - centre(a) for a, b in zip(a_pairs, b_pairs)])
    generator = random.Random(seed)
    results = []
    for _ in range(draws):
        total = 0.0
        for _pair in range(count):
            index = generator.randrange(count)
            a, b = a_pairs[index], b_pairs[index]
            total += (centre([b[generator.randrange(len(b))] for _ in b]) - centre([a[generator.randrange(len(a))] for _ in a]))
        results.append(total / count)
    results.sort()
    tail = (1.0 - level) / 2.0
    low = results[int(tail * draws)]
    high = results[min(draws - 1, int((1.0 - tail) * draws))]
    degenerate = results[0] == results[-1]
    return {"diff": round(point, 3), "low": round(low, 3), "high": round(high, 3), "level": level, "pairs": count,
            "reps": [len(a) for a in a_pairs], "seed": seed, "draws": draws, "degenerate": degenerate,
            "excludes_zero": (not degenerate) and (low > 0 or high < 0)}


def detectable(sd: float, pairs: int) -> dict:
    """What B minus A must be for `pairs` interleaved pairs to see it, given the run-to-run standard deviation of
    one arm: the difference a 95 % interval just excludes 0 at, and the one found four times in five.
    Repetitions inside a launch are not counted as extra pairs (how much they help is not known yet)."""
    error = sd * math.sqrt(2.0 / pairs)
    return {"pairs": pairs, "se": round(error, 2), "excludes_zero_from": round(1.96 * error, 2),
            "power80_from": round(2.8 * error, 2)}


def confirm_decision(interval: dict, pairs: int, min_pairs: int, max_pairs: int, floor_sd: float | None) -> dict:
    """The stopping rule of --mode confirm on the animation-window p99 difference: stop as "different" when the
    interval excludes 0, from min_pairs on, and the difference also clears what the earlier noise floor
    (floor_sd, one arm's run-to-run standard deviation) allows for that many pairs; otherwise go on to
    max_pairs. The second condition is there because a bootstrap interval over three to six values that is
    looked at after every pair excludes 0 by chance about one time in three."""
    beyond = None
    if floor_sd is not None:
        beyond = abs(interval["diff"]) >= detectable(floor_sd, pairs)["excludes_zero_from"]
    if interval["excludes_zero"] and pairs >= min_pairs and beyond in (True, None):
        return {"stop": True, "why": "interval excludes 0", "verdict": "different", "beyond_noise_floor": beyond}
    if pairs >= max_pairs:
        verdict = "no difference shown"
        if interval["excludes_zero"]:
            verdict = "interval excludes 0 but the difference is inside the earlier noise floor"
        return {"stop": True, "why": "max pairs", "verdict": verdict, "beyond_noise_floor": beyond}
    why = "interval includes 0"
    if interval["excludes_zero"]:
        why = "fewer than min pairs" if pairs < min_pairs else "inside the earlier noise floor"
    return {"stop": False, "why": why, "verdict": None, "beyond_noise_floor": beyond}


NOISE_MARK = "<!-- noise-floor lines are added above this line -->"
NOISE_PREFIX = "- noise-floor"


def noise_floor_line(date: str, build: str, config: str, source: str, groups: dict[str, list[list[float]]], note: str) -> str:
    """One line of RESULTS.md's "Noise floor" list. groups is {metric: [[same-configuration repeats], ...]} for
    CONFIRM_METRICS; the line gives each metric's pooled standard deviation and what one, three and six
    pairs can see in the animation p99."""
    cells = [NOISE_PREFIX, date, str(build)[:7], config, source]
    units = {"anim_p99_ms": "anim p99 sd {} ms", "anim_fps": "fps sd {}", "over50_per_min": "over-50 sd {} /min",
             "mem_available_mb": "MemAvailable sd {} MB"}
    p99_sd, freedom = pooled_sd(groups.get("anim_p99_ms") or [])
    repeats = sum(len(group) for group in groups.get("anim_p99_ms") or [])
    cells.append(f"n={repeats} in {len(groups.get('anim_p99_ms') or [])} groups (df {freedom})")
    for metric in CONFIRM_METRICS:
        value, _ = pooled_sd(groups.get(metric) or [])
        cells.append(units[metric].format("n/a" if value is None else (round(value) if metric == "mem_available_mb" else round(value, 2))))
    if p99_sd:
        cells.append("p99 seen from " + " / ".join(f"{detectable(p99_sd, pairs)['excludes_zero_from']:.1f}" for pairs in (1, 3, 6)) +
                     " ms with 1 / 3 / 6 pairs")
    else:
        cells.append("p99 floor n/a")
    cells.append(f"`{note}`")
    return " | ".join(cells)


def insert_noise_line(text: str, line: str) -> str:
    """RESULTS.md with the line added to its noise-floor list (the list is created before the standard rows'
    heading when it is not there, so run.py's appended table rows stay at the end of the file)."""
    if NOISE_MARK in text:
        return text.replace(NOISE_MARK, line + "\n" + NOISE_MARK, 1)
    section = ("## Noise floor\n\nRun-to-run spread of same-configuration repeats (format in `tools/bench/README.md`).\n\n"
               + line + "\n" + NOISE_MARK + "\n\n")
    heading = "## Standard bench rows"
    if heading in text:
        return text.replace(heading, section + heading, 1)
    return text.rstrip("\n") + "\n\n" + section


def rep_metrics(rep: dict) -> dict:
    animation = (rep.get("frames") or {}).get("animation") or {}
    return {"anim_p99_ms": animation.get("p99"), "anim_fps": animation.get("fps") or None,
            "over50_per_min": animation.get("over50_per_min"),
            "mem_available_mb": (rep.get("memory") or {}).get("MemAvailable_mb")}


def validate_screen(summary: dict) -> list[str]:
    """The keys --mode screen adds to a schema-1 summary."""
    problems = validate_phases(summary.get("phases"))
    if summary.get("mode") != "screen":
        problems.append("mode")
    if not isinstance(summary.get("reps"), list):
        return problems + ["reps missing"]
    for index, rep in enumerate(summary["reps"]):
        problems += [f"reps[{index}].{key} missing" for key in ("index", "rep_in_launch", "warmup", "frames", "steps", "window_ms") if key not in rep]
    if not isinstance(summary.get("spread"), dict):
        problems.append("spread missing")
    else:
        problems += [f"spread.{metric} missing" for metric in CONFIRM_METRICS if metric not in summary["spread"]]
    for key in ("reuse", "pushed_files", "identical"):
        if key not in summary:
            problems.append(f"{key} missing")
    return problems


def validate_confirm(summary: dict) -> list[str]:
    problems = [f"missing {key}" for key in ("schema", "mode", "name", "started_utc", "a", "b", "pairs", "values", "intervals",
                                              "decision", "spread", "seed", "share", "ok", "notes") if key not in summary]
    if summary.get("mode") != "confirm":
        problems.append("mode")
    for metric in CONFIRM_METRICS:
        if metric not in (summary.get("intervals") or {}):
            problems.append(f"intervals.{metric} missing")
    return problems
