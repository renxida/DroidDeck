#!/usr/bin/env python3
"""Let one 2D batch draw from several textures, so a texture change no longer starts a new draw.

    canvas_texture_slots.py GODOT_SRC

Godot 4's canvas renderer binds one colour texture per batch (set 3, binding 0), so every change of texture
between consecutive rects, nine-patches or primitives starts a new batch: a new draw (about 37 us of main-thread
time on the phone) and a new descriptor-set bind (about 25 us). In the batch census this is the biggest reason
batches break.

This patch adds, off by default:

    DROIDDECK_NATIVE_CANVAS_TEXTURE_SLOTS=<n>   up to n (1..7) extra textures per batch; unset or 0 = stock

When on, the canvas shader is compiled with DROIDDECK_TEXTURE_SLOTS, which declares binding 5 of set 3 as an
array of seven textures and picks the texture for each instance from three spare bits (29..31) of its flags:
0 is the batch's own colour texture, 1..7 an entry of the array. Each index is a constant, and every pixel of a
primitive belongs to one instance, so sampling stays as stock does it. When a rect, nine-patch or primitive
brings a texture other than the batch's, it joins the batch in a free slot instead of breaking it if the batch
has no material and no LCD blend, the texture is not MSDF, and its sampler, filter and repeat bits, normal map,
specular map, shininess and flags all equal the batch's. The uniform-set cache key includes the slot textures.
Off, nothing changes: binding 5 is neither declared nor bound.

The census counts joins and descriptor sets made through droiddeck_canvas_slots_take_joined() and
droiddeck_canvas_slots_take_sets(). Apply after canvas_poly_batch.py and before canvas_batch_census.py.
Fails unless every anchor is found the expected number of times.
"""
import os
import re
import sys

SLOT_SHIFT = 29

# --- shaders -------------------------------------------------------------------------------------------------

UNIFORMS_ANCHOR = "layout(set = 3, binding = 3) uniform sampler texture_sampler;\n"
UNIFORMS_NEW = UNIFORMS_ANCHOR + """
#ifdef DROIDDECK_TEXTURE_SLOTS
// Kestrel (canvas_texture_slots.py): extra colour textures of this batch, picked by flags bits 29..31.
layout(set = 3, binding = 5) uniform texture2D droiddeck_slot_textures[7];
#define DROIDDECK_SLOT_SHIFT %du
#endif
""" % SLOT_SHIFT

SAMPLE = "\t\tcolor *= texture(sampler2D(color_texture, texture_sampler), uv);\n"
SAMPLE_NEW = """#ifdef DROIDDECK_TEXTURE_SLOTS
		switch (draw_data.flags >> DROIDDECK_SLOT_SHIFT) {
			case 1u: color *= texture(sampler2D(droiddeck_slot_textures[0], texture_sampler), uv); break;
			case 2u: color *= texture(sampler2D(droiddeck_slot_textures[1], texture_sampler), uv); break;
			case 3u: color *= texture(sampler2D(droiddeck_slot_textures[2], texture_sampler), uv); break;
			case 4u: color *= texture(sampler2D(droiddeck_slot_textures[3], texture_sampler), uv); break;
			case 5u: color *= texture(sampler2D(droiddeck_slot_textures[4], texture_sampler), uv); break;
			case 6u: color *= texture(sampler2D(droiddeck_slot_textures[5], texture_sampler), uv); break;
			case 7u: color *= texture(sampler2D(droiddeck_slot_textures[6], texture_sampler), uv); break;
			default: color *= texture(sampler2D(color_texture, texture_sampler), uv); break;
		}
#else
""" + SAMPLE + "#endif\n"

# --- header --------------------------------------------------------------------------------------------------

TEX_INFO = "\t\tTextureInfo *tex_info;\n"
TEX_INFO_NEW = "\t\tTextureInfo *tex_info = nullptr; // Kestrel: stock leaves it unset in a draw list's first batch\n"

BATCH_END = "\t\tbool has_blend = false;\n\t\tuint32_t flags = 0;\n\t};\n"
BATCH_END_NEW = ("\t\tbool has_blend = false;\n\t\tuint32_t flags = 0;\n"
                 "\t\t// Kestrel (canvas_texture_slots.py): diffuse textures of instances whose flags pick slot 1..7.\n"
                 "\t\tRID droiddeck_slots[7];\n\t\tuint32_t droiddeck_slot_count = 0;\n\t};\n")

KEY_FIELDS = "\t\tTextureState state;\n\t\tRID instance_data;\n\n\t\tRIDSetKey() {\n"
KEY_FIELDS_NEW = "\t\tTextureState state;\n\t\tRID instance_data;\n\t\tRID droiddeck_slots[7];\n\n\t\tRIDSetKey() {\n"

KEY_EQ = "\t\t\treturn state == p_val.state && instance_data == p_val.instance_data;\n"
KEY_EQ_NEW = """\t\t\tif (!(state == p_val.state && instance_data == p_val.instance_data)) {
\t\t\t\treturn false;
\t\t\t}
\t\t\tfor (int i = 0; i < 7; i++) {
\t\t\t\tif (droiddeck_slots[i] != p_val.droiddeck_slots[i]) {
\t\t\t\t\treturn false;
\t\t\t\t}
\t\t\t}
\t\t\treturn true;
"""

KEY_HASH = "\t\t\th = hash_murmur3_one_64(instance_data.get_id(), h);\n"
KEY_HASH_NEW = KEY_HASH + """\t\t\tfor (int i = 0; i < 7 && droiddeck_slots[i].is_valid(); i++) {
\t\t\t\th = hash_murmur3_one_64(droiddeck_slots[i].get_id(), h);
\t\t\t}
"""

# --- source --------------------------------------------------------------------------------------------------

INCLUDE = '#include "renderer_canvas_render_rd.h"\n'
HELPERS = r"""
// Kestrel: several textures per 2D batch (canvas_texture_slots.py). On only with DROIDDECK_NATIVE_CANVAS_TEXTURE_SLOTS=<1..7>.
#include <stdlib.h>
#include <atomic>
static std::atomic<uint32_t> droiddeck_canvas_slots_joined{ 0 };
static std::atomic<uint32_t> droiddeck_canvas_slots_sets{ 0 };
uint32_t droiddeck_canvas_slots_max() {
	static const uint32_t max_slots = [] {
		const char *v = getenv("DROIDDECK_NATIVE_CANVAS_TEXTURE_SLOTS");
		const int n = v && *v ? atoi(v) : 0;
		return uint32_t(n < 0 ? 0 : (n > 7 ? 7 : n));
	}();
	return max_slots;
}
uint32_t droiddeck_canvas_slots_take_joined() {
	return droiddeck_canvas_slots_joined.exchange(0, std::memory_order_relaxed);
}
uint32_t droiddeck_canvas_slots_take_sets() {
	return droiddeck_canvas_slots_sets.exchange(0, std::memory_order_relaxed);
}
// The texture info that the last join placed, and the flag bits that pick its slot. new_instance_data adds the
// bits to an instance drawn with that info; every place that sets a batch's own texture clears them.
static const void *droiddeck_slot_info = nullptr;
static uint32_t droiddeck_slot_bits = 0;
template <typename B>
static void droiddeck_slot_reset(B *p_batch) {
	for (int i = 0; i < 7; i++) {
		p_batch->droiddeck_slots[i] = RID();
	}
	p_batch->droiddeck_slot_count = 0;
	droiddeck_slot_info = nullptr;
	droiddeck_slot_bits = 0;
}
// Try to draw an instance with p_info in p_batch without a new batch. True: the next new_instance_data call
// with p_info picks the slot.
template <typename B, typename T>
static bool droiddeck_slot_join(B *p_batch, T *p_info) {
	const uint32_t max_slots = droiddeck_canvas_slots_max();
	const T *own = p_batch->tex_info;
	// An empty batch copied from the last one may join too: it keeps that batch's descriptor set, so the bind
	// is skipped. The first batch of a draw list has no texture (tex_info starts null with this patch).
	if (max_slots == 0 || own == nullptr || p_info == nullptr) {
		return false;
	}
	if (p_batch->material.is_valid() || p_batch->material_data != nullptr || p_batch->has_blend) {
		return false;
	}
	if (p_info->state.other != own->state.other || p_info->state.texture_is_data()) {
		return false; // filter, repeat, linear colours or MSDF differ
	}
	if (p_info->sampler != own->sampler || p_info->normal != own->normal || p_info->specular != own->specular ||
			p_info->specular_shininess != own->specular_shininess || p_info->flags != own->flags || p_info->diffuse.is_null()) {
		return false;
	}
	uint32_t slot = 0;
	if (p_info->diffuse != own->diffuse) {
		for (uint32_t i = 0; i < p_batch->droiddeck_slot_count; i++) {
			if (p_batch->droiddeck_slots[i] == p_info->diffuse) {
				slot = i + 1;
				break;
			}
		}
		if (slot == 0) {
			if (p_batch->droiddeck_slot_count >= max_slots) {
				return false;
			}
			p_batch->droiddeck_slots[p_batch->droiddeck_slot_count++] = p_info->diffuse;
			slot = p_batch->droiddeck_slot_count;
		}
	}
	droiddeck_slot_info = p_info;
	droiddeck_slot_bits = slot << %d;
	droiddeck_canvas_slots_joined.fetch_add(1, std::memory_order_relaxed);
	return true;
}
""" % SLOT_SHIFT

RESIZE = "\tstate.batch_texture_uniforms.resize(5);\n"
RESIZE_NEW = "\tstate.batch_texture_uniforms.resize(droiddeck_canvas_slots_max() ? 6 : 5);\n"

DEFINES = '\t\tglobal_defines += "\\n#define SAMPLERS_BINDING_FIRST_INDEX " + itos(SAMPLERS_BINDING_FIRST_INDEX) + "\\n";\n'
DEFINES_NEW = DEFINES + '\t\tif (droiddeck_canvas_slots_max()) {\n\t\t\tglobal_defines += "\\n#define DROIDDECK_TEXTURE_SLOTS\\n";\n\t\t}\n'

CLEARED = "\ttexture_info_map.clear();\n"
CLEARED_NEW = CLEARED + "\tdroiddeck_slot_info = nullptr; // texture infos are freed here, and their addresses reused\n"

FLAGS = "\tinstance_data->flags = p_base_flags; // Reset on each command for safety.\n"
FLAGS_NEW = FLAGS + "\tif (p_info == droiddeck_slot_info) {\n\t\tinstance_data->flags |= droiddeck_slot_bits;\n\t}\n"

KEY_MADE = """				state.canvas_instance_data_buffers[state.current_data_buffer_index].instance_buffers[p_batch->instance_buffer_index]);
"""
KEY_MADE_NEW = KEY_MADE + """		for (int i = 0; i < 7; i++) {
			key.droiddeck_slots[i] = p_batch->droiddeck_slots[i];
		}
"""

UNIFORM4 = "\t\t\tuniform_ptrw[4] = RD::Uniform(RD::UNIFORM_TYPE_STORAGE_BUFFER, 4, state.canvas_instance_data_buffers[state.current_data_buffer_index].instance_buffers[p_batch->instance_buffer_index]);\n"
UNIFORM4_NEW = UNIFORM4 + """			if (droiddeck_canvas_slots_max()) {
				RD::Uniform slots;
				slots.uniform_type = RD::UNIFORM_TYPE_TEXTURE;
				slots.binding = 5;
				for (int i = 0; i < 7; i++) {
					slots.append_id(p_batch->droiddeck_slots[i].is_valid() ? p_batch->droiddeck_slots[i] : p_batch->tex_info->diffuse);
				}
				uniform_ptrw[5] = slots;
			}
			droiddeck_canvas_slots_sets.fetch_add(1, std::memory_order_relaxed);
"""

# A texture break: "if (r_current_batch->tex_info != tex_info) {" then a new batch taking the texture.
BREAK = re.compile(r"^(\t+)if \(r_current_batch->tex_info != tex_info\) \{( //[^\n]*)?\n"
                   r"(\t+r_current_batch = _new_batch\(r_batch_broken\);\n)"
                   r"(\t+)r_current_batch->tex_info = tex_info;\n", re.M)
OWN = re.compile(r"^(\t+)r_current_batch->tex_info = tex_info;\n", re.M)
NEVER_BATCHED = "Polygon's can't be batched"


def patch_shader_uniforms(text):
    if text.count(UNIFORMS_ANCHOR) != 1:
        raise SystemExit("canvas_texture_slots: expected the texture_sampler declaration once")
    return text.replace(UNIFORMS_ANCHOR, UNIFORMS_NEW)


def patch_shader(text):
    if text.count(SAMPLE) != 1:
        raise SystemExit("canvas_texture_slots: expected the colour texture sample once")
    return text.replace(SAMPLE, SAMPLE_NEW)


def patch_header(text):
    for anchor in (TEX_INFO, BATCH_END, KEY_FIELDS, KEY_EQ, KEY_HASH):
        if text.count(anchor) != 1:
            raise SystemExit("canvas_texture_slots: expected exactly one %r in the header" % anchor.strip().splitlines()[0])
    text = text.replace(TEX_INFO, TEX_INFO_NEW)
    text = text.replace(BATCH_END, BATCH_END_NEW)
    text = text.replace(KEY_FIELDS, KEY_FIELDS_NEW)
    text = text.replace(KEY_EQ, KEY_EQ_NEW)
    return text.replace(KEY_HASH, KEY_HASH_NEW)


def patch_source(text):
    for anchor in (INCLUDE, RESIZE, DEFINES, CLEARED, FLAGS, KEY_MADE, UNIFORM4):
        if text.count(anchor) != 1:
            raise SystemExit("canvas_texture_slots: expected exactly one %r" % anchor.strip().splitlines()[0])
    text = text.replace(INCLUDE, INCLUDE + HELPERS)
    text = text.replace(RESIZE, RESIZE_NEW)
    text = text.replace(DEFINES, DEFINES_NEW)
    text = text.replace(CLEARED, CLEARED_NEW)
    text = text.replace(FLAGS, FLAGS_NEW)
    text = text.replace(KEY_MADE, KEY_MADE_NEW)
    text = text.replace(UNIFORM4, UNIFORM4_NEW)

    # Every place that gives a batch its own texture forgets the slots.
    text, owns = OWN.subn(lambda m: m.group(0) + m.group(1) + "droiddeck_slot_reset(r_current_batch);\n", text)

    joined = 0

    def join(m):
        nonlocal joined
        before = text_ref[: m.start()]
        # The polygon path always starts its own batch first, so a join there gains nothing.
        if NEVER_BATCHED in before[before.rfind("case Item::Command::"):]:
            return m.group(0)
        joined += 1
        return (m.group(1) + "if (r_current_batch->tex_info != tex_info && !droiddeck_slot_join(r_current_batch, tex_info)) {"
                + (m.group(2) or "") + "\n" + m.group(3) + m.group(4) + "r_current_batch->tex_info = tex_info;\n")

    text_ref = text
    text = BREAK.sub(join, text)
    if joined < 4 or owns < joined:
        raise SystemExit("canvas_texture_slots: found %d texture breaks and %d texture sets; expected at least 4" % (joined, owns))
    return text, joined


def main(argv):
    (godot,) = argv
    rd = os.path.join(godot, "servers", "rendering", "renderer_rd")
    edits = [
        (os.path.join(rd, "shaders", "canvas_uniforms_inc.glsl"), patch_shader_uniforms),
        (os.path.join(rd, "shaders", "canvas.glsl"), patch_shader),
        (os.path.join(rd, "renderer_canvas_render_rd.h"), patch_header),
    ]
    for path, fn in edits:
        with open(path) as f:
            text = f.read()
        with open(path, "w") as f:
            f.write(fn(text))
    path = os.path.join(rd, "renderer_canvas_render_rd.cpp")
    with open(path) as f:
        text = f.read()
    text, joined = patch_source(text)
    with open(path, "w") as f:
        f.write(text)
    print("canvas_texture_slots: patched %d texture breaks in %s" % (joined, path))


if __name__ == "__main__":
    main(sys.argv[1:])
