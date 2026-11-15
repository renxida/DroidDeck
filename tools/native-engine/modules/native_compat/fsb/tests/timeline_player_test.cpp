// Host test for the timeline player on a hand-built table (no game data).
#include "../timeline_player.h"

#include <cstdio>
#include <cstdlib>
#include <map>
#include <string>

using namespace fsb;

#define CHECK(c)                                                    \
	do {                                                            \
		if (!(c)) {                                                 \
			fprintf(stderr, "%s:%d: CHECK(%s)\n", __FILE__, __LINE__, #c); \
			exit(1);                                                \
		}                                                           \
	} while (0)

static Guid G(uint8_t n) {
	Guid g{};
	g[0] = n;
	return g;
}

struct Sink : TimelineSink {
	double now = 0;
	uint64_t next = 1;
	struct Sound {
		Guid wave;
		bool loop;
		double offset, started;
	};
	std::map<uint64_t, Sound> live;
	std::map<std::string, float> values;
	int plays = 0;
	uint64_t play(const Guid &w, float, bool loop, double offset) override {
		plays++;
		live[next] = { w, loop, offset, now };
		return next++;
	}
	void stop(uint64_t h) override { live.erase(h); }
	bool playing(uint64_t h) override {
		auto it = live.find(h);
		return it != live.end() && (it->second.loop || now - it->second.started < 3);
	}
	float param(const EventTable &t, const Guid &p) override {
		auto it = t.params.find(p);
		auto v = values.find(it->second.name);
		return v == values.end() ? it->second.value : v->second;
	}
	const EventTable *nested_table = nullptr;
	const EventTable *table_for(const Guid &) override { return nested_table; }
	int count(const Guid &w) const {
		int n = 0;
		for (const auto &kv : live) {
			n += kv.second.wave == w;
		}
		return n;
	}
	void run(TimelinePlayer &p, double seconds) {
		for (double t = 0; t < seconds - 1e-9; t += 0.05) {
			now += 0.05;
			p.advance(0.05, *this);
		}
	}
};

int main() {
	const uint32_t R = uint32_t(EventTable::TIMELINE_RATE);
	EventTable t;
	const Guid section = G(1);
	t.params[section] = { "Section", 0, 2, 0, { "Calm", "Fight", "End" } };
	// Waves A (calm loop) and B (fight loop) as WAIT instruments; C a one-shot scattered; N a nested event.
	t.waves[G(10)] = 0;
	t.waves[G(11)] = 1;
	t.waves[G(12)] = 2;
	EventTable::Instrument a, b, c, n;
	a.wave = G(10);
	a.loop = true;
	b.wave = G(11);
	b.loop = true;
	b.conditions.push_back({ section, 1, 2 });
	c.multi = true;
	c.scatter = true;
	c.spawn_min = 1;
	c.spawn_max = 2;
	c.playlist.push_back({ G(12), 1 });
	n.nested = true;
	n.event = G(40);
	t.instruments[G(20)] = a;
	t.instruments[G(21)] = b;
	t.instruments[G(22)] = c;
	t.instruments[G(23)] = n;
	// Main event: calm 0..10 s (loop region 2..4), fight 10..20 s (loop region 12..14).
	EventTable::Event e;
	e.placements.push_back({ G(20), 0, 10 * R });
	e.placements.push_back({ G(21), 10 * R, 10 * R });
	e.placements.push_back({ G(22), 0, 20 * R });
	e.placements.push_back({ G(23), 0, 20 * R });
	EventTable::Transition loop1, loop2, to_fight, start_fight;
	loop1.kind = EventTable::Transition::LOOP_END;
	loop1.start = loop1.end = 4 * R;
	loop1.destination = 2 * R;
	loop2.kind = EventTable::Transition::LOOP_END;
	loop2.start = loop2.end = 14 * R;
	loop2.destination = 12 * R;
	to_fight.kind = EventTable::Transition::REGION;
	to_fight.start = 0;
	to_fight.end = 10 * R;
	to_fight.destination = 10 * R;
	to_fight.conditions.push_back({ section, 1, 2 });
	start_fight.kind = EventTable::Transition::MARKER;
	start_fight.start = start_fight.end = 0;
	start_fight.destination = 12 * R;
	start_fight.conditions.push_back({ section, 2, 2 }); // only the one value 2
	e.transitions = { loop1, loop2, to_fight, start_fight };
	e.length = 20 * R;
	e.dynamic = true;
	t.events[G(30)] = e;
	// The nested event lives in another table: one looping wave.
	EventTable other;
	other.waves[G(13)] = 0;
	EventTable::Instrument d;
	d.wave = G(13);
	d.loop = true;
	other.instruments[G(24)] = d;
	EventTable::Event ne;
	ne.placements.push_back({ G(24), 0, 30 * R });
	ne.length = 30 * R;
	other.events[G(40)] = ne;

	{
		Sink s;
		s.nested_table = &other;
		TimelinePlayer p(t, G(30), 0, 1);
		CHECK(p.valid());
		p.start(s);
		CHECK(s.count(G(10)) == 1); // calm plays
		CHECK(s.count(G(13)) == 1); // the nested event plays from the other table
		s.run(p, 9);
		CHECK(p.position() >= 2 && p.position() < 4); // held in the calm loop
		CHECK(p.jumps() >= 3);
		CHECK(s.count(G(10)) == 1); // a looping sound plays on across the loop's jumps
		CHECK(s.count(G(11)) == 0);
		const int scattered = s.plays - 2;
		CHECK(scattered >= 3 && scattered <= 10); // every 1..2 s
		s.values["Section"] = 1;
		s.run(p, 1);
		CHECK(p.position() >= 10 && p.position() < 14); // jumped to the fight
		CHECK(s.count(G(10)) == 0);
		CHECK(s.count(G(11)) == 1);
		CHECK(s.count(G(13)) == 1); // the nested event went on through the jump
		s.run(p, 10);
		CHECK(p.position() >= 12 && p.position() < 14);
		CHECK(!p.finished(s));
		p.stop(s);
		int still = 0;
		for (const auto &kv : s.live) {
			still += s.playing(kv.first); // a finished one-shot frees itself; none may still sound
		}
		CHECK(still == 0);
	}
	{
		// A value matching a one-value range picks the opening section at the start.
		Sink s;
		s.nested_table = &other;
		s.values["Section"] = 2;
		TimelinePlayer p(t, G(30), 0, 1);
		p.start(s);
		CHECK(p.position() >= 12 && p.position() < 14);
		CHECK(s.count(G(10)) == 0);
		s.values["Section"] = 1.5f; // within no one-value range: the fight's condition [1, 2) holds
		CHECK(conditions_hold(t.instruments[G(21)].conditions, [&](const Guid &) { return 1.5f; }));
		CHECK(!conditions_hold(start_fight.conditions, [&](const Guid &) { return 1.5f; }));
		p.stop(s);
	}
	{
		// Unknown event: nothing plays.
		Sink s;
		TimelinePlayer p(t, G(99), 0, 1);
		CHECK(!p.valid());
		p.start(s);
		CHECK(s.plays == 0 && p.finished(s));
	}
	printf("timeline player tests OK\n");
	return 0;
}
