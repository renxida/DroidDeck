#!/bin/sh
# Builds and runs the host tests for the clean-room FSB5 reader and path table reader (no engine needed).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/fsb_test.$$
c++ -std=c++17 -O1 -g -Wall -Wextra -fno-exceptions -fsanitize=address,undefined \
  "$here/fsb_test.cpp" "$here/../fsb5.cpp" -o "$out"
"$out"
c++ -std=c++17 -O1 -g -Wall -Wextra -fno-exceptions -fsanitize=address,undefined \
  "$here/path_table_test.cpp" "$here/../path_table.cpp" -o "$out"
"$out"
rm -f "$out"
