#!/usr/bin/env python3
"""One autofix sweep: new telemetry zips + new failed CI runs -> ledger events -> work items.

  sweep.py [--telemetry /home/claude/kestrel-telemetry] [--repo /home/claude/droiddeck]
           [--dry-run] [--json] [--no-pull] [--no-ci] [--no-fetch] [--push]

Steps: pull the telemetry clone; triage every runs/**/*.zip whose (path, blob sha) the ledger has
not seen (a checkpoint zip is rewritten in place, so a new blob at a known path is new evidence);
collect failed CI runs since the ledger's cursor; infer `promoted` for items whose
ready_to_promote sha (or a commit with its subject) is now on origin/perf/switchboard; verify
promoted items against new runs on or after the fix; write ledger events (not with --dry-run) and
commit the ledger in the telemetry clone (--push also pushes it); print one work item per
signature that is new, regressed (seen again after verified/closed, or after its fix landed), or
still open with new evidence.

This never pushes the fork and never checks out or stashes anything in it (the working tree is
shared): it only runs git log / rev-parse / merge-base and one bounded fetch there.
"""
import argparse
import datetime as _dt
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import ci  # noqa: E402
import history  # noqa: E402
from ledger import DONE, Ledger, compact_verdict, ledger_path, run_key  # noqa: E402

ESCALATE = re.compile(r"steam-?guard|(^|-)(sign-?in|log-?in|ownership|drm|account|license)(-|$)", re.I)
MIN_PERF_RUNS = 2
PARK_P = 0.05
OWNERS = [("fex", "fex"), ("native", "native"), ("dotnet", "dotnet"), ("clr", "dotnet"), ("r2r", "dotnet"),
          ("graphics", "graphics"), ("dxvk", "graphics"), ("vulkan", "graphics"), ("hub", "hub")]
MAINLINE = ("perf/switchboard", "main")
SEV_RANK = {"fail": 3, "degrade": 2, "info": 1}
SHOW_RANGE = 10


def _git(repo, *args, timeout=120):
    r = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout, r.stderr


def list_zips(telemetry):
    code, out, err = _git(telemetry, "ls-files", "-s", "--", "runs")
    if code:
        raise RuntimeError(f"git ls-files: {err.strip()}")
    rows = []
    for line in out.splitlines():
        meta, _, path = line.partition("\t")
        if path.endswith(".zip"):
            rows.append((path, meta.split()[1]))
    return rows


def run_meta(path):
    """runs/<branch-slug>-<commit7>/<device>/<utc>.zip -> fields a verdict may lack."""
    parts = path.split("/")
    slug = parts[1] if len(parts) >= 4 else None
    m = re.match(r"^(.*)-([0-9a-f]{7,40})$", slug or "")
    return {"slug": slug, "commit": m.group(2) if m else None, "branch_slug": m.group(1) if m else None,
            "device": parts[2] if len(parts) >= 4 else None,
            "started_utc": parts[-1][:-4] if path.endswith(".zip") else None}


def load_triage():
    import triage  # lazy: another tool owns it and it may not exist yet
    return triage.triage_zip


def triage_new(telemetry, todo, triage_fn):
    verdicts, errors = [], []
    for path, blob in todo:
        try:
            v = triage_fn(str(Path(telemetry) / path))
            if not isinstance(v, dict):
                raise TypeError(f"triage returned {type(v).__name__}")
        except Exception as e:  # noqa: BLE001 - one bad zip must not stop the sweep
            errors.append({"run": path, "blob": blob, "error": f"{type(e).__name__}: {e}"[:300]})
            continue
        v = dict(v)
        meta = run_meta(path)
        v["run"] = path
        v["blob"] = blob
        for k in ("slug", "commit", "device", "started_utc"):
            if not v.get(k) and meta.get(k):
                v[k] = meta[k]
        verdicts.append(compact_verdict(v))
    return verdicts, errors


def ci_owner(branch, workflow_file, cls):
    stem = re.sub(r"\.ya?ml$", "", (workflow_file or "").rsplit("/", 1)[-1])
    if cls != "env" and branch in MAINLINE:
        return "main"
    for k in ([stem, branch or ""] if cls == "env" else [branch or "", stem]):
        for word, owner in OWNERS:
            if word in k.lower():
                return owner
    return "main"


# ---------------------------------------------------------------- promote / verify

def remote_log(repo, tip, n=400):
    if not tip:
        return []
    code, out, _ = _git(repo, "log", f"-n{n}", "--format=%H%x09%s", tip)
    return [tuple(l.split("\t", 1)) for l in out.splitlines() if "\t" in l] if code == 0 else []


def subject_of(repo, sha, api=None):
    code, out, _ = _git(repo, "log", "-1", "--format=%s", sha)
    if code == 0 and out.strip():
        return out.strip()
    if api:
        try:
            return (api(f"/repos/{ci.REPO}/commits/{sha}").get("commit", {}).get("message") or "").splitlines()[0]
        except Exception:  # noqa: BLE001
            return None
    return None


def infer_promoted(state, repo, tip, api=None, log=None):
    """[(sig, commit)] for ready_to_promote items whose sha or subject is on the remote branch."""
    log = log if log is not None else remote_log(repo, tip)
    out = []
    for sig, it in state.items.items():
        ready = it.get("ready")
        if it["status"] != "ready_to_promote" or not ready:
            continue
        sha = ready.get("sha") or ""
        hit = next((h for h, _ in log if sha and (h.startswith(sha) or sha.startswith(h))), None)
        if not hit:
            subj = ready.get("subject") or subject_of(repo, sha, api)
            if subj:
                hit = next((h for h, s in log if s == subj), None)
        if hit:
            out.append((sig, hit[:12]))
    return out


def is_ancestor(repo, a, b):
    return _git(repo, "merge-base", "--is-ancestor", a, b)[0] == 0


def verify(state, new_verdicts, repo, promoted_now=()):
    """For promoted items: a new run built on/after the fix that got as far as the signature's stage.
    Returns (verified [(sig, run)], failed [(sig, run)])."""
    promoted = {s: it["promoted"] for s, it in state.items.items() if it["status"] == "promoted" and it["promoted"]}
    promoted.update(dict(promoted_now))
    ok, bad = [], []
    for sig, commit in promoted.items():
        floor = history.stage_rank(state.items.get(sig, {}).get("stage"))
        for v in sorted(new_verdicts, key=lambda v: v.get("started_utc") or ""):
            c = history.run_commit(v)
            if not c or not is_ancestor(repo, commit, c):
                continue
            if sig in history.sig_ids(v):
                bad.append((sig, v["run"]))
                break
            if history.stage_rank(v.get("stage")) >= floor:
                ok.append((sig, v["run"]))
                break
    return ok, bad


# ---------------------------------------------------------------- items

def telemetry_items(state, new_verdicts, all_verdicts, hist, failed_verify, active=None):
    by_sig = {}
    for v in new_verdicts:
        for s in v.get("signatures") or []:
            if s.get("id") and SEV_RANK.get(s.get("severity"), 1) >= SEV_RANK["degrade"]:
                by_sig.setdefault(s["id"], []).append((v, s))
    failed_sigs = {s for s, _ in failed_verify}
    items = []
    for sig, hits in by_sig.items():
        status = state.item_status(sig)
        if status is None:
            kind = "new"
        elif status in DONE:
            kind = "regressed"
        elif sig in failed_sigs:
            kind = "failed-verify"
        else:
            kind = "open"
        all_hits = [(v, s) for v in all_verdicts for s in v.get("signatures") or [] if s.get("id") == sig]
        newest_v, newest_s = max(hits, key=lambda h: h[0].get("started_utc") or "")
        sev = max((s.get("severity") for _, s in all_hits), key=lambda x: SEV_RANK.get(x, 0))
        owner = next((s.get("owner") for _, s in reversed(hits) if s.get("owner")), None) or \
            (state.items.get(sig) or {}).get("owner") or "main"
        b = hist["signatures"].get(sig, {})
        flags = (b.get("flags") or {}).get("suspects") or []
        bad_commits = {r["sha"] for r in b.get("range") or []}
        regs = [r for r in hist["metric_regressions"] if r["commit"] in bad_commits]
        item = {
            "sig": sig, "source": "telemetry", "kind": kind, "status": status, "severity": sev, "owner": owner,
            "runs": len({v["run"] for v, _ in all_hits}), "new_runs": len({v["run"] for v, _ in hits}),
            "runs_without": b.get("runs_not_seen"),
            "newest_run": newest_v["run"], "newest_stage": newest_v.get("stage"),
            "evidence": list(newest_s.get("evidence") or [])[:6],
            "first_seen": min((v.get("started_utc") or "" for v, _ in all_hits), default=None),
            "stage": min((v.get("stage") for v, _ in all_hits if v.get("stage")),
                         key=history.stage_rank, default=None),
            "bisect": {k: b.get(k) for k in ("first_bad", "last_good", "range_size", "intermittent",
                                             "not_seen_since", "unplaced_bad_runs", "range_open")}
            | {"range": (b.get("range") or [])[:SHOW_RANGE]},
            "suspect_flags": flags,
            "metric_regressions": regs,
        }
        item["action"], item["next"] = suggest(item, active)
        items.append(item)
    return items


def active_flags(telemetry):
    """Flags the app turns on from the current config, or None when it can't be read."""
    try:
        import config
        exp = config.load(telemetry).get("experiments") or {}
    except Exception:  # noqa: BLE001
        return None
    rows = exp.get("rotate") or []
    on = set(exp.get("always") or [])
    on |= {f for row in rows for f in row} if rows else set(exp.get("on") or [])
    return on


def suggest(item, active=None):
    nxt = []
    if item["severity"] == "info":
        return "ignore", nxt
    if ESCALATE.search(item["sig"]):
        return "escalate-cedar (Steam account, sign-in or ownership: never worked around)", nxt
    b = item["bisect"]
    from config import PINNED
    ranked = sorted(item["suspect_flags"], key=lambda f: (f.get("fisher_p") or 1, -(f.get("p_on") or 0)))
    suspects = [f["flag"] for f in ranked]
    # park only the strongest suspect, and only with strong support: a weaker flag that merely
    # co-occurs with a pinned or already-off one is not the cause
    best = ranked[0] if ranked else None
    parkable = [best["flag"]] if best and best["flag"] not in PINNED and (active is None or best["flag"] in active) \
        and (best.get("fisher_p") or 1) <= PARK_P else []
    if any(f in PINNED for f in suspects):
        nxt.append("native-engine is a suspect but pinned by the owner: fix forward, do not park")
    idle = [f for f in suspects if f not in PINNED and active is not None and f not in active]
    if idle:
        nxt.append(f"suspect flag(s) {', '.join(idle)} already off in the current config: nothing to park")
    weak = [f for f in suspects if f not in PINNED and f not in idle and f not in parkable]
    if weak:
        nxt.append(f"weaker suspect flag(s) {', '.join(weak)}: confirm with an autotest before parking")
    if b.get("range_size") == 1 and b.get("last_good"):
        fix = f"fix (one suspect commit {b['first_bad']['sha']}: {b['first_bad']['subject']})"
    elif b.get("range_size", 0) > 1:
        lg = b["last_good"]["sha"] if b.get("last_good") else "history start"
        fix = f"bisect {lg}..{b['first_bad']['sha']} ({b['range_size']} commits)"
    else:
        fix = "fix (no bisect range: runs not on perf/switchboard first-parent history)"
    route = item["owner"] not in (None, "main")
    if item["kind"] == "failed-verify":
        nxt.insert(0, "the promoted fix did not hold; two failed verifies -> escalate to the owner thread")
    if parkable:
        nxt += ([f"route to owner thread {item['owner']}"] if route else []) + [fix]
        return f"park-flag {parkable[0]}", nxt
    if route:
        return f"route-to-owner {item['owner']}", nxt + [fix]
    return fix, nxt


def ci_items(state, records, prior):
    by_sig = {}
    for r in records:
        by_sig.setdefault(r["sig"], []).append(r)
    items = []
    for sig, recs in by_sig.items():
        live = [r for r in recs if not r.get("already_fixed")]
        if not live:
            continue
        fixed_main = [r for r in recs if r.get("already_fixed") and r["branch"] in MAINLINE]
        if fixed_main and not any(r["branch"] in MAINLINE for r in live) and recs[0]["cls"] != "env":
            continue   # fixed on the mainline; the other branches pick the fix up when they merge it
        status = state.item_status(sig)
        kind = "new" if status is None else "regressed" if status in DONE else "open"
        newest = max(live, key=lambda r: r.get("created_at") or "")
        cls = newest["cls"]
        older = [p for p in prior if p.get("sig") == sig]
        branches = sorted({r["branch"] for r in recs} | {p.get("branch") for p in older if p.get("branch")})
        owner = ci_owner(newest["branch"], newest.get("workflow_file"), cls)
        if cls == "infra":
            action, sev = "ignore-infra (one re-run of the failed jobs, no code change)", "info"
        elif cls == "env":
            action, sev = (f"fix the workflow environment once ({newest.get('workflow_file')}): same failure on "
                           f"{len(branches)} branches; pin or adapt the toolchain/image"), "fail"
        elif any(r["branch"] in MAINLINE for r in live):
            owner = "main"
            spread = [b for b in branches if b not in MAINLINE]
            action, sev = "fix" + (f" on perf/switchboard (also failing on {len(spread)} branches that merged it)"
                                   if spread else ""), "fail"
        elif owner == "main":
            action, sev = f"fix on {newest['branch']} (no owner thread matches the branch)", "degrade"
        else:
            action, sev = f"route-to-owner {owner} (failure on its branch {newest['branch']})", "degrade"
        items.append({
            "sig": sig, "source": "ci", "kind": kind, "status": status, "severity": sev, "owner": owner,
            "class": cls, "reason": newest.get("reason"), "runs": len(recs) + len(older), "new_runs": len(recs),
            "still_failing": len(live), "branches": branches, "workflow": newest.get("workflow"),
            "newest_run": newest["run_id"], "newest_sha": (newest.get("sha") or "")[:7],
            "failed_step": newest.get("failed_step"),
            "evidence": ([newest["error_line"]] if newest.get("error_line") else []) + (newest.get("tail") or [])[-5:],
            "log_error": newest.get("log_error"),
            "first_seen": min(r.get("created_at") or "" for r in recs),
            "action": action, "next": [],
        })
    return items


def perf_items(state, hist, new_runs_commits):
    items = []
    for r in hist["metric_regressions"]:
        if r["runs"] < MIN_PERF_RUNS or r["baseline_runs"] < MIN_PERF_RUNS:
            continue   # one run against one run is noise; stays in the history output
        sig = f"perf:{r['metric']}:{r['commit']}"
        status = state.item_status(sig)
        if status is not None and r["commit"] not in new_runs_commits:
            continue   # known, and no new run at that commit
        kind = "new" if status is None else "regressed" if status in DONE else "open"
        flags = r["flags_added"] or r["flags_dropped"]
        action = (f"check experiment change first (added {r['flags_added']}, dropped {r['flags_dropped']}), then "
                  if flags else "") + (f"bisect {r['baseline_commits'][-1]}..{r['commit']} ({len(r['range'])} commits)"
                                       if len(r["range"]) > 1 else f"fix (one commit {r['commit']})")
        items.append({"sig": sig, "source": "metrics", "kind": kind, "status": status, "severity": "degrade",
                      "owner": "main", "runs": r["runs"], "new_runs": None,
                      "evidence": [f"{r['metric']} median {r['value']} vs {r['baseline']} over "
                                   f"{r['baseline_runs']} runs at {', '.join(r['baseline_commits'])} "
                                   f"({r['change_pct']:+}%)"],
                      "bisect": {"range": r["range"][:SHOW_RANGE], "range_size": len(r["range"])},
                      "metric_regressions": [r], "action": action, "next": []})
    return items


# ---------------------------------------------------------------- main

def sweep(telemetry, repo, dry_run=False, pull=True, use_ci=True, fetch=True, triage_fn=None,
          ci_collect=None, today=None):
    telemetry, repo = Path(telemetry), Path(repo)
    out = {"generated": today or ledger_now(), "dry_run": dry_run, "warnings": []}
    if pull:
        code, _, err = _git(telemetry, "pull", "-q", "--rebase", "--autostash", timeout=300)
        if code:
            out["warnings"].append(f"telemetry pull failed: {err.strip()[-200:]}")
    led = Ledger(ledger_path(telemetry))
    state = led.state()
    zips = list_zips(telemetry)
    todo = [(p, b) for p, b in zips if run_key(p, b) not in state.processed_runs]
    out["zips"] = len(zips)
    out["new_zips"] = len(todo)
    new_verdicts, errors = [], []
    if todo:
        if triage_fn is None:
            try:
                triage_fn = load_triage()
            except Exception as e:  # noqa: BLE001
                out["warnings"].append(f"triage unavailable ({type(e).__name__}: {e}); zips left for next sweep")
                todo = []
        if todo:
            new_verdicts, errors = triage_new(telemetry, todo, triage_fn)
    out["triage_errors"] = errors

    records, ci_error = [], None
    since = ci.cursor_since(state.ci_cursor)
    if use_ci:
        try:
            records = (ci_collect or ci.collect)(since=since, processed=state.ci_run_ids)
        except Exception as e:  # noqa: BLE001
            ci_error = f"{type(e).__name__}: {e}"[:300]
    prior = [dict(e) for e in state.ci_failures.values()]
    if records:
        ci.mark_spread(records + prior)   # a breakage spreading across sweeps is still one environment
    out["ci"] = {"since": since, "error": ci_error, "new_failures": [
        {k: r.get(k) for k in ("run_id", "workflow", "branch", "sha", "created_at", "cls", "reason", "sig",
                               "failed_step", "error_line", "already_fixed", "log_error")} for r in records]}

    verdicts = dict(state.verdicts)
    for v in new_verdicts:
        verdicts[v["run"]] = v
    allv = list(verdicts.values())
    need = sorted({history.run_commit(v) for v in allv if history.run_commit(v)})
    commits, ginfo = history.first_parent(repo, need=need, fetch=fetch, always_fetch=fetch)
    out["git"] = ginfo
    hist = history.analyze(allv, commits)

    tip = ginfo.get("tip")
    promoted_now = infer_promoted(state, repo, tip, api=ci.gh_json if use_ci else None)
    verified, failed_verify = verify(state, new_verdicts, repo, promoted_now)
    out["promoted_inferred"] = [{"sig": s, "commit": c} for s, c in promoted_now]
    out["verified"] = [{"sig": s, "run": r} for s, r in verified]
    out["failed_verify"] = [{"sig": s, "run": r} for s, r in failed_verify]

    new_commits = set()
    placement, oldest = history.place(new_verdicts, commits)
    for run, i in placement.items():
        if i is not None:
            new_commits.add(oldest[i]["short"])
    items = (telemetry_items(state, new_verdicts, allv, hist, failed_verify, active_flags(telemetry))
             + ci_items(state, records, prior)
             + perf_items(state, hist, new_commits))
    verified_sigs = {s for s, _ in verified}
    items = [i for i in items if not (i["sig"] in verified_sigs and i["kind"] == "open")]
    items.sort(key=lambda i: (-SEV_RANK.get(i["severity"], 0), i["kind"] != "new", -(i.get("runs") or 0)))
    out["items"] = items
    out["history"] = {"placed_runs": hist["placed_runs"], "runs": hist["runs"],
                      "metric_regressions": hist["metric_regressions"]}

    events = []
    for v in new_verdicts:
        events.append(("triaged", {"run": v["run"], "blob": v["blob"], "verdict": v}))
    for r in records:
        events.append(("ci_failed", {k: r.get(k) for k in (
            "run_id", "workflow", "workflow_file", "workflow_id", "branch", "sha", "conclusion", "created_at",
            "url", "jobs", "failed_step", "error_line", "tail", "cls", "reason", "sig", "already_fixed",
            "log_error")}))
    for s, c in promoted_now:
        events.append(("promoted", {"sig": s, "commit": c, "inferred": True}))
    for s, r in verified:
        events.append(("verified", {"sig": s, "run": r}))
    for it in items:
        if it["kind"] in ("new", "regressed", "failed-verify"):
            events.append(("item_opened", {"sig": it["sig"], "first_seen": it.get("first_seen") or out["generated"],
                                           "owner": it["owner"], "stage": it.get("stage"),
                                           "reopened": it["kind"] != "new", "why": it["kind"]}))
    out["ledger_events"] = len(events)
    if not dry_run and events:
        from ledger import make_event
        led.append_many([make_event(k, **f) for k, f in events])
    return out


def ledger_now():
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def commit_ledger(telemetry, push=False):
    rel = str(ledger_path(telemetry).relative_to(telemetry))
    msg = "autofix: sweep ledger"
    if push:
        import config
        return config.commit_and_push(telemetry, msg, paths=(rel,))
    _git(telemetry, "add", "--", rel)
    if _git(telemetry, "diff", "--cached", "--quiet", "--", rel)[0] == 0:
        return "nothing to commit"
    code, _, err = _git(telemetry, "commit", "-q", "-m", msg, "--", rel)
    return "committed (not pushed)" if code == 0 else f"commit failed: {err.strip()}"


def render(out):
    lines = [f"autofix sweep {out['generated']}{' (dry run)' if out['dry_run'] else ''}: "
             f"{out['new_zips']} new of {out['zips']} zips, {len(out['ci']['new_failures'])} new CI failures, "
             f"{len(out['items'])} work items"]
    for w in out["warnings"] + ([f"CI: {out['ci']['error']}"] if out["ci"]["error"] else []):
        lines.append(f"  ! {w}")
    for e in out["triage_errors"]:
        lines.append(f"  ! triage {e['run']}: {e['error']}")
    for p in out["promoted_inferred"]:
        lines.append(f"  promoted {p['sig']} as {p['commit']}")
    for v in out["verified"]:
        lines.append(f"  verified {v['sig']} by {v['run']}")
    for it in out["items"]:
        lines.append("")
        lines.append(f"[{it['severity']}] {it['sig']}  ({it['kind']}, owner {it['owner']}, {it['runs']} runs)")
        lines.append(f"  action: {it['action']}")
        for n in it.get("next") or []:
            lines.append(f"  then:   {n}")
        b = it.get("bisect") or {}
        if b.get("first_bad"):
            lg = b["last_good"]["sha"] if b.get("last_good") else "-"
            lines.append(f"  first bad {b['first_bad']['sha']}, last good {lg}, {b.get('range_size')} commits"
                         f"{', intermittent' if b.get('intermittent') else ''}")
        for c in (b.get("range") or [])[:5]:
            lines.append(f"    {c['sha']} {c['subject']}")
        for f in it.get("suspect_flags") or []:
            lines.append(f"  suspect flag {f['flag']}: {f['on_bad']}/{f['on_total']} on vs {f['off_bad']}/{f['off_total']} off")
        if it.get("branches"):
            lines.append(f"  branches: {', '.join(it['branches'])}")
        for e in it.get("evidence") or []:
            lines.append(f"  | {e[:200]}")
    return "\n".join(lines)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--telemetry", default="/home/claude/kestrel-telemetry")
    p.add_argument("--repo", default="/home/claude/droiddeck")
    p.add_argument("--dry-run", action="store_true", help="write nothing to the ledger")
    p.add_argument("--json", action="store_true")
    p.add_argument("--no-pull", action="store_true")
    p.add_argument("--no-ci", action="store_true")
    p.add_argument("--no-fetch", action="store_true")
    p.add_argument("--no-commit", action="store_true", help="leave ledger events uncommitted")
    p.add_argument("--push", action="store_true", help="push the ledger commit")
    a = p.parse_args(argv)
    out = sweep(a.telemetry, a.repo, dry_run=a.dry_run, pull=not a.no_pull, use_ci=not a.no_ci,
                fetch=not a.no_fetch)
    if not a.dry_run and not a.no_commit and out["ledger_events"]:
        out["ledger_commit"] = commit_ledger(a.telemetry, push=a.push)
    print(json.dumps(out, indent=2) if a.json else render(out))


if __name__ == "__main__":
    main()
