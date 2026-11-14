// Host tests for the clean-room bank event reader, on a hand-built synthetic bank (no game files).
// Usage: event_table_test [bank]   with a bank, also prints how many of its events reach a sound
#include "../event_table.h"

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

using Bytes = std::vector<uint8_t>;

void put(Bytes &b, uint32_t v, int n) {
	for (int i = 0; i < n; i++) {
		b.push_back(uint8_t(v >> (8 * i)));
	}
}
void putf(Bytes &b, float f) {
	uint32_t v;
	memcpy(&v, &f, 4);
	put(b, v, 4);
}
void putg(Bytes &b, const fsb::Guid &g) { b.insert(b.end(), g.begin(), g.end()); }

Bytes chunk(const char *id, const Bytes &body) {
	Bytes b(id, id + 4);
	put(b, uint32_t(body.size()), 4);
	b.insert(b.end(), body.begin(), body.end());
	if (body.size() & 1) {
		b.push_back(0);
	}
	return b;
}
Bytes list(const char *type, const std::vector<Bytes> &children) {
	Bytes body(type, type + 4);
	for (const Bytes &c : children) {
		body.insert(body.end(), c.begin(), c.end());
	}
	return chunk("LIST", body);
}

fsb::Guid G(uint8_t n) {
	fsb::Guid g{};
	for (int i = 0; i < 16; i++) {
		g[i] = uint8_t(n * 31 + i * 7 + 1);
	}
	return g;
}

Bytes inst(float volume_db) {
	Bytes b(130, 0);
	memcpy(&b[16], &volume_db, 4);
	return b;
}

// Events: E1 plays WAIT W1 (-6 dB) directly and, on its timeline T1, MUIT M1 (-3 dB) choosing W2 (weight 1)
// or W3 (weight 3); T1 has a loop region over M1. E2 has no instruments of its own; its parameter sheet S2
// names W1. E3's timeline places W2 half a second in. E4's places W1 under a loop region and W3 past it.
const fsb::Guid E1 = G(1), E2 = G(2), T1 = G(3), T2 = G(4), M1 = G(5), W1 = G(6), W2 = G(7), W3 = G(8), S2 = G(9),
				A = G(10), B = G(11), C = G(12), E3 = G(13), T3 = G(14), E4 = G(15), T4 = G(16);

Bytes bank() {
	auto evtb = [](const fsb::Guid &own, const fsb::Guid &tl, const std::vector<fsb::Guid> &refs) {
		Bytes b;
		putg(b, own);
		b.insert(b.end(), 16, 0);
		putg(b, tl);
		b.insert(b.end(), 21, 0xff);
		put(b, uint32_t(refs.size() * 2 + 1), 2);
		put(b, 16, 2);
		for (const fsb::Guid &g : refs) {
			putg(b, g);
		}
		putf(b, 1);
		return b;
	};
	// Instruments 1000 frames long at the given starts; with `loop`, an empty array, then a marker and a loop
	// region over 0..1200.
	auto tlnb = [](const fsb::Guid &own, const std::vector<std::pair<fsb::Guid, uint32_t>> &placed, bool loop) {
		Bytes b;
		putg(b, own);
		put(b, 1, 2);
		put(b, uint32_t(placed.size() * 2 + 1), 2);
		put(b, 24, 2);
		for (const auto &pl : placed) {
			putg(b, pl.first);
			put(b, pl.second, 4);
			put(b, 1000, 4);
		}
		if (loop) {
			put(b, 0, 2);
			put(b, 2 * 2, 2);
			put(b, 29, 2); // marker "end" at 900
			putg(b, G(40));
			put(b, 900, 4);
			put(b, 3, 2);
			b.insert(b.end(), { 'e', 'n', 'd', 0, 0, 0, 0 });
			put(b, 26, 2); // loop region 0..1200
			putg(b, G(41));
			put(b, 0, 4);
			put(b, 0, 2);
			put(b, 1200, 4);
			put(b, 1, 2);
		}
		return b;
	};
	auto wait = [](const fsb::Guid &own, const fsb::Guid &wave, float db) {
		Bytes b;
		putg(b, own);
		putg(b, wave);
		return list("WAIT", { chunk("WAIB", b), chunk("INST", inst(db)) });
	};
	auto wav = [](const fsb::Guid &own, uint32_t index) {
		Bytes b;
		putg(b, own);
		put(b, 12, 4);
		put(b, 0, 2);
		put(b, index, 4);
		put(b, 0, 4);
		return chunk("WAV ", b);
	};
	Bytes muib, plst, pmlb;
	putg(muib, M1);
	put(plst, 2, 4);
	put(plst, 1, 4);
	put(plst, 2 * 2 + 1, 2);
	put(plst, 20, 2);
	putg(plst, W2);
	putf(plst, 1);
	putg(plst, W3);
	putf(plst, 3);
	putg(pmlb, S2);
	Bytes sheet_refs;
	putg(sheet_refs, W1);
	Bytes proj = list("PROJ", {
			chunk("BNKI", Bytes(32, 0)),
			list("EVTS", { list("EVNT", { chunk("EVTB", evtb(E1, T1, { W1 })) }),
								 list("EVNT", { chunk("EVTB", evtb(E2, T2, { S2 })) }),
								 list("EVNT", { chunk("EVTB", evtb(E3, T3, {})) }),
								 list("EVNT", { chunk("EVTB", evtb(E4, T4, {})) }) }),
			list("TLNS", { list("TMLN", { chunk("TLNB", tlnb(T1, { { M1, 0 } }, true)) }),
								 list("TMLN", { chunk("TLNB", tlnb(T2, {}, false)) }),
								 list("TMLN", { chunk("TLNB", tlnb(T3, { { W2, 24000 } }, false)) }),
								 list("TMLN", { chunk("TLNB", tlnb(T4, { { W1, 0 }, { W3, 2000 } }, true)) }) }),
			list("PMLS", { list("PMLO", { chunk("PMLB", pmlb), chunk("PMIN", sheet_refs) }) }),
			list("MUIS", { list("MUIT", { chunk("MUIB", muib), chunk("PLST", plst), chunk("INST", inst(-3)) }) }),
			list("WAIS", { wait(W1, A, -6), wait(W2, B, 0), wait(W3, C, 0) }),
			list("WAVS", { wav(A, 5), wav(B, 7), wav(C, 9) }),
	});
	Bytes riff = { 'F', 'E', 'V', ' ' };
	Bytes fmt = chunk("FMT ", Bytes(8, 0));
	riff.insert(riff.end(), fmt.begin(), fmt.end());
	riff.insert(riff.end(), proj.begin(), proj.end());
	Bytes snd = chunk("SND ", Bytes(64, 0xab)); // stands in for the audio block
	riff.insert(riff.end(), snd.begin(), snd.end());
	return chunk("RIFF", riff);
}

void test_synthetic() {
	const Bytes b = bank();
	fsb::EventTable t;
	std::string error;
	CHECK(fsb::read_event_table(b.data(), b.size(), t, error));
	CHECK(t.events.size() == 4);
	CHECK(t.instruments.size() == 4);
	CHECK(t.waves.size() == 3 && t.waves.at(B) == 7);
	CHECK(t.instruments.at(M1).multi && t.instruments.at(M1).playlist.size() == 2);
	CHECK(t.events.at(E1).instruments.size() == 2);
	uint32_t rng = 1;
	int got_b = 0, got_c = 0;
	for (int i = 0; i < 4000; i++) {
		std::vector<fsb::Pick> p;
		CHECK(fsb::resolve_event(t, E1, rng, p));
		CHECK(p.size() == 2);
		CHECK(p[0].wave == A && p[0].volume_db == -6 && !p[0].loop);
		CHECK(p[1].volume_db == -3 && p[1].loop); // M1 sits in T1's loop region
		CHECK(p[0].delay == 0 && p[1].delay == 0);
		got_b += p[1].wave == B;
		got_c += p[1].wave == C;
	}
	CHECK(got_b + got_c == 4000);
	CHECK(got_b > 800 && got_b < 1200); // weight 1 of 4
	std::vector<fsb::Pick> p;
	CHECK(fsb::resolve_event(t, E2, rng, p));
	CHECK(p.size() == 1 && p[0].wave == A && !p[0].loop);
	p.clear();
	CHECK(fsb::resolve_event(t, E3, rng, p));
	CHECK(p.size() == 1 && p[0].wave == B && !p[0].loop && p[0].delay == 0.5f);
	p.clear();
	CHECK(fsb::resolve_event(t, E4, rng, p));
	CHECK(p.size() == 1 && p[0].wave == A && p[0].loop && p[0].delay == 0); // W3 sits past the loop
	p.clear();
	CHECK(!fsb::resolve_event(t, W1, rng, p) && p.empty());

	// Cut off inside the audio block, as a head-only copy is: metadata still reads.
	fsb::EventTable cut;
	CHECK(fsb::read_event_table(b.data(), b.size() - 40, cut, error));
	CHECK(cut.events.size() == 4 && cut.waves.size() == 3);
	// Cut inside the waves list: the waves before the cut remain.
	const size_t wavs_end = b.size() - 72 - 8;
	CHECK(fsb::read_event_table(b.data(), wavs_end - 10, cut, error));
	CHECK(cut.waves.size() == 2 && cut.events.size() == 4);
	// Not a RIFF file.
	CHECK(!fsb::read_event_table(b.data() + 1, b.size() - 1, cut, error));
	// Garbage sizes do not crash: flip bytes one at a time.
	for (size_t i = 12; i < b.size(); i += 3) {
		Bytes m = b;
		m[i] ^= 0x5a;
		fsb::EventTable x;
		fsb::read_event_table(m.data(), m.size(), x, error);
		for (const auto &e : x.events) {
			std::vector<fsb::Pick> q;
			fsb::resolve_event(x, e.first, rng, q);
		}
	}
	// A playlist that names itself stops at the depth limit.
	fsb::EventTable loop;
	fsb::EventTable::Instrument self;
	self.multi = true;
	self.playlist.push_back({ M1, 1 });
	loop.instruments[M1] = self;
	loop.events[E1].instruments.push_back(M1);
	p.clear();
	CHECK(fsb::resolve_event(loop, E1, rng, p) && p.empty());
}

Bytes slurp(const char *path) {
	Bytes d;
	FILE *f = fopen(path, "rb");
	if (!f) {
		return d;
	}
	uint8_t buf[65536];
	size_t n;
	while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
		d.insert(d.end(), buf, buf + n);
	}
	fclose(f);
	return d;
}

} // namespace

int main(int argc, char **argv) {
	test_synthetic();
	for (int i = 1; i < argc; i++) {
		const Bytes d = slurp(argv[i]);
		fsb::EventTable t;
		std::string error;
		if (!fsb::read_event_table(d.data(), d.size(), t, error)) {
			printf("%s: FAILED %s\n", argv[i], error.c_str());
			return 1;
		}
		int sounding = 0, picks = 0, missing_wave = 0, looping = 0;
		uint32_t rng = 7;
		for (const auto &e : t.events) {
			std::vector<fsb::Pick> p;
			fsb::resolve_event(t, e.first, rng, p);
			sounding += !p.empty();
			looping += !e.second.looped.empty();
			picks += int(p.size());
			for (const fsb::Pick &k : p) {
				missing_wave += !t.waves.count(k.wave);
			}
		}
		printf("%s: events %zu instruments %zu waves %zu sounding %d looping %d picks %d picks_without_wave %d\n",
				argv[i], t.events.size(), t.instruments.size(), t.waves.size(), sounding, looping, picks, missing_wave);
	}
	printf("event table tests OK\n");
	return 0;
}
