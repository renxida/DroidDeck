// Clean-room code for DroidDeck/Kestrel; see event_table.h.
#include "event_table.h"

#include <cstring>
#include <functional>
#include <map>
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

// The arrays of a timeline block (layout in event_table.h): placements, markers and loop regions.
struct TimelineParts {
	struct Placement {
		Guid g;
		uint32_t start, end;
	};
	std::vector<Placement> placed;
	std::unordered_map<Guid, uint32_t, GuidHash> marks; // marker or loop region -> its position
	std::vector<std::pair<Guid, std::pair<uint32_t, uint32_t>>> regions; // loop region -> (start, end field)
};

void read_timeline_arrays(const uint8_t *p, size_t n, TimelineParts &out) {
	size_t at = 16;
	while (at + 2 <= n) {
		const uint32_t tag = rd16(p + at);
		at += 2;
		const size_t count = tag >> 1;
		if (tag & 1) {
			if (count == 0) {
				continue; // an empty fixed array has no element size
			}
			if (at + 2 > n) {
				break;
			}
			const size_t size = rd16(p + at);
			at += 2;
			if (size == 0 || count > (n - at) / size) {
				break;
			}
			if (size >= 24) {
				for (size_t i = 0; i < count; i++) {
					const uint8_t *e = p + at + size * i;
					const uint32_t start = rd32(e + 16), length = rd32(e + 20);
					out.placed.push_back({ guid_at(e), start, start + length });
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
				if (size >= 22) {
					const Guid g = guid_at(p + at);
					out.marks[g] = rd32(p + at + 16);
					if (size == 26) {
						out.regions.push_back({ g, { rd32(p + at + 16), rd32(p + at + 22) } });
					}
				}
				at += size;
			}
			if (!ok) {
				break;
			}
		}
	}
}

// Conditions in a block of `n` bytes: each known parameter GUID found starts an entry (layout in
// event_table.h).
void read_conditions(const uint8_t *p, size_t n, const GuidSet &params, std::vector<EventTable::Condition> &out) {
	for (size_t i = 0; i + 28 <= n; i++) {
		const Guid g = guid_at(p + i);
		if (params.count(g)) {
			out.push_back({ g, rdf(p + i + 20), rdf(p + i + 24) });
			i += 27;
		}
	}
}

// The condition block of a chunk at `at`: a u32 byte count, then the entries.
void read_condition_block(const uint8_t *p, size_t n, size_t at, const GuidSet &params,
		std::vector<EventTable::Condition> &out) {
	if (at + 4 > n) {
		return;
	}
	const size_t size = rd32(p + at);
	if (size > n - at - 4) {
		return;
	}
	read_conditions(p + at + 4, size, params, out);
}

// What the simple player needs from a timeline: which of the event's instruments loop, start late, or sit
// past a loop region and so never play while it loops. A loop region ends where a loop-end transition points
// back to it, else at its second field.
void apply_timeline(const TimelineParts &parts, const std::unordered_map<Guid, uint32_t, GuidHash> &loop_ends,
		EventTable::Event &ev) {
	std::vector<std::pair<uint32_t, uint32_t>> regions;
	for (const auto &r : parts.regions) {
		auto e = loop_ends.find(r.first);
		regions.emplace_back(r.second.first, e != loop_ends.end() ? e->second : r.second.second);
	}
	for (const auto &pl : parts.placed) {
		bool loops = false, past = false;
		for (const auto &r : regions) {
			loops = loops || (pl.start < r.second && r.first < pl.end); // overlaps the region
			past = past || (r.first < r.second && r.second <= pl.start);
		}
		if (past && !loops) {
			ev.unreachable.push_back(pl.g);
			continue;
		}
		if (loops) {
			ev.looped.push_back(pl.g);
		}
		if (pl.start > 0) {
			ev.delays.emplace_back(pl.g, float(pl.start) / EventTable::TIMELINE_RATE);
		}
	}
}

} // namespace

// Diagnostics: the types of the objects an event reaches through GUID references (a few levels deep), so a
// silent event's log line says which kinds of object it uses.
static std::string reach_types(const std::unordered_map<Guid, Object, GuidHash> &objects, const GuidSet &all, const Guid &root) {
	GuidSet seen{ root };
	std::vector<Guid> level{ root };
	std::map<std::string, int> counts;
	for (int depth = 0; depth < 4 && !level.empty() && seen.size() < 512; depth++) {
		std::vector<Guid> next_level;
		for (const Guid &g : level) {
			auto it = objects.find(g);
			if (it == objects.end()) {
				continue;
			}
			std::vector<Guid> found;
			for (const Chunk &c : it->second.chunks) {
				scan(c.body, c.size, all, found);
			}
			for (const Guid &f : found) {
				if (seen.insert(f).second) {
					next_level.push_back(f);
					counts[std::string(objects.at(f).type, 4)]++;
				}
			}
		}
		level = std::move(next_level);
	}
	std::string s;
	for (const auto &kv : counts) {
		s += (s.empty() ? "" : " ") + kv.first + ":" + std::to_string(kv.second);
	}
	return s;
}

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
			// Chunks nested in lists inside the object (transitions sit in one) are flattened into its chunks.
			std::function<bool(const uint8_t *, size_t, int)> collect = [&](const uint8_t *at, size_t n, int depth) {
				return each_chunk(data, size_t(at - data), size_t(at - data) + n, [&](const uint8_t *ch, const uint8_t *cb, size_t cs, bool clist, const uint8_t *) {
					if (clist) {
						return depth < 4 ? collect(cb, cs, depth + 1) : true;
					}
					Chunk c;
					memcpy(c.id, ch, 4);
					c.body = cb;
					c.size = cs;
					o.chunks.push_back(c);
					return true;
				});
			};
			const bool inner = collect(ob, os, 0);
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
	GuidSet instrument_ids, sheet_ids, all_ids, param_ids;
	for (const auto &kv : objects) {
		all_ids.insert(kv.first);
		if (memcmp(kv.second.type, "PARM", 4) == 0) {
			param_ids.insert(kv.first);
		}
	}
	// Parameters: names, ranges, defaults and labels.
	for (const Guid &g : param_ids) {
		const Chunk *c = objects[g].find("PRMB");
		if (!c || c->size < 26) {
			continue;
		}
		const size_t len = rd16(c->body + 24);
		if (26 + len + 12 > c->size) {
			continue;
		}
		EventTable::Param prm;
		prm.name.assign(reinterpret_cast<const char *>(c->body + 26), len);
		const uint8_t *f = c->body + 26 + len;
		prm.min = rdf(f);
		prm.max = rdf(f + 4);
		prm.value = rdf(f + 8);
		if (!(prm.value >= prm.min && prm.value <= prm.max)) {
			prm.value = prm.min;
		}
		// Labels: the last variable array whose elements are (u16 n, n bytes), with sizes n + 2.
		for (size_t at = 26 + len + 12; at + 2 <= c->size; at++) {
			const size_t tag = rd16(c->body + at);
			if (tag == 0 || (tag & 1) || tag / 2 > 64) {
				continue;
			}
			std::vector<std::string> labels;
			size_t q = at + 2;
			bool ok = true;
			for (size_t i = 0; i < tag / 2 && ok; i++) {
				ok = q + 4 <= c->size && rd16(c->body + q) == rd16(c->body + q + 2) + 2 &&
						q + 2 + rd16(c->body + q) <= c->size && rd16(c->body + q + 2) > 0;
				if (ok) {
					labels.emplace_back(reinterpret_cast<const char *>(c->body + q + 4), rd16(c->body + q + 2));
					q += 2 + rd16(c->body + q);
				}
			}
			if (ok && q == c->size) {
				prm.labels = std::move(labels);
				break;
			}
		}
		out.params.emplace(g, std::move(prm));
	}
	for (const auto &kv : objects) {
		const Object &o = kv.second;
		const bool wait = memcmp(o.type, "WAIT", 4) == 0, muit = memcmp(o.type, "MUIT", 4) == 0,
				   evit = memcmp(o.type, "EVIT", 4) == 0, spit = memcmp(o.type, "SPIT", 4) == 0;
		if (memcmp(o.type, "PMLO", 4) == 0) {
			sheet_ids.insert(kv.first);
		}
		if (!wait && !muit && !evit && !spit) {
			continue;
		}
		EventTable::Instrument in;
		in.multi = muit || spit;
		if (const Chunk *c = o.find("INST"); c && c->size >= 20) {
			in.volume_db = rdf(c->body + 16);
			in.loop = c->size >= 28 && rd32(c->body + 24) == 0xffffffffu;
			read_condition_block(c->body, c->size, 126, param_ids, in.conditions);
		}
		if (wait) {
			const Chunk *c = o.find("WAIB");
			if (!c || c->size < 32) {
				continue;
			}
			in.wave = guid_at(c->body + 16);
		} else if (evit) {
			const Chunk *c = o.find("EVIB");
			if (!c || c->size < 32) {
				continue;
			}
			in.nested = true;
			in.event = guid_at(c->body + 16);
		} else {
			if (spit) {
				const Chunk *c = o.find("SPIB");
				if (!c || c->size < 32) {
					continue;
				}
				in.scatter = true;
				in.spawn_min = rdf(c->body + 24);
				in.spawn_max = rdf(c->body + 28);
			}
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
				TimelineParts parts;
				if (const Chunk *c = tl->second.find("TLNB")) {
					read_timeline_arrays(c->body, c->size, parts);
				}
				std::unordered_map<Guid, uint32_t, GuidHash> loop_ends;
				for (const Chunk &c : tl->second.chunks) {
					if (memcmp(c.id, "TRNB", 4) != 0 || c.size < 48) {
						continue;
					}
					EventTable::Transition t;
					const Guid dest = guid_at(c.body + 16);
					t.start = rd32(c.body + 32);
					t.end = rd32(c.body + 36);
					t.kind = int(rd32(c.body + c.size - 4));
					t.probability = rdf(c.body + c.size - 8);
					if (c.size > 60) {
						read_condition_block(c.body, c.size - 8, 40, param_ids, t.conditions);
					}
					if (t.kind == EventTable::Transition::LOOP_END) {
						loop_ends[dest] = t.start;
					}
					auto m = parts.marks.find(dest);
					if (m == parts.marks.end()) {
						continue;
					}
					if (t.kind != EventTable::Transition::LOOP_END) {
						// Kind 4 (seen with one condition, on spans) acts as a transition region; anything else
						// unknown is read by its shape.
						t.kind = t.start == t.end ? EventTable::Transition::MARKER : EventTable::Transition::REGION;
					}
					t.destination = m->second;
					ev.transitions.push_back(std::move(t));
				}
				apply_timeline(parts, loop_ends, ev);
				for (const auto &pl : parts.placed) {
					auto in = out.instruments.find(pl.g);
					if (in == out.instruments.end()) {
						continue;
					}
					ev.placements.push_back({ pl.g, pl.start, pl.end - pl.start });
					ev.length = pl.end > ev.length ? pl.end : ev.length;
					ev.dynamic = ev.dynamic || !in->second.conditions.empty() || in->second.nested || in->second.scatter;
				}
				ev.dynamic = ev.dynamic || !ev.transitions.empty();
			}
		}
		// Instruments that only a parameter sheet reaches play when nothing else would.
		if (ev.instruments.empty()) {
			std::vector<Guid> sheets;
			scan(b->body, b->size, sheet_ids, sheets);
			for (const Guid &sh : sheets) {
				for (const Chunk &c : objects[sh].chunks) {
					scan(c.body, c.size, instrument_ids, ev.instruments);
				}
			}
		}
		ev.refs = reach_types(objects, all_ids, kv.first);
		out.events.emplace(kv.first, std::move(ev));
	}
	std::map<std::string, int> counts;
	for (const auto &kv : objects) {
		counts[std::string(kv.second.type, 4)]++;
	}
	for (const auto &kv : counts) {
		out.object_types += (out.object_types.empty() ? "" : " ") + kv.first + ":" + std::to_string(kv.second);
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

void pick(const EventTable &t, const Guid &g, float volume_db, bool loop, float delay, uint32_t &rng, int depth,
		std::vector<Pick> &out) {
	if (t.waves.count(g)) {
		out.push_back({ g, volume_db, loop, delay });
		return;
	}
	auto it = t.instruments.find(g);
	if (it == t.instruments.end() || depth > 8) {
		return;
	}
	const EventTable::Instrument &in = it->second;
	volume_db += in.volume_db;
	loop = loop || in.loop;
	if (in.nested) {
		Pick n{ {}, volume_db, loop, delay };
		n.nested = true;
		n.event = in.event;
		out.push_back(n); // the timeline player plays it; resolve_event drops it
		return;
	}
	if (!in.multi) {
		out.push_back({ in.wave, volume_db, loop, delay });
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
	pick(t, in.playlist[chosen].first, volume_db, loop, delay, rng, depth + 1, out);
}

} // namespace

bool pick_wave(const EventTable &table, const Guid &instrument, uint32_t &rng, Pick &out) {
	std::vector<Pick> picks;
	pick(table, instrument, 0, false, 0, rng, 0, picks);
	if (picks.empty()) {
		return false;
	}
	out = picks.front();
	return true;
}

bool resolve_event(const EventTable &table, const Guid &event, uint32_t &rng, std::vector<Pick> &out) {
	auto it = table.events.find(event);
	if (it == table.events.end()) {
		return false;
	}
	const EventTable::Event &ev = it->second;
	for (const Guid &g : ev.instruments) {
		bool loop = false, skip = false;
		float delay = 0;
		for (const Guid &l : ev.looped) {
			loop = loop || l == g;
		}
		for (const Guid &u : ev.unreachable) {
			skip = skip || u == g;
		}
		for (const auto &d : ev.delays) {
			delay = d.first == g ? d.second : delay;
		}
		if (!skip) {
			std::vector<Pick> picks;
			pick(table, g, 0, loop, delay, rng, 0, picks);
			for (const Pick &p : picks) {
				if (!p.nested) {
					out.push_back(p);
				}
			}
		}
	}
	return true;
}

} // namespace fsb
