package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport

/**
 * Experiment [Experiments.BRIDGE_DEFAULT]: a Mali that can use the Mali bridge draws through it unless the
 * user set the route by hand, and the installed open driver pair is what the bridge falls back to. Without
 * the experiment Auto treats the bridge as a stand-in and switches it off once a pair is installed
 * (DriverMenus.endMaliFallback), which is how a fresh install ends up on PanVK.
 *
 * The bridge's self-test runs in the guest at every session start (droiddeck-session). A session whose
 * self-test fails already runs on the pair; here that result, or a bridge session that drew nothing, moves
 * an automatic choice to the pair for the sessions after it.
 */
object BridgeDefault {
    private const val TAG = "BridgeDefault"
    const val USER = "user"
    const val AUTO = "auto"
    const val FAILED_DRAW = "draw"
    const val FAILED_MEMORY = "memory"
    const val FAILED_NO_FRAME = "no-frame"
    /** Steam's first frame comes about 10 s into a bridge session (Pixel 10a). */
    const val NO_FRAME_MS = 60_000L

    /**
     * [bridge] is null when vkBridge was never stored (on for a Mali by default), [by] is who set the
     * route since that was recorded ("" before), [standIn] is the route Auto marked as its own stand-in.
     */
    data class Stored(
        val bridge: Boolean?,
        val by: String,
        val venus: Boolean,
        val standIn: String,
        val driversAuto: Boolean,
        val failed: String,
    )

    enum class Outcome { NOT_HERE, FAILED_HERE, USER, ALREADY, SWITCH }

    data class Decision(val outcome: Outcome, val venusOff: Boolean = false)

    /**
     * Whether the stored route was set by hand. Builds that did not record it leave three cases: a route
     * that is on without Auto's stand-in mark came from its button; the bridge stored off under Manual or
     * Mix drivers came from its button or from a pair picked by hand; the bridge stored off under Auto
     * drivers is taken as Auto's (endMaliFallback wrote it on every fresh install once the pair arrived),
     * though the button leaves the same values.
     */
    fun explicit(s: Stored): Boolean = when {
        s.by == USER -> true
        s.by == AUTO -> false
        s.venus -> s.standIn != "venus"
        s.bridge == true -> s.standIn != "bridge"
        s.bridge == false -> !s.driversAuto
        else -> false
    }

    fun decide(on: Boolean, bridgeUsable: Boolean, s: Stored): Decision = when {
        !on || !bridgeUsable -> Decision(Outcome.NOT_HERE)
        s.failed.isNotEmpty() -> Decision(Outcome.FAILED_HERE)
        explicit(s) -> Decision(Outcome.USER)
        s.bridge != false && !s.venus -> Decision(Outcome.ALREADY)
        else -> Decision(Outcome.SWITCH, venusOff = s.venus)
    }

    /**
     * A bridge session that showed nothing: no first frame while its screen was on a display for
     * [NO_FRAME_MS], and not while Steam's client installed.
     */
    fun drewNothing(firstFrame: Boolean, shownMs: Long, clientInstalled: Boolean): Boolean =
        !firstFrame && !clientInstalled && shownMs >= NO_FRAME_MS

    internal var mali: () -> Boolean = { DeviceSupport.mali() }
    internal var bridgeUsable: () -> Boolean = { DeviceSupport.bridgeUsable() }
    internal var pairInstalled: (Context) -> Boolean = { context ->
        val linux = SessionPrefs.linuxDriver(context)
        linux.isNotEmpty() && com.droiddeck.launcher.gpu.LinuxVulkanDriverManager(context).isInstalled(linux)
    }

    fun on(context: Context): Boolean = Experiments.on(context, Experiments.BRIDGE_DEFAULT)

    /** The bridge is the route Auto keeps here: the experiment is on, the phone can use it, and it has not failed. */
    fun keepsBridge(context: Context): Boolean =
        mali() && on(context) && bridgeUsable() && SessionPrefs.bridgeAutoFailed(context).isEmpty()

    fun stored(context: Context) = Stored(
        bridge = SessionPrefs.vkBridgeStored(context),
        by = SessionPrefs.maliRouteBy(context),
        venus = SessionPrefs.venus(context),
        standIn = SessionPrefs.maliFallbackByAuto(context),
        driversAuto = SessionPrefs.gpuDriverMode(context) == SessionPrefs.GPU_DRIVERS_AUTO,
        failed = SessionPrefs.bridgeAutoFailed(context),
    )

    private fun describe(s: Stored) = "bridge ${s.bridge ?: "unset"}, venus ${s.venus}" +
        (if (s.standIn.isNotEmpty()) ", Auto's stand-in ${s.standIn}" else "") + ", drivers ${if (s.driversAuto) "Auto" else "by hand"}"

    /**
     * Brings the stored route in line with the experiment. Call with no session running, or at the very
     * start of one. Returns true when the route changed. [where] names the caller in the log line.
     */
    fun apply(context: Context, where: String): Boolean {
        if (!mali()) return false
        val s = stored(context)
        if (!on(context)) return undo(context, s, where)
        return when (decide(true, bridgeUsable(), s).outcome) {
            Outcome.NOT_HERE, Outcome.FAILED_HERE -> false
            Outcome.USER -> {
                if (s.by != USER) {
                    SessionPrefs.setMaliRouteBy(context, USER)
                    Log.i(TAG, "route ($where): kept as set by hand (${describe(s)}); bridge-default leaves it alone")
                }
                false
            }
            Outcome.ALREADY -> {
                if (s.by != AUTO) {
                    SessionPrefs.setMaliRouteBy(context, AUTO)
                    Log.i(TAG, "route ($where): the Mali bridge is already the route (${describe(s)}); bridge-default keeps it when a driver pair is installed")
                }
                false
            }
            Outcome.SWITCH -> {
                SessionPrefs.setBridgeDefaultUndo(context, listOf(s.bridge?.toString() ?: "unset", s.venus.toString(), s.standIn).joinToString("|"))
                if (s.venus) SessionPrefs.setVenus(context, false)
                SessionPrefs.setVkBridge(context, true)
                SessionPrefs.setMaliFallbackByAuto(context, "")
                SessionPrefs.setMaliRouteBy(context, AUTO)
                Log.i(TAG, "route ($where): the Mali bridge is this phone's route now (was ${describe(s)}, not set by hand); " +
                    "the open driver pair stays installed as its fallback")
                true
            }
        }
    }

    /** The experiment is off: a route it switched, and nobody changed since, goes back to what it was. */
    private fun undo(context: Context, s: Stored, where: String): Boolean {
        val saved = SessionPrefs.bridgeDefaultUndo(context).split("|").takeIf { it.size == 3 } ?: return false
        SessionPrefs.setBridgeDefaultUndo(context, "")
        if (s.by != AUTO || s.failed.isNotEmpty()) return false
        SessionPrefs.restoreVkBridge(context, saved[0].toBooleanStrictOrNull())
        SessionPrefs.setVenus(context, saved[1] == "true")
        SessionPrefs.setMaliFallbackByAuto(context, saved[2])
        SessionPrefs.setMaliRouteBy(context, "")
        Log.i(TAG, "route ($where): bridge-default is off; back to bridge ${saved[0]}, venus ${saved[1]}")
        return true
    }

    /** A route button was pressed: the choice is the user's, and an earlier automatic fallback no longer applies. */
    fun setByHand(context: Context) {
        SessionPrefs.setMaliRouteBy(context, USER)
        SessionPrefs.setBridgeAutoFailed(context, "")
        SessionPrefs.setBridgeDefaultUndo(context, "")
    }

    private fun failure(reason: String) = when (reason) {
        FAILED_MEMORY -> "found no way to share memory with the phone's driver (self-test)"
        FAILED_NO_FRAME -> "drew no frame in ${NO_FRAME_MS / 1000} s on screen"
        else -> "failed its self-test (images or draws)"
    }

    /**
     * A session on the automatically chosen bridge failed its self-test ([FAILED_DRAW], [FAILED_MEMORY]) or
     * drew nothing ([FAILED_NO_FRAME]): later sessions use the open driver pair, if one is installed.
     */
    fun bridgeFailed(context: Context, reason: String): Boolean {
        if (!mali() || !on(context)) return false
        if (SessionPrefs.maliRouteBy(context) != AUTO || !SessionPrefs.vkBridge(context)) return false
        if (!pairInstalled(context)) {
            Log.w(TAG, "route: the Mali bridge ${failure(reason)}, and no open driver pair is installed to fall back on; the bridge stays the route")
            return false
        }
        SessionPrefs.setVkBridge(context, false)
        SessionPrefs.setBridgeAutoFailed(context, reason)
        Log.w(TAG, "route: the Mali bridge ${failure(reason)}; sessions use the open driver pair from now on " +
            "(Settings > Advanced says so; its \"Mali bridge\" button turns the bridge back on)")
        return true
    }
}
