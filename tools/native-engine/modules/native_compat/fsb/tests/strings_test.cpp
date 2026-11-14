// Host tests for the clean-room path table reader, on a hand-built synthetic table (no game files).
// Usage: strings_test [bank]       with a bank, also prints its paths and GUIDs
//        strings_test --write out  writes the synthetic bank (the engine test's fixture)
#include "../strings.h"

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

void put(std::vector<uint8_t> &b, uint32_t v, int bytes) {
	for (int i = 0; i < bytes; i++) {
		b.push_back(uint8_t(v >> (8 * i)));
	}
}

void node(std::vector<uint8_t> &b, uint32_t label, uint8_t key, uint32_t first, uint8_t count) {
	put(b, label, 3);
	b.push_back(key);
	put(b, first, 3);
	b.push_back(count);
}

fsb::Guid guid(uint32_t d1, uint8_t tail) {
	fsb::Guid g{};
	g[0] = uint8_t(d1);
	g[1] = uint8_t(d1 >> 8);
	g[2] = uint8_t(d1 >> 16);
	g[3] = uint8_t(d1 >> 24);
	g[6] = 0x00;
	g[7] = 0x40;
	g[8] = 0x80;
	g[15] = tail;
	return g;
}

// Paths "bus:/" (slot 0), "Event:/x" (slot 1), "bus:/a" (slot 2). Trie: root -> {"Event:/x", "bus:/"},
// "bus:/" -> {"", "a"}. Children sort by raw label bytes, so "Event:/x" comes first.
std::vector<uint8_t> table() {
	const uint32_t N = 0xFFFFFF;
	std::vector<uint8_t> b;
	put(b, 1, 4);
	put(b, 5 * 2 + 1, 2);
	put(b, 8, 2);
	// pool (preorder): "Event:/x\0" @0, "bus:/\0" @9, "a\0" @15
	node(b, N, 0, 1, 2); // 0 root
	node(b, 0, 'e', 1, 0); // 1 "Event:/x" -> slot 1
	node(b, 9, 'b', 3, 2); // 2 "bus:/"
	node(b, N, 0, 0, 0); // 3 "" -> slot 0
	node(b, 15, 'a', 2, 0); // 4 "a" -> slot 2
	put(b, 3 * 2 + 1, 2);
	put(b, 16, 2);
	for (uint32_t d1 : { 1u, 2u, 3u }) {
		fsb::Guid g = guid(d1, uint8_t(d1));
		b.insert(b.end(), g.begin(), g.end());
	}
	const char pool[] = "Event:/x\0bus:/\0a";
	put(b, sizeof(pool), 2);
	b.insert(b.end(), pool, pool + sizeof(pool));
	put(b, 3, 2);
	for (uint32_t n : { 3u, 1u, 4u }) {
		put(b, n, 3);
	}
	put(b, 5, 2);
	for (uint32_t p : { N, 0u, 0u, 2u, 2u }) {
		put(b, p, 3);
	}
	return b;
}

std::vector<uint8_t> bank(const std::vector<uint8_t> &stdt) {
	std::vector<uint8_t> list = { 'P', 'R', 'O', 'J' };
	const char *other = "BNKI";
	list.insert(list.end(), other, other + 4);
	put(list, 3, 4);
	list.insert(list.end(), { 1, 2, 3, 0 }); // odd size, padded
	list.insert(list.end(), { 'S', 'T', 'D', 'T' });
	put(list, uint32_t(stdt.size()), 4);
	list.insert(list.end(), stdt.begin(), stdt.end());
	if (stdt.size() & 1) {
		list.push_back(0);
	}
	std::vector<uint8_t> riff = { 'R', 'I', 'F', 'F' };
	put(riff, uint32_t(4 + 8 + list.size()), 4);
	riff.insert(riff.end(), { 'F', 'E', 'V', ' ', 'L', 'I', 'S', 'T' });
	put(riff, uint32_t(list.size()), 4);
	riff.insert(riff.end(), list.begin(), list.end());
	return riff;
}

void synthetic() {
	const std::vector<uint8_t> b = bank(table());
	fsb::PathTable t;
	std::string error;
	CHECK(fsb::read_path_table(b.data(), b.size(), t, error));
	CHECK(t.version == 1 && t.paths.size() == 3);
	CHECK(t.paths[0] == "bus:/" && t.paths[1] == "Event:/x" && t.paths[2] == "bus:/a");
	CHECK(t.find_path("bus:/a") == 2 && t.find_path("event:/X") == 1 && t.find_path("bus:/b") == -1);
	CHECK(t.find_guid(guid(2, 2)) == 1 && t.find_guid(guid(2, 9)) == -1);
	CHECK(fsb::guid_text(guid(0x12345678, 0xAB)) == "{12345678-0000-4000-8000-0000000000ab}");
	fsb::Guid back{};
	CHECK(fsb::parse_guid_text("{12345678-0000-4000-8000-0000000000ab}", back) && back == guid(0x12345678, 0xAB));
	CHECK(!fsb::parse_guid_text("{1234}", back));

	// Every single-byte corruption either still parses to the same shape or fails cleanly.
	const std::vector<uint8_t> good = table();
	for (size_t i = 0; i < good.size(); i++) {
		for (uint8_t v : { uint8_t(0), uint8_t(0xFF), uint8_t(good[i] ^ 1) }) {
			std::vector<uint8_t> bad = good;
			bad[i] = v;
			fsb::PathTable u;
			if (fsb::parse_path_table(bad.data(), bad.size(), u, error)) {
				CHECK(u.paths.size() == u.guids.size());
			}
		}
	}
	// Truncations fail.
	for (size_t n = 0; n < good.size(); n++) {
		fsb::PathTable u;
		CHECK(!fsb::parse_path_table(good.data(), n, u, error));
	}
}

} // namespace

int main(int argc, char **argv) {
	synthetic();
	if (argc > 2 && strcmp(argv[1], "--write") == 0) {
		const std::vector<uint8_t> b = bank(table());
		FILE *f = fopen(argv[2], "wb");
		CHECK(f && fwrite(b.data(), 1, b.size(), f) == b.size());
		fclose(f);
	} else if (argc > 1) {
		FILE *f = fopen(argv[1], "rb");
		CHECK(f);
		std::vector<uint8_t> data;
		uint8_t buf[65536];
		size_t n;
		while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
			data.insert(data.end(), buf, buf + n);
		}
		fclose(f);
		fsb::PathTable t;
		std::string error;
		if (!fsb::read_path_table(data.data(), data.size(), t, error)) {
			fprintf(stderr, "%s\n", error.c_str());
			return 1;
		}
		for (size_t i = 0; i < t.paths.size(); i++) {
			printf("%s  %s\n", fsb::guid_text(t.guids[i]).c_str(), t.paths[i].c_str());
		}
	}
	printf("strings tests OK\n");
	return 0;
}
