#!/usr/bin/env python3
"""Let rects and nine-patches share 2D batches instead of breaking at every switch between them.

    canvas_quad_merge.py GODOT_SRC

Godot 4's canvas renderer draws rects with its quad shader variant and nine-patches (StyleBoxTexture panels,
buttons) with its nine-patch variant, so a panel followed by its icon or label starts a new batch, and a new
draw (about 37 us of main-thread time on the phone) and pipeline bind, every time. Both are drawn the same way:
six vertices per instance from the same instance data. The only difference is in the fragment shader, where the
nine-patch variant maps the UV through the patch margins.

This patch adds, off by default:

    DROIDDECK_NATIVE_CANVAS_QUAD_MERGE=1   rects batch with nine-patches; unset or 0 = stock

When on, the canvas shader is compiled with DROIDDECK_QUAD_MERGE, which applies the nine-patch mapping only to
instances that mark themselves as nine-patches (pad.x, otherwise unused and zero, set to 1). Rects then record
into nine-patch batches drawn with the nine-patch variant. Their vertex shader and fragment shader do exactly
what the quad variant does, so every pixel stays the same.

Apply before canvas_batch_census.py. Fails unless every anchor is found exactly once.
"""
import os
import sys

# --- shader --------------------------------------------------------------------------------------------------

NP_BEGIN = "#ifdef USE_NINEPATCH\n\n\tint draw_center = 2;\n"
NP_END = "\tuv = uv * draw_data.src_rect.zw + draw_data.src_rect.xy; //apply region if needed\n"


def patch_shader(text):
    if text.count(NP_BEGIN) != 1 or text.count(NP_END) != 1:
        raise SystemExit("canvas_quad_merge: expected the nine-patch UV mapping once in canvas.glsl")
    begin = text.index(NP_BEGIN)
    end = text.index(NP_END, begin) + len(NP_END)
    body = text[begin + len("#ifdef USE_NINEPATCH\n"):end]
    new = ("#ifdef USE_NINEPATCH\n"
           "#ifdef DROIDDECK_QUAD_MERGE\n"
           "\t// Kestrel (canvas_quad_merge.py): rects share nine-patch batches; only nine-patches set pad.x.\n"
           "\tif (draw_data.pad.x != 0.0) {\n"
           "#endif\n"
           + body +
           "#ifdef DROIDDECK_QUAD_MERGE\n"
           "\t}\n"
           "#endif\n")
    return text[:begin] + new + text[end:]


# --- source --------------------------------------------------------------------------------------------------

INCLUDE = '#include "renderer_canvas_render_rd.h"\n'
HELPERS = r"""
// Kestrel: rects batch with nine-patches (canvas_quad_merge.py). On only with DROIDDECK_NATIVE_CANVAS_QUAD_MERGE=1.
#include <stdlib.h>
bool droiddeck_canvas_quad_merge() {
	static const bool on = [] {
		const char *v = getenv("DROIDDECK_NATIVE_CANVAS_QUAD_MERGE");
		return v && *v && atoi(v) > 0;
	}();
	return on;
}
static RendererCanvasRender::Item::Command::Type droiddeck_rect_batch_type() {
	return droiddeck_canvas_quad_merge() ? RendererCanvasRender::Item::Command::TYPE_NINEPATCH : RendererCanvasRender::Item::Command::TYPE_RECT;
}
"""

DEFINES = '\t\tglobal_defines += "\\n#define SAMPLERS_BINDING_FIRST_INDEX " + itos(SAMPLERS_BINDING_FIRST_INDEX) + "\\n";\n'
DEFINES_NEW = DEFINES + '\t\tif (droiddeck_canvas_quad_merge()) {\n\t\t\tglobal_defines += "\\n#define DROIDDECK_QUAD_MERGE\\n";\n\t\t}\n'

RECT = """				if (r_current_batch->command_type != Item::Command::TYPE_RECT) {
					r_current_batch = _new_batch(r_batch_broken);
					r_current_batch->command_type = Item::Command::TYPE_RECT;
					r_current_batch->command = c;
					// default variant
					r_current_batch->shader_variant = SHADER_VARIANT_QUAD;
"""
RECT_NEW = """				if (r_current_batch->command_type != droiddeck_rect_batch_type()) {
					r_current_batch = _new_batch(r_batch_broken);
					r_current_batch->command_type = droiddeck_rect_batch_type();
					r_current_batch->command = c;
					// default variant (canvas_quad_merge.py: the nine-patch one, which draws a rect the same way)
					r_current_batch->shader_variant = droiddeck_canvas_quad_merge() ? SHADER_VARIANT_NINEPATCH : SHADER_VARIANT_QUAD;
"""

NP_MARGIN = "\t\t\t\tinstance_data->ninepatch_margins[0] = np->margin[SIDE_LEFT];\n"
NP_MARGIN_NEW = NP_MARGIN + "\t\t\t\tif (droiddeck_canvas_quad_merge()) {\n\t\t\t\t\tinstance_data->pad[0] = 1.0; // a nine-patch, not a rect\n\t\t\t\t}\n"


def patch_source(text):
    for anchor in (INCLUDE, DEFINES, RECT, NP_MARGIN):
        if text.count(anchor) != 1:
            raise SystemExit("canvas_quad_merge: expected exactly one %r" % anchor.strip().splitlines()[0])
    text = text.replace(INCLUDE, INCLUDE + HELPERS)
    text = text.replace(DEFINES, DEFINES_NEW)
    text = text.replace(RECT, RECT_NEW)
    return text.replace(NP_MARGIN, NP_MARGIN_NEW)


def main(argv):
    (godot,) = argv
    rd = os.path.join(godot, "servers", "rendering", "renderer_rd")
    for path, fn in ((os.path.join(rd, "shaders", "canvas.glsl"), patch_shader),
                     (os.path.join(rd, "renderer_canvas_render_rd.cpp"), patch_source)):
        with open(path) as f:
            text = f.read()
        with open(path, "w") as f:
            f.write(fn(text))
    print("canvas_quad_merge: patched", rd)


if __name__ == "__main__":
    main(sys.argv[1:])
