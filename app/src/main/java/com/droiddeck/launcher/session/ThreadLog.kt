package com.droiddeck.launcher.session

import java.io.File
import java.util.Locale

/**
 * Per-thread CPU of the session's Windows processes (the game and Wine's helpers) and of the display and Steam
 * processes around them ([ALSO]), every 5 s, into threads.csv:
 * time on a CPU and time waiting for one (from /proc/<pid>/task/<tid>/schedstat), major faults, the core it last
 * ran on and the cores it may run on. The newest sample also goes into the live status file.
 */
object ThreadLog {
    const val FILE = "threads.csv"
    internal const val HEADER = "t_wall_ms,pid,tid,process,name,cpu_pct,wait_pct,majflt_s,core,allowed"
    private const val TAG = "ThreadLog"
    private const val PERIOD_MS = 5_000L
    private const val FIND_EVERY = 6L
    private const val MIN_PROCESS_PCT = 2.0
    private const val MIN_ROW_PCT = 0.5
    private const val LIVE_ROWS = 10
    internal val ALSO = setOf("Xwayland", "gamescope-wl", "steam")

    internal data class Task(val tid: Int, val name: String, val runNs: Long, val waitNs: Long, val majflt: Long, val core: Int, val allowed: String)

    @Volatile private var thread: Thread? = null
    @Volatile internal var live: List<String> = emptyList()
    private var sectionAdded = false

    /** The thread name between the first '(' and the last ')' of a stat line, commas made safe for csv. */
    internal fun statName(stat: String): String? {
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return stat.substring(open + 1, close).replace(',', '_')
    }

    /** Fields after the ')' of a stat line, 0-based: 9 majflt, 11 utime, 12 stime, 36 processor. */
    internal fun statFields(stat: String): List<String>? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        return stat.substring(close + 2).trim().split(' ')
    }

    /** schedstat is "<ns on cpu> <ns waiting on a run queue> <timeslices>". */
    internal fun schedstat(text: String): Pair<Long, Long>? {
        val parts = text.trim().split(Regex("\\s+"))
        val run = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val wait = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return run to wait
    }

    internal fun allowedCpus(status: String): String =
        Regex("""(?m)^Cpus_allowed_list:\s+(\S+)""").find(status)?.groupValues?.get(1)?.replace(',', ' ') ?: ""

    /**
     * One task from its stat, schedstat and status text. Without schedstat, CPU time falls back to utime + stime
     * in clock ticks of [ticksPerSecond] and the wait is unknown (-1).
     */
    internal fun task(tid: Int, stat: String, schedstat: String?, status: String?, ticksPerSecond: Long): Task? {
        val name = statName(stat) ?: return null
        val f = statFields(stat) ?: return null
        val majflt = f.getOrNull(9)?.toLongOrNull() ?: 0L
        val core = f.getOrNull(36)?.toIntOrNull() ?: -1
        val sched = schedstat?.let(::schedstat)
        val runNs = sched?.first ?: run {
            val ticks = (f.getOrNull(11)?.toLongOrNull() ?: return null) + (f.getOrNull(12)?.toLongOrNull() ?: return null)
            ticks * 1_000_000_000L / ticksPerSecond.coerceAtLeast(1)
        }
        return Task(tid, name, runNs, sched?.second ?: -1L, majflt, core, status?.let(::allowedCpus) ?: "")
    }

    /**
     * csv rows for one process between two samples [elapsedMs] apart: each thread on a CPU at least 0.5 % of the
     * time or waiting for one at least 0.5 %, busiest first, then one row named "*" with the whole process.
     */
    internal fun rows(at: Long, pid: Int, process: String, before: Map<Int, Task>, after: List<Task>, elapsedMs: Long): List<String> {
        if (elapsedMs <= 0) return emptyList()
        val elapsedNs = elapsedMs * 1_000_000.0
        val s = elapsedMs / 1000.0
        var total = 0.0
        var totalWait = 0.0
        var totalFaults = 0.0
        val out = ArrayList<Pair<Double, String>>()
        for (t in after) {
            val b = before[t.tid] ?: continue
            if (t.runNs < b.runNs) continue
            val cpu = (t.runNs - b.runNs) * 100.0 / elapsedNs
            val wait = if (t.waitNs >= 0 && b.waitNs >= 0 && t.waitNs >= b.waitNs) (t.waitNs - b.waitNs) * 100.0 / elapsedNs else null
            val faults = if (t.majflt >= b.majflt) (t.majflt - b.majflt) / s else 0.0
            total += cpu
            totalWait += wait ?: 0.0
            totalFaults += faults
            if (cpu < MIN_ROW_PCT && (wait ?: 0.0) < MIN_ROW_PCT) continue
            out += cpu to "$at,$pid,${t.tid},$process,${t.name},${pct(cpu)},${wait?.let(::pct) ?: ""},${pct(faults)},${t.core},${t.allowed}"
        }
        if (out.isEmpty() && total < MIN_ROW_PCT) return emptyList()
        return out.sortedByDescending { it.first }.map { it.second } +
            "$at,$pid,,$process,*,${pct(total)},${pct(totalWait)},${pct(totalFaults)},,"
    }

    /** Short lines for the live file: the busiest threads of the newest sample. */
    internal fun liveLines(rows: List<String>): List<String> =
        rows.map { it.split(',') }.filter { it.size >= 10 }
            .sortedByDescending { it[5].toDoubleOrNull() ?: 0.0 }
            .take(LIVE_ROWS)
            .map { String.format(Locale.US, "%6s%% cpu %6s%% waiting  core %-2s %-16s %s", it[5], it[6].ifEmpty { "?" }, it[8], it[4], it[3]) }

    private fun pct(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private fun sample(pid: Int, ticksPerSecond: Long): List<Task> =
        File("/proc/$pid/task").listFiles()?.mapNotNull { dir ->
            val tid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val stat = read("/proc/$pid/task/$tid/stat") ?: return@mapNotNull null
            task(tid, stat, read("/proc/$pid/task/$tid/schedstat"), read("/proc/$pid/task/$tid/status"), ticksPerSecond)
        }.orEmpty()

    @Synchronized
    fun start(sessionDir: File) {
        if (thread != null) return
        if (!sectionAdded) {
            sectionAdded = true
            com.droiddeck.launcher.telemetry.LiveStatus.addSection("game threads (cpu, newest threads.csv sample)") {
                live.takeIf { it.isNotEmpty() }?.joinToString("\n")
            }
        }
        live = emptyList()
        thread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            val file = File(sessionDir, FILE)
            val out = runCatching { java.io.FileWriter(file, true) }.getOrNull() ?: return@Thread
            val ticksPerSecond = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }
                .getOrDefault(100L).takeIf { it > 0 } ?: 100L
            var watched = emptyMap<Int, String>()
            val last = HashMap<Int, Pair<Long, Map<Int, Task>>>()
            var round = 0L
            try {
                if (file.length() == 0L) { out.write(HEADER + "\n"); out.flush() }
                android.util.Log.i(TAG, "DROIDDECK_THREAD_LOG file=$FILE every_s=${PERIOD_MS / 1000}")
                while (!Thread.currentThread().isInterrupted) {
                    runCatching {
                        if (round % FIND_EVERY == 0L) {
                            watched = MemoryReadout.split(withProcesses = true).processes
                                .filter { it.windows || it.name in ALSO }.associate { it.pid to it.name }
                            last.keys.retainAll(watched.keys)
                        }
                        val now = System.currentTimeMillis()
                        val newest = ArrayList<String>()
                        for ((pid, name) in watched) {
                            val tasks = sample(pid, ticksPerSecond)
                            if (tasks.isEmpty()) continue
                            val prev = last[pid]
                            last[pid] = now to tasks.associateBy { it.tid }
                            if (prev == null) continue
                            val rows = rows(now, pid, name, prev.second, tasks, now - prev.first)
                            val whole = rows.lastOrNull()?.split(',')?.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                            if (whole < MIN_PROCESS_PCT) continue
                            rows.forEach { out.write(it + "\n") }
                            newest += rows.filter { !it.split(',').getOrElse(4) { "" }.equals("*") }
                        }
                        out.flush()
                        live = liveLines(newest)
                    }
                    round++
                    try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                }
            } finally {
                runCatching { out.close() }
            }
        }, "thread-log").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }
}
