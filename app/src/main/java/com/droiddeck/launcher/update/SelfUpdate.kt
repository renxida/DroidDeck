package com.droiddeck.launcher.update

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.telemetry.BuildInfo
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the app on the fork's newest "latest" build. A CI build of [TRACK_BRANCH] checks when the front screen
 * resumes with no session, at most every 30 minutes; the signed remote "update" command checks at any time.
 * A download never installs by itself: the install starts on the resumed front screen with no session, and the
 * system asks the person to confirm. The one exception is opt-in: with Setup > Diagnostics > Install updates over
 * Wireless debugging on, [installUnattended] installs while the phone is idle on the charger ([KadbInstaller]).
 * An APK is installed only when its package is ours, its signing certificates equal ours exactly and it is newer
 * ([SelfUpdateRules.notNewerReason]); anything else is deleted.
 *
 * One thing happens at a time, so the front screen's line always says one clear state: a check or download never
 * starts while an install is under way ([SelfUpdateRules.mayStartCheck]), and an install first re-checks the release
 * when the last check is more than two minutes old ([SelfUpdateRules.recheckBeforeInstall]), so the person always
 * installs the newest build rather than one a newer release has replaced.
 */
object SelfUpdate {
    private const val TAG = "SelfUpdate"
    private const val PREFS = "self_update"
    private const val LAST_CHECK = "last_check"
    private const val DECLINED = "declined"
    private const val LAST_OUTCOME = "last_outcome"
    const val TRACK_BRANCH = "perf/switchboard"
    const val META_COMMIT = "chat.comb.lanner.SOURCE_COMMIT"
    const val META_COMMIT_TIME = "chat.comb.lanner.COMMIT_TIME"
    private const val CHANNEL = "self_update"
    private const val NOTIFICATION = 0x4b7e5a

    enum class Phase { IDLE, CHECKING, DOWNLOADING, READY, NEEDS_PERMISSION, INSTALLING }

    /** [available] is the staged build (READY, NEEDS_PERMISSION, INSTALLING), so the screen can name it. */
    data class Ui(val phase: Phase = Phase.IDLE, val percent: Int = -1, val available: SelfUpdateRules.Build? = null)

    /** What the front screen's update line shows. */
    var ui by mutableStateOf(Ui())
        private set

    /** Tried in order; a backend added later goes before the system prompt, which stays last as the fallback. */
    val installers: MutableList<UpdateInstaller> = CopyOnWriteArrayList(listOf<UpdateInstaller>(PackageInstallerPrompt))

    internal var http: UpdateHttp = UrlConnectionHttp

    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var front: WeakReference<Activity>? = null
    @Volatile private var pendingConfirm: Intent? = null
    @Volatile private var installing = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(context: Context) = File(context.filesDir, "self-update")

    fun sessionActive(): Boolean =
        SessionState.running || SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED)

    private val frontResumed: Boolean get() = front?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

    /**
     * Only CI builds of the tracked branch check by themselves; a local or PR build is left as the person put it.
     * CI's own unit tests run a CI build under Robolectric, which must not download the release.
     */
    fun tracksRelease(): Boolean =
        BuildConfig.CI_BUILD && BuildConfig.PR_NUMBER == 0 && BuildInfo.branch() == TRACK_BRANCH && Build.FINGERPRINT != "robolectric"

    fun installed(): SelfUpdateRules.Build =
        SelfUpdateRules.Build(BuildConfig.VERSION_CODE.toLong(), BuildConfig.SOURCE_COMMIT, BuildConfig.COMMIT_TIME)

    fun lastOutcome(context: Context): String? = prefs(context).getString(LAST_OUTCOME, null)

    private fun showUi(value: Ui) {
        if (Looper.myLooper() == Looper.getMainLooper()) ui = value else main.post { ui = value }
    }

    // ---- verification ----

    /** Null when [file] may be installed over this app, otherwise why not. */
    internal fun verify(context: Context, file: File): String? {
        val pm = context.packageManager
        val certFlag = certificateFlag()
        val info = pm.getPackageArchiveInfo(file.path, certFlag or PackageManager.GET_META_DATA) ?: return "not a readable APK"
        if (info.packageName != context.packageName) return "package ${info.packageName} is not ${context.packageName}"
        val ours = runCatching { pm.getPackageInfo(context.packageName, certFlag) }.getOrNull() ?: return "own package info unavailable"
        if (!SelfUpdateRules.sameCertificates(certificates(info), certificates(ours))) return "signing certificates differ"
        val meta = info.applicationInfo?.metaData
        val candidate = SelfUpdateRules.Build(versionCode(info),
            SelfUpdateRules.parseCommit(meta?.getString(META_COMMIT)),
            SelfUpdateRules.parseCommitTimeMs(meta?.getString(META_COMMIT_TIME)))
        return SelfUpdateRules.notNewerReason(candidate, installed())
    }

    /** What a staged APK says about itself, for the screen; null when it can't be read. */
    internal fun describe(context: Context, file: File): SelfUpdateRules.Build? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_META_DATA) ?: return null
        val meta = info.applicationInfo?.metaData
        SelfUpdateRules.Build(versionCode(info), SelfUpdateRules.parseCommit(meta?.getString(META_COMMIT)),
            SelfUpdateRules.parseCommitTimeMs(meta?.getString(META_COMMIT_TIME)))
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun certificateFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        else info.signatures?.map { it.toByteArray() } ?: emptyList()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun checker(app: Context) = UpdateCheck(dir(app), prefs(app), http, { verify(app, it) }) { percent ->
        showUi(Ui(Phase.DOWNLOADING, percent))
    }

    // ---- checking ----

    /**
     * One check now, on the calling (background) thread; null when another check or an install is running.
     * [forInstall] is the install's own re-check, which runs while that install holds [installing].
     */
    private fun runCheck(app: Context, forInstall: Boolean = false): UpdateCheck.Outcome? {
        if (!SelfUpdateRules.mayStartCheck(installing && !forInstall, false) || !busy.compareAndSet(false, true)) return null
        try {
            showUi(Ui(Phase.CHECKING))
            prefs(app).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
            val outcome = runCatching { checker(app).run() }
                .getOrElse { UpdateCheck.Outcome.Failed("${it.javaClass.simpleName}: ${it.message}") }
            Log.i(TAG, "check: ${outcome.line()}")
            prefs(app).edit().putString(LAST_OUTCOME, outcome.line()).apply()
            if (!forInstall) showStaged(app, outcome is UpdateCheck.Outcome.Ready)
            return outcome
        } finally {
            busy.set(false)
        }
    }

    private fun stagedBuild(app: Context): SelfUpdateRules.Build? =
        File(dir(app), UpdateCheck.APK).takeIf { it.isFile }?.let { describe(app, it) }

    private fun showStaged(app: Context, staged: Boolean) = showUi(when {
        !staged -> Ui()
        !canInstall(app) -> Ui(Phase.NEEDS_PERMISSION, available = stagedBuild(app))
        else -> Ui(Phase.READY, available = stagedBuild(app))
    })

    private fun canInstall(app: Context): Boolean =
        runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** From FrontEndActivity.onResume: show a waiting confirmation, check when due, start a staged install. */
    fun onFrontResumed(activity: Activity) {
        front = WeakReference(activity)
        val app = activity.applicationContext
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        if (sessionActive()) return
        pendingConfirm?.let { confirm ->
            pendingConfirm = null
            launchConfirm(activity, confirm)
            return
        }
        if (busy.get() || installing) return
        val due = tracksRelease() && SelfUpdateRules.checkDue(System.currentTimeMillis(), prefs(app).getLong(LAST_CHECK, 0L), sessionActive())
        // Hide a stale Install button at once: the check below may replace the staged build it would install.
        if (due) showUi(Ui(Phase.CHECKING))
        Thread({
            if (due) runCheck(app) ?: showStaged(app, checker(app).stagedReady() != null)
            else if (File(dir(app), UpdateCheck.APK).isFile) showStaged(app, checker(app).stagedReady() != null)
            else showUi(Ui())
            main.post { startInstall(app, byPerson = false) }
        }, "self-update").start()
    }

    fun onFrontPaused() {
        front = null
    }

    /** The front screen's Install button. */
    fun installNow(context: Context) = startInstall(context.applicationContext, byPerson = true)

    /** The front screen's Settings button, when this app may not yet install packages. */
    fun openInstallSettings(activity: Activity) {
        runCatching {
            activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
        }.onFailure { Log.w(TAG, "no unknown-sources settings: ${it.message}") }
    }

    private fun startInstall(app: Context, byPerson: Boolean) {
        val phase = ui.phase
        val staged = phase == Phase.READY || phase == Phase.NEEDS_PERMISSION
        val declined = prefs(app).getString(DECLINED, null)?.let { it == prefs(app).getString(UpdateCheck.HANDLED, null) } == true
        if (!SelfUpdateRules.mayStartInstall(sessionActive(), frontResumed, staged, declined, byPerson)) return
        if (!canInstall(app)) { showUi(Ui(Phase.NEEDS_PERMISSION)); return }
        if (busy.get() || (installing && !byPerson)) return
        installing = true
        showUi(Ui(Phase.INSTALLING, available = ui.available))
        Thread(work@{
            if (tracksRelease() && SelfUpdateRules.recheckBeforeInstall(System.currentTimeMillis(), prefs(app).getLong(LAST_CHECK, 0L)))
                runCheck(app, forInstall = true)
            showUi(Ui(Phase.INSTALLING, available = stagedBuild(app)))
            val file = if (busy.compareAndSet(false, true)) {
                try { checker(app).stagedReady() } finally { busy.set(false) }
            } else null
            if (file == null || sessionActive()) {
                installing = false
                showStaged(app, file != null)
                return@work
            }
            for (installer in installers) {
                val taken = runCatching { installer.install(app, file) { installResult(app, it) } }
                    .onFailure { Log.w(TAG, "${installer.name} failed: ${it.message}") }.getOrDefault(false)
                if (taken) {
                    Log.i(TAG, "installing with ${installer.name}")
                    return@work
                }
            }
            Log.w(TAG, "no installer took the update")
            installing = false
            showStaged(app, true)
        }, "self-update-install").start()
    }

    /**
     * With nobody at the phone (the charging-and-idle test job, or the remote "update" command away from the front
     * screen): check when due, then hand the staged update to the backends that install without a prompt
     * ([UpdateInstaller.unattended]). Does nothing unless such a backend is switched on, and never during a session.
     * Runs on the calling (background) thread; true when a backend took the update.
     */
    fun installUnattended(context: Context, check: Boolean = true): Boolean {
        val app = context.applicationContext
        val backends = installers.filter { runCatching { it.unattended(app) }.getOrDefault(false) }
        // With the front screen open the person's own flow installs; this must not race its prompt.
        if (backends.isEmpty() || sessionActive() || frontResumed) return false
        if (check && tracksRelease() && SelfUpdateRules.checkDue(System.currentTimeMillis(), prefs(app).getLong(LAST_CHECK, 0L), sessionActive()))
            runCheck(app)
        if (installing || sessionActive() || !busy.compareAndSet(false, true)) return false
        val file = try { checker(app).stagedReady() } finally { busy.set(false) }
        if (file == null) return false
        installing = true
        for (installer in backends) {
            val taken = runCatching { installer.install(app, file) { unattendedResult(app, installer.name, it) } }
                .onFailure { Log.w(TAG, "${installer.name} failed: ${it.message}") }.getOrDefault(false)
            if (taken) {
                Log.i(TAG, "installing unattended with ${installer.name}")
                return true
            }
        }
        installing = false
        return false
    }

    /**
     * An unattended backend's result. Its failure is not the person declining, so the front screen still offers the
     * system's prompt for this build by itself.
     */
    private fun unattendedResult(app: Context, backend: String, result: UpdateInstaller.Result) {
        if (result is UpdateInstaller.Result.Success || result is UpdateInstaller.Result.NeedsConfirmation) {
            installResult(app, result)
            return
        }
        val why = if (result is UpdateInstaller.Result.Failed) result.message else "cancelled"
        Log.i(TAG, "$backend did not install: $why")
        main.post {
            installing = false
            prefs(app).edit().putString(LAST_OUTCOME, "$backend: $why").apply()
        }
    }

    /** Where every installer's result ends up. */
    internal fun installResult(app: Context, result: UpdateInstaller.Result) {
        main.post {
            when (result) {
                is UpdateInstaller.Result.Success -> {
                    // The staged file is left for the next start to judge: if a check replaced it with a newer build
                    // meanwhile, deleting it here would lose that build until the next release.
                    installing = false
                    showUi(Ui())
                }
                is UpdateInstaller.Result.NeedsConfirmation -> {
                    val activity = front?.get()
                    if (activity != null && SelfUpdateRules.mayPrompt(sessionActive(), frontResumed)) launchConfirm(activity, result.confirm)
                    else { pendingConfirm = result.confirm; if (!sessionActive()) notifyReady(app) }
                }
                is UpdateInstaller.Result.Cancelled -> declined(app, "cancelled")
                is UpdateInstaller.Result.Failed -> declined(app, result.message)
            }
        }
    }

    private fun declined(app: Context, why: String) {
        installing = false
        Log.i(TAG, "install did not finish: $why")
        prefs(app).edit().putString(DECLINED, prefs(app).getString(UpdateCheck.HANDLED, null))
            .putString(LAST_OUTCOME, "install: $why").apply()
        showStaged(app, File(dir(app), UpdateCheck.APK).isFile)
    }

    private fun launchConfirm(activity: Activity, confirm: Intent) {
        runCatching { activity.startActivity(confirm) }
            .onFailure { Log.w(TAG, "confirmation not shown: ${it.message}"); declined(activity.applicationContext, "no confirmation") }
    }

    private fun notifyReady(app: Context) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.self_update_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, NOTIFICATION,
                Intent(app, com.droiddeck.launcher.FrontEndActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(NOTIFICATION, Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(app.getString(R.string.self_update_notification))
                .setContentText(app.getString(R.string.self_update_notification_text))
                .setContentIntent(open).setAutoCancel(true).build())
        }.onFailure { Log.w(TAG, "no notification: ${it.message}") }
    }

    // ---- remote ----

    /** The signed remote "update" command: check and download now, whatever the throttle; install as usual. */
    fun remoteUpdate(context: Context): JSONObject {
        val app = context.applicationContext
        val outcome = runCheck(app)
            ?: return JSONObject().put("ok", false).put("error", JSONObject().put("code", "BUSY").put("message", "a check or an install is running"))
        if (outcome is UpdateCheck.Outcome.Ready) main.post {
            if (frontResumed) startInstall(app, byPerson = false)
            else if (!sessionActive()) Thread({
                installUnattended(app, check = false)
                if (!sessionActive() && File(dir(app), UpdateCheck.APK).isFile) notifyReady(app)
            }, "self-update-unattended").start()
        }
        return JSONObject().put("ok", outcome !is UpdateCheck.Outcome.Failed)
            .put("outcome", outcome.line())
            .put("installed_commit", BuildInfo.shortCommit())
            .put("staged", outcome is UpdateCheck.Outcome.Ready)
            .put("can_install", canInstall(app))
            .put("session_running", sessionActive())
    }

    fun status(context: Context): String = buildString {
        append(ui.phase.name.lowercase())
        lastOutcome(context)?.let { append(", last: ").append(it) }
    }
}
