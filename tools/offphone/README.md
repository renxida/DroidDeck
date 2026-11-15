# Off-phone launch rig

`.github/workflows/offphone-rig.yml` runs the real APK on an arm64 Android in docker
([redroid](https://github.com/remote-android/redroid-doc)) on GitHub's free `ubuntu-24.04-arm`
runner, and drives it through the launch path a phone takes. Phone-only failures that are not
about the phone's own GPU driver, heat or memory should show up here first.

| Scenario | Target stage | What it covers |
| --- | --- | --- |
| `steam-ui` | `login_screen` | install, runtime install, proot, PulseAudio, the Wayland compositor, gamescope, Xwayland, the Steam client download and start, its UI's first frame, its sign-in screen (read through DevTools; nothing signs in) |
| `stand-in-game` | `standin_exit_clean` | the stand-in Godot game (`tools/native-engine/standin`, our own code, no game files) through `droiddeck-proton-run`, Proton (with Steam's client installed, as on a phone) and FEX, judged by the app's own `frames.csv`: its first Vulkan frame, at least 100 presented frames, then a clean exit (it quits after 300) |
| `steam-ui-screen-cycle` | `login_screen` | `steam-ui` with the screen turned off and on three times during Steam's start; the UI must still appear within 180 s |
| `steam-ui-background` | `login_screen` | `steam-ui` with the app sent home for 30 s during Steam's start, then reopened from its launcher icon, which must bring the running session back (`returned_to_session`). Landing on the home screen instead is logged as `landed_on_home` and fails the run; the rig still presses Open Steam so the rest is measured. Also fails if the app restarts the session |
| `stand-in-background` | `standin_exit_clean` | `stand-in-game` with the app sent home for 20 s once Steam is ready, then the same return-by-icon check |
| `upgrade` | `login_screen` | the latest release's `kestrel.apk` installed first, then the APK under test over it (`adb install -r`); a setting and the installed runtime must survive, then `steam-ui` |

Outputs per scenario (artifact `rig-<scenario>`, and printed to the job log):
- `verdict.json`: `{scenario, target_stage, stage_reached, ok, seconds_to_each_stage, exit, next_missing_stage, game_vulkan_frames, too_late, session_restarted}`
- `rig-session/`: the app's own session folder (`build.txt`, `events.jsonl`, `session.log`, ...), as on a phone
- `rig-events.jsonl`, `state.json`, `ps-*.txt` (the app's processes every minute or so), `screen.png`, `logcat.txt`, `redroid.txt`
- the app's per-game logs (`rig-session/games/`: Proton's log, launch.txt) and Godot's log, printed without FEX's noise

The stand-in's stdout markers (`FIRST_FRAME`, `MAINLOOP_OK`) don't reach any log under Proton's
`steam.exe`, which is why the frame log is the evidence. Without an unexpired stand-in artifact,
`all` runs `steam-ui` alone and warns.

The job fails unless the target stage was reached in the first session the rig started, and within any deadline (`steam_ui` 180 s, 210 s when backgrounded, counted from the rig's start command). Dispatch inputs: `apk_run_id` (a build.yml run)
or `ref` (its newest green build), `scenario` (`all` = the first two, `regressions` = the other four, `everything`, or one scenario name; a push to `ci/offphone-probe` runs `everything`), `guest_vulkan`.
Other workflows can `uses: ./.github/workflows/offphone-rig.yml` with the same inputs. Pushes to
`autofix/**` run it against that commit's own build.yml APK.

## How CI Android differs from the phone

- No GPU. The guest gets Vulkan from Mesa's CPU driver (lavapipe) with GL on llvmpipe, the session's
  own software fallback. `guest_vulkan: venus` tries the Mali path over the emulator's SwiftShader
  instead (SwiftShader lacks the dma-buf sharing gamescope needs, so it stops there).
- `cpusync/` is a test-only Vulkan layer that gives lavapipe the sync-file semaphores gamescope
  requires, the same way `tools/venus/mesa-venus-vtest.patch` does for Venus. It is built, checked
  against the runner's lavapipe, and pushed into the CI device's rootfs. It is never in the APK.
- Debugging tools are switched on by writing the app's `agent` preferences as root
  (`enable-debug-tools.sh`), the switch a person flips in Setup > Diagnostics.
- So the PowerVR driver bridge (libhybris), thermal limits, cpusets and low-memory behaviour stay
  phone-only. `/mnt/project-files/kestrel/offphone-failure-map.md` lists which past failures the
  rig catches.

## Hard limits

Nothing here signs in to Steam, stubs or bypasses Steam's API, ownership or DRM, or uses anyone's
Steam session. The rig stops at the sign-in screen. No real game files are used.

## Run it yourself

On any arm64 Linux with docker and root: `tools/offphone/redroid-up.sh`, install the APK, build
`cpusync/` (`tools/offphone/cpusync/build.sh cpusync`), then `tools/offphone/drive.sh steam-ui out`.
