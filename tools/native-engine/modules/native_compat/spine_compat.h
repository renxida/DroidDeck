// Clean-room code for DroidDeck/Kestrel: written from scratch; no third-party runtime source consulted.
//
// Godot classes under the names a game built with the official skeleton plugin expects
// (SpineSprite, SpineAnimationState, ...), backed by our own skel core. Names, argument orders and
// signals follow the plugin's public user documentation and what the game's own scenes and code
// use (from the on-device inventory); behaviour is our own. Anything the core cannot do yet is
// accepted and ignored rather than failing, and listed by get_unsupported_features().
#pragma once

#include "skel/atlas.h"
#include "skel/skel.h"

#include "core/io/resource.h"
#include "core/io/resource_loader.h"
#include "core/object/ref_counted.h"
#include "core/templates/hash_map.h"
#include "scene/2d/node_2d.h"
#include "scene/resources/texture.h"

#include <memory>

class SpineSprite;
class SpineSkeletonDataResource;

// --- Resources ----------------------------------------------------------------------------------------------

class SpineAtlasResource : public Resource {
	GDCLASS(SpineAtlasResource, Resource);

	String source_path;
	String atlas_text;
	String normal_texture_prefix = "n";
	String specular_texture_prefix = "s";
	skel::Atlas atlas;
	Vector<Ref<Texture2D>> textures;
	String load_error;

protected:
	static void _bind_methods();

public:
	// Text atlas whose page images sit next to [p_path].
	Error load_from_atlas_file(const String &p_path);
	// The imported form: JSON with atlas_data and source_path.
	Error load_imported(const String &p_path);
	Error parse(const String &p_text, const String &p_source_path);

	String get_source_path() const { return source_path; }
	String get_load_error() const { return load_error; }
	Array get_textures() const;
	const skel::Atlas &get_atlas() const { return atlas; }
	const Vector<Ref<Texture2D>> &get_page_textures() const { return textures; }
	bool is_loaded() const { return !atlas.pages.empty(); }
	void set_normal_texture_prefix(const String &p) { normal_texture_prefix = p; }
	String get_normal_texture_prefix() const { return normal_texture_prefix; }
	void set_specular_texture_prefix(const String &p) { specular_texture_prefix = p; }
	String get_specular_texture_prefix() const { return specular_texture_prefix; }
};

class SpineSkeletonFileResource : public Resource {
	GDCLASS(SpineSkeletonFileResource, Resource);

	PackedByteArray bytes;

protected:
	static void _bind_methods();

public:
	Error load_from_file(const String &p_path);
	void set_bytes(const PackedByteArray &p_bytes) { bytes = p_bytes; emit_changed(); }
	PackedByteArray get_bytes() const { return bytes; }
	bool is_json() const;
};

class SpineAnimationMix : public Resource {
	GDCLASS(SpineAnimationMix, Resource);

	String from, to;
	float mix = 0;

protected:
	static void _bind_methods();

public:
	void set_from(const String &p) { from = p; emit_changed(); }
	String get_from() const { return from; }
	void set_to(const String &p) { to = p; emit_changed(); }
	String get_to() const { return to; }
	void set_mix(float p) { mix = p; emit_changed(); }
	float get_mix() const { return mix; }
};

class SpineAnimation;
class SpineBoneData;
class SpineSlotData;
class SpineSkin;
class SpineEventData;

class SpineSkeletonDataResource : public Resource {
	GDCLASS(SpineSkeletonDataResource, Resource);

	Ref<SpineSkeletonFileResource> skeleton_file_res;
	Ref<SpineAtlasResource> atlas_res;
	float default_mix = 0;
	Array animation_mixes;

	std::shared_ptr<skel::SkeletonData> data;
	std::shared_ptr<skel::AnimationStateData> state_data;
	String load_error;
	bool dirty = true;

	void _rebuild();
	void _changed();

protected:
	static void _bind_methods();

public:
	void set_skeleton_file_res(const Ref<SpineSkeletonFileResource> &p);
	Ref<SpineSkeletonFileResource> get_skeleton_file_res() const { return skeleton_file_res; }
	void set_atlas_res(const Ref<SpineAtlasResource> &p);
	Ref<SpineAtlasResource> get_atlas_res() const { return atlas_res; }
	void set_default_mix(float p);
	float get_default_mix() const { return default_mix; }
	void set_animation_mixes(const Array &p);
	Array get_animation_mixes() const { return animation_mixes; }

	// Shared by every sprite that shows this resource; null until both files are set and valid.
	std::shared_ptr<skel::SkeletonData> get_data();
	std::shared_ptr<skel::AnimationStateData> get_state_data();
	bool is_skeleton_data_loaded();
	String get_load_error() { get_data(); return load_error; }

	Ref<SpineAnimation> find_animation(const String &p_name);
	Ref<SpineBoneData> find_bone(const String &p_name);
	Ref<SpineSlotData> find_slot(const String &p_name);
	Ref<SpineSkin> find_skin(const String &p_name);
	Ref<SpineEventData> find_event(const String &p_name);
	Array get_animations();
	Array get_skins();
	Array get_bones();
	Array get_slots();
	Array get_events();
	PackedStringArray get_animation_names();
	PackedStringArray get_skin_names();
	PackedStringArray get_unsupported_features();
	float get_x() { auto d = get_data(); return d ? d->x : 0; }
	float get_y() { auto d = get_data(); return d ? d->y : 0; }
	float get_width() { auto d = get_data(); return d ? d->width : 0; }
	float get_height() { auto d = get_data(); return d ? d->height : 0; }
	float get_fps() { auto d = get_data(); return d ? d->fps : 0; }
	String get_version() { auto d = get_data(); return d ? String::utf8(d->version.c_str()) : String(); }
};

// --- Plain data views (hold the skeleton data alive) -------------------------------------------------------

class SpineAnimation : public RefCounted {
	GDCLASS(SpineAnimation, RefCounted);

protected:
	static void _bind_methods();

public:
	std::shared_ptr<skel::SkeletonData> owner;
	const skel::Animation *animation = nullptr;
	String get_name() const;
	float get_duration() const { return animation ? animation->duration : 0; }
};

class SpineBoneData : public RefCounted {
	GDCLASS(SpineBoneData, RefCounted);

protected:
	static void _bind_methods();

public:
	std::shared_ptr<skel::SkeletonData> owner;
	const skel::BoneData *bone = nullptr;
	String get_name() const;
	int get_index() const { return bone ? bone->index : -1; }
	Ref<SpineBoneData> get_parent() const;
	float get_length() const { return bone ? bone->length : 0; }
	float get_x() const { return bone ? bone->x : 0; }
	float get_y() const { return bone ? bone->y : 0; }
	float get_rotation() const { return bone ? bone->rotation : 0; }
	float get_scale_x() const { return bone ? bone->scale_x : 1; }
	float get_scale_y() const { return bone ? bone->scale_y : 1; }
	float get_shear_x() const { return bone ? bone->shear_x : 0; }
	float get_shear_y() const { return bone ? bone->shear_y : 0; }
};

class SpineSlotData : public RefCounted {
	GDCLASS(SpineSlotData, RefCounted);

protected:
	static void _bind_methods();

public:
	std::shared_ptr<skel::SkeletonData> owner;
	const skel::SlotData *slot = nullptr;
	String get_name() const;
	int get_index() const { return slot ? slot->index : -1; }
	Ref<SpineBoneData> get_bone_data() const;
	String get_attachment_name() const;
	Color get_color() const;
};

class SpineSkin : public RefCounted {
	GDCLASS(SpineSkin, RefCounted);

protected:
	static void _bind_methods();

public:
	std::shared_ptr<skel::SkeletonData> owner;
	const skel::Skin *skin = nullptr;
	String name; // for a skin made with new_skin, which the core cannot hold yet
	Vector<Ref<SpineSkin>> added;
	String get_name() const;
	void add_skin(const Ref<SpineSkin> &p_other);
	Array get_attachments() const;
};

class SpineEventData : public RefCounted {
	GDCLASS(SpineEventData, RefCounted);

protected:
	static void _bind_methods();

public:
	std::shared_ptr<skel::SkeletonData> owner;
	const skel::EventData *event = nullptr;
	String get_event_name() const;
	int get_int_value() const { return event ? event->int_value : 0; }
	float get_float_value() const { return event ? event->float_value : 0; }
	String get_string_value() const;
	String get_audio_path() const;
	float get_volume() const { return event ? event->volume : 1; }
	float get_balance() const { return event ? event->balance : 0; }
};

class SpineEvent : public RefCounted {
	GDCLASS(SpineEvent, RefCounted);

protected:
	static void _bind_methods();

public:
	std::shared_ptr<skel::SkeletonData> owner;
	skel::Event event;
	Ref<SpineEventData> get_data() const;
	String get_event_name() const;
	float get_time() const { return event.time; }
	int get_int_value() const { return event.int_value; }
	float get_float_value() const { return event.float_value; }
	String get_string_value() const;
	float get_volume() const { return event.volume; }
	float get_balance() const { return event.balance; }
};

// --- Live views on one sprite -------------------------------------------------------------------------------

class SpineTrackEntry : public RefCounted {
	GDCLASS(SpineTrackEntry, RefCounted);

protected:
	static void _bind_methods();

public:
	std::shared_ptr<skel::SkeletonData> owner;
	std::shared_ptr<skel::TrackEntry> entry;
	int track = 0;
	bool reverse = false;
	bool holdPrevious = false;

	int get_track_index() const { return track; }
	Ref<SpineAnimation> get_animation() const;
	bool get_loop() const { return entry && entry->loop; }
	void set_loop(bool p) { if (entry) { entry->loop = p; } }
	float get_track_time() const { return entry ? entry->track_time : 0; }
	void set_track_time(float p);
	float get_animation_time() const { return entry ? entry->animation_time() : 0; }
	float get_animation_start() const { return 0; }
	float get_animation_end() const { return entry && entry->animation ? entry->animation->duration : 0; }
	float get_track_complete() const;
	bool is_complete() const;
	float get_time_scale() const { return entry ? entry->time_scale : 1; }
	void set_time_scale(float p) { if (entry) { entry->time_scale = p; } }
	float get_alpha() const { return entry ? entry->alpha : 1; }
	void set_alpha(float p) { if (entry) { entry->alpha = p; } }
	float get_delay() const { return entry ? entry->delay : 0; }
	void set_delay(float p) { if (entry) { entry->delay = p; } }
	float get_mix_duration() const { return entry ? entry->mix_duration : 0; }
	void set_mix_duration(float p) { if (entry) { entry->mix_duration = p; } }
	float get_mix_time() const { return entry ? entry->mix_time : 0; }
	void set_reverse(bool p) { reverse = p; }
	bool get_reverse() const { return reverse; }
	void set_hold_previous(bool p) { holdPrevious = p; }
	bool get_hold_previous() const { return holdPrevious; }
	Ref<SpineTrackEntry> get_next() const;
	Ref<SpineTrackEntry> get_mixing_from() const;
};

class SpineAnimationState : public RefCounted {
	GDCLASS(SpineAnimationState, RefCounted);

protected:
	static void _bind_methods();

public:
	ObjectID sprite;
	SpineSprite *get_sprite() const;

	Ref<SpineTrackEntry> set_animation(const String &p_name, bool p_loop = true, int p_track = 0);
	Ref<SpineTrackEntry> add_animation(const String &p_name, float p_delay = 0, bool p_loop = true, int p_track = 0);
	Ref<SpineTrackEntry> set_empty_animation(int p_track, float p_mix_duration);
	Ref<SpineTrackEntry> add_empty_animation(int p_track, float p_mix_duration, float p_delay);
	void set_empty_animations(float p_mix_duration);
	void clear_track(int p_track);
	void clear_tracks();
	Ref<SpineTrackEntry> get_current(int p_track);
	Array get_tracks();
	void update(float p_delta);
	bool apply(const Variant &p_skeleton);
	float get_time_scale() const;
	void set_time_scale(float p);
};

class SpineBone : public RefCounted {
	GDCLASS(SpineBone, RefCounted);

protected:
	static void _bind_methods();

public:
	ObjectID sprite;
	int index = -1;
	skel::Bone *bone() const;
	SpineSprite *get_sprite() const;

	Ref<SpineBoneData> get_data() const;
	String get_name() const;
	Ref<SpineBone> get_parent() const;
	float get_x() const;
	void set_x(float p);
	float get_y() const;
	void set_y(float p);
	float get_rotation() const;
	void set_rotation(float p);
	float get_scale_x() const;
	void set_scale_x(float p);
	float get_scale_y() const;
	void set_scale_y(float p);
	float get_world_x() const;
	float get_world_y() const;
	float get_world_rotation_x() const;
	float get_world_scale_x() const;
	float get_world_scale_y() const;
	bool is_active() const;
	void set_to_setup_pose();
	Transform2D get_transform() const; // local, skeleton space
	Transform2D get_global_transform() const; // canvas
	void set_global_transform(const Transform2D &p);
};

class SpineSlot : public RefCounted {
	GDCLASS(SpineSlot, RefCounted);

protected:
	static void _bind_methods();

public:
	ObjectID sprite;
	int index = -1;
	skel::Slot *slot() const;
	SpineSprite *get_sprite() const;

	Ref<SpineSlotData> get_data() const;
	String get_name() const;
	Ref<SpineBone> get_bone() const;
	Color get_color() const;
	void set_color(const Color &p);
	String get_attachment_name() const;
	bool set_attachment_name(const String &p_name);
	void set_to_setup_pose();
	int get_sequence_index() const;
	void set_sequence_index(int p);
};

class SpineSkeleton : public RefCounted {
	GDCLASS(SpineSkeleton, RefCounted);

protected:
	static void _bind_methods();

public:
	ObjectID sprite;
	SpineSprite *get_sprite() const;

	Ref<SpineSkeletonDataResource> get_data() const;
	Ref<SpineBone> find_bone(const String &p_name) const;
	Ref<SpineSlot> find_slot(const String &p_name) const;
	Ref<SpineBone> get_root_bone() const;
	Array get_bones() const;
	Array get_slots() const;
	Array get_draw_order() const;
	bool set_skin_by_name(const String &p_name);
	void set_skin(const Ref<SpineSkin> &p_skin);
	Ref<SpineSkin> get_skin() const;
	void set_to_setup_pose();
	void set_bones_to_setup_pose();
	void set_slots_to_setup_pose();
	bool set_attachment(const String &p_slot, const String &p_attachment);
	void update_world_transform();
	Color get_color() const;
	void set_color(const Color &p);
	float get_x() const;
	void set_x(float p);
	float get_y() const;
	void set_y(float p);
	float get_scale_x() const;
	void set_scale_x(float p);
	float get_scale_y() const;
	void set_scale_y(float p);
	Rect2 get_bounds() const;
};

// --- Nodes ----------------------------------------------------------------------------------------------------

class SpineSprite : public Node2D {
	GDCLASS(SpineSprite, Node2D);

public:
	enum UpdateMode { UPDATE_MODE_PROCESS, UPDATE_MODE_PHYSICS, UPDATE_MODE_MANUAL };

private:
	Ref<SpineSkeletonDataResource> skeleton_data_res;
	UpdateMode update_mode = UPDATE_MODE_PROCESS;
	String preview_skin = "default";
	String preview_animation;
	bool preview_frame = false;
	float preview_time = 0;
	float time_scale = 1;
	HashMap<StringName, Variant> extra; // properties the plugin's editor saves that we only keep

	std::shared_ptr<skel::SkeletonData> data;
	std::shared_ptr<skel::AnimationStateData> state_data; // kept alive while [state] points at it
	std::unique_ptr<skel::AnimationState> state;
	std::unique_ptr<skel::Skeleton> skeleton;
	Ref<SpineAnimationState> state_view;
	Ref<SpineSkeleton> skeleton_view;
	Vector<Ref<SpineBone>> bone_views;
	Vector<Ref<SpineSlot>> slot_views;
	HashMap<const skel::TrackEntry *, Ref<SpineTrackEntry>> entry_views;
	Vector<Ref<SpineSkin>> custom_skins;

	void _data_changed();
	void _rebuild();
	void _draw_skeleton();
	void _emit_pending();
	void _follow_children();

protected:
	static void _bind_methods();
	void _notification(int p_what);
	bool _set(const StringName &p_name, const Variant &p_value);
	bool _get(const StringName &p_name, Variant &r_ret) const;

public:
	void set_skeleton_data_res(const Ref<SpineSkeletonDataResource> &p);
	Ref<SpineSkeletonDataResource> get_skeleton_data_res() const { return skeleton_data_res; }
	void set_update_mode(UpdateMode p);
	UpdateMode get_update_mode() const { return update_mode; }
	void set_preview_skin(const String &p);
	String get_preview_skin() const { return preview_skin; }
	void set_preview_animation(const String &p) { preview_animation = p; }
	String get_preview_animation() const { return preview_animation; }
	void set_preview_frame(bool p) { preview_frame = p; }
	bool get_preview_frame() const { return preview_frame; }
	void set_preview_time(float p) { preview_time = p; }
	float get_preview_time() const { return preview_time; }
	void set_time_scale(float p) { time_scale = p; }
	float get_time_scale() const { return time_scale; }

	Ref<SpineSkeleton> get_skeleton();
	Ref<SpineAnimationState> get_animation_state();
	void update_skeleton(float p_delta);
	Transform2D get_global_bone_transform(const String &p_bone);
	void set_global_bone_transform(const String &p_bone, const Transform2D &p_transform);
	Ref<SpineSkin> new_skin(const String &p_name);
	PackedStringArray get_unsupported_features() const;

	// For the views.
	skel::Skeleton *core_skeleton() { return skeleton.get(); }
	skel::AnimationState *core_state() { return state.get(); }
	const std::shared_ptr<skel::SkeletonData> &core_data() const { return data; }
	Ref<SpineTrackEntry> entry_view(const std::shared_ptr<skel::TrackEntry> &p_entry, int p_track);
	Ref<SpineBone> bone_view(int p_index);
	Ref<SpineSlot> slot_view(int p_index);
	Transform2D bone_canvas_transform(int p_index) const; // in this node's space
};

VARIANT_ENUM_CAST(SpineSprite::UpdateMode);

class SpineBoneNode : public Node2D {
	GDCLASS(SpineBoneNode, Node2D);

public:
	enum BoneMode { BONE_MODE_FOLLOW, BONE_MODE_DRIVE };

private:
	String bone_name;
	BoneMode bone_mode = BONE_MODE_FOLLOW;
	bool enabled = true;

protected:
	static void _bind_methods();

public:
	void set_bone_name(const String &p) { bone_name = p; }
	String get_bone_name() const { return bone_name; }
	void set_bone_mode(BoneMode p) { bone_mode = p; }
	BoneMode get_bone_mode() const { return bone_mode; }
	void set_enabled(bool p) { enabled = p; }
	bool get_enabled() const { return enabled; }
	void follow(SpineSprite *p_sprite);
	void drive(SpineSprite *p_sprite);
};

VARIANT_ENUM_CAST(SpineBoneNode::BoneMode);

class SpineSlotNode : public Node2D {
	GDCLASS(SpineSlotNode, Node2D);

	String slot_name;
	Ref<Material> normal_material, additive_material, multiply_material, screen_material;

protected:
	static void _bind_methods();

public:
	void set_slot_name(const String &p) { slot_name = p; }
	String get_slot_name() const { return slot_name; }
	void set_normal_material(const Ref<Material> &p) { normal_material = p; }
	Ref<Material> get_normal_material() const { return normal_material; }
	void set_additive_material(const Ref<Material> &p) { additive_material = p; }
	Ref<Material> get_additive_material() const { return additive_material; }
	void set_multiply_material(const Ref<Material> &p) { multiply_material = p; }
	Ref<Material> get_multiply_material() const { return multiply_material; }
	void set_screen_material(const Ref<Material> &p) { screen_material = p; }
	Ref<Material> get_screen_material() const { return screen_material; }
	void follow(SpineSprite *p_sprite);
};

// The plugin draws through child meshes it makes itself; the sprite here draws directly, so a saved
// mesh child is kept only so the scene loads.
class SpineMesh2D : public Node2D {
	GDCLASS(SpineMesh2D, Node2D);

protected:
	static void _bind_methods() {}
};

// --- Loaders for the imported files (.spskel: the skeleton file's bytes; .spatlas: JSON) ---------------------

class SpineResourceLoader : public ResourceFormatLoader {
	GDCLASS(SpineResourceLoader, ResourceFormatLoader);

public:
	Ref<Resource> load(const String &p_path, const String &p_original_path = "", Error *r_error = nullptr,
			bool p_use_sub_threads = false, float *r_progress = nullptr, CacheMode p_cache_mode = CACHE_MODE_REUSE) override;
	void get_recognized_extensions(List<String> *p_extensions) const override;
	bool handles_type(const String &p_type) const override;
	String get_resource_type(const String &p_path) const override;
};
