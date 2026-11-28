package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Closes other apps' background processes so the session starts with memory to spare: once before Steam and the
 * guest start, and again before a game when the guest asks and little is available. The cached apps start fresh
 * the next time they open.
 *
 * First ActivityManager.killBackgroundProcesses for every other package. From Android 14 the platform drops calls
 * for other packages unless the caller holds the privileged KILL_ALL_BACKGROUND_PROCESSES
 * (ActivityManagerService.killBackgroundProcesses logs "Invalid packageName"), so the step measures what it freed.
 * When that is under [KILL_ENOUGH_MB], a balloon in the :balloon process ([MemoryBalloonService]) takes memory in
 * [STEP_MB] steps. Free pages under the high watermark make lmkd kill cached apps (adj 701 and up, kill reason
 * LOW_MEM); the balloon sits at PERCEPTIBLE_LOW_APP_ADJ 250 (BIND_NOT_PERCEPTIBLE from an app at 200 or below), so
 * in lmkd's highest-adj-first order it dies before this app (0 in the foreground) and before perceptible apps.
 */
internal object FreeMemory {
    private const val TAG = "FreeMemory"

    const val SESSION_START = "session_start"
    const val GAME_EXEC = "game_exec"
    /** The guest's request and the app's answer in the launch directory (droiddeck-game-env free_memory). */
    const val REQUEST = "free-memory"
    const val DONE = "free-memory-done"

    const val SESSION_NEED_MB = 4096
    const val GAME_NEED_MB = 3072
    const val KILL_ENOUGH_MB = 500
    const val STEP_MB = 256
    const val MAX_MB = 4096
    const val MAX_MS = 6_000L
    const val SETTLE_MS = 150L
    const val KILL_SETTLE_MS = 500L
    const val KILL_LOOP_MS = 3_000L
    const val KILL_GAIN_MB = 128
    const val QUIET_STEPS = 2
    const val FLOOR_MB = 256
    const val FULL_STALL_LIMIT = 0.25
    const val PERCEPTIBLE_ADJ = 200
    /** LmkRisk stays quiet this long after a run: PSI avg10 still shows the balloon's stall for a while. */
    const val QUIET_AFTER_MS = 30_000L

    @Volatile var quietUntil = 0L
        private set
    private val lock = Any()

    data class Sample(val availableKb: Long, val swapFreeKb: Long, val fullStallUs: Long?) {
        val headroomMb get() = (availableKb + swapFreeKb) / 1024
    }

    data class Result(
        val reason: String,
        val method: String,
        val packages: Int,
        val before: MemoryReadout.Device?,
        val after: MemoryReadout.Device?,
        val killFreedMb: Long?,
        val balloonPeakMb: Int = 0,
        val balloonMs: Long = 0,
        val balloonStop: String? = null,
        val appAdj: Int? = null,
        val balloonAdj: Int? = null,
        val ms: Long = 0,
    ) {
        val freedMb: Long? get() = if (before != null && after != null) headroomMb(after) - headroomMb(before) else null
    }

    fun headroomMb(d: MemoryReadout.Device) = (d.availableKb + d.swapFreeKb) / 1024

    /** Whether a run is worth it: under [needMb] of MemAvailable. */
    fun wanted(device: MemoryReadout.Device?, needMb: Int): Boolean = device != null && device.availableKb / 1024 < needMb

    /** Every other installed package, leaving out this one and [keep] (the launcher and keyboard just restart). */
    fun candidates(installed: Collection<String>, own: String, keep: Set<String>): List<String> =
        installed.filter { it.isNotEmpty() && it != own && it !in keep }.distinct().sorted()

    /** The package of a Settings.Secure.DEFAULT_INPUT_METHOD value ("pkg/.Service"). */
    fun imePackage(setting: String?): String? = setting?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }

    fun needsBalloon(killFreedMb: Long?, afterKill: MemoryReadout.Device?, needMb: Int): Boolean =
        afterKill != null && (killFreedMb ?: 0) < KILL_ENOUGH_MB && wanted(afterKill, needMb)

    /** The balloon must be more killable than this app; without its own value, AMS pins it to 250 under an app at 200 or below. */
    fun ordered(appAdj: Int?, balloonAdj: Int?): Boolean = when {
        appAdj == null -> false
        balloonAdj != null -> balloonAdj > appAdj
        else -> appAdj <= PERCEPTIBLE_ADJ
    }

    fun capMb(totalKb: Long): Int = minOf(MAX_MB.toLong(), totalKb / 1024 / 3).toInt()

    /**
     * When to stop growing. A step that brought back [KILL_GAIN_MB] or more of MemAvailable + SwapFree beyond its
     * own size counts as lmkd killing; [QUIET_STEPS] steps without that after a kill mean the cached apps are gone.
     */
    class StopRule(private val capMb: Int, private val maxMs: Long = MAX_MS) {
        var killSteps = 0
            private set
        private var quiet = 0

        fun after(before: Sample, after: Sample, stepMb: Int, sizeMb: Int, elapsedMs: Long, stepMs: Long): String? {
            val gain = after.headroomMb - before.headroomMb + stepMb
            if (gain >= KILL_GAIN_MB) { killSteps++; quiet = 0 } else if (killSteps > 0) quiet++
            val stall = if (before.fullStallUs != null && after.fullStallUs != null && stepMs > 0)
                (after.fullStallUs - before.fullStallUs) / (stepMs * 1000.0) else null
            return when {
                killSteps > 0 && quiet >= QUIET_STEPS -> "reclaimed"
                stall != null && stall > FULL_STALL_LIMIT -> "stall"
                after.availableKb / 1024 < FLOOR_MB -> "floor"
                sizeMb + STEP_MB > capMb -> "cap"
                elapsedMs >= maxMs -> "time"
                else -> null
            }
        }
    }

    fun parseFullStallUs(psi: String): Long? =
        Regex("""(?m)^full .*\btotal=(\d+)""").find(psi)?.groupValues?.get(1)?.toLongOrNull()

    fun parseAdj(text: String?): Int? = text?.trim()?.toIntOrNull()

    fun fields(r: Result): Map<String, Any?> = linkedMapOf(
        "reason" to r.reason,
        "method" to r.method,
        "freedMb" to r.freedMb,
        "availableBeforeMb" to r.before?.availableKb?.div(1024),
        "availableAfterMb" to r.after?.availableKb?.div(1024),
        "swapUsedBeforeMb" to r.before?.swapUsedKb?.div(1024),
        "swapUsedAfterMb" to r.after?.swapUsedKb?.div(1024),
        "balloonPeakMb" to r.balloonPeakMb,
        "balloonMs" to r.balloonMs,
        "balloonStop" to r.balloonStop,
        "packages" to r.packages,
        "killFreedMb" to r.killFreedMb,
        "appAdj" to r.appAdj,
        "balloonAdj" to r.balloonAdj,
        "ms" to r.ms,
    )

    fun line(r: Result): String = "DROIDDECK_FREE_MEMORY " + fields(r).entries.joinToString(" ") { (k, v) -> "$k=${v ?: "-"}" }

    /** Runs in the background and records the result; join the thread before starting the session's own processes. */
    fun startAsync(context: Context, reason: String, needMb: Int): Thread =
        Thread({ runCatching { run(context, reason, needMb) }.onFailure { Log.w(TAG, "free memory failed", it) } }, "free-memory")
            .apply { start() }

    fun run(context: Context, reason: String, needMb: Int): Result = synchronized(lock) {
        val app = context.applicationContext
        val began = SystemClock.elapsedRealtime()
        quietUntil = Long.MAX_VALUE
        try {
            val before = MemoryReadout.device()
            val result = if (!wanted(before, needMb)) {
                Result(reason, "none", 0, before, before, null)
            } else {
                val packages = killBackground(app)
                sleep(KILL_SETTLE_MS)
                val afterKill = MemoryReadout.device()
                val killFreed = if (before != null && afterKill != null) headroomMb(afterKill) - headroomMb(before) else null
                if (!needsBalloon(killFreed, afterKill, needMb) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Result(reason, "kill_background", packages, before, afterKill, killFreed)
                } else {
                    val b = balloon(app, afterKill!!)
                    sleep(KILL_SETTLE_MS)
                    Result(reason, "kill_background+balloon", packages, before, MemoryReadout.device(), killFreed,
                        b.peakMb, b.ms, b.stop, b.appAdj, b.balloonAdj)
                }
            }.copy(ms = SystemClock.elapsedRealtime() - began)
            report(result)
            result
        } finally {
            quietUntil = SystemClock.elapsedRealtime() + QUIET_AFTER_MS
        }
    }

    private fun report(r: Result) {
        val line = line(r)
        Log.i(TAG, line)
        runCatching { SessionState.logFile?.appendText(line + "\n") }
        SessionEvents.record("memory.freed", fields(r))
    }

    private fun killBackground(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        val pm = context.packageManager
        val launcher = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        val ime = runCatching { imePackage(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)) }.getOrNull()
        @Suppress("DEPRECATION")
        val installed = runCatching { pm.getInstalledPackages(0).map { it.packageName } }.getOrDefault(emptyList())
        val list = candidates(installed, context.packageName, setOfNotNull(launcher, ime))
        val deadline = SystemClock.elapsedRealtime() + KILL_LOOP_MS
        var tried = 0
        for (pkg in list) {
            if (SystemClock.elapsedRealtime() > deadline) break
            runCatching { am.killBackgroundProcesses(pkg) }
            tried++
        }
        return tried
    }

    class Balloon(val peakMb: Int, val ms: Long, val stop: String, val appAdj: Int?, val balloonAdj: Int?)

    private fun sample(): Sample? {
        val d = MemoryReadout.device() ?: return null
        val full = runCatching { parseFullStallUs(File("/proc/pressure/memory").readText()) }.getOrNull()
        return Sample(d.availableKb, d.swapFreeKb, full)
    }

    private fun adjOf(pid: Int): Int? = runCatching { parseAdj(File("/proc/$pid/oom_score_adj").readText()) }.getOrNull()

    private fun balloon(context: Context, device: MemoryReadout.Device): Balloon {
        val began = SystemClock.elapsedRealtime()
        val appAdj = adjOf(android.os.Process.myPid())
        val client = BalloonClient(context)
        var peak = 0
        var balloonAdj: Int? = null
        val stop = try {
            val binder = client.connect() ?: return Balloon(0, SystemClock.elapsedRealtime() - began, "bind", appAdj, null)
            val pid = BalloonClient.call(binder, MemoryBalloonService.PID, 0)
            val settleBy = SystemClock.elapsedRealtime() + 1_000L
            while (true) {
                balloonAdj = adjOf(pid)
                if (ordered(appAdj, balloonAdj) || SystemClock.elapsedRealtime() > settleBy) break
                sleep(50L)
            }
            if (!ordered(appAdj, balloonAdj)) {
                "adj"
            } else {
                val rule = StopRule(capMb(device.totalKb))
                val growBegan = SystemClock.elapsedRealtime()
                var reason: String? = null
                while (reason == null) {
                    val before = sample() ?: break
                    val stepAt = SystemClock.elapsedRealtime()
                    val size = BalloonClient.call(binder, MemoryBalloonService.GROW, STEP_MB)
                    if (size < 0) { reason = "alloc"; break }
                    peak = size
                    sleep(SETTLE_MS)
                    val after = sample() ?: break
                    val now = SystemClock.elapsedRealtime()
                    reason = rule.after(before, after, STEP_MB, peak, now - growBegan, now - stepAt)
                }
                BalloonClient.call(binder, MemoryBalloonService.RELEASE, 0)
                reason ?: "unreadable"
            }
        } catch (e: android.os.DeadObjectException) {
            "killed"
        } catch (e: Exception) {
            Log.w(TAG, "balloon", e)
            "error"
        } finally {
            client.close()
        }
        return Balloon(peak, SystemClock.elapsedRealtime() - began, stop, appAdj, balloonAdj)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private class BalloonClient(private val context: Context) : ServiceConnection {
        private val connected = CountDownLatch(1)
        @Volatile private var binder: IBinder? = null
        private var bound = false

        fun connect(): IBinder? {
            val intent = Intent(context, MemoryBalloonService::class.java)
            bound = runCatching { context.bindService(intent, this, Context.BIND_AUTO_CREATE or Context.BIND_NOT_PERCEPTIBLE) }
                .getOrDefault(false)
            if (!bound) return null
            connected.await(3, TimeUnit.SECONDS)
            return binder
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            connected.countDown()
        }

        /** lmkd killed the balloon; unbinding keeps Android from starting it again. */
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            close()
        }

        @Synchronized
        fun close() {
            if (!bound) return
            bound = false
            runCatching { context.unbindService(this) }
        }

        companion object {
            fun call(binder: IBinder, code: Int, arg: Int): Int {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInt(arg)
                    binder.transact(code, data, reply, 0)
                    return reply.readInt()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }
}
