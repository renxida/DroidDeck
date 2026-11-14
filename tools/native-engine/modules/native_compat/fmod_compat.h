// Clean-room code for DroidDeck/Kestrel. Classes under the names of the Godot FMOD plugin the game ships
// (its Windows library has no linux-arm64 build), so the game's audio code finds them on our engine.
// Written from the plugin's public API reference (method and property names only) and the game's own
// bank files; no FMOD or plugin code. Starting an event plays the sounds its bank metadata names (see
// fsb/event_table.h) through Godot audio players; parameters, buses and VCAs are tracked but not mixed yet.
#pragma once

#include "native_sound_bank.h"

#include "core/object/object.h"
#include "core/object/ref_counted.h"
#include "core/templates/hash_map.h"
#include "scene/2d/node_2d.h"
#include "scene/3d/node_3d.h"

class FmodEventDescription;

class FmodParameterDescription : public RefCounted {
	GDCLASS(FmodParameterDescription, RefCounted);

protected:
	static void _bind_methods();

public:
	String name;
	int64_t id = 0;
	float minimum = 0, maximum = 1, default_value = 0;
	bool global = false;

	String get_name() const { return name; }
	int64_t get_id() const { return id; }
	float get_minimum() const { return minimum; }
	float get_maximum() const { return maximum; }
	float get_default_value() const { return default_value; }
	bool is_global() const { return global; }
	bool is_discrete() const { return false; }
	bool is_labeled() const { return false; }
	bool is_automatic() const { return false; }
	bool is_read_only() const { return false; }
};

class FmodEvent : public RefCounted {
	GDCLASS(FmodEvent, RefCounted);

protected:
	static void _bind_methods();

public:
	// FMOD Studio's documented playback states and stop modes.
	enum PlaybackState { PLAYING = 0, SUSTAINING = 1, STOPPED = 2, STARTING = 3, STOPPING = 4 };
	enum StopMode { ALLOWFADEOUT = 0, IMMEDIATE = 1 };

	String path;
	String guid;
	int state = STOPPED;
	bool paused = false;
	bool released = false;
	float volume = 1, pitch = 1;
	HashMap<String, float> parameters;
	Transform2D transform_2d;
	Transform3D transform_3d;
	// The audio players this start made, and the level (dB) each was set at before the event volume.
	Vector<ObjectID> players;
	Vector<float> player_db;

	void start();
	void stop(int p_mode);
	void set_paused(bool p_paused);
	bool get_paused() const { return paused; }
	void release() { released = true; }
	void event_key_off() {}
	void set_parameter_by_name(const String &p_name, float p_value) { parameters[p_name] = p_value; }
	float get_parameter_by_name(const String &p_name) const;
	void set_parameter_by_id(int64_t p_id, float p_value) { parameters[itos(p_id)] = p_value; }
	float get_parameter_by_id(int64_t p_id) const { return get_parameter_by_name(itos(p_id)); }
	void set_parameter_by_name_with_label(const String &p_name, const String &p_label, bool p_ignore_seek) {}
	void set_2d_attributes(const Transform2D &p_t) { transform_2d = p_t; }
	void set_3d_attributes(const Transform3D &p_t) { transform_3d = p_t; }
	void set_node_attributes(Node *p_node);
	int get_playback_state();
	bool is_valid() const { return !released; }
	float get_volume() const { return volume; }
	void set_volume(float p_v);
	float get_pitch() const { return pitch; }
	void set_pitch(float p_v);
	int get_timeline_position() const { return 0; }
	void set_timeline_position(int p_ms) {}
	String get_guid() const { return guid; }
	String get_path() const { return path; }
};

class FmodEventDescription : public RefCounted {
	GDCLASS(FmodEventDescription, RefCounted);

protected:
	static void _bind_methods();

public:
	String path;
	String guid;

	Ref<FmodEvent> create_instance();
	int get_instance_count() const;
	Array get_instance_list() const;
	void release_all_instances();
	bool is_3d() const { return false; }
	bool is_one_shot() const { return true; }
	bool is_stream() const { return false; }
	bool is_snapshot() const { return path.begins_with("snapshot:/"); }
	int get_length() const { return 0; }
	Array get_min_max_distance() const;
	int get_parameter_count() const { return 0; }
	Ref<FmodParameterDescription> get_parameter_by_name(const String &p_name) const;
	Array get_parameters() const { return Array(); }
	String get_guid() const { return guid; }
	String get_path() const { return path; }
};

class FmodBus : public RefCounted {
	GDCLASS(FmodBus, RefCounted);

protected:
	static void _bind_methods();

public:
	String path, guid;
	float volume = 1;
	bool mute = false, paused = false;

	float get_volume() const { return volume; }
	void set_volume(float p_v) { volume = p_v; }
	bool get_mute() const { return mute; }
	void set_mute(bool p_m) { mute = p_m; }
	bool get_paused() const { return paused; }
	void set_paused(bool p_p) { paused = p_p; }
	void stop_all_events(int p_mode);
	String get_path() const { return path; }
	String get_guid() const { return guid; }
};

class FmodVCA : public RefCounted {
	GDCLASS(FmodVCA, RefCounted);

protected:
	static void _bind_methods();

public:
	String path, guid;
	float volume = 1;
	float get_volume() const { return volume; }
	void set_volume(float p_v) { volume = p_v; }
	String get_path() const { return path; }
	String get_guid() const { return guid; }
};

class FmodBank : public RefCounted {
	GDCLASS(FmodBank, RefCounted);

protected:
	static void _bind_methods();

public:
	String file;
	String path; // bank:/... when the name table knows it
	Ref<NativeSoundBank> data;

	int get_event_description_count() const;
	Array get_description_list() const;
	int get_bus_count() const;
	Array get_bus_list() const;
	int get_vca_count() const;
	Array get_vca_list() const;
	int get_loading_state() const { return data.is_valid() ? 3 : 0; } // 3: loaded
	int get_sample_loading_state() const { return get_loading_state(); }
	void load_sample_data() {}
	void unload_sample_data() {}
	void update_bank_data() {}
	String get_godot_res_path() const { return file; }
	String get_path() const { return path; }
	int get_sound_count() const { return data.is_valid() ? data->get_sound_count() : 0; }
};

// The FmodServer singleton.
class FmodServer : public Object {
	GDCLASS(FmodServer, Object);
	static FmodServer *singleton;

	HashMap<String, Ref<FmodBank>> banks; // by file
	Vector<Ref<NativeSoundBank>> name_tables;
	HashMap<String, Ref<FmodEventDescription>> events;
	HashMap<String, Ref<FmodBus>> buses;
	HashMap<String, Ref<FmodVCA>> vcas;
	HashMap<String, float> globals;
	Vector<Ref<FmodEvent>> live;
	int64_t one_shots = 0;
	int64_t starts = 0, sounds_started = 0, starts_without_sound = 0, sounds_without_wave = 0;
	uint32_t rng = 0x2545f491u;
	PackedStringArray missing; // event paths asked for that no loaded name table knows (first 64)
	PackedStringArray silent; // events started that no loaded bank gives a sound (first 64)

	String guid_for(const String &p_path) const;
	String path_for(const String &p_guid) const;
	PackedStringArray paths_with_prefix(const String &p_prefix) const;

protected:
	static void _bind_methods();

public:
	static FmodServer *get_singleton() { return singleton; }
	FmodServer();
	~FmodServer();

	void init(const Variant &p_settings);
	void update();
	void shutdown();
	Ref<FmodBank> load_bank(const String &p_path, int p_flag);
	void unload_bank(const String &p_path);
	bool check_event_path(const String &p_path) const { return !guid_for(p_path).is_empty(); }
	bool check_event_guid(const String &p_guid) const { return !path_for(p_guid).is_empty(); }
	bool check_bus_path(const String &p_path) const { return check_event_path(p_path); }
	bool check_vca_path(const String &p_path) const { return check_event_path(p_path); }
	Ref<FmodEventDescription> get_event(const String &p_path);
	Ref<FmodEventDescription> get_event_from_guid(const String &p_guid);
	Ref<FmodEvent> create_event_instance(const String &p_path);
	Ref<FmodEvent> create_event_instance_with_guid(const String &p_guid);
	void play_one_shot(const String &p_path);
	void play_one_shot_with_params(const String &p_path, const Dictionary &p_params);
	void play_one_shot_attached(const String &p_path, Node *p_node);
	void play_one_shot_using_guid(const String &p_guid);
	Ref<FmodBus> get_bus(const String &p_path);
	Ref<FmodVCA> get_vca(const String &p_path);
	Array get_all_banks() const;
	Array get_all_event_descriptions() const;
	Array get_all_buses() const;
	Array get_all_vca() const;
	void set_global_parameter_by_name(const String &p_name, float p_value) { globals[p_name] = p_value; }
	float get_global_parameter_by_name(const String &p_name) const;
	void set_global_parameter_by_name_with_label(const String &p_name, const String &p_label) {}
	void set_global_parameter_by_id(int64_t p_id, float p_value) { globals[itos(p_id)] = p_value; }
	float get_global_parameter_by_id(int64_t p_id) const { return get_global_parameter_by_name(itos(p_id)); }
	void mute_all_events() {}
	void unmute_all_events() {}
	void pause_all_events() {}
	void unpause_all_events() {}
	bool banks_still_loading() const { return false; }
	void wait_for_all_loads() {}
	// Ours, for logs and tests.
	Dictionary get_compat_stats() const;
	friend class FmodEventDescription;
	friend class FmodBank;
	friend class FmodBus;
	int64_t count_instances(const String &p_path) const;
	// Plays the sounds one start of the event (by GUID text) gives, each in its own audio player added to the
	// scene root. Returns how many started; their ids and levels go to r_players and r_db when given.
	int play_event_sounds(const String &p_guid, const String &p_path, float p_volume, float p_pitch,
			Vector<ObjectID> *r_players, Vector<float> *r_db);
};

class FmodEventEmitter2D : public Node2D {
	GDCLASS(FmodEventEmitter2D, Node2D);

protected:
	static void _bind_methods();
	void _notification(int p_what);
	bool _set(const StringName &p_name, const Variant &p_value);
	bool _get(const StringName &p_name, Variant &r_ret) const;

public:
	String event_name, event_guid;
	bool autoplay = false, attached = true, allow_fadeout = true, preload_event = true, auto_release = false;
	float volume = 1;
	Ref<FmodEvent> event;
	HashMap<StringName, Variant> extra;

	void set_event_name(const String &p_v) { event_name = p_v; }
	String get_event_name() const { return event_name; }
	void set_event_guid(const String &p_v) { event_guid = p_v; }
	String get_event_guid() const { return event_guid; }
	void set_autoplay(bool p_v) { autoplay = p_v; }
	bool is_autoplay() const { return autoplay; }
	void set_attached(bool p_v) { attached = p_v; }
	bool is_attached() const { return attached; }
	void set_allow_fadeout(bool p_v) { allow_fadeout = p_v; }
	bool is_allow_fadeout() const { return allow_fadeout; }
	void set_preload_event(bool p_v) { preload_event = p_v; }
	bool is_preload_event() const { return preload_event; }
	void set_auto_release(bool p_v) { auto_release = p_v; }
	bool is_auto_release() const { return auto_release; }
	void set_volume(float p_v);
	float get_volume() const { return volume; }
	void play(bool p_restart_if_playing = true);
	void play_one_shot();
	void stop();
	void set_paused(bool p_p);
	bool is_paused() const { return event.is_valid() && event->paused; }
	bool is_playing() const { return event.is_valid() && event->state == FmodEvent::PLAYING; }
	void set_parameter(const String &p_name, const Variant &p_value);
	Variant get_parameter(const String &p_name) const;
	Ref<FmodEvent> get_event() const { return event; }
};

class FmodEventEmitter3D : public Node3D {
	GDCLASS(FmodEventEmitter3D, Node3D);

protected:
	static void _bind_methods();
	void _notification(int p_what);
	bool _set(const StringName &p_name, const Variant &p_value);
	bool _get(const StringName &p_name, Variant &r_ret) const;

public:
	String event_name, event_guid;
	bool autoplay = false, attached = true, allow_fadeout = true, preload_event = true, auto_release = false;
	float volume = 1;
	Ref<FmodEvent> event;
	HashMap<StringName, Variant> extra;

	void set_event_name(const String &p_v) { event_name = p_v; }
	String get_event_name() const { return event_name; }
	void set_event_guid(const String &p_v) { event_guid = p_v; }
	String get_event_guid() const { return event_guid; }
	void set_autoplay(bool p_v) { autoplay = p_v; }
	bool is_autoplay() const { return autoplay; }
	void set_attached(bool p_v) { attached = p_v; }
	bool is_attached() const { return attached; }
	void set_allow_fadeout(bool p_v) { allow_fadeout = p_v; }
	bool is_allow_fadeout() const { return allow_fadeout; }
	void set_preload_event(bool p_v) { preload_event = p_v; }
	bool is_preload_event() const { return preload_event; }
	void set_auto_release(bool p_v) { auto_release = p_v; }
	bool is_auto_release() const { return auto_release; }
	void set_volume(float p_v);
	float get_volume() const { return volume; }
	void play(bool p_restart_if_playing = true);
	void play_one_shot();
	void stop();
	void set_paused(bool p_p);
	bool is_paused() const { return event.is_valid() && event->paused; }
	bool is_playing() const { return event.is_valid() && event->state == FmodEvent::PLAYING; }
	void set_parameter(const String &p_name, const Variant &p_value);
	Variant get_parameter(const String &p_name) const;
	Ref<FmodEvent> get_event() const { return event; }
};

class FmodListener2D : public Node2D {
	GDCLASS(FmodListener2D, Node2D);

protected:
	static void _bind_methods();

public:
	int listener_index = 0;
	float listener_weight = 1;
	bool locked = false;
	void set_listener_index(int p_v) { listener_index = CLAMP(p_v, 0, 7); }
	int get_listener_index() const { return listener_index; }
	void set_listener_weight(float p_v) { listener_weight = p_v; }
	float get_listener_weight() const { return listener_weight; }
	void set_is_locked(bool p_v) { locked = p_v; }
	bool get_is_locked() const { return locked; }
};

class FmodListener3D : public Node3D {
	GDCLASS(FmodListener3D, Node3D);

protected:
	static void _bind_methods();

public:
	int listener_index = 0;
	float listener_weight = 1;
	bool locked = false;
	void set_listener_index(int p_v) { listener_index = CLAMP(p_v, 0, 7); }
	int get_listener_index() const { return listener_index; }
	void set_listener_weight(float p_v) { listener_weight = p_v; }
	float get_listener_weight() const { return listener_weight; }
	void set_is_locked(bool p_v) { locked = p_v; }
	bool get_is_locked() const { return locked; }
};

class FmodBankLoader : public Node {
	GDCLASS(FmodBankLoader, Node);

protected:
	static void _bind_methods();
	void _notification(int p_what);

public:
	Array bank_paths;
	bool preload_sample_data = false;
	Vector<Ref<FmodBank>> loaded;
	void set_bank_paths(const Array &p_paths) { bank_paths = p_paths; }
	Array get_bank_paths() const { return bank_paths; }
	void set_preload_sample_data(bool p_v) { preload_sample_data = p_v; }
	bool get_preload_sample_data() const { return preload_sample_data; }
};
