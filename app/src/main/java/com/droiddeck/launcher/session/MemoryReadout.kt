package com.droiddeck.launcher.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import java.io.File
import java.util.Locale

internal object MemoryReadout {
    data class Device(val totalKb: Long, val availableKb: Long, val swapTotalKb: Long, val swapFreeKb: Long) {
        val usedKb get() = (totalKb - availableKb).coerceAtLeast(0)
        val swapUsedKb get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0)
    }

    data class Split(
        val gameKb: Long, val droiddeckKb: Long, val gpuKb: Long?,
        val gameSwapKb: Long = 0, val droiddeckSwapKb: Long = 0, val topName: String = "", val topKb: Long = 0,
        val processes: List<Proc> = emptyList(),
        val gameMajflt: Long = 0, val droiddeckMajflt: Long = 0,
    )

    /** Pages read back from swap or files since boot: the counters from /proc/vmstat that show thrashing. */
    data class Paging(val swapInPages: Long, val swapOutPages: Long, val majorFaults: Long)

    /** One process of the session's tree; [cpuTicks] is utime + stime from /proc/<pid>/stat. */
    data class Proc(val pid: Int, val name: String, val windows: Boolean, val rssKb: Long, val swapKb: Long, val cpuTicks: Long)

    fun cpuTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).split(' ')
        val user = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val system = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return user + system
    }

    /** Major page faults (field 12 of /proc/<pid>/stat): each one waited on a read from swap or disk. */
    fun majorFaults(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        return stat.substring(close + 2).split(' ').getOrNull(9)?.toLongOrNull()
    }

    fun parseVmstat(text: String): Paging? {
        fun field(name: String) = Regex("""(?m)^$name\s+(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val pswpin = field("pswpin") ?: return null
        return Paging(pswpin, field("pswpout") ?: 0, field("pgmajfault") ?: 0)
    }

    fun paging(): Paging? = runCatching { parseVmstat(File("/proc/vmstat").readText()) }.getOrNull()

    fun parseMeminfo(text: String): Device? {
        fun field(name: String) = Regex("""(?m)^$name:\s+(\d+) kB""").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val total = field("MemTotal") ?: return null
        val available = field("MemAvailable") ?: return null
        return Device(total, available, field("SwapTotal") ?: 0, field("SwapFree") ?: 0)
    }

    fun residentKb(status: String): Long {
        fun field(name: String) = Regex("""(?m)^$name:\s+(\d+) kB""").find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return field("RssAnon") + field("RssShmem")
    }

    fun swapKb(status: String): Long =
        Regex("""(?m)^VmSwap:\s+(\d+) kB""").find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0

    fun processName(status: String): String =
        Regex("""(?m)^Name:\s+(\S+)""").find(status)?.groupValues?.get(1)?.replace(',', '_') ?: ""

    fun inWinePrefix(environ: ByteArray): Boolean {
        var start = 0
        val key = "WINEPREFIX=".toByteArray()
        for (i in 0..environ.size) {
            if (i == environ.size || environ[i] == 0.toByte()) {
                if (i - start >= key.size && (key.indices).all { environ[start + it] == key[it] }) return true
                start = i + 1
            }
        }
        return false
    }

    fun androidKb(device: Device, split: Split): Long =
        (device.usedKb - split.gameKb - split.droiddeckKb - (split.gpuKb ?: 0)).coerceAtLeast(0)

    fun gb(kb: Long): String = String.format(Locale.US, "%.1f", kb / (1024.0 * 1024.0))

    fun availableMb(): Long? = runCatching { parseMeminfo(File("/proc/meminfo").readText())?.availableKb?.div(1024) }.getOrNull()

    fun device(): Device? = runCatching { parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull()

    fun split(root: Int = android.os.Process.myPid(), withProcesses: Boolean = false): Split {
        val parents = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            parentOf(pid)?.let { parents[pid] = it }
        }
        val children = parents.entries.groupBy({ it.value }, { it.key })
        var game = 0L
        var droiddeck = 0L
        var gameSwap = 0L
        var droiddeckSwap = 0L
        var topName = ""
        var topKb = 0L
        var gameMajflt = 0L
        var droiddeckMajflt = 0L
        val processes = ArrayList<Proc>()
        val queue = ArrayDeque<Int>().apply { add(root) }
        val seen = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!seen.add(pid)) continue
            val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull()
            if (status != null) {
                val kb = residentKb(status)
                val swap = swapKb(status)
                val windows = pid != root && runCatching { inWinePrefix(File("/proc/$pid/environ").readBytes()) }.getOrDefault(false)
                val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull()
                val faults = stat?.let(::majorFaults) ?: 0L
                if (windows) { game += kb; gameSwap += swap; gameMajflt += faults } else { droiddeck += kb; droiddeckSwap += swap; droiddeckMajflt += faults }
                if (kb + swap > topKb) { topKb = kb + swap; topName = processName(status) }
                if (withProcesses) {
                    val ticks = stat?.let(::cpuTicks) ?: 0L
                    processes += Proc(pid, processName(status), windows, kb, swap, ticks)
                }
            }
            children[pid]?.let(queue::addAll)
        }
        return Split(game, droiddeck, gpuKb(), gameSwap, droiddeckSwap, topName, topKb, processes, gameMajflt, droiddeckMajflt)
    }

    private fun parentOf(pid: Int): Int? {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        return stat.substring(close + 2).split(' ').getOrNull(1)?.toIntOrNull()
    }

    private fun gpuKb(): Long? = runCatching {
        File("/sys/class/kgsl/kgsl/page_alloc").readText().trim().split(Regex("\\s+"))[0].toLong() / 1024
    }.getOrNull()
}

object MemoryLog {
    private const val TAG = "Memory"
    private const val PERIOD_MS = 5_000L
    private const val LOG_EVERY = 2L
    @Volatile private var thread: Thread? = null

    internal fun line(device: MemoryReadout.Device, split: MemoryReadout.Split): String =
        "used ${device.usedKb / 1024}/${device.totalKb / 1024} MB, available ${device.availableKb / 1024} MB, " +
            "swap ${device.swapUsedKb / 1024}/${device.swapTotalKb / 1024} MB | game ${split.gameKb / 1024}, " +
            "droiddeck ${split.droiddeckKb / 1024}, android ${MemoryReadout.androidKb(device, split) / 1024}, " +
            "gpu ${split.gpuKb?.div(1024) ?: "?"} MB"

    internal const val CSV_HEADER = "t_wall_ms,used_mb,available_mb,swap_used_mb,game_mb,droiddeck_mb,app_mb,gpu_mb,game_swap_mb,droiddeck_swap_mb,top_process,top_mb," +
        "game_majflt_s,droiddeck_majflt_s,swapin_mb_s,swapout_mb_s"

    /** Per-second rates between two cumulative counters, or blank without an earlier sample. */
    internal class Rates(val gameMajflt: Double?, val droiddeckMajflt: Double?, val swapInMb: Double?, val swapOutMb: Double?) {
        companion object {
            val NONE = Rates(null, null, null, null)
            fun between(before: MemoryReadout.Split?, beforePaging: MemoryReadout.Paging?, after: MemoryReadout.Split,
                        afterPaging: MemoryReadout.Paging?, elapsedMs: Long): Rates {
                if (elapsedMs <= 0) return NONE
                val s = elapsedMs / 1000.0
                fun rate(a: Long?, b: Long?) = if (a != null && b != null && b >= a) (b - a) / s else null
                val pageMb = 4096.0 / (1024 * 1024)
                return Rates(rate(before?.gameMajflt, after.gameMajflt), rate(before?.droiddeckMajflt, after.droiddeckMajflt),
                    rate(beforePaging?.swapInPages, afterPaging?.swapInPages)?.times(pageMb),
                    rate(beforePaging?.swapOutPages, afterPaging?.swapOutPages)?.times(pageMb))
            }
        }
    }

    private fun num(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: ""

    internal fun csvLine(at: Long, device: MemoryReadout.Device, split: MemoryReadout.Split, appKb: Long?, rates: Rates = Rates.NONE): String =
        "$at,${device.usedKb / 1024},${device.availableKb / 1024},${device.swapUsedKb / 1024},${split.gameKb / 1024}," +
            "${split.droiddeckKb / 1024},${appKb?.div(1024) ?: ""},${split.gpuKb?.div(1024) ?: ""}," +
            "${split.gameSwapKb / 1024},${split.droiddeckSwapKb / 1024},${split.topName},${split.topKb / 1024}," +
            "${num(rates.gameMajflt)},${num(rates.droiddeckMajflt)},${num(rates.swapInMb)},${num(rates.swapOutMb)}"

    internal const val PROCESS_FILE = "processes.csv"
    internal const val PROCESS_HEADER = "t_wall_ms,pid,name,side,rss_mb,swap_mb,cpu_pct"
    private const val PROCESS_EVERY = 12L
    private const val PROCESS_MIN_KB = 16L * 1024

    /**
     * processes.csv rows for one snapshot: every process of the session's tree holding at least 16 MB
     * (resident + swapped) or, against [previous] ticks taken [elapsedMs] earlier, at least 1 % of a core.
     */
    internal fun processLines(at: Long, procs: List<MemoryReadout.Proc>, previous: Map<Int, Long>, elapsedMs: Long, ticksPerSecond: Long): List<String> =
        procs.mapNotNull { p ->
            val before = previous[p.pid]
            val pct = if (before != null && elapsedMs > 0 && ticksPerSecond > 0 && p.cpuTicks >= before)
                (p.cpuTicks - before) * 100_000.0 / (ticksPerSecond * elapsedMs) else null
            if (p.rssKb + p.swapKb < PROCESS_MIN_KB && (pct ?: 0.0) < 1.0) return@mapNotNull null
            "$at,${p.pid},${p.name.replace(',', '_')},${if (p.windows) "game" else "droiddeck"},${p.rssKb / 1024},${p.swapKb / 1024}," +
                (pct?.let { String.format(Locale.US, "%.1f", it) } ?: "")
        }

    /** Logs to logcat every [LOG_EVERY] samples and, with [csv], appends every sample to it (memory.csv). */
    @Synchronized
    fun start(csv: java.io.File? = null, smaps: Boolean = false) {
        if (thread != null) return
        thread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            val out = csv?.let { runCatching { java.io.FileWriter(it, true) }.getOrNull() }
            val processFile = csv?.parentFile?.let { java.io.File(it, PROCESS_FILE) }
            val processOut = processFile?.let { runCatching { java.io.FileWriter(it, true) }.getOrNull() }
            val smapsFile = if (smaps) csv?.parentFile?.let { java.io.File(it, SmapsRollup.FILE) } else null
            val smapsOut = smapsFile?.let { runCatching { java.io.FileWriter(it, true) }.getOrNull() }
            val ticksPerSecond = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }
                .getOrDefault(100L).takeIf { it > 0 } ?: 100L
            var lastTicks = emptyMap<Int, Long>()
            var lastSnapshotAt = 0L
            var lastSplit: MemoryReadout.Split? = null
            var lastPaging: MemoryReadout.Paging? = null
            var lastSampleAt = 0L
            try {
                if (out != null && csv?.length() == 0L) runCatching { out.write(CSV_HEADER + "\n"); out.flush() }
                if (processOut != null && processFile?.length() == 0L) runCatching {
                    processOut.write(PROCESS_HEADER + "\n"); processOut.flush()
                    android.util.Log.i(TAG, "DROIDDECK_PROCESS_LOG file=$PROCESS_FILE every_s=${PERIOD_MS * PROCESS_EVERY / 1000} min_mb=${PROCESS_MIN_KB / 1024}")
                }
                if (smapsOut != null && smapsFile?.length() == 0L) runCatching {
                    smapsOut.write(SmapsRollup.HEADER + "\n"); smapsOut.flush()
                    android.util.Log.i(TAG, "DROIDDECK_SMAPS_LOG file=${SmapsRollup.FILE} every_s=${PERIOD_MS * PROCESS_EVERY / 1000}")
                }
                var sample = 0L
                while (!Thread.currentThread().isInterrupted) {
                    runCatching {
                        val device = MemoryReadout.device() ?: return@runCatching
                        val snapshot = processOut != null && sample % PROCESS_EVERY == 0L
                        val split = MemoryReadout.split(withProcesses = snapshot)
                        if (sample % LOG_EVERY == 0L) android.util.Log.i(TAG, line(device, split))
                        val now = System.currentTimeMillis()
                        val paging = MemoryReadout.paging()
                        val rates = Rates.between(lastSplit, lastPaging, split, paging, if (lastSampleAt > 0) now - lastSampleAt else 0L)
                        lastSplit = split
                        lastPaging = paging
                        lastSampleAt = now
                        if (out != null) {
                            val app = runCatching { MemoryReadout.residentKb(java.io.File("/proc/self/status").readText()) }.getOrNull()
                            out.write(csvLine(now, device, split, app, rates) + "\n")
                            out.flush()
                        }
                        if (snapshot && processOut != null) {
                            val elapsed = if (lastSnapshotAt > 0) now - lastSnapshotAt else 0L
                            for (row in processLines(now, split.processes, lastTicks, elapsed, ticksPerSecond)) processOut.write(row + "\n")
                            processOut.flush()
                            lastTicks = split.processes.associate { it.pid to it.cpuTicks }
                            lastSnapshotAt = now
                        }
                        if (snapshot && smapsOut != null) {
                            // The biggest game process (resident + swapped): the game itself, not wineserver or explorer.
                            split.processes.filter { it.windows }.maxByOrNull { it.rssKb + it.swapKb }?.let { p ->
                                val began = System.nanoTime()
                                val text = runCatching { java.io.File("/proc/${p.pid}/smaps").readText() }.getOrNull()
                                if (text != null) {
                                    val readMs = (System.nanoTime() - began) / 1_000_000
                                    for (row in SmapsRollup.lines(now, p.pid, p.name, text, readMs)) smapsOut.write(row + "\n")
                                    smapsOut.flush()
                                }
                            }
                        }
                    }
                    sample++
                    try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                }
            } finally {
                runCatching { out?.close() }
                runCatching { processOut?.close() }
                runCatching { smapsOut?.close() }
            }
        }, "memory-log").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }
}

class MemoryHud(context: Context) {
    var text by mutableStateOf("")
        private set

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var thread: Thread? = null

    fun refresh() {
        if (SessionPrefs.memoryHud(context) == SessionPrefs.MEMORY_HUD_OFF) stop() else start()
    }

    fun start() {
        if (running || SessionPrefs.memoryHud(context) == SessionPrefs.MEMORY_HUD_OFF) return
        running = true
        val token = ++generation
        thread = Thread({
            while (running && generation == token) {
                val line = runCatching { sample() }.getOrDefault("")
                handler.post { if (running && generation == token) text = line }
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
            }
        }, "memory-hud").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        generation++
        thread?.interrupt()
        thread = null
        text = ""
    }

    private fun sample(): String {
        val device = MemoryReadout.device() ?: return ""
        val memory = context.getString(
            R.string.memory_hud_simple,
            MemoryReadout.gb(device.usedKb), MemoryReadout.gb(device.totalKb),
            MemoryReadout.gb(device.swapUsedKb), MemoryReadout.gb(device.swapTotalKb),
        )
        if (SessionPrefs.memoryHud(context) != SessionPrefs.MEMORY_HUD_ADVANCED) return memory
        val split = MemoryReadout.split()
        val parts = context.getString(
            R.string.memory_hud_split,
            MemoryReadout.gb(split.gameKb), MemoryReadout.gb(split.droiddeckKb), MemoryReadout.gb(MemoryReadout.androidKb(device, split)),
        )
        val gpu = split.gpuKb?.let { context.getString(R.string.memory_hud_gpu, MemoryReadout.gb(it)) }
        return listOfNotNull(memory, parts, gpu).joinToString("\n")
    }

    private companion object {
        const val PERIOD_MS = 2000L
    }
}
