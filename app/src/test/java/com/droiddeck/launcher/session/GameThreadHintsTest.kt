package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameThreadHintsTest {
    @Test fun `a thread joins above 60 percent and leaves under 15`() {
        assertEquals(emptySet<Int>(), GameThreadHints.pick(emptySet(), mapOf(1 to 0.55)))
        assertEquals(setOf(1), GameThreadHints.pick(emptySet(), mapOf(1 to 0.68)))
        assertEquals(setOf(1), GameThreadHints.pick(setOf(1), mapOf(1 to 0.25)))
        assertEquals(emptySet<Int>(), GameThreadHints.pick(setOf(1), mapOf(1 to 0.1)))
    }

    @Test fun `at most two threads, the boosted ones kept before busier newcomers`() {
        val busy = mapOf(1 to 0.2, 2 to 0.9, 3 to 0.8, 4 to 0.7, 5 to 0.95)
        assertEquals(setOf(1, 5), GameThreadHints.pick(setOf(1), busy))
    }

    @Test fun `a thread gone from proc is dropped`() {
        assertEquals(setOf(2), GameThreadHints.pick(setOf(1, 2), mapOf(2 to 0.7)))
    }

    @Test fun `a game at the panel's rate or a fraction of it holds pace`() {
        assertTrue(GameThreadHints.holdsPace(119.0, 120.0))
        assertTrue(GameThreadHints.holdsPace(60.5, 120.0))
        assertTrue(GameThreadHints.holdsPace(30.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(92.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(105.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(3.0, 120.0))
    }

    @Test fun `a frame cap sets the target to one frame and holds pace at the cap`() {
        assertEquals(16_666_666L, GameThreadHints.targetFor(60))
        assertEquals(25_000_000L, GameThreadHints.targetFor(40))
        assertEquals(10_000_000L, GameThreadHints.targetFor(0))
        assertEquals("target 16.7 ms, reporting 25.0 ms every 50 ms", GameThreadHints.targetText(GameThreadHints.targetFor(60)))
        assertEquals("target 25 ms, reporting 37.5 ms every 50 ms", GameThreadHints.targetText(GameThreadHints.targetFor(40)))
        assertTrue(GameThreadHints.holdsPace(44.5, 120.0, cap = 45))
        assertFalse(GameThreadHints.holdsPace(44.5, 120.0))
    }

    @Test fun `the log names the hinted threads and the target duration`() {
        assertEquals(
            "boosting SlayTheSpire2.e/1234 82% (target 10 ms, reporting 15 ms every 50 ms) (game 58 fps)",
            GameThreadHints.boostLine(listOf("SlayTheSpire2.e/1234 82%"), sessionMissing = false, fps = 58, paced = false))
        assertEquals(
            "no session for a/1 70% (target 10 ms, reporting 15 ms every 50 ms) (game 30 fps, holding pace)",
            GameThreadHints.boostLine(listOf("a/1 70%"), sessionMissing = true, fps = 30, paced = true))
        assertEquals("boosting nothing (game 60 fps)", GameThreadHints.boostLine(emptyList(), sessionMissing = true, fps = 60, paced = false))
    }
}
