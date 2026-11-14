// Clean-room reader for the path table of a sound bank's strings bank (the "STDT" chunk), worked out from
// the game's own Master.strings.bank bytes alone. No FMOD code or documentation of it was used.
//
// Layout (all little-endian, u24 = 3 bytes, 0xFFFFFF = none):
//   u32 version (1)
//   u16 nodes*2+1, u16 8;  node[nodes]   = u24 label offset, u8 lower-cased first label byte,
//                                          u24 first child (inner) or GUID slot (leaf), u8 child count
//   u16 guids*2+1, u16 16; guid[guids]   sorted by their first u32
//   u16 pool length;       pool          NUL-terminated labels
//   u16 leaves;            u24[leaves]   GUID slot -> node
//   u16 nodes;             u24[nodes]    node -> parent
// The nodes form a radix trie whose edges carry whole labels; a path that ends where longer ones continue
// gets an empty-label leaf child.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace fsb {

using Guid = std::array<uint8_t, 16>;

// {xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}: first field a LE u32, next two LE u16s, last 8 bytes as stored.
std::string guid_text(const Guid &g);
bool parse_guid_text(const std::string &text, Guid &out);

// Finds a chunk by id anywhere inside the file's RIFF/LIST tree. Returns false if none.
bool find_riff_chunk(const uint8_t *data, size_t length, const char id[4], size_t &body, size_t &size);

struct PathTable {
	uint32_t version = 0;
	std::vector<std::string> paths; // indexed by GUID slot
	std::vector<Guid> guids;
	std::unordered_map<std::string, int> by_path;
	std::unordered_map<std::string, int> by_lower_path;

	int find_path(const std::string &path) const; // exact, then ignoring ASCII case; -1 if absent
	int find_guid(const Guid &g) const; // binary search on the sorted table; -1 if absent
};

// Reads the STDT chunk body. Checks the structure as it goes (every node reached once, parents agree,
// leaves cover every GUID slot) and fails on any mismatch.
bool parse_path_table(const uint8_t *data, size_t length, PathTable &out, std::string &error);

// Finds and reads the STDT chunk of a whole bank file.
bool read_path_table(const uint8_t *data, size_t length, PathTable &out, std::string &error);

} // namespace fsb
