#!/usr/bin/env python3
"""Draw the contents of canvas groups and clip_children masks in one back-buffer pass instead of three passes each.

    canvas_group_hoist.py GODOT_SRC

Godot 4's canvas renderer draws each CanvasGroup and each clip_children item (Control.clip_children,
CanvasItem.clip_children) like this: it ends the screen pass, copies the screen under the group into the back
buffer (clip modes) or clears it (plain groups), draws the group's children into the back buffer in a pass of its
own, then starts the screen pass again and composites. That is three render passes per group, and on a tile GPU
every pass that resumes the screen reloads and stores the whole frame. A Slay the Spire 2 fight has 5 groups and 17
clip_children items, and the phone shows 32 passes and 81 barriers a frame.

This patch adds, off by default:

    DROIDDECK_NATIVE_CANVAS_GROUP_HOIST=<n>   up to n groups per back-buffer pass; unset or 0 = stock

When on, at the first group of a run the renderer looks ahead and takes every following group it can, until a
group it can't take or anything that reads or copies the screen: a group whose children use only blend-mix
materials that don't read the screen, with no nested groups, skeletons or mipmap blur, whose own material is the
default (or, for clip-and-draw, any blend-mix one), and whose children's bounds keep off every other group's
owner. It draws all their children into a cleared back buffer in one pass, then keeps drawing the screen in the same
pass and composites each group where its owner comes.

Plain groups composite exactly as stock (they already start from a cleared back buffer). Clip groups start from a
cleared back buffer instead of a copy of the screen, and composite with a shader that unpremultiplies the back
buffer and multiplies the owner's alpha by its alpha. Over the same screen that is the same blend,
owner_alpha * children + (1 - owner_alpha * children_alpha) * screen, but rounded once less, so pixels can differ by
a step or two of 255.

Apply after canvas_quad_merge.py and before canvas_batch_census.py. Fails unless every anchor is found exactly once.
"""
import os
import sys

INCLUDE = '#include "renderer_canvas_render_rd.h"\n'
HELPERS = r"""
// Kestrel: canvas group contents in one back-buffer pass (canvas_group_hoist.py).
// On only with DROIDDECK_NATIVE_CANVAS_GROUP_HOIST=<groups per pass>.
#define DROIDDECK_GROUP_HOIST_PATCH 1
#include <atomic>
#include <stdlib.h>
uint32_t droiddeck_canvas_group_hoist() {
	static const uint32_t max = [] {
		const char *v = getenv("DROIDDECK_NATIVE_CANVAS_GROUP_HOIST");
		const int n = v ? atoi(v) : 0;
		return uint32_t(n < 0 ? 0 : (n > 256 ? 256 : n));
	}();
	return max;
}
static std::atomic<uint32_t> droiddeck_hoist_groups{ 0 };
static std::atomic<uint32_t> droiddeck_hoist_passes{ 0 };
uint32_t droiddeck_canvas_group_hoist_take_groups() {
	return droiddeck_hoist_groups.exchange(0, std::memory_order_relaxed);
}
uint32_t droiddeck_canvas_group_hoist_take_passes() {
	return droiddeck_hoist_passes.exchange(0, std::memory_order_relaxed);
}
static bool droiddeck_bb_clear_next = false; // the next back-buffer pass starts by clearing to transparent
static RID droiddeck_clip_shader;
static RID droiddeck_clip_material;
static LocalVector<RendererCanvasRender::Item *> droiddeck_hoisted_owners; // this frame's groups drawn this way
// Stand-in clip items that confine a group's children to its owner's rect in the shared back buffer.
static LocalVector<RendererCanvasRender::Item *> droiddeck_clip_pool;
static RendererCanvasRender::Item *droiddeck_clip_item(uint32_t p_index, const Rect2 &p_rect) {
	while (droiddeck_clip_pool.size() <= p_index) {
		droiddeck_clip_pool.push_back(memnew(RendererCanvasRender::Item));
	}
	droiddeck_clip_pool[p_index]->final_clip_rect = p_rect;
	return droiddeck_clip_pool[p_index];
}
// Where a group's owner composites: a plain group draws its whole group rect; a clip group draws only its own
// shape, inside its own rect (the group rect also covers children that reach past it).
static Rect2 droiddeck_owner_read_rect(const RendererCanvasRender::Item *p_owner) {
	if (p_owner->canvas_group->mode != RS::CANVAS_GROUP_MODE_TRANSPARENT && !p_owner->rect_dirty && p_owner->repeat_source_item == nullptr) {
		return p_owner->final_transform.xform(p_owner->rect).grow(1);
	}
	return p_owner->global_rect_cache.grow(1);
}
static bool droiddeck_hoisted_owner(const RendererCanvasRender::Item *p_item) {
	for (const RendererCanvasRender::Item *o : droiddeck_hoisted_owners) {
		if (o == p_item) {
			return true;
		}
	}
	return false;
}
"""

CTOR = "\t\tmaterial_storage->material_set_shader(default_clip_children_material, default_clip_children_shader);\n\t}\n"
CTOR_NEW = CTOR + r"""
	if (droiddeck_canvas_group_hoist()) {
		// Kestrel (canvas_group_hoist.py): clip groups drawn from a cleared back buffer composite with this.
		droiddeck_clip_shader = material_storage->shader_allocate();
		material_storage->shader_initialize(droiddeck_clip_shader);
		material_storage->shader_set_code(droiddeck_clip_shader, R"(
shader_type canvas_item;
render_mode unshaded;

uniform sampler2D screen_texture : hint_screen_texture, repeat_disable, filter_nearest;

void fragment() {
	vec4 c = textureLod(screen_texture, SCREEN_UV, 0.0);
	if (c.a > 0.0001) {
		c.rgb /= c.a;
	}
	COLOR.rgb = c.rgb;
	COLOR.a *= c.a;
}
)");
		droiddeck_clip_material = material_storage->material_allocate();
		material_storage->material_initialize(droiddeck_clip_material);
		material_storage->material_set_shader(droiddeck_clip_material, droiddeck_clip_shader);
	}
"""

DTOR = "\tmaterial_storage->shader_free(default_clip_children_shader);\n"
DTOR_NEW = DTOR + ("\tfor (RendererCanvasRender::Item *c : droiddeck_clip_pool) {\n\t\tmemdelete(c);\n\t}\n\tdroiddeck_clip_pool.clear();\n"
                   "\tif (droiddeck_clip_material.is_valid()) {\n"
                   "\t\tmaterial_storage->material_free(droiddeck_clip_material);\n"
                   "\t\tmaterial_storage->shader_free(droiddeck_clip_shader);\n"
                   "\t}\n")

LOOP = "\twhile (ci) {\n\t\tif (ci->copy_back_buffer && canvas_group_owner == nullptr) {\n"
LOOP_NEW = ("\tItem *droiddeck_skip_owner = nullptr; // Kestrel (canvas_group_hoist.py): inside a group drawn ahead\n"
            "\tLocalVector<Item *> droiddeck_run_starts;\n"
            "\tLocalVector<Item *> droiddeck_run_owners;\n"
            "\tLocalVector<Rect2> droiddeck_run_spills;\n"
            "\tLocalVector<bool> droiddeck_run_scissor;\n"
            "\tLocalVector<Item *> droiddeck_saved_clips;\n"
            "\tLocalVector<Item *> droiddeck_pending;\n" + LOOP)

GROUP = "\t\tif (ci->canvas_group_owner != nullptr) {\n\t\t\tif (canvas_group_owner == nullptr) {\n"
GROUP_NEW = r"""		// Kestrel (canvas_group_hoist.py): draw this group, and the groups after it that can share the pass, into a
		// cleared back buffer now, without ending the screen pass; then skip their children and composite each owner.
		if (droiddeck_skip_owner == nullptr && canvas_group_owner == nullptr && ci->canvas_group_owner != nullptr && droiddeck_canvas_group_hoist()) {
			bool planned = false;
			for (Item *s : droiddeck_run_starts) {
				planned = planned || s == ci;
			}
			if (!planned) {
				droiddeck_run_starts.clear();
				droiddeck_run_owners.clear();
				droiddeck_run_spills.clear();
				droiddeck_run_scissor.clear();
				auto droiddeck_material_ok = [&](RID p_material, bool p_custom) -> bool {
					if (p_material.is_null()) {
						return true;
					}
					if (!p_custom) {
						return false;
					}
					CanvasMaterialData *md = static_cast<CanvasMaterialData *>(material_storage->material_get_data(p_material, RendererRD::MaterialStorage::SHADER_TYPE_2D));
					return md && md->shader_data && md->shader_data->is_valid() && !md->shader_data->uses_screen_texture && md->shader_data->blend_mode == RendererRD::MaterialStorage::ShaderData::BLEND_MODE_MIX;
				};
				int total = 0;
				Item *it = ci;
				while (it && droiddeck_run_starts.size() < droiddeck_canvas_group_hoist()) {
					if (it->canvas_group_owner != nullptr) {
						Item *owner = it->canvas_group_owner;
						const bool clip_draw = owner->canvas_group->mode == RS::CANVAS_GROUP_MODE_CLIP_AND_DRAW;
						RID owner_material = owner->material_owner == nullptr ? owner->material : owner->material_owner->material;
						bool ok = !owner->canvas_group->blur_mipmaps && !owner->copy_back_buffer && !owner->skeleton.is_valid() && droiddeck_material_ok(owner_material, clip_draw);
						Rect2 spill = clip_draw ? owner->global_rect_cache : Rect2();
						bool have = clip_draw;
						int count = clip_draw ? 1 : 0;
						for (Item *x = it; ok && x != owner; x = x->next) {
							if (x == nullptr || (x != it && x->canvas_group_owner != nullptr) || x->canvas_group != nullptr || x->copy_back_buffer || x->skeleton.is_valid()) {
								ok = false;
								break;
							}
							if (!droiddeck_material_ok(x->material_owner == nullptr ? x->material : x->material_owner->material, true)) {
								ok = false;
								break;
							}
							spill = have ? spill.merge(x->global_rect_cache) : x->global_rect_cache;
							have = true;
							count++;
						}
						const Rect2 read = droiddeck_owner_read_rect(owner);
						// Children reaching past the owner (card art under a smaller mask) are scissored to it.
						const bool scissor = !read.encloses(spill);
						spill = scissor ? read : spill.grow(1);
						for (uint32_t g = 0; ok && g < droiddeck_run_owners.size(); g++) {
							if (droiddeck_run_spills[g].intersects(read) || spill.intersects(droiddeck_owner_read_rect(droiddeck_run_owners[g]))) {
								ok = false;
							}
						}
						if (!ok || item_count + total + count >= MAX_RENDER_ITEMS - 2) {
							break;
						}
						droiddeck_run_starts.push_back(it);
						droiddeck_run_owners.push_back(owner);
						droiddeck_run_spills.push_back(spill);
						droiddeck_run_scissor.push_back(scissor);
						total += count;
						it = owner->next;
						continue;
					}
					// Drawn to the screen between groups: stop at anything that copies or reads the screen.
					if (it->copy_back_buffer || it->canvas_group != nullptr) {
						break;
					}
					RID m = it->material_owner == nullptr ? it->material : it->material_owner->material;
					if (m.is_valid()) {
						CanvasMaterialData *md = static_cast<CanvasMaterialData *>(material_storage->material_get_data(m, RendererRD::MaterialStorage::SHADER_TYPE_2D));
						if (md && md->shader_data && md->shader_data->uses_screen_texture) {
							break;
						}
					}
					it = it->next;
				}
				if (!droiddeck_run_starts.is_empty()) {
					// Owners of an earlier run still waiting to composite read the back buffer this pass will clear,
					// so draw them first.
					bool earlier_owner = false;
					for (int i = 0; i < item_count && !earlier_owner; i++) {
						earlier_owner = items[i]->use_canvas_group && droiddeck_hoisted_owner(items[i]);
					}
					if (earlier_owner) {
						if (update_skeletons) {
							mesh_storage->update_mesh_instances();
							update_skeletons = false;
						}
						_render_batch_items(to_render_target, item_count, canvas_transform_inverse, p_light_list, r_sdf_used, false, r_render_info);
						item_count = 0;
					}
					droiddeck_pending.resize(item_count);
					for (int i = 0; i < item_count; i++) {
						droiddeck_pending[i] = items[i];
					}
					int n = 0;
					uint32_t clips = 0;
					droiddeck_saved_clips.clear();
					for (uint32_t g = 0; g < droiddeck_run_starts.size(); g++) {
						Item *owner = droiddeck_run_owners[g];
						const int first = n;
						if (owner->canvas_group->mode == RS::CANVAS_GROUP_MODE_CLIP_AND_DRAW) {
							owner->use_canvas_group = false;
							items[n++] = owner;
						}
						for (Item *x = droiddeck_run_starts[g]; x != owner; x = x->next) {
							x->use_canvas_group = false;
							items[n++] = x;
						}
						if (droiddeck_run_scissor[g]) {
							const Rect2 read = droiddeck_run_spills[g];
							Item *plain = nullptr; // the stand-in for children with no clip of their own
							for (int i = first; i < n; i++) {
								Item *x = items[i];
								droiddeck_saved_clips.push_back(x->final_clip_owner);
								if (x->final_clip_owner == nullptr) {
									if (plain == nullptr) {
										plain = droiddeck_clip_item(clips++, read);
									}
									x->final_clip_owner = plain;
								} else {
									x->final_clip_owner = droiddeck_clip_item(clips++, x->final_clip_owner->final_clip_rect.intersection(read));
								}
							}
						}
						droiddeck_hoisted_owners.push_back(owner);
					}
					droiddeck_bb_clear_next = true;
					_render_batch_items(to_render_target, n, canvas_transform_inverse, p_light_list, r_sdf_used, true, r_render_info);
					if (droiddeck_bb_clear_next) { // nothing was drawn, but the owners still read a cleared back buffer
						droiddeck_bb_clear_next = false;
						texture_storage->render_target_clear_back_buffer(p_to_render_target, Rect2i(), Color(0, 0, 0, 0));
					}
					uint32_t restored = 0;
					for (uint32_t g = 0; g < droiddeck_run_starts.size(); g++) {
						if (!droiddeck_run_scissor[g]) {
							continue;
						}
						Item *owner = droiddeck_run_owners[g];
						if (owner->canvas_group->mode == RS::CANVAS_GROUP_MODE_CLIP_AND_DRAW) {
							owner->final_clip_owner = droiddeck_saved_clips[restored++];
						}
						for (Item *x = droiddeck_run_starts[g]; x != owner; x = x->next) {
							x->final_clip_owner = droiddeck_saved_clips[restored++];
						}
					}
					for (int i = 0; i < item_count; i++) {
						items[i] = droiddeck_pending[i];
					}
					backbuffer_cleared = false;
					droiddeck_hoist_groups.fetch_add(droiddeck_run_starts.size(), std::memory_order_relaxed);
					droiddeck_hoist_passes.fetch_add(1, std::memory_order_relaxed);
				}
			}
			for (Item *s : droiddeck_run_starts) {
				if (s == ci) {
					droiddeck_skip_owner = ci->canvas_group_owner;
					ci->canvas_group_owner = nullptr;
					break;
				}
			}
		}
		if (droiddeck_skip_owner != nullptr) {
			if (ci == droiddeck_skip_owner) {
				droiddeck_skip_owner = nullptr;
				ci->use_canvas_group = true;
				items[item_count++] = ci;
			} else {
				ci->use_canvas_group = false;
			}
			if (!ci->next || item_count == MAX_RENDER_ITEMS - 1) {
				if (update_skeletons) {
					mesh_storage->update_mesh_instances();
					update_skeletons = false;
				}
				_render_batch_items(to_render_target, item_count, canvas_transform_inverse, p_light_list, r_sdf_used, false, r_render_info);
				item_count = 0;
			}
			ci = ci->next;
			continue;
		}

""" + GROUP

FRAME_END = "\ttexture_info_map.clear();\n"
FRAME_END_NEW = FRAME_END + "\tdroiddeck_hoisted_owners.clear();\n"

MATERIAL = "\t\t\tif (material != current_batch->material) {\n\t\t\t\tcurrent_batch = _new_batch(batch_broken);\n"
MATERIAL_NEW = ("\t\t\tif (ci->use_canvas_group && ci->canvas_group->mode != RS::CANVAS_GROUP_MODE_TRANSPARENT && droiddeck_clip_material.is_valid() && droiddeck_hoisted_owner(ci)) {\n"
                "\t\t\t\tmaterial = droiddeck_clip_material; // Kestrel (canvas_group_hoist.py): drawn from a cleared back buffer\n"
                "\t\t\t}\n" + MATERIAL)

BACKBUFFER = "\t\tfb_uniform_set = texture_storage->render_target_get_backbuffer_uniform_set(p_to_render_target.render_target);\n"
BACKBUFFER_NEW = BACKBUFFER + ("\t\tif (droiddeck_bb_clear_next) { // Kestrel (canvas_group_hoist.py)\n"
                               "\t\t\tdroiddeck_bb_clear_next = false;\n"
                               "\t\t\tclear = true;\n"
                               "\t\t\tclear_color = Color(0, 0, 0, 0);\n"
                               "\t\t}\n")


def patch_source(text):
    pairs = ((INCLUDE, INCLUDE + HELPERS), (CTOR, CTOR_NEW), (DTOR, DTOR_NEW), (LOOP, LOOP_NEW), (GROUP, GROUP_NEW),
             (FRAME_END, FRAME_END_NEW), (MATERIAL, MATERIAL_NEW), (BACKBUFFER, BACKBUFFER_NEW))
    for anchor, _ in pairs:
        if text.count(anchor) != 1:
            raise SystemExit("canvas_group_hoist: expected exactly one %r" % anchor.strip().splitlines()[0])
    for anchor, new in pairs:
        text = text.replace(anchor, new)
    return text


def main(argv):
    (godot,) = argv
    path = os.path.join(godot, "servers", "rendering", "renderer_rd", "renderer_canvas_render_rd.cpp")
    with open(path) as f:
        text = f.read()
    with open(path, "w") as f:
        f.write(patch_source(text))
    print("canvas_group_hoist: patched", path)


if __name__ == "__main__":
    main(sys.argv[1:])
