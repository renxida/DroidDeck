# Autofix loop: failed run -> triaged, fixed, retested change

Goal: every failed run (a phone session's telemetry zip, an unattended on-phone autotest, a CI
failure) becomes a root-caused, fixed and retested change without a human step. Compute is cheap;
the scarce thing is the owner's attention and phone time.

## Pieces

```
 phone session / autotest ──zip──▶ kestrel-telemetry runs/<slug>/<device>/<utc>.zip
 GitHub Actions on the fork ──────▶ run conclusions (gh api)
                     │
                     ▼
 tools/autofix/sweep.py      new zips + new failed CI runs since the ledger cursor
   └─ tools/autofix/triage.py   zip -> verdict {outcome, stage, signatures[], metrics, flags}
   └─ tools/autofix/history.py  signature x (commit, experiments) table -> first-bad / last-good,
                                suspect commits, suspect experiment flags
                     │  work items (one per new or regressed signature)
                     ▼
 driver session (tools/autofix/PLAYBOOK.md, run hourly as a fresh session)
   1. mitigate:   signature tied to an experiment flag -> park the flag in config/kestrel.json
   2. root cause: read the evidence lines, the suspect diff range, the owning thread's notes
   3. fix:        N parallel agents, one hypothesis each, branch autofix/<sig>/<k>
   4. retest:     CI build.yml (+ off-device startup gates, + launch rig) on each branch
   5. promote:    first green fix that also adds a regression check is handed (branch + sha) to
                  the switchboard owner, the only pusher to perf/switchboard
   6. verify:     queue an autotest (config "autotest") on the new build; the next triaged run
                  without the signature closes the item
                     │
                     ▼
 kestrel-telemetry autofix/ledger.jsonl   one line per event: seen, triaged, mitigated, attempt,
                                          promoted, verified, escalated (private repo)
```

## Verdict schema (triage.py output, one JSON object per zip)

```
{ "run": "runs/<slug>/<device>/<utc>.zip", "slug", "commit", "branch", "device", "started_utc",
  "kind": "session" | "autotest" | "checkpoint",
  "experiments": [...],               # experiments_at_session_start
  "stage": "app" | "setup" | "steam_ui" | "steam_signed_in" | "launch_requested"
           | "game_exec" | "game_first_frame" | "in_game",   # furthest stage reached
  "outcome": "ok" | "fail" | "degraded" | "incomplete" | "user_stop",
  "signatures": [ {"id", "severity": "fail"|"degrade"|"info", "evidence": [<= 6 lines], "owner"} ],
  "metrics": { "steam_ui_s", "game_first_frame_s", "fps_p50", "frametime_p99_ms",
               "stutters_per_min", "thermal_max", "zram_used_pct_at_launch", "run_s" } }
```

A signature id is stable text (`fex-hang-after-memory-names`, `steam-ui-slow`, ...), so the same
failure in two runs is one work item. `owner` names the thread that owns that area (hub, fex,
graphics, native, dotnet, main) so the loop routes instead of duplicating.

## What still needs the owner (keep short)

1. Steam: signing in, Steam Guard, anything about ownership, DRM or the account. The loop never
   stubs, mocks or bypasses Steam's API, ownership or DRM checks, and never copies a login.
2. One-time setup on the phone (an install, a switch only a hand may flip).
3. Money, new accounts, making anything public, repo settings and secrets.
4. Changes the owner would feel and has opinions on (pad layout, what the launch screen says): the
   loop may ship them, but tells the owner in one line.
Everything else (crashes, hangs, regressions, CI breaks, perf drops) is the loop's.

## Hard limits

- Real game files never leave the phone; ledger and run data stay in the private repo.
- Never push perf/switchboard from the loop; its owner merges handed-over green branches.
- A fix without green CI does not merge. A fix that only hides a signature (deletes the log line,
  widens a timeout without cause) is not a fix.
- The owner's pinned config choices (native-engine pin) are not reverted by the loop.

## Off-phone gates

- native-startup-gate: jobs inside build.yml (export matrix, gate matrix, soak) that start the native
  engine off-device on every push. A red gate job is a retest failure for the attempt.
- offphone-rig.yml: redroid Android 14 on ubuntu-24.04-arm. Inputs apk_run_id (build.yml run; empty
  means newest green build of ref), ref (default perf/switchboard), scenario (steam-ui,
  stand-in-game, all), guest_vulkan (lavapipe or venus), image. Runs on workflow_dispatch,
  workflow_call and pushes to ci/offphone-probe and autofix/**. build.yml calls it with steam-ui
  on its own APK in every build (job offphone-steam-ui), a required check before release-apk
  publishes. Each rig (<scenario>) job passes only when the
  scenario's target stage is reached and prints verdict.json ({scenario, target_stage,
  stage_reached, ok, seconds_to_each_stage, next_missing_stage, exit{class, suspect, status,
  signal, detail}}), the app's events.jsonl and session.log, logcat and process samples in its
  log. steam-ui stops at Steam's sign-in screen and never types anything; stand-in-game uses a
  stand-in executable, never real game files.
- A session without API write access can't dispatch the rig; it reads the rig runs that already
  exist for a build and leaves dispatching to a session that can.
