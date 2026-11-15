package com.droiddeck.launcher.update

import com.droiddeck.launcher.session.AutoTestPlan

/** The decisions [KadbInstaller] makes, free of Android so they can be tested on the JVM. */
object KadbInstallRules {
    /** Where the verified APK is pushed for `pm install`; the shell user can read it there. */
    const val REMOTE_APK = "/data/local/tmp/kestrel-update.apk"

    /** What the installer looks at before it touches Wireless debugging. */
    data class State(
        /** Setup > Diagnostics > Install updates over Wireless debugging. */
        val enabled: Boolean,
        /** A Kadb pairing is saved. */
        val paired: Boolean,
        /** The system's Wireless debugging setting; null when it can't be read. */
        val wirelessDebugging: Boolean?,
        /** A session, a test run, a runtime install or a package install is under way. */
        val sessionBusy: Boolean,
        val charging: Boolean,
        /** The screen is on; with it off the phone counts as idle, as for test runs. */
        val interactive: Boolean,
        /** PowerManager thermal status (0 none ... 6 shutdown), 0 when unknown. */
        val thermal: Int = 0,
        /** Battery temperature, NaN when unknown. */
        val batteryC: Float = Float.NaN,
        /** Whether a connection opened; null before one was tried. */
        val reachable: Boolean? = null,
    )

    /**
     * Null when the update may be installed over Wireless debugging now, otherwise why not; then the next backend
     * (the system's prompt) is tried. The cheap checks come first, so a connection is opened only when all of them
     * pass; [State.reachable] is filled in after that.
     */
    fun skip(state: State): String? {
        if (!state.enabled) return "switched off"
        if (!state.paired) return "no Wireless debugging pairing"
        if (state.sessionBusy) return "a session is running"
        if (!state.charging) return "not charging"
        if (state.interactive) return "screen is on"
        if (state.thermal >= AutoTestPlan.WARM_STATUS) return "phone is warm (thermal status ${state.thermal})"
        if (state.batteryC >= AutoTestPlan.MAX_BATTERY_C) return "battery at ${state.batteryC.toInt()} C"
        if (state.wirelessDebugging == false) return "Wireless debugging is off"
        if (state.reachable == false) return "Wireless debugging not reachable"
        return null
    }

    /**
     * One shell command: install, delete the pushed APK, then print what pm said. pm's output goes to a variable
     * first, so the delete still runs when the install ends our process and nobody is left to read the answer.
     */
    fun installCommand(remote: String = REMOTE_APK): String =
        "out=\$(pm install -r -t $remote 2>&1); status=\$?; rm -f $remote; echo \"\$out\"; exit \$status"

    /** pm prints a line that is exactly "Success" when the install went through. */
    fun installSucceeded(output: String): Boolean = output.lineSequence().any { it.trim() == "Success" }

    /** pm's failure, short enough for a status line. */
    fun failureLine(output: String): String =
        output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()?.take(200) ?: "pm printed nothing"

    /** Written before `pm install`, which ends our process; read on the next start. */
    data class Marker(val versionCode: Long, val commit: String, val atMs: Long)

    fun formatMarker(marker: Marker): String = "${marker.versionCode}\n${marker.commit}\n${marker.atMs}\n"

    fun parseMarker(text: String?): Marker? {
        val lines = text?.lines() ?: return null
        if (lines.size < 3) return null
        val code = lines[0].trim().toLongOrNull() ?: return null
        val at = lines[2].trim().toLongOrNull() ?: return null
        return Marker(code, lines[1].trim(), at)
    }

    /** The running build is not the one that started the install, so the install went through. */
    fun installedSince(marker: Marker, running: SelfUpdateRules.Build): Boolean {
        if (running.versionCode != marker.versionCode) return true
        val before = marker.commit.trim().lowercase()
        val now = running.commit.trim().lowercase()
        if (before.isEmpty() || now.isEmpty()) return false
        return !(before.startsWith(now) || now.startsWith(before))
    }
}
