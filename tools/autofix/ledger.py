#!/usr/bin/env python3
"""Append-only autofix ledger: <telemetry clone>/autofix/ledger.jsonl, one JSON event per line.

Every event carries "ts" (UTC, ISO 8601) and "event"; the rest depends on the type:

  seen(run, blob)                          a telemetry zip at this git blob was looked at
  triaged(run, blob, verdict)              its verdict (triage.py schema, evidence included)
  ci_failed(run_id, workflow, branch, sha, jobs, sig, cls, ...)
                                           a failed (or cancelled-by-failure) Actions run
  item_opened(sig, first_seen, owner)      a work item starts, or starts again (reopened=True)
  mitigated(sig, action)                   e.g. "parked flag fex-trim"
  attempt(sig, branch, hypothesis)         a fix branch was started
  attempt_result(sig, branch, conclusion)  that branch's CI conclusion
  ready_to_promote(sig, branch, sha, summary, files)
                                           a green fix with a regression test, waiting for the
                                           session that owns perf/switchboard to take it
  promoted(sig, commit)                    the fix is on origin/perf/switchboard (sweep infers this
                                           from the sha, or a commit with the same subject)
  verified(sig, run)                       a later run on/after the fix lacks the signature
  escalated(sig, reason, needs)            needs is "cedar" (the owner) or "owner-thread"
  closed(sig)

A run is keyed by path AND blob sha: a checkpoint zip is overwritten in place as its session grows,
so the same path with a new blob is new evidence. The ledger is private run data: it lives only in
the telemetry repository.
"""
import datetime as _dt
import json
import os
from pathlib import Path

REQUIRED = {
    "seen": ("run", "blob"),
    "triaged": ("run", "blob", "verdict"),
    "ci_failed": ("run_id", "workflow", "branch", "sha"),
    "item_opened": ("sig", "first_seen", "owner"),
    "mitigated": ("sig", "action"),
    "attempt": ("sig", "branch", "hypothesis"),
    "attempt_result": ("sig", "branch", "conclusion"),
    "ready_to_promote": ("sig", "branch", "sha", "summary", "files"),
    "promoted": ("sig", "commit"),
    "verified": ("sig", "run"),
    "escalated": ("sig", "reason", "needs"),
    "closed": ("sig",),
}
NEEDS = ("cedar", "owner-thread")
# status an event leaves its item in
STATUS = {"item_opened": "open", "mitigated": "mitigated", "attempt": "fixing",
          "ready_to_promote": "ready_to_promote", "promoted": "promoted",
          "verified": "verified", "escalated": "escalated", "closed": "closed"}
DONE = ("verified", "closed")
EVIDENCE_LINES = 6


def now_iso():
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ledger_path(telemetry):
    return Path(telemetry) / "autofix" / "ledger.jsonl"


def run_key(run, blob):
    return f"{run}@{blob}"


def make_event(event, ts=None, **fields):
    if event not in REQUIRED:
        raise ValueError(f"unknown ledger event {event!r}")
    missing = [k for k in REQUIRED[event] if fields.get(k) in (None, "")]
    if missing:
        raise ValueError(f"{event}: missing {', '.join(missing)}")
    if event == "escalated" and fields["needs"] not in NEEDS:
        raise ValueError(f"escalated: needs must be one of {NEEDS}")
    return {"ts": ts or now_iso(), "event": event, **fields}


def compact_verdict(verdict):
    """The verdict as stored: evidence capped, nothing else dropped."""
    v = dict(verdict)
    sigs = []
    for s in v.get("signatures") or []:
        s = dict(s)
        s["evidence"] = list(s.get("evidence") or [])[:EVIDENCE_LINES]
        sigs.append(s)
    v["signatures"] = sigs
    return v


class Ledger:
    def __init__(self, path):
        self.path = Path(path)

    @classmethod
    def at(cls, telemetry):
        return cls(ledger_path(telemetry))

    def events(self):
        if not self.path.exists():
            return []
        out = []
        with open(self.path, encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except json.JSONDecodeError:
                    # a torn last line from an interrupted write: skip it, keep the rest
                    continue
        return out

    def append(self, event, ts=None, **fields):
        e = make_event(event, ts=ts, **fields)
        self.append_many([e])
        return e

    def append_many(self, events):
        if not events:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        text = "".join(json.dumps(e, sort_keys=True, ensure_ascii=False) + "\n" for e in events)
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())

    def state(self):
        return State(self.events())


class State:
    """What the ledger says now: processed runs, latest verdict per path, CI cursor, items."""

    def __init__(self, events):
        self.processed_runs = set()      # run_key(path, blob)
        self.verdicts = {}               # path -> latest triaged verdict
        self.ci_run_ids = set()
        self.ci_failures = {}            # run_id -> ci_failed event
        self.ci_cursor = None            # newest created_at among recorded CI failures
        self.items = {}
        for e in events:
            self._apply(e)

    def _item(self, sig):
        return self.items.setdefault(sig, {
            "sig": sig, "status": None, "owner": None, "first_seen": None, "opened": None,
            "last_event": None, "runs": [], "ci_runs": [], "mitigations": [], "attempts": {},
            "ready": None, "promoted": None, "verified": None, "escalations": [], "reopened": 0,
            "failed_verifies": 0, "stage": None})

    def _apply(self, e):
        kind = e.get("event")
        if kind == "seen":
            self.processed_runs.add(run_key(e["run"], e["blob"]))
        elif kind == "triaged":
            self.processed_runs.add(run_key(e["run"], e["blob"]))
            v = dict(e["verdict"])
            v.setdefault("run", e["run"])
            v["blob"] = e["blob"]
            self.verdicts[e["run"]] = v
            for s in v.get("signatures") or []:
                if s.get("id") in self.items:
                    it = self.items[s["id"]]
                    if e["run"] not in it["runs"]:
                        it["runs"].append(e["run"])
        elif kind == "ci_failed":
            rid = int(e["run_id"])
            self.ci_run_ids.add(rid)
            self.ci_failures[rid] = e
            c = e.get("created_at")
            if c and (self.ci_cursor is None or c > self.ci_cursor):
                self.ci_cursor = c
            sig = e.get("sig")
            if sig and sig in self.items and rid not in self.items[sig]["ci_runs"]:
                self.items[sig]["ci_runs"].append(rid)
        elif kind in STATUS:
            sig = e["sig"]
            it = self._item(sig)
            prev = it["status"]
            if kind == "item_opened":
                if prev is not None:
                    it["reopened"] += 1
                    if prev == "promoted":
                        it["failed_verifies"] += 1
                it["owner"] = e.get("owner") or it["owner"]
                it["first_seen"] = it["first_seen"] or e.get("first_seen")
                it["opened"] = e["ts"]
                if e.get("stage"):
                    it["stage"] = e["stage"]
            elif kind == "mitigated":
                it["mitigations"].append(e["action"])
            elif kind == "attempt":
                it["attempts"][e["branch"]] = {"hypothesis": e["hypothesis"], "conclusion": None}
            elif kind == "ready_to_promote":
                it["ready"] = {k: e.get(k) for k in ("branch", "sha", "summary", "files", "subject")}
            elif kind == "promoted":
                it["promoted"] = e["commit"]
            elif kind == "verified":
                it["verified"] = e["run"]
            elif kind == "escalated":
                it["escalations"].append({"reason": e["reason"], "needs": e["needs"]})
            # mitigation of an item already being fixed or promoted does not move it backwards
            if not (kind == "mitigated" and prev in ("fixing", "ready_to_promote", "promoted")):
                it["status"] = STATUS[kind]
            it["last_event"] = e["ts"]
        elif kind == "attempt_result":
            it = self._item(e["sig"])
            it["attempts"].setdefault(e["branch"], {"hypothesis": None})["conclusion"] = e["conclusion"]
            it["last_event"] = e["ts"]

    def open_items(self):
        return {s: it for s, it in self.items.items() if it["status"] not in DONE and it["status"] is not None}

    def item_status(self, sig):
        it = self.items.get(sig)
        return it["status"] if it else None


if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser(description="Print the ledger's open items")
    p.add_argument("--telemetry", default="/home/claude/kestrel-telemetry")
    a = p.parse_args()
    st = Ledger.at(a.telemetry).state()
    print(json.dumps({"processed_runs": len(st.processed_runs), "ci_runs": len(st.ci_run_ids),
                      "ci_cursor": st.ci_cursor, "items": st.items}, indent=2, default=list))
