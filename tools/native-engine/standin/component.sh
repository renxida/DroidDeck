#!/bin/sh
# Print the wrapper arguments that pick the engine component for a gate or soak run:
#   sh component.sh WRAPPER pinned|native-engine-N WORKDIR
# "pinned" prints "--component pinned" (gate.py/soak.py turn that into --fetch, as on the phone). A release tag
# is downloaded from this repository's public release asset into WORKDIR and printed as --component DIR plus
# Valve's libsteam_api.so (--steam-api).
# Components up to native-engine-38 carry a no-op Steam stand-in in stubs/, which the wrapper refuses. While no
# stub-free component is published, CI drops that folder from its own copy, so the stub is never placed or
# loaded, and passes Valve's real libsteam_api.so instead; the engine and kit are unchanged.
set -eu
WRAPPER=$1 COMPONENT=$2 WORK=$3
mkdir -p "$WORK"
if [ "$COMPONENT" = pinned ]; then
  src=$(python3 -c 'import runpy, sys; print(runpy.run_path(sys.argv[1])["fetch"]())' "$WRAPPER")
  echo "pinned component: $(basename "$src")" >&2
  if [ ! -e "$src/stubs" ]; then
    echo "--component pinned"
    exit 0
  fi
  C=$WORK/$(basename "$src")
  rm -rf "$C" && cp -a "$src" "$C"
else
  case $COMPONENT in native-engine-[0-9]*) ;; *) echo "component must be pinned or native-engine-N" >&2; exit 1 ;; esac
  C=$WORK/$COMPONENT
  rm -rf "$C" && mkdir -p "$C"
  curl -fsSL -o "$WORK/c.tar.zst" "https://github.com/${GITHUB_REPOSITORY:-renxida/DroidDeck}/releases/download/$COMPONENT/native-engine.tar.zst"
  zstd -dc "$WORK/c.tar.zst" | tar -C "$C" -x
fi
if [ -e "$C/stubs" ]; then
  rm -rf "$C/stubs"
  echo "::warning::$(basename "$C") carries a Steam stand-in (stubs/); the gate removed it from its copy and runs with Valve's libsteam_api.so" >&2
fi
steam=$(python3 -c 'import runpy, sys; print(runpy.run_path(sys.argv[1])["steam_api"]())' "$WRAPPER")
echo "--component $C --steam-api $steam"
