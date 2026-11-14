// Host tests for the clean-room FSB5 reader, on synthetic containers only (no game files).
#include "../fsb5.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

#define CHECK(c) \
	do { \
		if (!(c)) { \
			fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #c); \
			exit(1); \
		} \
	} while (0)

namespace {

void put32(std::vector<uint8_t> &b, uint32_t v) {
	for (int i = 0; i < 4; i++) {
		b.push_back(uint8_t(v >> (8 * i)));
	}
}

void put64(std::vector<uint8_t> &b, uint64_t v) {
	put32(b, uint32_t(v));
	put32(b, uint32_t(v >> 32));
}

// Packet bytes: bit 0 audio (0), bit 1 mode (0 short, 1 long), then filler.
std::vector<uint8_t> packet(bool long_block, int size) {
	std::vector<uint8_t> p(size, 0x5A);
	p[0] = long_block ? 0x02 : 0x00;
	return p;
}

struct Spec {
	std::vector<std::vector<std::vector<uint8_t>>> packets; // per sample
	std::vector<uint64_t> frames;
	std::vector<std::string> names;
	uint32_t setup = 0xC4C30A29u;
};

std::vector<uint8_t> build(const Spec &spec) {
	std::vector<std::vector<uint8_t>> datas;
	for (const auto &list : spec.packets) {
		std::vector<uint8_t> d;
		for (const auto &p : list) {
			d.push_back(uint8_t(p.size()));
			d.push_back(uint8_t(p.size() >> 8));
			d.insert(d.end(), p.begin(), p.end());
		}
		d.push_back(0);
		d.push_back(0);
		while (d.size() % 32) {
			d.push_back(0);
		}
		datas.push_back(d);
	}
	std::vector<uint8_t> headers;
	size_t offset = 0;
	for (size_t i = 0; i < datas.size(); i++) {
		// rate index 9 (48 kHz), channels index 1 (stereo), one extra chunk: Vorbis setup id + 4 filler bytes
		const uint64_t mode = 1 | (9ull << 1) | (1ull << 5) | (uint64_t(offset / 32) << 7) | (spec.frames[i] << 34);
		put64(headers, mode);
		put32(headers, 0u | (8u << 1) | (11u << 25));
		put32(headers, spec.setup);
		put32(headers, 0);
		offset += datas[i].size();
	}
	std::vector<uint8_t> names;
	size_t at = 4 * spec.names.size();
	for (const auto &n : spec.names) {
		put32(names, uint32_t(at));
		at += n.size() + 1;
	}
	for (const auto &n : spec.names) {
		names.insert(names.end(), n.begin(), n.end());
		names.push_back(0);
	}
	while (names.size() % 4) {
		names.push_back(0);
	}
	std::vector<uint8_t> out = { 'F', 'S', 'B', '5' };
	put32(out, 1);
	put32(out, uint32_t(datas.size()));
	put32(out, uint32_t(headers.size()));
	put32(out, uint32_t(names.size()));
	put32(out, uint32_t(offset));
	put32(out, fsb::CODEC_VORBIS);
	while (out.size() < 0x3C) {
		out.push_back(0);
	}
	out.insert(out.end(), headers.begin(), headers.end());
	out.insert(out.end(), names.begin(), names.end());
	for (const auto &d : datas) {
		out.insert(out.end(), d.begin(), d.end());
	}
	return out;
}

void test_parse_and_stream() {
	Spec spec;
	spec.packets = { { packet(true, 40), packet(true, 33), packet(false, 7), packet(true, 50) },
		{ packet(false, 9), packet(false, 9) } };
	// granules: 0, 1024, 1024+512+64=1600, 1600+64+512=2176; the container says 2000 frames
	spec.frames = { 2000, 64 };
	spec.names = { "zap_a", "click" };
	std::vector<uint8_t> bank = { 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'F', 'E', 'V', ' ' };
	const std::vector<uint8_t> fsb = build(spec);
	bank.insert(bank.end(), fsb.begin(), fsb.end());
	size_t at = 0;
	CHECK(fsb::find_fsb5(bank.data(), bank.size(), at) && at == 12);
	fsb::Container c;
	std::string error;
	CHECK(fsb::parse_fsb5(bank.data() + at, bank.size() - at, c, error));
	CHECK(c.codec == fsb::CODEC_VORBIS && c.samples.size() == 2 && c.size == fsb.size());
	CHECK(c.samples[0].name == "zap_a" && c.samples[1].name == "click");
	CHECK(c.samples[0].rate == 48000 && c.samples[0].channels == 2 && c.samples[0].frames == 2000);
	CHECK(c.samples[0].has_setup && c.samples[0].setup_id == 0xC4C30A29u && fsb::known_vorbis_setup(0xC4C30A29u));
	fsb::VorbisStream v;
	CHECK(fsb::vorbis_stream(bank.data() + at, bank.size() - at, c.samples[0], v, error));
	CHECK(v.headers.size() == 3 && v.packets.size() == 4);
	CHECK(v.headers[0].size() == 30 && v.headers[0][0] == 1 && v.headers[0][11] == 2);
	CHECK(v.headers[0][28] == (8 | 11 << 4) && v.headers[0][29] == 1); // 256 and 2048 sample blocks
	CHECK(v.headers[1][0] == 3 && v.headers[2][0] == 5);
	CHECK(v.granules[0] == 0 && v.granules[1] == 1024 && v.granules[2] == 1600);
	CHECK(v.granules[3] == 2000); // trimmed to the container's length
	CHECK(v.packets[1].size() == 33);
	CHECK(fsb::vorbis_stream(bank.data() + at, bank.size() - at, c.samples[1], v, error));
	CHECK(v.packets.size() == 2 && v.granules[1] == 64); // 64 + 64, trimmed to 64
}

void test_damage() {
	Spec spec;
	spec.packets = { { packet(true, 40), packet(true, 40) } };
	spec.frames = { 1000 };
	spec.names = { "a" };
	std::vector<uint8_t> fsb = build(spec);
	fsb::Container c;
	std::string error;
	CHECK(!fsb::parse_fsb5(fsb.data(), 20, c, error));
	// Cut through the second packet: the sample still lists, the stream reports the cut.
	const size_t cut = fsb.size() - 40;
	CHECK(fsb::parse_fsb5(fsb.data(), cut, c, error) && c.samples[0].end == cut);
	fsb::VorbisStream v;
	CHECK(!fsb::vorbis_stream(fsb.data(), cut, c.samples[0], v, error));
	CHECK(error.find("past the end") != std::string::npos);
	spec.setup = 0x12345678u;
	fsb = build(spec);
	CHECK(fsb::parse_fsb5(fsb.data(), fsb.size(), c, error));
	CHECK(!fsb::vorbis_stream(fsb.data(), fsb.size(), c.samples[0], v, error));
	CHECK(error == "unknown Vorbis setup id 12345678");
	std::vector<uint8_t> bad = fsb;
	bad[4] = 7; // version
	CHECK(!fsb::parse_fsb5(bad.data(), bad.size(), c, error));
}

} // namespace

int main() {
	test_parse_and_stream();
	test_damage();
	printf("fsb tests OK\n");
	return 0;
}
