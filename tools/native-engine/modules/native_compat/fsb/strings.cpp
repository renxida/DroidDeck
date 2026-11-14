// Clean-room code for DroidDeck/Kestrel; see strings.h.
#include "strings.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace fsb {
namespace {

constexpr uint32_t NONE24 = 0xFFFFFF;

uint32_t rd16(const uint8_t *p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8; }
uint32_t rd24(const uint8_t *p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16; }
uint32_t rd32(const uint8_t *p) { return rd24(p) | uint32_t(p[3]) << 24; }

std::string lower(std::string s) {
	for (char &c : s) {
		if (c >= 'A' && c <= 'Z') {
			c = char(c - 'A' + 'a');
		}
	}
	return s;
}

bool search(const uint8_t *d, size_t start, size_t end, const char id[4], size_t &body, size_t &size, int depth) {
	size_t at = start;
	while (at + 8 <= end) {
		const size_t sz = rd32(d + at + 4);
		const size_t b = at + 8;
		if (sz > end - b) {
			return false;
		}
		if (memcmp(d + at, id, 4) == 0) {
			body = b;
			size = sz;
			return true;
		}
		if (depth < 16 && sz >= 4 && (memcmp(d + at, "RIFF", 4) == 0 || memcmp(d + at, "LIST", 4) == 0) &&
				search(d, b + 4, b + sz, id, body, size, depth + 1)) {
			return true;
		}
		at = b + sz + (sz & 1);
	}
	return false;
}

struct Cursor {
	const uint8_t *d;
	size_t at, end;
	bool ok = true;
	const uint8_t *take(size_t n) {
		if (!ok || n > end - at) {
			ok = false;
			return nullptr;
		}
		const uint8_t *p = d + at;
		at += n;
		return p;
	}
	uint32_t u16() {
		const uint8_t *p = take(2);
		return p ? rd16(p) : 0;
	}
};

} // namespace

std::string guid_text(const Guid &g) {
	char buf[40];
	snprintf(buf, sizeof(buf), "{%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x}", unsigned(rd32(g.data())),
			unsigned(rd16(g.data() + 4)), unsigned(rd16(g.data() + 6)), g[8], g[9], g[10], g[11], g[12], g[13], g[14],
			g[15]);
	return buf;
}

bool parse_guid_text(const std::string &text, Guid &out) {
	std::string hex;
	for (char c : text) {
		if (isxdigit(static_cast<unsigned char>(c))) {
			hex += c;
		} else if (c != '{' && c != '}' && c != '-') {
			return false;
		}
	}
	if (hex.size() != 32) {
		return false;
	}
	uint8_t raw[16];
	for (int i = 0; i < 16; i++) {
		raw[i] = uint8_t(strtoul(hex.substr(i * 2, 2).c_str(), nullptr, 16));
	}
	// Text order -> stored order: the first three fields are little-endian on disk.
	const int order[16] = { 3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15 };
	for (int i = 0; i < 16; i++) {
		out[i] = raw[order[i]];
	}
	return true;
}

bool find_riff_chunk(const uint8_t *data, size_t length, const char id[4], size_t &body, size_t &size) {
	if (length < 12 || memcmp(data, "RIFF", 4) != 0) {
		return false;
	}
	const size_t end = std::min(length, size_t(8) + rd32(data + 4));
	return search(data, 12, end, id, body, size, 0);
}

int PathTable::find_path(const std::string &path) const {
	auto it = by_path.find(path);
	if (it != by_path.end()) {
		return it->second;
	}
	auto lt = by_lower_path.find(lower(path));
	return lt == by_lower_path.end() ? -1 : lt->second;
}

int PathTable::find_guid(const Guid &g) const {
	auto it = std::lower_bound(guids.begin(), guids.end(), g, [](const Guid &a, const Guid &b) {
		const uint32_t ka = rd32(a.data()), kb = rd32(b.data());
		return ka != kb ? ka < kb : memcmp(a.data(), b.data(), 16) < 0;
	});
	for (; it != guids.end() && rd32(it->data()) == rd32(g.data()); ++it) {
		if (*it == g) {
			return int(it - guids.begin());
		}
	}
	return -1;
}

bool parse_path_table(const uint8_t *data, size_t length, PathTable &out, std::string &error) {
	out = PathTable();
	Cursor c{ data, 0, length };
	const uint8_t *v = c.take(4);
	if (!v) {
		error = "path table too short";
		return false;
	}
	out.version = rd32(v);
	auto record_array = [&](uint32_t want_size, uint32_t &count) -> const uint8_t * {
		const uint32_t tagged = c.u16();
		const uint32_t esize = c.u16();
		if (!c.ok || !(tagged & 1) || esize != want_size) {
			c.ok = false;
			return nullptr;
		}
		count = tagged >> 1;
		return c.take(size_t(count) * esize);
	};
	uint32_t n_nodes = 0, n_guids = 0;
	const uint8_t *nodes = record_array(8, n_nodes);
	const uint8_t *guid_bytes = record_array(16, n_guids);
	const uint32_t pool_len = c.u16();
	const uint8_t *pool = c.take(pool_len);
	const uint32_t n_leaves = c.u16();
	const uint8_t *leaves = c.take(size_t(n_leaves) * 3);
	const uint32_t n_parents = c.u16();
	const uint8_t *parents = c.take(size_t(n_parents) * 3);
	if (!c.ok || !nodes || c.at != length) {
		error = c.ok ? "path table has " + std::to_string(length - c.at) + " unexplained bytes" :
					   "path table sections out of range";
		return false;
	}
	if (n_nodes == 0 || n_leaves != n_guids || n_parents != n_nodes || rd24(parents) != NONE24) {
		error = "path table counts disagree";
		return false;
	}
	out.guids.resize(n_guids);
	for (uint32_t i = 0; i < n_guids; i++) {
		memcpy(out.guids[i].data(), guid_bytes + 16 * i, 16);
	}
	auto label = [&](uint32_t off, std::string &s) {
		if (off == NONE24) {
			s.clear();
			return true;
		}
		if (off >= pool_len) {
			return false;
		}
		const void *nul = memchr(pool + off, 0, pool_len - off);
		if (!nul) {
			return false;
		}
		s.assign(reinterpret_cast<const char *>(pool + off), static_cast<const uint8_t *>(nul) - (pool + off));
		return true;
	};
	out.paths.assign(n_guids, std::string());
	std::vector<uint8_t> seen(n_nodes, 0), slot_seen(n_guids, 0);
	struct Item {
		uint32_t node;
		uint32_t parent;
		std::string prefix;
	};
	std::vector<Item> stack{ { 0, NONE24, std::string() } };
	size_t visited = 0;
	while (!stack.empty()) {
		Item it = std::move(stack.back());
		stack.pop_back();
		if (it.node >= n_nodes || seen[it.node]) {
			error = "node " + std::to_string(it.node) + " out of range or reached twice";
			return false;
		}
		seen[it.node] = 1;
		visited++;
		const uint8_t *n = nodes + 8 * size_t(it.node);
		std::string lab;
		if (!label(rd24(n), lab)) {
			error = "bad label at node " + std::to_string(it.node);
			return false;
		}
		if (it.parent != NONE24) {
			const uint8_t want_key = lab.empty() ? 0 : uint8_t(lower(lab.substr(0, 1))[0]);
			if (rd24(parents + 3 * size_t(it.node)) != it.parent || n[3] != want_key) {
				error = "parent or key disagrees at node " + std::to_string(it.node);
				return false;
			}
		}
		const std::string path = it.prefix + lab;
		const uint32_t first = rd24(n + 4);
		const uint32_t count = n[7];
		if (count == 0) {
			if (first >= n_guids || slot_seen[first] || rd24(leaves + 3 * size_t(first)) != it.node) {
				error = "leaf " + std::to_string(it.node) + " has a bad GUID slot";
				return false;
			}
			slot_seen[first] = 1;
			out.paths[first] = path;
		} else {
			if (first + count > n_nodes) {
				error = "children of node " + std::to_string(it.node) + " out of range";
				return false;
			}
			for (uint32_t k = first + count; k-- > first;) {
				stack.push_back({ k, it.node, path });
			}
		}
	}
	if (visited != n_nodes) {
		error = std::to_string(n_nodes - visited) + " nodes unreachable";
		return false;
	}
	for (uint32_t i = 0; i < n_guids; i++) {
		if (!slot_seen[i]) {
			error = "GUID slot " + std::to_string(i) + " has no path";
			return false;
		}
		out.by_path.emplace(out.paths[i], int(i));
		out.by_lower_path.emplace(lower(out.paths[i]), int(i));
	}
	return true;
}

bool read_path_table(const uint8_t *data, size_t length, PathTable &out, std::string &error) {
	size_t body = 0, size = 0;
	if (!find_riff_chunk(data, length, "STDT", body, size)) {
		error = "no path table (STDT) in the file";
		return false;
	}
	return parse_path_table(data + body, size, out, error);
}

} // namespace fsb
