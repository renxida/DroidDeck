// Clean-room code for DroidDeck/Kestrel; see native_sound_bank.h.
#include "native_sound_bank.h"

#include "core/io/file_access.h"
#include "modules/ogg/ogg_packet_sequence.h"

Error NativeSoundBank::load(const String &p_path) {
	Error err = OK;
	Vector<uint8_t> raw = FileAccess::get_file_as_bytes(p_path, &err);
	if (err != OK) {
		load_error = "cannot read " + p_path;
		return err;
	}
	return load_bytes(raw);
}

Error NativeSoundBank::load_bytes(const PackedByteArray &p_bytes) {
	bytes.clear();
	container = fsb::Container();
	paths = fsb::PathTable();
	events = fsb::EventTable();
	streams.clear();
	looping_streams.clear();
	std::string error;
	// Event metadata is optional: a bank we cannot read it from still serves its sounds by index.
	if (!fsb::read_event_table(p_bytes.ptr(), p_bytes.size(), events, error)) {
		events = fsb::EventTable();
	}
	error.clear();
	// A strings bank has a path table and no audio; other banks have audio and no path table.
	const bool has_paths = fsb::read_path_table(p_bytes.ptr(), p_bytes.size(), paths, error);
	if (!has_paths) {
		paths = fsb::PathTable();
		if (error != "no path table (STDT) in the file") {
			load_error = String::utf8(error.c_str());
			return ERR_FILE_CORRUPT;
		}
	}
	size_t at = 0;
	if (!fsb::find_fsb5(p_bytes.ptr(), p_bytes.size(), at)) {
		load_error = has_paths ? String() : String("no FSB5 block or path table in the file");
		return has_paths ? OK : ERR_FILE_UNRECOGNIZED;
	}
	fsb::Container parsed;
	if (!fsb::parse_fsb5(p_bytes.ptr() + at, p_bytes.size() - at, parsed, error)) {
		load_error = String::utf8(error.c_str());
		return ERR_FILE_CORRUPT;
	}
	const size_t keep = MIN(parsed.size, size_t(p_bytes.size()) - at);
	bytes = p_bytes.slice(int64_t(at), int64_t(at + keep));
	container = parsed;
	load_error = String();
	return OK;
}

String NativeSoundBank::get_path(int p_index) const {
	ERR_FAIL_INDEX_V(p_index, int(paths.paths.size()), String());
	return String::utf8(paths.paths[p_index].c_str());
}

String NativeSoundBank::get_path_guid(int p_index) const {
	ERR_FAIL_INDEX_V(p_index, int(paths.guids.size()), String());
	return String(fsb::guid_text(paths.guids[p_index]).c_str());
}

String NativeSoundBank::find_guid_for_path(const String &p_path) const {
	const int i = paths.find_path(p_path.utf8().get_data());
	return i < 0 ? String() : get_path_guid(i);
}

String NativeSoundBank::find_path_for_guid(const String &p_guid) const {
	fsb::Guid g;
	if (!fsb::parse_guid_text(p_guid.utf8().get_data(), g)) {
		return String();
	}
	const int i = paths.find_guid(g);
	return i < 0 ? String() : get_path(i);
}

PackedStringArray NativeSoundBank::get_paths_with_prefix(const String &p_prefix) const {
	PackedStringArray out;
	const CharString prefix = p_prefix.utf8();
	for (const std::string &p : paths.paths) {
		if (p.compare(0, prefix.length(), prefix.get_data()) == 0) {
			out.push_back(String::utf8(p.c_str()));
		}
	}
	out.sort();
	return out;
}

String NativeSoundBank::get_sound_name(int p_index) const {
	ERR_FAIL_INDEX_V(p_index, int(container.samples.size()), String());
	return String::utf8(container.samples[p_index].name.c_str());
}

int NativeSoundBank::find_sound(const String &p_name) const {
	const CharString name = p_name.utf8();
	for (size_t i = 0; i < container.samples.size(); i++) {
		if (container.samples[i].name == name.get_data()) {
			return int(i);
		}
	}
	return -1;
}

int NativeSoundBank::get_sound_frames(int p_index) const {
	ERR_FAIL_INDEX_V(p_index, int(container.samples.size()), 0);
	return int(container.samples[p_index].frames);
}

bool NativeSoundBank::has_event(const String &p_guid) const {
	fsb::Guid g;
	return fsb::parse_guid_text(p_guid.utf8().get_data(), g) && events.events.count(g);
}

PackedInt32Array NativeSoundBank::pick_event_sounds(const String &p_guid, int p_seed) const {
	PackedInt32Array out;
	fsb::Guid g;
	if (!fsb::parse_guid_text(p_guid.utf8().get_data(), g)) {
		return out;
	}
	uint32_t rng = uint32_t(p_seed);
	std::vector<fsb::Pick> picks;
	fsb::resolve_event(events, g, rng, picks);
	for (const fsb::Pick &p : picks) {
		out.push_back(find_wave(p.wave));
	}
	return out;
}

int NativeSoundBank::find_wave(const fsb::Guid &p_wave) const {
	auto it = events.waves.find(p_wave);
	return it == events.waves.end() || it->second >= container.samples.size() ? -1 : int(it->second);
}

Ref<AudioStreamOggVorbis> NativeSoundBank::get_stream(int p_index) {
	ERR_FAIL_INDEX_V(p_index, int(container.samples.size()), Ref<AudioStreamOggVorbis>());
	if (const Ref<AudioStreamOggVorbis> *have = streams.getptr(p_index)) {
		return *have;
	}
	if (container.codec != fsb::CODEC_VORBIS) {
		load_error = "codec " + itos(container.codec) + " is not Vorbis";
		return Ref<AudioStreamOggVorbis>();
	}
	fsb::VorbisStream v;
	std::string error;
	if (!fsb::vorbis_stream(bytes.ptr(), bytes.size(), container.samples[p_index], v, error)) {
		load_error = String::utf8(error.c_str());
		return Ref<AudioStreamOggVorbis>();
	}
	auto to_bytes = [](const std::vector<uint8_t> &p) {
		PackedByteArray out;
		out.resize(p.size());
		memcpy(out.ptrw(), p.data(), p.size());
		return out;
	};
	Ref<OggPacketSequence> sequence;
	sequence.instantiate();
	Vector<PackedByteArray> page;
	for (const std::vector<uint8_t> &h : v.headers) {
		page.push_back(to_bytes(h));
	}
	sequence->push_page(0, page);
	// One page per 16 packets keeps seeking cheap; a page's granule is the frame count after its last packet.
	const size_t per_page = 16;
	for (size_t i = 0; i < v.packets.size(); i += per_page) {
		page.clear();
		const size_t end = MIN(i + per_page, v.packets.size());
		for (size_t j = i; j < end; j++) {
			page.push_back(to_bytes(v.packets[j]));
		}
		sequence->push_page(v.granules[end - 1], page);
	}
	sequence->set_sampling_rate(float(v.rate));
	Ref<AudioStreamOggVorbis> stream;
	stream.instantiate();
	stream->set_packet_sequence(sequence);
	streams[p_index] = stream;
	return stream;
}

Ref<AudioStreamOggVorbis> NativeSoundBank::get_looping_stream(int p_index) {
	if (const Ref<AudioStreamOggVorbis> *have = looping_streams.getptr(p_index)) {
		return *have;
	}
	Ref<AudioStreamOggVorbis> base = get_stream(p_index);
	if (base.is_null()) {
		return base;
	}
	Ref<AudioStreamOggVorbis> loop = base->duplicate(); // shares the packet data
	loop->set_loop(true);
	looping_streams[p_index] = loop;
	return loop;
}

void NativeSoundBank::_bind_methods() {
	ClassDB::bind_method(D_METHOD("load", "path"), &NativeSoundBank::load);
	ClassDB::bind_method(D_METHOD("load_bytes", "bytes"), &NativeSoundBank::load_bytes);
	ClassDB::bind_method(D_METHOD("get_load_error"), &NativeSoundBank::get_load_error);
	ClassDB::bind_method(D_METHOD("get_sound_count"), &NativeSoundBank::get_sound_count);
	ClassDB::bind_method(D_METHOD("get_sound_name", "index"), &NativeSoundBank::get_sound_name);
	ClassDB::bind_method(D_METHOD("find_sound", "name"), &NativeSoundBank::find_sound);
	ClassDB::bind_method(D_METHOD("get_sound_frames", "index"), &NativeSoundBank::get_sound_frames);
	ClassDB::bind_method(D_METHOD("get_stream", "index"), &NativeSoundBank::get_stream);
	ClassDB::bind_method(D_METHOD("get_path_count"), &NativeSoundBank::get_path_count);
	ClassDB::bind_method(D_METHOD("get_path", "index"), &NativeSoundBank::get_path);
	ClassDB::bind_method(D_METHOD("get_path_guid", "index"), &NativeSoundBank::get_path_guid);
	ClassDB::bind_method(D_METHOD("find_guid_for_path", "path"), &NativeSoundBank::find_guid_for_path);
	ClassDB::bind_method(D_METHOD("find_path_for_guid", "guid"), &NativeSoundBank::find_path_for_guid);
	ClassDB::bind_method(D_METHOD("get_paths_with_prefix", "prefix"), &NativeSoundBank::get_paths_with_prefix);
	ClassDB::bind_method(D_METHOD("get_event_count"), &NativeSoundBank::get_event_count);
	ClassDB::bind_method(D_METHOD("has_event", "guid"), &NativeSoundBank::has_event);
	ClassDB::bind_method(D_METHOD("pick_event_sounds", "guid", "seed"), &NativeSoundBank::pick_event_sounds, DEFVAL(1));
}
