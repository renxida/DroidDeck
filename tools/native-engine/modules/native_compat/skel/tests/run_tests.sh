#!/bin/sh
# Builds and runs the host tests for the clean-room skeleton core (no engine needed).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/skel_test.$$
c++ -std=c++17 -O1 -g -Wall -Wextra -fno-exceptions -fsanitize=address,undefined \
  "$here/skel_test.cpp" "$here/../json.cpp" "$here/../skel.cpp" "$here/../skel_json.cpp" "$here/../skel_binary.cpp" "$here/../atlas.cpp" -o "$out"
"$out"
rm -f "$out"
