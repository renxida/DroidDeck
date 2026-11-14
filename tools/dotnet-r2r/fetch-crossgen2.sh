#!/usr/bin/env bash
# Puts crossgen2 (MIT, Microsoft.NETCore.App.Crossgen2.<rid> from nuget.org) into DIR: the compiler,
# its x64-Windows code generator and the license. The default rid is linux-arm64, which runs on the phone.
set -euo pipefail
DIR=${1:?usage: fetch-crossgen2.sh DIR [rid]}
RID=${2:-linux-arm64}
VERSION=9.0.23
case "$RID" in
  linux-arm64) SHA=d8193474cde586e1d419e95ea6ffe300adcf2c752f58461c4c14b23bf7becc4c; HOST=arm64; MACHINE=183 ;;
  linux-x64) SHA=d0958ff209ba41943dd04293476055dd839a77bd2c7864cfc53b87e08a1f9c6a; HOST=x64; MACHINE=62 ;;
  *) echo "unknown rid $RID" >&2; exit 2 ;;
esac
PACKAGE="microsoft.netcore.app.crossgen2.$RID"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 5 --retry-all-errors --retry-delay 10 -o "$TMP/p.nupkg" \
  "https://api.nuget.org/v3-flatcontainer/$PACKAGE/$VERSION/$PACKAGE.$VERSION.nupkg"
echo "$SHA  $TMP/p.nupkg" | sha256sum -c - >/dev/null
mkdir -p "$DIR"
python3 -I - "$TMP/p.nupkg" "$DIR" "$HOST" "$MACHINE" <<'PY'
import os, sys, zipfile
package, folder, host, machine = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
wanted = ("tools/crossgen2", "tools/libclrjit_win_x64_%s.so" % host, "tools/libjitinterface_%s.so" % host, "LICENSE.TXT")
with zipfile.ZipFile(package) as z:
    for name in wanted:
        target = os.path.join(folder, name.split("/")[-1])
        with open(target, "wb") as out:
            out.write(z.read(name))
        os.chmod(target, 0o755 if not name.endswith(".TXT") else 0o644)
head = open(os.path.join(folder, "crossgen2"), "rb").read(20)
assert head[:4] == b"\x7fELF" and int.from_bytes(head[18:20], "little") == machine, "crossgen2 is for another machine"
PY
echo "crossgen2 $VERSION ($RID) in $DIR"
