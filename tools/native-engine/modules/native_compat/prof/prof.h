// A sampling profiler for the main thread, off unless asked for. It answers where a frame's main-thread time
// goes (which library, which engine function) and which threads burn the CPU, so battery and frame-time work
// can aim at what the phone actually spends time on.
//
//   DROIDDECK_NATIVE_PROF=<hz>             sample the main thread's CPU time this often (e.g. 250; the kernel's
//                                          tick caps the real rate, often at 250 Hz); unset = off
//   DROIDDECK_NATIVE_PROF_WINDOW=<s>       seconds per report (default 10)
//   DROIDDECK_NATIVE_PROF_SYMS=<path>      the engine's symbol map (default: godot.syms next to the executable)
//
// The engine ships stripped; CI writes godot.syms (nm output with the build id on its first line) beside it so
// engine addresses come out as function names. Nothing here depends on the engine, so it builds and tests alone.
#pragma once

#include <string>
#include <vector>

namespace droiddeck_prof {

// Starts sampling the calling thread when DROIDDECK_NATIVE_PROF asks for it. Returns a line for the log
// either way when the variable is set (started, or why not), and an empty string when it is unset.
std::string start_from_env();
// Starts sampling the calling thread at p_hz. p_syms is the symbol map's path ("" for the default).
std::string start(int p_hz, double p_window_sec, const std::string &p_syms);
bool active();
// Call once a frame on the sampled thread. Once a window has passed, returns its report lines.
std::vector<std::string> tick();
// The report for whatever has been sampled since the last one, now (for tests and shutdown).
std::vector<std::string> report_now();

} // namespace droiddeck_prof
