#!/usr/bin/env python3
"""Suggest DROIDDECK_DOTNET_HEAP_LIMIT_MB for a .NET game from session telemetry (memory.csv).

Inputs are what perf_report.py reads: share-bundle or telemetry zips, session folders, or
directories of them. Pass runs of one game that ended normally.

memory.csv's game_mb is the resident anonymous and shared memory of every Wine process, so it
includes the managed heap, CoreCLR, Godot, FEX's code cache and driver memory. Pages swapped out
are not resident, so each sample adds the swap growth since the game appeared. The managed heap
cannot exceed that sum, which makes the sum's peak an upper bound on what the GC committed while
pages were touched. The suggestion is that peak plus 25 % and 256 MB, rounded up to 256 MB: a
cap the measured runs never reached. A suggestion at or above the device's RAM is no cap at all.
See docs/research/memory-footprint.md.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import perf_report  # noqa: E402

STEP_MB = 256
HEADROOM = 1.25
MIN_MB, MAX_MB = 256, 16384


def rows(text: str) -> list[dict]:
    lines = text.splitlines()
    if not lines:
        return []
    header = lines[0].split(",")
    out = []
    for line in lines[1:]:
        values = line.split(",")
        if len(values) != len(header):
            continue
        out.append({k: int(v) for k, v in zip(header, values) if v.strip().isdigit()})
    return out


def game_bound(samples: list[dict]) -> tuple[int, int, int] | None:
    """(peak game_mb, peak game_mb + swap growth since the game appeared, RAM in MB) or None."""
    peak = bound = total = 0
    base = None
    for row in samples:
        if "used_mb" in row and "available_mb" in row:
            total = max(total, row["used_mb"] + row["available_mb"])
        game = row.get("game_mb", 0)
        if game <= 0:
            continue
        swap = row.get("swap_used_mb", 0)
        base = swap if base is None else min(base, swap)
        peak = max(peak, game)
        bound = max(bound, game + swap - base)
    return (peak, bound, total) if peak else None


def suggest(bound_mb: int, total_mb: int) -> int | None:
    limit = -(-int(bound_mb * HEADROOM + STEP_MB) // STEP_MB) * STEP_MB
    limit = max(MIN_MB, min(MAX_MB, limit))
    if total_mb and limit >= total_mb:
        return None
    return limit


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", nargs="+", help="session zips, folders or directories of them")
    args = parser.parse_args(argv)
    peak = bound = total = runs = 0
    for session in perf_report.load(args.inputs):
        found = game_bound(rows(session.files.get("memory.csv", "")))
        if found is None:
            continue
        runs += 1
        print(f"{session.key}: game peak {found[0]} MB, with swap growth {found[1]} MB, RAM {found[2]} MB")
        peak, bound, total = max(peak, found[0]), max(bound, found[1]), max(total, found[2])
    if not runs:
        print("dotnet_heap_limit: no memory.csv with a running game", file=sys.stderr)
        return 1
    limit = suggest(bound, total)
    print(f"{runs} run(s): game peak {peak} MB, bound {bound} MB, RAM {total} MB")
    if limit is None:
        print("no useful limit: the bound plus headroom reaches the device's RAM")
    else:
        print(f"DROIDDECK_DOTNET_HEAP_LIMIT_MB={limit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
