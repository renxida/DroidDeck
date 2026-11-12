# Experimental Quest 2/3/3S support

Quest runs DroidDeck as a 2D panel. Quest 2 has a separate experimental Adreno 6xx driver path.

Quest 2 selects `turnip26.3.0-quest2`, a pinned Banners-Turnip A6xx/A7xx build
with the same GPUOBJ adapter. It does not select the Quest 3 A7xx binary.
The first Quest 2 launch applies manual driver selection and the SurfaceControl
workaround, even if `questDefaultsV1` exists from an earlier install. Later user
choices are retained. The separate `turnip26.3.0-a6xx` bundle provides a plain
Adreno 6xx offline fallback for other devices; their driver Auto behavior remains.
Neither family-wide hardware coverage nor Quest 2 operation has been verified.

The A6xx input URL and SHA-256 are pinned in `tools/quest/build-driver.py`.
Both A6xx bundles are prepared by the existing local/CI wrapper build step and
covered by runtime-input attestation. Quest 3/3S retains its original driver.
Early installation and compositor breadcrumbs are written to `driver-setup.txt`
in private files and, when available, the app's external files directory. They
do not require a Steam session. The existing Quest Java crash file now also
applies to Quest 2; native crashes still require Android logcat.

Install the standard DroidDeck APK on Quest 3/3S. Device detection on the
first launch selects manual drivers, the bundled
`turnip25.1.0-quest1` Android driver and the default Linux driver. It enables
`hdr.steam` to use the existing SurfaceControl presentation workaround from
[the Quest report](https://github.com/Droid-Deck/DroidDeck/issues/245).
Presentation also has a Quest-specific layer path for complete SDR scenes and
the black backdrop, independent of this HDR preference; see
[the presentation candidate and headset test](quest-presentation-test.md).
These defaults apply once; subsequent user changes are retained. Colour
accuracy and headset performance still need testing.

Selecting driver Auto also uses the bundled Quest display driver and runtime
default, without downloading a generic Adreno driver pair. Manual selections
remain available and are not replaced on startup. Android's Auto-by-GPU fallback
uses the Quest bundle too; the external `Download/droiddeck-driver` override
still takes precedence. Fully close and restart DroidDeck after changing the
display driver, since the compositor loads it once per app process.

Both the Android driver wrapper and the Linux session preload use
`tools/quest/kgsl_compat.c`. Successful legacy allocation/free calls are
unchanged. Only `EACCES`/`EPERM` failures retry through `GPUOBJ_ALLOC`,
`GPUOBJ_INFO` and `GPUOBJ_FREE`. The Android wrapper depends on the original
bundled Turnip binary. Quest's SELinux policy also denies `CMDSTREAM_READTIMESTAMP_CTXTID`, which Mesa 26's Turnip
needs to wait on timeline semaphores; the adapter answers it from the last submitted timestamp
and a zero-timeout `WAITTIMESTAMP`. Unhandled controller ioctls forward to the next
preload hook rather than skipping it with a raw syscall.

The Ubuntu runtime's Turnip polls timeline points with
`CMDSTREAM_READTIMESTAMP_CTXTID` and reports any failure as `VK_ERROR_UNKNOWN`,
which aborted gamescope with `vk.WaitSemaphores` result -13 on Quest. The Linux
preload (`tools/linuxfs/preload/kgslpoll.c`) answers a failed read from one
millisecond `WAITTIMESTAMP` calls over the context's submitted timestamps, and
logs refused KGSL ioctls to `session.log` as `[QuestKGSL] ioctl 0x.. refused`.

`tools/build_local.sh` and CI build the wrapper from source using the pinned
Android NDK; the normal preload builds include the shared Linux adapter.
Runtime input verification checks the resulting driver archive and invalidates
stale prepared assets after compatibility sources change. To rebuild only the
Android driver archive:

```sh
python3 tools/quest/build-driver.py "$ANDROID_HOME/ndk/27.3.13750724"
```

Quest compatibility is included in the standard APK; no separate Quest
package is needed. Other devices retain their existing defaults.

Quest 3 testers have successfully launched games.

On a headset, check the Steam login panel, sign-in, game installation and
then an owned game's rendering/input. Capture the device/OS, session logs,
`files/quest-startup.txt` and any `files/quest-last-crash.txt` failure.
Host allocation and preload-chain tests do not establish headset operation.
