// Clean-room FSB5 reader; see fsb5.h.
#include "fsb5.h"

#include "vorbis_setups.gen.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace fsb {

namespace {

uint32_t u32(const uint8_t *p) {
	return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}

uint64_t u64(const uint8_t *p) {
	return uint64_t(u32(p)) | uint64_t(u32(p + 4)) << 32;
}

const int RATES[16] = { 0, 8000, 11000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 0, 0, 0, 0, 0, 0 };
const int CHANNELS[4] = { 1, 2, 6, 8 };

enum ChunkKind {
	CHUNK_CHANNELS = 1,
	CHUNK_RATE = 2,
	CHUNK_VORBIS = 11,
};

const VorbisSetup *setup_for(uint32_t id) {
	for (const VorbisSetup &s : vorbis_setups) {
		if (s.id == id) {
			return &s;
		}
	}
	return nullptr;
}

int log2_of(int v) {
	int n = 0;
	while ((1 << n) < v) {
		n++;
	}
	return n;
}

void put32(std::vector<uint8_t> &out, uint32_t v) {
	for (int i = 0; i < 4; i++) {
		out.push_back(uint8_t(v >> (8 * i)));
	}
}

std::vector<uint8_t> identification(int channels, int rate, int short_block, int long_block) {
	std::vector<uint8_t> p = { 1, 'v', 'o', 'r', 'b', 'i', 's' };
	put32(p, 0); // version
	p.push_back(uint8_t(channels));
	put32(p, uint32_t(rate));
	put32(p, 0); // bitrate maximum, nominal, minimum: unknown
	put32(p, 0);
	put32(p, 0);
	p.push_back(uint8_t(log2_of(short_block) | log2_of(long_block) << 4));
	p.push_back(1); // framing
	return p;
}

std::vector<uint8_t> comment() {
	static const char vendor[] = "kestrel fsb5";
	std::vector<uint8_t> p = { 3, 'v', 'o', 'r', 'b', 'i', 's' };
	put32(p, sizeof(vendor) - 1);
	p.insert(p.end(), vendor, vendor + sizeof(vendor) - 1);
	put32(p, 0); // no user comments
	p.push_back(1);
	return p;
}

} // namespace

bool known_vorbis_setup(uint32_t id) {
	return setup_for(id) != nullptr;
}

bool find_fsb5(const uint8_t *data, size_t length, size_t &at) {
	for (size_t i = 0; i + 4 <= length; i++) {
		if (memcmp(data + i, "FSB5", 4) == 0) {
			at = i;
			return true;
		}
	}
	return false;
}

size_t fsb5_head_size(const uint8_t *data, size_t length) {
	if (length < 28 || memcmp(data, "FSB5", 4) != 0) {
		return 0;
	}
	const size_t head = u32(data + 4) >= 1 ? 0x3C : 0x40;
	return head + size_t(u32(data + 12)) + size_t(u32(data + 16));
}

bool parse_fsb5(const uint8_t *data, size_t length, Container &out, std::string &error, size_t full_length) {
	out = Container();
	if (length < 28 || memcmp(data, "FSB5", 4) != 0) {
		error = "not an FSB5 block";
		return false;
	}
	out.version = u32(data + 4);
	const uint32_t count = u32(data + 8);
	const uint32_t header_bytes = u32(data + 12);
	const uint32_t name_bytes = u32(data + 16);
	const uint32_t data_bytes = u32(data + 20);
	out.codec = u32(data + 24);
	const size_t head = out.version >= 1 ? 0x3C : 0x40;
	const size_t base = head + size_t(header_bytes) + size_t(name_bytes);
	if (out.version > 1 || head + header_bytes > length || count > header_bytes / 8) {
		error = "FSB5 header out of range (version " + std::to_string(out.version) + ")";
		return false;
	}
	out.size = base + data_bytes;
	std::vector<size_t> offsets;
	size_t pos = head;
	const size_t table_end = head + header_bytes;
	for (uint32_t i = 0; i < count; i++) {
		if (pos + 8 > table_end) {
			error = "sample header " + std::to_string(i) + " past its table";
			return false;
		}
		const uint64_t mode = u64(data + pos);
		pos += 8;
		Sample s;
		s.rate = RATES[(mode >> 1) & 0xF];
		s.channels = CHANNELS[(mode >> 5) & 0x3];
		offsets.push_back(size_t((mode >> 7) & 0x7FFFFFF) * 32);
		s.frames = mode >> 34;
		bool more = mode & 1;
		while (more) {
			if (pos + 4 > table_end) {
				error = "sample " + std::to_string(i) + " extra data past its table";
				return false;
			}
			const uint32_t chunk = u32(data + pos);
			more = chunk & 1;
			const size_t size = (chunk >> 1) & 0xFFFFFF;
			const uint32_t kind = chunk >> 25;
			const uint8_t *body = data + pos + 4;
			pos += 4 + size;
			if (pos > table_end) {
				error = "sample " + std::to_string(i) + " extra data past its table";
				return false;
			}
			if (kind == CHUNK_CHANNELS && size >= 1) {
				s.channels = body[0];
			} else if (kind == CHUNK_RATE && size >= 4) {
				s.rate = int(u32(body));
			} else if (kind == CHUNK_VORBIS && size >= 4) {
				s.has_setup = true;
				s.setup_id = u32(body);
			}
		}
		out.samples.push_back(s);
	}
	for (uint32_t i = 0; i < count; i++) {
		Sample &s = out.samples[i];
		const size_t next = i + 1 < count ? offsets[i + 1] : data_bytes;
		const size_t limit = full_length ? full_length : length;
		s.begin = std::min(base + offsets[i], limit);
		s.end = std::min(base + std::max(next, offsets[i]), limit);
	}
	// Names: one 32-bit offset per sample from the start of the name table, then NUL-terminated strings.
	const size_t names = table_end;
	if (name_bytes >= size_t(count) * 4 && names + name_bytes <= length) {
		for (uint32_t i = 0; i < count; i++) {
			const size_t at = names + u32(data + names + 4 * i);
			if (at >= names + name_bytes) {
				continue;
			}
			const uint8_t *start = data + at;
			const uint8_t *stop = static_cast<const uint8_t *>(memchr(start, 0, names + name_bytes - at));
			out.samples[i].name.assign(reinterpret_cast<const char *>(start), stop ? size_t(stop - start) : names + name_bytes - at);
		}
	}
	return true;
}

bool vorbis_stream(const uint8_t *fsb, size_t length, const Sample &sample, VorbisStream &out, std::string &error) {
	out = VorbisStream();
	if (!sample.has_setup) {
		error = "sample has no Vorbis setup id";
		return false;
	}
	const VorbisSetup *setup = setup_for(sample.setup_id);
	if (!setup) {
		char id[16];
		snprintf(id, sizeof(id), "%08x", sample.setup_id);
		error = std::string("unknown Vorbis setup id ") + id;
		return false;
	}
	if (setup->channels != sample.channels || setup->rate != sample.rate) {
		error = "sample format does not match its Vorbis setup";
		return false;
	}
	out.rate = sample.rate;
	out.channels = sample.channels;
	out.headers.push_back(identification(setup->channels, setup->rate, setup->short_block, setup->long_block));
	out.headers.push_back(comment());
	out.headers.emplace_back(setup->packet, setup->packet + setup->size);
	// Audio packets are stored as a 16-bit little-endian length then the packet, until a zero length or
	// the end of the sample. The generated setups have two modes (0 short block, 1 long block), so the
	// mode is the bit after the packet-type bit.
	size_t pos = sample.begin;
	const size_t end = std::min(sample.end, length);
	int64_t granule = 0;
	int previous = 0;
	while (pos + 2 <= end) {
		const size_t size = size_t(fsb[pos]) | size_t(fsb[pos + 1]) << 8;
		pos += 2;
		if (size == 0) {
			break;
		}
		if (pos + size > end) {
			error = "packet runs past the end of the sample";
			return false;
		}
		const uint8_t *p = fsb + pos;
		pos += size;
		if (p[0] & 1) {
			error = "header packet inside the audio";
			return false;
		}
		const int block = (p[0] >> 1) & 1 ? setup->long_block : setup->short_block;
		if (previous) {
			granule += previous / 4 + block / 4;
		}
		previous = block;
		out.packets.emplace_back(p, p + size);
		out.granules.push_back(granule);
	}
	if (out.packets.empty()) {
		error = "sample has no audio packets";
		return false;
	}
	// The last block runs past the true end; the container's frame count trims it, as an Ogg end page does.
	if (sample.frames > 0 && int64_t(sample.frames) < out.granules.back()) {
		out.granules.back() = int64_t(sample.frames);
	}
	return true;
}

} // namespace fsb
