package com.droiddeck.launcher.session

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.telemetry.BuildInfo
import com.droiddeck.launcher.telemetry.RemoteConfig

/**
 * What this launch is running, for the loading screen and the corner card while the game starts. First the
 * [BOOT_LINES] boot lines ([BootLine]): the engine it starts with its build (what droiddeck-game-env reported once it
 * has, the switches' intent before) and the app build with its commit time; the screens show those larger. Then the
 * remote config's row and switches, the settings it wrote, the FEX components put in place, memory, and the last
 * remote-control command. Plus a line of flavour text.
 */
object LaunchInfo {
    /** The FEX line [ComponentUpdater.ensureForSession] gave this session. */
    @Volatile var fex: String? = null

    /** What droiddeck-game-env last said it started (its engine line in session.log), null before it says. Compose
     *  state, so the loading screen's lines are read again when it changes. */
    var engine: BootLine.Engine? by mutableStateOf(null)
        private set

    /** The engine and app build lines lead [lines]; the screens show them larger than the rest. */
    const val BOOT_LINES = 2
    /** The corner card comes back this long after a game's engine line, so a game started later in the session shows it too. */
    private const val ENGINE_SHOWN_MS = 60_000L
    /** A Steam branch asked for this long before the session started still belongs to its launch. */
    private const val BRANCH_BEFORE_SESSION_MS = 120_000L

    private const val RECENT_COMMAND_MS = 30 * 60_000L
    private const val AFTER_GAME_MS = 45_000L
    private const val NO_GAME_MS = 180_000L

    /** When the loading screen gave way to the first frame this session (0 before), and when the game itself started. */
    @Volatile private var firstFrameAt = 0L
    @Volatile private var gameAt = 0L
    @Volatile private var engineAt = 0L
    @Volatile private var sessionAt = 0L

    /** A new session: forget the last one's FEX and engine lines and corner card. */
    fun reset(now: Long = System.currentTimeMillis()) {
        fex = null; engine = null; firstFrameAt = 0L; gameAt = 0L; engineAt = 0L; sessionAt = now
    }

    /** droiddeck-game-env's engine line arrived (read off the UI thread by FrameLogComponent). */
    fun engineStarted(started: BootLine.Engine, now: Long) { engine = started; engineAt = now }

    fun firstFrame(now: Long) { if (firstFrameAt == 0L) firstFrameAt = now }

    /** The corner card shows from the first frame until 45 s after the game starts, or 3 min if no game starts, and for
     *  a minute after each engine line (a game started later in the same session). */
    fun cornerVisible(now: Long, gameRunning: Boolean): Boolean {
        if (engineAt != 0L && now - engineAt in 0 until ENGINE_SHOWN_MS) return true
        if (firstFrameAt == 0L) return false
        if (gameRunning && gameAt == 0L) gameAt = now
        return if (gameAt != 0L) now - gameAt < AFTER_GAME_MS else now - firstFrameAt < NO_GAME_MS
    }

    fun words(context: Context): BootLine.Words = BootLine.Words(
        app = context.getString(R.string.boot_app),
        nativeIntended = context.getString(R.string.boot_native_intended),
        native = context.getString(R.string.boot_native),
        protonKestrel = context.getString(R.string.boot_proton_kestrel),
        protonStock = context.getString(R.string.boot_proton_stock),
        proton = context.getString(R.string.boot_proton),
        skipped = context.getString(R.string.boot_native_skipped),
        beta = context.getString(R.string.boot_branch_beta),
        stable = context.getString(R.string.boot_branch_stable),
    )

    /** The Steam branch this launch asked for, if its game offers one. */
    fun steamBranch(): String? = SteamBranch.last?.takeIf { it.at >= sessionAt - BRANCH_BEFORE_SESSION_MS }?.branch

    /** The engine line: "Native engine (native-engine-54) · beta", "Proton + FEX (FEX-…)", … (see [BootLine.engine]). */
    fun engineLine(context: Context, on: Set<String>, words: BootLine.Words = words(context)): String =
        BootLine.engine(words, Experiments.NATIVE_ENGINE in on, Experiments.FEX_KESTREL in on, engine, steamBranch())

    /** The app build line: "Lanner e890b89 · Nov 14 15:57 UTC · perf/switchboard". */
    fun appLine(words: BootLine.Words): String =
        BootLine.app(words, BuildInfo.shortCommit(), BuildConfig.COMMIT_TIME, BuildInfo.branch())

    fun lines(context: Context): List<String> {
        val on = Experiments.session ?: runCatching { Experiments.enabled(context) }.getOrDefault(emptySet())
        val words = words(context)
        val out = ArrayList<String>()
        out += engineLine(context, on, words)
        out += appLine(words)
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
        "Unhooding the falcon.",
        "Waiting for a thermal.",
        "Teaching an ARM to think in x86.",
        "Proton says it's Windows. Nobody tell the game.",
        "Translating instructions. Some nuance may be lost.",
        "Asking Steam nicely.",
        "The falcon is ready. The game is loading.",
        "Warming the shader cache.",
        "Your phone is pretending to be a PC. Be supportive.",
        "Four layers of translation, one falcon.",
        "Lanners spot prey from a mile off. Frames take longer.",
        "Two architectures walk into a phone.",
    )
}
