#!/usr/bin/env python3
"""Guarded edits to config/kestrel.json in the telemetry clone (the app's remote config).

Only keys this tool understands are touched: experiments.on / always / rotate (the lists the app
reads), experiments.parked_by_autofix (ignored by the app, like the other parked_* keys) and
"autotest". Everything else, "note" and "session" included, is left byte-for-byte as JSON.

the owner's pin: "native-engine" in experiments.on is never removed; park_flag refuses it.

Each edit writes the file and returns a unified diff; nothing is committed until
commit_and_push(message), which pulls with rebase, commits and pushes, retrying up to 4 times.

The app reads autotest.branch as the *Steam* branch (beta or stable), not a git branch, and runs the
test on whatever APK is installed: queue_autotest records the build it is meant to verify under
autotest.build (ignored by the app) so the verifier can match the run's slug.
"""
import datetime as _dt
import difflib
import json
import re
import subprocess
import time
from pathlib import Path

PATH = "config/kestrel.json"
PINNED = {"native-engine"}
AUTOTEST_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
STEAM_BRANCHES = ("beta", "stable")
STS2_APPID = 2868840


class ConfigError(Exception):
    pass


def _path(clone):
    return Path(clone) / PATH


def load(clone):
    return json.loads(_path(clone).read_text(encoding="utf-8"))


def dumps(cfg):
    return json.dumps(cfg, indent=2, ensure_ascii=False) + "\n"


def _write(clone, before_text, cfg):
    after = dumps(cfg)
    _path(clone).write_text(after, encoding="utf-8")
    return "".join(difflib.unified_diff(before_text.splitlines(True), after.splitlines(True),
                                        f"a/{PATH}", f"b/{PATH}"))


def park_flag(clone, flag, reason, today=None):
    """Remove `flag` from experiments.on, .always and every .rotate row; record it under
    experiments.parked_by_autofix. Returns the diff ("" when the flag was in none of them)."""
    if flag in PINNED:
        raise ConfigError(f"refusing to park {flag!r}: the owner's pin (see the config's note)")
    if not reason:
        raise ConfigError("a reason is required")
    text = _path(clone).read_text(encoding="utf-8")
    cfg = json.loads(text)
    exp = cfg.get("experiments")
    if not isinstance(exp, dict):
        raise ConfigError("config has no experiments object")
    removed = []
    for key in ("on", "always"):
        lst = exp.get(key)
        if isinstance(lst, list) and flag in lst:
            exp[key] = [f for f in lst if f != flag]
            removed.append(key)
    rows = exp.get("rotate")
    if isinstance(rows, list):
        for i, row in enumerate(rows):
            if isinstance(row, list) and flag in row:
                rows[i] = [f for f in row if f != flag]
                removed.append(f"rotate[{i}]")
    for p in PINNED:
        if p in (json.loads(text).get("experiments", {}).get("on") or []) and p not in (exp.get("on") or []):
            raise ConfigError(f"edit would drop pinned {p!r}")   # belt and braces
    if not removed:
        return ""
    parked = exp.setdefault("parked_by_autofix", {})
    if not isinstance(parked, dict):
        raise ConfigError("experiments.parked_by_autofix is not an object; not touching it")
    parked[flag] = {"reason": reason, "date": today or _dt.date.today().isoformat(), "removed_from": removed}
    return _write(clone, text, cfg)


def unpark_flag(clone, flag):
    """Drop the parked record only (putting the flag back is a human or driver decision)."""
    text = _path(clone).read_text(encoding="utf-8")
    cfg = json.loads(text)
    parked = (cfg.get("experiments") or {}).get("parked_by_autofix") or {}
    if flag not in parked:
        return ""
    del parked[flag]
    return _write(clone, text, cfg)


def queue_autotest(clone, test_id, build=None, menu_seconds=300, app=None, steam_branch=None, repeat_hours=None):
    """Point "autotest" at a new test id. `build` is the upload slug or git branch the run should
    come from (recorded for the verifier; the app does not install builds)."""
    if not AUTOTEST_ID.match(str(test_id)):
        raise ConfigError(f"autotest id {test_id!r} must match {AUTOTEST_ID.pattern}")
    menu_seconds = int(menu_seconds)
    if not 30 <= menu_seconds <= 1800:
        raise ConfigError("menu_seconds must be 30..1800 (the app clamps to that)")
    text = _path(clone).read_text(encoding="utf-8")
    cfg = json.loads(text)
    old = cfg.get("autotest") if isinstance(cfg.get("autotest"), dict) else {}
    branch = steam_branch or old.get("branch") or "beta"
    if branch not in STEAM_BRANCHES:
        raise ConfigError(f"autotest.branch is the Steam branch, one of {STEAM_BRANCHES}")
    if old.get("id") == test_id:
        raise ConfigError(f"autotest id {test_id!r} already queued; the app runs an id once, use a new one")
    new = dict(old)   # keep keys we don't set
    new.update({"id": test_id, "app": int(app or old.get("app") or STS2_APPID), "menu_seconds": menu_seconds,
                "branch": branch})
    if repeat_hours is not None:
        new["repeat_hours"] = int(repeat_hours)
    if build:
        new["build"] = build
    elif "build" in new:
        del new["build"]
    cfg["autotest"] = new
    return _write(clone, text, cfg)


def _git(clone, *args, timeout=120):
    return subprocess.run(["git", "-C", str(clone), *args], capture_output=True, text=True, timeout=timeout)


def commit_and_push(clone, message, paths=(PATH,), retries=4, sleep=time.sleep):
    """git pull --rebase (autostash), commit `paths`, push; on a rejected push pull and retry."""
    r = _git(clone, "pull", "-q", "--rebase", "--autostash")
    if r.returncode:
        _git(clone, "rebase", "--abort")
        raise ConfigError(f"pull failed: {r.stderr.strip()[-300:]}")
    _git(clone, "add", "--", *paths)
    if _git(clone, "diff", "--cached", "--quiet").returncode == 0:
        return "nothing to commit"
    r = _git(clone, "commit", "-q", "-m", message, "--", *paths)
    if r.returncode:
        raise ConfigError(f"commit failed: {r.stderr.strip()[-300:]}")
    for attempt in range(retries):
        r = _git(clone, "push", "-q")
        if r.returncode == 0:
            return _git(clone, "rev-parse", "--short", "HEAD").stdout.strip()
        sleep(2 ** attempt)
        p = _git(clone, "pull", "-q", "--rebase")
        if p.returncode:
            _git(clone, "rebase", "--abort")
            raise ConfigError(f"rebase onto the remote failed: {p.stderr.strip()[-300:]}")
    raise ConfigError(f"push failed after {retries} tries: {r.stderr.strip()[-300:]}")


if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--telemetry", default="/home/claude/kestrel-telemetry")
    sub = p.add_subparsers(dest="cmd", required=True)
    a1 = sub.add_parser("park_flag"); a1.add_argument("flag"); a1.add_argument("reason")
    a2 = sub.add_parser("queue_autotest"); a2.add_argument("id"); a2.add_argument("--build")
    a2.add_argument("--menu-seconds", type=int, default=300)
    a3 = sub.add_parser("commit_and_push"); a3.add_argument("message")
    a = p.parse_args()
    try:
        if a.cmd == "park_flag":
            print(park_flag(a.telemetry, a.flag, a.reason) or "flag not active; nothing changed")
        elif a.cmd == "queue_autotest":
            print(queue_autotest(a.telemetry, a.id, a.build, a.menu_seconds))
        else:
            print(commit_and_push(a.telemetry, a.message))
    except ConfigError as e:
        raise SystemExit(f"error: {e}")
