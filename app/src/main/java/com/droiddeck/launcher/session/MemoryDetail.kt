package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the phone's memory went, without root: the whole of /proc/meminfo, zram, readable dma-buf, ION and GPU
 * totals, /proc/vmstat paging counters, memory pressure (PSI), ActivityManager's view, and every process that runs as
 * this app's user (the app, proot, the Linux side and the game) by RSS and swap. A row goes to [FILE] at session start
 * (kind prelaunch, also written out as [PRELAUNCH_FILE]), every 30 s from [MemoryLog]'s sampler, and when a Steam game is
 * asked to start (kind game_launch).
 *
 * The phone's own "System" figure is mostly memory an app cannot see. When Wireless debugging is on and Setup's Kadb
 * pairing is saved, `dumpsys meminfo -s` runs over that pairing (read-only) at session start and at a game launch
 * (never periodically during play, so it can't hitch the game), and its summary and top processes by PSS are kept too. Wireless debugging is never
 * turned on, nothing is paired, and no setting changes; without it the dumpsys part is skipped.
 */
internal object MemoryDetail {
    const val FILE = "memory-detail.jsonl"
    const val PRELAUNCH_FILE = "memory-prelaunch.txt"
    /** Past this size only every [THIN_EVERY]th sample is kept; at [MAX_BYTES] the file stops growing. */
    internal const val SOFT_BYTES = 1L shl 20
    internal const val MAX_BYTES = 2L shl 20
    internal const val THIN_EVERY = 4
    /** [MemoryLog] samples every 5 s; a detail row every sixth sample is every 30 s. */
    internal const val LOG_EVERY = 6L
    private const val MIN_GAP_MS = 20_000L
    /** ActivityManager's per-process PSS reads smaps of the app's own processes, so only every tenth row. */
    private const val ANDROID_EVERY = 10
    private const val DUMPSYS_AFTER_LAUNCH_MS = 60_000L
    private const val DUMPSYS_CONNECT_MS = 3_000
    private const val DUMPSYS_SOCKET_MS = 20_000
    internal const val DUMPSYS_COMMAND = "dumpsys meminfo -s"
    private const val HEADLINE_FRESH_MS = 90_000L
    private const val DMABUF_MAX_ENTRIES = 8192
    private const val DMABUF_MAX_MS = 250L
    internal const val TOP = 12
    internal const val DUMPSYS_TOP = 15
    private const val TAG = "MemoryDetail"

    const val APP = "app"
    const val PROOT = "proot"
    const val RUNTIME = "runtime"
    const val GAME = "game"
    private val GROUPS = listOf(APP, PROOT, RUNTIME, GAME)

    internal const val ESTIMATE_NOTE = "estimates: system_kb = MemTotal - MemAvailable - Lanner's processes (anon+shmem RSS), " +
        "so it still holds other running apps; kernel_total_kb = unreclaimable slab + kernel stacks + page tables + vmalloc + " +
        "dma-buf/ION + GPU + zram pool + unaccounted (MemTotal less every meminfo bucket and those)"

    // ---------- what one snapshot holds ----------

    data class Zram(
        val dev: String, val disksizeBytes: Long?, val origBytes: Long?, val comprBytes: Long?, val usedBytes: Long?,
        val limitBytes: Long?, val usedMaxBytes: Long?, val samePages: Long?, val compactedPages: Long?, val hugePages: Long?,
        val algorithm: String?,
    ) {
        val ratio: Double? get() = if (origBytes != null && comprBytes != null && comprBytes > 0) origBytes.toDouble() / comprBytes else null
    }

    data class Dmabuf(val source: String, val kb: Long, val buffers: Int?, val exporters: List<Pair<String, Long>> = emptyList())

    /** One process of this app's user from /proc/<pid>/status; [group] is one of [APP], [PROOT], [RUNTIME], [GAME]. */
    data class Proc(
        val pid: Int, val ppid: Int, val uid: Int?, val name: String,
        val rssKb: Long, val anonKb: Long, val fileKb: Long, val shmemKb: Long, val swapKb: Long,
        val group: String = "",
    ) {
        /** Resident memory that is not file cache: what the process costs while it runs. */
        val residentKb get() = anonKb + shmemKb
    }

    data class ProcInfo(val argv0: String, val windows: Boolean)

    class GroupSum {
        var count = 0; var rssKb = 0L; var anonKb = 0L; var fileKb = 0L; var shmemKb = 0L; var swapKb = 0L
        fun add(p: Proc) { count++; rssKb += p.rssKb; anonKb += p.anonKb; fileKb += p.fileKb; shmemKb += p.shmemKb; swapKb += p.swapKb }
    }

    data class OwnProc(val pid: Int, val name: String, val importance: Int, val pssKb: Long?, val swapKb: Long?, val graphicsKb: Long?)

    data class AndroidMem(val availKb: Long, val totalKb: Long, val thresholdKb: Long, val low: Boolean, val own: List<OwnProc>)

    data class Estimate(
        val usedKb: Long, val lannerKb: Long, val systemKb: Long,
        val slabUnreclaimKb: Long, val stackKb: Long, val pageTablesKb: Long, val vmallocKb: Long,
        val driverKb: Long, val driverSource: String?, val gpuKb: Long, val zramKb: Long, val unaccountedKb: Long,
    ) {
        val kernelTotalKb get() = slabUnreclaimKb + stackKb + pageTablesKb + vmallocKb + driverKb + gpuKb + zramKb + unaccountedKb
    }

    class Snapshot(
        val atMs: Long,
        val kind: String,
        val meminfo: Map<String, Long>,
        val zram: List<Zram>,
        val dmabuf: Dmabuf?,
        val kernelFiles: Map<String, Long>,
        val gpuKb: Long?,
        val vmstat: Map<String, Long>,
        val psi: Map<String, Double>,
        val android: AndroidMem?,
        val procs: List<Proc>,
    ) {
        val estimate: Estimate? = MemoryDetail.estimate(meminfo, procs.sumOf { it.residentKb }, zram.sumOf { it.usedBytes ?: 0L } / 1024,
            dmabuf, gpuKb)

        fun groups(): Map<String, GroupSum> {
            val out = LinkedHashMap<String, GroupSum>()
            GROUPS.forEach { out[it] = GroupSum() }
            procs.forEach { out.getOrPut(it.group) { GroupSum() }.add(it) }
            return out
        }

        fun top(n: Int = TOP): List<Proc> = procs.sortedByDescending { it.rssKb + it.swapKb }.take(n)
        fun kb(name: String): Long? = meminfo[name]
    }

    data class DumpsysProc(val name: String, val pid: Int, val kb: Long)

    data class Dumpsys(
        val totalRamKb: Long?, val freeRamKb: Long?, val freeParts: Map<String, Long>,
        val usedRamKb: Long?, val usedParts: Map<String, Long>, val lostRamKb: Long?,
        val zramPhysicalKb: Long?, val zramSwapKb: Long?, val zramTotalSwapKb: Long?,
        val extra: Map<String, Long>,
        val metric: String,
        val processes: List<DumpsysProc>,
        val oom: List<Pair<String, Long>>,
    )

    // ---------- parsers: pure ----------

    private val MEMINFO_LINE = Regex("""^([A-Za-z0-9_()\-]+):\s+(\d+)(?:\s+kB)?\s*$""")

    /** Every numeric field of /proc/meminfo in file order; values are kB except the HugePages_ counts. */
    fun meminfoFields(text: String): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in text.lineSequence()) {
            val m = MEMINFO_LINE.find(line.trim()) ?: continue
            m.groupValues[2].toLongOrNull()?.let { out[m.groupValues[1]] = it }
        }
        return out
    }

    private val DRIVER_FIELD = Regex("""(?i)(ion|dma.?buf|gpu|kgsl|mali|pvr)""")

    /** Driver memory that some kernels add to /proc/meminfo (ION, DMA-buf, GPU), pools left out. */
    fun meminfoDriverLines(meminfo: Map<String, Long>): Map<String, Long> =
        meminfo.filterKeys { DRIVER_FIELD.containsMatchIn(it) && !it.contains("pool", ignoreCase = true) }

    fun estimate(m: Map<String, Long>, lannerKb: Long, zramKb: Long, dmabuf: Dmabuf?, gpuKb: Long?): Estimate? {
        val total = m["MemTotal"] ?: return null
        val available = m["MemAvailable"] ?: return null
        fun f(name: String) = m[name] ?: 0L
        val slab = m["Slab"] ?: ((m["SReclaimable"] ?: 0L) + (m["SUnreclaim"] ?: 0L))
        val unreclaim = m["SUnreclaim"] ?: (slab - f("SReclaimable")).coerceAtLeast(0)
        val meminfoDrivers = meminfoDriverLines(m)
        val (driver, driverSource) = when {
            dmabuf != null -> dmabuf.kb to dmabuf.source
            meminfoDrivers.isNotEmpty() -> meminfoDrivers.values.sum() to "meminfo"
            else -> 0L to null
        }
        val gpu = gpuKb ?: 0L
        val used = (total - available).coerceAtLeast(0)
        val accounted = f("MemFree") + f("Buffers") + f("Cached") + f("SwapCached") + f("AnonPages") + slab +
            f("KernelStack") + f("PageTables") + f("VmallocUsed") + driver + gpu + zramKb
        return Estimate(
            usedKb = used, lannerKb = lannerKb, systemKb = (used - lannerKb).coerceAtLeast(0),
            slabUnreclaimKb = unreclaim, stackKb = f("KernelStack"), pageTablesKb = f("PageTables"), vmallocKb = f("VmallocUsed"),
            driverKb = driver, driverSource = driverSource, gpuKb = gpu, zramKb = zramKb,
            unaccountedKb = (total - accounted).coerceAtLeast(0),
        )
    }

    /** One zram device from its mm_stat, disksize and comp_algorithm files; null when none of them could be read. */
    fun parseZram(dev: String, mmStat: String?, disksize: String?, algorithm: String?): Zram? {
        val fields = mmStat?.trim()?.split(Regex("\\s+"))?.map { it.toLongOrNull() }.orEmpty()
        val size = disksize?.trim()?.toLongOrNull()
        val algo = algorithm?.let(::selectedAlgorithm)
        if (fields.all { it == null } && size == null && algo == null) return null
        return Zram(dev, size, fields.getOrNull(0), fields.getOrNull(1), fields.getOrNull(2), fields.getOrNull(3),
            fields.getOrNull(4), fields.getOrNull(5), fields.getOrNull(6), fields.getOrNull(7), algo)
    }

    /** "lzo lzo-rle [lz4] zstd" names the selected algorithm in brackets; a single word is the only one. */
    fun selectedAlgorithm(text: String): String? {
        Regex("""\[([^\]\s]+)]""").find(text)?.let { return it.groupValues[1] }
        return text.trim().split(Regex("\\s+")).singleOrNull()?.takeIf { it.isNotEmpty() }
    }

    private val STATUS_KB = Regex("""^(VmRSS|RssAnon|RssFile|RssShmem|VmSwap):\s+(\d+)\s*kB""")

    /** A process's memory from /proc/<pid>/status; null without a Name line. */
    fun parseStatus(pid: Int, text: String): Proc? {
        var name: String? = null
        var ppid = 0
        var uid: Int? = null
        val kb = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            when {
                line.startsWith("Name:") -> name = line.substringAfter(':').trim()
                line.startsWith("PPid:") -> ppid = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("Uid:") -> uid = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                else -> STATUS_KB.find(line)?.let { kb[it.groupValues[1]] = it.groupValues[2].toLong() }
            }
        }
        val n = name ?: return null
        val anon = kb["RssAnon"] ?: 0L
        val file = kb["RssFile"] ?: 0L
        val shmem = kb["RssShmem"] ?: 0L
        return Proc(pid, ppid, uid, n, kb["VmRSS"] ?: (anon + file + shmem), anon, file, shmem, kb["VmSwap"] ?: 0L)
    }

    /** The first argument of /proc/<pid>/cmdline (NUL separated). */
    fun argv0(cmdline: ByteArray): String {
        val end = cmdline.indexOf(0.toByte()).let { if (it < 0) cmdline.size else it }
        return String(cmdline, 0, end, Charsets.UTF_8)
    }

    private val GAME_NAME = Regex("""(?i)^(wine.*|.*\.exe|godot.*|droiddeck-nati.*|.*\.x86_64|.*\.arm64)$""")

    fun baseGroup(name: String, info: ProcInfo?, packageName: String?): String {
        val argv0 = info?.argv0.orEmpty()
        val program = argv0.substringAfterLast('/')
        return when {
            !packageName.isNullOrEmpty() && argv0.startsWith(packageName) -> APP
            name.startsWith("libproot") || name == "proot" || program.startsWith("libproot") -> PROOT
            info?.windows == true || GAME_NAME.matches(name) || (program.isNotEmpty() && GAME_NAME.matches(program)) -> GAME
            else -> RUNTIME
        }
    }

    /** Groups each process; a [RUNTIME] process started by a game process (any ancestor) is part of the game. */
    fun classify(procs: List<Proc>, info: Map<Int, ProcInfo>, packageName: String?): List<Proc> {
        val base = procs.associate { it.pid to baseGroup(it.name, info[it.pid], packageName) }
        val byPid = procs.associateBy { it.pid }
        return procs.map { p ->
            var group = base.getValue(p.pid)
            if (group == RUNTIME) {
                var up = p.ppid
                var hops = 0
                while (up > 1 && hops++ < 64) {
                    if (base[up] == GAME) { group = GAME; break }
                    up = byPid[up]?.ppid ?: break
                }
            }
            p.copy(group = group)
        }
    }

    private val VMSTAT_KEYS = listOf("pgmajfault", "pswpin", "pswpout", "workingset_refault", "workingset_activate",
        "workingset_restore", "pgscan_kswapd", "pgscan_direct", "pgsteal_kswapd", "pgsteal_direct", "allocstall",
        "compact_stall", "oom_kill", "pgpgin", "pgpgout")

    /** The paging and reclaim counters of /proc/vmstat (cumulative since boot). */
    fun vmstatCounters(text: String): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(' ')
            if (parts.size != 2) continue
            if (VMSTAT_KEYS.none { parts[0].startsWith(it) }) continue
            parts[1].toLongOrNull()?.let { out[parts[0]] = it }
        }
        return out
    }

    /** /proc/pressure/memory as some_avg10, some_avg60, some_avg300, some_total, full_... */
    fun parsePsi(text: String): LinkedHashMap<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            val kind = parts.firstOrNull()?.takeIf { it == "some" || it == "full" } ?: continue
            for (field in parts.drop(1)) {
                val key = field.substringBefore('=', "")
                val value = field.substringAfter('=', "").toDoubleOrNull() ?: continue
                if (key.isNotEmpty()) out["${kind}_$key"] = value
            }
        }
        return out
    }

    private val KB = """(-?[\d,]+)K"""
    private val TOTAL_RAM = Regex("""^\s*Total RAM:\s*$KB""")
    private val FREE_RAM = Regex("""^\s*Free RAM:\s*$KB(?:\s*\((.*)\))?""")
    private val USED_RAM = Regex("""^\s*Used RAM:\s*$KB(?:\s*\((.*)\))?""")
    private val LOST_RAM = Regex("""^\s*Lost RAM:\s*$KB""")
    private val ZRAM_LINE = Regex("""^\s*ZRAM:\s*$KB physical used for\s*$KB in swap(?:\s*\(\s*$KB total swap\))?""")
    private val EXTRA_LINE = Regex("""^\s*(DMA-BUF(?: Heaps(?: pool)?)?|GPU|ION):\s*$KB""")
    private val PROC_LINE = Regex("""^(\s*)$KB: (.+?) \(pid (\d+)""")
    private val OOM_LINE = Regex("""^(\s*)$KB: ([^(]+?)\s*(?:\(.*\))?\s*$""")
    private val PART = Regex("""^\s*$KB\s+(.+?)\s*$""")

    private fun kb(text: String): Long? = text.replace(",", "").toLongOrNull()

    private fun parts(inner: String?): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        inner?.split('+')?.forEach { part -> PART.find(part)?.let { m -> kb(m.groupValues[1])?.let { out[m.groupValues[2]] = it } } }
        return out
    }

    /** The lines of the section that starts with [title], up to the next blank line. */
    private fun section(lines: List<String>, title: String): List<String> {
        val start = lines.indexOfFirst { it.trim() == title }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.isNotBlank() }
    }

    /** The summary and the biggest processes of `dumpsys meminfo` (any of -s, -a or none); null when it has neither. */
    fun parseDumpsys(text: String, top: Int = DUMPSYS_TOP): Dumpsys? {
        val lines = text.lines()
        var total: Long? = null; var free: Long? = null; var used: Long? = null; var lost: Long? = null
        var freeParts = emptyMap<String, Long>(); var usedParts = emptyMap<String, Long>()
        var zPhys: Long? = null; var zSwap: Long? = null; var zTotal: Long? = null
        val extra = LinkedHashMap<String, Long>()
        for (line in lines) {
            TOTAL_RAM.find(line)?.let { total = kb(it.groupValues[1]) }
            FREE_RAM.find(line)?.let { free = kb(it.groupValues[1]); freeParts = parts(it.groupValues[2]) }
            USED_RAM.find(line)?.let { used = kb(it.groupValues[1]); usedParts = parts(it.groupValues[2]) }
            LOST_RAM.find(line)?.let { lost = kb(it.groupValues[1]) }
            ZRAM_LINE.find(line)?.let { zPhys = kb(it.groupValues[1]); zSwap = kb(it.groupValues[2]); zTotal = kb(it.groupValues[3]) }
            EXTRA_LINE.find(line)?.let { m -> kb(m.groupValues[2])?.let { extra[m.groupValues[1]] = it } }
        }
        val pss = section(lines, "Total PSS by process:")
        val (metric, procLines) = if (pss.isNotEmpty()) "pss" to pss else "rss" to section(lines, "Total RSS by process:")
        val processes = procLines.mapNotNull { line ->
            PROC_LINE.find(line)?.let { m ->
                val k = kb(m.groupValues[2]) ?: return@let null
                DumpsysProc(m.groupValues[3].trim(), m.groupValues[4].toInt(), k)
            }
        }.sortedByDescending { it.kb }.take(top)
        val oomLines = section(lines, "Total ${metric.uppercase(Locale.US)} by OOM adjustment:")
        val indent = oomLines.firstOrNull()?.let { it.length - it.trimStart().length }
        val oom = oomLines.filter { !it.contains("(pid ") && it.length - it.trimStart().length == indent }.mapNotNull { line ->
            OOM_LINE.find(line)?.let { m -> kb(m.groupValues[2])?.let { m.groupValues[3].trim() to it } }
        }
        if (total == null && processes.isEmpty()) return null
        return Dumpsys(total, free, freeParts, used, usedParts, lost, zPhys, zSwap, zTotal, extra, metric, processes, oom)
    }

    // ---------- output: pure ----------

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun mb(kb: Long?) = kb?.let { (it / 1024).toString() } ?: "-"
    private fun gb(kb: Long?) = kb?.let { String.format(Locale.US, "%.1f", it / (1024.0 * 1024.0)) } ?: "-"

    internal fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append(String.format(Locale.US, "\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    /** A minimal JSON object writer; org.json is a stub in plain unit tests. */
    internal class Obj {
        private val sb = StringBuilder("{")
        private fun key(k: String) { if (sb.length > 1) sb.append(','); sb.append(quote(k)).append(':') }
        fun num(k: String, v: Long?) = apply { if (v != null) { key(k); sb.append(v) } }
        fun num(k: String, v: Int?) = apply { if (v != null) { key(k); sb.append(v) } }
        fun dec(k: String, v: Double?) = apply { if (v != null && !v.isNaN() && !v.isInfinite()) { key(k); sb.append(f2(v)) } }
        fun str(k: String, v: String?) = apply { if (v != null) { key(k); sb.append(quote(v)) } }
        fun bool(k: String, v: Boolean?) = apply { if (v != null) { key(k); sb.append(v) } }
        fun raw(k: String, json: String?) = apply { if (json != null) { key(k); sb.append(json) } }
        fun longs(k: String, map: Map<String, Long>) = apply { if (map.isNotEmpty()) raw(k, Obj().also { o -> map.forEach { (n, v) -> o.num(n, v) } }.text()) }
        fun text() = sb.toString() + "}"
    }

    private fun array(items: List<String>) = items.joinToString(",", "[", "]")

    internal fun estimateJson(e: Estimate, withNote: Boolean): String = Obj()
        .num("used_kb", e.usedKb).num("lanner_kb", e.lannerKb).num("system_kb", e.systemKb)
        .num("kernel_total_kb", e.kernelTotalKb).num("slab_unreclaim_kb", e.slabUnreclaimKb).num("stack_kb", e.stackKb)
        .num("page_tables_kb", e.pageTablesKb).num("vmalloc_kb", e.vmallocKb).num("driver_kb", e.driverKb)
        .str("driver_source", e.driverSource).num("gpu_kb", e.gpuKb).num("zram_kb", e.zramKb).num("unaccounted_kb", e.unaccountedKb)
        .str("note", if (withNote) ESTIMATE_NOTE else null).text()

    /** One [FILE] row for a snapshot. */
    fun jsonLine(s: Snapshot): String {
        val o = Obj().num("t", s.atMs).str("kind", s.kind)
        s.estimate?.let { o.raw("est", estimateJson(it, withNote = s.kind == "prelaunch")) }
        o.longs("meminfo", s.meminfo)
        if (s.zram.isNotEmpty()) o.raw("zram", array(s.zram.map { z ->
            Obj().str("dev", z.dev).num("disksize", z.disksizeBytes).num("orig_data_size", z.origBytes)
                .num("compr_data_size", z.comprBytes).num("mem_used_total", z.usedBytes).num("mem_limit", z.limitBytes)
                .num("mem_used_max", z.usedMaxBytes).num("same_pages", z.samePages).num("pages_compacted", z.compactedPages)
                .num("huge_pages", z.hugePages).str("comp_algorithm", z.algorithm).text()
        }))
        s.dmabuf?.let { d ->
            o.raw("dmabuf", Obj().str("source", d.source).num("kb", d.kb).num("buffers", d.buffers)
                .longs("exporters_kb", d.exporters.toMap()).text())
        }
        o.longs("kernel_files", s.kernelFiles)
        o.num("gpu_kb", s.gpuKb)
        o.longs("vmstat", s.vmstat)
        if (s.psi.isNotEmpty()) o.raw("psi", Obj().also { p -> s.psi.forEach { (k, v) -> p.dec(k, v) } }.text())
        s.android?.let { a ->
            o.raw("android", Obj().num("avail_kb", a.availKb).num("total_kb", a.totalKb).num("threshold_kb", a.thresholdKb)
                .bool("low_memory", a.low).raw("own", array(a.own.map { p ->
                    Obj().num("pid", p.pid).str("name", p.name).num("importance", p.importance).num("pss_kb", p.pssKb)
                        .num("swap_kb", p.swapKb).num("graphics_kb", p.graphicsKb).text()
                })).text())
        }
        val groups = s.groups()
        o.raw("groups", Obj().also { g ->
            groups.forEach { (name, sum) ->
                g.raw(name, Obj().num("n", sum.count).num("rss_kb", sum.rssKb).num("anon_kb", sum.anonKb).num("file_kb", sum.fileKb)
                    .num("shmem_kb", sum.shmemKb).num("swap_kb", sum.swapKb).text())
            }
        }.text())
        o.raw("top", array(s.top().map { p ->
            Obj().num("pid", p.pid).num("ppid", p.ppid).str("name", p.name).str("group", p.group).num("rss_kb", p.rssKb)
                .num("anon_kb", p.anonKb).num("file_kb", p.fileKb).num("shmem_kb", p.shmemKb).num("swap_kb", p.swapKb).text()
        }))
        return o.text()
    }

    fun dumpsysJsonLine(atMs: Long, reason: String, d: Dumpsys): String = Obj().num("t", atMs).str("kind", "dumpsys").str("reason", reason)
        .num("total_ram_kb", d.totalRamKb).num("free_ram_kb", d.freeRamKb).longs("free_parts_kb", d.freeParts)
        .num("used_ram_kb", d.usedRamKb).longs("used_parts_kb", d.usedParts).num("lost_ram_kb", d.lostRamKb)
        .num("zram_physical_kb", d.zramPhysicalKb).num("zram_in_swap_kb", d.zramSwapKb).num("zram_total_swap_kb", d.zramTotalSwapKb)
        .longs("other_kb", d.extra).str("metric", d.metric)
        .raw("oom_kb", Obj().also { o -> d.oom.forEach { (n, v) -> o.num(n, v) } }.text())
        .raw("top", array(d.processes.map { Obj().str("name", it.name).num("pid", it.pid).num("kb", it.kb).text() }))
        .text()

    fun skipJsonLine(atMs: Long, reason: String, why: String): String =
        Obj().num("t", atMs).str("kind", "dumpsys_skipped").str("reason", reason).str("why", why).text()

    /** Per-second rates of the paging counters between two snapshots; empty without an earlier one. */
    fun rates(prev: Snapshot?, s: Snapshot): Map<String, Double> {
        if (prev == null || s.atMs <= prev.atMs) return emptyMap()
        val secs = (s.atMs - prev.atMs) / 1000.0
        fun delta(match: (String) -> Boolean): Long? {
            val now = s.vmstat.filterKeys(match).values.takeIf { it.isNotEmpty() }?.sum() ?: return null
            val before = prev.vmstat.filterKeys(match).values.takeIf { it.isNotEmpty() }?.sum() ?: return null
            return (now - before).takeIf { it >= 0 }
        }
        val pageMb = 4096.0 / (1024 * 1024)
        val out = LinkedHashMap<String, Double>()
        delta { it == "pgmajfault" }?.let { out["majflt_s"] = it / secs }
        delta { it == "pswpin" }?.let { out["swapin_mb_s"] = it * pageMb / secs }
        delta { it == "pswpout" }?.let { out["swapout_mb_s"] = it * pageMb / secs }
        delta { it.startsWith("workingset_refault") }?.let { out["refault_s"] = it / secs }
        return out
    }

    private fun procLine(p: Proc) = String.format(Locale.US, "%-15s %-7s rss %5d anon %5d file %4d shmem %4d swap %5d MB (pid %d)",
        p.name.take(15), p.group, p.rssKb / 1024, p.anonKb / 1024, p.fileKb / 1024, p.shmemKb / 1024, p.swapKb / 1024, p.pid)

    private fun splitLine(s: Snapshot) = s.groups().entries.joinToString(", ") { (g, sum) -> "$g ${mb(sum.rssKb)}/${mb(sum.swapKb)}" } +
        " MB (rss/swap)"

    private fun estimateLines(e: Estimate): List<String> = listOf(
        "system (estimate) ${mb(e.systemKb)} MB = used ${mb(e.usedKb)} - Lanner ${mb(e.lannerKb)} (MemTotal-MemAvailable less " +
            "Lanner's anon+shmem; still holds other running apps)",
        "kernel part (estimate) ${mb(e.kernelTotalKb)} MB: unreclaimable slab ${mb(e.slabUnreclaimKb)}, stacks ${mb(e.stackKb)}, " +
            "page tables ${mb(e.pageTablesKb)}, vmalloc ${mb(e.vmallocKb)}, dma-buf/ION ${mb(e.driverKb)}" +
            (e.driverSource?.let { " ($it)" } ?: "") + ", gpu ${mb(e.gpuKb)}, zram pool ${mb(e.zramKb)}, unaccounted ${mb(e.unaccountedKb)}",
    )

    private fun ramLine(s: Snapshot) = "ram: total ${mb(s.kb("MemTotal"))}, available ${mb(s.kb("MemAvailable"))}, " +
        "free ${mb(s.kb("MemFree"))}, file cache ${mb(s.kb("Cached"))}, anon ${mb(s.kb("AnonPages"))}, " +
        "shmem ${mb(s.kb("Shmem"))}, swap cached ${mb(s.kb("SwapCached"))} MB"

    private fun swapLine(s: Snapshot): String {
        val total = s.kb("SwapTotal")
        val used = total?.let { it - (s.kb("SwapFree") ?: it) }
        val z = s.zram.joinToString("; ") { z ->
            "${z.dev} ${mb(z.origBytes?.div(1024))} MB stored in ${mb(z.usedBytes?.div(1024))} MB" +
                (z.ratio?.let { " (${f2(it)}x)" } ?: "") + (z.algorithm?.let { " $it" } ?: "") +
                (z.disksizeBytes?.let { ", disksize ${mb(it / 1024)}" } ?: "")
        }
        return "swap: ${mb(used)} of ${mb(total)} MB used" + if (z.isNotEmpty()) "; $z" else "; zram stats not readable"
    }

    private fun dumpsysSummary(d: Dumpsys): String {
        fun partsText(p: Map<String, Long>) = if (p.isEmpty()) "" else " (" + p.entries.joinToString(" + ") { "${mb(it.value)} ${it.key}" } + ")"
        return "Total ${mb(d.totalRamKb)}, Free ${mb(d.freeRamKb)}${partsText(d.freeParts)}, Used ${mb(d.usedRamKb)}${partsText(d.usedParts)}, " +
            "Lost ${mb(d.lostRamKb)}, ZRAM ${mb(d.zramPhysicalKb)} physical for ${mb(d.zramSwapKb)} in swap MB"
    }

    /** The "memory detail" section of the live status file; kept under the live file's 1500 characters per section. */
    fun liveText(s: Snapshot, prev: Snapshot?, dumpsys: Pair<Long, Dumpsys>?, dumpsysNote: String?, nowMs: Long): String = buildString {
        appendLine("at ${((nowMs - s.atMs) / 1000).coerceAtLeast(0)} s ago (${s.kind})")
        appendLine(ramLine(s))
        appendLine(swapLine(s))
        s.estimate?.let { e -> estimateLines(e).forEach { appendLine(it) } }
        appendLine("split: " + splitLine(s))
        val r = rates(prev, s)
        val psi = s.psi
        appendLine("pressure: " + (if (psi.isEmpty()) "psi not readable" else
            "some avg10 ${psi["some_avg10"]?.let(::f2) ?: "-"}, full avg10 ${psi["full_avg10"]?.let(::f2) ?: "-"}") +
            (if (r.isEmpty()) "" else "; " + r.entries.joinToString(", ") { "${it.key} ${String.format(Locale.US, "%.1f", it.value)}" }))
        s.android?.let { a -> appendLine("android: availMem ${mb(a.availKb)} MB, threshold ${mb(a.thresholdKb)} MB, lowMemory ${a.low}") }
        appendLine("top (rss+swap MB): " + s.top(5).joinToString(", ") { "${it.name.take(15)} ${it.rssKb / 1024}+${it.swapKb / 1024}" })
        when {
            dumpsys != null -> {
                val (at, d) = dumpsys
                appendLine("dumpsys ${((nowMs - at) / 60_000).coerceAtLeast(0)} min ago: ${dumpsysSummary(d)}")
                appendLine("dumpsys top ${d.metric}: " + d.processes.take(5).joinToString(", ") { "${it.name.take(28)} ${it.kb / 1024}" })
            }
            dumpsysNote != null -> appendLine("dumpsys: $dumpsysNote")
        }
    }.trimEnd()

    private fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** [PRELAUNCH_FILE]: the session-start snapshot in full, for reading without a JSON tool. */
    fun prelaunchText(s: Snapshot): String = buildString {
        appendLine("# Lanner memory before launch, ${utc(s.atMs)} (session start, before the Linux side starts). Sizes in MB.")
        appendLine(ramLine(s))
        appendLine(swapLine(s))
        s.estimate?.let { e -> estimateLines(e).forEach { appendLine(it) } }
        s.dmabuf?.let { d ->
            appendLine("dma-buf: ${mb(d.kb)} MB from ${d.source}" + (d.buffers?.let { " in $it buffers" } ?: "") +
                (if (d.exporters.isEmpty()) "" else "; " + d.exporters.joinToString(", ") { "${it.first} ${mb(it.second)}" }))
        }
        if (s.kernelFiles.isNotEmpty()) appendLine("kernel files (raw values): " + s.kernelFiles.entries.joinToString(", ") { "${it.key}=${it.value}" })
        appendLine("pressure: " + if (s.psi.isEmpty()) "psi not readable" else s.psi.entries.joinToString(" ") { "${it.key}=${f2(it.value)}" })
        s.android?.let { a ->
            appendLine("android: availMem ${mb(a.availKb)}, totalMem ${mb(a.totalKb)}, threshold ${mb(a.thresholdKb)}, lowMemory ${a.low}")
            a.own.forEach { appendLine("  ${it.name} (pid ${it.pid}, importance ${it.importance}): pss ${mb(it.pssKb)}, swap ${mb(it.swapKb)}, graphics ${mb(it.graphicsKb)}") }
        }
        appendLine("split: " + splitLine(s))
        appendLine("processes of this app's user, biggest first:")
        s.top().forEach { appendLine("  " + procLine(it)) }
        appendLine("meminfo (kB): " + s.meminfo.entries.joinToString(" ") { "${it.key}=${it.value}" })
        if (s.vmstat.isNotEmpty()) appendLine("vmstat: " + s.vmstat.entries.joinToString(" ") { "${it.key}=${it.value}" })
        appendLine(ESTIMATE_NOTE)
    }

    fun dumpsysText(atMs: Long, reason: String, d: Dumpsys): String = buildString {
        appendLine()
        appendLine("## $DUMPSYS_COMMAND over Wireless debugging, ${utc(atMs)} ($reason)")
        appendLine(dumpsysSummary(d))
        if (d.extra.isNotEmpty()) appendLine(d.extra.entries.joinToString(", ") { "${it.key} ${mb(it.value)} MB" })
        if (d.oom.isNotEmpty()) appendLine("by OOM adjustment (${d.metric}, MB): " + d.oom.joinToString(", ") { "${it.first} ${mb(it.second)}" })
        appendLine("top by ${d.metric} (MB):")
        d.processes.forEach { appendLine(String.format(Locale.US, "  %6d  %s (pid %d)", it.kb / 1024, it.name, it.pid)) }
    }

    /** The headline numbers for the remote "ping" and the live file's device section; sizes in MB. */
    fun headline(s: Snapshot, dumpsys: Pair<Long, Dumpsys>?): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        fun put(k: String, kb: Long?) { if (kb != null) out[k] = kb / 1024 }
        put("mem_total_mb", s.kb("MemTotal"))
        put("mem_available_mb", s.kb("MemAvailable"))
        put("mem_free_mb", s.kb("MemFree"))
        val swapTotal = s.kb("SwapTotal")
        put("swap_total_mb", swapTotal)
        put("swap_used_mb", swapTotal?.let { it - (s.kb("SwapFree") ?: it) })
        s.estimate?.let { e ->
            put("mem_system_est_mb", e.systemKb)
            put("mem_kernel_est_mb", e.kernelTotalKb)
            put("mem_lanner_mb", e.lannerKb)
            put("mem_zram_pool_mb", e.zramKb.takeIf { s.zram.isNotEmpty() })
        }
        out["mem_split"] = splitLine(s)
        out["mem_top"] = s.top(3).joinToString("; ") { "${it.name.take(15)} ${it.rssKb / 1024}+${it.swapKb / 1024} MB" }
        out["mem_at"] = s.atMs
        dumpsys?.let { (at, d) -> out["mem_dumpsys"] = "${dumpsysSummary(d)} at ${utc(at)}" }
        return out
    }

    // ---------- reading the device ----------

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private fun zramDevices(): List<Zram> = File("/sys/block").list()?.filter { it.startsWith("zram") }?.sorted()?.mapNotNull { dev ->
        parseZram(dev, read("/sys/block/$dev/mm_stat"), read("/sys/block/$dev/disksize"), read("/sys/block/$dev/comp_algorithm"))
    }.orEmpty()

    /** Sum of /sys/kernel/dmabuf/buffers/<inode>/size, by exporter; null when the directory cannot be listed. */
    private fun dmabufSysfs(): Dmabuf? {
        val names = File("/sys/kernel/dmabuf/buffers").list() ?: return null
        val began = System.currentTimeMillis()
        var bytes = 0L
        var count = 0
        val exporters = HashMap<String, Long>()
        for (name in names.take(DMABUF_MAX_ENTRIES)) {
            if (System.currentTimeMillis() - began > DMABUF_MAX_MS) break
            val size = read("/sys/kernel/dmabuf/buffers/$name/size")?.trim()?.toLongOrNull() ?: continue
            bytes += size
            count++
            val exporter = read("/sys/kernel/dmabuf/buffers/$name/exporter_name")?.trim().orEmpty().ifEmpty { "?" }
            exporters[exporter] = (exporters[exporter] ?: 0L) + size / 1024
        }
        if (count == 0) return null
        return Dmabuf("/sys/kernel/dmabuf/buffers", bytes / 1024, count,
            exporters.entries.sortedByDescending { it.value }.take(4).map { it.key to it.value })
    }

    /** ION and DMA-heap totals some kernels publish in kB. */
    private val HEAP_TOTALS = listOf("/sys/kernel/ion/total_heaps_kb", "/sys/kernel/ion/total_pools_kb",
        "/sys/kernel/dma_heap/total_pools_kb")
    private const val KGSL_PAGE_ALLOC = "/sys/class/kgsl/kgsl/page_alloc"
    /** GPU drivers' sysfs folders; any small readable file in them whose name speaks of memory is recorded raw. */
    private val GPU_DIRS = listOf("/sys/class/kgsl/kgsl", "/sys/kernel/gpu", "/sys/class/misc/mali0/device", "/sys/kernel/pvr")
    private val GPU_FILE = Regex("""(?i)(mem|alloc|pool|coherent|mapped|secure)""")

    private fun kernelFiles(): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (path in HEAP_TOTALS) read(path)?.trim()?.toLongOrNull()?.let { out[path] = it }
        for (folder in GPU_DIRS) {
            val names = File(folder).list()?.filter { GPU_FILE.containsMatchIn(it) && !it.endsWith("_max") }?.sorted() ?: continue
            for (name in names.take(16)) {
                val file = File(folder, name)
                if (!file.isFile || file.length() > 64) continue
                read(file.path)?.trim()?.toLongOrNull()?.let { out[file.path] = it }
            }
        }
        return out
    }

    /** ION's in-use total when per-buffer dma-buf stats are not readable; the pools are free pages kept for reuse. */
    private fun heapDmabuf(files: Map<String, Long>): Dmabuf? =
        files["/sys/kernel/ion/total_heaps_kb"]?.let { Dmabuf("/sys/kernel/ion/total_heaps_kb", it, null) }

    private fun androidMem(app: Context, withProcesses: Boolean): AndroidMem? = runCatching {
        val am = app.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val own = if (!withProcesses) emptyList() else runCatching {
            val running = am.runningAppProcesses.orEmpty()
            val pids = running.map { it.pid }.toIntArray()
            val mem: Array<android.os.Debug.MemoryInfo> = if (pids.isEmpty()) emptyArray() else am.getProcessMemoryInfo(pids)
            running.mapIndexed { i, p ->
                val m = mem.getOrNull(i)
                OwnProc(p.pid, p.processName.orEmpty(), p.importance, m?.totalPss?.toLong(),
                    m?.getMemoryStat("summary.total-swap")?.toLongOrNull(), m?.getMemoryStat("summary.graphics")?.toLongOrNull())
            }
        }.getOrDefault(emptyList())
        AndroidMem(info.availMem / 1024, info.totalMem / 1024, info.threshold / 1024, info.lowMemory, own)
    }.getOrNull()

    private fun processes(packageName: String?): List<Proc> {
        val myUid = android.os.Process.myUid()
        val procs = ArrayList<Proc>()
        val info = HashMap<Int, ProcInfo>()
        File("/proc").list()?.forEach { entry ->
            val pid = entry.toIntOrNull() ?: return@forEach
            val p = read("/proc/$pid/status")?.let { parseStatus(pid, it) } ?: return@forEach
            if (p.uid != null && p.uid != myUid) return@forEach
            val argv0 = runCatching { argv0(File("/proc/$pid/cmdline").readBytes()) }.getOrDefault("")
            val windows = runCatching { MemoryReadout.inWinePrefix(File("/proc/$pid/environ").readBytes()) }.getOrDefault(false)
            procs += p
            info[pid] = ProcInfo(argv0, windows)
        }
        return classify(procs, info, packageName).filter { it.rssKb + it.swapKb > 0 }
    }

    /** Reads everything; every source may be missing or refused, and is then left out. */
    fun snapshot(app: Context?, kind: String, atMs: Long, withAndroidProcesses: Boolean, scanDmabuf: Boolean = true): Snapshot {
        val files = runCatching { kernelFiles() }.getOrDefault(LinkedHashMap())
        val dmabuf = (if (scanDmabuf) runCatching { dmabufSysfs() }.getOrNull() else null) ?: heapDmabuf(files)
        return Snapshot(
            atMs = atMs,
            kind = kind,
            meminfo = read("/proc/meminfo")?.let { meminfoFields(it) }.orEmpty(),
            zram = runCatching { zramDevices() }.getOrDefault(emptyList()),
            dmabuf = dmabuf,
            kernelFiles = files,
            gpuKb = files[KGSL_PAGE_ALLOC]?.div(1024),
            vmstat = read("/proc/vmstat")?.let { vmstatCounters(it) }.orEmpty(),
            psi = MemoryPressure.shared.text()?.let { parsePsi(it) }.orEmpty(),
            android = app?.let { androidMem(it, withAndroidProcesses) },
            procs = runCatching { processes(app?.packageName) }.getOrDefault(emptyList()),
        )
    }

    // ---------- the session ----------

    @Volatile private var app: Context? = null
    @Volatile private var dir: File? = null
    @Volatile private var latest: Snapshot? = null
    @Volatile private var previous: Snapshot? = null
    @Volatile private var dumpsys: Pair<Long, Dumpsys>? = null
    @Volatile private var dumpsysNote: String? = null
    @Volatile private var lastDumpsysTry = 0L
    @Volatile private var requested: String? = null
    @Volatile private var lastRowAt = 0L
    @Volatile private var rows = 0
    @Volatile private var capped = false
    private val dumpsysRunning = AtomicBoolean(false)
    private val sectionAdded = AtomicBoolean(false)

    /** Session start, before the Linux side starts: the prelaunch snapshot, then `dumpsys meminfo` in the background. */
    fun begin(context: Context, sessionDir: File) {
        val a = context.applicationContext
        synchronized(this) {
            app = a; dir = sessionDir; latest = null; previous = null; dumpsys = null; dumpsysNote = null
            lastDumpsysTry = 0L; requested = null; lastRowAt = 0L; rows = 0; capped = false
        }
        if (sectionAdded.compareAndSet(false, true)) {
            com.droiddeck.launcher.telemetry.LiveStatus.addSection("memory detail") { liveNow(System.currentTimeMillis()) }
        }
        runCatching {
            val s = snapshot(a, "prelaunch", System.currentTimeMillis(), withAndroidProcesses = true)
            record(sessionDir, s, force = true)
            File(sessionDir, PRELAUNCH_FILE).writeText(prelaunchText(s))
            Log.i(TAG, "DROIDDECK_MEMORY_DETAIL file=$FILE prelaunch=$PRELAUNCH_FILE every_s=30 " +
                "available_mb=${mb(s.kb("MemAvailable"))} system_est_mb=${mb(s.estimate?.systemKb)}")
        }.onFailure { Log.w(TAG, "prelaunch snapshot: ${it.javaClass.simpleName}: ${it.message}") }
        dumpsysAsync("prelaunch")
    }

    /** A detail row at the next sampler tick, and `dumpsys meminfo` if the last try is a minute old. */
    fun requestSample(reason: String) {
        if (dir != null) requested = reason
    }

    fun end() {
        synchronized(this) { dir = null; requested = null }
    }

    /** From [MemoryLog]'s thread every 5 s; [periodic] on every [LOG_EVERY]th sample. Never throws. */
    fun tick(nowMs: Long, periodic: Boolean) {
        runCatching {
            val d = dir ?: return
            val reason = requested
            if (reason == null && (!periodic || nowMs - lastRowAt < MIN_GAP_MS)) return
            requested = null
            rows++
            val s = snapshot(app, reason ?: "sample", nowMs, withAndroidProcesses = reason != null || rows % ANDROID_EVERY == 0)
            record(d, s, force = reason != null)
            // No periodic dumpsys while a game plays: it makes system_server walk every process's PSS, which can hitch.
            if (reason != null && nowMs - lastDumpsysTry >= DUMPSYS_AFTER_LAUNCH_MS) dumpsysAsync(reason)
        }.onFailure { Log.w(TAG, "tick: ${it.javaClass.simpleName}: ${it.message}") }
    }

    @Synchronized
    private fun record(sessionDir: File, s: Snapshot, force: Boolean) {
        previous = latest
        latest = s
        lastRowAt = s.atMs
        append(sessionDir, jsonLine(s), force)
    }

    /** Appends one row within [SOFT_BYTES] / [MAX_BYTES]; past the cap one last row says so. */
    @Synchronized
    private fun append(sessionDir: File, line: String, force: Boolean) {
        val file = File(sessionDir, FILE)
        val length = file.length()
        if (length + line.length + 1 > MAX_BYTES) {
            if (!capped) {
                capped = true
                runCatching { file.appendText(Obj().num("t", System.currentTimeMillis()).str("kind", "capped").num("bytes", length).text() + "\n") }
            }
            return
        }
        if (!force && length >= SOFT_BYTES && rows % THIN_EVERY != 0) return
        runCatching { file.appendText(line + "\n") }
    }

    private fun dumpsysAsync(reason: String) {
        val a = app ?: return
        val d = dir ?: return
        lastDumpsysTry = System.currentTimeMillis()
        if (!dumpsysRunning.compareAndSet(false, true)) return
        Thread({
            try {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                val (text, why) = runDumpsys(a)
                val at = System.currentTimeMillis()
                val parsed = text?.let { parseDumpsys(it) }
                if (dir !== d) return@Thread
                if (parsed != null) {
                    dumpsys = at to parsed
                    dumpsysNote = null
                    append(d, dumpsysJsonLine(at, reason, parsed), force = true)
                    if (reason == "prelaunch" || reason == "game_launch")
                        runCatching { File(d, PRELAUNCH_FILE).appendText(dumpsysText(at, reason, parsed)) }
                } else {
                    val note = why ?: "output not understood"
                    if (dumpsysNote != note || reason == "prelaunch") {
                        append(d, skipJsonLine(at, reason, note), force = true)
                        if (reason == "prelaunch") runCatching { File(d, PRELAUNCH_FILE).appendText("\n## $DUMPSYS_COMMAND: skipped ($note)\n") }
                    }
                    dumpsysNote = note
                }
            } finally {
                dumpsysRunning.set(false)
            }
        }, "memory-dumpsys").apply { isDaemon = true; start() }
    }

    /**
     * `dumpsys meminfo -s` as the shell user over the saved Kadb pairing, only while Wireless debugging is already on.
     * (output, null) or (null, why it was skipped).
     */
    private fun runDumpsys(app: Context): Pair<String?, String?> {
        val wireless = runCatching { com.droiddeck.launcher.core.GlobalSettings.wirelessDebuggingOn(app) }.getOrNull()
        if (wireless != true) return null to "Wireless debugging off"
        if (!runCatching { com.droiddeck.launcher.core.WirelessAdbFix.hasSavedPairing(app) }.getOrDefault(false))
            return null to "no saved Wireless debugging pairing"
        return try {
            val output = com.droiddeck.launcher.core.WirelessAdbFix.withAdb(app, connectTimeoutMs = DUMPSYS_CONNECT_MS,
                socketTimeoutMs = DUMPSYS_SOCKET_MS) { adb ->
                val result = adb.shell(DUMPSYS_COMMAND)
                check(result.exitCode == 0) { "exit ${result.exitCode}" }
                result.output
            }
            output to null
        } catch (e: Exception) {
            null to "not reachable: ${e.javaClass.simpleName}"
        }
    }

    private fun liveNow(nowMs: Long): String? {
        val s = latest ?: return null
        return liveText(s, previous, dumpsys, dumpsysNote, nowMs)
    }

    /** Headline numbers for the remote "ping": the session's newest row while fresh, otherwise a quick fresh read. */
    fun headlineNow(context: Context, nowMs: Long = System.currentTimeMillis()): Map<String, Any> {
        val s = latest?.takeIf { nowMs - it.atMs in 0 until HEADLINE_FRESH_MS }
            ?: snapshot(context.applicationContext, "ping", nowMs, withAndroidProcesses = false, scanDmabuf = false)
        return headline(s, dumpsys)
    }
}
