// Clean-room reader for the event metadata of a sound bank, worked out from the game's own bank bytes
// alone (no FMOD code or SDK documentation). It answers one question: which sounds does an event play?
//
// A bank file is RIFF "FEV " holding LIST "PROJ"; PROJ holds object lists (LIST EVTS, MUIS, WAIS, TLNS,
// PMLS, ...), each a run of LIST objects, plus WAVS, a run of bare "WAV " chunks. Every object's first
// chunk starts with the object's own GUID (16 bytes, as stored). References between objects are those
// GUIDs written inside the payloads. The parts read here:
//   EVNT (LIST): EVTB  own GUID, 16 zero bytes, timeline GUID at 32, then arrays holding the event's
//                      instrument GUIDs and its parameter-sheet GUIDs
//   TMLN (LIST): TLNB  own GUID, u16, then arrays. An array is u16 tag then either (tag odd) u16 element
//                      size and tag/2 elements, or (tag even) tag/2 elements each led by its u16 size.
//                      The first holds instrument placements (GUID, u32 start, u32 length); later
//                      variable ones hold markers (GUID, u32 position, name) and loop regions, 26 bytes:
//                      (GUID, u32 start, u16, u32 end). Positions count 48 kHz frames (they match
//                      the sounds' frame counts). An instrument overlapping a loop region loops; one
//                      wholly past a loop region never plays while it loops; others start at their place.
//   PMLO (LIST):       a parameter sheet; its chunks name the instruments it switches between
//   WAIT (LIST): WAIB  own GUID, then the waveform GUID;  INST  f32 volume in dB at byte 16
//   MUIT (LIST): MUIB  own GUID;  INST as above;  PLST  u32 mode, u32 1, u16 entries*2+1, u16 20,
//                      then entries of (GUID, f32 weight). An entry is a waveform or another MUIT.
//   WAV  (chunk):      GUID, u32 12, u16 0, u32 sound index in the bank's FSB5 block, u32 flags
// Instrument references are found by matching every 16-byte window of a payload against the known
// instrument GUIDs, so the exact array layouts around them do not matter.
#pragma once

#include "path_table.h"

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace fsb {

struct GuidHash {
	size_t operator()(const Guid &g) const {
		uint64_t h = 1469598103934665603ull;
		for (uint8_t b : g) {
			h = (h ^ b) * 1099511628211ull;
		}
		return size_t(h);
	}
};

struct EventTable {
	struct Instrument {
		bool multi = false; // MUIT: picks one playlist entry; WAIT: plays `wave`
		float volume_db = 0;
		Guid wave{};
		std::vector<std::pair<Guid, float>> playlist; // (instrument or waveform GUID, weight)
	};
	struct Event {
		std::vector<Guid> instruments; // all start together
		std::vector<Guid> looped; // those of them overlapping a timeline loop region
		std::vector<Guid> unreachable; // placed past a loop region: skipped
		std::vector<std::pair<Guid, float>> delays; // timeline start, seconds
	};
	static constexpr float TIMELINE_RATE = 48000;
	std::unordered_map<Guid, Event, GuidHash> events;
	std::unordered_map<Guid, Instrument, GuidHash> instruments;
	std::unordered_map<Guid, uint32_t, GuidHash> waves; // waveform GUID -> sound index in the FSB5 block
};

// Reads the object lists of a whole bank file. A file without PROJ objects (a strings bank) gives an
// empty table and true; a malformed RIFF tree gives false.
bool read_event_table(const uint8_t *data, size_t length, EventTable &out, std::string &error);

struct Pick {
	Guid wave{};
	float volume_db = 0;
	bool loop = false; // plays until the event stops
	float delay = 0; // seconds after the start
};

// The sounds one start of the event plays: each instrument in turn, a multi instrument choosing one entry
// by weight with `rng` (any seed; advanced on each choice). Waveforms may live in another bank, so picks
// name the waveform GUID. Returns false if the table has no such event.
bool resolve_event(const EventTable &table, const Guid &event, uint32_t &rng, std::vector<Pick> &out);

} // namespace fsb
