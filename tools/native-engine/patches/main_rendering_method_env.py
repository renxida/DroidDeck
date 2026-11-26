#!/usr/bin/env python3
"""Let DROIDDECK_NATIVE_RENDERING_METHOD pick the renderer when the command line doesn't.

    main_rendering_method_env.py GODOT_SRC

Slay the Spire 2 is a 2D game, but its project asks for Forward+ ("Vulkan 1.3.0 - Forward+" in the phone's
log). Forward+ sets up the clustered 3D renderer at start (its scene shaders and compute effects), so the
phone converts and compiles shader modules that a 2D frame never uses, and its 2D frames go through the same
canvas renderer either way. The Mobile renderer shares that canvas renderer and starts with less.

DROIDDECK_NATIVE_RENDERING_METHOD=mobile (or forward_plus) replaces the project's rendering_method, and the
forward_plus a --rendering-driver alone would pick; a --rendering-method on the command line still wins. Any
other value is ignored with a line saying so. Unset changes nothing. Fails unless its anchor is found once.

Devloop (lavapipe, the batch test's fight scene set to Forward+): Forward+ made 201 pipelines in ~970 ms and
235 shader modules; Mobile 64 pipelines in ~140 ms and 153 modules; launch to quit 5.4 s -> 4.3 s; every
pixel the same.
"""
import os
import sys

MARK = "// Kestrel (main_rendering_method_env.py)"

ANCHOR = """#endif

	if (!rendering_method.is_empty()) {
		if (rendering_method != "forward_plus" &&
"""

PATCHED = """#endif

	""" + MARK + """: DROIDDECK_NATIVE_RENDERING_METHOD stands in for the project's renderer. It is read before
	// a --rendering-driver picks forward_plus for an unset method, so only --rendering-method beats it.
	if (rendering_method.is_empty()) {
		const char *droiddeck_method = getenv("DROIDDECK_NATIVE_RENDERING_METHOD");
		if (droiddeck_method && (String(droiddeck_method) == "mobile" || String(droiddeck_method) == "forward_plus")) {
			rendering_method = droiddeck_method;
			OS::get_singleton()->print("native_compat: rendering method %s (DROIDDECK_NATIVE_RENDERING_METHOD)\\n", droiddeck_method);
		} else if (droiddeck_method && *droiddeck_method) {
			OS::get_singleton()->print("native_compat: DROIDDECK_NATIVE_RENDERING_METHOD=%s ignored (mobile or forward_plus)\\n", droiddeck_method);
		}
	}

	if (!rendering_method.is_empty()) {
		if (rendering_method != "forward_plus" &&
"""


def patch(source):
    if MARK in source:
        raise SystemExit("main_rendering_method_env: already applied")
    if source.count(ANCHOR) != 1:
        raise SystemExit("main_rendering_method_env: expected the rendering_method check exactly once")
    out = source.replace(ANCHOR, PATCHED)
    if "#include <stdlib.h>" not in out:
        out = out.replace('#include "main.h"\n', '#include "main.h"\n\n#include <stdlib.h>\n', 1)
    return out


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    path = os.path.join(sys.argv[1], "main", "main.cpp")
    with open(path, encoding="utf-8") as f:
        src = f.read()
    with open(path, "w", encoding="utf-8") as f:
        f.write(patch(src))
    print("main_rendering_method_env: patched", path)


if __name__ == "__main__":
    main()
