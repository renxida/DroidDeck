"""Pure parts of the standard bench: frame statistics, window selection, log parsers, screenshot
features on raw RGBA buffers and the summary schema. No adb here, so tools/tests covers it in CI."""
from __future__ import annotations

import json
import re
import struct
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
