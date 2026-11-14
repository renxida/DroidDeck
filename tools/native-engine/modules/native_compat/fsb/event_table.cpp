// Clean-room code for DroidDeck/Kestrel; see event_table.h.
#include "event_table.h"

#include <cstring>
#include <unordered_set>

namespace fsb {
namespace {

uint32_t rd16(const uint8_t *p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8; }
uint32_t rd32(const uint8_t *p) { return rd16(p) | rd16(p + 2) << 16; }
float rdf(const uint8_t *p) {
	const uint32_t v = rd32(p);
	float f;
	memcpy(&f, &v, 4);
	return f;
}

Guid guid_at(const uint8_t *p) {
	Guid g;
	memcpy(g.data(), p, 16);
	return g;
}

struct Chunk {
	char id[4];
	const uint8_t *body;
	size_t size;
};

struct Object {
	char type[4];
	std::vector<Chunk> chunks;
	const Chunk *find(const char id[4]) const {
		for (const Chunk &c : chunks) {
			if (memcmp(c.id, id, 4) == 0) {
				return &c;
			}
		}
		return nullptr;
	}
};

// Calls f(id, body, size, is_list, list_type) for each chunk in [at, end). False on a chunk running past end.
// With `lenient`, a chunk running past end is cut to end and is the last one visited (a file cut short
// after its metadata); otherwise it fails the walk.
template <typename F>
bool each_chunk(const uint8_t *d, size_t at, size_t end, F f, bool lenient = false) {
	while (at + 8 <= end) {
		size_t sz = rd32(d + at + 4);
		const size_t b = at + 8;
		if (sz > end - b) {
			if (!lenient) {
				return false;
			}
			sz = end - b;
		}
		const bool list = memcmp(d + at, "LIST", 4) == 0 && sz >= 4;
		if (!f(d + at, d + b + (list ? 4 : 0), sz - (list ? 4 : 0), list, list ? d + b : nullptr)) {
			return false;
		}
		at = b + sz + (sz & 1);
	}
	return true;
}

using GuidSet = std::unordered_set<Guid, GuidHash>;

// GUIDs from `set` found anywhere in the payload, in order, each once.
void scan(const uint8_t *p, size_t n, const GuidSet &set, std::vector<Guid> &out) {
	for (size_t i = 0; i + 16 <= n; i++) {
		const Guid g = guid_at(p + i);
		if (set.count(g)) {
			bool have = false;
			for (const Guid &h : out) {
				have = have || h == g;
			}
			if (!have) {
				out.push_back(g);
			}
			i += 15;
		}
	}
}

// Instruments placed inside a loop region of a timeline block (layout in event_table.h).
void timeline_loops(const uint8_t *p, size_t n, std::vector<Guid> &out) {
	size_t at = 18;
	if (n < at + 4) {
		return;
	}
	struct Placement {
		Guid g;
		uint32_t start, end;
	};
	std::vector<Placement> placed;
	std::vector<std::pair<uint32_t, uint32_t>> regions;
	bool first = true;
	while (at + 2 <= n) {
		const uint32_t tag = rd16(p + at);
		at += 2;
		const size_t count = tag >> 1;
		if (tag & 1) {
			if (at + 2 > n) {
				break;
			}
			const size_t size = rd16(p + at);
			at += 2;
			if (size == 0 || count > (n - at) / size) {
				break;
			}
			if (first && size >= 24) {
				for (size_t i = 0; i < count; i++) {
					const uint8_t *e = p + at + size * i;
					const uint32_t start = rd32(e + 16), length = rd32(e + 20);
					placed.push_back({ guid_at(e), start, start + length });
				}
			}
			at += size * count;
		} else {
			bool ok = true;
			for (size_t i = 0; i < count && ok; i++) {
				if (at + 2 > n) {
					ok = false;
					break;
				}
				const size_t size = rd16(p + at);
				at += 2;
				if (size > n - at) {
					ok = false;
					break;
				}
				if (size == 26) {
					regions.emplace_back(rd32(p + at + 16), rd32(p + at + 22));
				}
				at += size;
			}
			if (!ok) {
				break;
			}
		}
		first = false;
	}
	for (const Placement &pl : placed) {
		for (const auto &r : regions) {
			if (pl.start < r.second && r.first < pl.end) { // overlaps the region
				out.push_back(pl.g);
				break;
			}
		}
	}
}

} // namespace

bool read_event_table(const uint8_t *data, size_t length, EventTable &out, std::string &error) {
	out = EventTable();
	if (length < 12 || memcmp(data, "RIFF", 4) != 0) {
		error = "not a RIFF file";
		return false;
	}
	// LIST PROJ is a direct child of the RIFF; take it even when the file stops before its declared end.
	size_t proj = 0, proj_size = 0;
	for (size_t at = 12; at + 12 <= length;) {
		const size_t sz = rd32(data + at + 4);
		if (memcmp(data + at, "LIST", 4) == 0 && memcmp(data + at + 8, "PROJ", 4) == 0) {
			proj = at + 8;
			proj_size = sz > length - proj ? length - proj : sz;
			break;
		}
		if (sz > length - at - 8) {
			break;
		}
		at += 8 + sz + (sz & 1);
	}
	if (proj_size < 4) {
		return true; // no PROJ: nothing to read
	}
	std::unordered_map<Guid, Object, GuidHash> objects;
	const bool ok = each_chunk(data, proj + 4, proj + proj_size, [&](const uint8_t *, const uint8_t *body, size_t size, bool list, const uint8_t *) {
		if (!list) {
			return true;
		}
		// An object list: LIST objects, or bare WAV chunks.
		return each_chunk(data, size_t(body - data), size_t(body - data) + size, [&](const uint8_t *head, const uint8_t *ob, size_t os, bool olist, const uint8_t *otype) {
			if (!olist) {
				if (memcmp(head, "WAV ", 4) == 0 && os >= 26) {
					out.waves[guid_at(ob)] = rd32(ob + 22);
				}
				return true;
			}
			Object o;
			memcpy(o.type, otype, 4);
			const bool inner = each_chunk(data, size_t(ob - data), size_t(ob - data) + os, [&](const uint8_t *ch, const uint8_t *cb, size_t cs, bool, const uint8_t *) {
				Chunk c;
				memcpy(c.id, ch, 4);
				c.body = cb;
				c.size = cs;
				o.chunks.push_back(c);
				return true;
			});
			if (!inner) {
				return true; // an object cut short: skip it
			}
			if (!o.chunks.empty() && o.chunks.front().size >= 16 && memcmp(o.chunks.front().id, "LIST", 4) != 0) {
				objects.emplace(guid_at(o.chunks.front().body), std::move(o));
			}
			return true;
		}, true);
	}, true);
	if (!ok) {
		error = "bank object list runs past its end";
		out = EventTable();
		return false;
	}
	GuidSet instrument_ids, sheet_ids;
	for (const auto &kv : objects) {
		const Object &o = kv.second;
		const bool wait = memcmp(o.type, "WAIT", 4) == 0, muit = memcmp(o.type, "MUIT", 4) == 0;
		if (memcmp(o.type, "PMLO", 4) == 0) {
			sheet_ids.insert(kv.first);
		}
		if (!wait && !muit) {
			continue;
		}
		EventTable::Instrument in;
		in.multi = muit;
		if (const Chunk *c = o.find("INST"); c && c->size >= 20) {
			in.volume_db = rdf(c->body + 16);
		}
		if (wait) {
			const Chunk *c = o.find("WAIB");
			if (!c || c->size < 32) {
				continue;
			}
			in.wave = guid_at(c->body + 16);
		} else {
			const Chunk *c = o.find("PLST");
			if (!c || c->size < 10) {
				continue;
			}
			const uint32_t tagged = rd16(c->body + 8);
			const size_t count = tagged >> 1;
			if (count > 0 && (c->size < 12 || rd16(c->body + 10) != 20 || c->size < 12 + 20 * count)) {
				continue;
			}
			for (size_t i = 0; i < count; i++) {
				const uint8_t *e = c->body + 12 + 20 * i;
				in.playlist.emplace_back(guid_at(e), rdf(e + 16));
			}
		}
		instrument_ids.insert(kv.first);
		out.instruments.emplace(kv.first, std::move(in));
	}
	for (const auto &kv : objects) {
		const Object &o = kv.second;
		if (memcmp(o.type, "EVNT", 4) != 0) {
			continue;
		}
		const Chunk *b = o.find("EVTB");
		if (!b) {
			continue;
		}
		EventTable::Event ev;
		scan(b->body, b->size, instrument_ids, ev.instruments);
		if (b->size >= 48) {
			auto tl = objects.find(guid_at(b->body + 32));
			if (tl != objects.end()) {
				for (const Chunk &c : tl->second.chunks) {
					scan(c.body, c.size, instrument_ids, ev.instruments);
				}
				if (const Chunk *c = tl->second.find("TLNB")) {
					timeline_loops(c->body, c->size, ev.looped);
				}
			}
		}
		// Instruments that only a parameter sheet reaches play when nothing else would.
		if (ev.instruments.empty()) {
			std::vector<Guid> sheets;
			scan(b->body, b->size, sheet_ids, sheets);
			for (const Guid &s : sheets) {
				for (const Chunk &c : objects[s].chunks) {
					scan(c.body, c.size, instrument_ids, ev.instruments);
				}
			}
		}
		out.events.emplace(kv.first, std::move(ev));
	}
	return true;
}

namespace {

uint32_t next(uint32_t &s) {
	// xorshift32; a zero state would stay zero.
	if (s == 0) {
		s = 0x9e3779b9u;
	}
	s ^= s << 13;
	s ^= s >> 17;
	s ^= s << 5;
	return s;
}

void pick(const EventTable &t, const Guid &g, float volume_db, bool loop, uint32_t &rng, int depth, std::vector<Pick> &out) {
	if (t.waves.count(g)) {
		out.push_back({ g, volume_db, loop });
		return;
	}
	auto it = t.instruments.find(g);
	if (it == t.instruments.end() || depth > 8) {
		return;
	}
	const EventTable::Instrument &in = it->second;
	volume_db += in.volume_db;
	if (!in.multi) {
		out.push_back({ in.wave, volume_db, loop });
		return;
	}
	if (in.playlist.empty()) {
		return;
	}
	float total = 0;
	for (const auto &e : in.playlist) {
		total += e.second > 0 ? e.second : 0;
	}
	size_t chosen = 0;
	if (total > 0) {
		const float r = float(next(rng) >> 8) / float(1u << 24) * total;
		float acc = 0;
		for (size_t i = 0; i < in.playlist.size(); i++) {
			acc += in.playlist[i].second > 0 ? in.playlist[i].second : 0;
			if (r < acc) {
				chosen = i;
				break;
			}
			chosen = i;
		}
	} else {
		chosen = next(rng) % in.playlist.size();
	}
	pick(t, in.playlist[chosen].first, volume_db, loop, rng, depth + 1, out);
}

} // namespace

bool resolve_event(const EventTable &table, const Guid &event, uint32_t &rng, std::vector<Pick> &out) {
	auto it = table.events.find(event);
	if (it == table.events.end()) {
		return false;
	}
	const EventTable::Event &ev = it->second;
	for (const Guid &g : ev.instruments) {
		bool loop = false;
		for (const Guid &l : ev.looped) {
			loop = loop || l == g;
		}
		pick(table, g, 0, loop, rng, 0, out);
	}
	return true;
}

} // namespace fsb
