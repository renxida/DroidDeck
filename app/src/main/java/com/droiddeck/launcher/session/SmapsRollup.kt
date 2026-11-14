package com.droiddeck.launcher.session

import java.util.Locale

/**
 * Experiment fex-smaps: where a game process's memory sits, by owner. Once a minute the memory log reads the biggest
 * game process's /proc/<pid>/smaps and sums each mapping's Rss, Pss, Swap and Anonymous into a category named from the
 * mapping's path or VMA name. FEX names its own anonymous memory (FEXMemJIT, FEXMem_Lookup, FEXMem_Lookup_L1, ...),
 * so FEX's share is exact wherever the kernel keeps anon VMA names (Android does). Kestrel's FEX can also name Windows
 * private memory ([anon:win:...]); without that, Windows private memory (the .NET GC heap, Wine heaps and everything
 * Godot mallocs) stays one bucket, anon.
 */
object SmapsRollup {
    const val FILE = "smaps.csv"
    const val HEADER = "t_wall_ms,pid,process,kind,category,owner,vmas,size_mb,rss_mb,pss_mb,swap_mb,anon_mb"
    const val TOP = 15

    data class Vma(val start: Long, val end: Long, val perms: String, val path: String,
                   val rssKb: Long, val pssKb: Long, val swapKb: Long, val anonKb: Long) {
        val sizeKb get() = (end - start) / 1024
    }

    class Sum(var vmas: Int = 0, var sizeKb: Long = 0, var rssKb: Long = 0, var pssKb: Long = 0, var swapKb: Long = 0, var anonKb: Long = 0) {
        fun add(v: Vma) { vmas++; sizeKb += v.sizeKb; rssKb += v.rssKb; pssKb += v.pssKb; swapKb += v.swapKb; anonKb += v.anonKb }
    }

    private val HEADER_LINE = Regex("""^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+\S+\s+\S+\s+\d+\s*(.*)$""")
    private val FIELD = Regex("""^(\w+):\s+(\d+) kB""")

    fun parse(text: String): List<Vma> {
        val out = ArrayList<Vma>()
        var head: MatchResult? = null
        val fields = HashMap<String, Long>()
        fun flush() {
            val h = head ?: return
            out += Vma(h.groupValues[1].toULong(16).toLong(), h.groupValues[2].toULong(16).toLong(), h.groupValues[3], h.groupValues[4].trim(),
                fields["Rss"] ?: 0, fields["Pss"] ?: 0, fields["Swap"] ?: 0, fields["Anonymous"] ?: 0)
        }
        for (line in text.lineSequence()) {
            val h = HEADER_LINE.find(line)
            if (h != null) { flush(); head = h; fields.clear(); continue }
            FIELD.find(line)?.let { fields[it.groupValues[1]] = it.groupValues[2].toLong() }
        }
        flush()
        return out
    }

    private val DOTNET = Regex("""(?i)(^|/)(coreclr|clrjit|clrgc|hostfxr|hostpolicy|mscordaccore|mscordbi|system\.[^/]*|microsoft\.[^/]*|netstandard|mscorlib|godotsharp[^/]*)\.dll$""")
    private val GPU_SO = Regex("""(?i)(vulkan|libgles|libegl|hybris|adreno|mali|turnip|freedreno|kgsl|gralloc|libdrm)""")

    /** (category, owner) for one mapping. Owners are best effort: they say who most likely asked for the memory. */
    fun classify(path: String): Pair<String, String> {
        val p = path.trim()
        val l = p.lowercase(Locale.US)
        when {
            p.startsWith("[anon:FEXMem") -> {
                val name = p.removePrefix("[anon:FEXMem").removeSuffix("]").trimStart('_').lowercase(Locale.US)
                return when (name) {
                    "jit" -> "fex-code" to "FEX: translated code buffers"
                    "lookup" -> "fex-lookup-l2" to "FEX: per-thread block lookup pages"
                    "lookup_l1" -> "fex-lookup-l1" to "FEX: per-thread L1 lookup table"
                    "" -> "fex-other" to "FEX"
                    else -> "fex-${name.replace('_', '-')}" to "FEX"
                }
            }
            p.startsWith("[anon:FEXAllocator") -> return "fex-malloc" to "FEX: its own malloc (rpmalloc: IR, block maps, compiler)"
            p.startsWith("[anon:win:") -> {
                val name = p.removePrefix("[anon:win:").removeSuffix("]").substringBefore(':')
                return "win-$name" to when (name) {
                    "gc" -> ".NET GC heap (largest reservation)"
                    "exec" -> "executable private memory: .NET JIT code and stubs"
                    "heap" -> "Windows heaps (Wine RtlHeap: malloc from Godot, .NET runtime, Wine, FEX's PE side)"
                    "stack" -> "thread stacks"
                    "big" -> "other reservations of 256 MB or more (.NET loader heaps, engine pools)"
                    else -> "Windows private memory"
                }
            }
            p == "[heap]" -> return "linux-brk" to "Linux side malloc (Wine unix side, GPU driver, libc)"
            p == "[stack]" || p.startsWith("[anon:stack") || p.startsWith("[stack:") -> return "linux-stack" to "Linux thread stacks"
            p.startsWith("[anon:") -> return "anon-named" to p.removePrefix("[anon:").removeSuffix("]").take(40)
            p.startsWith("[") -> return "kernel" to p
            "/dev/kgsl" in l || "/dev/mali" in l || "/dev/dri" in l -> return "gpu-device" to "GPU driver mappings"
            "dmabuf" in l || l.startsWith("/dev/dma_heap") -> return "gpu-dmabuf" to "GPU and display buffers"
            l.startsWith("anon_inode:") -> return "anon-inode" to p.removePrefix("anon_inode:")
            l.startsWith("/memfd:") || l.startsWith("/dev/ashmem") -> return "shared-memfd" to p.substringBefore(" (deleted)").take(40)
            l.startsWith("/dev/shm/") -> return "shared-shm" to "FEX stats and other /dev/shm"
            p.isEmpty() -> return "anon" to "Windows private memory and Linux-side mmap, unlabeled (.NET GC, Wine heaps, Godot)"
            l.endsWith(".exe") -> return "image-exe" to "game executable (Godot engine)"
            l.endsWith(".dll") || l.endsWith(".drv") || l.endsWith(".sys") ->
                return when {
                    "/lib/wine/" in l || "/windows/system32/" in l || "/windows/syswow64/" in l ->
                        if ("fex" in l.substringAfterLast('/')) "fex-image" to "FEX DLL" else "image-wine" to "Wine DLLs"
                    DOTNET.containsMatchIn(l) -> "image-dotnet" to ".NET runtime and assemblies"
                    else -> "image-game" to "game and other DLLs"
                }
            ".so" in l.substringAfterLast('/') ->
                return when {
                    "fex" in l.substringAfterLast('/') -> "fex-so" to "FEX unixlib"
                    "/lib/wine/" in l -> "so-wine" to "Wine unix side"
                    GPU_SO.containsMatchIn(l.substringAfterLast('/')) -> "so-gpu" to "GPU driver libraries"
                    else -> "so-other" to "Linux libraries"
                }
            l.endsWith(".pck") -> return "file-pck" to "Godot game data"
            else -> return "file-other" to "other mapped files"
        }
    }

    fun rollup(vmas: List<Vma>): Map<Pair<String, String>, Sum> {
        val out = LinkedHashMap<Pair<String, String>, Sum>()
        for (v in vmas) out.getOrPut(classify(v.path)) { Sum() }.add(v)
        return out
    }

    private fun mb(kb: Long) = String.format(Locale.US, "%.1f", kb / 1024.0)
    private fun row(at: Long, pid: Int, process: String, kind: String, category: String, owner: String, s: Sum) =
        "$at,$pid,${process.replace(',', '_')},$kind,${category.replace(',', '_')},${owner.replace(',', ';')},${s.vmas}," +
            "${mb(s.sizeKb)},${mb(s.rssKb)},${mb(s.pssKb)},${mb(s.swapKb)},${mb(s.anonKb)}"

    /**
     * smaps.csv rows for one snapshot: a total, one row per category (largest resident + swapped first), then the
     * [TOP] largest single mappings (kind top, category = its own address range and name) and a meta row with the read time.
     */
    fun lines(at: Long, pid: Int, process: String, smaps: String, readMs: Long): List<String> {
        val vmas = parse(smaps)
        val total = Sum().also { s -> vmas.forEach(s::add) }
        val out = ArrayList<String>()
        out += row(at, pid, process, "total", "total", "all mappings", total)
        rollup(vmas).entries.sortedByDescending { it.value.rssKb + it.value.swapKb }
            .forEach { (k, s) -> out += row(at, pid, process, "category", k.first, k.second, s) }
        vmas.sortedByDescending { it.rssKb + it.swapKb }.take(TOP).forEach { v ->
            val (cat, _) = classify(v.path)
            out += row(at, pid, process, "top", "%x-%x".format(v.start, v.end), "$cat ${v.perms} ${v.path}".trim().take(80), Sum().also { it.add(v) })
        }
        out += "$at,$pid,${process.replace(',', '_')},meta,read_ms,$readMs,${vmas.size},,,,,"
        return out
    }
}
