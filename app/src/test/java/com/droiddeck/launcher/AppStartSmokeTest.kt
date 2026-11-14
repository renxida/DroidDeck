package com.droiddeck.launcher

import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.Lifecycle
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class AppStartSmokeTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var threadsBefore: Set<Thread> = emptySet()
    private val backgroundWrites = ConcurrentHashMap<String, String>()
    private var writeObserver: ObserverHandle? = null

    @Before fun setUp() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        // App.onCreate's CrashHandler ends in Runtime.halt when nothing is chained behind it, which would kill the test JVM.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            uncaught.add(AssertionError("uncaught exception on thread \"${thread.name}\"", error))
        }
        threadsBefore = Thread.getAllStackTraces().keys.toSet()
        // A snapshot write off the main thread schedules Compose's global apply on the main looper; one that lands after a
        // test's last drain can be dropped when Robolectric resets the looper, which leaves Compose in this sandbox unable
        // to apply again. Record such writes so a failure names the writer.
        val main = Looper.getMainLooper().thread
        writeObserver = Snapshot.registerGlobalWriteObserver { state ->
            val writer = Thread.currentThread()
            if (writer === main) return@registerGlobalWriteObserver
            val frames = writer.stackTrace.filter { it.className.startsWith("com.droiddeck") }.take(8)
            backgroundWrites.putIfAbsent("${writer.name}|${frames.joinToString()}",
                "thread \"${writer.name}\" wrote ${state.javaClass.simpleName}\n" + frames.joinToString("\n") { "      at $it" })
        }
    }

    @After fun tearDown() {
        writeObserver?.dispose()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }

    @Test fun launcherEntryStartsResumesAndPausesWithoutCrashing() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(RuntimeEnvironment.getApplication(), FrontEndActivity::class.java)
        val controller = Robolectric.buildActivity(FrontEndActivity::class.java, launch)
        val activity = controller.get()
        try {
            startResumePause(controller, activity)
        } finally {
            // A failure part way must not leave the launcher's handlers posting on the shared main looper, or every
            // later Compose test in this JVM times out waiting for idle.
            runCatching { if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause() }
            runCatching { if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop() }
            runCatching { if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy() }
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
        }
        assertNoUncaughtExceptions()
        assertLauncherLeavesComposeWorking()
    }

    private fun startResumePause(controller: org.robolectric.android.controller.ActivityController<FrontEndActivity>, activity: FrontEndActivity) {
        controller.create().start().postCreate(null).resume().visible()
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertFalse(activity.isFinishing)

        awaitStartupWork()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoUncaughtExceptions()
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)

        controller.pause()
        assertEquals(Lifecycle.State.STARTED, activity.lifecycle.currentState)
    }

    private fun assertNoUncaughtExceptions() {
        uncaught.firstOrNull()?.let { throw it }
    }

    // The launcher starts its catalog, component and log housekeeping on plain threads; network ones may still be
    // waiting on a slow host at the deadline, which is not a startup crash.
    private fun awaitStartupWork() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_WORK_SECONDS)
        while (System.nanoTime() < deadline) {
            val busy = Thread.getAllStackTraces().keys.any {
                it !in threadsBefore && it !== Thread.currentThread() && it.isAlive &&
                    (it.state == Thread.State.RUNNABLE || it.state == Thread.State.BLOCKED)
            }
            if (!busy) return
            Thread.sleep(50)
        }
    }

    /**
     * After the launcher is destroyed, nothing it started may keep Compose busy for the tests that share this JVM: its
     * threads settle, the main looper drains, and a snapshot write is still applied (Compose's global apply runs on the
     * main looper; when that is wedged every later Compose test fails with "Compose did not get idle").
     */
    private fun assertLauncherLeavesComposeWorking() {
        awaitStartupWork()
        shadowOf(Looper.getMainLooper()).idle()
        val problems = mutableListOf<String>()
        composeApplyProblem()?.let { problems += "right after destroy: $it" }
        // Anything still running a moment later must not write Compose state from its own thread either.
        Thread.sleep(SETTLE_MS)
        shadowOf(Looper.getMainLooper()).idle()
        composeApplyProblem()?.let { problems += "${SETTLE_MS}ms after destroy: $it" }
        val leftovers = launcherThreads()
        val report = buildString {
            if (backgroundWrites.isNotEmpty()) appendLine("Compose state written off the main thread:\n" + backgroundWrites.values.joinToString("\n"))
            if (leftovers.isNotEmpty()) appendLine("launcher threads alive after destroy:\n" + leftovers.joinToString("\n") { describe(it) })
            pendingMainMessages().takeIf { it.isNotEmpty() }?.let { appendLine("main looper still holds:\n" + it.joinToString("\n") { "    $it" }) }
        }
        if (report.isNotEmpty()) System.err.println("AppStartSmokeTest diagnostics:\n$report")
        // Later tests must not inherit the launcher's work: stop what of it is still running.
        val ours = leftovers.filter { t -> t !in threadsBefore && t.stackTrace.any { it.className.startsWith("com.droiddeck") } }
        ours.forEach { it.interrupt() }
        ours.forEach { it.join(1_000) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNoUncaughtExceptions()
        watchForComposeHangs()
        if (problems.isNotEmpty()) fail(problems.joinToString("\n") + "\n" + uiDispatcherState() + "\n" + report)
    }

    private fun composeApplyProblem(): String? {
        val probe = mutableStateOf(0)
        probe.value = 1
        shadowOf(Looper.getMainLooper()).idle()
        return if (Snapshot.current.hasPendingChanges()) "a main-thread snapshot write was never applied" else null
    }

    // Threads started by this launcher, and any older thread (a shared pool's worker) now running launcher code.
    private fun launcherThreads(): List<Thread> = Thread.getAllStackTraces().filter { (t, stack) ->
        t !== Thread.currentThread() && t.isAlive && (t !in threadsBefore || stack.any { it.className.startsWith("com.droiddeck") })
    }.keys.toList()

    /**
     * Diagnostics for the Compose tests that later share this sandbox: if its main thread then spins in Compose's idling
     * loop for half a minute, print what the main thread is doing and every thread running app code or waiting on a lock.
     */
    private fun watchForComposeHangs() {
        val main = Looper.getMainLooper().thread
        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
        fun spinning() = main.stackTrace.any { it.className.contains("IdlingStrategy") }
        Thread({
            val end = System.currentTimeMillis() + HANG_WATCH_MS
            var since = 0L
            var dumps = 0
            while (System.currentTimeMillis() < end && dumps < 2) {
                Thread.sleep(5_000)
                if (!spinning()) { since = 0L; continue }
                if (since == 0L) since = System.currentTimeMillis()
                if (System.currentTimeMillis() - since < 30_000) continue
                dumps++
                since = 0L
                val samples = (1..6).joinToString("\n    --\n") {
                    Thread.sleep(150)
                    main.stackTrace.take(45).joinToString("\n") { "      at $it" }
                }
                val others = mx.dumpAllThreads(true, true).filter { info ->
                    info.threadId != main.id && (info.threadState == Thread.State.BLOCKED || info.lockOwnerName != null ||
                        info.stackTrace.any { it.className.startsWith("com.droiddeck") || it.className.startsWith("androidx.compose") })
                }.joinToString("\n") { info ->
                    "  \"${info.threadName}\" ${info.threadState}" +
                        (info.lockName?.let { " on $it" } ?: "") + (info.lockOwnerName?.let { " held by \"$it\"" } ?: "") + "\n" +
                        info.stackTrace.take(25).joinToString("\n") { "      at $it" }
                }
                System.err.println("AppStartSmokeTest hang watch: main thread \"${main.name}\" has spun in Compose's idling loop " +
                    "for 30s+.\n  main samples:\n$samples\n  other threads:\n$others")
            }
        }, "smoke-test-hang-watch").apply { isDaemon = true; start() }
    }

    private fun describe(thread: Thread): String =
        "  \"${thread.name}\" ${thread.state}${if (thread.isDaemon) " daemon" else ""}\n" +
            thread.stackTrace.take(12).joinToString("\n") { "      at $it" }

    private fun pendingMainMessages(): List<String> = runCatching {
        val queue = Looper.getMainLooper().queue
        var message = android.os.MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }.get(queue)
        val out = mutableListOf<String>()
        while (message != null && out.size < 50) {
            val m = message as android.os.Message
            out += "when=${m.`when`} what=${m.what} target=${m.target} callback=${m.callback}"
            message = android.os.Message::class.java.getDeclaredField("next").apply { isAccessible = true }.get(m)
        }
        out
    }.getOrElse { listOf("(could not read the queue: $it)") }

    private fun uiDispatcherState(): String = runCatching {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor]!!
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        val manager = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
        val sent = manager.getDeclaredField("sent").apply { isAccessible = true }.get(null)
        "AndroidUiDispatcher.Main: scheduledTrampolineDispatch=${field(dispatcher, "scheduledTrampolineDispatch")} " +
            "scheduledFrameDispatch=${field(dispatcher, "scheduledFrameDispatch")} queued=${(field(dispatcher, "toRunTrampolined") as Collection<*>).size}; " +
            "GlobalSnapshotManager.sent=$sent"
    }.getOrElse { "(Compose dispatcher state unreadable: $it)" }

    private companion object {
        const val STARTUP_WORK_SECONDS = 20L
        const val SETTLE_MS = 2_000L
        const val HANG_WATCH_MS = 20 * 60_000L
    }
}
