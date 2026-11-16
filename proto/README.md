# Claude phone: workspace prototype

Not part of DroidDeck or Lanner. This orphan branch only holds a test of Android virtual displays
driven from the shell user: which display flags the shell may set, launching and driving an app on a
workspace while display 0 is in use, surface swaps, keyboard policy, `/dev/uinput` and input-to-display
association. `Main.java` is the helper (`vd.dex` is its prebuilt dex), `device-test.sh` runs on the
device, and the workflow runs it on Android 15 and 16 emulators and on Redroid.
