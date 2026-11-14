package com.droiddeck.launcher.telemetry

/**
 * Whether the window in front is a game, from the HUD ring's focus records (engine label, pid, Steam flag).
 * A game whose frames carry no API name of their own (Slay the Spire 2's Godot on the PowerVR WSI path) shows up
 * as the bare compositor, engine GAMESCOPE, with its own pid; gamescope's start-up and Steam's own windows show
 * the same label. So a GAMESCOPE record counts as a game only with a real pid that was never Steam's.
 */
class GameFocus {
    private val steamPids = HashSet<Int>()

    fun isGame(engine: String?, pid: Int, steam: Boolean): Boolean {
        if (steam) {
            if (pid > 0 && steamPids.size < MAX_PIDS) steamPids.add(pid)
            return false
        }
        if (engine.isNullOrEmpty() || engine == "none" || pid <= 0 && engine == "GAMESCOPE") return false
        return engine != "GAMESCOPE" || pid !in steamPids
    }

    private companion object { const val MAX_PIDS = 64 }
}
