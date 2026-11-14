"""Failure and degradation signatures for tools/autofix/triage.py.

Each entry of CATALOG is data: a stable id, a default severity (fail | degrade | info), the owning
thread (hub, fex, graphics, native, dotnet, main), the stage the problem shows up at, a one-line
description, and a matcher over a parsed run (triage.Run). A matcher returns None when the run does
not show the signature, or its evidence: a list of log lines and facts (at most 6 are kept), or a
(evidence, severity) pair when the severity depends on the run (a native-engine failure the Proton
fallback covered is a degradation; the same failure with no game afterwards is a failure).

Signatures prefer concrete markers (a log line, an event, a gap in a timeline) over heuristics.
Two markers are deliberately NOT failures: Wine's "nested exception on signal stack" at process
kill (arm64 Wine has no sigaltstack) and ld.so's complaint about the x86 gameoverlayrenderer.so.
"""
from __future__ import annotations

import re

OWNERS = ("hub", "fex", "graphics", "native", "dotnet", "main")
SEVERITIES = ("fail", "degrade", "info")
STAGES = ("app", "setup", "steam_ui", "steam_signed_in", "launch_requested", "game_exec",
          "game_first_frame", "in_game")

# Thresholds in one place, so a change is a one-line diff with a reason.
STEAM_UI_SLOW_S = 45          # normal is 9-25 s; a cold Steam start is ~35-40 s
STEAM_UI_STALL_S = 120        # the 2026-11-14 login1 replay storm took 150 s
STEAM_UI_NEVER_S = 180        # Steam started, no interface for this long, then the session ended
LOGIN1_STORM_TOTAL = 20       # "login1: pausing the session" lines in one session
LOGIN1_STORM_BEFORE_UI = 5    # ... or this many before Steam's interface came up
BACKGROUND_STARTUP_S = 10     # app in the background (Steam frozen) while starting, in total
LAUNCH_NO_EXEC_S = 60         # launch requested, no game process after this long
EXEC_NO_WINDOW_S = 90         # game process exec'd, no game frame after this long
WINE_STALL_S = 60             # silence in the game's Wine log during startup
ZRAM_FULL_PCT = 90            # swap (zram) use at game launch
THRASH_AVAILABLE_MB = 150     # memory.csv: available memory under this ...
THRASH_MAJFLT_S = 500         # ... while the game takes this many major faults per second
THRASH_MIN_SAMPLES = 3        # ... in this many 5 s samples (15 s)
GAME_RSS_MB = 2100            # game resident memory (resident + swapped is 2.7-3.0 GB in every session)
FREEZE_MS = 1000              # a single game frame this long is a freeze
FREEZES_MIN = 3
GAME_WARMUP_S = 60            # the game's own loading after its first frame: no freezes/fps judged
THERMAL_SEVERE = 3            # Android thermal status (3 severe, 4 critical)
FPS_OVER_CAP_RATIO = 1.2      # game commits this much faster than its cap
FPS_LOW = 20                  # in-game median fps under this
STUTTERS_PER_MIN_HIGH = 60    # frames over 2x the median per minute of game focus ...
STUTTER_P99_MS = 66.7         # ... when p99 also passes two 30 fps frames
P99_HIGH_MS = 100             # p99 frame time alone
BURST_OVER50_PER_MIN = 100    # one game minute (after warm-up) with this many frames over 50 ms
FEX_COLD_CACHE_MB = 5.0       # FEX disk cache this small at launch is a cold start
MIN_GAME_S = 30               # least game time before frame statistics are judged
SMAPS_RO_ANON_MB = 500
# Kestrel FEX builds whose libarm64ecfex.dll imports kernelbase heap/thread calls (patches 0008,
# 0009): they hang or crash at emulator start even with the options off. Fixed in .23.
FEX_NONNTDLL_BUILDS = ("Kestrel-FEXCore-20261114.20", "Kestrel-FEXCore-20261114.21",
                       "Kestrel-FEXCore-20261114.22")


def _first(lines, pattern, limit=6):
    rx = re.compile(pattern)
    return [line for line in lines if rx.search(line)][:limit]


def _fallback_severity(run, default_if_covered="degrade"):
    """A native-engine failure is covered when the game still reached its first frame (Proton)."""
    return default_if_covered if run.stage_at_least("game_first_frame") else "fail"


# ---------------------------------------------------------------------------- setup / Steam

def m_powervr_missing(run):
    hits = [e for e in run.events_named("session.failed") if e.get("code") == "POWERVR_MISSING"]
    if hits:
        return [f"session.failed code=POWERVR_MISSING: {hits[0].get('message', '')}"]
    return None


def m_session_failed(run):
    hits = [e for e in run.events_named("session.failed") if e.get("code") != "POWERVR_MISSING"]
    if hits:
        return [f"session.failed code={e.get('code')} status={e.get('status')}: {e.get('message', '')}"
                for e in hits][:6]
    return None


def m_steam_ui_never(run):
    if run.steam_ui_s() is not None or not run.steam_started_wall:
        return None
    waited = (run.end_ms - run.steam_started_wall) / 1000
    if waited >= STEAM_UI_NEVER_S:
        return [f"Steam started, no interface after {waited:.0f} s (session ended)"]
    return None


def _steam_ui_evidence(run, ui_s):
    ev = [f"steam_ui_s={ui_s:.1f}"]
    ev += _first(run.log, r"== steam: interface up after", 1)
    if run.steam_download_seen:
        ev.append("steam.client_download active during start (client update)")
    pauses = run.login1_pauses()
    if pauses:
        ev.append(f"login1: {pauses} 'pausing the session' lines")
    return ev


def m_steam_ui_stall(run):
    ui = run.steam_ui_s()
    if ui is None or ui < STEAM_UI_STALL_S or run.steam_download_seen:
        return None
    return _steam_ui_evidence(run, ui)


def m_steam_ui_slow(run):
    ui = run.steam_ui_s()
    if ui is None or ui < STEAM_UI_SLOW_S:
        return None
    if ui >= STEAM_UI_STALL_S and not run.steam_download_seen:
        return None  # steam-ui-stall
    ev = _steam_ui_evidence(run, ui)
    return (ev, "info") if run.steam_download_seen else ev


def m_login1_storm(run):
    total = run.login1_pauses()
    before = run.login1_pauses(before_ui=True)
    if total >= LOGIN1_STORM_TOTAL or before >= LOGIN1_STORM_BEFORE_UI:
        return [f"login1: {total} 'pausing the session' lines, {before} before Steam's interface was up",
                "each is a PrepareForSleep pause/resume replay to Steam"]
    return None


def m_backgrounded_startup(run):
    """The app left the foreground while Steam or the game was starting: Steam is frozen meanwhile."""
    until = run.game_first_frame_wall or run.end_ms
    spans = [(a, b) for a, b in run.suspended_spans() if a < until]
    total = sum(min(b, until) - a for a, b in spans) / 1000
    if total >= BACKGROUND_STARTUP_S:
        ev = [f"session suspended {total:.0f} s before the game's first frame ({len(spans)} time(s))"]
        ev += [f"suspended at +{(a - run.t0) / 1000:.1f} s for {(b - a) / 1000:.1f} s" for a, b in spans[:4]]
        return ev
    return None


def m_resume_stuck(run):
    hits = run.events_named("session.resume_stuck")
    if hits:
        return [f"session.resume_stuck at +{(e['t'] - run.t0) / 1000:.0f} s" for e in hits][:6]
    return None


# ---------------------------------------------------------------------------- launch

def m_game_never_execd(run):
    req = run.launch_requested_wall
    if req is None or run.game_exec_wall is not None or run.native_lines:
        return None
    waited = (run.end_ms - req) / 1000
    if waited >= LAUNCH_NO_EXEC_S:
        ev = [f"launch requested, no game process {waited:.0f} s later (session ended)"]
        ev += _first(run.log, r"== steam: .*(launching requested|not started directly|took the launch)", 3)
        return ev
    return None


def m_game_exe_not_running(run):
    hits = _first(run.log or run.crash_lines, r"thread placement: \S+ not running after")
    return hits or None


def m_game_no_window(run):
    """The game process runs (thread placement saw it) but never put up a frame."""
    if run.game_exec_wall is None or run.game_first_frame_wall is not None:
        return None
    if (run.end_ms - run.game_exec_wall) / 1000 < EXEC_NO_WINDOW_S:
        return None
    running = [p for p in run.thread_placement if p.get("pid")]
    if not running:
        return None
    ev = [f"game exec'd, no game frame in {(run.end_ms - run.game_exec_wall) / 1000:.0f} s"]
    ev += [p["line"] for p in running[:3]]
    return ev


def m_wine_explorer_failed(run):
    ev = [f"game.explorer_failed attempt={e.get('attempt')} action={e.get('action')}"
          for e in run.events_named("game.explorer_failed")]
    ev += run.wine_grep(r"The explorer process failed to start", 2)
    ev += run.wine_grep(r"waylanddrv:.*(doesn't support|failed)", 2)
    return ev[:6] or None


def m_fex_hang_after_memory_names(run):
    """Kestrel FEX patch 0008 starts a "FEX memory names" thread at emulator init; a hang there
    shows as silence in the Wine log right after that thread is named."""
    for rel, lines in run.wine_logs.items():
        stamps = run.wine_stamps(rel)
        for i, (ts, idx) in enumerate(stamps):
            if 'FEX memory names' not in lines[idx]:
                continue
            nxt = stamps[i + 1][0] if i + 1 < len(stamps) else None
            gap = (nxt - ts) if nxt is not None else None
            exe_missing = bool(_first(run.log, r"thread placement: \S+ not running after", 1))
            if (gap is not None and gap >= WINE_STALL_S) or (gap is None and exe_missing):
                ev = [lines[idx].strip()]
                ev.append(f"next Wine log line {gap:.0f} s later" if gap is not None else "no Wine log line after it")
                if nxt is not None:
                    ev.append(lines[stamps[i + 1][1]].strip())
                ev += _first(run.log, r"thread placement: \S+ not running after", 1)
                return ev
    return None


def m_wine_startup_stall(run):
    """The game's exe never ran and its Wine log went quiet for a minute or more."""
    if not _first(run.log, r"thread placement: \S+ not running after", 1):
        return None
    if m_fex_hang_after_memory_names(run):
        return None
    for rel, lines in run.wine_logs.items():
        stamps = run.wine_stamps(rel)
        for (a, ia), (b, ib) in zip(stamps, stamps[1:]):
            if b - a >= WINE_STALL_S:
                return [lines[ia].strip(), f"... {b - a:.0f} s of silence ...", lines[ib].strip()]
    return None


def m_wine_nested_exception(run):
    hits = run.wine_grep(r"nested exception on signal stack", 2)
    return hits or None


def m_fex_nonntdll_imports(run):
    text = " ".join([run.fex_build_line] + [e.get("state", "") for e in run.events_named("components.fex")])
    bad = [b for b in FEX_NONNTDLL_BUILDS if b in text]
    if bad:
        return [f"FEX build {bad[0]} (libarm64ecfex.dll imports kernelbase; hangs at emulator start)"] + \
            ([run.fex_build_line] if run.fex_build_line else [])
    return None


def m_fex_kestrel_without_experiment(run):
    """A Kestrel FEX build is installed while the session did not ask for fex-kestrel."""
    if "fex-kestrel" in run.experiments:
        return None
    if "Kestrel" in run.fex_build_line:
        return [run.fex_build_line, "experiments do not include fex-kestrel"]
    for e in run.events_named("components.fex"):
        state = e.get("state", "")
        m = re.search(r"\bhas (\S*Kestrel\S*)", state)
        if m:
            return [state[:240], "experiments do not include fex-kestrel"]
    return None


def m_fex_cold_cache(run):
    mb = run.fex_cache_mb
    if mb is None or mb >= FEX_COLD_CACHE_MB:
        return None
    ev = _first(run.log, r"FEX disk cache on for app", 1)
    fps = run.fps_settled(GAME_WARMUP_S)
    if fps is not None:
        ev.append(f"in-game median {fps:.1f} fps after the first {GAME_WARMUP_S} s")
    severity = "degrade" if fps is not None and fps < FPS_LOW else "info"
    return ev, severity


# ---------------------------------------------------------------------------- native engine

def m_native_watchdog_fallback(run):
    hits = [line for line in run.native_lines
            if re.search(r"native-engine: .*(starting on Proton|no first frame|exited with|rc=86|fall)", line)]
    if not hits:
        return None
    return hits[:6], _fallback_severity(run)


def m_native_compose_mixed_mode(run):
    """compose() refused a mixed-mode assembly. The game's own assembly being mixed-mode (or an
    unnamed one aborting compose) breaks the native path; another library left out is only info."""
    own = {s.lower() for s in run.game_assembly_names()}
    ev, own_hit, aborted = [], False, False
    for line in run.native_lines + run.native_log:
        if re.search(r"ValueError: mixed-mode", line):
            ev.append(line.strip())
            aborted = True
        m = re.search(r"left out (\S+?):? .*mixed-mode", line)
        if m:
            ev.append(line.strip())
            if m.group(1).lower().rsplit("/", 1)[-1] in own:
                own_hit = True
    for item in run.native_launch.get("mixed_mode", []) if isinstance(run.native_launch, dict) else []:
        name = str(item).rsplit("/", 1)[-1]
        ev.append(f"native-launch.json mixed_mode: {name}")
        if name.lower() in own:
            own_hit = True
    if not ev:
        return None
    if own_hit or aborted:
        return ev[:6], _fallback_severity(run, "degrade")
    return ev[:6], "info"


def m_native_no_display(run):
    hits = [line for line in run.native_lines + run.native_log
            if re.search(r"Unable to create DisplayServer|None of the devices supports both graphics and present", line)]
    if hits:
        return hits[:6], _fallback_severity(run)
    return None


def m_native_steam_init(run):
    hits = [line for line in run.native_lines + run.native_log
            if re.search(r"libsteam_api\.so.*(missing|not found|lacks)|flat-API|SteamAPI_Init.*(fail|false)", line, re.I)]
    if hits:
        return hits[:6], "fail"
    return None


def m_native_gdextension_missing(run):
    hits = [line for line in run.native_lines + run.native_log if "GDExtension dynamic library not found" in line]
    return hits[:6] or None


def m_native_component_stub(run):
    hits = [line for line in run.native_lines + run.native_log + run.grep_text_files(r"stubs/libsteam_api64\.so", 3)
            if "stubs/libsteam_api64.so" in line]
    return hits[:3] or None


# ---------------------------------------------------------------------------- crashes

def m_dotnet_bad_image(run):
    hits = run.grep_crash_sources(r"BadImageFormatException")
    return hits or None


def m_dotnet_unhandled(run):
    hits = run.grep_crash_sources(r"Unhandled exception\.|Unhandled Exception:")
    return hits or None


def m_fex_sigill(run):
    hits = run.grep_crash_sources(r"droiddeck-fex: SIGILL")
    return hits or None


def m_wine_page_fault(run):
    hits = run.grep_crash_sources(r"Unhandled page fault")
    return hits or None


def m_godot_crash(run):
    hits = run.grep_crash_sources(r"Program crashed|CrashHandlerException")
    return hits or None


def m_game_abnormal_exit(run):
    ev = []
    for e in run.events_named("game.exited"):
        if e.get("abnormal"):
            ev.append(f"game.exited abnormal how={e.get('how')} status={e.get('status')} signal={e.get('signal')} "
                      f"run_s={e.get('run_s')}")
            ev += [str(x) for x in (e.get("evidence") or [])][:4]
    for g in run.game_exits:
        if g["signal"] not in (0, 1, 2, 15) or (g["signal"] == 0 and g["status"] not in (0, 129, 130, 143)):
            ev.append(g["line"])
    return ev[:6] or None


def m_game_segfault(run):
    hits = [line for line in run.crash_lines
            if "Segmentation fault" in line and "droiddeck-session" not in line]
    return hits[:6] or None


def m_steam_segfault_at_exit(run):
    hits = _first(run.log, r"droiddeck-session: line \d+: .*Segmentation fault.*steam", 2)
    return [h[:240] for h in hits] or None


def m_app_killed(run):
    hits = []
    for e in run.events_named("app.exit"):
        if e.get("before_session") or not e.get("abnormal"):
            continue
        if not (run.t0 <= e["t"] <= run.end_ms):
            continue
        if ":" in str(e.get("process", "")):
            continue  # a helper process (shortcuts_sync, balloon), not the app
        hits.append(f"app.exit reason={e.get('reason')} process={e.get('process')} status={e.get('status')} "
                    f"{e.get('description', '')}".strip())
    return hits[:6] or None


def m_helper_lmk(run):
    hits = [f"app.exit reason={e.get('reason')} process={e.get('process')}"
            for e in run.events_named("app.exit")
            if e.get("abnormal") and ":" in str(e.get("process", "")) and run.t0 <= e["t"] <= run.end_ms]
    return hits[:6] or None


# ---------------------------------------------------------------------------- memory / thermal / frames

def m_zram_full_at_launch(run):
    """zram full at launch is common; it is a degradation when the game then thrashes."""
    pct = run.metrics.get("zram_used_pct_at_launch")
    if pct is None or pct < ZRAM_FULL_PCT:
        return None
    ev = [f"zram {pct:.0f}% used at game launch ({run.swap_at_launch_mb} of {run.swap_total_mb} MB)"]
    return ev, ("degrade" if _thrash_rows(run) else "info")


def _thrash_rows(run):
    rows = [r for r in run.memory if r.get("available_mb") is not None and r.get("available_mb") < THRASH_AVAILABLE_MB
            and (r.get("game_majflt_s") or 0) > THRASH_MAJFLT_S]
    return rows if len(rows) >= THRASH_MIN_SAMPLES else []


def m_memory_thrash(run):
    rows = _thrash_rows(run)
    if not rows:
        return None
    ev = [f"{len(rows)} memory samples with available < {THRASH_AVAILABLE_MB} MB and game majflt > {THRASH_MAJFLT_S}/s"]
    for r in rows[:3]:
        ev.append(f"+{(r['t_wall_ms'] - run.t0) / 1000:.0f} s available {r.get('available_mb')} MB, "
                  f"majflt {r.get('game_majflt_s')}/s, swap-in {r.get('swapin_mb_s')} MB/s")
    if _settled_freezes(run):
        ev.append(f"{len(_settled_freezes(run))} game frames over {FREEZE_MS} ms")
    return ev


def m_game_memory_high(run):
    best = None
    for r in run.memory:
        rss = r.get("game_mb") or 0
        if isinstance(rss, int) and rss > GAME_RSS_MB and (best is None or rss > best.get("game_mb")):
            best = r
    if best:
        return [f"game resident {best.get('game_mb')} MB (+ {best.get('game_swap_mb') or 0} MB swapped) "
                f"at +{(best['t_wall_ms'] - run.t0) / 1000:.0f} s"]
    return None


def _settled_freezes(run):
    start = (run.game_first_frame_wall or run.t0) + GAME_WARMUP_S * 1000
    return [(w, ms) for w, ms in run.freezes if w >= start]


def m_game_freezes(run):
    freezes = _settled_freezes(run)
    if len(freezes) < FREEZES_MIN:
        return None
    worst = sorted(freezes, key=lambda f: -f[1])[:3]
    return [f"{len(freezes)} game frames over {FREEZE_MS} ms while the game had focus "
            f"(after its first {GAME_WARMUP_S} s)"] + \
        [f"+{(w - run.t0) / 1000:.0f} s frame of {ms:.0f} ms" for w, ms in worst]


def m_lmk_risk(run):
    hits = run.events_named("memory.lmk_risk")
    if hits:
        low = min(hits, key=lambda e: e.get("availableMb") or 1 << 30)
        return [f"{len(hits)} memory.lmk_risk events; lowest available {low.get('availableMb')} MB, "
                f"swap free {low.get('swapFreeMb')} of {low.get('swapTotalMb')} MB"]
    return None


def m_smaps_ro_anon(run):
    for row in run.smaps:
        if row.get("kind") == "top" and str(row.get("process", "")).startswith("SlayTheSpire2") \
                and "anon r--p" in row.get("owner", "") and (row.get("size_mb") or 0) >= SMAPS_RO_ANON_MB:
            return [f"{row.get('process')} {row.get('category')} {row.get('owner')} {row.get('size_mb')} MB "
                    f"(rss {row.get('rss_mb')} MB)"]
    return None


def m_thermal_severe(run):
    tmax = run.metrics.get("thermal_max")
    if tmax is None or tmax < THERMAL_SEVERE:
        return None
    n = sum(1 for v in run.thermal if v >= THERMAL_SEVERE)
    first = next((i for i, v in enumerate(run.thermal) if v >= THERMAL_SEVERE), 0)
    return [f"thermal status max {tmax}; >= {THERMAL_SEVERE} for ~{n * 2 / 60:.0f} min "
            f"(from ~{first * 2 / 60:.0f} min into the session)"]


def m_fps_over_cap(run):
    ev = []
    for cap, fps, seconds in run.fps_by_cap():
        if cap and fps > cap * FPS_OVER_CAP_RATIO and seconds >= MIN_GAME_S:
            ev.append(f"cap {cap} fps: game committed {fps:.1f} fps (median) over {seconds:.0f} s")
    return ev[:6] or None


def m_fps_low(run):
    fps, secs = run.fps_settled(GAME_WARMUP_S), run.game_seconds
    if fps is None or secs < MIN_GAME_S or fps >= FPS_LOW:
        return None
    return [f"in-game median {fps:.1f} fps after the first {GAME_WARMUP_S} s ({secs:.0f} s of game frames; "
            f"fps_p50 over all {run.metrics.get('fps_p50')})"]


def m_stutter_high(run):
    spm, p99 = run.metrics.get("stutters_per_min"), run.metrics.get("frametime_p99_ms")
    if run.game_seconds < MIN_GAME_S or p99 is None:
        return None
    if p99 > P99_HIGH_MS or (spm is not None and spm > STUTTERS_PER_MIN_HIGH and p99 > STUTTER_P99_MS):
        return [f"frametime_p99_ms={p99}, stutters_per_min={spm} (frames over 2x the median)"]
    return None


def m_stutter_burst(run):
    """A stretch of heavy stutter in an otherwise smooth session (e.g. a cap the game does not follow)."""
    if m_stutter_high(run):
        return None
    minutes = run.over50_by_minute(GAME_WARMUP_S)
    bad = [(i, n) for i, n in enumerate(minutes) if n >= BURST_OVER50_PER_MIN]
    if not bad:
        return None
    return [f"{len(bad)} game minute(s) with {BURST_OVER50_PER_MIN}+ frames over 50 ms; worst {max(n for _, n in bad)}"
            f" in game minute {max(bad, key=lambda b: b[1])[0] + 1}",
            "frames over 50 ms per game minute: " + " ".join(str(n) for n in minutes[:20])]


# ---------------------------------------------------------------------------- graphics / bundle

def m_astc_reverted(run):
    text = run.file_text("texpack.txt")
    if not text:
        return None
    if re.search(r'"phase"\s*:\s*"reverted"', text) or re.search(r"\bBC in use\b", text):
        return [line.strip() for line in text.splitlines() if re.search(r"phase|BC in use|reason", line)][:4]
    return None


def m_bundle_wrong_session_log(run):
    text = run.file_text("resource-loads.txt")
    m = re.search(r"^# from (\S*/godot\d{4}-\d{2}-\d{2}T[\d.]+\.log)", text or "", re.M)
    if m:
        return [f"resource-loads.txt built from a rotated log: {m.group(1)}"]
    return None


def m_session_log_missing(run):
    if run.has_session_log or not run.stage_at_least("steam_ui"):
        return None
    return ["session.log not in the bundle (dropped for size or not written)"]


CATALOG = [
    # setup / Steam
    dict(id="powervr-runtime-missing", severity="fail", owner="graphics", stage="setup", match=m_powervr_missing,
         description="session.failed POWERVR_MISSING: the PowerVR runtime could not be downloaded"),
    dict(id="session-failed", severity="fail", owner="main", stage="setup", match=m_session_failed,
         description="session.failed event with any other code"),
    dict(id="steam-ui-never", severity="fail", owner="main", stage="setup", match=m_steam_ui_never,
         description=f"Steam started but showed no interface for {STEAM_UI_NEVER_S}+ s before the session ended"),
    dict(id="steam-ui-stall", severity="fail", owner="main", stage="steam_ui", match=m_steam_ui_stall,
         description=f"Steam interface took {STEAM_UI_STALL_S}+ s with no client download"),
    dict(id="steam-ui-slow", severity="degrade", owner="main", stage="steam_ui", match=m_steam_ui_slow,
         description=f"Steam interface took {STEAM_UI_SLOW_S}+ s (info when a client update was downloading)"),
    dict(id="login1-pause-storm", severity="degrade", owner="main", stage="setup", match=m_login1_storm,
         description="many login1 PrepareForSleep pause/resume replays (each freezes Steam)"),
    dict(id="app-backgrounded-at-startup", severity="degrade", owner="main", stage="setup",
         match=m_backgrounded_startup,
         description=f"session suspended {BACKGROUND_STARTUP_S}+ s before the game's first frame (Steam frozen)"),
    dict(id="session-resume-stuck", severity="degrade", owner="main", stage="setup", match=m_resume_stuck,
         description="session.resume_stuck: resuming from the background did not finish"),
    # launch
    dict(id="game-never-execd", severity="fail", owner="main", stage="launch_requested", match=m_game_never_execd,
         description=f"launch requested, no game process {LAUNCH_NO_EXEC_S}+ s later"),
    dict(id="game-exe-not-running", severity="fail", owner="main", stage="game_exec", match=m_game_exe_not_running,
         description="game-env: 'thread placement: <exe> not running after 60 s' (Proton ran, the game exe never did)"),
    dict(id="game-no-window", severity="fail", owner="main", stage="game_exec", match=m_game_no_window,
         description=f"game process running but no game frame {EXEC_NO_WINDOW_S}+ s after exec"),
    dict(id="wine-explorer-failed", severity="fail", owner="graphics", stage="game_exec", match=m_wine_explorer_failed,
         description="Wine: 'The explorer process failed to start' (no display driver; game gets no window)"),
    dict(id="fex-hang-after-memory-names", severity="fail", owner="fex", stage="game_exec",
         match=m_fex_hang_after_memory_names,
         description=f"Wine log silent {WINE_STALL_S}+ s right after FEX starts its 'FEX memory names' thread"),
    dict(id="wine-startup-stall", severity="fail", owner="fex", stage="game_exec", match=m_wine_startup_stall,
         description=f"game exe never ran and the Wine log went silent {WINE_STALL_S}+ s"),
    dict(id="fex-nonntdll-imports", severity="degrade", owner="fex", stage="game_exec", match=m_fex_nonntdll_imports,
         description="a Kestrel FEX build known to import kernelbase from libarm64ecfex.dll was loaded"),
    dict(id="fex-kestrel-without-experiment", severity="degrade", owner="fex", stage="game_exec",
         match=m_fex_kestrel_without_experiment,
         description="Kestrel FEX installed though the session did not run fex-kestrel (updater kept it)"),
    dict(id="fex-cold-cache", severity="degrade", owner="fex", stage="game_exec", match=m_fex_cold_cache,
         description=f"FEX disk cache under {FEX_COLD_CACHE_MB} MB at launch (degrade when the game then ran "
                     f"under {FPS_LOW} fps)"),
    dict(id="wine-nested-exception", severity="info", owner="fex", stage="game_exec", match=m_wine_nested_exception,
         description="Wine 'nested exception on signal stack' (expected at process kill on arm64; not a failure)"),
    # native engine
    dict(id="native-watchdog-fallback", severity="degrade", owner="native", stage="game_exec",
         match=m_native_watchdog_fallback,
         description="native engine failed or showed no first frame; game-env started Proton instead"),
    dict(id="native-mixed-mode-game", severity="degrade", owner="native", stage="game_exec",
         match=m_native_compose_mixed_mode,
         description="native compose hit a mixed-mode assembly (failure if it is the game's own; else info)"),
    dict(id="native-no-display", severity="degrade", owner="native", stage="game_exec", match=m_native_no_display,
         description="native Godot could not create a display / found no graphics+present queue"),
    dict(id="native-steam-init-failed", severity="fail", owner="native", stage="game_exec", match=m_native_steam_init,
         description="native compose/engine: libsteam_api flat-API names missing or SteamAPI_Init failed"),
    dict(id="native-gdextension-missing", severity="info", owner="native", stage="game_exec",
         match=m_native_gdextension_missing, description="native Godot: a GDExtension library was not found"),
    dict(id="native-component-has-stub", severity="info", owner="native", stage="game_exec",
         match=m_native_component_stub, description="native-engine asset ships stubs/libsteam_api64.so"),
    # crashes
    dict(id="dotnet-bad-image-format", severity="fail", owner="dotnet", stage="game_exec", match=m_dotnet_bad_image,
         description=".NET BadImageFormatException"),
    dict(id="dotnet-unhandled-exception", severity="fail", owner="dotnet", stage="game_exec", match=m_dotnet_unhandled,
         description=".NET 'Unhandled exception'"),
    dict(id="fex-sigill", severity="fail", owner="fex", stage="game_exec", match=m_fex_sigill,
         description="FEX fault report 'droiddeck-fex: SIGILL at ...'"),
    dict(id="wine-unhandled-page-fault", severity="fail", owner="fex", stage="game_exec", match=m_wine_page_fault,
         description="Wine 'Unhandled page fault'"),
    dict(id="godot-crash", severity="fail", owner="main", stage="game_exec", match=m_godot_crash,
         description="Godot 'Program crashed' / crash handler"),
    dict(id="game-abnormal-exit", severity="fail", owner="main", stage="game_exec", match=m_game_abnormal_exit,
         description="game.exited abnormal, or game_exit with a signal other than HUP/INT/TERM or a nonzero status"),
    dict(id="game-segfault", severity="fail", owner="main", stage="game_exec", match=m_game_segfault,
         description="'Segmentation fault' in the game's crash lines"),
    dict(id="steam-segfault-at-exit", severity="info", owner="main", stage="steam_ui", match=m_steam_segfault_at_exit,
         description="the Steam client segfaulted while the session shut down"),
    dict(id="app-killed", severity="fail", owner="hub", stage="setup", match=m_app_killed,
         description="Android ended the app's main process abnormally during the session (LMK, crash, ANR)"),
    dict(id="helper-process-killed", severity="info", owner="hub", stage="setup", match=m_helper_lmk,
         description="Android killed one of the app's helper processes (shortcuts_sync, balloon)"),
    # memory / thermal / frames
    dict(id="zram-full-at-launch", severity="degrade", owner="hub", stage="game_exec", match=m_zram_full_at_launch,
         description=f"zram {ZRAM_FULL_PCT}%+ used when the game launched (degrade when memory-thrash follows)"),
    dict(id="memory-thrash", severity="degrade", owner="hub", stage="game_exec", match=m_memory_thrash,
         description=f"available memory under {THRASH_AVAILABLE_MB} MB while the game takes {THRASH_MAJFLT_S}+ "
                     f"major faults/s, in {THRASH_MIN_SAMPLES}+ samples"),
    dict(id="game-memory-high", severity="degrade", owner="hub", stage="game_exec", match=m_game_memory_high,
         description=f"game resident memory over {GAME_RSS_MB} MB"),
    dict(id="game-freezes", severity="degrade", owner="hub", stage="in_game", match=m_game_freezes,
         description=f"{FREEZES_MIN}+ game frames over {FREEZE_MS} ms with game focus, after its first {GAME_WARMUP_S} s"),
    dict(id="lmk-risk", severity="info", owner="hub", stage="setup", match=m_lmk_risk,
         description="memory.lmk_risk events (swap nearly full, little available)"),
    dict(id="smaps-large-ro-anon", severity="info", owner="hub", stage="in_game", match=m_smaps_ro_anon,
         description=f"a read-only anonymous region of {SMAPS_RO_ANON_MB}+ MB in the game's smaps"),
    dict(id="thermal-severe", severity="degrade", owner="hub", stage="setup", match=m_thermal_severe,
         description=f"Android thermal status {THERMAL_SEVERE}+ (severe/critical)"),
    dict(id="fps-over-cap", severity="degrade", owner="graphics", stage="in_game", match=m_fps_over_cap,
         description=f"game frames come {FPS_OVER_CAP_RATIO}x faster than the frame cap in force"),
    dict(id="fps-low", severity="degrade", owner="hub", stage="in_game", match=m_fps_low,
         description=f"in-game median fps under {FPS_LOW} after the game's first {GAME_WARMUP_S} s"),
    dict(id="stutter-high", severity="degrade", owner="graphics", stage="in_game", match=m_stutter_high,
         description=f"in-game p99 frame time over {P99_HIGH_MS} ms, or over {STUTTERS_PER_MIN_HIGH} stutters/min "
                     f"with p99 over {STUTTER_P99_MS} ms"),
    dict(id="stutter-burst", severity="degrade", owner="graphics", stage="in_game", match=m_stutter_burst,
         description=f"a game minute with {BURST_OVER50_PER_MIN}+ frames over 50 ms in an otherwise smooth session"),
    dict(id="astc-reverted", severity="degrade", owner="graphics", stage="game_exec", match=m_astc_reverted,
         description="texpack.txt: the ASTC texture pack was reverted (BC textures in use)"),
    dict(id="bundle-wrong-session-log", severity="degrade", owner="main", stage="in_game",
         match=m_bundle_wrong_session_log,
         description="resource-loads.txt was built from a rotated godot<stamp>.log (an earlier run's log)"),
    dict(id="session-log-missing", severity="info", owner="main", stage="steam_ui", match=m_session_log_missing,
         description="session.log missing from the bundle"),
]


def validate():
    seen = set()
    for sig in CATALOG:
        assert sig["id"] not in seen, sig["id"]
        seen.add(sig["id"])
        assert sig["severity"] in SEVERITIES, sig
        assert sig["owner"] in OWNERS, sig
        assert sig["stage"] in STAGES, sig
        assert callable(sig["match"]) and sig["description"], sig


validate()
