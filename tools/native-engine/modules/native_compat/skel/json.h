// Clean-room code for DroidDeck/Kestrel: written from scratch, no third-party runtime source consulted.
// A small JSON reader for skeleton files. No exceptions (the engine builds without them).
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace skel {

struct JsonValue {
	enum Kind { NUL, BOOL, NUMBER, STRING, ARRAY, OBJECT };
	Kind kind = NUL;
	bool boolean = false;
	double number = 0;
	std::string string;
	std::vector<JsonValue> array;
	// Object members in file order: order matters for bones, slots and draw order.
	std::vector<std::pair<std::string, JsonValue>> members;

	const JsonValue *get(const std::string &key) const;
	bool has(const std::string &key) const { return get(key) != nullptr; }
	double num(const std::string &key, double fallback) const;
	bool flag(const std::string &key, bool fallback) const;
	std::string str(const std::string &key, const std::string &fallback) const;
	bool is_object() const { return kind == OBJECT; }
	bool is_array() const { return kind == ARRAY; }
};

// Returns false and fills error on malformed input.
bool parse_json(const char *text, size_t length, JsonValue &out, std::string &error);

} // namespace skel
