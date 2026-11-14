package com.droiddeck.launcher.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.telemetry.GamePhase
import com.droiddeck.launcher.telemetry.RemoteConfig
import com.droiddeck.launcher.telemetry.TelemetryUploadSettings
import org.json.JSONObject

/**
 * Unattended test runs. The telemetry repo's config/kestrel.json can ask for one:
 *
 *     "autotest": {"id": "tex-astc-1", "app": 2868840, "menu_seconds": 240, "branch": "beta", "repeat_hours": 0}
 *
 * While the phone charges and nobody is using it, Lanner launches that Steam game the usual way (the
 * session's experiments and settings come from the same config), lets it sit for menu_seconds once the
 * game's first frame is up, stops the session and lets the normal telemetry upload carry the report. A
 * test id runs once (two tries if it never reaches the game); repeat_hours > 0 runs it again after that
 * many hours. Nothing runs unless the person granted "Display over other apps", which Android requires
 * to open a screen from the background. A warm phone (thermal status moderate or above, or the battery at
 * 38 C) skips the run; unplugging or severe throttling stops one, and every run ends after menu_seconds
 * in the game or 15 minutes past that at most.
 */
object AutoTestPlan {
    data class Request(val id: String, val appId: Int, val menuSeconds: Int, val branch: String, val repeatHours: Int)

    data class State(
        val overlay: Boolean,
        val charging: Boolean,
        val battery: Int,
        val interactive: Boolean,
        val sessionBusy: Boolean,
        val nowMs: Long,
        /** PowerManager thermal status (0 none ... 6 shutdown), 0 when unknown. */
        val thermal: Int = 0,
        /** Battery temperature, NaN when unknown. */
        val batteryC: Float = Float.NaN,
    )

    data class Record(val id: String?, val attempts: Int, val startedMs: Long, val completed: Boolean)

    const val MIN_BATTERY = 30
    /** PowerManager.THERMAL_STATUS_MODERATE: a run would start already throttled and only heat it further. */
    const val WARM_STATUS = 2
    /** PowerManager.THERMAL_STATUS_SEVERE: stop a run that got here. */
    const val HOT_STATUS = 3
    const val MAX_BATTERY_C = 38f
    const val MAX_ATTEMPTS = 2
    private val ID = Regex("[A-Za-z0-9._-]{1,64}")

    fun parse(config: JSONObject?): Request? {
        val test = config?.optJSONObject("autotest") ?: return null
        val id = test.optString("id").takeIf { ID.matches(it) } ?: return null
        val app = test.optInt("app", 0).takeIf { it > 0 } ?: return null
        val menu = test.optInt("menu_seconds", 240).coerceIn(30, 1800)
        val branch = test.optString("branch", SteamBranch.BETA).takeIf { it == SteamBranch.BETA || it == SteamBranch.STABLE }
            ?: SteamBranch.BETA
        val repeat = test.optInt("repeat_hours", 0).coerceIn(0, 24 * 30)
        return Request(id, app, menu, branch, repeat)
    }

    /** Null when the request should run now; otherwise why not. */
    fun skip(request: Request?, state: State, record: Record): String? {
        if (request == null) return "no test requested"
        if (record.id == request.id) {
            val sinceMs = state.nowMs - record.startedMs
            if (request.repeatHours > 0) {
                if (sinceMs in 0 until request.repeatHours * 3_600_000L) return "ran ${sinceMs / 60_000} min ago"
            } else {
                if (record.completed) return "already ran"
                if (record.attempts >= MAX_ATTEMPTS) return "gave up after ${record.attempts} tries"
            }
        }
        if (!state.overlay) return "needs Display over other apps"
        if (!state.charging) return "not charging"
        if (state.battery in 0 until MIN_BATTERY) return "battery ${state.battery}%"
        if (state.thermal >= WARM_STATUS) return "phone is warm (thermal status ${state.thermal})"
        if (state.batteryC >= MAX_BATTERY_C) return "battery at ${state.batteryC.toInt()} C"
        if (state.interactive) return "screen is on"
        if (state.sessionBusy) return "a session is running"
        return null
    }

    fun nextRecord(request: Request, record: Record, nowMs: Long): Record =
        if (record.id == request.id && request.repeatHours == 0) record.copy(attempts = record.attempts + 1, startedMs = nowMs)
        else Record(request.id, 1, nowMs, false)

    /** What the watcher should do: null keeps waiting, otherwise the reason to stop. */
    fun stopReason(elapsedMs: Long, gameMs: Long, menuSeconds: Int, charging: Boolean, thermal: Int = 0): String? = when {
        !charging -> "unplugged"
        thermal >= HOT_STATUS -> "hot (thermal status $thermal)"
        gameMs >= menuSeconds * 1000L -> "done"
        elapsedMs >= LIMIT_MS + menuSeconds * 1000L -> "timeout"
        else -> null
    }

    const val LIMIT_MS = 15 * 60_000L
}

object AutoTest {
    private const val TAG = "AutoTest"
    const val EXTRA_ID = "autoTestId"
    const val EXTRA_MANUAL = "autoTestManual"
    private const val JOB_ID = 0x4b7e57
    private const val CHANNEL = "autotest"
    private const val NOTIFICATION = 0x4b7e58
    private const val POLL_MS = 5_000L

    @Volatile var runningId: String? = null
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("autotest", Context.MODE_PRIVATE)

    fun schedule(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoTestJob::class.java))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60_000L)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }.onFailure { Log.w(TAG, "could not schedule test runs: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun record(context: Context): AutoTestPlan.Record {
        val p = prefs(context)
        return AutoTestPlan.Record(p.getString("id", null), p.getInt("attempts", 0), p.getLong("started", 0L),
            p.getBoolean("completed", false))
    }

    private fun save(context: Context, record: AutoTestPlan.Record, result: String? = null) {
        val edit = prefs(context).edit().putString("id", record.id).putInt("attempts", record.attempts)
            .putLong("started", record.startedMs).putBoolean("completed", record.completed)
        if (result != null) edit.putString("result", result)
        edit.apply()
    }

    private data class Power(val charging: Boolean, val level: Int, val batteryC: Float, val thermal: Int)

    private fun power(context: Context): Power {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val deci = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull() ?: 0 else 0
        return Power(plugged, level, if (deci == Int.MIN_VALUE) Float.NaN else deci / 10f, thermal)
    }

    /** Runs on the job's thread: reads the config, decides, and launches. Returns what it did. */
    fun check(context: Context): String {
        val app = context.applicationContext
        if (!TelemetryUploadSettings.active(app)) return "telemetry off"
        val request = AutoTestPlan.parse(RemoteConfig.fetchNow(app))
        val power = power(app)
        val state = AutoTestPlan.State(
            overlay = Settings.canDrawOverlays(app),
            charging = power.charging,
            battery = power.level,
            thermal = power.thermal,
            batteryC = power.batteryC,
            interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true,
            sessionBusy = SessionState.running || SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) ||
                SessionState.installing != null || com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.isBusy(),
            nowMs = System.currentTimeMillis(),
        )
        val record = record(app)
        val reason = AutoTestPlan.skip(request, state, record)
        if (reason != null || request == null) {
            if (request != null && reason == "needs Display over other apps") askForOverlay(app, request.id)
            if (request != null) prefs(app).edit().putString("skipped", "${state.nowMs} $reason").apply()
            return reason ?: "no test requested"
        }
        save(app, AutoTestPlan.nextRecord(request, record, state.nowMs), "launching")
        launch(app, request)
        return "launched ${request.id}"
    }

    /**
     * The queued test, started by hand from Setup > Diagnostics: no charging, idle or screen checks, and
     * unplugging doesn't stop it. Calls back on the main thread with what happened.
     */
    fun runNow(context: Context, done: (String) -> Unit) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val result = runCatching {
                val request = AutoTestPlan.parse(RemoteConfig.fetchNow(app))
                when {
                    request == null -> app.getString(R.string.autotest_none_queued)
                    SessionState.running || SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) ->
                        app.getString(R.string.autotest_session_busy)
                    else -> {
                        val now = System.currentTimeMillis()
                        save(app, AutoTestPlan.nextRecord(request, record(app), now), "launching by hand")
                        main.post { launch(app, request, manual = true) }
                        app.getString(R.string.autotest_starting, request.id)
                    }
                }
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            main.post { done(result) }
        }, "autotest-now").start()
    }

    private fun launch(context: Context, request: AutoTestPlan.Request, manual: Boolean = false) {
        SteamBranch.request(context, request.appId, request.branch)
        runningId = request.id
        val intent = Intent(context, SessionActivity::class.java)
            .setAction(SessionService.ACTION_AGENT_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_STEAM)
            .putExtra(SessionService.EXTRA_STEAM_URL, "steam://rungameid/${request.appId}")
            .putExtra(EXTRA_ID, request.id)
            .putExtra(EXTRA_MANUAL, manual)
        context.startActivity(intent)
        Thread({ watch(context, request, manual) }, "autotest-watch").start()
    }

    private fun watch(context: Context, request: AutoTestPlan.Request, manual: Boolean) {
        val start = SystemClock.elapsedRealtime()
        var gameSince = 0L
        var stopped: String? = null
        var sawSession = false
        try {
            while (true) {
                Thread.sleep(POLL_MS)
                val now = SystemClock.elapsedRealtime()
                val busy = SessionState.running || SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED)
                if (busy) sawSession = true
                if (!busy) {
                    if (sawSession) break
                    if (now - start > 90_000L) { stopped = stopped ?: "never started (Android may have blocked the launch)"; break }
                    continue
                }
                if (GamePhase.inGame && gameSince == 0L) {
                    gameSince = now
                    SessionEvents.record("autotest.game", mapOf("id" to request.id, "after_ms" to now - start))
                }
                // A test run is attended by this watcher, not idle: keep the idle frame cap and idle stop off.
                SessionState.input()
                if (stopped == null) {
                    val gameMs = if (gameSince == 0L) 0L else now - gameSince
                    val power = power(context)
                    stopped = AutoTestPlan.stopReason(now - start, gameMs, request.menuSeconds, manual || power.charging,
                        power.thermal)
                    if (stopped != null) {
                        SessionEvents.record("autotest.stop", mapOf("id" to request.id, "reason" to stopped,
                            "game_ms" to gameMs, "elapsed_ms" to now - start))
                        SessionService.stop(context)
                    }
                }
                if (stopped != null && now - start > AutoTestPlan.LIMIT_MS + request.menuSeconds * 1000L + 120_000L) break
            }
        } catch (_: InterruptedException) {
        } finally {
            runningId = null
            // Out of time with the session still up (a stop that did not take): stop it again.
            if (stopped != null && (SessionState.running || SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED)))
                runCatching { SessionService.stop(context) }
            val result = stopped ?: "session ended"
            val record = record(context)
            save(context, record.copy(completed = record.completed || result == "done"), result)
            Log.i(TAG, "test ${request.id}: $result")
        }
    }

    private fun askForOverlay(context: Context, id: String) {
        val p = prefs(context)
        if (p.getString("asked", null) == id) return
        p.edit().putString("asked", id).apply()
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.autotest_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val action = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(R.string.autotest_permission_title))
                .setContentText(context.getString(R.string.autotest_permission_text))
                .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.autotest_permission_text)))
                .setContentIntent(action).setOnlyAlertOnce(true).setAutoCancel(true).build())
        }.onFailure { Log.w(TAG, "could not ask for the overlay permission", it) }
    }

    /** The line the session report carries, so a bundle says whether it came from a test run. */
    fun summary(context: Context): String {
        val p = prefs(context)
        return "autotest: id=${p.getString("id", null)} attempts=${p.getInt("attempts", 0)} " +
            "completed=${p.getBoolean("completed", false)} result=${p.getString("result", null)} " +
            "skipped=${p.getString("skipped", null)} overlay=${runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)}"
    }
}

class AutoTestJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val result = runCatching { AutoTest.check(this) }
                .getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
            Log.i("AutoTest", result)
            jobFinished(params, false)
        }, "autotest-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
