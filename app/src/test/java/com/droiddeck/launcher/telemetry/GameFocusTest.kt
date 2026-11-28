package com.droiddeck.launcher.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class GameFocusTest {
    /** The focus records of Cedar's 2026-11-13T23:57:47Z session, in order: StS2 drew through gamescope's own label. */
    @Test fun aGameDrawingUnderTheCompositorsLabelIsAGame() {
        val focus = GameFocus()
        val seen = listOf(
            Triple("GAMESCOPE", 0, false),
            Triple("GAMESCOPE", 0, false),
            Triple("GAMESCOPE", 8862, true),
            Triple("GAMESCOPE", 8862, false),
            Triple("GAMESCOPE", 10020, false),
        ).map { (engine, pid, steam) -> focus.isGame(engine, pid, steam) }
        assertEquals(listOf(false, false, false, false, true), seen)
    }

    @Test fun anEngineLabelStillCountsAndNoneNeverDoes() {
        val focus = GameFocus()
        assertEquals(true, focus.isGame("DXVK", 42, false))
        assertEquals(false, focus.isGame("DXVK", 42, true))
        assertEquals(false, focus.isGame("none", 42, false))
        assertEquals(false, focus.isGame("", 42, false))
        assertEquals(false, focus.isGame(null, 42, false))
    }
}
