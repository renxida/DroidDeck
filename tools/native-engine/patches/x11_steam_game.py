#!/usr/bin/env python3
"""Mark a prestarted engine's windows for gamescope with the STEAM_GAME X property.

    x11_steam_game.py GODOT_SRC

gamescope gives each window an app id: the STEAM_GAME property when it is set (authoritative), else the AppId of
the "reaper SteamLaunch AppId=N" process above the window's _NET_WM_PID. A game Steam launched gets its id from
the reaper; an engine droiddeck-native-godot prestarted before Steam launched the game has no reaper above it,
so it would get id 0 and could be shown or focused while it waits (gamescope 3.16 steamcompmgr; phone side's
reading, Nov 26).

Only in an engine started with DROIDDECK_NATIVE_HOLD_DIR set (prestart): each window gets STEAM_GAME=1, an id no
base layer lists, while the Steam bridge holds the game; once the bridge has released it (the hold variable is
gone and the launch's SteamAppId set), every window gets STEAM_GAME=<SteamAppId>, checked once a frame on the
main thread until then, so gamescope focuses it like any game Steam launched. Any other engine changes nothing.
Fails unless every anchor is found exactly once.
"""
import os
import sys

MARK = "// Kestrel (x11_steam_game.py)"

INCLUDE = '#include "display_server_x11.h"\n'
HELPERS = INCLUDE + """
""" + MARK + """: STEAM_GAME for a prestarted engine's windows.
#include <stdlib.h>

// 1 while the bridge holds the game (an id no gamescope base layer lists), then the launch's SteamAppId.
static long droiddeck_steam_game_id() {
	const char *hold = getenv("DROIDDECK_NATIVE_HOLD_DIR");
	if (hold && *hold) {
		return 1;
	}
	const char *app = getenv("SteamAppId");
	return app ? atol(app) : 0;
}

static bool droiddeck_steam_game_prestarted() {
	static const bool v = [] {
		const char *hold = getenv("DROIDDECK_NATIVE_HOLD_DIR");
		return hold && *hold;
	}();
	return v;
}

static long droiddeck_steam_game_last = 0;

static void droiddeck_steam_game_mark(Display *p_display, ::Window p_window, long p_id) {
	Atom steam_game = XInternAtom(p_display, "STEAM_GAME", False);
	if (steam_game != None && p_id > 0) {
		XChangeProperty(p_display, p_window, steam_game, XA_CARDINAL, 32, PropModeReplace, (unsigned char *)&p_id, 1);
	}
}
"""

PID = """			if (net_wm_pid != None) {
				XChangeProperty(x11_display, wd.x11_window, net_wm_pid, XA_CARDINAL, 32, PropModeReplace, (unsigned char *)&pid, 1);
			}
		}
"""
PID_NEW = PID + """		if (droiddeck_steam_game_prestarted()) { """ + MARK + """
			droiddeck_steam_game_last = droiddeck_steam_game_id();
			droiddeck_steam_game_mark(x11_display, wd.x11_window, droiddeck_steam_game_last);
		}
"""

EVENTS = """void DisplayServerX11::process_events() {
	ERR_FAIL_COND(!Thread::is_main_thread());

	_THREAD_SAFE_LOCK_
"""
EVENTS_NEW = EVENTS + """
	""" + MARK + """: once the bridge releases a prestarted game, its windows take the launch's app id.
	if (droiddeck_steam_game_prestarted() && droiddeck_steam_game_last <= 1) {
		const long id = droiddeck_steam_game_id();
		if (id != droiddeck_steam_game_last && id > 0) {
			droiddeck_steam_game_last = id;
			for (const KeyValue<WindowID, WindowData> &E : windows) {
				droiddeck_steam_game_mark(x11_display, E.value.x11_window, id);
			}
			XFlush(x11_display);
			print_line(vformat("native_compat: STEAM_GAME %d on %d windows", (int64_t)id, windows.size()));
		}
	}
"""

EDITS = [("include", INCLUDE, HELPERS), ("_NET_WM_PID", PID, PID_NEW), ("process_events", EVENTS, EVENTS_NEW)]


def patch(source):
    if MARK in source:
        raise SystemExit("x11_steam_game: already applied")
    for name, old, new in EDITS:
        if source.count(old) != 1:
            raise SystemExit(f"x11_steam_game: expected the {name} anchor exactly once")
        source = source.replace(old, new)
    return source


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    path = os.path.join(sys.argv[1], "platform", "linuxbsd", "x11", "display_server_x11.cpp")
    with open(path, encoding="utf-8") as f:
        src = f.read()
    with open(path, "w", encoding="utf-8") as f:
        f.write(patch(src))
    print("x11_steam_game: patched", path)


if __name__ == "__main__":
    main()
