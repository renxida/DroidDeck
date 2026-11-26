// Clean-room timeline player for events that change over time: music that jumps between markers as a
// parameter changes, ambience whose layers follow a parameter, nested events and scatterers. It works from
// an EventTable (see event_table.h) and knows nothing of the engine: a TimelineSink plays the sounds.
#pragma once

#include "event_table.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fsb {

class TimelineSink {
public:
	virtual ~TimelineSink() {}
	// Starts a waveform `offset` seconds in, looping or once. Returns a handle (0: nothing started).
	virtual uint64_t play(const Guid &wave, float volume_db, bool loop, double offset) = 0;
	virtual void stop(uint64_t handle) = 0;
	virtual bool playing(uint64_t handle) = 0;
	// The current value of a parameter of `table`.
	virtual float param(const EventTable &table, const Guid &param) = 0;
	// A command instrument sets a parameter of `table`.
	virtual void set_param(const EventTable &table, const Guid &param, float value) {
		(void)table;
		(void)param;
		(void)value;
	}
	// The table holding a nested event that is not in the playing event's own bank (nullptr: none).
	virtual const EventTable *table_for(const Guid &event) {
		(void)event;
		return nullptr;
	}
	virtual void log(const std::string &line) { (void)line; }
};

class TimelinePlayer {
public:
	TimelinePlayer(const EventTable &table, const Guid &event, float volume_db, uint32_t seed);
	~TimelinePlayer();
	bool valid() const { return ev != nullptr; }
	// Starts at the beginning of the timeline (transitions placed at its start fire at once).
	void start(TimelineSink &sink);
	// Moves the playhead on by `seconds`: fires transitions, starts and stops instruments.
	void advance(double seconds, TimelineSink &sink);
	void stop(TimelineSink &sink);
	// Past the end of the timeline with nothing left playing.
	bool finished(TimelineSink &sink) const;
	double position() const { return pos / EventTable::TIMELINE_RATE; }
	int jumps() const { return jump_count; }

private:
	struct Voice {
		size_t placement = 0;
		uint64_t handle = 0;
		bool fresh = true; // started this step
		bool keep = false; // plays on across timeline jumps (nested events, scatterers, looping sounds)
		std::unique_ptr<TimelinePlayer> child;
		double age = 0, next_spawn = 0; // seconds; scatterers
		std::vector<uint64_t> spawned;
	};
	const EventTable &table;
	const EventTable::Event *ev = nullptr;
	float volume_db = 0;
	uint32_t rng;
	double pos = 0; // timeline frames
	bool started = false;
	int jump_count = 0;
	std::vector<Voice> voices;

	bool holds(const std::vector<EventTable::Condition> &c, TimelineSink &sink);
	bool wanted(size_t placement, TimelineSink &sink);
	void start_voice(size_t placement, TimelineSink &sink);
	void stop_voice(Voice &v, TimelineSink &sink);
	void sync(bool jumped, TimelineSink &sink);
	float random(float lo, float hi);
};

} // namespace fsb
