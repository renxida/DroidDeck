#!/usr/bin/env python3
"""Failed GitHub Actions runs on the fork, classified and given a stable signature.

REST only (`gh api`; GraphQL does not work here). For each failed run, or a run cancelled because
one of its jobs failed: the failed jobs and step names, the tail of the failing job's log
(/actions/jobs/{id}/logs, which redirects to blob storage and may be unreachable: then only step
names are used), and a class:

  infra  runner, network, secrets, cancellation or timeout; re-run, no code change
  env    the same failure on several branches at once: the build environment moved (a toolchain or
         image update), fix the workflow, not each branch
  code   compile error, test failure, lint, a failing check
  unknown  nothing readable to go on

Signature: ci:<workflow file stem>:<failed step>:<first error line, normalized>. It names no branch,
so one breakage across branches is one item. "already fixed" means a later run of the same
workflow on the same branch succeeded.
"""
import json
import re
import subprocess
import concurrent.futures as cf
import datetime as _dt

REPO = "familiar-of-cedar/lanner"
MAX_LOGS_PER_RUN = 3
TAIL = 12
ENV_SPREAD = 3      # same signature on this many branches -> env

TS = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z ?")
ANSI = re.compile(r"\x1b\[[0-9;]*m|\[\d+(;\d+)*m")
INFRA = [re.compile(p, re.I) for p in (
    r"runner has received a shutdown signal", r"lost communication with the server",
    r"No space left on device", r"Could not resolve host", r"Temporary failure in name resolution",
    r"Connection (reset|refused|timed out)", r"TLS handshake timeout", r"API rate limit",
    r"\b50[234]\b.*(Bad Gateway|Service Unavailable|Gateway Time-?out)", r"HTTP (Error )?50[234]",
    r"Bad credentials", r"Resource not accessible by integration", r"Unable to (download|resolve) action",
    r"The operation was canceled", r"The job was not acquired", r"has exceeded the maximum execution time",
    r"secret[s]? .*(is not set|missing|empty)", r"remote: Internal Server Error", r"curl: \((6|7|28|35|56)\)",
    r"Received request to deprovision", r"registry (is )?busy", r"toomanyrequests", r"failed to (fetch|download) .*(timed out|refused|reset)",
)]
INFRA_STEPS = re.compile(r"^(set up job|complete job|pick runner|checkout|run actions/checkout|"
                         r"post |download artifact|upload artifact|cache|restore cache)", re.I)
# first match wins; ordered from most to least specific
CODE = [re.compile(p) for p in (
    r"CMake Error", r"\berror(\[\w+\])?:", r"^e: ", r"> \S.* FAILED$", r"^FAIL[: ]", r"^--- FAIL",
    r"AssertionError", r"Traceback \(most recent call last\)", r"Unhandled exception",
    r"Segmentation fault", r"Aborted", r"undefined reference", r"\bFAILED\b", r"BUILD FAILED", r"::error::",
    r"\b(assert|Assertion)\b", r"\bfatal:",
    r"(?i)\b(exited-without-\w+|failed|failure|timed out|timeout|hang|hung)\b",
)]
# a failure on several branches is the environment only if it also looks like the toolchain or image
ENV_HINT = re.compile(r"(?i)/usr/|/include/|c\+\+/\d|pacman|apt(-get)?\b|dnf\b|pip install|docker|registry|"
                      r"\bimage\b|toolchain|\bsdk\b|\bndk\b|cmake|arch linux|ubuntu|runner image|setup-")
GATE = re.compile(r"(?i)require .*(job|compilation)|every test job|all (required )?jobs|\bgate\b")
NOISE = re.compile(r"^(##\[(group|endgroup|warning)\]|shell: |env:|\s+[A-Z_]+: )")


# ---------------------------------------------------------------- gh api

def gh_json(path):
    r = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=90)
    if r.returncode:
        raise RuntimeError(f"gh api {path}: {(r.stderr or r.stdout).strip()[:200]}")
    return json.loads(r.stdout)


def gh_text(path):
    r = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=120)
    if r.returncode:
        raise RuntimeError(f"gh api {path}: {(r.stderr or r.stdout).strip()[:200]}")
    return r.stdout


# ---------------------------------------------------------------- listing

def list_runs(status, since, api=gh_json, repo=REPO, max_pages=5):
    out = []
    for page in range(1, max_pages + 1):
        q = f"/repos/{repo}/actions/runs?status={status}&per_page=100&page={page}"
        if since:
            q += f"&created=%3E%3D{since}"
        data = api(q)
        runs = data.get("workflow_runs") or []
        out.extend(runs)
        if len(runs) < 100:
            break
    return out


def run_jobs(run_id, api=gh_json, repo=REPO):
    data = api(f"/repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")
    jobs = []
    for j in data.get("jobs") or []:
        steps = [s.get("name") for s in j.get("steps") or [] if s.get("conclusion") == "failure"]
        jobs.append({"id": j.get("id"), "name": j.get("name"), "conclusion": j.get("conclusion"),
                     "failed_steps": steps})
    return jobs


# ---------------------------------------------------------------- logs

def clean(line):
    return ANSI.sub("", TS.sub("", line.rstrip("\r\n")))


def error_window(log):
    """The failing step's output: from the step's "##[group]Run" header (after its ##[endgroup])
    up to the first ##[error]. Returns (lines, error message)."""
    lines = [clean(l) for l in log.splitlines()]
    err = next((i for i, l in enumerate(lines) if l.startswith("##[error]")), None)
    if err is None:
        return lines[-200:], None
    head = max((i for i in range(err) if lines[i].startswith("##[group]Run ")), default=None)
    if head is None:
        start = max(0, err - 400)
    else:
        end = next((i for i in range(head, err) if lines[i].startswith("##[endgroup]")), head)
        start = end + 1
    return [l for l in lines[start:err] if not l.startswith(("##[group]", "##[endgroup]"))], \
        lines[err][len("##[error]"):].strip()


def first_error(window):
    for pat in CODE:
        for k, line in enumerate(window):
            if NOISE.match(line) or not line.strip():
                continue
            if pat.search(line):
                text = line.strip()
                # "CMake Error:" alone says little; take the next line that says what
                if re.fullmatch(r"CMake Error( in .*)?:", text) or text.endswith(":") and len(text) < 40:
                    nxt = next((w.strip() for w in window[k + 1:k + 4] if w.strip()), "")
                    text = f"{text} {nxt}".strip()
                return text
    return None


def normalize(line, limit=60):
    s = line.lower()
    s = re.sub(r"^\S*\.sh: line \d+:\s+\d+\s+", "", s)   # bash's "<script>: line N: <pid> Aborted"
    s = re.sub(r"/home/runner/work/[^\s:]*/", "", s)
    s = re.sub(r"/tmp/[^\s:]*", "tmp", s)
    s = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "uuid", s)
    s = re.sub(r"\b[0-9a-f]{7,40}\b", "hex", s)
    s = re.sub(r"\d+", "n", s)
    s = re.sub(r"[^a-z]+", "-", s).strip("-")
    return s[:limit].rstrip("-") or "unknown"


def slug(text, limit=30):
    return (re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-") or "unknown")[:limit].rstrip("-")


def workflow_stem(run):
    path = run.get("path") or ""
    m = re.search(r"([^/]+?)\.ya?ml", path)
    return m.group(1) if m else slug(run.get("name"))


def signature(run, step, error_line, exit_msg=None):
    if error_line:
        tail = normalize(error_line)
    else:
        m = re.search(r"exit code (\d+)", exit_msg or "")
        tail = f"exit-{m.group(1)}" if m else "no-log"
    return f"ci:{workflow_stem(run)}:{slug(step)}:{tail}"


def classify(run, jobs, window_text, error_line):
    concl = run.get("conclusion")
    steps = [s for j in jobs for s in j["failed_steps"]]
    text = "\n".join((window_text or [])[-40:])
    for pat in INFRA:
        m = pat.search(text)
        if m:
            return "infra", f"log: {m.group(0)}"
    if concl in ("cancelled", "timed_out") and not error_line:
        return "infra", f"run {concl}"
    if steps and all(INFRA_STEPS.match(s) for s in steps):
        return "infra", "failed only in setup steps: " + ", ".join(steps)
    if error_line:
        return "code", f"error: {error_line[:120]}"
    if window_text:
        return "code", "step exited nonzero"
    return "unknown", "no log readable"


# ---------------------------------------------------------------- per run

def describe(run, api=gh_json, text_api=gh_text, repo=REPO):
    rec = {
        "run_id": run["id"], "workflow": run.get("name"), "workflow_file": run.get("path"),
        "workflow_id": run.get("workflow_id"), "branch": run.get("head_branch"), "sha": run.get("head_sha"),
        "conclusion": run.get("conclusion"), "created_at": run.get("created_at"), "url": run.get("html_url"),
        "attempt": run.get("run_attempt"), "jobs": [], "log_error": None,
    }
    try:
        jobs = run_jobs(run["id"], api, repo)
    except Exception as e:  # noqa: BLE001 - degrade, never abort the sweep
        jobs, rec["log_error"] = [], f"jobs: {e}"
    failed = [j for j in jobs if j["conclusion"] in ("failure", "timed_out")]
    # a gate job ("require every test job") only repeats another job's failure: look at it last
    failed.sort(key=lambda j: bool(GATE.search(j["name"] or "") or any(GATE.search(s or "") for s in j["failed_steps"])))
    rec["jobs"] = [{"id": j["id"], "name": j["name"], "failed_steps": j["failed_steps"]} for j in failed]
    window, error_line, step, tail, exit_msg = [], None, None, [], None
    for j in failed[:MAX_LOGS_PER_RUN]:
        try:
            log = text_api(f"/repos/{repo}/actions/jobs/{j['id']}/logs")
        except Exception as e:  # noqa: BLE001 - log storage is often blocked
            rec["log_error"] = f"log {j['id']}: {e}"[:200]
            continue
        w, err = error_window(log)
        line = first_error(w)
        if line or not window:
            window, error_line, exit_msg = w, line, err
            step = (j["failed_steps"] or [j["name"]])[0]
            tail = [l for l in w if l.strip() and not NOISE.match(l)][-TAIL:]
            if err and err not in tail:
                tail.append(err)
        if line:
            break
    if step is None and failed:
        step = (failed[0]["failed_steps"] or [failed[0]["name"]])[0]
    rec["failed_step"] = step
    rec["error_line"] = error_line
    rec["tail"] = tail
    rec["cls"], rec["reason"] = classify(run, failed, window, error_line)
    rec["sig"] = signature(run, step or "no-step", error_line, exit_msg) if failed or step else \
        f"ci:{workflow_stem(run)}:{rec['conclusion'] or 'failed'}:no-jobs"
    return rec


def already_fixed(rec, api=gh_json, repo=REPO, cache=None):
    """A later successful run of the same workflow on the same branch, or None."""
    key = (rec.get("workflow_id"), rec.get("branch"))
    if cache is not None and key in cache:
        runs = cache[key]
    else:
        try:
            q = f"/repos/{repo}/actions/workflows/{rec['workflow_id']}/runs?branch={rec['branch']}&status=success&per_page=10"
            runs = api(q).get("workflow_runs") or []
        except Exception:  # noqa: BLE001
            runs = []
        if cache is not None:
            cache[key] = runs
    later = [r for r in runs if (r.get("created_at") or "") > (rec.get("created_at") or "")]
    if not later:
        return None
    r = min(later, key=lambda r: r["created_at"])
    return {"run_id": r["id"], "sha": (r.get("head_sha") or "")[:7], "created_at": r["created_at"]}


def mark_spread(records):
    """The same code failure on ENV_SPREAD or more branches that also looks like the toolchain or
    image is the environment; without that hint it spread through merges and stays code."""
    branches = {}
    for r in records:
        branches.setdefault(r["sig"], set()).add(r["branch"])
    for r in records:
        n = len(branches[r["sig"]])
        r["branches_with_sig"] = n
        hint = ENV_HINT.search(" ".join(filter(None, [r.get("error_line"), r.get("failed_step")])))
        if r["cls"] in ("code", "unknown") and n >= ENV_SPREAD and hint:
            r["cls"] = "env"
            r["reason"] = f"same failure on {n} branches: build environment changed ({r['reason']})"
    return records


def collect(since=None, processed=(), api=gh_json, text_api=gh_text, repo=REPO, workers=8):
    """New failed runs since `since` (ISO date or time) not in `processed`, described and classified."""
    runs = list_runs("failure", since, api, repo)
    for r in list_runs("cancelled", since, api, repo):
        r["_cancelled"] = True
        runs.append(r)
    runs = [r for r in runs if r["id"] not in set(processed)]
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        recs = list(ex.map(lambda r: describe(r, api, text_api, repo), runs))
    # a cancelled run counts only when one of its jobs actually failed
    recs = [rec for rec, r in zip(recs, runs) if not r.get("_cancelled") or rec["jobs"]]
    cache = {}
    for rec in recs:
        rec["already_fixed"] = already_fixed(rec, api, repo, cache)
    return mark_spread(recs)


def default_since(days=3):
    d = _dt.datetime.now(_dt.timezone.utc) - _dt.timedelta(days=days)
    return d.strftime("%Y-%m-%dT%H:%M:%SZ")


def cursor_since(cursor, margin_hours=6):
    """The ledger's newest recorded failure, less a margin for runs that finished late."""
    if not cursor:
        return default_since()
    t = _dt.datetime.strptime(cursor[:19], "%Y-%m-%dT%H:%M:%S") - _dt.timedelta(hours=margin_hours)
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser(description="List and classify failed Actions runs")
    p.add_argument("--since", default=None, help="ISO time; default 3 days ago")
    p.add_argument("--repo", default=REPO)
    a = p.parse_args()
    recs = collect(a.since or default_since(), repo=a.repo)
    print(json.dumps(recs, indent=2))
