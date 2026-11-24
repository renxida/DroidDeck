// Kestrel native-engine compatibility module. Clean-room: no Esoteric or FMOD code.
//
// Render census: what in the running scene makes the 2D renderer start render passes. Each canvas layer with
// something visible, each back-buffer copy (a BackBufferCopy node or a material that reads the screen), each
// canvas group (CanvasGroup or clip_children) and each viewport costs a pass, and on a tile-based phone GPU
// every pass loads and stores the whole render target. One log line per window, from the main thread:
//   DROIDDECK_NATIVE_RENDER_CENSUS=<seconds>   a census every that many seconds (off when unset or 0)
// Only counts, sizes and resource names go to the log, never shader code or game data.
#pragma once

#include <string>

namespace droiddeck_census {

// Reads the env switch; returns the line to log about it, empty when off.
std::string start_from_env();
bool active();
// Call once per frame on the main thread; returns a census line when one is due, else empty.
std::string tick();

} // namespace droiddeck_census
