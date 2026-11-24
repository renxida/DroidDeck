#!/usr/bin/env python3
"""Let small plain triangle polygons join the 2D renderer's batches instead of each taking its own draw.

    canvas_poly_batch.py GODOT_SRC

Godot 4's canvas renderer never batches TYPE_POLYGON commands: each one is its own draw (about 37 us of
main-thread time on the phone) with its own vertex buffer. StyleBoxFlat panels, rounded buttons, anti-aliased
lines and anything drawn with draw_polygon or draw_colored_polygon come through here.

This patch adds, off by default:

    DROIDDECK_NATIVE_CANVAS_POLY_BATCH=<n>   polygons of at most n triangles (1..1024) join batches; unset or 0 = stock

When on, request_polygon keeps a CPU copy of each polygon that has no bones or weights and at most n triangles,
and the recorder turns such a polygon, drawn as a triangle list with no material, no 2D lights and no linear
(HDR 2D) colours, into one three-point primitive instance per triangle. Godot already batches three-point
primitives (draw_primitive) by texture, so consecutive panels share one draw. The vertex shader's primitive
path takes the same positions, UVs and world transform; the only difference is that the colour times the
item's modulate is worked out on the CPU and stored as half floats, as Godot does for every primitive, instead
of being multiplied in float on the GPU. That can move a channel by at most one step out of 255.

The census counts converted polygons through droiddeck_canvas_poly_take(), and canvas_reorder.py treats a
convertible polygon as joining primitive batches when DROIDDECK_POLY_BATCH is defined. Apply before canvas_batch_census.py, which
labels its batch starts "polygon: as triangles, ...". Fails unless every anchor is found exactly once.
"""
import os
import sys

REQUEST = "RendererCanvasRender::PolygonID RendererCanvasRenderRD::request_polygon("

STORED = "	polygon_buffers.polygons[id] = pb;\n"
STORED_NEW = STORED + "	droiddeck_poly_remember(id, p_indices, p_points, p_colors, p_uvs, p_bones, p_weights, p_count);\n"

FREE = """void RendererCanvasRenderRD::free_polygon(PolygonID p_polygon) {
"""
FREE_NEW = FREE + "	droiddeck_poly_cpu.erase(p_polygon);\n"

RECORD = """			case Item::Command::TYPE_POLYGON: {
				const Item::CommandPolygon *polygon = static_cast<const Item::CommandPolygon *>(c);
"""

RECORD_NEW = RECORD + """
				// Kestrel (canvas_poly_batch.py): a small plain triangle polygon becomes three-point primitives.
				if (const DroiddeckPolyCpu *cpu = droiddeck_poly_lookup(polygon->polygon.polygon_id);
						cpu && polygon->primitive == RS::PRIMITIVE_TRIANGLES && !use_linear_colors && light_count == 0 && !use_lighting &&
						!(p_item->material_owner ? p_item->material_owner->material : p_item->material).is_valid()) {
					if (r_current_batch->command_type != Item::Command::TYPE_PRIMITIVE || r_current_batch->primitive_points != 3) { // as triangles: command type
						r_current_batch = _new_batch(r_batch_broken);
						r_current_batch->command_type = Item::Command::TYPE_PRIMITIVE;
						r_current_batch->has_blend = false;
						r_current_batch->command = &droiddeck_poly_triangle_command();
						r_current_batch->primitive_points = 3;
						r_current_batch->flags = 0;
						r_current_batch->shader_variant = SHADER_VARIANT_PRIMITIVE;
						r_current_batch->render_primitive = RD::RENDER_PRIMITIVE_TRIANGLES;
					}

					TextureState tex_state(polygon->texture, texture_filter, texture_repeat, false, use_linear_colors);
					TextureInfo *tex_info = texture_info_map.getptr(tex_state);
					if (!tex_info) {
						tex_info = &texture_info_map.insert(tex_state, TextureInfo())->value;
						_prepare_batch_texture_info(polygon->texture, tex_state, tex_info);
					}

					if (r_current_batch->tex_info != tex_info) { // as triangles: texture
						r_current_batch = _new_batch(r_batch_broken);
						r_current_batch->tex_info = tex_info;
					}

					const uint32_t triangles = cpu->indices.size() / 3;
					for (uint32_t t = 0; t < triangles; t++) {
						InstanceData *instance_data = new_instance_data(world, lights, base_flags, r_index, uniforms_ofs, tex_info);
						for (uint32_t j = 0; j < 3; j++) {
							const uint32_t v = uint32_t(cpu->indices[t * 3 + j]);
							instance_data->points[j * 2 + 0] = cpu->points[v].x;
							instance_data->points[j * 2 + 1] = cpu->points[v].y;
							const Vector2 uv = cpu->uvs.is_empty() ? Vector2() : cpu->uvs[v];
							instance_data->uvs[j * 2 + 0] = uv.x;
							instance_data->uvs[j * 2 + 1] = uv.y;
							const Color vc = cpu->colors.is_empty() ? Color(1, 1, 1, 1) : cpu->colors[cpu->colors.size() == 1 ? 0 : v];
							const Color col = vc * base_color;
							instance_data->colors[j * 2 + 0] = (uint32_t(Math::make_half_float(col.g)) << 16) | Math::make_half_float(col.r);
							instance_data->colors[j * 2 + 1] = (uint32_t(Math::make_half_float(col.a)) << 16) | Math::make_half_float(col.b);
						}
						_add_to_batch(r_index, r_batch_broken, r_current_batch);
					}
					droiddeck_canvas_poly_batched.fetch_add(1, std::memory_order_relaxed);
					break;
				}
"""

HELPERS = r"""
// Kestrel: small polygons join batches (canvas_poly_batch.py). On only with DROIDDECK_NATIVE_CANVAS_POLY_BATCH=<max triangles>.
#define DROIDDECK_POLY_BATCH 1
#include <stdlib.h>
#include <atomic>
struct DroiddeckPolyCpu {
	LocalVector<Vector2> points;
	LocalVector<Color> colors; // empty: white; one: that colour for every vertex
	LocalVector<Vector2> uvs; // empty: (0, 0)
	LocalVector<int> indices; // a triangle list
};
static HashMap<RendererCanvasRender::PolygonID, DroiddeckPolyCpu> droiddeck_poly_cpu;
static std::atomic<uint32_t> droiddeck_canvas_poly_batched{ 0 };
uint32_t droiddeck_canvas_poly_take() {
	return droiddeck_canvas_poly_batched.exchange(0, std::memory_order_relaxed);
}
uint32_t droiddeck_canvas_poly_max() {
	static const uint32_t max_triangles = [] {
		const char *v = getenv("DROIDDECK_NATIVE_CANVAS_POLY_BATCH");
		const int n = v && *v ? atoi(v) : 0;
		return uint32_t(n < 0 ? 0 : (n > 1024 ? 1024 : n));
	}();
	return max_triangles;
}
static const DroiddeckPolyCpu *droiddeck_poly_lookup(RendererCanvasRender::PolygonID p_id) {
	return droiddeck_canvas_poly_max() ? droiddeck_poly_cpu.getptr(p_id) : nullptr;
}
static RendererCanvasRender::Item::CommandPrimitive &droiddeck_poly_triangle_command() {
	// What a batch of converted polygons points at: _render_batch reads only point_count from it.
	static RendererCanvasRender::Item::CommandPrimitive command = [] {
		RendererCanvasRender::Item::CommandPrimitive c;
		c.point_count = 3;
		return c;
	}();
	return command;
}
static void droiddeck_poly_remember(RendererCanvasRender::PolygonID p_id, const Vector<int> &p_indices, const Vector<Point2> &p_points, const Vector<Color> &p_colors, const Vector<Point2> &p_uvs, const Vector<int> &p_bones, const Vector<float> &p_weights, int p_count) {
	const uint32_t max_triangles = droiddeck_canvas_poly_max();
	const int vertex_count = p_points.size();
	if (max_triangles == 0 || vertex_count == 0) {
		return;
	}
	if (p_bones.size() == vertex_count * 4 && p_weights.size() == vertex_count * 4) {
		return; // skinned: the vertex shader moves it
	}
	const int index_count = p_indices.size() ? p_count : vertex_count;
	if (index_count <= 0 || index_count % 3 != 0 || uint32_t(index_count / 3) > max_triangles || (p_indices.size() && index_count > p_indices.size())) {
		return;
	}
	DroiddeckPolyCpu cpu;
	cpu.indices.resize(index_count);
	for (int i = 0; i < index_count; i++) {
		const int v = p_indices.size() ? p_indices[i] : i;
		if (v < 0 || v >= vertex_count) {
			return;
		}
		cpu.indices[i] = v;
	}
	cpu.points.resize(vertex_count);
	for (int i = 0; i < vertex_count; i++) {
		cpu.points[i] = p_points[i];
	}
	if (p_colors.size() == vertex_count || p_colors.size() == 1) {
		cpu.colors.resize(p_colors.size());
		for (int i = 0; i < p_colors.size(); i++) {
			cpu.colors[i] = p_colors[i];
		}
	}
	if (p_uvs.size() == vertex_count) {
		cpu.uvs.resize(vertex_count);
		for (int i = 0; i < vertex_count; i++) {
			cpu.uvs[i] = p_uvs[i];
		}
	}
	droiddeck_poly_cpu.insert(p_id, cpu);
}

"""


def patch_source(text):
    for anchor in (REQUEST, STORED, FREE, RECORD):
        if text.count(anchor) != 1:
            raise SystemExit("canvas_poly_batch: expected exactly one %r" % anchor.strip().splitlines()[0])
    at = text.index(REQUEST)
    line_start = text.rindex("\n", 0, at) + 1
    out = text[:line_start] + HELPERS.lstrip("\n") + "\n" + text[line_start:]
    out = out.replace(STORED, STORED_NEW)
    out = out.replace(FREE, FREE_NEW)
    out = out.replace(RECORD, RECORD_NEW)
    return out


def main(argv):
    (godot,) = argv
    path = os.path.join(godot, "servers", "rendering", "renderer_rd", "renderer_canvas_render_rd.cpp")
    with open(path) as f:
        source = f.read()
    with open(path, "w") as f:
        f.write(patch_source(source))
    print("canvas_poly_batch: patched", path)


if __name__ == "__main__":
    main(sys.argv[1:])
