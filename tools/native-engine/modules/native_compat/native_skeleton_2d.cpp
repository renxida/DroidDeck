// Clean-room code for DroidDeck/Kestrel: written from scratch; no third-party runtime source consulted.
#include "native_skeleton_2d.h"

#include "core/io/file_access.h"
#include "core/io/resource_loader.h"
#include "servers/rendering_server.h"

namespace {

std::string to_std(const String &s) {
	CharString c = s.utf8();
	return std::string(c.get_data(), c.length());
}

String to_godot(const std::string &s) {
	return String::utf8(s.c_str(), int(s.size()));
}

// The skeleton is y-up; the canvas is y-down.
inline Vector2 to_canvas(float x, float y) {
	return Vector2(x, -y);
}

} // namespace

Error NativeSkeleton2D::load_files(const String &p_skeleton, const String &p_atlas) {
	load_error = String();
	skeleton.reset();
	state.reset();
	state_data.reset();
	data.reset();
	pages.clear();

	Error err = OK;
	Vector<uint8_t> raw = FileAccess::get_file_as_bytes(p_skeleton, &err);
	if (err != OK) {
		load_error = "cannot read " + p_skeleton;
		return err;
	}
	// JSON starts with '{' (after any whitespace or a UTF-8 byte order mark); anything else is the binary format.
	int first = 0;
	while (first < raw.size() && (raw[first] == ' ' || raw[first] == '\t' || raw[first] == '\r' || raw[first] == '\n' ||
										 raw[first] == 0xef || raw[first] == 0xbb || raw[first] == 0xbf)) {
		first++;
	}
	std::string error;
	if (first < raw.size() && raw[first] == '{') {
		data = skel::load_json(reinterpret_cast<const char *>(raw.ptr()), size_t(raw.size()), skeleton_scale, error);
	} else {
		data = skel::load_binary(raw.ptr(), size_t(raw.size()), skeleton_scale, error);
	}
	if (!data) {
		load_error = p_skeleton + ": " + to_godot(error);
		return ERR_PARSE_ERROR;
	}
	if (!p_atlas.is_empty()) {
		String text = FileAccess::get_file_as_string(p_atlas, &err);
		if (err != OK) {
			load_error = "cannot read " + p_atlas;
			return err;
		}
		CharString at = text.utf8();
		if (!skel::parse_atlas(at.get_data(), at.length(), atlas, error)) {
			load_error = p_atlas + ": " + to_godot(error);
			return ERR_PARSE_ERROR;
		}
		String dir = p_atlas.get_base_dir();
		for (const skel::AtlasPage &page : atlas.pages) {
			Ref<Texture2D> tex = ResourceLoader::load(dir.path_join(to_godot(page.name)));
			if (tex.is_null()) {
				WARN_PRINT("NativeSkeleton2D: atlas page not loadable: " + dir.path_join(to_godot(page.name)));
			}
			pages.push_back(tex);
		}
		for (const std::string &m : skel::bind_atlas(*data, atlas)) {
			WARN_PRINT("NativeSkeleton2D: no atlas region for " + to_godot(m));
		}
	}
	for (const std::string &u : data->unsupported) {
		print_verbose("NativeSkeleton2D: ignoring " + to_godot(u) + " in " + p_skeleton);
	}
	state_data = std::make_unique<skel::AnimationStateData>();
	state_data->skeleton = data.get();
	state_data->default_mix = default_mix;
	state = std::make_unique<skel::AnimationState>(state_data.get());
	skeleton = std::make_unique<skel::Skeleton>(data.get());
	if (!skin.is_empty()) {
		skeleton->set_skin(to_std(skin));
	}
	if (!animation.is_empty()) {
		state->set_animation(0, to_std(animation), loop);
	}
	skeleton->update_world_transform();
	queue_redraw();
	return OK;
}

void NativeSkeleton2D::_emit_pending() {
	if (!state) {
		return;
	}
	std::vector<skel::StateEvent> events;
	events.swap(state->pending);
	for (const skel::StateEvent &e : events) {
		String anim = e.animation ? to_godot(e.animation->name) : String();
		switch (e.kind) {
			case skel::StateEvent::Start:
				emit_signal(SNAME("animation_started"), e.track, anim);
				break;
			case skel::StateEvent::Complete:
				emit_signal(SNAME("animation_completed"), e.track, anim);
				break;
			case skel::StateEvent::End:
				emit_signal(SNAME("animation_ended"), e.track, anim);
				break;
			case skel::StateEvent::Fire:
				emit_signal(SNAME("animation_event"), e.track, anim, to_godot(e.event.data->name), e.event.int_value,
						e.event.float_value, to_godot(e.event.string_value));
				break;
		}
	}
}

void NativeSkeleton2D::_advance(float delta) {
	if (!skeleton || !state) {
		return;
	}
	state->update(delta * time_scale);
	state->apply(*skeleton);
	skeleton->update_world_transform();
	_emit_pending();
	queue_redraw();
}

void NativeSkeleton2D::advance(float p_delta) {
	_advance(p_delta);
}

void NativeSkeleton2D::_draw_skeleton() {
	if (!skeleton) {
		return;
	}
	RenderingServer *rs = RenderingServer::get_singleton();
	RID item = get_canvas_item();
	std::vector<float> world;
	Vector<int> indices;
	Vector<Point2> points, uvs;
	Vector<Color> colors;
	for (int slot_index : skeleton->draw_order) {
		const skel::Slot &slot = skeleton->slots[slot_index];
		const skel::Attachment *a = slot.drawn();
		if (!a || !skeleton->bones[slot.data->bone].active) {
			continue;
		}
		if (a->type != skel::AttachmentType::Region && a->type != skel::AttachmentType::Mesh) {
			continue;
		}
		int n = skeleton->world_vertices(slot_index, world);
		if (n == 0) {
			continue;
		}
		Color c(skeleton->color.r * slot.color.r * a->color.r, skeleton->color.g * slot.color.g * a->color.g,
				skeleton->color.b * slot.color.b * a->color.b, skeleton->color.a * slot.color.a * a->color.a);
		if (c.a <= 0) {
			continue;
		}
		points.resize(n);
		uvs.resize(n);
		colors.resize(n);
		Point2 *pw = points.ptrw();
		Point2 *uw = uvs.ptrw();
		Color *cw = colors.ptrw();
		for (int i = 0; i < n; i++) {
			pw[i] = to_canvas(world[i * 2], world[i * 2 + 1]);
			cw[i] = c;
		}
		if (a->type == skel::AttachmentType::Region) {
			for (int i = 0; i < 4; i++) {
				uw[i] = Point2(a->region_uv[i * 2], a->region_uv[i * 2 + 1]);
			}
			indices.resize(6);
			int *iw = indices.ptrw();
			const int quad[6] = { 0, 1, 2, 2, 3, 0 };
			for (int i = 0; i < 6; i++) {
				iw[i] = quad[i];
			}
		} else {
			const std::vector<float> &src = a->page_uvs.empty() ? a->uvs : a->page_uvs;
			for (int i = 0; i < n && size_t(i * 2 + 1) < src.size(); i++) {
				uw[i] = Point2(src[i * 2], src[i * 2 + 1]);
			}
			indices.resize(int(a->triangles.size()));
			int *iw = indices.ptrw();
			for (size_t i = 0; i < a->triangles.size(); i++) {
				iw[i] = a->triangles[i];
			}
		}
		RID tex;
		if (a->page >= 0 && a->page < pages.size() && pages[a->page].is_valid()) {
			tex = pages[a->page]->get_rid();
		}
		rs->canvas_item_add_triangle_array(item, indices, points, colors, uvs, Vector<int>(), Vector<float>(), tex);
	}
}

void NativeSkeleton2D::_notification(int p_what) {
	switch (p_what) {
		case NOTIFICATION_READY:
			if (!skeleton && !skeleton_path.is_empty()) {
				Error e = load_files(skeleton_path, atlas_path);
				if (e != OK) {
					ERR_PRINT("NativeSkeleton2D: " + load_error);
				}
			}
			set_process_internal(playing);
			break;
		case NOTIFICATION_INTERNAL_PROCESS:
			_advance(float(get_process_delta_time()));
			break;
		case NOTIFICATION_DRAW:
			_draw_skeleton();
			break;
	}
}

void NativeSkeleton2D::set_skeleton_path(const String &p_path) {
	skeleton_path = p_path;
	if (is_inside_tree()) {
		load_files(skeleton_path, atlas_path);
	}
}

void NativeSkeleton2D::set_atlas_path(const String &p_path) {
	atlas_path = p_path;
	if (is_inside_tree() && !skeleton_path.is_empty()) {
		load_files(skeleton_path, atlas_path);
	}
}

void NativeSkeleton2D::set_animation(const String &p_name) {
	animation = p_name;
	if (state) {
		if (p_name.is_empty()) {
			state->clear_track(0);
		} else {
			state->set_animation(0, to_std(p_name), loop);
		}
	}
}

void NativeSkeleton2D::set_loop(bool p_loop) {
	loop = p_loop;
	if (state && state->current(0)) {
		state->current(0)->loop = p_loop;
	}
}

void NativeSkeleton2D::set_playing(bool p_playing) {
	playing = p_playing;
	if (is_inside_tree()) {
		set_process_internal(p_playing);
	}
}

void NativeSkeleton2D::set_skeleton_scale(float p_scale) {
	skeleton_scale = p_scale;
	if (is_inside_tree() && !skeleton_path.is_empty()) {
		load_files(skeleton_path, atlas_path);
	}
}

void NativeSkeleton2D::set_skin(const String &p_skin) {
	skin = p_skin;
	if (skeleton) {
		skeleton->set_skin(to_std(p_skin));
		queue_redraw();
	}
}

void NativeSkeleton2D::set_default_mix(float p_mix) {
	default_mix = p_mix;
	if (state_data) {
		state_data->default_mix = p_mix;
	}
}

bool NativeSkeleton2D::play(int p_track, const String &p_name, bool p_loop) {
	ERR_FAIL_COND_V(!state, false);
	if (p_track == 0) {
		animation = p_name;
		loop = p_loop;
	}
	return state->set_animation(p_track, to_std(p_name), p_loop) != nullptr;
}

bool NativeSkeleton2D::queue(int p_track, const String &p_name, bool p_loop, float p_delay) {
	ERR_FAIL_COND_V(!state, false);
	return state->add_animation(p_track, to_std(p_name), p_loop, p_delay) != nullptr;
}

void NativeSkeleton2D::clear_track(int p_track) {
	if (state) {
		state->clear_track(p_track);
	}
}

void NativeSkeleton2D::set_mix(const String &p_from, const String &p_to, float p_duration) {
	ERR_FAIL_COND(!state_data);
	state_data->set_mix(to_std(p_from), to_std(p_to), p_duration);
}

PackedStringArray NativeSkeleton2D::get_animation_names() const {
	PackedStringArray out;
	if (data) {
		for (const auto &a : data->animations) {
			out.push_back(to_godot(a.name));
		}
	}
	return out;
}

PackedStringArray NativeSkeleton2D::get_skin_names() const {
	PackedStringArray out;
	if (data) {
		for (const auto &s : data->skins) {
			out.push_back(to_godot(s->name));
		}
	}
	return out;
}

PackedStringArray NativeSkeleton2D::get_unsupported_features() const {
	PackedStringArray out;
	if (data) {
		for (const auto &u : data->unsupported) {
			out.push_back(to_godot(u));
		}
	}
	return out;
}

float NativeSkeleton2D::get_animation_duration(const String &p_name) const {
	const skel::Animation *a = data ? data->find_animation(to_std(p_name)) : nullptr;
	return a ? a->duration : -1;
}

Vector2 NativeSkeleton2D::get_bone_position(const String &p_bone) const {
	ERR_FAIL_COND_V(!skeleton, Vector2());
	int i = data->find_bone(to_std(p_bone));
	ERR_FAIL_COND_V_MSG(i < 0, Vector2(), "no bone " + p_bone);
	const skel::Bone &b = skeleton->bones[i];
	return to_canvas(b.world_x, b.world_y);
}

float NativeSkeleton2D::get_bone_rotation(const String &p_bone) const {
	ERR_FAIL_COND_V(!skeleton, 0);
	int i = data->find_bone(to_std(p_bone));
	ERR_FAIL_COND_V_MSG(i < 0, 0, "no bone " + p_bone);
	return -skeleton->bones[i].world_rotation_x(); // canvas rotation is clockwise-positive
}

String NativeSkeleton2D::get_slot_attachment(const String &p_slot) const {
	ERR_FAIL_COND_V(!skeleton, String());
	int i = data->find_slot(to_std(p_slot));
	ERR_FAIL_COND_V_MSG(i < 0, String(), "no slot " + p_slot);
	const skel::Attachment *a = skeleton->slots[i].attachment;
	return a ? to_godot(a->name) : String();
}

bool NativeSkeleton2D::set_slot_attachment(const String &p_slot, const String &p_attachment) {
	ERR_FAIL_COND_V(!skeleton, false);
	bool ok = skeleton->set_attachment(to_std(p_slot), to_std(p_attachment));
	queue_redraw();
	return ok;
}

Rect2 NativeSkeleton2D::get_bounds() const {
	Rect2 r;
	if (!skeleton) {
		return r;
	}
	bool first = true;
	std::vector<float> world;
	for (size_t s = 0; s < skeleton->slots.size(); s++) {
		int n = skeleton->world_vertices(int(s), world);
		for (int i = 0; i < n; i++) {
			Vector2 p = to_canvas(world[i * 2], world[i * 2 + 1]);
			if (first) {
				r = Rect2(p, Vector2());
				first = false;
			} else {
				r.expand_to(p);
			}
		}
	}
	return r;
}

void NativeSkeleton2D::_bind_methods() {
	ClassDB::bind_method(D_METHOD("load_files", "skeleton_path", "atlas_path"), &NativeSkeleton2D::load_files);
	ClassDB::bind_method(D_METHOD("get_load_error"), &NativeSkeleton2D::get_load_error);
	ClassDB::bind_method(D_METHOD("set_skeleton_path", "path"), &NativeSkeleton2D::set_skeleton_path);
	ClassDB::bind_method(D_METHOD("get_skeleton_path"), &NativeSkeleton2D::get_skeleton_path);
	ClassDB::bind_method(D_METHOD("set_atlas_path", "path"), &NativeSkeleton2D::set_atlas_path);
	ClassDB::bind_method(D_METHOD("get_atlas_path"), &NativeSkeleton2D::get_atlas_path);
	ClassDB::bind_method(D_METHOD("set_animation", "name"), &NativeSkeleton2D::set_animation);
	ClassDB::bind_method(D_METHOD("get_animation"), &NativeSkeleton2D::get_animation);
	ClassDB::bind_method(D_METHOD("set_loop", "loop"), &NativeSkeleton2D::set_loop);
	ClassDB::bind_method(D_METHOD("get_loop"), &NativeSkeleton2D::get_loop);
	ClassDB::bind_method(D_METHOD("set_playing", "playing"), &NativeSkeleton2D::set_playing);
	ClassDB::bind_method(D_METHOD("is_playing"), &NativeSkeleton2D::is_playing);
	ClassDB::bind_method(D_METHOD("set_time_scale", "scale"), &NativeSkeleton2D::set_time_scale);
	ClassDB::bind_method(D_METHOD("get_time_scale"), &NativeSkeleton2D::get_time_scale);
	ClassDB::bind_method(D_METHOD("set_skeleton_scale", "scale"), &NativeSkeleton2D::set_skeleton_scale);
	ClassDB::bind_method(D_METHOD("get_skeleton_scale"), &NativeSkeleton2D::get_skeleton_scale);
	ClassDB::bind_method(D_METHOD("set_skin", "skin"), &NativeSkeleton2D::set_skin);
	ClassDB::bind_method(D_METHOD("get_skin"), &NativeSkeleton2D::get_skin);
	ClassDB::bind_method(D_METHOD("set_default_mix", "duration"), &NativeSkeleton2D::set_default_mix);
	ClassDB::bind_method(D_METHOD("get_default_mix"), &NativeSkeleton2D::get_default_mix);
	ClassDB::bind_method(D_METHOD("play", "track", "name", "loop"), &NativeSkeleton2D::play);
	ClassDB::bind_method(D_METHOD("queue", "track", "name", "loop", "delay"), &NativeSkeleton2D::queue);
	ClassDB::bind_method(D_METHOD("clear_track", "track"), &NativeSkeleton2D::clear_track);
	ClassDB::bind_method(D_METHOD("set_mix", "from", "to", "duration"), &NativeSkeleton2D::set_mix);
	ClassDB::bind_method(D_METHOD("advance", "delta"), &NativeSkeleton2D::advance);
	ClassDB::bind_method(D_METHOD("get_animation_names"), &NativeSkeleton2D::get_animation_names);
	ClassDB::bind_method(D_METHOD("get_skin_names"), &NativeSkeleton2D::get_skin_names);
	ClassDB::bind_method(D_METHOD("get_unsupported_features"), &NativeSkeleton2D::get_unsupported_features);
	ClassDB::bind_method(D_METHOD("get_animation_duration", "name"), &NativeSkeleton2D::get_animation_duration);
	ClassDB::bind_method(D_METHOD("get_bone_position", "bone"), &NativeSkeleton2D::get_bone_position);
	ClassDB::bind_method(D_METHOD("get_bone_rotation", "bone"), &NativeSkeleton2D::get_bone_rotation);
	ClassDB::bind_method(D_METHOD("get_slot_attachment", "slot"), &NativeSkeleton2D::get_slot_attachment);
	ClassDB::bind_method(D_METHOD("set_slot_attachment", "slot", "attachment"), &NativeSkeleton2D::set_slot_attachment);
	ClassDB::bind_method(D_METHOD("get_bounds"), &NativeSkeleton2D::get_bounds);

	ADD_PROPERTY(PropertyInfo(Variant::STRING, "skeleton_path", PROPERTY_HINT_FILE, "*.json"), "set_skeleton_path", "get_skeleton_path");
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "atlas_path", PROPERTY_HINT_FILE, "*.atlas"), "set_atlas_path", "get_atlas_path");
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "animation"), "set_animation", "get_animation");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "loop"), "set_loop", "get_loop");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "playing"), "set_playing", "is_playing");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "time_scale"), "set_time_scale", "get_time_scale");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "skeleton_scale"), "set_skeleton_scale", "get_skeleton_scale");
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "skin"), "set_skin", "get_skin");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "default_mix"), "set_default_mix", "get_default_mix");

	ADD_SIGNAL(MethodInfo("animation_started", PropertyInfo(Variant::INT, "track"), PropertyInfo(Variant::STRING, "animation")));
	ADD_SIGNAL(MethodInfo("animation_completed", PropertyInfo(Variant::INT, "track"), PropertyInfo(Variant::STRING, "animation")));
	ADD_SIGNAL(MethodInfo("animation_ended", PropertyInfo(Variant::INT, "track"), PropertyInfo(Variant::STRING, "animation")));
	ADD_SIGNAL(MethodInfo("animation_event", PropertyInfo(Variant::INT, "track"), PropertyInfo(Variant::STRING, "animation"),
			PropertyInfo(Variant::STRING, "event"), PropertyInfo(Variant::INT, "int_value"), PropertyInfo(Variant::FLOAT, "float_value"),
			PropertyInfo(Variant::STRING, "string_value")));
}
