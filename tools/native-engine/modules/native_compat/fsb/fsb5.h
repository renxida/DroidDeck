// Clean-room reader for FSB5 sound containers (the audio part of the game's sound banks), written from
// the public FSB5 notes and the game's own files. No FMOD code.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace fsb {

enum Codec : uint32_t {
	CODEC_PCM16 = 2,
	CODEC_VORBIS = 15,
};

struct Sample {
	std::string name;
	int rate = 0;
	int channels = 0;
	uint64_t frames = 0; // length in frames as the container states it
	bool has_setup = false;
	uint32_t setup_id = 0; // Vorbis: CRC-32 of the setup header packet the encoder wrote
	size_t begin = 0; // byte range of this sample's data inside the FSB5 block
	size_t end = 0;
};

struct Container {
	uint32_t version = 0;
	uint32_t codec = 0;
	size_t size = 0; // bytes from "FSB5" to the end of the sample data
	std::vector<Sample> samples;
};

// Finds the first FSB5 block in a file (a bank holds one after its metadata). Returns false if none.
bool find_fsb5(const uint8_t *data, size_t length, size_t &at);

// Reads the FSB5 block that starts at data[0]. Sample ranges are clamped to [0, length), so a block cut
// short still lists its samples; check Sample::end against the length you have. With `full_length`, `data`
// may hold only the block's headers and names (fsb5_head_size() bytes): ranges are then clamped to
// full_length, the block's true length, so the audio can be read from the file when a sound is wanted.
bool parse_fsb5(const uint8_t *data, size_t length, Container &out, std::string &error, size_t full_length = 0);

// Bytes from "FSB5" to the end of the sample headers and names (what parse_fsb5 needs), given at least
// the block's first 28 bytes; 0 if `data` is not an FSB5 block.
size_t fsb5_head_size(const uint8_t *data, size_t length);

// One Vorbis sample as a standard Vorbis stream: the three header packets (identification, comment,
// setup, the setup regenerated from the id), the audio packets, and the frame position after each.
struct VorbisStream {
	int rate = 0;
	int channels = 0;
	std::vector<std::vector<uint8_t>> headers;
	std::vector<std::vector<uint8_t>> packets;
	std::vector<int64_t> granules; // frames decoded once packets[i] is in
};

bool vorbis_stream(const uint8_t *fsb, size_t length, const Sample &sample, VorbisStream &out, std::string &error);

// Whether a setup id is one we can rebuild.
bool known_vorbis_setup(uint32_t id);

} // namespace fsb
