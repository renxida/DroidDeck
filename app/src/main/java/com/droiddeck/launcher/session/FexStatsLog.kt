package com.droiddeck.launcher.session

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * FEX's own per-thread counters for every emulated process (experiment fex-stats sets FEX_PROFILESTATS=1, and FEX
 * then keeps them in /dev/shm/fex-<pid>-stats, which is [shmDir] on the host), every 5 s, into fex.csv: what each
 * process spent translating x86 code, how often the code it had translated was overwritten (SMC: .NET writing new
 * methods next to old ones, patching), unaligned-atomic faults (SIGBUS), and code cache misses and locks.
 * Every value is the change over the interval, so `jit_ms / interval_ms` is the share of one core spent translating.
 * Layout: FEXCore/include/FEXCore/Utils/SHMStats.h, STATS_VERSION 2 (FEX-2611 and later).
 */
object FexStatsLog {
    const val FILE = "fex.csv"
    internal const val HEADER = "t_wall_ms,interval_ms,pid,process,threads,jit_ms,jit_blocks,smc,sigbus,signal_ms," +
        "float_fallback,cache_miss,cache_rlock_ms,cache_wlock_ms,disk_hit,disk_miss,disk_lookup_ms"
    private const val TAG = "FexStatsLog"
    private const val PERIOD_MS = 5_000L
    private const val HEADER_BYTES = 64
    private const val FIELDS = 12
    /** JITTime, SignalTime, SIGBUS, SMC, FloatFallback, CacheMiss, CacheReadLock, CacheWriteLock, JITCount, DiskHit,
     *  DiskMiss, DiskLookupTime: which are cycle counts. */
    private val IS_TIME = booleanArrayOf(true, true, false, false, false, false, true, true, false, false, false, true)
    /** Column order in fex.csv after `threads`, as indices into the FEX fields. */
    private val COLUMNS = intArrayOf(0, 8, 3, 2, 1, 4, 5, 6, 7, 9, 10, 11)

    internal data class Snapshot(val version: Int, val fexVersion: String, val threads: Map<Int, LongArray>)

    /** Parses one stats file; null if it isn't a version FEX we know. Threads are keyed by TID. */
    internal fun parse(bytes: ByteArray): Snapshot? {
        if (bytes.size < HEADER_BYTES) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = b.get(0).toInt() and 0xff
        if (version != 2) return null
        val stride = b.getShort(2).toInt() and 0xffff
        val fexVersion = String(bytes, 4, 48, Charsets.US_ASCII).substringBefore('\u0000')
        var off = b.getInt(52).toLong() and 0xffffffffL
        val threads = LinkedHashMap<Int, LongArray>()
        val need = 8 + 8 * FIELDS
        var guard = 0
        while (off != 0L && off + need <= bytes.size && guard++ < 4096) {
            val o = off.toInt()
            val next = b.getInt(o).toLong() and 0xffffffffL
            val tid = b.getInt(o + 4)
            threads[tid] = LongArray(FIELDS) { b.getLong(o + 8 + 8 * it) }
            if (stride != 0 && next == off) break
            off = next
        }
        return Snapshot(version, fexVersion, threads)
    }

    /**
     * Change in each counter between two snapshots of one process. A thread first seen now counts in full (it started
     * in the interval or the file just appeared); a thread whose counters went down was replaced and counts in full too.
     */
    internal fun delta(before: Map<Int, LongArray>?, after: Map<Int, LongArray>): LongArray {
        val sum = LongArray(FIELDS)
        for ((tid, now) in after) {
            val was = before?.get(tid)
            val reset = was != null && now[0] < was[0]
            for (k in 0 until FIELDS) sum[k] += if (was == null || reset) now[k] else (now[k] - was[k]).coerceAtLeast(0)
        }
        return sum
    }

    internal fun row(at: Long, intervalMs: Long, pid: Int, process: String, threads: Int, d: LongArray, cyclesPerSecond: Double): String {
        val cols = COLUMNS.joinToString(",") { k ->
            if (IS_TIME[k]) String.format(Locale.US, "%.1f", d[k] * 1000.0 / cyclesPerSecond) else d[k].toString()
        }
        return "$at,$intervalMs,$pid,$process,$threads,$cols"
    }

    @JvmStatic private external fun nativeTimerHz(): Long

    /** The generic timer's ticks per second (FEX counts time in them); 0 when it can't be read. */
    fun timerHz(): Double = runCatching {
        System.loadLibrary("deviceinfo")
        nativeTimerHz().toDouble()
    }.getOrDefault(0.0)

    private fun processName(pid: Int): String {
        val cmd = runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull() ?: return "?"
        val first = String(cmd).substringBefore('\u0000')
        return first.substringAfterLast('\\').substringAfterLast('/').replace(',', '_').ifEmpty { "?" }
    }

    @Volatile private var thread: Thread? = null

    @Synchronized
    fun start(sessionDir: File, shmDir: File) {
        stop()
        val hz = timerHz().takeIf { it > 0 } ?: run { Log.w(TAG, "no timer frequency; FEX stats not logged"); return }
        val csv = File(sessionDir, FILE)
        thread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            val last = HashMap<Int, Pair<Long, Map<Int, LongArray>>>()
            val names = HashMap<Int, String>()
            var wroteHeader = csv.isFile && csv.length() > 0
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val rows = ArrayList<String>()
                    val seen = HashSet<Int>()
                    shmDir.listFiles()?.forEach { f ->
                        val pid = Regex("""fex-(\d+)-stats""").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                        if (!File("/proc/$pid").exists()) return@forEach
                        val snap = parse(f.readBytes()) ?: return@forEach
                        seen += pid
                        val prev = last[pid]
                        val d = delta(prev?.second, snap.threads)
                        val name = names.getOrPut(pid) { processName(pid) }
                        rows += row(now, if (prev == null) 0 else now - prev.first, pid, name, snap.threads.size, d, hz)
                        last[pid] = now to snap.threads
                    }
                    last.keys.retainAll(seen)
                    names.keys.retainAll(seen)
                    if (rows.isNotEmpty()) {
                        if (!wroteHeader) { csv.appendText(HEADER + "\n"); wroteHeader = true }
                        csv.appendText(rows.joinToString("\n", postfix = "\n"))
                    }
                }.onFailure { Log.w(TAG, "sampling FEX stats", it) }
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
            }
        }, TAG).apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }
}
