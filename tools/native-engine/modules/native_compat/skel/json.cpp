// Clean-room code for DroidDeck/Kestrel: written from scratch, no third-party runtime source consulted.
#include "json.h"

#include <cstdlib>
#include <cstring>

namespace skel {

const JsonValue *JsonValue::get(const std::string &key) const {
	if (kind != OBJECT) {
		return nullptr;
	}
	for (const auto &m : members) {
		if (m.first == key) {
			return &m.second;
		}
	}
	return nullptr;
}

double JsonValue::num(const std::string &key, double fallback) const {
	const JsonValue *v = get(key);
	return v && v->kind == NUMBER ? v->number : fallback;
}

bool JsonValue::flag(const std::string &key, bool fallback) const {
	const JsonValue *v = get(key);
	return v && v->kind == BOOL ? v->boolean : fallback;
}

std::string JsonValue::str(const std::string &key, const std::string &fallback) const {
	const JsonValue *v = get(key);
	return v && v->kind == STRING ? v->string : fallback;
}

namespace {

struct Reader {
	const char *p;
	const char *end;
	std::string error;
	int depth = 0;

	void ws() {
		while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) {
			p++;
		}
	}
	bool fail(const char *what) {
		if (error.empty()) {
			error = what;
		}
		return false;
	}
	static void utf8(std::string &out, uint32_t c) {
		if (c < 0x80) {
			out += char(c);
		} else if (c < 0x800) {
			out += char(0xC0 | (c >> 6));
			out += char(0x80 | (c & 0x3F));
		} else if (c < 0x10000) {
			out += char(0xE0 | (c >> 12));
			out += char(0x80 | ((c >> 6) & 0x3F));
			out += char(0x80 | (c & 0x3F));
		} else {
			out += char(0xF0 | (c >> 18));
			out += char(0x80 | ((c >> 12) & 0x3F));
			out += char(0x80 | ((c >> 6) & 0x3F));
			out += char(0x80 | (c & 0x3F));
		}
	}
	bool hex4(uint32_t &c) {
		if (end - p < 4) {
			return fail("short \\u escape");
		}
		c = 0;
		for (int i = 0; i < 4; i++) {
			char h = *p++;
			c <<= 4;
			if (h >= '0' && h <= '9') {
				c |= h - '0';
			} else if (h >= 'a' && h <= 'f') {
				c |= h - 'a' + 10;
			} else if (h >= 'A' && h <= 'F') {
				c |= h - 'A' + 10;
			} else {
				return fail("bad \\u escape");
			}
		}
		return true;
	}
	bool string(std::string &out) {
		p++; // opening quote
		while (p < end && *p != '"') {
			char ch = *p++;
			if (ch != '\\') {
				out += ch;
				continue;
			}
			if (p >= end) {
				return fail("unterminated escape");
			}
			char e = *p++;
			switch (e) {
				case '"': out += '"'; break;
				case '\\': out += '\\'; break;
				case '/': out += '/'; break;
				case 'b': out += '\b'; break;
				case 'f': out += '\f'; break;
				case 'n': out += '\n'; break;
				case 'r': out += '\r'; break;
				case 't': out += '\t'; break;
				case 'u': {
					uint32_t c;
					if (!hex4(c)) {
						return false;
					}
					if (c >= 0xD800 && c < 0xDC00 && end - p >= 6 && p[0] == '\\' && p[1] == 'u') {
						p += 2;
						uint32_t lo;
						if (!hex4(lo)) {
							return false;
						}
						c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
					}
					utf8(out, c);
					break;
				}
				default: return fail("bad escape");
			}
		}
		if (p >= end) {
			return fail("unterminated string");
		}
		p++;
		return true;
	}
	bool value(JsonValue &v) {
		if (++depth > 256) {
			return fail("nesting too deep");
		}
		ws();
		if (p >= end) {
			return fail("unexpected end");
		}
		bool ok = true;
		char c = *p;
		if (c == '{') {
			v.kind = JsonValue::OBJECT;
			p++;
			ws();
			if (p < end && *p == '}') {
				p++;
			} else {
				while (ok) {
					ws();
					if (p >= end || *p != '"') {
						ok = fail("expected key");
						break;
					}
					std::string key;
					if (!string(key)) {
						ok = false;
						break;
					}
					ws();
					if (p >= end || *p != ':') {
						ok = fail("expected ':'");
						break;
					}
					p++;
					v.members.emplace_back(std::move(key), JsonValue());
					if (!value(v.members.back().second)) {
						ok = false;
						break;
					}
					ws();
					if (p < end && *p == ',') {
						p++;
						continue;
					}
					if (p < end && *p == '}') {
						p++;
						break;
					}
					ok = fail("expected ',' or '}'");
				}
			}
		} else if (c == '[') {
			v.kind = JsonValue::ARRAY;
			p++;
			ws();
			if (p < end && *p == ']') {
				p++;
			} else {
				while (ok) {
					v.array.emplace_back();
					if (!value(v.array.back())) {
						ok = false;
						break;
					}
					ws();
					if (p < end && *p == ',') {
						p++;
						continue;
					}
					if (p < end && *p == ']') {
						p++;
						break;
					}
					ok = fail("expected ',' or ']'");
				}
			}
		} else if (c == '"') {
			v.kind = JsonValue::STRING;
			ok = string(v.string);
		} else if (c == 't' && end - p >= 4 && !strncmp(p, "true", 4)) {
			v.kind = JsonValue::BOOL;
			v.boolean = true;
			p += 4;
		} else if (c == 'f' && end - p >= 5 && !strncmp(p, "false", 5)) {
			v.kind = JsonValue::BOOL;
			p += 5;
		} else if (c == 'n' && end - p >= 4 && !strncmp(p, "null", 4)) {
			p += 4;
		} else if (c == '-' || (c >= '0' && c <= '9')) {
			const char *start = p;
			while (p < end && (strchr("+-.eE", *p) || (*p >= '0' && *p <= '9'))) {
				p++;
			}
			std::string num(start, p - start);
			char *stop = nullptr;
			v.kind = JsonValue::NUMBER;
			v.number = strtod(num.c_str(), &stop);
			if (stop != num.c_str() + num.size()) {
				ok = fail("bad number");
			}
		} else {
			ok = fail("unexpected character");
		}
		depth--;
		return ok;
	}
};

} // namespace

bool parse_json(const char *text, size_t length, JsonValue &out, std::string &error) {
	Reader r{ text, text + length, {} };
	out = JsonValue();
	bool ok = r.value(out);
	if (ok) {
		r.ws();
		if (r.p != r.end) {
			ok = r.fail("trailing characters");
		}
	}
	if (!ok) {
		error = r.error + " at byte " + std::to_string(r.p - text);
	}
	return ok;
}

} // namespace skel
