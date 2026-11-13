package com.droiddeck.launcher.session

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the real SessionSuspendController against a real multithreaded process tree that keeps
 * creating threads and processes (game_tree.c), with real signals, and checks that every thread
 * runs again after two quick freeze/resume cycles. With KESTREL_TEST_PROOT set to a host proot
 * (tools/proot/build-host.sh) the tree runs under proot, as on the phone, and the session root is
 * the proot tracer; otherwise the tree's first process is the session root.
 */
class SessionSuspendRealProcessTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var tools: File
    private lateinit var signals: Signals
    private var tree: GameTree? = null

    @Before fun setUp() {
        assumeTrue("needs Linux /proc and signals", System.getProperty("os.name") == "Linux" && File("/proc/self/task").isDirectory)
        tools = temporary.newFolder("tools")
        compile("game_tree.c", "-pthread")
        compile("signals.c")
        signals = Signals(File(tools, "signals"))
    }

    @After fun tearDown() {
        tree?.kill(signals)
        if (::signals.isInitialized) signals.close()
    }

    @Test fun twoQuickFreezesWhileThreadsAreCreatedLeaveEveryThreadRunning() {
        val rounds = System.getenv("KESTREL_SUSPEND_ROUNDS")?.toIntOrNull() ?: ROUNDS
        for (round in 0 until rounds) {
            freezeTwiceDuringLoad(round, workersBeforeFreeze = listOf(1, 3, 6)[round % 3])
        }
    }

    private fun freezeTwiceDuringLoad(round: Int, workersBeforeFreeze: Int) {
        val tree = GameTree.start(File(tools, "game_tree"), temporary.newFolder("round$round"), prootBinary()).also { tree = it }
        val reports = Collections.synchronizedList(ArrayList<SessionSuspendController.Stuck>())
        val controller = SessionSuspendController(
            sessionRoot = { tree.rootPid },
            helperRoots = { emptyList() },
            suspendAudio = {},
            resumeAudio = {},
            signal = { pid, signal -> signals.kill(pid, signal) },
            threadSignal = { pid, tid, signal -> signals.tgkill(pid, tid, signal) },
            stillStopped = { reports.addAll(it) },
        )
        val context = "round $round${if (tree.underProot) " under proot" else ""}"
        waitFor("$context: the game to start $workersBeforeFreeze workers", START_TIMEOUT_MS, { tree.describe() }) {
            val counters = tree.counters()
            counters != null && counters[GAME_MAIN] > 0 && tree.workersStarted(counters) >= workersBeforeFreeze
        }
        val createdAtFreeze = IntArray(2)
        val workersAtFreeze = IntArray(2)
        repeat(2) { cycle ->
            val before = tree.counters()!!
            createdAtFreeze[cycle] = before[GAME_TRANSIENT].toInt()
            workersAtFreeze[cycle] = tree.workersStarted(before)
            assertTrue("$context: freeze ${cycle + 1} did not complete\n${tree.describe()}", await { controller.freeze(completion = it) })
            val running = tree.tasks().filter { it.state !in STOPPED_OR_GONE }
            assertEquals("$context: threads still running while frozen (cycle ${cycle + 1})", emptyList<Task>(), running)
            val frozen = tree.counters()!!
            Thread.sleep(FROZEN_CHECK_MS)
            assertArrayEquals("$context: counters moved while frozen (cycle ${cycle + 1})", frozen, tree.counters()!!)
            assertTrue("$context: resume ${cycle + 1} did not complete\n${tree.describe()}", await { controller.resume(it) })
            if (cycle == 0) Thread.sleep(BETWEEN_CYCLES_MS)
        }
        val baseline = tree.counters()!!
        waitFor("$context: every thread to run after the second resume", PROGRESS_TIMEOUT_MS, {
            "behind: ${tree.behind(baseline, tree.counters()!!)}\n${tree.describe()}"
        }) {
            val now = tree.counters()!!
            tree.workersStarted(now) == WORKERS && tree.behind(baseline, now).isEmpty()
        }
        assertTrue("$context: no threads were created around the freezes", tree.counters()!![GAME_TRANSIENT] > createdAtFreeze.max())
        assertEquals("$context: threads left stopped", emptyList<Task>(), tree.stuckTasks())
        assertTrue("$context: controller did not close", await { done -> controller.closeAndResume { done(true) } })
        assertEquals("$context: stillStopped reports", emptyList<SessionSuspendController.Stuck>(), reports.toList())
        val after = tree.counters()!!
        println("$context: froze with ${workersAtFreeze.joinToString("/")} of $WORKERS workers and after " +
            "${createdAtFreeze.joinToString("/")} short-lived threads (${after[GAME_TRANSIENT]} by the end); every thread ran again")
        assertTrue("$context: a process of the tree outlived it", tree.kill(signals))
        this.tree = null
    }

    private fun prootBinary(): File? {
        val path = System.getenv("KESTREL_TEST_PROOT")?.takeIf { it.isNotBlank() } ?: return null
        val binary = File(path)
        assertTrue("KESTREL_TEST_PROOT=$path is not an executable file", binary.isFile && binary.canExecute())
        return binary
    }

    private fun compile(source: String, vararg flags: String) {
        val file = File(tools, source)
        file.writeBytes(javaClass.classLoader!!.getResource("suspend/$source")!!.readBytes())
        val compiler = System.getenv("CC")?.takeIf { it.isNotBlank() } ?: "cc"
        val command = listOf(compiler, "-O1", "-Wall", "-o", File(tools, source.removeSuffix(".c")).path, file.path) + flags
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: IOException) {
            throw AssertionError("This test builds its process tree with a C compiler; $compiler is unavailable", error)
        }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("$command timed out", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("$command failed:\n$output", 0, process.exitValue())
    }

    private fun await(start: ((Boolean) -> Unit) -> Unit): Boolean {
        val result = ArrayBlockingQueue<Boolean>(1)
        start { result.offer(it) }
        return result.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("controller did not answer in ${OPERATION_TIMEOUT_MS}ms")
    }

    private fun waitFor(what: String, timeoutMs: Long, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out after ${timeoutMs}ms waiting for $what\n${describe()}")
            Thread.sleep(POLL_MS)
        }
    }

    data class Task(val pid: Int, val tid: Int, val name: String, val state: Char, val ticks: Long)

    private class GameTree(val process: Process, val counterFile: File, val underProot: Boolean) {
        // Process.pid() is Java 9+ and absent from android.jar, which the unit tests compile against; the host JVM has it.
        val rootPid = (Process::class.java.getMethod("pid").invoke(process) as Long).toInt()
        private val marker = counterFile.path.toByteArray()

        fun counters(): LongArray? {
            val bytes = try { counterFile.readBytes() } catch (_: IOException) { return null }
            if (bytes.size < SLOTS * 8) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            return LongArray(SLOTS) { buffer.getLong(it * 8) }
        }

        fun workersStarted(counters: LongArray) = (0 until WORKERS).count { counters[FIRST_WORKER + it] > 0 }

        fun behind(baseline: LongArray, now: LongArray): List<String> =
            (0 until SLOTS).filter { now[it] <= baseline[it] }.map { slotName(it) }

        /** The root, its descendants and tracees, and any process of this tree that lost its parent. */
        fun pids(): List<Int> {
            val parents = HashMap<Int, MutableList<Int>>()
            val found = LinkedHashSet<Int>()
            if (process.isAlive) found.add(rootPid)
            for (entry in File("/proc").listFiles().orEmpty()) {
                val pid = entry.name.toIntOrNull() ?: continue
                val fields = stat(File(entry, "stat")) ?: continue
                parents.getOrPut(fields.second[1].toInt()) { ArrayList() }.add(pid)
                val tracer = try {
                    File(entry, "status").useLines { lines -> lines.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toInt() }
                } catch (_: IOException) { null }
                if (tracer == rootPid || ours(pid)) found.add(pid)
            }
            val queue = ArrayDeque(found)
            while (queue.isNotEmpty()) for (child in parents[queue.removeFirst()].orEmpty()) if (found.add(child)) queue.add(child)
            return found.toList()
        }

        private fun ours(pid: Int): Boolean {
            val cmdline = try { File("/proc/$pid/cmdline").readBytes() } catch (_: IOException) { return false }
            return cmdline.size >= marker.size && (0..cmdline.size - marker.size).any { start ->
                marker.indices.all { cmdline[start + it] == marker[it] }
            }
        }

        fun tasks(): List<Task> = pids().flatMap { pid ->
            File("/proc/$pid/task").listFiles().orEmpty().mapNotNull { entry ->
                val tid = entry.name.toIntOrNull() ?: return@mapNotNull null
                val (name, fields) = stat(File(entry, "stat")) ?: return@mapNotNull null
                Task(pid, tid, name, fields[0].first(), fields[11].toLong() + fields[12].toLong())
            }
        }

        /** Threads in T or t with no CPU time in every one of several samples. */
        fun stuckTasks(): List<Task> {
            var candidates: Map<Pair<Int, Int>, Task>? = null
            repeat(STUCK_SAMPLES) { sample ->
                if (sample > 0) Thread.sleep(STUCK_SAMPLE_MS)
                val stopped = tasks().filter { it.state == 'T' || it.state == 't' }.associateBy { it.pid to it.tid }
                candidates = candidates?.filter { (key, task) -> stopped[key]?.ticks == task.ticks } ?: stopped
            }
            return candidates.orEmpty().values.toList()
        }

        fun describe(): String = buildString {
            counters()?.let { counters -> append("counters: ").append((0 until SLOTS).joinToString { "${slotName(it)}=${counters[it]}" }).append('\n') }
            for (task in tasks()) append("  ${task.pid}/${task.tid} ${task.name} ${task.state} ticks=${task.ticks}\n")
        }

        /** Kills the tree; false if some process of it is still there after [KILL_TIMEOUT_MS]. */
        fun kill(signals: Signals): Boolean {
            val deadline = System.nanoTime() + KILL_TIMEOUT_MS * 1_000_000
            var left = pids()
            while (left.isNotEmpty() && System.nanoTime() < deadline) {
                for (pid in left) if (pid != rootPid) signals.kill(pid, SIGKILL)
                process.destroyForcibly()
                Thread.sleep(POLL_MS)
                left = pids()
            }
            process.waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return left.isEmpty()
        }

        companion object {
            fun start(binary: File, dir: File, proot: File?): GameTree {
                val counters = File(dir, "counters")
                val guest = listOf(binary.path, counters.path, WORKERS.toString())
                val command = if (proot == null) guest else listOf(proot.path, "--kill-on-exit", "-r", "/", "-w", dir.path) + guest
                val builder = ProcessBuilder(command).directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "output.log"))
                if (proot != null) {
                    builder.environment()["PROOT_TMP_DIR"] = dir.path
                    val loader = System.getenv("KESTREL_TEST_PROOT_LOADER")?.let(::File)
                        ?: listOf(File(proot.parentFile, "loader"), File(proot.parentFile, "loader/loader")).firstOrNull { it.isFile }
                    loader?.let { builder.environment()["PROOT_LOADER"] = it.path }
                }
                return GameTree(builder.start(), counters, proot != null)
            }

            private fun stat(file: File): Pair<String, List<String>>? {
                val text = try { file.readText() } catch (_: IOException) { return null }
                val open = text.indexOf('(')
                val close = text.lastIndexOf(')')
                if (open < 0 || close < open) return null
                return text.substring(open + 1, close) to text.substring(close + 2).trim().split(' ')
            }

            private fun slotName(slot: Int) = when (slot) {
                ROOT -> "root"
                SERVER_MAIN -> "server"
                SERVER_COND -> "server-cond"
                SERVER_POLL -> "server-poll"
                GAME_MAIN -> "game"
                GAME_LOADER -> "loader"
                GAME_TRANSIENT -> "transient-threads"
                GAME_COLLECTOR -> "collector"
                else -> "worker-${slot - FIRST_WORKER}"
            }
        }
    }

    private class Signals(binary: File) : AutoCloseable {
        private val process = ProcessBuilder(binary.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = OutputStreamWriter(process.outputStream)
        private val output = BufferedReader(InputStreamReader(process.inputStream))

        fun kill(pid: Int, signal: Int): Int = request("k $pid $signal")

        fun tgkill(pid: Int, tid: Int, signal: Int): Int = request("t $pid $tid $signal")

        @Synchronized private fun request(line: String): Int {
            input.write(line + "\n")
            input.flush()
            return output.readLine()?.toInt() ?: throw IOException("signal helper exited")
        }

        override fun close() {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val ROUNDS = 3
        const val WORKERS = 24
        const val ROOT = 0
        const val SERVER_MAIN = 1
        const val SERVER_COND = 2
        const val SERVER_POLL = 3
        const val GAME_MAIN = 4
        const val GAME_LOADER = 5
        const val GAME_TRANSIENT = 6
        const val GAME_COLLECTOR = 7
        const val FIRST_WORKER = 8
        const val SLOTS = FIRST_WORKER + WORKERS
        const val SIGKILL = 9
        const val KILL_TIMEOUT_MS = 10_000L
        const val START_TIMEOUT_MS = 30_000L
        const val OPERATION_TIMEOUT_MS = 30_000L
        const val PROGRESS_TIMEOUT_MS = 20_000L
        const val FROZEN_CHECK_MS = 150L
        const val BETWEEN_CYCLES_MS = 50L
        const val POLL_MS = 10L
        const val STUCK_SAMPLES = 3
        const val STUCK_SAMPLE_MS = 300L
        val STOPPED_OR_GONE = setOf('T', 't', 'Z', 'X')
    }
}
