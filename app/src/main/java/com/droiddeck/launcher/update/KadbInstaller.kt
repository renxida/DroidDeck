package com.droiddeck.launcher.update

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.droiddeck.launcher.core.GlobalSettings
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.session.AutoTest
import com.droiddeck.launcher.telemetry.Timeline
import com.flyfishxu.kadb.Kadb
import java.io.File

/**
 * Installs an update with no prompt, through the phone's own Wireless debugging and the Kadb pairing that
 * Setup already makes: the verified APK is pushed to [KadbInstallRules.REMOTE_APK] and `pm install -r -t` runs
 * as the shell user. Opt-in (Setup > Diagnostics > Install updates over Wireless debugging, off by default), and
 * only while the phone charges with the screen off, cool, with no session; otherwise [install] returns false
 * and the system's prompt is used. Android turns Wireless debugging off on a reboot or a network change, so a
 * connection is opened each time and a failed one also falls back to the prompt.
 *
 * The install ends this process. A marker written just before it is read on the next start ([register]), which
 * records `update.installed` with `installed_via=kadb` and the old and new build, and runs the configured test.
 */
object KadbInstaller : UpdateInstaller {
    private const val TAG = "SelfUpdate"
    private const val PREFS = "kadb_install"
    private const val ENABLED = "enabled"
    private const val REACHABLE = "reachable"
    private const val CHECKED_AT = "checked_at"
    private const val LAST = "last"
    private const val MARKER = "kadb-update-marker.txt"
    /** rw-r--r--, so the shell user's pm can read the pushed APK. */
    private val APK_MODE = "644".toInt(8)
    /** A large APK takes a while to push and to install; a dead connection must not hold the updater forever. */
    private const val SOCKET_TIMEOUT_MS = 5 * 60_000

    override val name = "kadb"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, on).apply()
    }

    /** What the last connection or install came to, for the switch's subtitle; null before any. */
    fun lastResult(context: Context): String? = prefs(context).getString(LAST, null)

    /** Whether Wireless debugging answered the last time a connection was tried; null before any. */
    fun reachable(context: Context): Boolean? =
        prefs(context).takeIf { it.contains(REACHABLE) }?.getBoolean(REACHABLE, false)

    /** When that was, in ms; 0 before any. */
    fun checkedAt(context: Context): Long = prefs(context).getLong(CHECKED_AT, 0L)

    /** From app start: put this backend ahead of the system's prompt, and finish an install the last process began. */
    fun register(context: Context) {
        val app = context.applicationContext
        if (SelfUpdate.installers.none { it === KadbInstaller }) SelfUpdate.installers.add(0, KadbInstaller)
        runCatching { afterRestart(app) }.onFailure { Log.w(TAG, "kadb marker: ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun unattended(context: Context): Boolean = enabled(context)

    private fun sessionBusy(): Boolean = SelfUpdate.sessionActive() || AutoTest.sessionBusy() || AutoTest.runningId != null

    internal fun state(app: Context): KadbInstallRules.State {
        val power = runCatching { AutoTest.power(app) }.getOrNull()
        return KadbInstallRules.State(
            enabled = enabled(app),
            paired = runCatching { WirelessAdbFix.hasSavedPairing(app) }.getOrDefault(false),
            wirelessDebugging = runCatching { GlobalSettings.wirelessDebuggingOn(app) }.getOrNull(),
            sessionBusy = sessionBusy(),
            charging = power?.charging == true,
            interactive = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true,
            thermal = power?.thermal ?: 0,
            batteryC = power?.batteryC ?: Float.NaN,
        )
    }

    override fun install(context: Context, verifiedApk: File, onResult: (UpdateInstaller.Result) -> Unit): Boolean {
        val app = context.applicationContext
        KadbInstallRules.skip(state(app))?.let { Log.i(TAG, "kadb: $it"); return false }
        val result: UpdateInstaller.Result? = try {
            WirelessAdbFix.withAdb(app, socketTimeoutMs = SOCKET_TIMEOUT_MS) { adb ->
                // Throwing here is safe: nothing has changed yet, and the advertised port gets a second try.
                val probe = adb.shell("echo ok")
                check(probe.exitCode == 0 && probe.output.trim() == "ok") { "no answer (exit ${probe.exitCode})" }
                remember(app, reachable = true, last = null)
                // Finding the port can take a few seconds; look again before changing anything.
                if (sessionBusy()) null else pushAndInstall(app, adb, verifiedApk)
            }
        } catch (e: Exception) {
            Log.i(TAG, "kadb: Wireless debugging not reachable: ${e.javaClass.simpleName}: ${e.message}")
            remember(app, reachable = false, last = "Wireless debugging not reachable")
            return false
        }
        if (result == null) return false
        if (result is UpdateInstaller.Result.Failed) remember(app, reachable = true, last = "Install failed: ${result.message}")
        onResult(result)
        return true
    }

    /** Never throws: once the APK is on its way, the result is reported rather than handed to the next backend. */
    private fun pushAndInstall(app: Context, adb: Kadb, apk: File): UpdateInstaller.Result {
        val remote = KadbInstallRules.REMOTE_APK
        try {
            adb.push(apk, remote, APK_MODE, apk.lastModified())
        } catch (e: Exception) {
            runCatching { adb.shell("rm -f $remote") }
            return UpdateInstaller.Result.Failed("push: ${e.javaClass.simpleName}: ${e.message}")
        }
        val marker = marker(app)
        val installed = SelfUpdate.installed()
        runCatching { marker.writeText(KadbInstallRules.formatMarker(
            KadbInstallRules.Marker(installed.versionCode, installed.commit, System.currentTimeMillis()))) }
            .onFailure { Log.w(TAG, "kadb marker not written: ${it.message}") }
        Log.i(TAG, "kadb: installing ${apk.length()} bytes")
        val output = try {
            adb.shell(KadbInstallRules.installCommand(remote)).allOutput
        } catch (e: Exception) {
            // pm may still finish; the marker stays so the next start can tell.
            return UpdateInstaller.Result.Failed("pm install: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (KadbInstallRules.installSucceeded(output)) return UpdateInstaller.Result.Success
        marker.delete()
        return UpdateInstaller.Result.Failed(KadbInstallRules.failureLine(output))
    }

    private fun marker(app: Context) = File(app.noBackupFilesDir, MARKER)

    private fun remember(app: Context, reachable: Boolean, last: String?) {
        prefs(app).edit().putBoolean(REACHABLE, reachable).putLong(CHECKED_AT, System.currentTimeMillis()).apply {
            if (last != null) putString(LAST, last)
        }.apply()
    }

    /** The first start after an install this backend began: record how it went, and run the configured test. */
    private fun afterRestart(app: Context) {
        val file = marker(app)
        if (!file.isFile) return
        val marker = KadbInstallRules.parseMarker(runCatching { file.readText() }.getOrNull())
        file.delete()
        if (marker == null) return
        val running = SelfUpdate.installed()
        val fields = mapOf(
            "installed_via" to name,
            "old_version_code" to marker.versionCode,
            "old_commit" to marker.commit,
            "new_version_code" to running.versionCode,
            "new_commit" to running.commit,
            "started_ms" to marker.atMs,
        )
        if (!KadbInstallRules.installedSince(marker, running)) {
            Log.i(TAG, "kadb: the install begun at ${marker.atMs} did not replace this build")
            Timeline.mark("update.not_installed", fields)
            prefs(app).edit().putString(LAST, "The last install over Wireless debugging did not finish").apply()
            return
        }
        Log.i(TAG, "kadb: updated ${marker.commit.take(7)} -> ${running.commit.take(7)}")
        Timeline.mark("update.installed", fields)
        prefs(app).edit().putString(LAST, "Installed ${running.commit.take(7).ifEmpty { running.versionCode.toString() }} over Wireless debugging").apply()
        Thread({
            AutoTest.schedule(app)
            val result = runCatching { AutoTest.check(app) }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
            Log.i("AutoTest", "after update: $result")
        }, "kadb-update-autotest").start()
    }
}
