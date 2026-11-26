#!/usr/bin/env python3
"""Put a monotonic-clock time at the start of every engine log line when DROIDDECK_NATIVE_LOG_TIME=1.

    logger_time_prefix.py GODOT_SRC

The game's log (its own "[INFO] Preloading ..." lines, Steam init, cloud-save sync, mod scan, our
native_compat lines) carries no times, so a slow boot can't be split into its steps. With the switch set,
StdLogger writes "[<CLOCK_MONOTONIC seconds>] " before each line, the same clock as frames.csv's t0_mono_ns,
so each line lines up with the frame timeline. A message longer than 4095 bytes is cut there (switch on
only). "Project FPS:" lines keep no prefix, as the wrapper reads the first one as the first frame by its line
start. Unset changes nothing. Fails unless its anchor is found exactly once.
"""
import os
import sys

MARK = "// Kestrel (logger_time_prefix.py)"

ANCHOR = """void StdLogger::logv(const char *p_format, va_list p_list, bool p_err) {
	if (!should_log(p_err)) {
		return;
	}
"""

PATCHED = ANCHOR + """
	""" + MARK + """: DROIDDECK_NATIVE_LOG_TIME=1 puts the CLOCK_MONOTONIC time in front of each line.
	static const bool droiddeck_log_time = [] {
		const char *e = getenv("DROIDDECK_NATIVE_LOG_TIME");
		return e && e[0] == '1';
	}();
	if (droiddeck_log_time) {
		static bool droiddeck_line_start[2] = { true, true };
		char buf[4096];
		va_list copy;
		va_copy(copy, p_list);
		const int n = vsnprintf(buf, sizeof(buf), p_format, copy);
		va_end(copy);
		if (n <= 0) {
			return;
		}
		size_t len = MIN(size_t(n), sizeof(buf) - 1);
		FILE *out = p_err ? stderr : stdout;
		struct timespec ts;
		clock_gettime(CLOCK_MONOTONIC, &ts);
		const char *s = buf;
		while (len > 0) {
			// "Project FPS:" stays at the line start: droiddeck-native-godot takes the first one as the first frame.
			if (droiddeck_line_start[p_err] && strncmp(s, "Project FPS:", MIN(len, size_t(12))) != 0) {
				fprintf(out, "[%lld.%03ld] ", (long long)ts.tv_sec, ts.tv_nsec / 1000000);
			}
			const char *nl = static_cast<const char *>(memchr(s, '\\n', len));
			const size_t part = nl ? size_t(nl - s) + 1 : len;
			fwrite(s, 1, part, out);
			droiddeck_line_start[p_err] = nl != nullptr;
			s += part;
			len -= part;
		}
		if (!p_err && _flush_stdout_on_print) {
			fflush(stdout);
		}
		return;
	}
"""

INCLUDE = '#include "logger.h"\n'
INCLUDE_NEW = INCLUDE + "\n#include <stdlib.h>\n#include <string.h>\n#include <time.h>\n"


def patch(source):
    if MARK in source:
        raise SystemExit("logger_time_prefix: already applied")
    if source.count(ANCHOR) != 1 or source.count(INCLUDE) != 1:
        raise SystemExit("logger_time_prefix: expected StdLogger::logv and the logger.h include exactly once")
    return source.replace(ANCHOR, PATCHED).replace(INCLUDE, INCLUDE_NEW)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    path = os.path.join(sys.argv[1], "core", "io", "logger.cpp")
    with open(path, encoding="utf-8") as f:
        src = f.read()
    with open(path, "w", encoding="utf-8") as f:
        f.write(patch(src))
    print("logger_time_prefix: patched", path)


if __name__ == "__main__":
    main()
