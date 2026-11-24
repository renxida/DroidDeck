#!/usr/bin/env python3
"""Write what the 2D renderer is asked to draw, a frame at a time, as text, so a real scene can be replayed off the phone.

    canvas_trace.py GODOT_SRC

Off by default. With

    DROIDDECK_NATIVE_CANVAS_TRACE=<dir>          write traces there
    DROIDDECK_NATIVE_CANVAS_TRACE_EVERY=<s>      one frame every s seconds (default 20), at most 30 files

the renderer writes <dir>/canvas-<frame>.trace for a frame now and then: every canvas_render_items call of that
frame (render target and its size, canvas transform, modulate, light counts, default filter and repeat) and every
item it draws, in order (final transform, modulate, clip rect and clip owner, material, canvas group, back-buffer
copy, skeleton, z), with its commands: rects and nine-patches with their rects, source rects, colours and flags;
primitives with their points; transforms; and for polygons, meshes, multimeshes and particles only their bounds
and triangle or instance counts. Textures appear only as an id and a size. No pixels, no vertices of meshes or
polygons, no text: what leaves the phone is the scene's layout and batching structure, never its art.

tools/native-engine/replay rebuilds such a frame through the RenderingServer with placeholder textures of the
same sizes, so draw and bind counts for every renderer arm can be measured on lavapipe in seconds.
Apply after canvas_poly_batch.py if both are used (either order works). Fails unless every anchor is found once.
"""
import os
import sys

REQUEST = "RendererCanvasRender::PolygonID RendererCanvasRenderRD::request_polygon("
STORED = "\tpolygon_buffers.polygons[id] = pb;\n"
STORED_NEW = STORED + "\tdroiddeck_trace_remember_polygon(id, p_indices.size() ? p_count : p_points.size(), p_uvs.size() == p_points.size(), p_colors.size(), p_bones.size() > 0);\n"
FREE = "void RendererCanvasRenderRD::free_polygon(PolygonID p_polygon) {\n"
FREE_NEW = FREE + "\tdroiddeck_trace_polygons.erase(p_polygon);\n"
RENDER = "void RendererCanvasRenderRD::canvas_render_items(RID p_to_render_target, Item *p_item_list, const Color &p_modulate, Light *p_light_list, Light *p_directional_light_list, const Transform2D &p_canvas_transform, RenderingServer::CanvasItemTextureFilter p_default_filter, RenderingServer::CanvasItemTextureRepeat p_default_repeat, bool p_snap_2d_vertices_to_pixel, bool &r_sdf_used, RenderingMethod::RenderInfo *r_render_info) {\n"
RENDER_NEW = RENDER + "\tdroiddeck_canvas_trace(p_to_render_target, p_item_list, p_modulate, p_light_list, p_directional_light_list, p_canvas_transform, p_default_filter, p_default_repeat);\n"

HELPERS = r"""
// Kestrel: canvas trace (canvas_trace.py). On only with DROIDDECK_NATIVE_CANVAS_TRACE=<dir>.
#include "core/os/os.h"
#include "servers/rendering/rendering_server_globals.h"
#include <stdio.h>
#include <stdlib.h>
struct DroiddeckTracePolygon {
	uint32_t triangles = 0;
	bool uvs = false;
	uint32_t colors = 0;
	bool bones = false;
};
static HashMap<RendererCanvasRender::PolygonID, DroiddeckTracePolygon> droiddeck_trace_polygons;
static const char *droiddeck_trace_dir() {
	static const char *dir = [] {
		const char *v = getenv("DROIDDECK_NATIVE_CANVAS_TRACE");
		return v && *v ? v : (const char *)nullptr;
	}();
	return dir;
}
static void droiddeck_trace_remember_polygon(RendererCanvasRender::PolygonID p_id, int p_index_count, bool p_uvs, int p_colors, bool p_bones) {
	if (!droiddeck_trace_dir()) {
		return;
	}
	DroiddeckTracePolygon p;
	p.triangles = p_index_count > 0 ? uint32_t(p_index_count / 3) : 0;
	p.uvs = p_uvs;
	p.colors = uint32_t(p_colors);
	p.bones = p_bones;
	droiddeck_trace_polygons.insert(p_id, p);
}
static void droiddeck_trace_texture(FILE *f, RID p_texture) {
	if (!p_texture.is_valid()) {
		fprintf(f, " tex 0 0 0");
		return;
	}
	RendererRD::TextureStorage *ts = RendererRD::TextureStorage::get_singleton();
	Size2 size;
	if (ts->owns_texture(p_texture)) {
		size = ts->texture_size_with_proxy(p_texture);
	}
	fprintf(f, " tex %llu %d %d", (unsigned long long)p_texture.get_id(), int(size.x), int(size.y));
}
static void droiddeck_trace_xf(FILE *f, const char *p_name, const Transform2D &p_xf) {
	fprintf(f, " %s %g %g %g %g %g %g", p_name, p_xf.columns[0].x, p_xf.columns[0].y, p_xf.columns[1].x, p_xf.columns[1].y, p_xf.columns[2].x, p_xf.columns[2].y);
}
static void droiddeck_trace_rect(FILE *f, const char *p_name, const Rect2 &p_r) {
	fprintf(f, " %s %g %g %g %g", p_name, p_r.position.x, p_r.position.y, p_r.size.x, p_r.size.y);
}
static void droiddeck_trace_color(FILE *f, const char *p_name, const Color &p_c) {
	fprintf(f, " %s %g %g %g %g", p_name, p_c.r, p_c.g, p_c.b, p_c.a);
}
static void droiddeck_canvas_trace(RID p_target, RendererCanvasRender::Item *p_list, const Color &p_modulate, RendererCanvasRender::Light *p_lights, RendererCanvasRender::Light *p_directional_lights, const Transform2D &p_canvas_transform, RS::CanvasItemTextureFilter p_filter, RS::CanvasItemTextureRepeat p_repeat) {
	const char *dir = droiddeck_trace_dir();
	if (!dir) {
		return;
	}
	static const double every = [] {
		const char *v = getenv("DROIDDECK_NATIVE_CANVAS_TRACE_EVERY");
		const double s = v && *v ? atof(v) : 20.0;
		return s > 0 ? s : 20.0;
	}();
	static FILE *out = nullptr;
	static uint64_t seen_frame = ~0ull;
	static uint64_t next_usec = 0;
	static int written = 0;
	static int calls = 0;
	const uint64_t frame = RSG::rasterizer->get_frame_number();
	if (frame != seen_frame) {
		seen_frame = frame;
		if (out) {
			fprintf(out, "end calls %d\n", calls);
			fclose(out);
			out = nullptr;
			written++;
		}
		const uint64_t now = OS::get_singleton()->get_ticks_usec();
		if (written < 30 && now >= next_usec) {
			next_usec = now + uint64_t(every * 1e6);
			char path[1024];
			snprintf(path, sizeof path, "%s/canvas-%08llu.trace", dir, (unsigned long long)frame);
			out = fopen(path, "w");
			calls = 0;
			if (out) {
				fprintf(out, "droiddeck-canvas-trace 1 frame %llu\n", (unsigned long long)frame);
			}
		}
	}
	if (!out) {
		return;
	}
	calls++;
	RendererRD::TextureStorage *ts = RendererRD::TextureStorage::get_singleton();
	RendererRD::MeshStorage *ms = RendererRD::MeshStorage::get_singleton();
	const Size2i target_size = ts->render_target_get_size(p_target);
	int lights = 0, directional = 0;
	for (RendererCanvasRender::Light *l = p_lights; l; l = l->next_ptr) {
		lights++;
	}
	for (RendererCanvasRender::Light *l = p_directional_lights; l; l = l->next_ptr) {
		directional++;
	}
	HashMap<const RendererCanvasRender::Item *, int> index;
	int n = 0;
	for (const RendererCanvasRender::Item *ci = p_list; ci; ci = ci->next) {
		index.insert(ci, n++);
	}
	fprintf(out, "call target %llu %d %d items %d lights %d %d filter %d repeat %d", (unsigned long long)p_target.get_id(), target_size.x, target_size.y, n, lights, directional, int(p_filter), int(p_repeat));
	droiddeck_trace_xf(out, "canvas", p_canvas_transform);
	droiddeck_trace_color(out, "modulate", p_modulate);
	fprintf(out, "\n");
	auto owner = [&](const RendererCanvasRender::Item *p_item) {
		if (!p_item) {
			return -1;
		}
		const int *i = index.getptr(p_item);
		return i ? *i : -2;
	};
	for (const RendererCanvasRender::Item *ci = p_list; ci; ci = ci->next) {
		const RID material = ci->material_owner ? ci->material_owner->material : ci->material;
		fprintf(out, "item");
		droiddeck_trace_xf(out, "xf", ci->final_transform);
		droiddeck_trace_color(out, "mod", ci->final_modulate);
		fprintf(out, " clip %d %d", int(ci->clip), owner(ci->final_clip_owner));
		droiddeck_trace_rect(out, "cliprect", ci->final_clip_rect);
		fprintf(out, " material %llu %d", (unsigned long long)material.get_id(), owner(ci->material_owner));
		fprintf(out, " group %d %d %d", int(ci->use_canvas_group), ci->canvas_group ? int(ci->canvas_group->mode) : -1, owner(ci->canvas_group_owner));
		fprintf(out, " backbuffer %d", ci->copy_back_buffer ? (ci->copy_back_buffer->full ? 2 : 1) : 0);
		if (ci->copy_back_buffer) {
			droiddeck_trace_rect(out, "bbrect", ci->copy_back_buffer->rect);
		}
		fprintf(out, " skeleton %d z %d lightmask %d filter %d repeat %d sdf %d repeatsrc %d %d vp %d\n", int(ci->skeleton.is_valid()), ci->z_final, ci->light_mask, int(ci->texture_filter), int(ci->texture_repeat), int(ci->distance_field), int(ci->repeat_source), owner(ci->repeat_source_item), int(ci->vp_render != nullptr));
		for (const RendererCanvasRender::Item::Command *c = ci->commands; c; c = c->next) {
			switch (c->type) {
				case RendererCanvasRender::Item::Command::TYPE_RECT: {
					const RendererCanvasRender::Item::CommandRect *r = static_cast<const RendererCanvasRender::Item::CommandRect *>(c);
					fprintf(out, " rect");
					droiddeck_trace_rect(out, "r", r->rect);
					droiddeck_trace_rect(out, "src", r->source);
					droiddeck_trace_texture(out, r->texture);
					droiddeck_trace_color(out, "c", r->modulate);
					fprintf(out, " flags %d outline %g px %g\n", int(r->flags), r->outline, r->px_range);
				} break;
				case RendererCanvasRender::Item::Command::TYPE_NINEPATCH: {
					const RendererCanvasRender::Item::CommandNinePatch *np = static_cast<const RendererCanvasRender::Item::CommandNinePatch *>(c);
					fprintf(out, " ninepatch");
					droiddeck_trace_rect(out, "r", np->rect);
					droiddeck_trace_rect(out, "src", np->source);
					droiddeck_trace_texture(out, np->texture);
					droiddeck_trace_color(out, "c", np->color);
					fprintf(out, " margin %g %g %g %g center %d axis %d %d\n", np->margin[0], np->margin[1], np->margin[2], np->margin[3], int(np->draw_center), int(np->axis_x), int(np->axis_y));
				} break;
				case RendererCanvasRender::Item::Command::TYPE_POLYGON: {
					const RendererCanvasRender::Item::CommandPolygon *p = static_cast<const RendererCanvasRender::Item::CommandPolygon *>(c);
					const DroiddeckTracePolygon *info = droiddeck_trace_polygons.getptr(p->polygon.polygon_id);
					fprintf(out, " polygon prim %d", int(p->primitive));
					droiddeck_trace_rect(out, "bounds", p->polygon.rect_cache);
					droiddeck_trace_texture(out, p->texture);
					fprintf(out, " triangles %u uvs %d colors %u bones %d\n", info ? info->triangles : 0u, info ? int(info->uvs) : 0, info ? info->colors : 0u, info ? int(info->bones) : 0);
				} break;
				case RendererCanvasRender::Item::Command::TYPE_PRIMITIVE: {
					const RendererCanvasRender::Item::CommandPrimitive *p = static_cast<const RendererCanvasRender::Item::CommandPrimitive *>(c);
					fprintf(out, " primitive %u", p->point_count);
					droiddeck_trace_texture(out, p->texture);
					for (uint32_t j = 0; j < p->point_count && j < 4; j++) {
						fprintf(out, " p %g %g %g %g", p->points[j].x, p->points[j].y, p->uvs[j].x, p->uvs[j].y);
						droiddeck_trace_color(out, "c", p->colors[j]);
					}
					fprintf(out, "\n");
				} break;
				case RendererCanvasRender::Item::Command::TYPE_MESH: {
					const RendererCanvasRender::Item::CommandMesh *m = static_cast<const RendererCanvasRender::Item::CommandMesh *>(c);
					const AABB aabb = ms->owns_mesh(m->mesh) ? ms->mesh_get_aabb(m->mesh, RID()) : AABB();
					fprintf(out, " mesh surfaces %d instance %d", ms->owns_mesh(m->mesh) ? ms->mesh_get_surface_count(m->mesh) : 0, int(m->mesh_instance.is_valid()));
					droiddeck_trace_rect(out, "bounds", Rect2(aabb.position.x, aabb.position.y, aabb.size.x, aabb.size.y));
					droiddeck_trace_texture(out, m->texture);
					droiddeck_trace_xf(out, "xf", m->transform);
					droiddeck_trace_color(out, "c", m->modulate);
					fprintf(out, "\n");
				} break;
				case RendererCanvasRender::Item::Command::TYPE_MULTIMESH: {
					const RendererCanvasRender::Item::CommandMultiMesh *m = static_cast<const RendererCanvasRender::Item::CommandMultiMesh *>(c);
					fprintf(out, " multimesh instances %d", ms->owns_multimesh(m->multimesh) ? int(ms->multimesh_get_instances_to_draw(m->multimesh)) : 0);
					droiddeck_trace_texture(out, m->texture);
					fprintf(out, "\n");
				} break;
				case RendererCanvasRender::Item::Command::TYPE_PARTICLES: {
					const RendererCanvasRender::Item::CommandParticles *p = static_cast<const RendererCanvasRender::Item::CommandParticles *>(c);
					fprintf(out, " particles");
					droiddeck_trace_texture(out, p->texture);
					fprintf(out, "\n");
				} break;
				case RendererCanvasRender::Item::Command::TYPE_TRANSFORM: {
					droiddeck_trace_xf(out, " transform", static_cast<const RendererCanvasRender::Item::CommandTransform *>(c)->xform);
					fprintf(out, "\n");
				} break;
				case RendererCanvasRender::Item::Command::TYPE_CLIP_IGNORE: {
					fprintf(out, " clipignore %d\n", int(static_cast<const RendererCanvasRender::Item::CommandClipIgnore *>(c)->ignore));
				} break;
				case RendererCanvasRender::Item::Command::TYPE_ANIMATION_SLICE: {
					const RendererCanvasRender::Item::CommandAnimationSlice *s = static_cast<const RendererCanvasRender::Item::CommandAnimationSlice *>(c);
					fprintf(out, " slice %g %g %g %g\n", s->animation_length, s->slice_begin, s->slice_end, s->offset);
				} break;
				default: {
					fprintf(out, " other %d\n", int(c->type));
				} break;
			}
		}
	}
	fflush(out);
}

"""


def patch_source(text):
    for anchor in (REQUEST, STORED, FREE, RENDER):
        if text.count(anchor) != 1:
            raise SystemExit("canvas_trace: expected exactly one %r" % anchor.strip().splitlines()[0][:80])
    at = text.index(REQUEST)
    line_start = text.rindex("\n", 0, at) + 1
    out = text[:line_start] + HELPERS.lstrip("\n") + "\n" + text[line_start:]
    out = out.replace(STORED, STORED_NEW)
    out = out.replace(FREE, FREE_NEW)
    out = out.replace(RENDER, RENDER_NEW)
    return out


def main(argv):
    (godot,) = argv
    path = os.path.join(godot, "servers", "rendering", "renderer_rd", "renderer_canvas_render_rd.cpp")
    with open(path) as f:
        source = f.read()
    with open(path, "w") as f:
        f.write(patch_source(source))
    print("canvas_trace: patched", path)


if __name__ == "__main__":
    main(sys.argv[1:])
