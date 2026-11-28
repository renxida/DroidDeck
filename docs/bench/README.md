# Lanner on the Pixel 10a: how the team works

One phone, many hands. The phone is the scarce resource, so one role owns it and everyone else works off-phone and
queues for measurements.

## Roles

| Role | Who | Owns |
|---|---|---|
| **Test director** | the forgespire session (lead) | The phone and its queue. Decides what runs next, integrates branches into a test build, merges what passes into `main`, reports results. Nothing else touches the phone. |
| **Bench runner** | one forgespire agent at a time | Executes queue tickets on the phone with the standard bench (below). Never edits app code; files bugs back to the owning track. |
| **Optimization tracks** | forgespire agents, one branch each | Code and off-phone CI only. Each track ships behind a default-off switch, with tests, a green CI build and a test ticket. |
| **Research and review** | the server session (and its swarm) | Ranked ideas with evidence, design review of UI changes, review of notes and tickets, the project board. Its sandboxed runner on forgespire can take code-only tasks. |
| **Owner** | Cedar | Taste decisions, money, anything public, anything a backup cannot undo. |

## Tracks (one branch each)

| Track | Branch | Goal |
|---|---|---|
| Steam out of the way | `perf/steam-out-of-the-way` | Give back most of the ~1.5 GB of GPU memory Steam's interface holds behind a game |
| Present path | `perf/direct-present` | Find and remove the ~45 fps ceiling; shorten press-to-screen time; latency probe |
| Defaults and small fixes | `perf/mali-8gb-defaults` | `pe-align` on 8 GB devices, missing metrics, misleading logs |
| GPU memory | `test/mali-10a-gpu` (notes) | What the game's own 1.2-1.4 GB is; Venus / bridge / PanVK |
| Everything we own | (opens after the first traced fight) | Lanner's app process, gamescope, audio server, session scripts: profile, fix, re-measure |
| UI | `ux/*` | Design-review fixes; drafts stay on branches for the owner |

## The standard bench

Every measurement uses the same harness so numbers are comparable and a ticket costs minutes, not an evening.

- **Preflight (enforced, not optional):** Steam offline (setting, screenshot, log), sound off (volume 0, game sound,
  boot-movie sound), fresh save backup when the scenario can write saves, other apps closed, wait for free memory,
  record memory / swap / temperature / low-memory kills.
- **Scenarios:** `menu` (120 s at the main menu) and `fight` (a scripted battle on a fixed seed with the same card
  sequence; frame times and latency are taken **inside the animation windows**: card attacks, enemy turns, a kill).
- **Captured every run:** frame times (p50 / p95 / p99, frames over 50 ms per minute) and on-screen pacing;
  **press-to-screen latency** (p50 / p95 / p99 over 50+ presses); memory (available, swap, per-process, GPU memory
  per owner); CPU per process and thread; a Perfetto system trace; the engine's own profiler on the native path;
  thermal; which engine and graphics path actually ran.
- **Output:** one JSON summary per run plus the raw logs outside the repo, and a row in `docs/bench/RESULTS.md`.
- **Noise rule:** a difference counts only if it beats the control-to-control spread measured the same day, or
  repeats.

## Tickets

A track asks for phone time by adding a ticket to `docs/bench/QUEUE.md`:

    ### <short name>
    - build: <branch @ commit, CI run id>   (or "current test build")
    - switch: <experiment tag(s) to turn on>
    - scenario: menu | fight | both
    - expects: <metric and size, e.g. "GPU memory with Steam UI up: 1.7 GB -> under 0.8 GB">
    - pass/fail: <numbers>
    - regressions to check: <e.g. returning to Steam, cloud sync at exit>

The director batches tickets: candidate branches are merged into one **test build** (everything default-off), so one
install serves many tickets and each ticket is a switch flip plus a bench run. A change reaches `main` when its
ticket passes and nothing in the regression list broke; otherwise it goes back to its track with the evidence.

## Standing rules for anything that touches the phone

Steam offline for tests. Sound off. Back up the saves first, then just do it. Never fake Steam auth or ownership.
No game files in any repo or release. Hands off while the owner is using the phone (off the charger counts).
P99 frame time during battle animations first, press-to-screen latency with it, battery second.
