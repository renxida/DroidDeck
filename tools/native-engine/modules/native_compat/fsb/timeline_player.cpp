// Clean-room timeline player; see timeline_player.h and the layout notes in event_table.h.
#include "timeline_player.h"

#include <algorithm>
#include <cstdio>

namespace fsb {

namespace {

uint32_t step(uint32_t &s) {
	if (s == 0) {
		s = 0x9e3779b9u;
	}
	s ^= s << 13;
	s ^= s >> 17;
	s ^= s << 5;
	return s;
}

constexpr int MAX_JUMPS_PER_STEP = 8;

} // namespace

TimelinePlayer::TimelinePlayer(const EventTable &p_table, const Guid &event, float p_volume_db, uint32_t seed) :
		table(p_table), volume_db(p_volume_db), rng(seed ? seed : 0x2545f491u) {
	auto it = table.events.find(event);
	if (it != table.events.end()) {
		ev = &it->second;
	}
}

TimelinePlayer::~TimelinePlayer() {}

float TimelinePlayer::random(float lo, float hi) {
	const float r = float(step(rng) >> 8) / float(1u << 24);
	return lo + (hi - lo) * r;
}

bool TimelinePlayer::holds(const std::vector<EventTable::Condition> &c, TimelineSink &sink) {
	return conditions_hold(c, [&](const Guid &p) { return sink.param(table, p); });
}

bool TimelinePlayer::wanted(size_t i, TimelineSink &sink) {
	const EventTable::Placement &pl = ev->placements[i];
	if (pos < pl.start || pos >= double(pl.start) + double(pl.length)) {
		return false;
	}
	auto it = table.instruments.find(pl.instrument);
	return it != table.instruments.end() && holds(it->second.conditions, sink);
}

void TimelinePlayer::start_voice(size_t i, TimelineSink &sink) {
	const EventTable::Placement &pl = ev->placements[i];
	const EventTable::Instrument &in = table.instruments.at(pl.instrument);
	const double offset = std::max(0.0, (pos - pl.start) / EventTable::TIMELINE_RATE);
	Voice v;
	v.placement = i;
	Pick p;
	if (in.scatter) {
		v.keep = true;
		v.next_spawn = random(0, std::max(in.spawn_min, 0.5f));
	} else if (pick_wave(table, pl.instrument, rng, p)) {
		if (p.nested) {
			// A nested event (picked straight or from a playlist) runs on its own timeline.
			v.keep = true;
			const EventTable *t = table.events.count(p.event) ? &table : sink.table_for(p.event);
			if (t) {
				v.child.reset(new TimelinePlayer(*t, p.event, volume_db + p.volume_db, step(rng)));
				v.child->start(sink);
				if (offset > 0) {
					v.child->advance(offset, sink);
				}
			} else {
				sink.log("nested event " + guid_text(p.event) + " is in no loaded bank");
			}
		} else {
			v.keep = p.loop;
			v.handle = sink.play(p.wave, volume_db + p.volume_db, p.loop, offset);
		}
	}
	voices.push_back(std::move(v));
}

void TimelinePlayer::stop_voice(Voice &v, TimelineSink &sink) {
	if (v.handle) {
		sink.stop(v.handle);
		v.handle = 0;
	}
	if (v.child) {
		v.child->stop(sink);
		v.child.reset();
	}
	for (uint64_t h : v.spawned) {
		sink.stop(h);
	}
	v.spawned.clear();
}

void TimelinePlayer::sync(bool jumped, TimelineSink &sink) {
	std::vector<bool> active(ev->placements.size(), false);
	for (size_t k = 0; k < voices.size();) {
		Voice &v = voices[k];
		const bool want = wanted(v.placement, sink);
		if (!want || (jumped && !v.keep)) {
			stop_voice(v, sink);
			voices.erase(voices.begin() + k);
			continue;
		}
		active[v.placement] = true;
		k++;
	}
	for (size_t i = 0; i < ev->placements.size(); i++) {
		if (!active[i] && wanted(i, sink)) {
			start_voice(i, sink);
		}
	}
}

void TimelinePlayer::start(TimelineSink &sink) {
	if (!ev || started) {
		return;
	}
	started = true;
	pos = 0;
	// Transitions placed at the very start (they pick the opening section) fire before anything plays.
	double prev = -1;
	for (int n = 0; n < MAX_JUMPS_PER_STEP; n++) {
		const EventTable::Transition *best = nullptr;
		for (const EventTable::Transition &t : ev->transitions) {
			const bool here = t.kind == EventTable::Transition::REGION ? (t.start <= pos && pos < t.end)
																	  : (t.start > prev && t.start <= pos);
			if (here && (!best || t.start < best->start) && holds(t.conditions, sink)) {
				best = &t;
			}
		}
		if (!best || best->destination == pos) {
			break;
		}
		prev = best->destination;
		pos = best->destination;
		jump_count++;
	}
	sync(false, sink);
}

void TimelinePlayer::advance(double seconds, TimelineSink &sink) {
	if (!ev || !started || seconds <= 0) {
		return;
	}
	double prev = pos;
	double cur = pos + seconds * EventTable::TIMELINE_RATE;
	bool jumped = false;
	for (int n = 0; n < MAX_JUMPS_PER_STEP; n++) {
		const EventTable::Transition *best = nullptr;
		double at = 0;
		for (const EventTable::Transition &t : ev->transitions) {
			double when;
			if (t.kind == EventTable::Transition::REGION) {
				// A region jumps as soon as the playhead is inside it and its conditions hold; a region that jumps
				// into itself would jump forever.
				if (!(cur >= t.start && cur < t.end) || (t.destination >= t.start && t.destination < t.end)) {
					continue;
				}
				when = std::max(prev, double(t.start));
			} else {
				if (!(t.start > prev && t.start <= cur)) {
					continue;
				}
				when = t.start;
			}
			if (best && when >= at) {
				continue;
			}
			if (!holds(t.conditions, sink)) {
				continue;
			}
			if (t.probability < 100 && random(0, 100) >= t.probability) {
				continue;
			}
			best = &t;
			at = when;
		}
		if (!best) {
			break;
		}
		const double overshoot = best->kind == EventTable::Transition::REGION ? 0 : cur - at;
		char line[160];
		snprintf(line, sizeof(line), "jump %.3f -> %.3f (kind %d)", at / EventTable::TIMELINE_RATE,
				best->destination / EventTable::TIMELINE_RATE, best->kind);
		sink.log(line);
		pos = best->destination;
		prev = pos;
		cur = pos + overshoot;
		jumped = true;
		jump_count++;
		if (n == MAX_JUMPS_PER_STEP - 1) {
			cur = pos; // keep a chain of jumps from spinning
		}
	}
	pos = cur;
	sync(jumped, sink);
	// Voices that were already running move on with the timeline; ones just started are where they belong.
	for (Voice &v : voices) {
		if (v.fresh) {
			v.fresh = false;
			continue;
		}
		if (v.child) {
			v.child->advance(seconds, sink);
		}
		const EventTable::Placement &pl = ev->placements[v.placement];
		const EventTable::Instrument &in = table.instruments.at(pl.instrument);
		if (!in.scatter) {
			continue;
		}
		v.spawned.erase(std::remove_if(v.spawned.begin(), v.spawned.end(),
								[&](uint64_t h) { return !sink.playing(h); }),
				v.spawned.end());
		v.age += seconds;
		while (v.age >= v.next_spawn) {
			Pick p;
			if (pick_wave(table, pl.instrument, rng, p) && !p.nested) {
				const uint64_t h = sink.play(p.wave, volume_db + p.volume_db, false, 0);
				if (h) {
					v.spawned.push_back(h);
				}
			}
			v.next_spawn += std::max(0.05f, random(in.spawn_min, std::max(in.spawn_min, in.spawn_max)));
		}
	}
}

void TimelinePlayer::stop(TimelineSink &sink) {
	for (Voice &v : voices) {
		stop_voice(v, sink);
	}
	voices.clear();
	started = false;
}

bool TimelinePlayer::finished(TimelineSink &sink) const {
	if (!ev || !started) {
		return true;
	}
	if (pos < ev->length) {
		return false;
	}
	for (const Voice &v : voices) {
		if ((v.handle && sink.playing(v.handle)) || (v.child && !v.child->finished(sink)) || !v.spawned.empty()) {
			return false;
		}
	}
	return true;
}

} // namespace fsb
