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
//
// Music and ambience use more (layouts from the game's own Master, ambience and act banks):
//   TLNB arrays start at byte 16; an odd tag with no elements has no element-size field. Fixed arrays of
//                      24-byte elements are placements; variable elements of 26 bytes are loop regions
//                      (GUID, u32 start, ...), other variable ones markers (GUID, u32 position, u16 n, name).
//   TRNB (in TMLN):    GUID, destination GUID (a marker, or a loop region), u32 start, u32 end, a condition
//                      block (u32 byte count, then entries), f32 probability (percent), u32 kind:
//                      0 transition region (jump while inside it), 1 transition marker (jump on reaching it),
//                      2 the end of a loop region (jump back to its start), 4 seen on spans with one condition
//                      and read as a transition region. TRNB chunks sit in a LIST inside the TMLN object.
//   Condition entries: u32 tag, parameter GUID, u16 flags, u16, f32 min, f32 max (value in [min, max)), more.
//                      Ranges on one parameter are alternatives; different parameters must all hold.
//   INST:              byte 16 f32 volume dB; byte 24 u32 0xffffffff when the instrument loops; from byte 126
//                      a condition block (u32 byte count, entries) that triggers the instrument.
//   EVIT (LIST): EVIB  own GUID, the nested event's GUID: plays that event as an instrument
//   CMDI (LIST): CMDB  own GUID, u32 kind (2: set a parameter), parameter GUID, f32 value at byte 36
//   SPIT (LIST): SPIB  own GUID, u32, u32, f32 min and max seconds between spawns; PLST as for MUIT
//   PARM (LIST): PRMB  own GUID, u32 flags, u32, u16 n, name (n bytes), f32 min, f32 max, f32 default, then
//                      the labels as a variable array of (u16 n, n chars).
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
	struct Condition {
		Guid param{};
		float min = 0, max = 0; // holds for a value in [min, max); min == max: for that one value
	};
	struct Instrument {
		bool multi = false; // MUIT: picks one playlist entry; WAIT: plays `wave`
		float volume_db = 0;
		Guid wave{};
		std::vector<std::pair<Guid, float>> playlist; // (instrument or waveform GUID, weight)
		bool loop = false; // plays its sound over and over
		std::vector<Condition> conditions; // parameter values that trigger it (none: always)
		bool nested = false; // EVIT: plays the event `event`
		Guid event{};
		bool scatter = false; // SPIT: plays a playlist entry every spawn_min..spawn_max seconds
		float spawn_min = 0, spawn_max = 0;
		bool command = false; // CMDI: sets parameter `command_param` to `command_value` when reached
		Guid command_param{};
		float command_value = 0;
	};
	struct Placement {
		Guid instrument{};
		uint32_t start = 0, length = 0; // timeline frames
	};
	struct Transition {
		enum Kind { REGION = 0, MARKER = 1, LOOP_END = 2 };
		int kind = MARKER;
		uint32_t start = 0, end = 0;
		uint32_t destination = 0; // timeline frame to jump to
		float probability = 100;
		std::vector<Condition> conditions;
	};
	struct Param {
		std::string name;
		float min = 0, max = 0, value = 0; // value: the default
		std::vector<std::string> labels; // label i stands for the value i
		uint32_t flags = 0; // PRMB flags; bit 0 is set on the parameters the game sets globally (Progress, ducking)
		bool global() const { return flags & 1; }
	};
	struct Event {
		std::vector<Guid> instruments; // all start together
		std::vector<Guid> looped; // those of them overlapping a timeline loop region
		std::vector<Guid> unreachable; // placed past a loop region: skipped
		std::vector<std::pair<Guid, float>> delays; // timeline start, seconds
		std::string refs; // diagnostics: types of the objects reachable from the event, as "TYPE:count ..."
		std::vector<Guid> params; // parameters (PARM objects) reachable from the event, for its description
		// The timeline as a whole, for events that need it played over time (see `dynamic`).
		std::vector<Placement> placements;
		std::vector<Transition> transitions;
		uint32_t length = 0; // end of the last placement
		// Played by a timeline player rather than as one set of picks: it has transitions, conditional
		// instruments, nested events or scatterers.
		bool dynamic = false;
	};
	static constexpr float TIMELINE_RATE = 48000;
	std::unordered_map<Guid, Event, GuidHash> events;
	std::unordered_map<Guid, Instrument, GuidHash> instruments;
	std::unordered_map<Guid, uint32_t, GuidHash> waves; // waveform GUID -> sound index in the FSB5 block
	std::unordered_map<Guid, Param, GuidHash> params;
	std::string object_types; // diagnostics: the bank's object types, as "TYPE:count ..."
};

// Reads the object lists of a whole bank file. A file without PROJ objects (a strings bank) gives an
// empty table and true; a malformed RIFF tree gives false.
bool read_event_table(const uint8_t *data, size_t length, EventTable &out, std::string &error);

struct Pick {
	Guid wave{};
	float volume_db = 0;
	bool loop = false; // plays until the event stops
	float delay = 0; // seconds after the start
	bool nested = false; // a nested event to play (`event`) rather than a waveform
	Guid event{};
};

// The sounds one start of the event plays: each instrument in turn, a multi instrument choosing one entry
// by weight with `rng` (any seed; advanced on each choice). Waveforms may live in another bank, so picks
// name the waveform GUID. Returns false if the table has no such event.
bool resolve_event(const EventTable &table, const Guid &event, uint32_t &rng, std::vector<Pick> &out);

// One choice from an instrument: a WAIT gives its waveform, a MUIT or SPIT one playlist entry chosen by weight
// (recursively). False when it reaches no waveform (a nested event, or a broken reference).
bool pick_wave(const EventTable &table, const Guid &instrument, uint32_t &rng, Pick &out); // may pick a nested event

// Whether `conditions` hold for parameter values given by `value(param GUID)`.
template <typename F>
bool conditions_hold(const std::vector<EventTable::Condition> &conditions, F value) {
	for (size_t i = 0; i < conditions.size(); i++) {
		bool first = true;
		for (size_t j = 0; j < i; j++) {
			first = first && conditions[j].param != conditions[i].param;
		}
		if (!first) {
			continue; // this parameter was checked with its first range
		}
		const float v = value(conditions[i].param);
		bool any = false;
		for (size_t j = i; j < conditions.size(); j++) {
			if (conditions[j].param == conditions[i].param) {
				const float lo = conditions[j].min, hi = conditions[j].max;
				any = any || (lo == hi ? v == lo : (v >= lo && v < hi)); // an empty range means that one value
			}
		}
		if (!any) {
			return false;
		}
	}
	return true;
}

} // namespace fsb
