// Clean-room code for DroidDeck/Kestrel: written from scratch from public format notes and first principles.
// No third-party skeletal-animation runtime source was consulted.
//
// A 2D skeletal animation core: setup data, a posed skeleton, keyframed timelines and a mixing
// animation state. It has no engine dependency so it can be unit tested on the host; the Godot
// classes in the module wrap it. Coordinates are y-up; the Godot side flips y.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace skel {

struct Color {
	float r = 1, g = 1, b = 1, a = 1;
};

enum class Inherit { Normal, OnlyTranslation, NoRotationOrReflection, NoScale, NoScaleOrReflection };
enum class BlendMode { Normal, Additive, Multiply, Screen };

struct BoneData {
	std::string name;
	int index = 0;
	int parent = -1;
	float length = 0, x = 0, y = 0, rotation = 0, scale_x = 1, scale_y = 1, shear_x = 0, shear_y = 0;
	Inherit inherit = Inherit::Normal;
	bool skin_required = false;
};

struct SlotData {
	std::string name;
	int index = 0;
	int bone = 0;
	Color color;
	bool has_dark = false;
	Color dark{ 0, 0, 0, 1 };
	std::string attachment; // setup-pose attachment key, empty for none
	BlendMode blend = BlendMode::Normal;
};

// Attachment frames that come from numbered images (path + zero-padded index).
enum class SequenceMode { Hold, Once, Loop, PingPong, OnceReverse, LoopReverse, PingPongReverse };
struct Sequence {
	int count = 0, start = 0, digits = 0, setup = 0;
	std::string frame_path(const std::string &base, int index) const;
};

enum class AttachmentType { Region, Mesh, BoundingBox, Path, Point, Clipping };

struct Attachment {
	AttachmentType type = AttachmentType::Region;
	std::string name; // key inside the skin
	std::string path; // texture lookup name (for a sequence, the setup frame's image)
	Color color;
	bool has_sequence = false;
	Sequence sequence;
	std::string sequence_base; // path without the frame number

	// Region: placement inside the bone, and the 4 corner offsets computed from it.
	float x = 0, y = 0, rotation = 0, scale_x = 1, scale_y = 1, width = 0, height = 0;
	float offset[8] = {};

	// Vertex attachments (mesh, bounding box, path, clipping).
	// Unweighted: vertices = x,y per vertex, bones empty.
	// Weighted: bones = per vertex [count, bone index * count]; vertices = [x, y, weight] per influence.
	std::vector<int> bones;
	std::vector<float> vertices;
	int vertex_count = 0;
	std::vector<float> uvs; // region uvs for a mesh, 0..1 within its atlas region
	std::vector<uint16_t> triangles;
	int hull = 0;
	// Deform keys target this attachment (itself, or a linked mesh's parent).
	const Attachment *timeline_attachment = this;

	// Point.
	float point_rotation = 0;
	// Clipping: slot index where clipping ends.
	int end_slot = -1;
	// Path.
	bool closed = false, constant_speed = true;
	std::vector<float> lengths;

	// Filled by whoever resolves textures (atlas region uvs and the page it lives on).
	int page = -1;
	float region_uv[8] = { 0, 1, 1, 1, 1, 0, 0, 0 }; // bottom-left, bottom-right, top-right, top-left
	bool region_rotated = false;
	float region_u = 0, region_v = 0, region_u2 = 1, region_v2 = 1;
	float trim[4] = { 0, 0, 1, 1 }; // region: packed pixels within the original image (left, bottom, right, top; 0..1)
	std::vector<float> page_uvs; // mesh: uvs mapped into the atlas page
	// A sequence's frames, each bound to its own atlas region by bind_atlas (empty until then).
	std::vector<Attachment> sequence_frames;

	bool weighted() const { return !bones.empty(); }
	// The attachment to draw for a sequence frame (-1 = the setup frame, which is this one).
	const Attachment *frame(int index) const {
		return index < 0 || sequence_frames.empty() ? this : &sequence_frames[size_t(index) % sequence_frames.size()];
	}
	void update_region_offsets();
};

struct Skin {
	std::string name;
	// One map per slot index: attachment key -> attachment.
	std::vector<std::unordered_map<std::string, std::unique_ptr<Attachment>>> slots;
	std::vector<int> bones; // bones only active while this skin is on (skin_required bones)

	Attachment *find(int slot, const std::string &key) const;
};

struct EventData {
	std::string name;
	int int_value = 0;
	float float_value = 0;
	std::string string_value, audio;
	float volume = 1, balance = 0;
};

struct Event {
	const EventData *data = nullptr;
	float time = 0;
	int int_value = 0;
	float float_value = 0;
	std::string string_value;
	float volume = 1, balance = 0;
};

struct IkData {
	std::string name;
	int order = 0;
	std::vector<int> bones;
	int target = 0;
	float mix = 1, softness = 0;
	bool bend_positive = true, compress = false, stretch = false, uniform = false;
};

// Moves bones toward a target bone's transform. World mode works on world matrices; local mode on
// local values. Offsets are captured relative to the target so the setup pose is unchanged.
struct TransformData {
	std::string name;
	int order = 0;
	std::vector<int> bones;
	int target = 0;
	bool local = false, relative = false;
	float offset_rotation = 0, offset_x = 0, offset_y = 0, offset_scale_x = 0, offset_scale_y = 0, offset_shear_y = 0;
	float mix_rotate = 0, mix_x = 0, mix_y = 0, mix_scale_x = 0, mix_scale_y = 0, mix_shear_y = 0;
};

// Moves bones along a path attachment in the target slot. Modes: position fixed 0 / percent 1;
// spacing length 0 / fixed 1 / percent 2 / proportional 3; rotate tangent 0 / chain 1 / chain scale 2.
struct PathData {
	std::string name;
	int order = 0;
	bool skin_required = false;
	std::vector<int> bones;
	int target = 0; // slot
	int position_mode = 1, spacing_mode = 0, rotate_mode = 0;
	float offset_rotation = 0, position = 0, spacing = 0, mix_rotate = 1, mix_x = 1, mix_y = 1;
};

// Spring physics on one bone's local transform. mass_inverse is stored as read (1 / mass).
struct PhysicsData {
	std::string name;
	int order = 0;
	bool skin_required = false;
	int bone = 0;
	float x = 0, y = 0, rotate = 0, scale_x = 0, shear_x = 0, limit = 5000;
	float fps = 60, inertia = 1, strength = 100, damping = 1, mass_inverse = 1, wind = 0, gravity = 0, mix = 1;
	uint8_t global_flags = 0; // which values follow the skeleton-wide physics timelines
};

// Keyframes with per-channel values and per-segment curves. Bezier controls are stored in absolute
// time/value space so loaders for different format versions can share one evaluator.
struct Curve {
	enum Kind : uint8_t { LINEAR, STEPPED, BEZIER };
	int channels = 1;
	std::vector<float> frames; // stride 1 + channels: time, values...
	std::vector<uint8_t> kinds; // per frame (segment from this frame to the next)
	std::vector<float> beziers; // per frame, channels * 4 (cx1, cy1, cx2, cy2); unused unless BEZIER

	int frame_count() const { return channels ? int(frames.size()) / (1 + channels) : 0; }
	float time(int frame) const { return frames[frame * (1 + channels)]; }
	float value(int frame, int channel) const { return frames[frame * (1 + channels) + 1 + channel]; }
	void add(float t, const float *values, Kind kind);
	void set_bezier(int frame, int channel, float cx1, float cy1, float cx2, float cy2);
	// Index of the last frame with time <= t (frames[0] if before the first).
	int search(float t) const;
	// Value of a channel at time t; t before the first key returns the first key's value.
	float evaluate(float t, int channel) const;
};

enum class TimelineType {
	Rotate, Translate, TranslateX, TranslateY, Scale, ScaleX, ScaleY, Shear, ShearX, ShearY,
	Attachment, RGBA, RGB, Alpha, RGBA2, RGB2, Deform, DrawOrder, Event, Ik, Inherit, Transform, Sequence,
	PathPosition, PathSpacing, PathMix
};

struct Timeline {
	TimelineType type = TimelineType::Rotate;
	int target = 0; // bone, slot or constraint index
	Curve curve;
	std::vector<std::string> names; // attachment timeline: key per frame ("" for none)
	const Attachment *attachment = nullptr; // deform target
	std::vector<std::vector<float>> deforms; // deform: per frame, offsets for every vertex value
	std::vector<std::vector<int>> draw_orders; // per frame, slot index per draw position; empty = setup
	std::vector<Event> events;
	std::vector<int> sequence_keys; // sequence: per frame, start index << 4 | SequenceMode
	std::vector<float> sequence_delays; // sequence: per frame, seconds per image
	std::vector<uint8_t> flags; // ik: bend_positive, compress, stretch per frame (bit 0, 1, 2); inherit: mode per frame
};

struct Animation {
	std::string name;
	float duration = 0;
	std::vector<Timeline> timelines;
};

struct SkeletonData {
	std::string version; // e.g. "4.2.43", or "" when unknown
	std::string hash;
	float x = 0, y = 0, width = 0, height = 0, fps = 30;
	std::vector<BoneData> bones;
	std::vector<SlotData> slots;
	std::vector<std::unique_ptr<Skin>> skins;
	const Skin *default_skin = nullptr;
	std::vector<EventData> events;
	std::vector<IkData> ik;
	std::vector<TransformData> transforms;
	std::vector<PathData> paths;
	std::vector<PhysicsData> physics;
	std::vector<Animation> animations;
	std::vector<std::string> unsupported; // features in the file this core ignores, for logging

	int find_bone(const std::string &name) const;
	int find_slot(const std::string &name) const;
	const Skin *find_skin(const std::string &name) const;
	const Animation *find_animation(const std::string &name) const;
	const EventData *find_event(const std::string &name) const;
	int find_ik(const std::string &name) const;
	int find_transform(const std::string &name) const;
	int major_version() const;
};

struct Bone {
	const BoneData *data = nullptr;
	float x = 0, y = 0, rotation = 0, scale_x = 1, scale_y = 1, shear_x = 0, shear_y = 0;
	Inherit inherit = Inherit::Normal;
	float a = 1, b = 0, c = 0, d = 1, world_x = 0, world_y = 0; // world transform
	bool active = true;

	void setup();
	// World position of a point in this bone's space.
	void local_to_world(float lx, float ly, float &wx, float &wy) const {
		wx = a * lx + b * ly + world_x;
		wy = c * lx + d * ly + world_y;
	}
	float world_rotation_x() const;
	float world_scale_x() const;
	float world_scale_y() const;
};

struct Slot {
	const SlotData *data = nullptr;
	Color color;
	Color dark;
	const Attachment *attachment = nullptr;
	std::vector<float> deform; // per vertex value offsets; empty when none
	int sequence_index = -1; // frame of a sequence attachment; -1 = its setup frame
	void setup(const struct Skeleton &skeleton);
	const Attachment *drawn() const { return attachment ? attachment->frame(sequence_index) : nullptr; }
};

struct IkConstraint {
	const IkData *data = nullptr;
	float mix = 1, softness = 0;
	bool bend_positive = true, compress = false, stretch = false;
};

struct TransformConstraint {
	const TransformData *data = nullptr;
	float mix_rotate = 0, mix_x = 0, mix_y = 0, mix_scale_x = 0, mix_scale_y = 0, mix_shear_y = 0;
};

struct Skeleton {
	const SkeletonData *data = nullptr;
	std::vector<Bone> bones;
	std::vector<Slot> slots;
	std::vector<int> draw_order; // slot indices, back to front
	std::vector<IkConstraint> ik;
	std::vector<TransformConstraint> transforms;
	std::vector<std::pair<bool, int>> constraint_order; // (is transform, index), by data order
	const Skin *skin = nullptr;
	Color color;
	float x = 0, y = 0, scale_x = 1, scale_y = 1;

	explicit Skeleton(const SkeletonData *data);
	void setup_pose();
	void setup_bones();
	void setup_slots();
	bool set_skin(const std::string &name); // also re-applies setup attachments that the skin provides
	const Attachment *attachment(int slot, const std::string &key) const;
	bool set_attachment(const std::string &slot, const std::string &key);
	void update_world_transform();

	// World-space vertices for a slot's current attachment (x,y pairs). Returns vertex count.
	int world_vertices(int slot, std::vector<float> &out) const;

private:
	void update_bone(Bone &bone);
	void apply_ik(IkConstraint &ik);
	void apply_transform(TransformConstraint &t);
	void set_world(Bone &bone, float a, float b, float c, float d, float wx, float wy);
	void update_after(const std::vector<int> &constrained);
	std::vector<Bone> pose_; // local values before constraints, restored after the world transform
};

enum class MixBlend {
	Replace, // value = current + (key - current) * alpha
	Add, // value = current + (key - setup) * alpha
};

// Applies an animation at `time`, firing events crossed since `last_time` (both already wrapped for loops).
void apply_animation(const Animation &animation, Skeleton &skeleton, float last_time, float time, bool loop,
		std::vector<Event> *events, float alpha, MixBlend blend, bool discrete = true);

struct TrackEntry {
	const Animation *animation = nullptr;
	bool loop = true;
	float time_scale = 1;
	float track_time = 0, last_time = -1;
	float mix_time = 0, mix_duration = 0;
	float alpha = 1;
	float delay = 0;
	bool complete_fired = false;
	std::shared_ptr<TrackEntry> from;
	std::shared_ptr<TrackEntry> next; // queued with add_animation
	float animation_time() const;
};

struct AnimationStateData {
	const SkeletonData *skeleton = nullptr;
	float default_mix = 0;
	std::unordered_map<std::string, float> mixes; // "from\nto" -> duration
	float mix(const Animation *from, const Animation *to) const;
	void set_mix(const std::string &from, const std::string &to, float duration);
};

struct StateEvent {
	enum Kind { Start, Interrupt, Complete, End, Fire } kind = Fire;
	int track = 0;
	const Animation *animation = nullptr;
	Event event;
	std::shared_ptr<TrackEntry> entry; // the entry the event is about
};

struct AnimationState {
	const AnimationStateData *data = nullptr;
	std::vector<std::shared_ptr<TrackEntry>> tracks;
	float time_scale = 1;
	bool reset_to_setup = true; // pose starts from setup each apply, so keys never leak between animations
	std::vector<StateEvent> pending;

	explicit AnimationState(const AnimationStateData *data) : data(data) {}
	std::shared_ptr<TrackEntry> set_animation(int track, const std::string &name, bool loop);
	std::shared_ptr<TrackEntry> add_animation(int track, const std::string &name, bool loop, float delay);
	std::shared_ptr<TrackEntry> set_empty(int track, float mix_duration);
	void clear_track(int track);
	void clear_tracks();
	std::shared_ptr<TrackEntry> current(int track) const;
	void update(float dt);
	void apply(Skeleton &skeleton);

private:
	std::shared_ptr<TrackEntry> start(int track, const Animation *animation, bool loop);
};

// Loads skeleton JSON (3.x and 4.x naming). Returns nullptr and fills error on failure.
std::unique_ptr<SkeletonData> load_json(const char *text, size_t length, float scale, std::string &error);

// Loads a 4.2 binary skeleton. Returns nullptr and fills error on failure, including for features
// whose binary layout is not known yet (path and physics constraints).
std::unique_ptr<SkeletonData> load_binary(const uint8_t *data, size_t length, float scale, std::string &error);

} // namespace skel
