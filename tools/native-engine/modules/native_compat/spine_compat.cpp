// Clean-room code for DroidDeck/Kestrel: written from scratch; no third-party runtime source consulted.
#include "spine_compat.h"

#include "core/io/file_access.h"
#include "core/io/json.h"
#include "core/templates/hash_set.h"
#include "scene/resources/canvas_item_material.h"
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

SpineCompatStats spine_compat_stats;

namespace {

void warn_once(const String &p_what) {
	static HashSet<String> said;
	if (!said.has(p_what)) {
		said.insert(p_what);
		WARN_PRINT("spine_compat: " + p_what);
	}
}

bool looks_like_json(const uint8_t *p, int n) {
	int i = 0;
	while (i < n && (p[i] == ' ' || p[i] == '\t' || p[i] == '\r' || p[i] == '\n' || p[i] == 0xef || p[i] == 0xbb || p[i] == 0xbf)) {
		i++;
	}
	return i < n && p[i] == '{';
}

template <typename T>
Ref<T> view_of(const std::shared_ptr<skel::SkeletonData> &owner) {
	Ref<T> r;
	r.instantiate();
	r->owner = owner;
	return r;
}

Ref<SpineAnimation> animation_view(const std::shared_ptr<skel::SkeletonData> &owner, const skel::Animation *a) {
	if (!a) {
		return Ref<SpineAnimation>();
	}
	Ref<SpineAnimation> r = view_of<SpineAnimation>(owner);
	r->animation = a;
	return r;
}

Ref<SpineBoneData> bone_data_view(const std::shared_ptr<skel::SkeletonData> &owner, int i) {
	if (!owner || i < 0 || i >= int(owner->bones.size())) {
		return Ref<SpineBoneData>();
	}
	Ref<SpineBoneData> r = view_of<SpineBoneData>(owner);
	r->bone = &owner->bones[i];
	return r;
}

Ref<SpineSlotData> slot_data_view(const std::shared_ptr<skel::SkeletonData> &owner, int i) {
	if (!owner || i < 0 || i >= int(owner->slots.size())) {
		return Ref<SpineSlotData>();
	}
	Ref<SpineSlotData> r = view_of<SpineSlotData>(owner);
	r->slot = &owner->slots[i];
	return r;
}

Ref<SpineSkin> skin_view(const std::shared_ptr<skel::SkeletonData> &owner, const skel::Skin *s) {
	if (!s) {
		return Ref<SpineSkin>();
	}
	Ref<SpineSkin> r = view_of<SpineSkin>(owner);
	r->skin = s;
	return r;
}

Ref<SpineEventData> event_data_view(const std::shared_ptr<skel::SkeletonData> &owner, const skel::EventData *e) {
	if (!e) {
		return Ref<SpineEventData>();
	}
	Ref<SpineEventData> r = view_of<SpineEventData>(owner);
	r->event = e;
	return r;
}

} // namespace

// ============================================================================================ resources

Error SpineAtlasResource::parse(const String &p_text, const String &p_source_path) {
	atlas = skel::Atlas();
	textures.clear();
	load_error = String();
	atlas_text = p_text;
	source_path = p_source_path;
	CharString at = p_text.utf8();
	std::string error;
	if (!skel::parse_atlas(at.get_data(), at.length(), atlas, error)) {
		load_error = p_source_path + ": " + to_godot(error);
		return ERR_PARSE_ERROR;
	}
	String dir = p_source_path.get_base_dir();
	for (const skel::AtlasPage &page : atlas.pages) {
		String path = dir.path_join(to_godot(page.name));
		Ref<Texture2D> tex;
		if (ResourceLoader::exists(path)) {
			tex = ResourceLoader::load(path);
		}
		if (tex.is_null()) {
			warn_once("atlas page not loadable: " + path);
		}
		textures.push_back(tex);
	}
	emit_changed();
	return OK;
}

Error SpineAtlasResource::load_from_atlas_file(const String &p_path) {
	Error err = OK;
	String text = FileAccess::get_file_as_string(p_path, &err);
	if (err != OK) {
		load_error = "cannot read " + p_path;
		return err;
	}
	return parse(text, p_path);
}

Error SpineAtlasResource::load_imported(const String &p_path) {
	Error err = OK;
	String text = FileAccess::get_file_as_string(p_path, &err);
	if (err != OK) {
		load_error = "cannot read " + p_path;
		return err;
	}
	Variant parsed = JSON::parse_string(text);
	if (parsed.get_type() != Variant::DICTIONARY) {
		// Not the JSON wrapper: take it as a plain text atlas.
		return parse(text, p_path);
	}
	Dictionary d = parsed;
	normal_texture_prefix = d.get("normal_texture_prefix", normal_texture_prefix);
	specular_texture_prefix = d.get("specular_texture_prefix", specular_texture_prefix);
	return parse(d.get("atlas_data", String()), d.get("source_path", p_path));
}

Array SpineAtlasResource::get_textures() const {
	Array out;
	for (const Ref<Texture2D> &t : textures) {
		out.push_back(t);
	}
	return out;
}

void SpineAtlasResource::_bind_methods() {
	ClassDB::bind_method(D_METHOD("load_from_atlas_file", "path"), &SpineAtlasResource::load_from_atlas_file);
	ClassDB::bind_method(D_METHOD("get_source_path"), &SpineAtlasResource::get_source_path);
	ClassDB::bind_method(D_METHOD("get_textures"), &SpineAtlasResource::get_textures);
	ClassDB::bind_method(D_METHOD("get_load_error"), &SpineAtlasResource::get_load_error);
	ClassDB::bind_method(D_METHOD("set_normal_texture_prefix", "prefix"), &SpineAtlasResource::set_normal_texture_prefix);
	ClassDB::bind_method(D_METHOD("get_normal_texture_prefix"), &SpineAtlasResource::get_normal_texture_prefix);
	ClassDB::bind_method(D_METHOD("set_specular_texture_prefix", "prefix"), &SpineAtlasResource::set_specular_texture_prefix);
	ClassDB::bind_method(D_METHOD("get_specular_texture_prefix"), &SpineAtlasResource::get_specular_texture_prefix);
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "normal_texture_prefix"), "set_normal_texture_prefix", "get_normal_texture_prefix");
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "specular_texture_prefix"), "set_specular_texture_prefix", "get_specular_texture_prefix");
}

Error SpineSkeletonFileResource::load_from_file(const String &p_path) {
	Error err = OK;
	bytes = FileAccess::get_file_as_bytes(p_path, &err);
	emit_changed();
	return err;
}

bool SpineSkeletonFileResource::is_json() const {
	return looks_like_json(bytes.ptr(), bytes.size());
}

void SpineSkeletonFileResource::_bind_methods() {
	ClassDB::bind_method(D_METHOD("load_from_file", "path"), &SpineSkeletonFileResource::load_from_file);
	ClassDB::bind_method(D_METHOD("set_bytes", "bytes"), &SpineSkeletonFileResource::set_bytes);
	ClassDB::bind_method(D_METHOD("get_bytes"), &SpineSkeletonFileResource::get_bytes);
	ClassDB::bind_method(D_METHOD("is_json"), &SpineSkeletonFileResource::is_json);
}

void SpineAnimationMix::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_from", "from"), &SpineAnimationMix::set_from);
	ClassDB::bind_method(D_METHOD("get_from"), &SpineAnimationMix::get_from);
	ClassDB::bind_method(D_METHOD("set_to", "to"), &SpineAnimationMix::set_to);
	ClassDB::bind_method(D_METHOD("get_to"), &SpineAnimationMix::get_to);
	ClassDB::bind_method(D_METHOD("set_mix", "mix"), &SpineAnimationMix::set_mix);
	ClassDB::bind_method(D_METHOD("get_mix"), &SpineAnimationMix::get_mix);
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "from"), "set_from", "get_from");
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "to"), "set_to", "get_to");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "mix"), "set_mix", "get_mix");
}

// ------------------------------------------------------------------------------- skeleton data resource

void SpineSkeletonDataResource::_changed() {
	dirty = true;
	emit_changed();
}

void SpineSkeletonDataResource::set_skeleton_file_res(const Ref<SpineSkeletonFileResource> &p) {
	skeleton_file_res = p;
	_changed();
}

void SpineSkeletonDataResource::set_atlas_res(const Ref<SpineAtlasResource> &p) {
	atlas_res = p;
	_changed();
}

void SpineSkeletonDataResource::set_default_mix(float p) {
	default_mix = p;
	if (state_data) {
		state_data->default_mix = p;
	}
	emit_changed();
}

void SpineSkeletonDataResource::set_animation_mixes(const Array &p) {
	animation_mixes = p;
	if (state_data) {
		for (int i = 0; i < p.size(); i++) {
			Ref<SpineAnimationMix> m = p[i];
			if (m.is_valid()) {
				state_data->set_mix(to_std(m->get_from()), to_std(m->get_to()), m->get_mix());
			}
		}
	}
	emit_changed();
}

void SpineSkeletonDataResource::_rebuild() {
	dirty = false;
	data.reset();
	state_data.reset();
	load_error = String();
	if (skeleton_file_res.is_null()) {
		load_error = "no skeleton file";
		return;
	}
	PackedByteArray raw = skeleton_file_res->get_bytes();
	std::string error;
	std::unique_ptr<skel::SkeletonData> loaded;
	if (looks_like_json(raw.ptr(), raw.size())) {
		loaded = skel::load_json(reinterpret_cast<const char *>(raw.ptr()), size_t(raw.size()), 1, error);
	} else {
		loaded = skel::load_binary(raw.ptr(), size_t(raw.size()), 1, error);
	}
	if (!loaded) {
		load_error = skeleton_file_res->get_path() + ": " + to_godot(error);
		ERR_PRINT("spine_compat: " + load_error);
		spine_compat_stats.failed++;
		return;
	}
	spine_compat_stats.loaded++;
	if (atlas_res.is_valid() && atlas_res->is_loaded()) {
		for (const std::string &m : skel::bind_atlas(*loaded, atlas_res->get_atlas())) {
			warn_once("no atlas region for " + to_godot(m) + " in " + skeleton_file_res->get_path());
		}
	}
	for (const std::string &u : loaded->unsupported) {
		warn_once("ignoring " + to_godot(u) + " in " + skeleton_file_res->get_path());
	}
	data = std::shared_ptr<skel::SkeletonData>(loaded.release());
	state_data = std::make_shared<skel::AnimationStateData>();
	state_data->skeleton = data.get();
	state_data->default_mix = default_mix;
	set_animation_mixes(animation_mixes);
}

std::shared_ptr<skel::SkeletonData> SpineSkeletonDataResource::get_data() {
	if (dirty) {
		_rebuild();
	}
	return data;
}

std::shared_ptr<skel::AnimationStateData> SpineSkeletonDataResource::get_state_data() {
	get_data();
	// The state data points at the skeleton data; share ownership so neither outlives the other.
	if (!state_data) {
		return nullptr;
	}
	auto keep = data;
	auto sd = state_data;
	return std::shared_ptr<skel::AnimationStateData>(sd.get(), [keep, sd](skel::AnimationStateData *) {});
}

bool SpineSkeletonDataResource::is_skeleton_data_loaded() {
	return get_data() != nullptr;
}

Ref<SpineAnimation> SpineSkeletonDataResource::find_animation(const String &p_name) {
	auto d = get_data();
	return d ? animation_view(d, d->find_animation(to_std(p_name))) : Ref<SpineAnimation>();
}

Ref<SpineBoneData> SpineSkeletonDataResource::find_bone(const String &p_name) {
	auto d = get_data();
	return d ? bone_data_view(d, d->find_bone(to_std(p_name))) : Ref<SpineBoneData>();
}

Ref<SpineSlotData> SpineSkeletonDataResource::find_slot(const String &p_name) {
	auto d = get_data();
	return d ? slot_data_view(d, d->find_slot(to_std(p_name))) : Ref<SpineSlotData>();
}

Ref<SpineSkin> SpineSkeletonDataResource::find_skin(const String &p_name) {
	auto d = get_data();
	return d ? skin_view(d, d->find_skin(to_std(p_name))) : Ref<SpineSkin>();
}

Ref<SpineEventData> SpineSkeletonDataResource::find_event(const String &p_name) {
	auto d = get_data();
	return d ? event_data_view(d, d->find_event(to_std(p_name))) : Ref<SpineEventData>();
}

Array SpineSkeletonDataResource::get_animations() {
	Array out;
	auto d = get_data();
	if (d) {
		for (const auto &a : d->animations) {
			out.push_back(animation_view(d, &a));
		}
	}
	return out;
}

Array SpineSkeletonDataResource::get_skins() {
	Array out;
	auto d = get_data();
	if (d) {
		for (const auto &s : d->skins) {
			out.push_back(skin_view(d, s.get()));
		}
	}
	return out;
}

Array SpineSkeletonDataResource::get_bones() {
	Array out;
	auto d = get_data();
	for (int i = 0; d && i < int(d->bones.size()); i++) {
		out.push_back(bone_data_view(d, i));
	}
	return out;
}

Array SpineSkeletonDataResource::get_slots() {
	Array out;
	auto d = get_data();
	for (int i = 0; d && i < int(d->slots.size()); i++) {
		out.push_back(slot_data_view(d, i));
	}
	return out;
}

Array SpineSkeletonDataResource::get_events() {
	Array out;
	auto d = get_data();
	if (d) {
		for (const auto &e : d->events) {
			out.push_back(event_data_view(d, &e));
		}
	}
	return out;
}

PackedStringArray SpineSkeletonDataResource::get_animation_names() {
	PackedStringArray out;
	auto d = get_data();
	if (d) {
		for (const auto &a : d->animations) {
			out.push_back(to_godot(a.name));
		}
	}
	return out;
}

PackedStringArray SpineSkeletonDataResource::get_skin_names() {
	PackedStringArray out;
	auto d = get_data();
	if (d) {
		for (const auto &s : d->skins) {
			out.push_back(to_godot(s->name));
		}
	}
	return out;
}

PackedStringArray SpineSkeletonDataResource::get_unsupported_features() {
	PackedStringArray out;
	auto d = get_data();
	if (d) {
		for (const auto &u : d->unsupported) {
			out.push_back(to_godot(u));
		}
	}
	return out;
}

void SpineSkeletonDataResource::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_skeleton_file_res", "res"), &SpineSkeletonDataResource::set_skeleton_file_res);
	ClassDB::bind_method(D_METHOD("get_skeleton_file_res"), &SpineSkeletonDataResource::get_skeleton_file_res);
	ClassDB::bind_method(D_METHOD("set_atlas_res", "res"), &SpineSkeletonDataResource::set_atlas_res);
	ClassDB::bind_method(D_METHOD("get_atlas_res"), &SpineSkeletonDataResource::get_atlas_res);
	ClassDB::bind_method(D_METHOD("set_default_mix", "mix"), &SpineSkeletonDataResource::set_default_mix);
	ClassDB::bind_method(D_METHOD("get_default_mix"), &SpineSkeletonDataResource::get_default_mix);
	ClassDB::bind_method(D_METHOD("set_animation_mixes", "mixes"), &SpineSkeletonDataResource::set_animation_mixes);
	ClassDB::bind_method(D_METHOD("get_animation_mixes"), &SpineSkeletonDataResource::get_animation_mixes);
	ClassDB::bind_method(D_METHOD("is_skeleton_data_loaded"), &SpineSkeletonDataResource::is_skeleton_data_loaded);
	ClassDB::bind_method(D_METHOD("get_load_error"), &SpineSkeletonDataResource::get_load_error);
	ClassDB::bind_method(D_METHOD("find_animation", "name"), &SpineSkeletonDataResource::find_animation);
	ClassDB::bind_method(D_METHOD("find_bone", "name"), &SpineSkeletonDataResource::find_bone);
	ClassDB::bind_method(D_METHOD("find_slot", "name"), &SpineSkeletonDataResource::find_slot);
	ClassDB::bind_method(D_METHOD("find_skin", "name"), &SpineSkeletonDataResource::find_skin);
	ClassDB::bind_method(D_METHOD("find_event", "name"), &SpineSkeletonDataResource::find_event);
	ClassDB::bind_method(D_METHOD("get_animations"), &SpineSkeletonDataResource::get_animations);
	ClassDB::bind_method(D_METHOD("get_skins"), &SpineSkeletonDataResource::get_skins);
	ClassDB::bind_method(D_METHOD("get_bones"), &SpineSkeletonDataResource::get_bones);
	ClassDB::bind_method(D_METHOD("get_slots"), &SpineSkeletonDataResource::get_slots);
	ClassDB::bind_method(D_METHOD("get_events"), &SpineSkeletonDataResource::get_events);
	ClassDB::bind_method(D_METHOD("get_animation_names"), &SpineSkeletonDataResource::get_animation_names);
	ClassDB::bind_method(D_METHOD("get_skin_names"), &SpineSkeletonDataResource::get_skin_names);
	ClassDB::bind_method(D_METHOD("get_unsupported_features"), &SpineSkeletonDataResource::get_unsupported_features);
	ClassDB::bind_method(D_METHOD("get_x"), &SpineSkeletonDataResource::get_x);
	ClassDB::bind_method(D_METHOD("get_y"), &SpineSkeletonDataResource::get_y);
	ClassDB::bind_method(D_METHOD("get_width"), &SpineSkeletonDataResource::get_width);
	ClassDB::bind_method(D_METHOD("get_height"), &SpineSkeletonDataResource::get_height);
	ClassDB::bind_method(D_METHOD("get_fps"), &SpineSkeletonDataResource::get_fps);
	ClassDB::bind_method(D_METHOD("get_version"), &SpineSkeletonDataResource::get_version);
	ADD_PROPERTY(PropertyInfo(Variant::OBJECT, "atlas_res", PROPERTY_HINT_RESOURCE_TYPE, "SpineAtlasResource"), "set_atlas_res", "get_atlas_res");
	ADD_PROPERTY(PropertyInfo(Variant::OBJECT, "skeleton_file_res", PROPERTY_HINT_RESOURCE_TYPE, "SpineSkeletonFileResource"), "set_skeleton_file_res", "get_skeleton_file_res");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "default_mix"), "set_default_mix", "get_default_mix");
	ADD_PROPERTY(PropertyInfo(Variant::ARRAY, "animation_mixes", PROPERTY_HINT_ARRAY_TYPE, "SpineAnimationMix"), "set_animation_mixes", "get_animation_mixes");
}

// ============================================================================================ data views

String SpineAnimation::get_name() const {
	return animation ? to_godot(animation->name) : String();
}

void SpineAnimation::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_name"), &SpineAnimation::get_name);
	ClassDB::bind_method(D_METHOD("get_duration"), &SpineAnimation::get_duration);
}

String SpineBoneData::get_name() const {
	return bone ? to_godot(bone->name) : String();
}

Ref<SpineBoneData> SpineBoneData::get_parent() const {
	return bone ? bone_data_view(owner, bone->parent) : Ref<SpineBoneData>();
}

void SpineBoneData::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_name"), &SpineBoneData::get_name);
	ClassDB::bind_method(D_METHOD("get_index"), &SpineBoneData::get_index);
	ClassDB::bind_method(D_METHOD("get_parent"), &SpineBoneData::get_parent);
	ClassDB::bind_method(D_METHOD("get_length"), &SpineBoneData::get_length);
	ClassDB::bind_method(D_METHOD("get_x"), &SpineBoneData::get_x);
	ClassDB::bind_method(D_METHOD("get_y"), &SpineBoneData::get_y);
	ClassDB::bind_method(D_METHOD("get_rotation"), &SpineBoneData::get_rotation);
	ClassDB::bind_method(D_METHOD("get_scale_x"), &SpineBoneData::get_scale_x);
	ClassDB::bind_method(D_METHOD("get_scale_y"), &SpineBoneData::get_scale_y);
	ClassDB::bind_method(D_METHOD("get_shear_x"), &SpineBoneData::get_shear_x);
	ClassDB::bind_method(D_METHOD("get_shear_y"), &SpineBoneData::get_shear_y);
}

String SpineSlotData::get_name() const {
	return slot ? to_godot(slot->name) : String();
}

Ref<SpineBoneData> SpineSlotData::get_bone_data() const {
	return slot ? bone_data_view(owner, slot->bone) : Ref<SpineBoneData>();
}

String SpineSlotData::get_attachment_name() const {
	return slot ? to_godot(slot->attachment) : String();
}

Color SpineSlotData::get_color() const {
	return slot ? Color(slot->color.r, slot->color.g, slot->color.b, slot->color.a) : Color(1, 1, 1);
}

void SpineSlotData::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_name"), &SpineSlotData::get_name);
	ClassDB::bind_method(D_METHOD("get_index"), &SpineSlotData::get_index);
	ClassDB::bind_method(D_METHOD("get_bone_data"), &SpineSlotData::get_bone_data);
	ClassDB::bind_method(D_METHOD("get_attachment_name"), &SpineSlotData::get_attachment_name);
	ClassDB::bind_method(D_METHOD("get_color"), &SpineSlotData::get_color);
}

String SpineSkin::get_name() const {
	return skin ? to_godot(skin->name) : name;
}

void SpineSkin::add_skin(const Ref<SpineSkin> &p_other) {
	if (p_other.is_valid()) {
		added.push_back(p_other);
	}
}

Array SpineSkin::get_attachments() const {
	Array out;
	if (skin) {
		for (size_t s = 0; s < skin->slots.size(); s++) {
			for (const auto &kv : skin->slots[s]) {
				Dictionary d;
				d["slot_index"] = int(s);
				d["name"] = to_godot(kv.first);
				out.push_back(d);
			}
		}
	}
	return out;
}

void SpineSkin::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_name"), &SpineSkin::get_name);
	ClassDB::bind_method(D_METHOD("add_skin", "other"), &SpineSkin::add_skin);
	ClassDB::bind_method(D_METHOD("get_attachments"), &SpineSkin::get_attachments);
}

String SpineEventData::get_event_name() const {
	return event ? to_godot(event->name) : String();
}

String SpineEventData::get_string_value() const {
	return event ? to_godot(event->string_value) : String();
}

String SpineEventData::get_audio_path() const {
	return event ? to_godot(event->audio) : String();
}

void SpineEventData::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_event_name"), &SpineEventData::get_event_name);
	ClassDB::bind_method(D_METHOD("get_int_value"), &SpineEventData::get_int_value);
	ClassDB::bind_method(D_METHOD("get_float_value"), &SpineEventData::get_float_value);
	ClassDB::bind_method(D_METHOD("get_string_value"), &SpineEventData::get_string_value);
	ClassDB::bind_method(D_METHOD("get_audio_path"), &SpineEventData::get_audio_path);
	ClassDB::bind_method(D_METHOD("get_volume"), &SpineEventData::get_volume);
	ClassDB::bind_method(D_METHOD("get_balance"), &SpineEventData::get_balance);
}

Ref<SpineEventData> SpineEvent::get_data() const {
	return event_data_view(owner, event.data);
}

String SpineEvent::get_event_name() const {
	return event.data ? to_godot(event.data->name) : String();
}

String SpineEvent::get_string_value() const {
	return to_godot(event.string_value);
}

void SpineEvent::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_data"), &SpineEvent::get_data);
	ClassDB::bind_method(D_METHOD("get_event_name"), &SpineEvent::get_event_name);
	ClassDB::bind_method(D_METHOD("get_time"), &SpineEvent::get_time);
	ClassDB::bind_method(D_METHOD("get_int_value"), &SpineEvent::get_int_value);
	ClassDB::bind_method(D_METHOD("get_float_value"), &SpineEvent::get_float_value);
	ClassDB::bind_method(D_METHOD("get_string_value"), &SpineEvent::get_string_value);
	ClassDB::bind_method(D_METHOD("get_volume"), &SpineEvent::get_volume);
	ClassDB::bind_method(D_METHOD("get_balance"), &SpineEvent::get_balance);
}

// ============================================================================================ track entries

Ref<SpineAnimation> SpineTrackEntry::get_animation() const {
	return entry ? animation_view(owner, entry->animation) : Ref<SpineAnimation>();
}

void SpineTrackEntry::set_track_time(float p) {
	if (entry) {
		entry->track_time = p;
		entry->last_time = -1; // jumping fires no events for the skipped span
		float duration = entry->animation ? entry->animation->duration : 0;
		entry->complete_fired = !entry->loop && duration > 0 && p >= duration;
	}
}

float SpineTrackEntry::get_track_complete() const {
	float duration = get_animation_end();
	if (!entry || duration <= 0) {
		return entry ? entry->track_time : 0;
	}
	if (!entry->loop) {
		return duration;
	}
	return duration * (std::floor(entry->track_time / duration) + 1);
}

bool SpineTrackEntry::is_complete() const {
	return entry && entry->animation && entry->track_time >= get_animation_end();
}

Ref<SpineTrackEntry> SpineTrackEntry::get_next() const {
	Ref<SpineTrackEntry> r;
	if (entry && entry->next) {
		r.instantiate();
		r->owner = owner;
		r->entry = entry->next;
		r->track = track;
	}
	return r;
}

Ref<SpineTrackEntry> SpineTrackEntry::get_mixing_from() const {
	Ref<SpineTrackEntry> r;
	if (entry && entry->from) {
		r.instantiate();
		r->owner = owner;
		r->entry = entry->from;
		r->track = track;
	}
	return r;
}

void SpineTrackEntry::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_track_index"), &SpineTrackEntry::get_track_index);
	ClassDB::bind_method(D_METHOD("get_animation"), &SpineTrackEntry::get_animation);
	ClassDB::bind_method(D_METHOD("get_loop"), &SpineTrackEntry::get_loop);
	ClassDB::bind_method(D_METHOD("set_loop", "loop"), &SpineTrackEntry::set_loop);
	ClassDB::bind_method(D_METHOD("get_track_time"), &SpineTrackEntry::get_track_time);
	ClassDB::bind_method(D_METHOD("set_track_time", "time"), &SpineTrackEntry::set_track_time);
	ClassDB::bind_method(D_METHOD("get_animation_time"), &SpineTrackEntry::get_animation_time);
	ClassDB::bind_method(D_METHOD("get_animation_start"), &SpineTrackEntry::get_animation_start);
	ClassDB::bind_method(D_METHOD("get_animation_end"), &SpineTrackEntry::get_animation_end);
	ClassDB::bind_method(D_METHOD("get_track_complete"), &SpineTrackEntry::get_track_complete);
	ClassDB::bind_method(D_METHOD("is_complete"), &SpineTrackEntry::is_complete);
	ClassDB::bind_method(D_METHOD("get_time_scale"), &SpineTrackEntry::get_time_scale);
	ClassDB::bind_method(D_METHOD("set_time_scale", "scale"), &SpineTrackEntry::set_time_scale);
	ClassDB::bind_method(D_METHOD("get_alpha"), &SpineTrackEntry::get_alpha);
	ClassDB::bind_method(D_METHOD("set_alpha", "alpha"), &SpineTrackEntry::set_alpha);
	ClassDB::bind_method(D_METHOD("get_delay"), &SpineTrackEntry::get_delay);
	ClassDB::bind_method(D_METHOD("set_delay", "delay"), &SpineTrackEntry::set_delay);
	ClassDB::bind_method(D_METHOD("get_mix_duration"), &SpineTrackEntry::get_mix_duration);
	ClassDB::bind_method(D_METHOD("set_mix_duration", "duration"), &SpineTrackEntry::set_mix_duration);
	ClassDB::bind_method(D_METHOD("get_mix_time"), &SpineTrackEntry::get_mix_time);
	ClassDB::bind_method(D_METHOD("set_reverse", "reverse"), &SpineTrackEntry::set_reverse);
	ClassDB::bind_method(D_METHOD("get_reverse"), &SpineTrackEntry::get_reverse);
	ClassDB::bind_method(D_METHOD("set_hold_previous", "hold"), &SpineTrackEntry::set_hold_previous);
	ClassDB::bind_method(D_METHOD("get_hold_previous"), &SpineTrackEntry::get_hold_previous);
	ClassDB::bind_method(D_METHOD("get_next"), &SpineTrackEntry::get_next);
	ClassDB::bind_method(D_METHOD("get_mixing_from"), &SpineTrackEntry::get_mixing_from);
}

// ============================================================================================ animation state

SpineSprite *SpineAnimationState::get_sprite() const {
	return Object::cast_to<SpineSprite>(ObjectDB::get_instance(sprite));
}

#define STATE_OR(ret)                                    \
	SpineSprite *s = get_sprite();                       \
	skel::AnimationState *st = s ? s->core_state() : nullptr; \
	if (!st) {                                           \
		return ret;                                      \
	}

Ref<SpineTrackEntry> SpineAnimationState::set_animation(const String &p_name, bool p_loop, int p_track) {
	STATE_OR(Ref<SpineTrackEntry>());
	ERR_FAIL_COND_V(p_track < 0, Ref<SpineTrackEntry>());
	auto e = st->set_animation(p_track, to_std(p_name), p_loop);
	if (!e) {
		warn_once("no animation " + p_name);
		return Ref<SpineTrackEntry>();
	}
	return s->entry_view(e, p_track);
}

Ref<SpineTrackEntry> SpineAnimationState::add_animation(const String &p_name, float p_delay, bool p_loop, int p_track) {
	STATE_OR(Ref<SpineTrackEntry>());
	ERR_FAIL_COND_V(p_track < 0, Ref<SpineTrackEntry>());
	auto e = st->add_animation(p_track, to_std(p_name), p_loop, p_delay);
	if (!e) {
		warn_once("no animation " + p_name);
		return Ref<SpineTrackEntry>();
	}
	return s->entry_view(e, p_track);
}

Ref<SpineTrackEntry> SpineAnimationState::set_empty_animation(int p_track, float p_mix_duration) {
	STATE_OR(Ref<SpineTrackEntry>());
	ERR_FAIL_COND_V(p_track < 0, Ref<SpineTrackEntry>());
	return s->entry_view(st->set_empty(p_track, p_mix_duration), p_track);
}

Ref<SpineTrackEntry> SpineAnimationState::add_empty_animation(int p_track, float p_mix_duration, float p_delay) {
	STATE_OR(Ref<SpineTrackEntry>());
	ERR_FAIL_COND_V(p_track < 0, Ref<SpineTrackEntry>());
	auto last = st->current(p_track);
	if (!last) {
		return set_empty_animation(p_track, p_mix_duration);
	}
	while (last->next) {
		last = last->next;
	}
	auto e = std::make_shared<skel::TrackEntry>();
	e->loop = false;
	e->mix_duration = p_mix_duration;
	float end = last->animation ? last->animation->duration : 0;
	e->delay = p_delay > 0 ? p_delay : MAX(0.0f, end + p_delay - p_mix_duration);
	last->next = e;
	return s->entry_view(e, p_track);
}

void SpineAnimationState::set_empty_animations(float p_mix_duration) {
	STATE_OR();
	for (int t = 0; t < int(st->tracks.size()); t++) {
		if (st->tracks[t]) {
			st->set_empty(t, p_mix_duration);
		}
	}
}

void SpineAnimationState::clear_track(int p_track) {
	STATE_OR();
	st->clear_track(p_track);
}

void SpineAnimationState::clear_tracks() {
	STATE_OR();
	st->clear_tracks();
}

Ref<SpineTrackEntry> SpineAnimationState::get_current(int p_track) {
	STATE_OR(Ref<SpineTrackEntry>());
	auto e = st->current(p_track);
	return e ? s->entry_view(e, p_track) : Ref<SpineTrackEntry>();
}

Array SpineAnimationState::get_tracks() {
	Array out;
	STATE_OR(out);
	for (int t = 0; t < int(st->tracks.size()); t++) {
		out.push_back(st->tracks[t] ? Variant(s->entry_view(st->tracks[t], t)) : Variant());
	}
	return out;
}

void SpineAnimationState::update(float p_delta) {
	STATE_OR();
	st->update(p_delta);
}

bool SpineAnimationState::apply(const Variant &p_skeleton) {
	STATE_OR(false);
	st->apply(*s->core_skeleton());
	return true;
}

float SpineAnimationState::get_time_scale() const {
	SpineSprite *s = get_sprite();
	return s && s->core_state() ? s->core_state()->time_scale : 1;
}

void SpineAnimationState::set_time_scale(float p) {
	STATE_OR();
	st->time_scale = p;
}

void SpineAnimationState::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_animation", "animation_name", "loop", "track_id"), &SpineAnimationState::set_animation, DEFVAL(true), DEFVAL(0));
	ClassDB::bind_method(D_METHOD("add_animation", "animation_name", "delay", "loop", "track_id"), &SpineAnimationState::add_animation, DEFVAL(0), DEFVAL(true), DEFVAL(0));
	ClassDB::bind_method(D_METHOD("set_empty_animation", "track_id", "mix_duration"), &SpineAnimationState::set_empty_animation);
	ClassDB::bind_method(D_METHOD("add_empty_animation", "track_id", "mix_duration", "delay"), &SpineAnimationState::add_empty_animation);
	ClassDB::bind_method(D_METHOD("set_empty_animations", "mix_duration"), &SpineAnimationState::set_empty_animations);
	ClassDB::bind_method(D_METHOD("clear_track", "track_id"), &SpineAnimationState::clear_track);
	ClassDB::bind_method(D_METHOD("clear_tracks"), &SpineAnimationState::clear_tracks);
	ClassDB::bind_method(D_METHOD("get_current", "track_id"), &SpineAnimationState::get_current);
	ClassDB::bind_method(D_METHOD("get_tracks"), &SpineAnimationState::get_tracks);
	ClassDB::bind_method(D_METHOD("update", "delta"), &SpineAnimationState::update, DEFVAL(0));
	ClassDB::bind_method(D_METHOD("apply", "skeleton"), &SpineAnimationState::apply);
	ClassDB::bind_method(D_METHOD("get_time_scale"), &SpineAnimationState::get_time_scale);
	ClassDB::bind_method(D_METHOD("set_time_scale", "scale"), &SpineAnimationState::set_time_scale);
}

// ============================================================================================ bones and slots

SpineSprite *SpineBone::get_sprite() const {
	return Object::cast_to<SpineSprite>(ObjectDB::get_instance(sprite));
}

skel::Bone *SpineBone::bone() const {
	SpineSprite *s = get_sprite();
	skel::Skeleton *sk = s ? s->core_skeleton() : nullptr;
	return sk && index >= 0 && index < int(sk->bones.size()) ? &sk->bones[index] : nullptr;
}

Ref<SpineBoneData> SpineBone::get_data() const {
	SpineSprite *s = get_sprite();
	return s ? bone_data_view(s->core_data(), index) : Ref<SpineBoneData>();
}

String SpineBone::get_name() const {
	skel::Bone *b = bone();
	return b ? to_godot(b->data->name) : String();
}

Ref<SpineBone> SpineBone::get_parent() const {
	SpineSprite *s = get_sprite();
	skel::Bone *b = bone();
	return s && b && b->data->parent >= 0 ? s->bone_view(b->data->parent) : Ref<SpineBone>();
}

#define BONE_FIELD(getter, setter, field, fallback)  \
	float SpineBone::getter() const {               \
		skel::Bone *b = bone();                      \
		return b ? b->field : fallback;              \
	}                                                \
	void SpineBone::setter(float p) {               \
		if (skel::Bone *b = bone()) {                \
			b->field = p;                            \
		}                                            \
	}

BONE_FIELD(get_x, set_x, x, 0)
BONE_FIELD(get_y, set_y, y, 0)
BONE_FIELD(get_rotation, set_rotation, rotation, 0)
BONE_FIELD(get_scale_x, set_scale_x, scale_x, 1)
BONE_FIELD(get_scale_y, set_scale_y, scale_y, 1)

float SpineBone::get_world_x() const {
	skel::Bone *b = bone();
	return b ? b->world_x : 0;
}

float SpineBone::get_world_y() const {
	skel::Bone *b = bone();
	return b ? b->world_y : 0;
}

float SpineBone::get_world_rotation_x() const {
	skel::Bone *b = bone();
	return b ? b->world_rotation_x() : 0;
}

float SpineBone::get_world_scale_x() const {
	skel::Bone *b = bone();
	return b ? b->world_scale_x() : 1;
}

float SpineBone::get_world_scale_y() const {
	skel::Bone *b = bone();
	return b ? b->world_scale_y() : 1;
}

bool SpineBone::is_active() const {
	skel::Bone *b = bone();
	return b && b->active;
}

void SpineBone::set_to_setup_pose() {
	if (skel::Bone *b = bone()) {
		b->setup();
	}
}

Transform2D SpineBone::get_transform() const {
	skel::Bone *b = bone();
	if (!b) {
		return Transform2D();
	}
	Transform2D t;
	t.set_rotation_scale_and_skew(Math::deg_to_rad(-b->rotation), Size2(b->scale_x, b->scale_y), 0);
	t.set_origin(to_canvas(b->x, b->y));
	return t;
}

Transform2D SpineBone::get_global_transform() const {
	SpineSprite *s = get_sprite();
	return s ? s->get_global_transform() * s->bone_canvas_transform(index) : Transform2D();
}

void SpineBone::set_global_transform(const Transform2D &p) {
	SpineSprite *s = get_sprite();
	if (s) {
		s->set_global_bone_transform(get_name(), p);
	}
}

void SpineBone::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_data"), &SpineBone::get_data);
	ClassDB::bind_method(D_METHOD("get_name"), &SpineBone::get_name);
	ClassDB::bind_method(D_METHOD("get_parent"), &SpineBone::get_parent);
	ClassDB::bind_method(D_METHOD("get_x"), &SpineBone::get_x);
	ClassDB::bind_method(D_METHOD("set_x", "x"), &SpineBone::set_x);
	ClassDB::bind_method(D_METHOD("get_y"), &SpineBone::get_y);
	ClassDB::bind_method(D_METHOD("set_y", "y"), &SpineBone::set_y);
	ClassDB::bind_method(D_METHOD("get_rotation"), &SpineBone::get_rotation);
	ClassDB::bind_method(D_METHOD("set_rotation", "degrees"), &SpineBone::set_rotation);
	ClassDB::bind_method(D_METHOD("get_scale_x"), &SpineBone::get_scale_x);
	ClassDB::bind_method(D_METHOD("set_scale_x", "scale"), &SpineBone::set_scale_x);
	ClassDB::bind_method(D_METHOD("get_scale_y"), &SpineBone::get_scale_y);
	ClassDB::bind_method(D_METHOD("set_scale_y", "scale"), &SpineBone::set_scale_y);
	ClassDB::bind_method(D_METHOD("get_world_x"), &SpineBone::get_world_x);
	ClassDB::bind_method(D_METHOD("get_world_y"), &SpineBone::get_world_y);
	ClassDB::bind_method(D_METHOD("get_world_rotation_x"), &SpineBone::get_world_rotation_x);
	ClassDB::bind_method(D_METHOD("get_world_scale_x"), &SpineBone::get_world_scale_x);
	ClassDB::bind_method(D_METHOD("get_world_scale_y"), &SpineBone::get_world_scale_y);
	ClassDB::bind_method(D_METHOD("is_active"), &SpineBone::is_active);
	ClassDB::bind_method(D_METHOD("set_to_setup_pose"), &SpineBone::set_to_setup_pose);
	ClassDB::bind_method(D_METHOD("get_transform"), &SpineBone::get_transform);
	ClassDB::bind_method(D_METHOD("get_global_transform"), &SpineBone::get_global_transform);
	ClassDB::bind_method(D_METHOD("set_global_transform", "transform"), &SpineBone::set_global_transform);
}

SpineSprite *SpineSlot::get_sprite() const {
	return Object::cast_to<SpineSprite>(ObjectDB::get_instance(sprite));
}

skel::Slot *SpineSlot::slot() const {
	SpineSprite *s = get_sprite();
	skel::Skeleton *sk = s ? s->core_skeleton() : nullptr;
	return sk && index >= 0 && index < int(sk->slots.size()) ? &sk->slots[index] : nullptr;
}

Ref<SpineSlotData> SpineSlot::get_data() const {
	SpineSprite *s = get_sprite();
	return s ? slot_data_view(s->core_data(), index) : Ref<SpineSlotData>();
}

String SpineSlot::get_name() const {
	skel::Slot *sl = slot();
	return sl ? to_godot(sl->data->name) : String();
}

Ref<SpineBone> SpineSlot::get_bone() const {
	SpineSprite *s = get_sprite();
	skel::Slot *sl = slot();
	return s && sl ? s->bone_view(sl->data->bone) : Ref<SpineBone>();
}

Color SpineSlot::get_color() const {
	skel::Slot *sl = slot();
	return sl ? Color(sl->color.r, sl->color.g, sl->color.b, sl->color.a) : Color(1, 1, 1);
}

void SpineSlot::set_color(const Color &p) {
	if (skel::Slot *sl = slot()) {
		sl->color = { p.r, p.g, p.b, p.a };
	}
}

String SpineSlot::get_attachment_name() const {
	skel::Slot *sl = slot();
	return sl && sl->attachment ? to_godot(sl->attachment->name) : String();
}

bool SpineSlot::set_attachment_name(const String &p_name) {
	SpineSprite *s = get_sprite();
	skel::Slot *sl = slot();
	return s && sl && s->core_skeleton()->set_attachment(sl->data->name, to_std(p_name));
}

void SpineSlot::set_to_setup_pose() {
	SpineSprite *s = get_sprite();
	if (skel::Slot *sl = slot()) {
		sl->setup(*s->core_skeleton());
	}
}

int SpineSlot::get_sequence_index() const {
	skel::Slot *sl = slot();
	return sl ? sl->sequence_index : -1;
}

void SpineSlot::set_sequence_index(int p) {
	if (skel::Slot *sl = slot()) {
		sl->sequence_index = p;
	}
}

void SpineSlot::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_data"), &SpineSlot::get_data);
	ClassDB::bind_method(D_METHOD("get_name"), &SpineSlot::get_name);
	ClassDB::bind_method(D_METHOD("get_bone"), &SpineSlot::get_bone);
	ClassDB::bind_method(D_METHOD("get_color"), &SpineSlot::get_color);
	ClassDB::bind_method(D_METHOD("set_color", "color"), &SpineSlot::set_color);
	ClassDB::bind_method(D_METHOD("get_attachment_name"), &SpineSlot::get_attachment_name);
	ClassDB::bind_method(D_METHOD("set_attachment_name", "name"), &SpineSlot::set_attachment_name);
	ClassDB::bind_method(D_METHOD("set_to_setup_pose"), &SpineSlot::set_to_setup_pose);
	ClassDB::bind_method(D_METHOD("get_sequence_index"), &SpineSlot::get_sequence_index);
	ClassDB::bind_method(D_METHOD("set_sequence_index", "index"), &SpineSlot::set_sequence_index);
}

// ============================================================================================ skeleton

SpineSprite *SpineSkeleton::get_sprite() const {
	return Object::cast_to<SpineSprite>(ObjectDB::get_instance(sprite));
}

#define SKEL_OR(ret)                                        \
	SpineSprite *s = get_sprite();                          \
	skel::Skeleton *sk = s ? s->core_skeleton() : nullptr; \
	if (!sk) {                                              \
		return ret;                                         \
	}

Ref<SpineSkeletonDataResource> SpineSkeleton::get_data() const {
	SpineSprite *s = get_sprite();
	return s ? s->get_skeleton_data_res() : Ref<SpineSkeletonDataResource>();
}

Ref<SpineBone> SpineSkeleton::find_bone(const String &p_name) const {
	SKEL_OR(Ref<SpineBone>());
	return s->bone_view(sk->data->find_bone(to_std(p_name)));
}

Ref<SpineSlot> SpineSkeleton::find_slot(const String &p_name) const {
	SKEL_OR(Ref<SpineSlot>());
	return s->slot_view(sk->data->find_slot(to_std(p_name)));
}

Ref<SpineBone> SpineSkeleton::get_root_bone() const {
	SKEL_OR(Ref<SpineBone>());
	return s->bone_view(0);
}

Array SpineSkeleton::get_bones() const {
	Array out;
	SKEL_OR(out);
	for (int i = 0; i < int(sk->bones.size()); i++) {
		out.push_back(s->bone_view(i));
	}
	return out;
}

Array SpineSkeleton::get_slots() const {
	Array out;
	SKEL_OR(out);
	for (int i = 0; i < int(sk->slots.size()); i++) {
		out.push_back(s->slot_view(i));
	}
	return out;
}

Array SpineSkeleton::get_draw_order() const {
	Array out;
	SKEL_OR(out);
	for (int i : sk->draw_order) {
		out.push_back(s->slot_view(i));
	}
	return out;
}

bool SpineSkeleton::set_skin_by_name(const String &p_name) {
	SKEL_OR(false);
	bool ok = sk->set_skin(to_std(p_name));
	if (!ok) {
		warn_once("no skin " + p_name);
	}
	s->queue_redraw();
	return ok;
}

void SpineSkeleton::set_skin(const Ref<SpineSkin> &p_skin) {
	SKEL_OR();
	if (p_skin.is_null()) {
		sk->skin = nullptr;
		return;
	}
	if (p_skin->skin) {
		sk->set_skin(p_skin->skin->name);
	} else if (!p_skin->added.is_empty()) {
		// A skin built from others: the core holds one skin at a time, so take the last one added.
		warn_once("combined skins show only their last part");
		for (int i = p_skin->added.size() - 1; i >= 0; i--) {
			if (p_skin->added[i].is_valid() && p_skin->added[i]->skin) {
				sk->set_skin(p_skin->added[i]->skin->name);
				break;
			}
		}
	}
	s->queue_redraw();
}

Ref<SpineSkin> SpineSkeleton::get_skin() const {
	SKEL_OR(Ref<SpineSkin>());
	return skin_view(s->core_data(), sk->skin);
}

void SpineSkeleton::set_to_setup_pose() {
	SKEL_OR();
	sk->setup_pose();
}

void SpineSkeleton::set_bones_to_setup_pose() {
	SKEL_OR();
	sk->setup_bones();
}

void SpineSkeleton::set_slots_to_setup_pose() {
	SKEL_OR();
	sk->setup_slots();
}

bool SpineSkeleton::set_attachment(const String &p_slot, const String &p_attachment) {
	SKEL_OR(false);
	return sk->set_attachment(to_std(p_slot), to_std(p_attachment));
}

void SpineSkeleton::update_world_transform() {
	SKEL_OR();
	sk->update_world_transform();
}

Color SpineSkeleton::get_color() const {
	SKEL_OR(Color(1, 1, 1));
	return Color(sk->color.r, sk->color.g, sk->color.b, sk->color.a);
}

void SpineSkeleton::set_color(const Color &p) {
	SKEL_OR();
	sk->color = { p.r, p.g, p.b, p.a };
	s->queue_redraw();
}

#define SKEL_FIELD(getter, setter, field, fallback) \
	float SpineSkeleton::getter() const {          \
		SKEL_OR(fallback);                          \
		return sk->field;                           \
	}                                               \
	void SpineSkeleton::setter(float p) {          \
		SKEL_OR();                                  \
		sk->field = p;                              \
	}

SKEL_FIELD(get_x, set_x, x, 0)
SKEL_FIELD(get_y, set_y, y, 0)
SKEL_FIELD(get_scale_x, set_scale_x, scale_x, 1)
SKEL_FIELD(get_scale_y, set_scale_y, scale_y, 1)

Rect2 SpineSkeleton::get_bounds() const {
	Rect2 r;
	SKEL_OR(r);
	bool first = true;
	std::vector<float> world;
	for (size_t i = 0; i < sk->slots.size(); i++) {
		int n = sk->world_vertices(int(i), world);
		for (int v = 0; v < n; v++) {
			Vector2 p = to_canvas(world[v * 2], world[v * 2 + 1]);
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

void SpineSkeleton::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_data"), &SpineSkeleton::get_data);
	ClassDB::bind_method(D_METHOD("find_bone", "name"), &SpineSkeleton::find_bone);
	ClassDB::bind_method(D_METHOD("find_slot", "name"), &SpineSkeleton::find_slot);
	ClassDB::bind_method(D_METHOD("get_root_bone"), &SpineSkeleton::get_root_bone);
	ClassDB::bind_method(D_METHOD("get_bones"), &SpineSkeleton::get_bones);
	ClassDB::bind_method(D_METHOD("get_slots"), &SpineSkeleton::get_slots);
	ClassDB::bind_method(D_METHOD("get_draw_order"), &SpineSkeleton::get_draw_order);
	ClassDB::bind_method(D_METHOD("set_skin_by_name", "name"), &SpineSkeleton::set_skin_by_name);
	ClassDB::bind_method(D_METHOD("set_skin", "skin"), &SpineSkeleton::set_skin);
	ClassDB::bind_method(D_METHOD("get_skin"), &SpineSkeleton::get_skin);
	ClassDB::bind_method(D_METHOD("set_to_setup_pose"), &SpineSkeleton::set_to_setup_pose);
	ClassDB::bind_method(D_METHOD("set_bones_to_setup_pose"), &SpineSkeleton::set_bones_to_setup_pose);
	ClassDB::bind_method(D_METHOD("set_slots_to_setup_pose"), &SpineSkeleton::set_slots_to_setup_pose);
	ClassDB::bind_method(D_METHOD("set_attachment", "slot_name", "attachment_name"), &SpineSkeleton::set_attachment);
	ClassDB::bind_method(D_METHOD("update_world_transform"), &SpineSkeleton::update_world_transform);
	ClassDB::bind_method(D_METHOD("get_color"), &SpineSkeleton::get_color);
	ClassDB::bind_method(D_METHOD("set_color", "color"), &SpineSkeleton::set_color);
	ClassDB::bind_method(D_METHOD("get_x"), &SpineSkeleton::get_x);
	ClassDB::bind_method(D_METHOD("set_x", "x"), &SpineSkeleton::set_x);
	ClassDB::bind_method(D_METHOD("get_y"), &SpineSkeleton::get_y);
	ClassDB::bind_method(D_METHOD("set_y", "y"), &SpineSkeleton::set_y);
	ClassDB::bind_method(D_METHOD("get_scale_x"), &SpineSkeleton::get_scale_x);
	ClassDB::bind_method(D_METHOD("set_scale_x", "scale"), &SpineSkeleton::set_scale_x);
	ClassDB::bind_method(D_METHOD("get_scale_y"), &SpineSkeleton::get_scale_y);
	ClassDB::bind_method(D_METHOD("set_scale_y", "scale"), &SpineSkeleton::set_scale_y);
	ClassDB::bind_method(D_METHOD("get_bounds"), &SpineSkeleton::get_bounds);
}

// ============================================================================================ sprite

void SpineSprite::set_skeleton_data_res(const Ref<SpineSkeletonDataResource> &p) {
	if (skeleton_data_res.is_valid()) {
		skeleton_data_res->disconnect_changed(callable_mp(this, &SpineSprite::_data_changed));
	}
	skeleton_data_res = p;
	if (skeleton_data_res.is_valid()) {
		skeleton_data_res->connect_changed(callable_mp(this, &SpineSprite::_data_changed));
	}
	_rebuild();
}

void SpineSprite::_data_changed() {
	if (skeleton_data_res.is_valid() && skeleton_data_res->get_data() != data) {
		_rebuild();
	}
}

void SpineSprite::_rebuild() {
	state.reset();
	skeleton.reset();
	entry_views.clear();
	for (Ref<SpineBone> &b : bone_views) {
		if (b.is_valid()) {
			b->index = -1;
		}
	}
	for (Ref<SpineSlot> &sl : slot_views) {
		if (sl.is_valid()) {
			sl->index = -1;
		}
	}
	bone_views.clear();
	slot_views.clear();
	data.reset();
	state_data.reset();
	if (skeleton_data_res.is_null()) {
		queue_redraw();
		return;
	}
	data = skeleton_data_res->get_data();
	state_data = skeleton_data_res->get_state_data();
	if (!data || !state_data) {
		queue_redraw();
		return;
	}
	state = std::make_unique<skel::AnimationState>(state_data.get());
	skeleton = std::make_unique<skel::Skeleton>(data.get());
	bone_views.resize(int(data->bones.size()));
	slot_views.resize(int(data->slots.size()));
	if (!preview_skin.is_empty() && preview_skin != "default") {
		skeleton->set_skin(to_std(preview_skin));
	}
	skeleton->update_world_transform();
	queue_redraw();
}

void SpineSprite::set_update_mode(UpdateMode p) {
	update_mode = p;
	if (is_inside_tree()) {
		set_process_internal(update_mode == UPDATE_MODE_PROCESS);
		set_physics_process_internal(update_mode == UPDATE_MODE_PHYSICS);
	}
}

void SpineSprite::set_preview_skin(const String &p) {
	preview_skin = p;
	if (skeleton && !p.is_empty() && p != "default") {
		skeleton->set_skin(to_std(p));
		queue_redraw();
	}
}

Ref<SpineSkeleton> SpineSprite::get_skeleton() {
	if (!skeleton) {
		return Ref<SpineSkeleton>();
	}
	if (skeleton_view.is_null()) {
		skeleton_view.instantiate();
		skeleton_view->sprite = get_instance_id();
	}
	return skeleton_view;
}

Ref<SpineAnimationState> SpineSprite::get_animation_state() {
	if (!state) {
		return Ref<SpineAnimationState>();
	}
	if (state_view.is_null()) {
		state_view.instantiate();
		state_view->sprite = get_instance_id();
	}
	return state_view;
}

Ref<SpineTrackEntry> SpineSprite::entry_view(const std::shared_ptr<skel::TrackEntry> &p_entry, int p_track) {
	if (!p_entry) {
		return Ref<SpineTrackEntry>();
	}
	if (Ref<SpineTrackEntry> *found = entry_views.getptr(p_entry.get())) {
		return *found;
	}
	Ref<SpineTrackEntry> r;
	r.instantiate();
	r->owner = data;
	r->entry = p_entry;
	r->track = p_track;
	entry_views[p_entry.get()] = r;
	return r;
}

Ref<SpineBone> SpineSprite::bone_view(int p_index) {
	if (p_index < 0 || p_index >= bone_views.size()) {
		return Ref<SpineBone>();
	}
	if (bone_views[p_index].is_null()) {
		Ref<SpineBone> b;
		b.instantiate();
		b->sprite = get_instance_id();
		b->index = p_index;
		bone_views.write[p_index] = b;
	}
	return bone_views[p_index];
}

Ref<SpineSlot> SpineSprite::slot_view(int p_index) {
	if (p_index < 0 || p_index >= slot_views.size()) {
		return Ref<SpineSlot>();
	}
	if (slot_views[p_index].is_null()) {
		Ref<SpineSlot> sl;
		sl.instantiate();
		sl->sprite = get_instance_id();
		sl->index = p_index;
		slot_views.write[p_index] = sl;
	}
	return slot_views[p_index];
}

Transform2D SpineSprite::bone_canvas_transform(int p_index) const {
	if (!skeleton || p_index < 0 || p_index >= int(skeleton->bones.size())) {
		return Transform2D();
	}
	const skel::Bone &b = skeleton->bones[p_index];
	// World matrix [a b; c d] is y-up; flipping y on both sides gives [a -b; -c d] on the canvas.
	return Transform2D(b.a, -b.c, -b.b, b.d, b.world_x, -b.world_y);
}

Transform2D SpineSprite::get_global_bone_transform(const String &p_bone) {
	if (!skeleton) {
		return get_global_transform();
	}
	int i = data->find_bone(to_std(p_bone));
	if (i < 0) {
		warn_once("no bone " + p_bone);
		return get_global_transform();
	}
	return get_global_transform() * bone_canvas_transform(i);
}

void SpineSprite::set_global_bone_transform(const String &p_bone, const Transform2D &p_transform) {
	if (!skeleton) {
		return;
	}
	int i = data->find_bone(to_std(p_bone));
	if (i < 0) {
		warn_once("no bone " + p_bone);
		return;
	}
	skel::Bone &b = skeleton->bones[i];
	// Into the parent bone's space (or the skeleton's for the root), back to y-up.
	Transform2D local = get_global_transform().affine_inverse() * p_transform;
	Vector2 pos(local.get_origin().x, -local.get_origin().y);
	float rot = -Math::rad_to_deg(local.get_rotation());
	if (b.data->parent >= 0) {
		const skel::Bone &p = skeleton->bones[b.data->parent];
		float det = p.a * p.d - p.b * p.c;
		if (Math::abs(det) > 1e-6f) {
			float dx = pos.x - p.world_x, dy = pos.y - p.world_y;
			pos = Vector2((dx * p.d - dy * p.b) / det, (dy * p.a - dx * p.c) / det);
		}
		rot -= p.world_rotation_x();
	}
	b.x = pos.x;
	b.y = pos.y;
	b.rotation = rot;
	skeleton->update_world_transform();
	queue_redraw();
}

Ref<SpineSkin> SpineSprite::new_skin(const String &p_name) {
	Ref<SpineSkin> s;
	s.instantiate();
	s->owner = data;
	s->name = p_name;
	custom_skins.push_back(s);
	return s;
}

PackedStringArray SpineSprite::get_unsupported_features() const {
	PackedStringArray out;
	if (data) {
		for (const auto &u : data->unsupported) {
			out.push_back(to_godot(u));
		}
	}
	return out;
}

void SpineSprite::_emit_pending() {
	if (!state) {
		return;
	}
	std::vector<skel::StateEvent> events;
	events.swap(state->pending);
	Ref<SpineAnimationState> sv = get_animation_state();
	for (const skel::StateEvent &e : events) {
		Ref<SpineTrackEntry> entry = entry_view(e.entry, e.track);
		switch (e.kind) {
			case skel::StateEvent::Start:
				emit_signal(SNAME("animation_started"), this, sv, entry);
				break;
			case skel::StateEvent::Interrupt:
				emit_signal(SNAME("animation_interrupted"), this, sv, entry);
				break;
			case skel::StateEvent::Complete:
				emit_signal(SNAME("animation_completed"), this, sv, entry);
				break;
			case skel::StateEvent::End:
				emit_signal(SNAME("animation_ended"), this, sv, entry);
				emit_signal(SNAME("animation_disposed"), this, sv, entry);
				if (e.entry) {
					entry_views.erase(e.entry.get());
				}
				break;
			case skel::StateEvent::Fire: {
				Ref<SpineEvent> ev;
				ev.instantiate();
				ev->owner = data;
				ev->event = e.event;
				emit_signal(SNAME("animation_event"), this, sv, entry, ev);
			} break;
		}
		if (!state) {
			return; // a handler swapped the skeleton
		}
	}
}

void SpineSprite::update_skeleton(float p_delta) {
	if (!skeleton || !state) {
		return;
	}
	emit_signal(SNAME("before_animation_state_update"), this);
	state->update(p_delta * time_scale);
	emit_signal(SNAME("before_animation_state_apply"), this);
	state->apply(*skeleton);
	emit_signal(SNAME("before_world_transforms_change"), this);
	for (int i = 0; i < get_child_count(); i++) {
		if (SpineBoneNode *bn = Object::cast_to<SpineBoneNode>(get_child(i))) {
			bn->drive(this);
		}
	}
	skeleton->update_world_transform();
	emit_signal(SNAME("world_transforms_changed"), this);
	_follow_children();
	_emit_pending();
	queue_redraw();
}

void SpineSprite::_follow_children() {
	for (int i = 0; i < get_child_count(); i++) {
		Node *c = get_child(i);
		if (SpineBoneNode *bn = Object::cast_to<SpineBoneNode>(c)) {
			bn->follow(this);
		} else if (SpineSlotNode *sn = Object::cast_to<SpineSlotNode>(c)) {
			sn->follow(this);
		}
	}
}

RID SpineSprite::_blend_item(int p_run, int p_blend) {
	RenderingServer *rs = RenderingServer::get_singleton();
	while (blend_items.size() <= p_run) {
		RID item = rs->canvas_item_create();
		rs->canvas_item_set_parent(item, get_canvas_item());
		rs->canvas_item_set_draw_index(item, blend_items.size());
		blend_items.push_back(item);
	}
	RID item = blend_items[p_run];
	rs->canvas_item_clear(item);
	if (p_blend == int(skel::BlendMode::Normal)) {
		rs->canvas_item_set_use_parent_material(item, true);
		rs->canvas_item_set_material(item, RID());
	} else {
		if (blend_materials[p_blend].is_null()) {
			Ref<CanvasItemMaterial> m;
			m.instantiate();
			// The canvas has no screen blend; additive is the nearest.
			m->set_blend_mode(p_blend == int(skel::BlendMode::Multiply) ? CanvasItemMaterial::BLEND_MODE_MUL : CanvasItemMaterial::BLEND_MODE_ADD);
			blend_materials[p_blend] = m;
		}
		rs->canvas_item_set_use_parent_material(item, false);
		rs->canvas_item_set_material(item, blend_materials[p_blend]->get_rid());
	}
	return item;
}

void SpineSprite::_free_blend_items() {
	RenderingServer *rs = RenderingServer::get_singleton();
	for (const RID &item : blend_items) {
		rs->free(item);
	}
	blend_items.clear();
}

void SpineSprite::_draw_skeleton() {
	RenderingServer *rs = RenderingServer::get_singleton();
	for (const RID &item : blend_items) {
		rs->canvas_item_clear(item);
	}
	if (!skeleton) {
		return;
	}
	const Vector<Ref<Texture2D>> *pages = nullptr;
	if (skeleton_data_res.is_valid() && skeleton_data_res->get_atlas_res().is_valid()) {
		pages = &skeleton_data_res->get_atlas_res()->get_page_textures();
	}
	RID item = get_canvas_item();
	int run = -1, run_blend = int(skel::BlendMode::Normal);
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
		int blend = int(slot.data->blend);
		if (blend != run_blend) {
			// Once one slot leaves the node's own item, every later slot goes to child items in order.
			run++;
			run_blend = blend;
			item = _blend_item(run, blend);
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
		if (pages && a->page >= 0 && a->page < pages->size() && (*pages)[a->page].is_valid()) {
			tex = (*pages)[a->page]->get_rid();
		}
		rs->canvas_item_add_triangle_array(item, indices, points, colors, uvs, Vector<int>(), Vector<float>(), tex);
	}
}

void SpineSprite::_notification(int p_what) {
	switch (p_what) {
		case NOTIFICATION_READY:
			spine_compat_stats.sprites++;
			set_update_mode(update_mode);
			_follow_children();
			break;
		case NOTIFICATION_INTERNAL_PROCESS:
			update_skeleton(float(get_process_delta_time()));
			break;
		case NOTIFICATION_INTERNAL_PHYSICS_PROCESS:
			update_skeleton(float(get_physics_process_delta_time()));
			break;
		case NOTIFICATION_DRAW:
			_draw_skeleton();
			break;
		case NOTIFICATION_PREDELETE:
			_free_blend_items();
			break;
	}
}

bool SpineSprite::_set(const StringName &p_name, const Variant &p_value) {
	// Debug and material settings the plugin's editor saves: keep them so scenes load and save cleanly.
	String n = p_name;
	if (n.begins_with("debug_") || n.ends_with("_material") || n.begins_with("preview_") || n == "normal_material") {
		extra[p_name] = p_value;
		return true;
	}
	return false;
}

bool SpineSprite::_get(const StringName &p_name, Variant &r_ret) const {
	if (const Variant *v = extra.getptr(p_name)) {
		r_ret = *v;
		return true;
	}
	return false;
}

void SpineSprite::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_skeleton_data_res", "res"), &SpineSprite::set_skeleton_data_res);
	ClassDB::bind_method(D_METHOD("get_skeleton_data_res"), &SpineSprite::get_skeleton_data_res);
	ClassDB::bind_method(D_METHOD("set_update_mode", "mode"), &SpineSprite::set_update_mode);
	ClassDB::bind_method(D_METHOD("get_update_mode"), &SpineSprite::get_update_mode);
	ClassDB::bind_method(D_METHOD("set_preview_skin", "skin"), &SpineSprite::set_preview_skin);
	ClassDB::bind_method(D_METHOD("get_preview_skin"), &SpineSprite::get_preview_skin);
	ClassDB::bind_method(D_METHOD("set_preview_animation", "animation"), &SpineSprite::set_preview_animation);
	ClassDB::bind_method(D_METHOD("get_preview_animation"), &SpineSprite::get_preview_animation);
	ClassDB::bind_method(D_METHOD("set_preview_frame", "frame"), &SpineSprite::set_preview_frame);
	ClassDB::bind_method(D_METHOD("get_preview_frame"), &SpineSprite::get_preview_frame);
	ClassDB::bind_method(D_METHOD("set_preview_time", "time"), &SpineSprite::set_preview_time);
	ClassDB::bind_method(D_METHOD("get_preview_time"), &SpineSprite::get_preview_time);
	ClassDB::bind_method(D_METHOD("set_time_scale", "scale"), &SpineSprite::set_time_scale);
	ClassDB::bind_method(D_METHOD("get_time_scale"), &SpineSprite::get_time_scale);
	ClassDB::bind_method(D_METHOD("get_skeleton"), &SpineSprite::get_skeleton);
	ClassDB::bind_method(D_METHOD("get_animation_state"), &SpineSprite::get_animation_state);
	ClassDB::bind_method(D_METHOD("update_skeleton", "delta"), &SpineSprite::update_skeleton);
	ClassDB::bind_method(D_METHOD("get_global_bone_transform", "bone_name"), &SpineSprite::get_global_bone_transform);
	ClassDB::bind_method(D_METHOD("set_global_bone_transform", "bone_name", "global_transform"), &SpineSprite::set_global_bone_transform);
	ClassDB::bind_method(D_METHOD("new_skin", "name"), &SpineSprite::new_skin);
	ClassDB::bind_method(D_METHOD("get_unsupported_features"), &SpineSprite::get_unsupported_features);

	ADD_PROPERTY(PropertyInfo(Variant::OBJECT, "skeleton_data_res", PROPERTY_HINT_RESOURCE_TYPE, "SpineSkeletonDataResource"), "set_skeleton_data_res", "get_skeleton_data_res");
	ADD_PROPERTY(PropertyInfo(Variant::INT, "update_mode", PROPERTY_HINT_ENUM, "Process,Physics,Manual"), "set_update_mode", "get_update_mode");
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "preview_skin"), "set_preview_skin", "get_preview_skin");
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "preview_animation"), "set_preview_animation", "get_preview_animation");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "preview_frame"), "set_preview_frame", "get_preview_frame");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "preview_time"), "set_preview_time", "get_preview_time");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "time_scale"), "set_time_scale", "get_time_scale");

	BIND_ENUM_CONSTANT(UPDATE_MODE_PROCESS);
	BIND_ENUM_CONSTANT(UPDATE_MODE_PHYSICS);
	BIND_ENUM_CONSTANT(UPDATE_MODE_MANUAL);

	PropertyInfo sprite_arg(Variant::OBJECT, "spine_sprite", PROPERTY_HINT_RESOURCE_TYPE, "SpineSprite");
	PropertyInfo state_arg(Variant::OBJECT, "animation_state", PROPERTY_HINT_RESOURCE_TYPE, "SpineAnimationState");
	PropertyInfo entry_arg(Variant::OBJECT, "track_entry", PROPERTY_HINT_RESOURCE_TYPE, "SpineTrackEntry");
	PropertyInfo event_arg(Variant::OBJECT, "event", PROPERTY_HINT_RESOURCE_TYPE, "SpineEvent");
	ADD_SIGNAL(MethodInfo("animation_started", sprite_arg, state_arg, entry_arg));
	ADD_SIGNAL(MethodInfo("animation_interrupted", sprite_arg, state_arg, entry_arg));
	ADD_SIGNAL(MethodInfo("animation_ended", sprite_arg, state_arg, entry_arg));
	ADD_SIGNAL(MethodInfo("animation_completed", sprite_arg, state_arg, entry_arg));
	ADD_SIGNAL(MethodInfo("animation_disposed", sprite_arg, state_arg, entry_arg));
	ADD_SIGNAL(MethodInfo("animation_event", sprite_arg, state_arg, entry_arg, event_arg));
	ADD_SIGNAL(MethodInfo("before_animation_state_update", sprite_arg));
	ADD_SIGNAL(MethodInfo("before_animation_state_apply", sprite_arg));
	ADD_SIGNAL(MethodInfo("before_world_transforms_change", sprite_arg));
	ADD_SIGNAL(MethodInfo("world_transforms_changed", sprite_arg));
}

// ============================================================================================ child nodes

void SpineBoneNode::follow(SpineSprite *p_sprite) {
	if (!enabled || bone_mode != BONE_MODE_FOLLOW || !p_sprite->core_skeleton()) {
		return;
	}
	int i = p_sprite->core_data()->find_bone(to_std(bone_name));
	if (i < 0) {
		return;
	}
	set_transform(p_sprite->bone_canvas_transform(i));
}

void SpineBoneNode::drive(SpineSprite *p_sprite) {
	if (!enabled || bone_mode != BONE_MODE_DRIVE || !p_sprite->core_skeleton()) {
		return;
	}
	p_sprite->set_global_bone_transform(bone_name, get_global_transform());
}

void SpineBoneNode::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_bone_name", "name"), &SpineBoneNode::set_bone_name);
	ClassDB::bind_method(D_METHOD("get_bone_name"), &SpineBoneNode::get_bone_name);
	ClassDB::bind_method(D_METHOD("set_bone_mode", "mode"), &SpineBoneNode::set_bone_mode);
	ClassDB::bind_method(D_METHOD("get_bone_mode"), &SpineBoneNode::get_bone_mode);
	ClassDB::bind_method(D_METHOD("set_enabled", "enabled"), &SpineBoneNode::set_enabled);
	ClassDB::bind_method(D_METHOD("get_enabled"), &SpineBoneNode::get_enabled);
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "bone_name"), "set_bone_name", "get_bone_name");
	ADD_PROPERTY(PropertyInfo(Variant::INT, "bone_mode", PROPERTY_HINT_ENUM, "Follow,Drive"), "set_bone_mode", "get_bone_mode");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "enabled"), "set_enabled", "get_enabled");
	BIND_ENUM_CONSTANT(BONE_MODE_FOLLOW);
	BIND_ENUM_CONSTANT(BONE_MODE_DRIVE);
}

void SpineSlotNode::follow(SpineSprite *p_sprite) {
	if (!p_sprite->core_skeleton()) {
		return;
	}
	int i = p_sprite->core_data()->find_slot(to_std(slot_name));
	if (i < 0) {
		return;
	}
	set_transform(p_sprite->bone_canvas_transform(p_sprite->core_data()->slots[i].bone));
}

void SpineSlotNode::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_slot_name", "name"), &SpineSlotNode::set_slot_name);
	ClassDB::bind_method(D_METHOD("get_slot_name"), &SpineSlotNode::get_slot_name);
	ClassDB::bind_method(D_METHOD("set_normal_material", "material"), &SpineSlotNode::set_normal_material);
	ClassDB::bind_method(D_METHOD("get_normal_material"), &SpineSlotNode::get_normal_material);
	ClassDB::bind_method(D_METHOD("set_additive_material", "material"), &SpineSlotNode::set_additive_material);
	ClassDB::bind_method(D_METHOD("get_additive_material"), &SpineSlotNode::get_additive_material);
	ClassDB::bind_method(D_METHOD("set_multiply_material", "material"), &SpineSlotNode::set_multiply_material);
	ClassDB::bind_method(D_METHOD("get_multiply_material"), &SpineSlotNode::get_multiply_material);
	ClassDB::bind_method(D_METHOD("set_screen_material", "material"), &SpineSlotNode::set_screen_material);
	ClassDB::bind_method(D_METHOD("get_screen_material"), &SpineSlotNode::get_screen_material);
	ADD_PROPERTY(PropertyInfo(Variant::STRING, "slot_name"), "set_slot_name", "get_slot_name");
	ADD_PROPERTY(PropertyInfo(Variant::OBJECT, "normal_material", PROPERTY_HINT_RESOURCE_TYPE, "Material"), "set_normal_material", "get_normal_material");
	ADD_PROPERTY(PropertyInfo(Variant::OBJECT, "additive_material", PROPERTY_HINT_RESOURCE_TYPE, "Material"), "set_additive_material", "get_additive_material");
	ADD_PROPERTY(PropertyInfo(Variant::OBJECT, "multiply_material", PROPERTY_HINT_RESOURCE_TYPE, "Material"), "set_multiply_material", "get_multiply_material");
	ADD_PROPERTY(PropertyInfo(Variant::OBJECT, "screen_material", PROPERTY_HINT_RESOURCE_TYPE, "Material"), "set_screen_material", "get_screen_material");
}

// ============================================================================================ loader

Ref<Resource> SpineResourceLoader::load(const String &p_path, const String &p_original_path, Error *r_error,
		bool p_use_sub_threads, float *r_progress, CacheMode p_cache_mode) {
	String ext = p_path.get_extension().to_lower();
	Error err = ERR_FILE_UNRECOGNIZED;
	Ref<Resource> out;
	if (ext == "spskel") {
		Ref<SpineSkeletonFileResource> r;
		r.instantiate();
		err = r->load_from_file(p_path);
		out = r;
	} else if (ext == "spatlas") {
		Ref<SpineAtlasResource> r;
		r.instantiate();
		err = r->load_imported(p_path);
		if (err != OK) {
			ERR_PRINT("spine_compat: " + r->get_load_error());
		}
		out = r;
	}
	if (r_error) {
		*r_error = err;
	}
	return err == OK ? out : Ref<Resource>();
}

void SpineResourceLoader::get_recognized_extensions(List<String> *p_extensions) const {
	p_extensions->push_back("spskel");
	p_extensions->push_back("spatlas");
}

bool SpineResourceLoader::handles_type(const String &p_type) const {
	return p_type == "SpineSkeletonFileResource" || p_type == "SpineAtlasResource";
}

String SpineResourceLoader::get_resource_type(const String &p_path) const {
	String ext = p_path.get_extension().to_lower();
	if (ext == "spskel") {
		return "SpineSkeletonFileResource";
	}
	if (ext == "spatlas") {
		return "SpineAtlasResource";
	}
	return String();
}
