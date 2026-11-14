# Off-phone launch rig

`.github/workflows/offphone-rig.yml` runs the real APK on an arm64 Android in docker
([redroid](https://github.com/remote-android/redroid-doc)) on GitHub's free `ubuntu-24.04-arm`
runner, and drives it through the launch path a phone takes. Phone-only failures that are not
about the phone's own GPU driver, heat or memory should show up here first.

| Scenario | Target stage | What it covers |
| --- | --- | --- |
| `steam-ui` | `login_screen` | install, runtime install, proot, PulseAudio, the Wayland compositor, gamescope, Xwayland, the Steam client download and start, its UI's first frame, its sign-in screen (read through DevTools; nothing signs in) |
| `stand-in-game` | `standin_mainloop_ok` | the stand-in Godot game (`tools/native-engine/standin`, our own code, no game files) through `droiddeck-proton-run`, Proton and FEX, to its first frame and a clean exit after 300 frames |

Outputs per scenario (artifact `rig-<scenario>`, and printed to the job log):
- `verdict.json`: `{scenario, target_stage, stage_reached, ok, seconds_to_each_stage, exit, next_missing_stage}`
- `rig-session/`: the app's own session folder (`build.txt`, `events.jsonl`, `session.log`, ...), as on a phone
- `rig-events.jsonl`, `state.json`, `screen.png`, `logcat.txt`, `redroid.txt`

The job fails unless the target stage was reached. Dispatch inputs: `apk_run_id` (a build.yml run)
or `ref` (its newest green build), `scenario` (`steam-ui`, `stand-in-game`, `all`), `guest_vulkan`.
Other workflows can `uses: ./.github/workflows/offphone-rig.yml` with the same inputs.

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
