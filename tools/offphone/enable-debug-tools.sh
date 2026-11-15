#!/usr/bin/env bash
# Turn on Setup > Diagnostics > Debugging tools on a rooted CI Android (redroid), the same
# switch a person flips by hand on the phone. Writes the app's "agent" preferences while it is stopped.
set -euo pipefail
pkg=${1:-dev.renxida.kestrel}
adb root >/dev/null 2>&1 || true
adb wait-for-device
adb shell am force-stop "$pkg"
dir=/data/data/$pkg/shared_prefs
uid=$(adb shell stat -c %u "/data/data/$pkg" | tr -d '\r')
adb shell "mkdir -p $dir && chown $uid:$uid $dir && chmod 771 $dir"
adb shell "cat > $dir/agent.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="commands" value="true" />
</map>
XML
adb shell "chown $uid:$uid $dir/agent.xml && chmod 660 $dir/agent.xml && restorecon -R $dir"
adb shell "ls -laZ $dir"
