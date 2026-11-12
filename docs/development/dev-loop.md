# Dev loop from a fork

The loop for a fork with no signing secrets and no self-hosted runner: CI builds the APK, a
machine with ADB installs it on a dedicated test device, and a scenario measures the result.

## Build

Push a branch to the fork and open a pull request against the fork's own `main`. `build.yml`
runs on GitHub-hosted runners (`pick-runner.yml` falls back to `ubuntu-24.04` when
`RUNNER_STATUS_TOKEN` is absent), builds `assembleRelease`, runs `testReleaseUnitTest` and uploads
the testkey-signed APK as the `droiddeck-apk` artifact. Forks start with Actions disabled; enable
them once on the fork's Actions tab.

Pure-Python changes under `tools/` do not need CI: `python3 -m unittest discover -s tools/tests`
runs on any Linux machine.

## Install and measure

```sh
gh auth login
export DROIDDECK_CI_REPO=<owner>/DroidDeck
tools/ci_apk_loop.sh my-branch                                    # install only
tools/ci_apk_loop.sh 12                                           # a pull request's head branch
tools/ci_apk_loop.sh my-branch tools/scenarios/slay-the-spire-2.json --out ./runs
```

`tools/ci_apk_loop.sh` picks the newest successful Build APK run for the branch, caches the APK
by run ID and installs it with the same device resolution as `deploy_local.sh`. CI builds are
signed with the public testkey: replacing a release-signed install fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the script stops rather than uninstall, since that
deletes the Steam sign-in and the runtime. Use a dedicated device.

## Scenarios as benchmarks

Scenario steps can record numbers and bound them:

```json
["pacing", "--seconds", "30"],
{"metric": "fpsOnScreen", "from": "fpsOnScreen"},
{"assert": "p99Ms", "max": 50}
```

`report.json` lists each metric's values over the passed runs with their min, median and max.
`pacing` reports `p50Ms`, `p95Ms` and `p99Ms` from SurfaceFlinger's present-to-present
histogram, so each is the lower edge of a histogram bucket.

`tools/scenarios/slay-the-spire-2.json` waits for the battery to cool below 38 °C, launches the
game (app 2868840), checks it has focus, lets the menu settle, records 30 s of pacing and the
battery temperature before and after, and quits, three times. It assumes Steam is signed in and
the game is installed. Change thresholds with `--var minFps=40 --var maxP99Ms=34`.

Compare builds by running the same scenario on each from the same thermal state; the medians
are the numbers to compare, not a single run.
