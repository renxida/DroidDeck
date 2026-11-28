package com.droiddeck.launcher.session

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The loading screen's "what is booting" lines, the first two of [LaunchInfo.lines]: which engine this launch
 * starts with its build, and which app build is running. Pure, so the wording is tested on the JVM; the words
 * come from strings.xml ([Words]).
 *
 * The app only knows the intended engine up front (the native-engine and fex-kestrel switches). droiddeck-game-env
 * says what it really started on a [MARKER] line in session.log, which [com.droiddeck.launcher.telemetry.FrameLogComponent]
 * already tails: once when the native engine starts, and once when Proton starts, with why native was skipped
 * (tools/tests/test_boot_line.py checks the script prints what [parse] reads).
 */
object BootLine {
    const val MARKER = "droiddeck-game-env: engine: "
    private const val SKIPPED = "; native skipped: "
    private const val UNKNOWN = "unknown"
    private val TIME = DateTimeFormatter.ofPattern("MMM d HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    enum class Path { NATIVE, PROTON }

    /** What droiddeck-game-env started: the path, its build (the native component's tag or the FEX version name), and
     *  why the native engine was skipped when it fell back to Proton. */
    data class Engine(val path: Path, val build: String, val skipped: String? = null) {
        fun fields(): Map<String, String?> =
            mapOf("path" to path.name.lowercase(Locale.ROOT), "build" to build, "native_skipped" to skipped)
    }

    /** The templates, from strings.xml (boot_*): [native] takes the component tag, [proton] the FEX version name,
     *  [skipped] the reason and then the Proton line; [protonKestrel]/[protonStock] stand in before the guest says. */
    class Words(
        val app: String,
        val nativeIntended: String,
        val native: String,
        val protonKestrel: String,
        val protonStock: String,
        val proton: String,
        val skipped: String,
        val beta: String,
        val stable: String,
    )

    /** [line] is droiddeck-game-env's engine line ("…engine: native native-engine-54", "…engine: proton FEX-…; native
     *  skipped: Steam library gap"); null for any other line. */
    fun parse(line: String): Engine? {
        val text = line.trim()
        if (!text.startsWith(MARKER)) return null
        val rest = text.substring(MARKER.length)
        val head = rest.substringBefore(SKIPPED).trim()
        val skipped = rest.substringAfter(SKIPPED, "").trim().ifEmpty { null }
        val path = when (head.substringBefore(' ')) {
            "native" -> Path.NATIVE
            "proton" -> Path.PROTON
            else -> return null
        }
        return Engine(path, head.substringAfter(' ', "").trim().ifEmpty { UNKNOWN }, skipped)
    }

    /** "Lanner e890b89 · Nov 14 15:57 UTC · perf/switchboard"; a missing time or branch is left out. */
    fun app(words: Words, commit: String, commitTimeMs: Long, branch: String): String {
        val parts = listOf(
            commit.trim().take(7).ifEmpty { UNKNOWN },
            if (commitTimeMs > 0) TIME.format(Instant.ofEpochMilli(commitTimeMs)) else "",
            branch.trim().takeIf { it != UNKNOWN }.orEmpty(),
        )
        return words.app.format(parts.filter { it.isNotEmpty() }.joinToString(" · "))
    }

    /**
     * The engine line: what droiddeck-game-env said it started ([actual]), or until it says, what the switches
     * intend ([nativeEngine], [kestrelFex]). [steamBranch] is the branch the launch asked for (SteamBranch.BETA or
     * SteamBranch.STABLE), null when the game offers no choice.
     */
    fun engine(words: Words, nativeEngine: Boolean, kestrelFex: Boolean, actual: Engine?, steamBranch: String?): String {
        val fex = if (kestrelFex) words.protonKestrel else words.protonStock
        fun proton(build: String) = if (build == UNKNOWN) fex else words.proton.format(build)
        val line = when {
            actual == null -> if (nativeEngine) words.nativeIntended else fex
            actual.path == Path.NATIVE -> words.native.format(actual.build)
            actual.skipped != null -> words.skipped.format(actual.skipped, proton(actual.build))
            else -> proton(actual.build)
        }
        val branch = when (steamBranch) {
            SteamBranchNames.BETA -> words.beta
            SteamBranchNames.STABLE -> words.stable
            else -> null
        }
        return if (branch == null) line else "$line · $branch"
    }

    /** SteamBranch's values, here so this file stands alone (SteamBranch needs Android). */
    internal object SteamBranchNames {
        const val BETA = "beta"
        const val STABLE = "public"
    }
}
