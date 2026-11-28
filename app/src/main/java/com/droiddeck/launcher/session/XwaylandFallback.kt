package com.droiddeck.launcher.session

/**
 * Whether a session starts Xwayland without glamor (XWAYLAND_NO_GLAMOR=1) because the session before it
 * logged an Xwayland crash.
 *
 * Without glamor Xwayland offers no DRI3. Only the Venus route presents without DRI3 (software WSI, and
 * tools/linuxfs/preload/venus.c keeps Zink's kopper for Steam's web helper); on the Mali bridge and on an
 * open driver pair Steam's web helper gets no GL context and restarts for good, so the session never draws
 * (seen on PanVK, docs/research/mali-10a-gpu-1.md section 3).
 */
object XwaylandFallback {
    const val ENV = "XWAYLAND_NO_GLAMOR=1"

    /** Written to session.log when the app begins stopping a session; a crash after it is the teardown's. */
    const val STOP_MARK = "== session: stopping"

    enum class Route(val label: String) {
        BRIDGE("the Mali bridge"),
        OPEN_PAIR("an open driver pair"),
        VENUS("Venus"),
        OTHER("this route"),
    }

    enum class Decision { NONE, APPLY, NOT_ON_ROUTE, UNDRAWN }

    fun stopLine(status: Int) = "$STOP_MARK (status $status)"

    /** An Xwayland segmentation fault in a session log, before the app began stopping that session. */
    fun crashedBeforeStop(lines: Sequence<String>): Boolean {
        var fault = false
        var xwayland = false
        for (line in lines) {
            if (line.startsWith(STOP_MARK)) break
            if (line.contains("(EE) Segmentation fault")) fault = true
            if (line.contains("X11 I/O error") || line.contains("Xwayland", ignoreCase = true)) xwayland = true
            if (fault && xwayland) return true
        }
        return false
    }

    /**
     * [undrawn]: the last session that started without glamor ended before its first frame; glamor comes
     * back for this start whatever the log says, so two sessions in a row cannot both fail on the fallback.
     */
    fun decide(adreno: Boolean, route: Route, crashed: Boolean, undrawn: Boolean): Decision = when {
        adreno -> Decision.NONE
        undrawn -> Decision.UNDRAWN
        !crashed -> Decision.NONE
        route == Route.BRIDGE || route == Route.OPEN_PAIR -> Decision.NOT_ON_ROUTE
        else -> Decision.APPLY
    }

    fun line(decision: Decision, route: Route): String? = when (decision) {
        Decision.NONE -> null
        Decision.APPLY -> "xwayland: the last session's Xwayland crashed; glamor off for this one ($ENV)"
        Decision.NOT_ON_ROUTE -> "xwayland: the last session's Xwayland crashed; glamor stays on, because on ${route.label} Steam does not start without it"
        Decision.UNDRAWN -> "xwayland: the last session started without glamor ($ENV) and drew no frame; glamor is back on for this one"
    }

    /** Runs once at the session's first frame when this session started without glamor. */
    @Volatile var onFirstFrame: (() -> Unit)? = null

    fun firstFrame() {
        val drew = onFirstFrame ?: return
        onFirstFrame = null
        drew()
    }
}
