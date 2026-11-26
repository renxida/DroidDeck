#!/usr/bin/env python3
"""Skip binding a uniform set that is already bound at that index in the current draw list.

    rd_skip_redundant_set_binds.py GODOT_SRC

RenderingDevice::draw_list_bind_uniform_set marks the set "needs rebind" on every call, even when the same
set is already bound there. The 2D renderer calls it for the material set on every batch, so a frame of
canvas items made of ShaderMaterials rebinds the same descriptor set again and again. On the phone each
vkCmdBindDescriptorSets costs about 25 us of main-thread time through the PowerVR driver and the hybris layer
(Nov 24 vkcount slope: 810 set binds for 604 draws). Pipelines, vertex arrays and index arrays already skip
redundant binds this way; this does the same for uniform sets.

Correctness: a uniform set never changes after it is made, the "bound" flag is cleared whenever a pipeline
change invalidates the set's index (that path is untouched), and each draw list starts with nothing bound, so
an identical driver id with bound still true means the GPU already has exactly this set there.
DROIDDECK_RD_REBIND_SETS=1 at run time restores the old behaviour.
Fails unless the anchor is found exactly once.
"""
import os
import sys

ANCHOR = """	const UniformSet *uniform_set = uniform_set_owner.get_or_null(p_uniform_set);
	ERR_FAIL_NULL(uniform_set);

	if (p_index > draw_list.state.set_count) {
		draw_list.state.set_count = p_index;
	}

	draw_list.state.sets[p_index].uniform_set_driver_id = uniform_set->driver_id; // Update set pointer.
	draw_list.state.sets[p_index].bound = false; // Needs rebind.
"""

PATCHED = """	const UniformSet *uniform_set = uniform_set_owner.get_or_null(p_uniform_set);
	ERR_FAIL_NULL(uniform_set);

	// Kestrel: the same set already bound at this index is a no-op (DROIDDECK_RD_REBIND_SETS=1 rebinds anyway).
	static const bool droiddeck_rebind_sets = [] {
		const char *v = getenv("DROIDDECK_RD_REBIND_SETS");
		return v && v[0] == '1';
	}();
	if (!droiddeck_rebind_sets && draw_list.state.sets[p_index].bound && draw_list.state.sets[p_index].uniform_set == p_uniform_set &&
			draw_list.state.sets[p_index].uniform_set_driver_id == uniform_set->driver_id) {
		return;
	}

	if (p_index > draw_list.state.set_count) {
		draw_list.state.set_count = p_index;
	}

	draw_list.state.sets[p_index].uniform_set_driver_id = uniform_set->driver_id; // Update set pointer.
	draw_list.state.sets[p_index].bound = false; // Needs rebind.
"""


def patch(source):
    if source.count(ANCHOR) != 1:
        raise SystemExit("rd_skip_redundant_set_binds: expected the draw_list_bind_uniform_set body exactly once")
    out = source.replace(ANCHOR, PATCHED)
    if "#include <stdlib.h>" not in out:
        out = out.replace('#include "rendering_device.h"\n', '#include "rendering_device.h"\n\n#include <stdlib.h>\n', 1)
    if "#include <stdlib.h>" not in out:
        raise SystemExit("rd_skip_redundant_set_binds: could not add <stdlib.h>")
    return out


def main(argv):
    (godot,) = argv
    path = os.path.join(godot, "servers", "rendering", "rendering_device.cpp")
    with open(path) as f:
        src = f.read()
    with open(path, "w") as f:
        f.write(patch(src))
    print("rd_skip_redundant_set_binds: patched", path)


if __name__ == "__main__":
    main(sys.argv[1:])
