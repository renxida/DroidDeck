#!/usr/bin/env bash
# Put another FEX in place of Hangover's: from a Kestrel .wcp, or from a loose libarm64ecfex.dll + .so.
#
#   install-fex.sh <workdir> <file.wcp | dir holding libarm64ecfex.dll and libarm64ecfex.so>
set -euo pipefail
work=$1 src=$2
if [ -f "$src" ]; then
  rm -rf "$work/wcp" && mkdir -p "$work/wcp"
  zstd -dc "$src" | tar -C "$work/wcp" -xf -
  find "$work/wcp" -type f | sort
  srcdll=$(find "$work/wcp" -path '*aarch64-windows/libarm64ecfex.dll' | head -1)
  srcso=$(find "$work/wcp" -path '*aarch64-unix/libarm64ecfex.so' | head -1)
else
  srcdll=$src/libarm64ecfex.dll srcso=$src/libarm64ecfex.so
fi
[ -f "$srcdll" ] && [ -f "$srcso" ] || { echo "no libarm64ecfex.dll/.so in $src" >&2; exit 1; }
dll=$(grep 'aarch64-windows/libarm64ecfex.dll$' "$work/fexpaths" | head -1 || true)
[ -n "$dll" ] || { echo "Hangover has no libarm64ecfex.dll" >&2; exit 1; }
so=$(dirname "$(dirname "$dll")")/aarch64-unix/libarm64ecfex.so
sudo cp "$srcdll" "$dll"
sudo cp "$srcso" "$so"
ls -la "$dll" "$so"
strings "$dll" | grep -m1 -oE 'Kestrel[^ ]*|FEX-[0-9A-Za-z._-]*' || true
