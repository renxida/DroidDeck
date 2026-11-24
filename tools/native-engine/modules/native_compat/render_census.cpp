// Kestrel native-engine compatibility module. Clean-room: no Esoteric or FMOD code.
#include "render_census.h"

#include "core/os/os.h"
#include "core/templates/hash_map.h"
#include "scene/2d/back_buffer_copy.h"
#include "scene/2d/canvas_group.h"
#include "scene/2d/cpu_particles_2d.h"
#include "scene/2d/gpu_particles_2d.h"
#include "scene/2d/light_2d.h"
#include "scene/2d/light_occluder_2d.h"
#include "scene/3d/world_environment.h"
#include "scene/main/canvas_item.h"
#include "scene/main/canvas_layer.h"
#include "scene/main/scene_tree.h"
#include "scene/main/viewport.h"
#include "scene/main/window.h"
#include "scene/resources/environment.h"
#include "scene/resources/material.h"
#include "scene/resources/shader.h"
#include "servers/rendering_server.h"

#include <algorithm>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <map>
#include <set>
#include <vector>

// Defined in the canvas renderer by patches/canvas_batch_census.py: why each 2D batch started.
int droiddeck_canvas_batch_sites();
const char *droiddeck_canvas_batch_site(int p_site);
uint32_t droiddeck_canvas_batch_take(int p_site);
// Defined in the canvas renderer by patches/canvas_reorder.py: items moved forward to join a batch.
uint32_t droiddeck_canvas_reorder_take();
uint32_t droiddeck_canvas_reorder_window();
// Defined in the canvas renderer by patches/canvas_poly_batch.py: polygons drawn as batched triangles.
uint32_t droiddeck_canvas_poly_take();
uint32_t droiddeck_canvas_poly_max();

namespace droiddeck_census {

static double window_sec = 0;
static uint64_t next_usec = 0;
static uint32_t frames = 0; // frames since the last census

std::string start_from_env() {
	const char *v = getenv("DROIDDECK_NATIVE_RENDER_CENSUS");
	if (!v || !*v) {
		return std::string();
	}
	const double s = atof(v);
	if (s <= 0) {
		return std::string();
	}
	window_sec = s;
	char buf[160];
	snprintf(buf, sizeof(buf), "native_compat: render census every %g s of what starts 2D render passes", s);
	return buf;
}

bool active() {
	return window_sec > 0;
}

namespace {

struct Layer {
	int items = 0; // visible canvas items drawn on this layer
	int screen_readers = 0;
	int groups = 0;
};

struct Census {
	int viewports = 0;
	std::string viewport_desc;
	std::map<const Viewport *, int> viewport_ids; // in the order the walk finds them
	std::map<std::pair<int, int>, Layer> layers; // (viewport id, layer) -> what draws there
	int items = 0, groups = 0, clip_children = 0, backbuffer_copies = 0, screen_reader_items = 0;
	int lights = 0, occluders = 0, gpu_particles = 0, cpu_particles = 0;
	std::set<String> screen_shaders; // resource paths of shaders that read the screen
	std::string env_desc;
};

HashMap<RID, bool> screen_shader_cache; // shader -> does its code read the screen

bool reads_screen(const Ref<Material> &p_material, String &r_name) {
	const Ref<ShaderMaterial> sm = p_material;
	if (sm.is_null()) {
		return false;
	}
	const Ref<Shader> sh = sm->get_shader();
	if (sh.is_null()) {
		return false;
	}
	bool *cached = screen_shader_cache.getptr(sh->get_rid());
	bool reads;
	if (cached) {
		reads = *cached;
	} else {
		const String code = sh->get_code();
		reads = code.contains("hint_screen_texture") || code.contains("SCREEN_TEXTURE");
		screen_shader_cache.insert(sh->get_rid(), reads);
	}
	if (reads) {
		r_name = sh->get_path().is_empty() ? String("(built-in)") : sh->get_path().get_file();
	}
	return reads;
}

std::string describe_viewport(const Viewport *p_vp, bool p_root) {
	char buf[200];
	const Size2 sz = p_vp->get_visible_rect().size;
	const char *mode = "";
	if (const SubViewport *sv = Object::cast_to<SubViewport>(p_vp)) {
		static const char *names[] = { "disabled", "once", "when_visible", "when_parent_visible", "always" };
		const int m = int(sv->get_update_mode());
		mode = m >= 0 && m < 5 ? names[m] : "?";
	}
	snprintf(buf, sizeof(buf), "%s %dx%d%s%s msaa2d %d hdr2d %d ssaa %d deband %d transparent %d", p_root ? "root" : "sub",
			int(sz.x), int(sz.y), *mode ? " " : "", mode, int(p_vp->get_msaa_2d()), int(p_vp->is_using_hdr_2d()),
			int(p_vp->get_screen_space_aa()), int(p_vp->is_using_debanding()), int(p_vp->has_transparent_background()));
	return buf;
}

// Depth-first over the tree; p_vp and p_layer are the viewport and canvas layer the node draws into.
void walk(Node *p_node, const Viewport *p_vp, int p_layer, bool p_layer_visible, Census &c) {
	if (const Viewport *vp = Object::cast_to<Viewport>(p_node)) {
		const SubViewport *sv = Object::cast_to<SubViewport>(vp);
		if (sv && sv->get_update_mode() == SubViewport::UPDATE_DISABLED) {
			return;
		}
		c.viewport_ids[vp] = c.viewports++;
		if (!c.viewport_desc.empty()) {
			c.viewport_desc += "; ";
		}
		c.viewport_desc += describe_viewport(vp, sv == nullptr && p_vp == nullptr);
		p_vp = vp;
		p_layer = 0;
		p_layer_visible = true;
	} else if (const CanvasLayer *cl = Object::cast_to<CanvasLayer>(p_node)) {
		p_layer = cl->get_layer();
		p_layer_visible = p_layer_visible && cl->is_visible();
	} else if (const WorldEnvironment *we = Object::cast_to<WorldEnvironment>(p_node)) {
		const Ref<Environment> env = we->get_environment();
		if (env.is_valid()) {
			char buf[120];
			snprintf(buf, sizeof(buf), "%sbackground %d glow %d", c.env_desc.empty() ? "" : "; ", int(env->get_background()),
					int(env->is_glow_enabled()));
			c.env_desc += buf;
		}
	}
	if (CanvasItem *ci = Object::cast_to<CanvasItem>(p_node)) {
		if (!p_layer_visible || !ci->is_visible_in_tree()) {
			return; // nothing under a hidden item draws
		}
		Layer &l = c.layers[{ c.viewport_ids[p_vp], p_layer }];
		l.items++;
		c.items++;
		String name;
		if (!ci->get_use_parent_material() && reads_screen(ci->get_material(), name)) {
			c.screen_reader_items++;
			l.screen_readers++;
			c.screen_shaders.insert(name);
		}
		if (Object::cast_to<CanvasGroup>(ci)) {
			c.groups++;
			l.groups++;
		}
		if (ci->get_clip_children_mode() != CanvasItem::CLIP_CHILDREN_DISABLED) {
			c.clip_children++;
			l.groups++;
		}
		if (const BackBufferCopy *bb = Object::cast_to<BackBufferCopy>(ci)) {
			if (bb->get_copy_mode() != BackBufferCopy::COPY_MODE_DISABLED) {
				c.backbuffer_copies++;
			}
		}
		if (Object::cast_to<Light2D>(ci)) {
			c.lights++;
		} else if (Object::cast_to<LightOccluder2D>(ci)) {
			c.occluders++;
		} else if (const GPUParticles2D *gp = Object::cast_to<GPUParticles2D>(ci)) {
			c.gpu_particles += gp->is_emitting() ? 1 : 0;
		} else if (const CPUParticles2D *cp = Object::cast_to<CPUParticles2D>(ci)) {
			c.cpu_particles += cp->is_emitting() ? 1 : 0;
		}
	}
	for (int i = 0; i < p_node->get_child_count(true); i++) {
		walk(p_node->get_child(i, true), p_vp, p_layer, p_layer_visible, c);
	}
}

// Per frame since the last census: draw lists, then each reason a new 2D batch started, most first.
std::string batch_reasons(uint32_t p_frames) {
	const int sites = droiddeck_canvas_batch_sites();
	const double per = p_frames ? 1.0 / p_frames : 0.0;
	std::vector<std::pair<uint32_t, int>> counts;
	uint32_t lists = 0, total = 0;
	for (int i = 0; i < sites; i++) {
		const uint32_t n = droiddeck_canvas_batch_take(i);
		if (i == 0) {
			lists = n;
		} else if (n) {
			counts.push_back({ n, i });
			total += n;
		}
	}
	std::sort(counts.begin(), counts.end(), [](const auto &a, const auto &b) { return a.first > b.first; });
	char buf[160];
	snprintf(buf, sizeof(buf), " | batches per frame over %u frames: %.1f draw lists, %.1f new batches because:", p_frames, lists * per,
			total * per);
	std::string out = buf;
	for (const auto &c : counts) {
		snprintf(buf, sizeof(buf), " %s %.1f;", droiddeck_canvas_batch_site(c.second), c.first * per);
		out += buf;
	}
	snprintf(buf, sizeof(buf), " | reorder look-ahead %u: %.1f items moved per frame", droiddeck_canvas_reorder_window(),
			droiddeck_canvas_reorder_take() * per);
	out += buf;
	snprintf(buf, sizeof(buf), " | polygon batching up to %u triangles: %.1f polygons batched per frame", droiddeck_canvas_poly_max(),
			droiddeck_canvas_poly_take() * per);
	return out + buf;
}

} // namespace

std::string tick() {
	if (window_sec <= 0) {
		return std::string();
	}
	frames++;
	const uint64_t now = OS::get_singleton()->get_ticks_usec();
	if (next_usec == 0) {
		next_usec = now + uint64_t(window_sec * 1e6);
		return std::string();
	}
	if (now < next_usec) {
		return std::string();
	}
	next_usec = now + uint64_t(window_sec * 1e6);
	const std::string batches = batch_reasons(frames);
	frames = 0;
	SceneTree *st = Object::cast_to<SceneTree>(OS::get_singleton()->get_main_loop());
	if (!st || !st->get_root()) {
		return "native_compat: census | no scene tree yet";
	}
	const uint64_t t0 = OS::get_singleton()->get_ticks_usec();
	Census c;
	walk(st->get_root(), nullptr, 0, true, c);
	// Layers that draw anything, per viewport in drawing order: each is a render pass of its own.
	std::string layer_desc;
	int drawn_layers = 0;
	for (const auto &kv : c.layers) {
		if (kv.second.items == 0) {
			continue;
		}
		drawn_layers++;
		char buf[96];
		snprintf(buf, sizeof(buf), "%sv%d:%d=%d%s%s", layer_desc.empty() ? "" : " ", kv.first.first, kv.first.second, kv.second.items,
				kv.second.screen_readers ? (std::string("/s") + std::to_string(kv.second.screen_readers)).c_str() : "",
				kv.second.groups ? (std::string("/g") + std::to_string(kv.second.groups)).c_str() : "");
		layer_desc += buf;
	}
	std::string shaders;
	for (const String &s : c.screen_shaders) {
		if (!shaders.empty()) {
			shaders += ", ";
		}
		shaders += s.utf8().get_data();
	}
	RenderingServer *rs = RenderingServer::get_singleton();
	const uint64_t draws = rs ? rs->get_rendering_info(RS::RENDERING_INFO_TOTAL_DRAW_CALLS_IN_FRAME) : 0;
	char head[512];
	snprintf(head, sizeof(head),
			"native_compat: census | %d viewports [%s] | %d layers drawn, %d visible items | %d canvas groups, %d clip_children, "
			"%d back-buffer copies, %d items read the screen (%d shaders: %s) | %d lights, %d occluders, particles %d gpu %d cpu | env [%s] "
			"| %llu draw calls last frame | census took %.2f ms",
			c.viewports, "", drawn_layers, c.items, c.groups, c.clip_children, c.backbuffer_copies, c.screen_reader_items,
			int(c.screen_shaders.size()), "", c.lights, c.occluders, c.gpu_particles, c.cpu_particles, "",
			(unsigned long long)draws, (OS::get_singleton()->get_ticks_usec() - t0) / 1000.0);
	// The variable parts go in after the fixed format so no buffer limit cuts them.
	std::string line = head;
	auto fill = [&line](const char *p_marker, const std::string &p_text) {
		const size_t at = line.find(p_marker);
		if (at != std::string::npos) {
			line.replace(at, strlen(p_marker), p_text);
		}
	};
	fill("[]", "[" + c.viewport_desc + "]");
	fill("shaders: )", "shaders: " + shaders + ")");
	fill("env []", "env [" + c.env_desc + "]");
	return line + " | layers (viewport:layer=items/s screen readers/g groups) " + layer_desc + batches;
}

} // namespace droiddeck_census
