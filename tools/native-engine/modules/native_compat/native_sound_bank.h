// Clean-room code for DroidDeck/Kestrel: written from scratch; no FMOD code consulted.
// Opens a sound bank (or a bare FSB5 file) and hands out each sound as a Godot Vorbis stream.
// Event playback (event paths, parameters, mixing) builds on this once the bank metadata is read.
#pragma once

#include "fsb/fsb5.h"
#include "fsb/strings.h"

#include "core/object/ref_counted.h"
#include "modules/vorbis/audio_stream_ogg_vorbis.h"

class NativeSoundBank : public RefCounted {
	GDCLASS(NativeSoundBank, RefCounted);

	Vector<uint8_t> bytes; // the FSB5 block only
	fsb::Container container;
	fsb::PathTable paths; // only in a strings bank
	String load_error;

protected:
	static void _bind_methods();

public:
	Error load(const String &p_path);
	Error load_bytes(const PackedByteArray &p_bytes);
	String get_load_error() const { return load_error; }
	int get_sound_count() const { return int(container.samples.size()); }
	String get_sound_name(int p_index) const;
	int find_sound(const String &p_name) const;
	int get_sound_frames(int p_index) const;
	Ref<AudioStreamOggVorbis> get_stream(int p_index);

	// The path table of a strings bank: event:/, bus:/, vca:/, snapshot:/, parameter:/ and bank:/ paths
	// with their GUIDs (as {xxxxxxxx-...} text).
	int get_path_count() const { return int(paths.paths.size()); }
	String get_path(int p_index) const;
	String get_path_guid(int p_index) const;
	String find_guid_for_path(const String &p_path) const; // empty if absent
	String find_path_for_guid(const String &p_guid) const; // empty if absent
	PackedStringArray get_paths_with_prefix(const String &p_prefix) const;
};
