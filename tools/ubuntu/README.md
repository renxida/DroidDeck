# The Linux runtime (Ubuntu)

DroidDeck's Linux runtime: Ubuntu 26.04 LTS for arm64. The app installs it into `files/linuxfs` and
runs it under its patched proot.

- `build-debs.sh` (run in an `ubuntu:26.04` container on an arm64 runner) rebuilds Ubuntu's Mesa
  source package with `-Dfreedreno-kmds=msm,kgsl`, so Turnip and freedreno reach Android's
  `/dev/kgsl-3d0`, and gives it a `+droiddeck` version. `patches/mesa` adds KGSL
  UBWC 5/6 (backported from upstream Mesa) and the KGSL device numbers for WSI (from
  [Droid-Deck/Drivers](https://github.com/Droid-Deck/Drivers)). It also carries Icenowy Zheng's upstream
  Zink fix that keeps the Imagination depth/stencil swizzle off colour views (Mesa d55418dae7).
- The runtime's built-in Vulkan driver is DroidDeck's DD-Turnip Linux build (`DD_TURNIP_URL` and
  `DD_TURNIP_SHA256` in `base.env`): Ubuntu's Turnip is diverted to `libvulkan_freedreno.so.distrib`
  because it lacks the Adreno 8xx fixes. Zink, EGL and llvmpipe stay Ubuntu's.
- `build.sh` verifies Ubuntu's base image (`base.env`: signed `SHA256SUMS` from cdimage plus the
  pinned hash), chroots into it and runs `setup.sh`, and packs the result as `<tag>.tar.zst`.
- `setup.sh` installs `packages-runtime.txt` without recommends and the `+droiddeck` debs from
  a local repository in `/var/lib/droiddeck/apt` (pinned at 1001 and held), then builds the
  `droiddeck-runtime` package from `overlay-runtime/`. The package is Protected, depends on
  `protected-runtime.txt`, owns our files and diverts any Ubuntu file it replaces, so apt neither
  removes the essentials nor overwrites our settings on upgrade.

At run time:

- `sudo`, `su`, `pkexec` and apt/aptitude (for commands that change the system) go through
  `droiddeck-root`: the app runs them in a separate proot with `-0 --link2symlink`, passing the
  caller's terminal, so dpkg gets root ownership emulation and hard links while the session keeps
  the fast path.
- The installer turns the image's hard links into proot's link2symlink layout, and proot shows the
  linked name as `/proc/self/exe` (patch 0020), so Ubuntu's multi-call coreutils work.

`.github/workflows/build-ubuntu-runtime.yml` builds it and, run with a new tag (`ubuntu-rN`),
publishes the runtime in Droid-Deck/DroidDeck-Components; `linuxfs-ubuntu.json` there names the release the
app installs.
