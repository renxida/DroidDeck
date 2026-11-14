#!/usr/bin/env python3
"""Signature x run history: bisect along perf/switchboard first-parent history, experiment-flag
association, and per-commit metric step changes.

Input is a list of verdicts (triage.py schema). Commits come from `git log --first-parent` of the
switchboard branch in the fork clone; a shallow clone is deepened with one bounded
`git fetch --depth=300 origin perf/switchboard` when a run's commit is missing (never --unshallow).

A run is placed on the history by its commit; a run built from a commit that is not on the
first-parent line (a side branch) still counts for run totals and flag association, not for bisect.
"""
import json
import math
import re
import statistics
import subprocess

STAGES = ["app", "setup", "steam_ui", "steam_signed_in", "launch_requested", "game_exec",
          "game_first_frame", "in_game"]
SEVERITY = {"fail": 3, "degrade": 2, "info": 1}
# metric -> (direction a regression goes, minimum absolute change, minimum relative change)
METRICS = {
    "fps_p50": (-1, 2.0, 0.10),
    "frametime_p99_ms": (+1, 5.0, 0.15),
    "steam_ui_s": (+1, 3.0, 0.20),
    "game_first_frame_s": (+1, 3.0, 0.20),
}
BRANCH = "perf/switchboard"
FETCH_DEPTH = 300
MAX_SUSPECTS = 25


def stage_rank(stage):
    try:
        return STAGES.index(stage)
    except ValueError:
        return -1


# ---------------------------------------------------------------- git

def _git(repo, *args, timeout=120):
    r = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout, r.stderr


def _log(repo, ref):
    code, out, _ = _git(repo, "log", "--first-parent", "--format=%H%x09%s", ref)
    if code:
        return None
    rows = []
    for line in out.splitlines():
        sha, _, subject = line.partition("\t")
        rows.append({"sha": sha, "short": sha[:7], "subject": subject})
    return rows


def _resolve_ref(repo, ref):
    for cand in ([ref] if ref else []) + ["refs/remotes/origin/perf/switchboard", "refs/heads/switchboard", "HEAD"]:
        code, out, _ = _git(repo, "rev-parse", "--verify", "-q", cand + "^{commit}")
        if code == 0:
            return cand, out.strip()
    return None, None


def first_parent(repo, ref=None, need=(), fetch=True, always_fetch=False):
    """Newest-first [{sha, short, subject}] of the switchboard first-parent line, and info.

    need: commit prefixes that runs were built from. When some are missing (or always_fetch) and
    fetch is true, one bounded `git fetch --depth=300 origin perf/switchboard` is made and the
    fetched tip is logged instead (FETCH_HEAD only: no ref, checkout or working-tree change).
    info: ref used, tip sha (the remote tip when fetched), fetched, fetch_error, missing.
    """
    info = {"ref": None, "tip": None, "fetched": False, "fetch_error": None}
    name, sha = _resolve_ref(repo, ref)
    commits = (_log(repo, name) if name else None) or []
    info["ref"], info["tip"] = name, sha
    missing = [c for c in need if c and not any(r["sha"].startswith(c) for r in commits)]
    if fetch and (missing or always_fetch):
        code, _, err = _git(repo, "fetch", "-q", f"--depth={FETCH_DEPTH}", "origin", BRANCH, timeout=300)
        if code == 0:
            info["fetched"] = True
            c2, tip, _ = _git(repo, "rev-parse", "FETCH_HEAD")
            tip = tip.strip()
            more = _log(repo, tip) if c2 == 0 else None
            if more:
                commits = more
                info["ref"], info["tip"] = f"origin {BRANCH}", tip
        else:
            info["fetch_error"] = err.strip()[-300:]
    info["missing"] = [c for c in need if c and not any(r["sha"].startswith(c) for r in commits)]
    info["commits"] = len(commits)
    return commits, info


# ---------------------------------------------------------------- verdict helpers

def run_commit(v):
    c = v.get("commit")
    if c:
        return str(c)
    slug = v.get("slug") or _slug_from_run(v.get("run", ""))
    m = re.search(r"-([0-9a-f]{7,40})$", slug or "")
    return m.group(1) if m else None


def _slug_from_run(run):
    parts = str(run).split("/")
    return parts[1] if len(parts) >= 4 and parts[0] == "runs" else None


def sig_ids(v, min_severity="info"):
    floor = SEVERITY.get(min_severity, 1)
    return {s["id"] for s in v.get("signatures") or [] if s.get("id") and SEVERITY.get(s.get("severity"), 1) >= floor}


def place(verdicts, commits):
    """run -> first-parent index (0 = oldest) or None."""
    oldest_first = list(reversed(commits))
    index = {}
    for i, c in enumerate(oldest_first):
        index[c["sha"]] = i
    out = {}
    for v in verdicts:
        c = run_commit(v)
        pos = None
        if c:
            for sha, i in index.items():
                if sha.startswith(c) or c.startswith(sha):
                    pos = i
                    break
        out[v["run"]] = pos
    return out, oldest_first


def table(verdicts):
    """signature -> {run: severity} plus the run list."""
    t = {}
    for v in verdicts:
        for s in v.get("signatures") or []:
            if s.get("id"):
                t.setdefault(s["id"], {})[v["run"]] = s.get("severity", "info")
    return t


# ---------------------------------------------------------------- bisect

def bisect(sig, verdicts, commits, placement=None):
    if placement is None:
        placement, oldest = place(verdicts, commits)
    else:
        oldest = list(reversed(commits))
    bad_runs = [v for v in verdicts if sig in sig_ids(v)]
    threshold = min((stage_rank(v.get("stage")) for v in bad_runs), default=-1)
    # a run without the signature only counts as good if it got at least as far as a bad run did
    good_runs = [v for v in verdicts if sig not in sig_ids(v) and stage_rank(v.get("stage")) >= threshold]
    per = {}
    for v in bad_runs:
        i = placement.get(v["run"])
        if i is not None:
            per.setdefault(i, {"bad": 0, "good": 0})["bad"] += 1
    for v in good_runs:
        i = placement.get(v["run"])
        if i is not None:
            per.setdefault(i, {"bad": 0, "good": 0})["good"] += 1
    out = {
        "runs_seen": len(bad_runs),
        "runs_not_seen": len(good_runs),
        "runs_total": len(verdicts),
        "unplaced_bad_runs": sum(1 for v in bad_runs if placement.get(v["run"]) is None),
        "first_bad": None, "last_good": None, "range": [], "range_size": 0, "intermittent": False,
        "not_seen_since": None, "per_commit": [],
    }
    for i in sorted(per):
        out["per_commit"].append({"commit": oldest[i]["short"], "bad": per[i]["bad"], "good": per[i]["good"]})
    bad_idx = sorted(i for i, c in per.items() if c["bad"])
    if not bad_idx:
        return out
    newest_bad = bad_idx[-1]
    good_before = [i for i, c in per.items() if not c["bad"] and c["good"] and i < newest_bad]
    last_good = max(good_before) if good_before else None
    first_bad = min(i for i in bad_idx if last_good is None or i > last_good)
    out["first_bad"] = _commit(oldest[first_bad])
    out["intermittent"] = any(i < (last_good if last_good is not None else -1) for i in bad_idx) or \
        any(per[i]["good"] for i in bad_idx)
    if last_good is not None:
        out["last_good"] = _commit(oldest[last_good])
        rng = oldest[last_good + 1:first_bad + 1]
    else:
        rng = oldest[:first_bad + 1]
        out["range_open"] = True   # no good run before it on this history: cause may be older
    out["range_size"] = len(rng)
    out["range"] = [_commit(c) for c in reversed(rng)][:MAX_SUSPECTS]   # newest first, like git log
    later_good = [i for i, c in per.items() if not c["bad"] and c["good"] and i > newest_bad]
    if later_good:
        out["not_seen_since"] = _commit(oldest[min(later_good)])
    return out


def _commit(c):
    return {"sha": c["short"], "subject": c["subject"]}


# ---------------------------------------------------------------- flags

def fisher_greater(on_bad, on_total, off_bad, off_total):
    """One-sided Fisher exact p: the chance of on_bad or more bad runs among the flag-on runs if the
    flag had nothing to do with it (hypergeometric tail)."""
    bad, n = on_bad + off_bad, on_total + off_total
    denom = math.comb(n, on_total)
    return sum(math.comb(bad, k) * math.comb(n - bad, on_total - k)
               for k in range(on_bad, min(bad, on_total) + 1)) / denom


def flag_association(sig, verdicts, min_bad=2, min_split=0.5, max_p=0.1):
    bad_runs = [v for v in verdicts if sig in sig_ids(v)]
    threshold = min((stage_rank(v.get("stage")) for v in bad_runs), default=-1)
    pop = [v for v in verdicts if sig in sig_ids(v) or stage_rank(v.get("stage")) >= threshold]
    flags = sorted({f for v in pop for f in (v.get("experiments") or [])})
    rows, suspects = [], []
    for f in flags:
        on = [v for v in pop if f in (v.get("experiments") or [])]
        off = [v for v in pop if f not in (v.get("experiments") or [])]
        on_bad = sum(1 for v in on if sig in sig_ids(v))
        off_bad = sum(1 for v in off if sig in sig_ids(v))
        p_on = on_bad / len(on) if on else None
        p_off = off_bad / len(off) if off else None
        row = {"flag": f, "on_bad": on_bad, "on_total": len(on), "off_bad": off_bad, "off_total": len(off),
               "p_on": _r(p_on), "p_off": _r(p_off),
               "fisher_p": _r(fisher_greater(on_bad, len(on), off_bad, len(off))) if on and off else None,
               "_runs": frozenset(v["run"] for v in on)}
        rows.append(row)
        # a suspect needs runs on both sides, at least min_bad bad runs with the flag on, a clear
        # split, and a split unlikely by chance (a flag in every run can never be told apart)
        if on and off and on_bad >= min_bad and p_on - p_off >= min_split and row["fisher_p"] <= max_p:
            suspects.append(row)
    # flags that were on in exactly the same runs cannot be told apart: say so
    for r in suspects:
        r["confounded_with"] = sorted(o["flag"] for o in suspects if o is not r and o["_runs"] == r["_runs"])
    for r in rows:
        r.pop("_runs", None)
    rows.sort(key=lambda r: -((r["p_on"] or 0) - (r["p_off"] or 0)))
    suspects.sort(key=lambda r: -((r["p_on"] or 0) - (r["p_off"] or 0)))
    return {"suspects": suspects, "flags": rows[:8]}


def _r(x):
    return None if x is None else round(x, 3)


# ---------------------------------------------------------------- metrics

def metric_regressions(verdicts, commits, placement=None, lookback=3):
    """Per first-parent commit, the median of each metric; a step change in the bad direction versus
    the pooled runs of up to `lookback` previous commits with data is a regression."""
    if placement is None:
        placement, oldest = place(verdicts, commits)
    else:
        oldest = list(reversed(commits))
    out = []
    for metric, (direction, min_abs, min_rel) in METRICS.items():
        by = {}
        for v in verdicts:
            i = placement.get(v["run"])
            val = (v.get("metrics") or {}).get(metric)
            if i is None or not isinstance(val, (int, float)):
                continue
            by.setdefault(i, []).append((float(val), v))
        idx = sorted(by)
        for k, i in enumerate(idx):
            prev = idx[max(0, k - lookback):k]
            if not prev:
                continue
            base_vals = [x for j in prev for x, _ in by[j]]
            base = statistics.median(base_vals)
            cur = statistics.median(x for x, _ in by[i])
            delta = (cur - base) * direction
            if delta >= min_abs and base and delta / abs(base) >= min_rel:
                flags_now = set.intersection(*[set(v.get("experiments") or []) for _, v in by[i]])
                flags_before = set.union(*[set(v.get("experiments") or []) for j in prev for _, v in by[j]])
                flags_then = set.intersection(*[set(v.get("experiments") or []) for j in prev for _, v in by[j]])
                out.append({
                    "metric": metric, "commit": oldest[i]["short"], "subject": oldest[i]["subject"],
                    "value": round(cur, 2), "baseline": round(base, 2), "runs": len(by[i]),
                    "baseline_runs": len(base_vals), "baseline_commits": [oldest[j]["short"] for j in prev],
                    "change_pct": round(100 * (cur - base) / abs(base), 1),
                    "range": [_commit(c) for c in reversed(oldest[prev[-1] + 1:i + 1])][:MAX_SUSPECTS],
                    "flags_added": sorted(flags_now - flags_before),
                    "flags_dropped": sorted(flags_then - flags_now),
                })
    return out


# ---------------------------------------------------------------- all together

def analyze(verdicts, commits, min_severity="degrade"):
    placement, _ = place(verdicts, commits)
    sigs = sorted({s for v in verdicts for s in sig_ids(v, min_severity)})
    report = {}
    for s in sigs:
        b = bisect(s, verdicts, commits, placement)
        b["flags"] = flag_association(s, verdicts)
        report[s] = b
    return {"signatures": report, "metric_regressions": metric_regressions(verdicts, commits, placement),
            "placed_runs": sum(1 for v in placement.values() if v is not None), "runs": len(verdicts)}


if __name__ == "__main__":
    import argparse
    import sys
    p = argparse.ArgumentParser(description="History report from a JSON list of verdicts (stdin or file)")
    p.add_argument("verdicts", nargs="?")
    p.add_argument("--repo", default="/home/claude/droiddeck")
    p.add_argument("--no-fetch", action="store_true")
    a = p.parse_args()
    vs = json.load(open(a.verdicts) if a.verdicts else sys.stdin)
    cs, info = first_parent(a.repo, need=sorted({run_commit(v) for v in vs if run_commit(v)}), fetch=not a.no_fetch)
    print(json.dumps({"git": info, **analyze(vs, cs)}, indent=2))
