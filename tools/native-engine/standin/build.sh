#!/bin/sh
# Export the stand-in game for Windows x86_64 into OUT (StandIn.exe, StandIn.pck, data_StandIn_windows_x86_64/).
#   GODOT=<Godot 4.5.1 mono editor> sh build.sh OUT [r2r=on|off] [deps=on|off] [gdext=present|missing]
# Needs the 4.5.1 mono export templates installed and a .NET 9 SDK. Builds in a scratch copy of this folder,
# then checks the exported StandIn.dll: with r2r=on it must be ReadyToRun without the IL-only COR flag (what
# crossgen2 makes, and what the converter before d68b35ad rejected); with r2r=off, plain IL-only.
set -eu
OUT=$(realpath -m "$1"); shift
R2R=on DEPS=on GDEXT=present
for kv in "$@"; do
  case "$kv" in
    r2r=on|r2r=off) R2R=${kv#r2r=} ;;
    deps=on|deps=off) DEPS=${kv#deps=} ;;
    gdext=present|gdext=missing) GDEXT=${kv#gdext=} ;;
    *) echo "build.sh: unknown option $kv" >&2; exit 2 ;;
  esac
done
: "${GODOT:?set GODOT to the Godot 4.5.1 mono editor}"
HERE=$(dirname "$(realpath "$0")")
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE"/. "$WORK"/
rm -rf "$WORK/.godot" "$WORK/out" "$WORK/bin" "$WORK/obj"
if [ "$GDEXT" = present ]; then
  mkdir -p "$WORK/addons/winonly/bin"
  printf 'MZ placeholder for the stand-in winonly extension; not a library\n' > "$WORK/addons/winonly/bin/winonly.windows.x86_64.dll"
else
  rm -rf "$WORK/addons/winonly"
fi
[ "$R2R" = on ] && StandInR2R=true || StandInR2R=false
[ "$DEPS" = on ] && StandInDeps=true || StandInDeps=false
export StandInR2R StandInDeps DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
cd "$WORK"
echo "== stand-in r2r=$R2R deps=$DEPS gdext=$GDEXT"
timeout 900 dotnet build StandIn.csproj -c ExportRelease 2>&1 | tail -3
timeout 300 "$GODOT" --headless --path . --import > import.log 2>&1 || true
mkdir -p "$OUT"
rm -rf "$OUT"/*
timeout 1200 "$GODOT" --headless --path . --export-release windows "$OUT/StandIn.exe" > export.log 2>&1 || echo "export exit $?"
if [ ! -f "$OUT/data_StandIn_windows_x86_64/StandIn.dll" ] || [ ! -f "$OUT/StandIn.pck" ]; then
  tail -60 export.log
  exit 1
fi
grep -i -E "winonly|gdextension" import.log export.log | head -5 || true
if [ -f .godot/extension_list.cfg ]; then echo "extension_list.cfg:"; cat .godot/extension_list.cfg; fi
python3 - "$OUT/data_StandIn_windows_x86_64" "$R2R" "$DEPS" <<'PY'
import os, struct, sys
folder, r2r, deps = sys.argv[1:]

def cor(path):
    b = open(path, "rb").read()
    pe = struct.unpack_from("<I", b, 0x3C)[0]
    machine, nsec = struct.unpack_from("<HH", b, pe + 4)
    opt = pe + 24
    opt_size = struct.unpack_from("<H", b, pe + 20)[0]
    dirs = 112 if struct.unpack_from("<H", b, opt)[0] == 0x20B else 96
    rva = struct.unpack_from("<I", b, opt + dirs + 14 * 8)[0]
    def off(r):
        for i in range(nsec):
            vs, va, rs, raw = struct.unpack_from("<IIII", b, opt + opt_size + 40 * i + 8)
            if va <= r < va + max(vs, rs):
                return r - va + raw
    cli = off(rva)
    flags = struct.unpack_from("<I", b, cli + 16)[0]
    native = struct.unpack_from("<I", b, cli + 64)[0]
    rtr = native != 0 and b[off(native):off(native) + 4] == b"RTR\0"
    return machine, flags, rtr

machine, flags, rtr = cor(os.path.join(folder, "StandIn.dll"))
print("StandIn.dll: machine %#x COR flags %#x ReadyToRun header %s" % (machine, flags, rtr))
if r2r == "on" and (flags & 1 or not rtr):
    sys.exit("expected a ReadyToRun StandIn.dll without the IL-only flag")
if r2r == "off" and (not flags & 1 or rtr):
    sys.exit("expected a plain IL-only StandIn.dll")
want = ["0Harmony.dll", "Sentry.dll", "SmartFormat.dll", "Steamworks.NET.dll", "Vortice.DXGI.dll",
        "Vortice.Mathematics.dll", "SharpGen.Runtime.dll", "MonoMod.Backports.dll", "MonoMod.ILHelpers.dll",
        "JetBrains.Annotations.dll", "System.IO.Hashing.dll"]
have = [n for n in want if os.path.isfile(os.path.join(folder, n))]
print("NuGet assemblies exported:", ", ".join(have) or "none")
if deps == "on" and len(have) != len(want):
    sys.exit("missing: %s" % sorted(set(want) - set(have)))
if deps == "off" and have:
    sys.exit("deps=off but exported %s" % have)
PY
ls "$OUT"
