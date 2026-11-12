package com.droiddeck.launcher.ui

internal enum class SessionDrawerPage(val firstControl: String) {
    CONTROLLER("input-profile"),
    SECOND_SCREEN("second-screen:custom-controls"),
    DISPLAY("fg"),
    HUD("hud"),
    EFFECTS("look"),
    GAMES("anisotropy"),
    AGENT("agent-input"),
    SESSION("suspend"),
}

internal fun sessionDrawerPages(
    hasSecondScreen: Boolean, hasAgent: Boolean = com.droiddeck.launcher.agent.AgentHost.installed,
): List<SessionDrawerPage> =
    SessionDrawerPage.entries.filter {
        (hasSecondScreen || it != SessionDrawerPage.SECOND_SCREEN) && (hasAgent || it != SessionDrawerPage.AGENT)
    }

internal fun List<SessionDrawerPage>.step(current: SessionDrawerPage, direction: Int): SessionDrawerPage {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + direction, size)]
}
