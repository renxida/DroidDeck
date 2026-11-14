#!/bin/sh
# Builds and runs the host tests for the clean-room skeleton core (no engine needed).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/skel_test.$$
c++ -std=c++17 -O1 -g -Wall -Wextra -fno-exceptions -fsanitize=address,undefined \
  "$here/skel_test.cpp" "$here/../json.cpp" "$here/../skel.cpp" "$here/../skel_json.cpp" "$here/../skel_binary.cpp" "$here/../atlas.cpp" -o "$out"
"$out"
rm -f "$out"
# The real-file checker only compiles here (CI has no game files); run it by hand on files you have.
c++ -std=c++17 -O1 -Wall -Wextra -fno-exceptions \
  "$here/real_check.cpp" "$here/../json.cpp" "$here/../skel.cpp" "$here/../skel_json.cpp" "$here/../skel_binary.cpp" "$here/../atlas.cpp" -o "$out"
"$out" && echo "real_check builds (no files given)"
rm -f "$out"
