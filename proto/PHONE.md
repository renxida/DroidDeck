# Real-phone test: Pixel 10a on GrapheneOS

> Nov 17 update: setup day now flashes our own signed GrapheneOS build with root (see the design doc's
> "Core principle"). The stock web-installer steps below stay as the fallback baseline and as the
> first sanity run of the workspace test before our build goes on.

GrapheneOS lists the Pixel 10a (codename stallion) under official production support
(https://grapheneos.org/faq). Install follows https://grapheneos.org/install/web.

## Buying
- Buy the **unlocked** Pixel 10a (Google Store, Best Buy unlocked, Amazon unlocked).
  Carrier-branded units can have bootloader unlocking disabled for good.
- Any storage size works for this project.

## Install (about 20 minutes, one USB session)
1. Phone: finish stock setup with Wi-Fi on, update to the latest release.
2. Settings > About phone > tap Build number 7 times. Developer options > OEM unlocking on.
3. Power off, hold Volume Down while powering on until "Fastboot Mode".
4. Laptop: open https://grapheneos.org/install/web in Chrome (not a VM, no USB hub).
   Unlock bootloader, Download release, Flash release, Lock bootloader.
   Each confirmation is on the phone with the volume and power keys. Unlock and lock wipe the phone.
5. Boot. Check the verified boot key hash shown on the yellow boot screen against the
   Pixel 10a hash on the install page.
6. Leave "Disable OEM unlocking" checked on the last setup screen.

## Run the workspace test (same USB session, about 5 minutes)
1. Settings > About phone > Build number x7. Developer options > USB debugging on. Accept the laptop's key.
2. On the laptop, with this branch checked out and `adb` installed:
   ```
   bash proto/host-test.sh results/pixel10a-grapheneos
   git add results && git commit -m "Pixel 10a GrapheneOS results" && git push
   ```
3. The test opens Settings, presses Home and briefly locks the screen. Leave the phone on the desk.

## What decides it
- `report.txt`: the shell still holds ADD_TRUSTED_DISPLAY, ADD_ALWAYS_UNLOCKED_DISPLAY,
  ASSOCIATE_INPUT_DEVICE_TO_DISPLAY and INJECT_EVENTS; flags=0x4f80 on the workspace; `OK uinput open rw`.
- Screenshots 1-7 match the emulator runs (results/api36/full).
- If GrapheneOS removed a permission, phase 6 of the design covers the fallback.
