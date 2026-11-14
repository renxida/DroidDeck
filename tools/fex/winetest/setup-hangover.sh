#!/usr/bin/env bash
# Install Hangover's Wine (ARM64EC, with its stock libarm64ecfex) on an arm64 Ubuntu runner and make a prefix
# whose x86-64 emulator is libarm64ecfex.dll.
#
#   setup-hangover.sh <release tag, e.g. hangover-11.16> <workdir>
#
# Needs GH_TOKEN (GitHub API). Writes <workdir>/fexpaths: where Hangover put libarm64ecfex.dll and .so, so a
# row can swap in another FEX with install-fex.sh. The prefix is $WINEPREFIX (default <workdir>/pfx).
set -euo pipefail
tag=$1 work=$2
mkdir -p "$work/hv"
rel=$(curl -fsSL -H "Authorization: Bearer $GH_TOKEN" "https://api.github.com/repos/AndreRH/hangover/releases/tags/$tag")
echo "$rel" | jq -r '"release " + .tag_name, (.assets[] | "  " + .name)'
url=$(echo "$rel" | jq -r '.assets[] | select(.name | test("ubuntu.?24|noble"; "i")) | select(.name | test("arm64|aarch64"; "i")) | .browser_download_url' | head -1)
[ -n "$url" ] || url=$(echo "$rel" | jq -r '.assets[] | select(.name | test("arm64|aarch64"; "i")) | .browser_download_url' | head -1)
[ -n "$url" ] || { echo "no arm64 asset in $tag" >&2; exit 1; }
echo "asset $url"
curl -fsSL -o "$work/hangover.pkg" "$url"
case "$url" in
  *.deb) cp "$work/hangover.pkg" "$work/hv/hangover.deb" ;;
  *) tar -C "$work/hv" -xf "$work/hangover.pkg" ;;
esac
sudo apt-get update -qq
find "$work/hv" -name '*.deb' -print0 | xargs -0 sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq zstd gdb xvfb >/dev/null
wine --version
{ find / -xdev -name 'libarm64ecfex.*' 2>/dev/null || true; } > "$work/fexpaths"
cat "$work/fexpaths"
export WINEPREFIX=${WINEPREFIX:-$work/pfx} WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="
wineboot -i >/dev/null 2>&1 || true
wine reg add 'HKLM\Software\Microsoft\Wow64\amd64' /ve /d libarm64ecfex.dll /f
wineserver -w
