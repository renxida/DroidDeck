package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.droiddeck.launcher.R

/**
 * Settings > Advanced > Experiments. Each id is the perf/ branch the change came from and reaches the
 * guest as DROIDDECK_EXP_<ID>=1. A fresh install starts with [defaults]; all off is main. Game-level parts follow the saved
 * switches at each game launch; Steam-side and app-side parts follow the switches the session started with.
 */
object Experiments {
    private const val TAG = "Experiments"

    const val GODOT_RENDER = "godot-render"
    const val WARMUP = "warmup"
    const val LAUNCH = "all"
    const val COMPAT_QUIET = "compat-quiet"
    const val MEMORY = "memory"
    const val ADPF_ALL = "adpf-all"
    const val TWO_FRAMES = "two-frames"
    const val LAYER_FENCE = "layer-fence"
    const val SCHED = "sched"
    const val ONE_LIMITER = "one-limiter"
    const val NATIVE_HZ = "native-hz"
    const val QUICK_TAP = "quick-tap"
    const val FEX_CODEGEN = "fex-codegen"
    const val FEX_MEMCPY_TSO = "fex-memcpy-tso"
    const val FEX_VECTOR_TSO = "fex-vector-tso"
    const val FEX_TSO = "fex-tso"
    const val FEX_FAST = "fex-fast"
    const val DOTNET_TRACE = "dotnet-trace"
    const val DOTNET_GEN0 = "dotnet-gen0"
    const val DOTNET_GC_STOCK = "dotnet-gc-stock"
    /** Games use Kestrel's own FEX build (tools/fex/arm64ec) through the Auto component line instead of DD-FEXCore. */
    const val FEX_KESTREL = "fex-kestrel"
    /** FEX keeps its per-thread counters (FEX_PROFILESTATS=1) and the app logs them to fex.csv. */
    const val FEX_STATS = "fex-stats"
    const val WAYLAND_PRESENT = "wayland-present"
    const val VK_COUNT = "vk-count"
    const val NATIVE_INVENTORY = "native-inventory"
    const val FREE_MEMORY = "free-memory"
    const val HYBRIS_INDEX = "hybris-index"
    /** FEX's per-thread block lookup cache at a fixed size from the start: 4 MB with Kestrel's FEX, 16 MB otherwise. */
    const val FEX_L1 = "fex-l1"
    const val HYBRIS_INDEX_SESSION = "hybris-index-session"
    /** Godot C# games run on Kestrel's own linux-arm64 engine (droiddeck-native-godot), skipping Wine and FEX; Proton if it fails to start. */
    const val NATIVE_ENGINE = "native-engine"
    /** PowerVR: 64-bit Direct3D games use DXVK built as ARM64EC (native) instead of x86-64 (translated by FEX). */
    const val DXVK_ARM64EC = "dxvk-arm64ec"

    class Switch(val id: String, @StringRes val label: Int)

    val toggles = listOf(
        Switch(GODOT_RENDER, R.string.exp_godot_render),
        Switch(WARMUP, R.string.exp_warmup),
        Switch(LAUNCH, R.string.exp_launch),
        Switch(COMPAT_QUIET, R.string.exp_compat_quiet),
        Switch(MEMORY, R.string.exp_memory),
        Switch(ADPF_ALL, R.string.exp_adpf_all),
        Switch(SCHED, R.string.exp_sched),
        Switch(ONE_LIMITER, R.string.exp_one_limiter),
        Switch(NATIVE_HZ, R.string.exp_native_hz),
        Switch(QUICK_TAP, R.string.exp_quick_tap),
        Switch(FEX_CODEGEN, R.string.exp_fex_codegen),
        Switch(DOTNET_TRACE, R.string.exp_dotnet_trace),
        Switch(DOTNET_GEN0, R.string.exp_dotnet_gen0),
        Switch(DOTNET_GC_STOCK, R.string.exp_dotnet_gc_stock),
        Switch(FEX_KESTREL, R.string.exp_fex_kestrel),
        Switch(FEX_STATS, R.string.exp_fex_stats),
        Switch(WAYLAND_PRESENT, R.string.exp_wayland_present),
        Switch(VK_COUNT, R.string.exp_vk_count),
        Switch(NATIVE_INVENTORY, R.string.exp_native_inventory),
        Switch(FREE_MEMORY, R.string.exp_free_memory),
        Switch(HYBRIS_INDEX, R.string.exp_hybris_index),
        Switch(FEX_L1, R.string.exp_fex_l1),
        Switch(HYBRIS_INDEX_SESSION, R.string.exp_hybris_index_session),
        Switch(NATIVE_ENGINE, R.string.exp_native_engine),
        Switch(DXVK_ARM64EC, R.string.exp_dxvk_arm64ec),
    )

    /** At most one of each group is on. */
    val present = listOf(Switch(TWO_FRAMES, R.string.exp_two_frames), Switch(LAYER_FENCE, R.string.exp_layer_fence))
    val fexOrder = listOf(
        Switch(FEX_MEMCPY_TSO, R.string.exp_fex_memcpy_tso),
        Switch(FEX_VECTOR_TSO, R.string.exp_fex_vector_tso),
        Switch(FEX_TSO, R.string.exp_fex_tso),
        Switch(FEX_FAST, R.string.exp_fex_fast),
    )
    private val groups = listOf(present.map { it.id }, fexOrder.map { it.id })

    /** The order the guest's droiddeck-game-env lists them in. */
    val ids = listOf(GODOT_RENDER, WARMUP, LAUNCH, COMPAT_QUIET, MEMORY, ADPF_ALL, TWO_FRAMES, LAYER_FENCE,
        SCHED, ONE_LIMITER, NATIVE_HZ, QUICK_TAP, FEX_CODEGEN, FEX_MEMCPY_TSO, FEX_VECTOR_TSO, FEX_TSO, FEX_FAST,
        DOTNET_TRACE, DOTNET_GEN0, DOTNET_GC_STOCK, FEX_KESTREL, FEX_STATS,
        WAYLAND_PRESENT, VK_COUNT,
        NATIVE_INVENTORY, FREE_MEMORY, HYBRIS_INDEX, FEX_L1, HYBRIS_INDEX_SESSION, NATIVE_ENGINE,
        DXVK_ARM64EC)

    /** A fresh install and "Turn everything on": all but layer-fence (two-frames instead) and the frame cap. */
    val defaults = setOf(GODOT_RENDER, WARMUP, LAUNCH, COMPAT_QUIET, MEMORY, ADPF_ALL, TWO_FRAMES, ONE_LIMITER, NATIVE_HZ,
        QUICK_TAP, FEX_CODEGEN, FEX_FAST, FEX_STATS, FREE_MEMORY)

    fun envName(id: String) = "DROIDDECK_EXP_" + id.uppercase().replace('-', '_')

    fun env(on: Set<String>): Map<String, String> = ids.filter { it in on }.associate { envName(it) to "1" }

    fun line(on: Set<String>): String = ids.filter { it in on }.joinToString(",").ifEmpty { "none" }

    /** [on] with [id] switched; turning one of a group on turns the rest of its group off. */
    fun with(on: Set<String>, id: String, value: Boolean): Set<String> {
        require(id in ids)
        if (!value) return on - id
        return on - groups.firstOrNull { id in it }.orEmpty().toSet() + id
    }

    fun presentMode(on: Set<String>): Int = when {
        TWO_FRAMES in on -> 1
        LAYER_FENCE in on -> 2
        else -> 0
    }

    private fun prefs(context: Context) = context.getSharedPreferences("experiments", Context.MODE_PRIVATE)

    fun enabled(context: Context): Set<String> =
        (prefs(context).getStringSet("on", null) ?: defaults).filterTo(LinkedHashSet()) { it in ids }

    fun set(context: Context, on: Set<String>) {
        val kept = on.filterTo(LinkedHashSet()) { it in ids }
        prefs(context).edit().putStringSet("on", kept).apply()
        Log.i(TAG, "saved: ${line(kept)}" + (session?.let { "; this session started with: ${line(it)}" } ?: ""))
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { Log.e(TAG, "Could not update game environment", it) }
    }

    fun setOne(context: Context, id: String, value: Boolean) = set(context, with(enabled(context), id, value))

    /** The switches the running session started with. */
    @Volatile var session: Set<String>? = null
        private set

    fun beginSession(context: Context): Set<String> = enabled(context).also { session = it }

    fun endSession() { session = null }

    /** For the app-side parts: the session's switches while one runs, the saved ones otherwise. */
    fun on(context: Context, id: String): Boolean = id in (session ?: enabled(context))
}
