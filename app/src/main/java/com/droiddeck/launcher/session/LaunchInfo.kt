package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.telemetry.BuildInfo
import com.droiddeck.launcher.telemetry.RemoteConfig

/**
 * What this launch is running, for the loading screen and the corner card while the game starts: the mode
 * (native engine, Kestrel FEX or stock FEX), the build, the remote config's row and switches, the settings
 * it wrote, the FEX build put in place, and the last remote-control command. Plus a line of flavour text.
 */
object LaunchInfo {
    /** The FEX line [ComponentUpdater.ensureForSession] gave this session. */
    @Volatile var fex: String? = null

    private const val RECENT_COMMAND_MS = 30 * 60_000L
    private const val AFTER_GAME_MS = 45_000L
    private const val NO_GAME_MS = 180_000L

    /** When the loading screen gave way to the first frame this session (0 before), and when the game itself started. */
    @Volatile private var firstFrameAt = 0L
    @Volatile private var gameAt = 0L

    /** A new session: forget the last one's FEX line and corner card. */
    fun reset() { fex = null; firstFrameAt = 0L; gameAt = 0L }

    fun firstFrame(now: Long) { if (firstFrameAt == 0L) firstFrameAt = now }

    /** The corner card shows from the first frame until 45 s after the game starts, or 3 min if no game starts. */
    fun cornerVisible(now: Long, gameRunning: Boolean): Boolean {
        if (firstFrameAt == 0L) return false
        if (gameRunning && gameAt == 0L) gameAt = now
        return if (gameAt != 0L) now - gameAt < AFTER_GAME_MS else now - firstFrameAt < NO_GAME_MS
    }

    fun mode(on: Set<String>): String = when {
        Experiments.NATIVE_ENGINE in on -> "native engine (Proton if it can't start)"
        Experiments.FEX_KESTREL in on -> "Proton on Kestrel FEX"
        else -> "Proton on stock FEX"
    }

    fun lines(context: Context): List<String> {
        val on = Experiments.session ?: runCatching { Experiments.enabled(context) }.getOrDefault(emptySet())
        val out = ArrayList<String>()
        out += "mode   " + mode(on)
        out += "build  " + BuildInfo.branch() + " @ " + BuildInfo.shortCommit()
        RemoteConfig.last?.let { applied ->
            out += "config " + (if (applied.row != null) "row ${applied.row + 1} of ${applied.rows}" else "fixed") +
                " · " + applied.source
            if (applied.session.isNotEmpty())
                out += "set    " + applied.session.entries.joinToString(" ") { "${it.key}=${it.value}" }
            if (applied.layouts.isNotEmpty()) out += "pads   " + applied.layouts.joinToString(" ")
        }
        out += "on     " + Experiments.line(on)
        fex?.let { line -> out += "fex    " + line.removePrefix("fex: ").take(160) }
        runCatching { MemoryReadout.parseMeminfo(java.io.File("/proc/meminfo").readText()) }.getOrNull()?.let { out += memory(it) }
        runCatching {
            val prefs = context.getSharedPreferences("agent_remote", Context.MODE_PRIVATE)
            val last = prefs.getString("last", null)
            if (last != null && System.currentTimeMillis() - prefs.getLong("last_at", 0L) < RECENT_COMMAND_MS)
                out += "remote " + last
        }
        return out
    }

    /** Below this much available memory, or this share of swap left, the game is likely to stall on swap. */
    private const val LOW_AVAILABLE_MB = 3072L
    private const val LOW_SWAP_FREE_SHARE = .2

    /**
     * Memory at launch: what is available and how full swap (zram) already is, plus a hint to close other apps when
     * little is left (a 14 Nov session started with swap 95% full and froze for seconds at a time). Nothing is closed.
     */
    internal fun memory(d: MemoryReadout.Device): List<String> {
        val mb = 1024L
        val out = arrayListOf("memory " + "${d.availableKb / mb} MB free" +
            (if (d.swapTotalKb > 0) " · swap ${d.swapUsedKb / mb} of ${d.swapTotalKb / mb} MB used" else ""))
        val low = d.availableKb / mb < LOW_AVAILABLE_MB || (d.swapTotalKb > 0 && d.swapFreeKb < d.swapTotalKb * LOW_SWAP_FREE_SHARE)
        if (low) out += "       low on memory: closing other apps will help"
        return out
    }

    /** Flavour text: one line, chosen by [seed] so it holds still for a whole launch. */
    fun flavor(seed: Long): String = FLAVOR[Math.floorMod(seed, FLAVOR.size.toLong()).toInt()]

    internal val FLAVOR = listOf(
        "The lanner circles twice before it stoops.",
        "Hooding the falcon. Checking the jesses.",
        "Thermals rising over the Spire.",
        "A lanner hunts in pairs; Proton and FEX take the left and right.",
        "Translating x86 one wingbeat at a time.",
        "Feathers preened, frame pacing set to thirty.",
        "The falconer whistles. The deck answers.",
        "Riding the updraft from the charger.",
        "Scanning the desert floor for a first frame.",
        "Lanners fly low and fast. So should frames.",
        "Coaxing a Windows game onto an Android glove.",
        "The Neow whale is waiting. The falcon is not.",
    )
}
