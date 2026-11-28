package com.droiddeck.launcher.update

/** The decisions the self-updater makes, free of Android so they can be tested on the JVM. */
object SelfUpdateRules {
    const val CHECK_EVERY_MS = 30 * 60_000L

    /**
     * An install first re-checks the release when the last check is older than this, so the person never installs a
     * staged build that a newer release has already replaced (an unchanged release costs one 304).
     */
    const val FRESH_FOR_INSTALL_MS = 2 * 60_000L

    /** What a build says about itself: versionCode, and the commit and commit time it embeds in its manifest. */
    data class Build(val versionCode: Long, val commit: String, val commitTimeMs: Long)

    /**
     * Null when [candidate] should replace [installed], otherwise why not. CI leaves versionCode unchanged between
     * builds, so at an equal versionCode a build counts as newer only when it embeds a different commit made later.
     */
    fun notNewerReason(candidate: Build, installed: Build): String? {
        if (candidate.versionCode < installed.versionCode) return "versionCode ${candidate.versionCode} is older than ${installed.versionCode}"
        if (candidate.versionCode > installed.versionCode) return null
        val theirs = candidate.commit.trim().lowercase()
        val ours = installed.commit.trim().lowercase()
        if (theirs.isEmpty()) return "the APK names no commit"
        if (ours.isNotEmpty() && (theirs.startsWith(ours) || ours.startsWith(theirs))) return "same commit ${theirs.take(7)}"
        if (candidate.commitTimeMs <= 0L) return "the APK names no commit time"
        if (installed.commitTimeMs <= 0L) return "the installed build has no commit time"
        if (candidate.commitTimeMs <= installed.commitTimeMs) return "commit ${theirs.take(7)} is not newer than ${ours.take(7)}"
        return null
    }

    /** The two certificate sets are the same: same count, each certificate in one has a byte-equal match in the other. */
    fun sameCertificates(a: List<ByteArray>, b: List<ByteArray>): Boolean {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return false
        return a.all { x -> b.any { it.contentEquals(x) } } && b.all { y -> a.any { it.contentEquals(y) } }
    }

    /** The manifest values carry a prefix so aapt keeps them as strings ("sha:<commit>", "t:<seconds>"). */
    fun parseCommit(value: String?): String = value?.trim()?.removePrefix("sha:")?.trim()
        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,64}")) } ?: ""

    fun parseCommitTimeMs(value: String?): Long =
        value?.trim()?.removePrefix("t:")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000L } ?: 0L

    /** A check runs only for a build that tracks the release, with no session, at most every [CHECK_EVERY_MS]. */
    fun checkDue(nowMs: Long, lastCheckMs: Long, sessionActive: Boolean, force: Boolean = false): Boolean {
        if (force) return true
        if (sessionActive) return false
        return lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_EVERY_MS
    }

    /** True when an install should re-check the release first ([FRESH_FOR_INSTALL_MS]). */
    fun recheckBeforeInstall(nowMs: Long, lastCheckMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= FRESH_FOR_INSTALL_MS

    /**
     * A check (and its download) never starts while an install is under way, so the screen never shows a download
     * racing the system's install prompt, and a download can't replace the APK the prompt is installing.
     */
    fun mayStartCheck(installing: Boolean, alreadyChecking: Boolean): Boolean = !installing && !alreadyChecking

    /**
     * The install starts by itself only on the resumed front screen with no session, and only once per build:
     * after the person cancels the system prompt, only the front screen's Install button starts it again.
     */
    fun mayStartInstall(sessionActive: Boolean, frontResumed: Boolean, staged: Boolean, declined: Boolean, byPerson: Boolean = false): Boolean =
        staged && !sessionActive && frontResumed && (byPerson || !declined)

    /** The system's confirmation shows only over the front screen, never over a session. */
    fun mayPrompt(sessionActive: Boolean, frontResumed: Boolean): Boolean = frontResumed && !sessionActive
}
