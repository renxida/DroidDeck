// Clean-room code for DroidDeck/Kestrel: written from scratch; no third-party runtime source consulted.
// A Node2D that loads a skeleton (JSON + text atlas), plays animations and draws itself.
// Class and method names here are our own; compatibility names the game expects get thin
// wrappers once the on-device inventory says what they are.
#pragma once

#include "skel/atlas.h"
#include "skel/skel.h"

#include "scene/2d/node_2d.h"
#include "scene/resources/texture.h"

#include <memory>

class NativeSkeleton2D : public Node2D {
	GDCLASS(NativeSkeleton2D, Node2D);

	String skeleton_path;
	String atlas_path;
	String animation;
	String skin;
	bool loop = true;
	bool playing = true;
	float time_scale = 1;
	float skeleton_scale = 1;
	float default_mix = 0.1f;

	std::unique_ptr<skel::SkeletonData> data;
	std::unique_ptr<skel::AnimationStateData> state_data;
	std::unique_ptr<skel::AnimationState> state;
	std::unique_ptr<skel::Skeleton> skeleton;
	skel::Atlas atlas;
	Vector<Ref<Texture2D>> pages;
	String load_error;

	void _advance(float delta);
	void _draw_skeleton();
	void _emit_pending();

protected:
	static void _bind_methods();
	void _notification(int p_what);

public:
	Error load_files(const String &p_skeleton, const String &p_atlas);
	String get_load_error() const { return load_error; }

	void set_skeleton_path(const String &p_path);
	String get_skeleton_path() const { return skeleton_path; }
	void set_atlas_path(const String &p_path);
	String get_atlas_path() const { return atlas_path; }
	void set_animation(const String &p_name);
	String get_animation() const { return animation; }
	void set_loop(bool p_loop);
	bool get_loop() const { return loop; }
	void set_playing(bool p_playing);
	bool is_playing() const { return playing; }
	void set_time_scale(float p_scale) { time_scale = p_scale; }
	float get_time_scale() const { return time_scale; }
	void set_skeleton_scale(float p_scale);
	float get_skeleton_scale() const { return skeleton_scale; }
	void set_skin(const String &p_skin);
	String get_skin() const { return skin; }
	void set_default_mix(float p_mix);
	float get_default_mix() const { return default_mix; }

	bool play(int p_track, const String &p_name, bool p_loop);
	bool queue(int p_track, const String &p_name, bool p_loop, float p_delay);
	void clear_track(int p_track);
	void set_mix(const String &p_from, const String &p_to, float p_duration);
	void advance(float p_delta); // step and redraw without waiting for a frame (tests, offline)

	PackedStringArray get_animation_names() const;
	PackedStringArray get_skin_names() const;
	PackedStringArray get_unsupported_features() const;
	float get_animation_duration(const String &p_name) const;
	Vector2 get_bone_position(const String &p_bone) const;
	float get_bone_rotation(const String &p_bone) const;
	String get_slot_attachment(const String &p_slot) const;
	bool set_slot_attachment(const String &p_slot, const String &p_attachment);
	Rect2 get_bounds() const;
};
