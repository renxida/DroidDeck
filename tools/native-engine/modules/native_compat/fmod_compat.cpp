// Clean-room code for DroidDeck/Kestrel; see fmod_compat.h.
#include "fmod_compat.h"

#include "core/config/engine.h"
#include "core/io/file_access.h"
#include "core/math/math_funcs.h"
#include "core/object/object.h"
#include "core/string/print_string.h"
#include "scene/audio/audio_stream_player.h"
#include "scene/main/scene_tree.h"
#include "scene/main/window.h"
#include "scene/scene_string_names.h"

FmodServer *FmodServer::singleton = nullptr;

// --- FmodParameterDescription -------------------------------------------------------------------------

void FmodParameterDescription::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_name"), &FmodParameterDescription::get_name);
	ClassDB::bind_method(D_METHOD("get_id"), &FmodParameterDescription::get_id);
	ClassDB::bind_method(D_METHOD("get_minimum"), &FmodParameterDescription::get_minimum);
	ClassDB::bind_method(D_METHOD("get_maximum"), &FmodParameterDescription::get_maximum);
	ClassDB::bind_method(D_METHOD("get_default_value"), &FmodParameterDescription::get_default_value);
	ClassDB::bind_method(D_METHOD("is_global"), &FmodParameterDescription::is_global);
	ClassDB::bind_method(D_METHOD("is_discrete"), &FmodParameterDescription::is_discrete);
	ClassDB::bind_method(D_METHOD("is_labeled"), &FmodParameterDescription::is_labeled);
	ClassDB::bind_method(D_METHOD("is_automatic"), &FmodParameterDescription::is_automatic);
	ClassDB::bind_method(D_METHOD("is_read_only"), &FmodParameterDescription::is_read_only);
}

// --- FmodEvent ------------------------------------------------------------------------------------------

namespace {

AudioStreamPlayer *player_of(ObjectID p_id) {
	return Object::cast_to<AudioStreamPlayer>(ObjectDB::get_instance(p_id));
}

float level_db(float p_linear) {
	return Math::linear_to_db(MAX(p_linear, 0.00001f));
}

} // namespace

void FmodEvent::start() {
	if (released) {
		return;
	}
	stop(IMMEDIATE); // a restart drops what the last start still plays
	if (FmodServer *s = FmodServer::get_singleton()) {
		s->play_event_sounds(guid, path, volume, pitch, &players, &player_db);
	}
	state = PLAYING;
}

void FmodEvent::stop(int p_mode) {
	for (const ObjectID &id : players) {
		if (AudioStreamPlayer *p = player_of(id)) {
			p->stop();
			p->queue_free();
		}
	}
	players.clear();
	player_db.clear();
	state = STOPPED;
}

void FmodEvent::set_paused(bool p_paused) {
	paused = p_paused;
	for (const ObjectID &id : players) {
		if (AudioStreamPlayer *p = player_of(id)) {
			p->set_stream_paused(p_paused);
		}
	}
}

void FmodEvent::set_volume(float p_v) {
	volume = p_v;
	for (int i = 0; i < players.size(); i++) {
		if (AudioStreamPlayer *p = player_of(players[i])) {
			p->set_volume_db(player_db[i] + level_db(p_v));
		}
	}
}

void FmodEvent::set_pitch(float p_v) {
	pitch = p_v;
	for (const ObjectID &id : players) {
		if (AudioStreamPlayer *p = player_of(id)) {
			p->set_pitch_scale(CLAMP(p_v, 0.01f, 16.0f));
		}
	}
}

// An event that started sounds has stopped once its players have all finished (each frees itself then).
int FmodEvent::get_playback_state() {
	if (state == PLAYING && !players.is_empty()) {
		bool any = false;
		for (const ObjectID &id : players) {
			any = any || player_of(id) != nullptr;
		}
		if (!any) {
			players.clear();
			player_db.clear();
			state = STOPPED;
		}
	}
	return state;
}

float FmodEvent::get_parameter_by_name(const String &p_name) const {
	const float *v = parameters.getptr(p_name);
	return v ? *v : 0.0f;
}

void FmodEvent::set_node_attributes(Node *p_node) {
	if (Node2D *n2 = Object::cast_to<Node2D>(p_node)) {
		transform_2d = n2->get_global_transform();
	} else if (Node3D *n3 = Object::cast_to<Node3D>(p_node)) {
		transform_3d = n3->get_global_transform();
	}
}

void FmodEvent::_bind_methods() {
	ClassDB::bind_method(D_METHOD("start"), &FmodEvent::start);
	ClassDB::bind_method(D_METHOD("stop", "stop_mode"), &FmodEvent::stop, DEFVAL(0));
	ClassDB::bind_method(D_METHOD("set_paused", "paused"), &FmodEvent::set_paused);
	ClassDB::bind_method(D_METHOD("get_paused"), &FmodEvent::get_paused);
	ClassDB::bind_method(D_METHOD("release"), &FmodEvent::release);
	ClassDB::bind_method(D_METHOD("event_key_off"), &FmodEvent::event_key_off);
	ClassDB::bind_method(D_METHOD("set_parameter_by_name", "name", "value"), &FmodEvent::set_parameter_by_name);
	ClassDB::bind_method(D_METHOD("get_parameter_by_name", "name"), &FmodEvent::get_parameter_by_name);
	ClassDB::bind_method(D_METHOD("set_parameter_by_id", "id", "value"), &FmodEvent::set_parameter_by_id);
	ClassDB::bind_method(D_METHOD("get_parameter_by_id", "id"), &FmodEvent::get_parameter_by_id);
	ClassDB::bind_method(D_METHOD("set_parameter_by_name_with_label", "name", "label", "ignore_seek_speed"),
			&FmodEvent::set_parameter_by_name_with_label, DEFVAL(false));
	ClassDB::bind_method(D_METHOD("set_2d_attributes", "transform"), &FmodEvent::set_2d_attributes);
	ClassDB::bind_method(D_METHOD("set_3d_attributes", "transform"), &FmodEvent::set_3d_attributes);
	ClassDB::bind_method(D_METHOD("set_node_attributes", "node"), &FmodEvent::set_node_attributes);
	ClassDB::bind_method(D_METHOD("get_playback_state"), &FmodEvent::get_playback_state);
	ClassDB::bind_method(D_METHOD("is_valid"), &FmodEvent::is_valid);
	ClassDB::bind_method(D_METHOD("get_volume"), &FmodEvent::get_volume);
	ClassDB::bind_method(D_METHOD("set_volume", "volume"), &FmodEvent::set_volume);
	ClassDB::bind_method(D_METHOD("get_pitch"), &FmodEvent::get_pitch);
	ClassDB::bind_method(D_METHOD("set_pitch", "pitch"), &FmodEvent::set_pitch);
	ClassDB::bind_method(D_METHOD("get_timeline_position"), &FmodEvent::get_timeline_position);
	ClassDB::bind_method(D_METHOD("set_timeline_position", "position"), &FmodEvent::set_timeline_position);
	ClassDB::bind_method(D_METHOD("get_guid"), &FmodEvent::get_guid);
	ClassDB::bind_method(D_METHOD("get_path"), &FmodEvent::get_path);
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "volume"), "set_volume", "get_volume");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "pitch"), "set_pitch", "get_pitch");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "paused"), "set_paused", "get_paused");
	BIND_CONSTANT(PLAYING);
	BIND_CONSTANT(SUSTAINING);
	BIND_CONSTANT(STOPPED);
	BIND_CONSTANT(STARTING);
	BIND_CONSTANT(STOPPING);
}

// --- FmodEventDescription -------------------------------------------------------------------------------

Ref<FmodEvent> FmodEventDescription::create_instance() {
	FmodServer *s = FmodServer::get_singleton();
	return s ? s->create_event_instance(path) : Ref<FmodEvent>();
}

int FmodEventDescription::get_instance_count() const {
	FmodServer *s = FmodServer::get_singleton();
	return s ? int(s->count_instances(path)) : 0;
}

Array FmodEventDescription::get_instance_list() const {
	Array out;
	if (FmodServer *s = FmodServer::get_singleton()) {
		for (const Ref<FmodEvent> &e : s->live) {
			if (e->path == path) {
				out.push_back(e);
			}
		}
	}
	return out;
}

void FmodEventDescription::release_all_instances() {
	if (FmodServer *s = FmodServer::get_singleton()) {
		for (const Ref<FmodEvent> &e : s->live) {
			if (e->path == path) {
				e->stop(FmodEvent::IMMEDIATE);
				e->release();
			}
		}
	}
}

Array FmodEventDescription::get_min_max_distance() const {
	Array a;
	a.push_back(1.0);
	a.push_back(20.0);
	return a;
}

Ref<FmodParameterDescription> FmodEventDescription::get_parameter_by_name(const String &p_name) const {
	Ref<FmodParameterDescription> d;
	d.instantiate();
	d->name = p_name;
	return d;
}

void FmodEventDescription::_bind_methods() {
	ClassDB::bind_method(D_METHOD("create_instance"), &FmodEventDescription::create_instance);
	ClassDB::bind_method(D_METHOD("get_instance_count"), &FmodEventDescription::get_instance_count);
	ClassDB::bind_method(D_METHOD("get_instance_list"), &FmodEventDescription::get_instance_list);
	ClassDB::bind_method(D_METHOD("release_all_instances"), &FmodEventDescription::release_all_instances);
	ClassDB::bind_method(D_METHOD("is_3d"), &FmodEventDescription::is_3d);
	ClassDB::bind_method(D_METHOD("is_one_shot"), &FmodEventDescription::is_one_shot);
	ClassDB::bind_method(D_METHOD("is_stream"), &FmodEventDescription::is_stream);
	ClassDB::bind_method(D_METHOD("is_snapshot"), &FmodEventDescription::is_snapshot);
	ClassDB::bind_method(D_METHOD("get_length"), &FmodEventDescription::get_length);
	ClassDB::bind_method(D_METHOD("get_min_max_distance"), &FmodEventDescription::get_min_max_distance);
	ClassDB::bind_method(D_METHOD("get_parameter_count"), &FmodEventDescription::get_parameter_count);
	ClassDB::bind_method(D_METHOD("get_parameter_by_name", "name"), &FmodEventDescription::get_parameter_by_name);
	ClassDB::bind_method(D_METHOD("get_parameters"), &FmodEventDescription::get_parameters);
	ClassDB::bind_method(D_METHOD("get_guid"), &FmodEventDescription::get_guid);
	ClassDB::bind_method(D_METHOD("get_path"), &FmodEventDescription::get_path);
}

// --- FmodBus / FmodVCA ----------------------------------------------------------------------------------

void FmodBus::stop_all_events(int p_mode) {
	if (FmodServer *s = FmodServer::get_singleton()) {
		for (const Ref<FmodEvent> &e : s->live) {
			e->stop(p_mode);
		}
	}
}

void FmodBus::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_volume"), &FmodBus::get_volume);
	ClassDB::bind_method(D_METHOD("set_volume", "volume"), &FmodBus::set_volume);
	ClassDB::bind_method(D_METHOD("get_mute"), &FmodBus::get_mute);
	ClassDB::bind_method(D_METHOD("set_mute", "mute"), &FmodBus::set_mute);
	ClassDB::bind_method(D_METHOD("get_paused"), &FmodBus::get_paused);
	ClassDB::bind_method(D_METHOD("set_paused", "paused"), &FmodBus::set_paused);
	ClassDB::bind_method(D_METHOD("stop_all_events", "stop_mode"), &FmodBus::stop_all_events, DEFVAL(0));
	ClassDB::bind_method(D_METHOD("get_path"), &FmodBus::get_path);
	ClassDB::bind_method(D_METHOD("get_guid"), &FmodBus::get_guid);
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "volume"), "set_volume", "get_volume");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "mute"), "set_mute", "get_mute");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "paused"), "set_paused", "get_paused");
}

void FmodVCA::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_volume"), &FmodVCA::get_volume);
	ClassDB::bind_method(D_METHOD("set_volume", "volume"), &FmodVCA::set_volume);
	ClassDB::bind_method(D_METHOD("get_path"), &FmodVCA::get_path);
	ClassDB::bind_method(D_METHOD("get_guid"), &FmodVCA::get_guid);
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "volume"), "set_volume", "get_volume");
}

// --- FmodBank -------------------------------------------------------------------------------------------
// A bank lists its own events; a strings bank lists every event path it names.

int FmodBank::get_event_description_count() const {
	return get_description_list().size();
}

Array FmodBank::get_description_list() const {
	Array out;
	FmodServer *s = FmodServer::get_singleton();
	if (!s || data.is_null()) {
		return out;
	}
	if (data->get_event_count() > 0) {
		// The bank's own events, by path where a name table knows it.
		for (const auto &kv : data->get_event_table().events) {
			const String g = String(fsb::guid_text(kv.first).c_str());
			const String p = s->path_for(g);
			out.push_back(s->get_event(p.is_empty() ? g : p));
		}
		return out;
	}
	if (data->get_path_count() == 0) {
		return out;
	}
	for (const String &p : data->get_paths_with_prefix("event:/")) {
		out.push_back(s->get_event(p));
	}
	return out;
}

int FmodBank::get_bus_count() const {
	return get_bus_list().size();
}

Array FmodBank::get_bus_list() const {
	Array out;
	FmodServer *s = FmodServer::get_singleton();
	if (s && data.is_valid()) {
		for (const String &p : data->get_paths_with_prefix("bus:/")) {
			out.push_back(s->get_bus(p));
		}
	}
	return out;
}

int FmodBank::get_vca_count() const {
	return get_vca_list().size();
}

Array FmodBank::get_vca_list() const {
	Array out;
	FmodServer *s = FmodServer::get_singleton();
	if (s && data.is_valid()) {
		for (const String &p : data->get_paths_with_prefix("vca:/")) {
			out.push_back(s->get_vca(p));
		}
	}
	return out;
}

void FmodBank::_bind_methods() {
	ClassDB::bind_method(D_METHOD("get_event_description_count"), &FmodBank::get_event_description_count);
	ClassDB::bind_method(D_METHOD("get_description_list"), &FmodBank::get_description_list);
	ClassDB::bind_method(D_METHOD("get_bus_count"), &FmodBank::get_bus_count);
	ClassDB::bind_method(D_METHOD("get_bus_list"), &FmodBank::get_bus_list);
	ClassDB::bind_method(D_METHOD("get_vca_count"), &FmodBank::get_vca_count);
	ClassDB::bind_method(D_METHOD("get_vca_list"), &FmodBank::get_vca_list);
	ClassDB::bind_method(D_METHOD("get_loading_state"), &FmodBank::get_loading_state);
	ClassDB::bind_method(D_METHOD("get_sample_loading_state"), &FmodBank::get_sample_loading_state);
	ClassDB::bind_method(D_METHOD("load_sample_data"), &FmodBank::load_sample_data);
	ClassDB::bind_method(D_METHOD("unload_sample_data"), &FmodBank::unload_sample_data);
	ClassDB::bind_method(D_METHOD("update_bank_data"), &FmodBank::update_bank_data);
	ClassDB::bind_method(D_METHOD("get_godot_res_path"), &FmodBank::get_godot_res_path);
	ClassDB::bind_method(D_METHOD("get_path"), &FmodBank::get_path);
	ClassDB::bind_method(D_METHOD("get_sound_count"), &FmodBank::get_sound_count);
}

// --- FmodServer -----------------------------------------------------------------------------------------

FmodServer::FmodServer() {
	singleton = this;
}

FmodServer::~FmodServer() {
	if (singleton == this) {
		singleton = nullptr;
	}
}

String FmodServer::guid_for(const String &p_path) const {
	for (const Ref<NativeSoundBank> &t : name_tables) {
		const String g = t->find_guid_for_path(p_path);
		if (!g.is_empty()) {
			return g;
		}
	}
	return String();
}

String FmodServer::path_for(const String &p_guid) const {
	for (const Ref<NativeSoundBank> &t : name_tables) {
		const String p = t->find_path_for_guid(p_guid);
		if (!p.is_empty()) {
			return p;
		}
	}
	return String();
}

void FmodServer::init(const Variant &p_settings) {
	print_verbose("FmodServer (native_compat): init");
}

void FmodServer::update() {
	// Forget finished events nobody holds any more.
	for (int i = live.size() - 1; i >= 0; i--) {
		const Ref<FmodEvent> &e = live[i];
		if (e->get_reference_count() <= 1 && (e->state == FmodEvent::STOPPED || e->released)) {
			live.remove_at(i);
		}
	}
}

void FmodServer::shutdown() {
	live.clear();
}

Ref<FmodBank> FmodServer::load_bank(const String &p_path, int p_flag) {
	if (Ref<FmodBank> *have = banks.getptr(p_path)) {
		return *have;
	}
	Ref<NativeSoundBank> data;
	data.instantiate();
	const Error err = data->load(p_path);
	Ref<FmodBank> bank;
	bank.instantiate();
	bank->file = p_path;
	if (err != OK) {
		WARN_PRINT("FmodServer (native_compat): bank " + p_path + ": " + data->get_load_error());
	} else {
		bank->data = data;
		if (data->get_path_count() > 0) {
			name_tables.push_back(data);
		}
	}
	const String stem = p_path.get_file().get_basename().trim_suffix(".strings");
	const String strings_path = p_path.get_file().ends_with(".strings.bank") ? "bank:/" + stem + ".strings" : "bank:/" + stem;
	if (!guid_for(strings_path).is_empty()) {
		bank->path = strings_path;
	}
	banks[p_path] = bank;
	print_line(vformat("native_compat: fmod bank loaded %s file_bytes=%d resident_bytes=%d", p_path.get_file(),
			err == OK ? FileAccess::get_size(p_path) : 0, data->get_resident_bytes()));
	return bank;
}

void FmodServer::unload_bank(const String &p_path) {
	if (Ref<FmodBank> *b = banks.getptr(p_path)) {
		print_line("native_compat: fmod bank unloaded " + p_path.get_file());
		if ((*b)->data.is_valid()) {
			name_tables.erase((*b)->data);
		}
		banks.erase(p_path);
	}
}

Ref<FmodEventDescription> FmodServer::get_event(const String &p_path) {
	if (Ref<FmodEventDescription> *have = events.getptr(p_path)) {
		return *have;
	}
	Ref<FmodEventDescription> d;
	d.instantiate();
	d->path = p_path;
	d->guid = guid_for(p_path);
	if (d->guid.is_empty() && p_path.begins_with("{")) {
		d->guid = p_path; // asked for by GUID with no name table entry: the banks may still know it
	}
	if (d->guid.is_empty() && missing.size() < 64 && !missing.has(p_path)) {
		missing.push_back(p_path);
	}
	events[p_path] = d;
	return d;
}

Ref<FmodEventDescription> FmodServer::get_event_from_guid(const String &p_guid) {
	const String p = path_for(p_guid);
	return get_event(p.is_empty() ? p_guid : p);
}

Ref<FmodEvent> FmodServer::create_event_instance(const String &p_path) {
	update(); // the game may never call update(); drop finished instances nobody holds
	Ref<FmodEventDescription> d = get_event(p_path);
	Ref<FmodEvent> e;
	e.instantiate();
	e->path = p_path;
	e->guid = d->guid;
	live.push_back(e);
	return e;
}

Ref<FmodEvent> FmodServer::create_event_instance_with_guid(const String &p_guid) {
	const String p = path_for(p_guid);
	return create_event_instance(p.is_empty() ? p_guid : p);
}

void FmodServer::play_one_shot(const String &p_path) {
	Ref<FmodEventDescription> d = get_event(p_path);
	one_shots++;
	play_event_sounds(d->guid, p_path, 1, 1, nullptr, nullptr);
}

int FmodServer::play_event_sounds(const String &p_guid, const String &p_path, float p_volume, float p_pitch,
		Vector<ObjectID> *r_players, Vector<float> *r_db) {
	starts++;
	fsb::Guid g;
	SceneTree *tree = SceneTree::get_singleton();
	Window *root = tree ? tree->get_root() : nullptr;
	std::vector<fsb::Pick> picks;
	if (!p_guid.is_empty() && fsb::parse_guid_text(p_guid.utf8().get_data(), g)) {
		for (const KeyValue<String, Ref<FmodBank>> &kv : banks) {
			const Ref<NativeSoundBank> &d = kv.value->data;
			if (d.is_valid() && fsb::resolve_event(d->get_event_table(), g, rng, picks)) {
				break;
			}
		}
	}
	int started = 0;
	for (const fsb::Pick &pick : picks) {
		Ref<AudioStreamOggVorbis> stream;
		for (const KeyValue<String, Ref<FmodBank>> &kv : banks) {
			const Ref<NativeSoundBank> &d = kv.value->data;
			const int index = d.is_valid() ? d->find_wave(pick.wave) : -1;
			if (index >= 0) {
				stream = pick.loop ? d->get_looping_stream(index) : d->get_stream(index);
				break;
			}
		}
		if (stream.is_null()) {
			sounds_without_wave++;
			continue;
		}
		if (!root) {
			continue;
		}
		AudioStreamPlayer *player = memnew(AudioStreamPlayer);
		player->set_stream(stream);
		player->set_volume_db(pick.volume_db + Math::linear_to_db(MAX(p_volume, 0.00001f)));
		player->set_pitch_scale(CLAMP(p_pitch, 0.01f, 16.0f));
		player->connect(SceneStringName(finished), callable_mp(static_cast<Node *>(player), &Node::queue_free));
		// Deferred: the game may start events while the tree is busy adding or removing nodes.
		root->call_deferred(SNAME("add_child"), player);
		if (pick.delay > 0) {
			// Placed later on the event's timeline. The connection goes when the player does (a stop).
			Ref<SceneTreeTimer> timer = tree->create_timer(MAX(pick.delay, 0.02f));
			timer->connect(SNAME("timeout"), callable_mp(player, &AudioStreamPlayer::play).bind(0.0));
		} else {
			player->set_autoplay(true);
		}
		if (r_players) {
			r_players->push_back(player->get_instance_id());
		}
		if (r_db) {
			r_db->push_back(pick.volume_db);
		}
		started++;
	}
	sounds_started += started;
	if (started == 0) {
		starts_without_sound++;
		const String name = p_path.is_empty() ? p_guid : p_path;
		if (silent.size() < 64 && !silent.has(name)) {
			silent.push_back(name);
		}
	}
	return started;
}

void FmodServer::play_one_shot_with_params(const String &p_path, const Dictionary &p_params) {
	play_one_shot(p_path);
}

void FmodServer::play_one_shot_attached(const String &p_path, Node *p_node) {
	play_one_shot(p_path);
}

void FmodServer::play_one_shot_using_guid(const String &p_guid) {
	const String p = path_for(p_guid);
	play_one_shot(p.is_empty() ? p_guid : p);
}

Ref<FmodBus> FmodServer::get_bus(const String &p_path) {
	if (Ref<FmodBus> *have = buses.getptr(p_path)) {
		return *have;
	}
	Ref<FmodBus> b;
	b.instantiate();
	b->path = p_path;
	b->guid = guid_for(p_path);
	buses[p_path] = b;
	return b;
}

Ref<FmodVCA> FmodServer::get_vca(const String &p_path) {
	if (Ref<FmodVCA> *have = vcas.getptr(p_path)) {
		return *have;
	}
	Ref<FmodVCA> v;
	v.instantiate();
	v->path = p_path;
	v->guid = guid_for(p_path);
	vcas[p_path] = v;
	return v;
}

PackedStringArray FmodServer::paths_with_prefix(const String &p_prefix) const {
	PackedStringArray out;
	for (const Ref<NativeSoundBank> &t : name_tables) {
		out.append_array(t->get_paths_with_prefix(p_prefix));
	}
	return out;
}

Array FmodServer::get_all_banks() const {
	Array out;
	for (const KeyValue<String, Ref<FmodBank>> &kv : banks) {
		out.push_back(kv.value);
	}
	return out;
}

Array FmodServer::get_all_event_descriptions() const {
	Array out;
	for (const String &p : paths_with_prefix("event:/")) {
		out.push_back(const_cast<FmodServer *>(this)->get_event(p));
	}
	return out;
}

Array FmodServer::get_all_buses() const {
	Array out;
	for (const String &p : paths_with_prefix("bus:/")) {
		out.push_back(const_cast<FmodServer *>(this)->get_bus(p));
	}
	return out;
}

Array FmodServer::get_all_vca() const {
	Array out;
	for (const String &p : paths_with_prefix("vca:/")) {
		out.push_back(const_cast<FmodServer *>(this)->get_vca(p));
	}
	return out;
}

float FmodServer::get_global_parameter_by_name(const String &p_name) const {
	const float *v = globals.getptr(p_name);
	return v ? *v : 0.0f;
}

int64_t FmodServer::count_instances(const String &p_path) const {
	int64_t n = 0;
	for (const Ref<FmodEvent> &e : live) {
		n += e->path == p_path && !e->released;
	}
	return n;
}

Dictionary FmodServer::get_compat_stats() const {
	Dictionary d;
	d["banks"] = banks.size();
	Array files;
	int64_t resident = 0;
	for (const KeyValue<String, Ref<FmodBank>> &kv : banks) {
		files.push_back(kv.key.get_file());
		if (kv.value->data.is_valid()) {
			resident += kv.value->data->get_resident_bytes();
		}
	}
	d["bank_files"] = files;
	d["bank_resident_bytes"] = resident;
	d["name_tables"] = name_tables.size();
	d["events_asked"] = events.size();
	d["live_instances"] = live.size();
	d["one_shots"] = one_shots;
	d["starts"] = starts;
	d["sounds_started"] = sounds_started;
	d["starts_without_sound"] = starts_without_sound;
	d["sounds_without_wave"] = sounds_without_wave;
	d["silent_events"] = silent;
	d["unknown_paths"] = missing;
	d["globals"] = globals.size();
	return d;
}

void FmodServer::_bind_methods() {
	ClassDB::bind_method(D_METHOD("init", "settings"), &FmodServer::init, DEFVAL(Variant()));
	ClassDB::bind_method(D_METHOD("update"), &FmodServer::update);
	ClassDB::bind_method(D_METHOD("shutdown"), &FmodServer::shutdown);
	ClassDB::bind_method(D_METHOD("load_bank", "path", "flag"), &FmodServer::load_bank, DEFVAL(0));
	ClassDB::bind_method(D_METHOD("unload_bank", "path"), &FmodServer::unload_bank);
	ClassDB::bind_method(D_METHOD("check_event_path", "path"), &FmodServer::check_event_path);
	ClassDB::bind_method(D_METHOD("check_event_guid", "guid"), &FmodServer::check_event_guid);
	ClassDB::bind_method(D_METHOD("check_bus_path", "path"), &FmodServer::check_bus_path);
	ClassDB::bind_method(D_METHOD("check_vca_path", "path"), &FmodServer::check_vca_path);
	ClassDB::bind_method(D_METHOD("get_event", "path"), &FmodServer::get_event);
	ClassDB::bind_method(D_METHOD("get_event_from_guid", "guid"), &FmodServer::get_event_from_guid);
	ClassDB::bind_method(D_METHOD("create_event_instance", "path"), &FmodServer::create_event_instance);
	ClassDB::bind_method(D_METHOD("create_event_instance_with_guid", "guid"), &FmodServer::create_event_instance_with_guid);
	ClassDB::bind_method(D_METHOD("play_one_shot", "path"), &FmodServer::play_one_shot);
	ClassDB::bind_method(D_METHOD("play_one_shot_with_params", "path", "parameters"), &FmodServer::play_one_shot_with_params);
	ClassDB::bind_method(D_METHOD("play_one_shot_attached", "path", "node"), &FmodServer::play_one_shot_attached);
	ClassDB::bind_method(D_METHOD("play_one_shot_using_guid", "guid"), &FmodServer::play_one_shot_using_guid);
	ClassDB::bind_method(D_METHOD("get_bus", "path"), &FmodServer::get_bus);
	ClassDB::bind_method(D_METHOD("get_vca", "path"), &FmodServer::get_vca);
	ClassDB::bind_method(D_METHOD("get_all_banks"), &FmodServer::get_all_banks);
	ClassDB::bind_method(D_METHOD("get_all_event_descriptions"), &FmodServer::get_all_event_descriptions);
	ClassDB::bind_method(D_METHOD("get_all_buses"), &FmodServer::get_all_buses);
	ClassDB::bind_method(D_METHOD("get_all_vca"), &FmodServer::get_all_vca);
	ClassDB::bind_method(D_METHOD("set_global_parameter_by_name", "name", "value"), &FmodServer::set_global_parameter_by_name);
	ClassDB::bind_method(D_METHOD("get_global_parameter_by_name", "name"), &FmodServer::get_global_parameter_by_name);
	ClassDB::bind_method(D_METHOD("set_global_parameter_by_name_with_label", "name", "label"),
			&FmodServer::set_global_parameter_by_name_with_label);
	ClassDB::bind_method(D_METHOD("set_global_parameter_by_id", "id", "value"), &FmodServer::set_global_parameter_by_id);
	ClassDB::bind_method(D_METHOD("get_global_parameter_by_id", "id"), &FmodServer::get_global_parameter_by_id);
	ClassDB::bind_method(D_METHOD("mute_all_events"), &FmodServer::mute_all_events);
	ClassDB::bind_method(D_METHOD("unmute_all_events"), &FmodServer::unmute_all_events);
	ClassDB::bind_method(D_METHOD("pause_all_events"), &FmodServer::pause_all_events);
	ClassDB::bind_method(D_METHOD("unpause_all_events"), &FmodServer::unpause_all_events);
	ClassDB::bind_method(D_METHOD("banks_still_loading"), &FmodServer::banks_still_loading);
	ClassDB::bind_method(D_METHOD("wait_for_all_loads"), &FmodServer::wait_for_all_loads);
	ClassDB::bind_method(D_METHOD("get_compat_stats"), &FmodServer::get_compat_stats);
}

// --- Emitters -------------------------------------------------------------------------------------------

#define FMOD_EMITTER_IMPL(Cls)                                                                                 \
	void Cls::set_volume(float p_v) {                                                                          \
		volume = p_v;                                                                                          \
		if (event.is_valid()) {                                                                                \
			event->set_volume(p_v);                                                                            \
		}                                                                                                      \
	}                                                                                                          \
	void Cls::play(bool p_restart_if_playing) {                                                                \
		FmodServer *s = FmodServer::get_singleton();                                                           \
		if (!s || (event_name.is_empty() && event_guid.is_empty())) {                                          \
			emit_signal("start_failed");                                                                       \
			return;                                                                                            \
		}                                                                                                      \
		const bool was_playing = is_playing();                                                                 \
		if (was_playing && !p_restart_if_playing) {                                                            \
			return;                                                                                            \
		}                                                                                                      \
		if (event.is_null()) {                                                                                 \
			event = event_name.is_empty() ? s->create_event_instance_with_guid(event_guid) :                   \
											s->create_event_instance(event_name);                              \
		}                                                                                                      \
		event->set_volume(volume);                                                                             \
		for (const KeyValue<StringName, Variant> &kv : extra) {                                                \
			const String key = kv.key;                                                                         \
			if (key.begins_with("event_parameter/") && key.ends_with("/value")) {                              \
				event->set_parameter_by_name(key.get_slicec('/', 1), kv.value);                                \
			}                                                                                                  \
		}                                                                                                      \
		event->set_node_attributes(this);                                                                      \
		event->start();                                                                                        \
		emit_signal(was_playing ? "restarted" : "started");                                                    \
	}                                                                                                          \
	void Cls::play_one_shot() {                                                                                \
		if (FmodServer *s = FmodServer::get_singleton()) {                                                     \
			s->play_one_shot(event_name.is_empty() ? event_guid : event_name);                                 \
		}                                                                                                      \
	}                                                                                                          \
	void Cls::stop() {                                                                                         \
		if (event.is_valid()) {                                                                                \
			event->stop(allow_fadeout ? FmodEvent::ALLOWFADEOUT : FmodEvent::IMMEDIATE);                       \
			emit_signal("stopped");                                                                            \
		}                                                                                                      \
	}                                                                                                          \
	void Cls::set_paused(bool p_p) {                                                                           \
		if (event.is_valid()) {                                                                                \
			event->set_paused(p_p);                                                                            \
		}                                                                                                      \
	}                                                                                                          \
	void Cls::set_parameter(const String &p_name, const Variant &p_value) {                                    \
		extra[StringName("event_parameter/" + p_name + "/value")] = p_value;                                   \
		if (event.is_valid()) {                                                                                \
			event->set_parameter_by_name(p_name, p_value);                                                     \
		}                                                                                                      \
	}                                                                                                          \
	Variant Cls::get_parameter(const String &p_name) const {                                                   \
		if (event.is_valid()) {                                                                                \
			return event->get_parameter_by_name(p_name);                                                       \
		}                                                                                                      \
		const Variant *v = extra.getptr(StringName("event_parameter/" + p_name + "/value"));                   \
		return v ? *v : Variant();                                                                             \
	}                                                                                                          \
	bool Cls::_set(const StringName &p_name, const Variant &p_value) {                                         \
		extra[p_name] = p_value;                                                                               \
		return true;                                                                                           \
	}                                                                                                          \
	bool Cls::_get(const StringName &p_name, Variant &r_ret) const {                                           \
		const Variant *v = extra.getptr(p_name);                                                               \
		if (!v) {                                                                                              \
			return false;                                                                                      \
		}                                                                                                      \
		r_ret = *v;                                                                                            \
		return true;                                                                                           \
	}                                                                                                          \
	void Cls::_notification(int p_what) {                                                                      \
		switch (p_what) {                                                                                      \
			case NOTIFICATION_READY:                                                                           \
				if (autoplay && !Engine::get_singleton()->is_editor_hint()) {                                  \
					play();                                                                                    \
				}                                                                                              \
				break;                                                                                         \
			case NOTIFICATION_EXIT_TREE:                                                                       \
				if (event.is_valid()) {                                                                        \
					event->stop(allow_fadeout ? FmodEvent::ALLOWFADEOUT : FmodEvent::IMMEDIATE);               \
					event->release();                                                                          \
					event.unref();                                                                             \
				}                                                                                              \
				break;                                                                                         \
		}                                                                                                      \
	}                                                                                                          \
	void Cls::_bind_methods() {                                                                                \
		ClassDB::bind_method(D_METHOD("set_event_name", "name"), &Cls::set_event_name);                       \
		ClassDB::bind_method(D_METHOD("get_event_name"), &Cls::get_event_name);                               \
		ClassDB::bind_method(D_METHOD("set_event_guid", "guid"), &Cls::set_event_guid);                       \
		ClassDB::bind_method(D_METHOD("get_event_guid"), &Cls::get_event_guid);                               \
		ClassDB::bind_method(D_METHOD("set_autoplay", "autoplay"), &Cls::set_autoplay);                       \
		ClassDB::bind_method(D_METHOD("is_autoplay"), &Cls::is_autoplay);                                     \
		ClassDB::bind_method(D_METHOD("set_attached", "attached"), &Cls::set_attached);                       \
		ClassDB::bind_method(D_METHOD("is_attached"), &Cls::is_attached);                                     \
		ClassDB::bind_method(D_METHOD("set_allow_fadeout", "allow"), &Cls::set_allow_fadeout);                \
		ClassDB::bind_method(D_METHOD("is_allow_fadeout"), &Cls::is_allow_fadeout);                           \
		ClassDB::bind_method(D_METHOD("set_preload_event", "preload"), &Cls::set_preload_event);              \
		ClassDB::bind_method(D_METHOD("is_preload_event"), &Cls::is_preload_event);                           \
		ClassDB::bind_method(D_METHOD("set_auto_release", "auto_release"), &Cls::set_auto_release);           \
		ClassDB::bind_method(D_METHOD("is_auto_release"), &Cls::is_auto_release);                             \
		ClassDB::bind_method(D_METHOD("set_volume", "volume"), &Cls::set_volume);                             \
		ClassDB::bind_method(D_METHOD("get_volume"), &Cls::get_volume);                                       \
		ClassDB::bind_method(D_METHOD("play", "restart_if_playing"), &Cls::play, DEFVAL(true));               \
		ClassDB::bind_method(D_METHOD("play_one_shot"), &Cls::play_one_shot);                                 \
		ClassDB::bind_method(D_METHOD("stop"), &Cls::stop);                                                   \
		ClassDB::bind_method(D_METHOD("set_paused", "paused"), &Cls::set_paused);                             \
		ClassDB::bind_method(D_METHOD("is_paused"), &Cls::is_paused);                                         \
		ClassDB::bind_method(D_METHOD("is_playing"), &Cls::is_playing);                                       \
		ClassDB::bind_method(D_METHOD("set_parameter", "name", "value"), &Cls::set_parameter);                \
		ClassDB::bind_method(D_METHOD("get_parameter", "name"), &Cls::get_parameter);                         \
		ClassDB::bind_method(D_METHOD("get_event"), &Cls::get_event);                                         \
		ADD_PROPERTY(PropertyInfo(Variant::STRING, "event_name"), "set_event_name", "get_event_name");       \
		ADD_PROPERTY(PropertyInfo(Variant::STRING, "event_guid"), "set_event_guid", "get_event_guid");       \
		ADD_PROPERTY(PropertyInfo(Variant::BOOL, "autoplay"), "set_autoplay", "is_autoplay");                \
		ADD_PROPERTY(PropertyInfo(Variant::BOOL, "attached"), "set_attached", "is_attached");                \
		ADD_PROPERTY(PropertyInfo(Variant::BOOL, "allow_fadeout"), "set_allow_fadeout", "is_allow_fadeout"); \
		ADD_PROPERTY(PropertyInfo(Variant::BOOL, "preload_event"), "set_preload_event", "is_preload_event"); \
		ADD_PROPERTY(PropertyInfo(Variant::BOOL, "auto_release"), "set_auto_release", "is_auto_release");    \
		ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "volume"), "set_volume", "get_volume");                    \
		ADD_SIGNAL(MethodInfo("started"));                                                                     \
		ADD_SIGNAL(MethodInfo("stopped"));                                                                     \
		ADD_SIGNAL(MethodInfo("restarted"));                                                                   \
		ADD_SIGNAL(MethodInfo("start_failed"));                                                                \
	}

FMOD_EMITTER_IMPL(FmodEventEmitter2D)
FMOD_EMITTER_IMPL(FmodEventEmitter3D)

// --- Listeners and the bank loader -----------------------------------------------------------------------

#define FMOD_LISTENER_BIND(Cls)                                                                                      \
	void Cls::_bind_methods() {                                                                                      \
		ClassDB::bind_method(D_METHOD("set_listener_index", "index"), &Cls::set_listener_index);                    \
		ClassDB::bind_method(D_METHOD("get_listener_index"), &Cls::get_listener_index);                             \
		ClassDB::bind_method(D_METHOD("set_listener_weight", "weight"), &Cls::set_listener_weight);                 \
		ClassDB::bind_method(D_METHOD("get_listener_weight"), &Cls::get_listener_weight);                           \
		ClassDB::bind_method(D_METHOD("set_is_locked", "locked"), &Cls::set_is_locked);                             \
		ClassDB::bind_method(D_METHOD("get_is_locked"), &Cls::get_is_locked);                                       \
		ADD_PROPERTY(PropertyInfo(Variant::INT, "listener_index"), "set_listener_index", "get_listener_index");     \
		ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "listener_weight"), "set_listener_weight", "get_listener_weight"); \
		ADD_PROPERTY(PropertyInfo(Variant::BOOL, "is_locked"), "set_is_locked", "get_is_locked");                   \
	}

FMOD_LISTENER_BIND(FmodListener2D)
FMOD_LISTENER_BIND(FmodListener3D)

void FmodBankLoader::_notification(int p_what) {
	if (p_what != NOTIFICATION_ENTER_TREE || Engine::get_singleton()->is_editor_hint()) {
		return;
	}
	FmodServer *s = FmodServer::get_singleton();
	if (!s) {
		return;
	}
	for (int i = 0; i < bank_paths.size(); i++) {
		loaded.push_back(s->load_bank(bank_paths[i], 0));
	}
}

void FmodBankLoader::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_bank_paths", "paths"), &FmodBankLoader::set_bank_paths);
	ClassDB::bind_method(D_METHOD("get_bank_paths"), &FmodBankLoader::get_bank_paths);
	ClassDB::bind_method(D_METHOD("set_preload_sample_data", "preload"), &FmodBankLoader::set_preload_sample_data);
	ClassDB::bind_method(D_METHOD("get_preload_sample_data"), &FmodBankLoader::get_preload_sample_data);
	ADD_PROPERTY(PropertyInfo(Variant::ARRAY, "bank_paths"), "set_bank_paths", "get_bank_paths");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "preload_sample_data"), "set_preload_sample_data", "get_preload_sample_data");
}
