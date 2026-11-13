#!/usr/bin/env python3
"""Print the newest live session status file (runs/<build>/<device>/<start>.live.txt) from the telemetry repo.

The app rewrites that file every 30 s while a session runs. With --follow this re-fetches it every
30 s and prints only the new history lines, or the whole file when a newer session's file appears.
Uses the gh CLI and its own sign-in.
"""
import argparse
import json
import subprocess
import sys
import time

DEFAULT_REPO = "renxida/kestrel-telemetry"
SUFFIX = ".live.txt"
HISTORY = "## history"


def gh(args, runner=subprocess.run) -> str:
    out = runner(["gh", "api", *args], capture_output=True, text=True, check=False)
    if out.returncode != 0:
        raise RuntimeError(f"gh api {' '.join(args)}: {out.stderr.strip() or out.returncode}")
    return out.stdout


def live_paths(tree: dict) -> list:
    """Live files under runs/ in a git trees API answer."""
    return [e["path"] for e in tree.get("tree", [])
            if e.get("type") == "blob" and e.get("path", "").startswith("runs/") and e["path"].endswith(SUFFIX)]


def newest(paths, build=None, device=None):
    """The newest session's file: names are the session's UTC start, so they sort by time."""
    def parts(p):
        bits = p.split("/")
        return bits if len(bits) == 4 else None
    chosen = [p for p in paths if parts(p)
              and (build is None or build in parts(p)[1])
              and (device is None or device.lower() in parts(p)[2].lower())]
    if not chosen:
        return None
    return max(chosen, key=lambda p: (parts(p)[3], p))


def history_lines(text: str) -> list:
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith(HISTORY):
            return [x for x in lines[i + 1:] if x.strip()]
    return []


def new_history(seen: set, text: str) -> list:
    """History lines not printed yet; a line starts with the session's uptime, so it is unique."""
    return [x for x in history_lines(text) if x not in seen]


def find_newest(repo, build=None, device=None, runner=subprocess.run):
    branch = json.loads(gh([f"repos/{repo}"], runner)).get("default_branch", "main")
    tree = json.loads(gh([f"repos/{repo}/git/trees/{branch}?recursive=1"], runner))
    if tree.get("truncated"):
        print("warning: the repository tree was truncated; the newest file may be missed", file=sys.stderr)
    return newest(live_paths(tree), build, device)


def fetch(repo, path, runner=subprocess.run) -> str:
    return gh(["-H", "Accept: application/vnd.github.raw+json", f"repos/{repo}/contents/{path}"], runner)


def main(argv=None, runner=subprocess.run, sleep=time.sleep) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--build", help="only builds whose name contains this")
    ap.add_argument("--device", help="only devices whose name contains this")
    ap.add_argument("--path", help="a specific runs/.../*.live.txt instead of the newest")
    ap.add_argument("--follow", action="store_true", help="re-fetch every --interval seconds and print new history lines")
    ap.add_argument("--interval", type=float, default=30.0)
    ap.add_argument("--count", type=int, default=0, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)

    path = args.path or find_newest(args.repo, args.build, args.device, runner)
    if not path:
        print(f"no {SUFFIX} files under runs/ in {args.repo}", file=sys.stderr)
        return 1
    text = fetch(args.repo, path, runner)
    print(f"== {path}")
    print(text, end="" if text.endswith("\n") else "\n")
    if not args.follow:
        return 0
    seen = set(history_lines(text))
    rounds = 0
    while args.count == 0 or rounds < args.count:
        rounds += 1
        sleep(args.interval)
        try:
            latest = args.path or find_newest(args.repo, args.build, args.device, runner) or path
            text = fetch(args.repo, latest, runner)
        except RuntimeError as e:
            print(f"(fetch failed: {e})", file=sys.stderr)
            continue
        if latest != path:
            path = latest
            print(f"\n== newer session: {path}")
            print(text, end="" if text.endswith("\n") else "\n")
            seen = set(history_lines(text))
            continue
        fresh = new_history(seen, text)
        for line in fresh:
            print(line)
        seen.update(fresh)
        if "state: session ended" in text:
            print("(session ended)")
            return 0
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
