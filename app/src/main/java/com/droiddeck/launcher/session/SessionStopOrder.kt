package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.SessionPart

object SessionStopOrder {
    /**
     * [started] in start order -> the parts to stop at once and the ones to stop after the guest has
     * exited ([SessionPart.stopsAfterGuest]), each list newest first.
     */
    fun split(started: List<SessionPart>): Pair<List<SessionPart>, List<SessionPart>> =
        started.reversed().partition { !it.stopsAfterGuest() }
}
