package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePacingTest {
    private val sts2 = "DROIDDECK_PACING app_id=2868840 cap=60 adpf=1 display=1 governor=1"

    @Test fun `reads the cap and the three switches`() {
        assertEquals(GamePacing.Plan("2868840", 60, adpf = true, display = true, governor = true), GamePacing.parse(sts2))
        assertEquals(GamePacing.Plan("7", 40, adpf = false, display = true, governor = false),
            GamePacing.parse("DROIDDECK_PACING app_id=7 cap=40 adpf=0 display=1 governor=0"))
        assertNull(GamePacing.parse("DROIDDECK_PACING app_id=7 cap=0 adpf=1"))
        assertNull(GamePacing.parse("DROIDDECK_PACING cap=60"))
        assertNull(GamePacing.parse("droiddeck-game-env: frame cap 60 fps"))
    }

    @Test fun `a cap holds from the game's exec to its exit`() {
        val tracker = GamePacing.Tracker()
        assertFalse(tracker.line(sts2))
        assertNull(tracker.plan)
        assertTrue(tracker.line("DROIDDECK_TIMELINE event=game_exec app_id=2868840 wall_ms=1"))
        assertEquals(60, tracker.plan?.cap)
        assertFalse(tracker.line("DROIDDECK_TIMELINE event=game_explorer_failed app_id=2868840 attempt=1 action=relaunch run_ms=1 wall_ms=2"))
        assertFalse(tracker.line("DROIDDECK_TIMELINE event=game_relaunch app_id=2868840 attempt=2 reason=explorer_failed ended_by=wineserver wall_ms=2"))
        assertEquals(60, tracker.plan?.cap)
        assertFalse(tracker.line("DROIDDECK_TIMELINE event=game_exit app_id=99 status=0 signal=0 run_ms=1 wall_ms=2"))
        assertTrue(tracker.line("DROIDDECK_TIMELINE event=game_exit app_id=2868840 status=0 signal=0 run_ms=1 wall_ms=2"))
        assertNull(tracker.plan)
    }

    @Test fun `another game's launch ends the cap and an uncapped launch gets none`() {
        val tracker = GamePacing.Tracker()
        tracker.line(sts2)
        tracker.line("DROIDDECK_TIMELINE event=game_exec app_id=2868840 wall_ms=1")
        assertTrue(tracker.line("DROIDDECK_TIMELINE event=game_exec app_id=99 wall_ms=5"))
        assertNull(tracker.plan)
        tracker.line(sts2)
        assertFalse(tracker.line("DROIDDECK_TIMELINE event=game_exec app_id=99 wall_ms=6"))
        assertNull(tracker.plan)
    }

    @Test fun `the ladder starts at the game's cap`() {
        assertEquals(listOf(60, 40, 30), GamePacing.Governor(60).ladder)
        assertEquals(listOf(90, 60, 40, 30), GamePacing.Governor(90).ladder)
        assertEquals(listOf(45, 40, 30), GamePacing.Governor(45).ladder)
        assertEquals(listOf(30), GamePacing.Governor(30).ladder)
    }

    @Test fun `steps down one rung, waits for it to settle before the next, and comes back up after a cool spell`() {
        val g = GamePacing.Governor(60)
        assertNull(g.sample(0.96f, 1, 0))
        assertNull(g.sample(0.96f, 1, 9_000))
        assertEquals(40, g.sample(0.96f, 1, 10_000))
        assertNull(g.sample(0.97f, 1, 20_000))
        assertNull(g.sample(0.97f, 1, 69_000))
        assertEquals(30, g.sample(0.97f, 1, 70_000))
        assertNull(g.sample(0.99f, 1, 200_000))
        assertNull(g.sample(0.85f, 1, 210_000))
        assertNull(g.sample(0.75f, 1, 220_000))
        assertNull(g.sample(0.75f, 1, 249_000))
        assertEquals(40, g.sample(0.75f, 1, 250_000))
        assertEquals(60, g.sample(0.7f, 1, 280_000))
        assertNull(g.sample(0.7f, 1, 400_000))
    }

    @Test fun `a forecast that is high but short of severe at a light status keeps the game's cap`() {
        val g = GamePacing.Governor(60)
        for (t in 0..300_000L step 5_000) assertNull(g.sample(0.87f, 1, t))
        assertEquals(60, g.cap)
    }

    @Test fun `the band between the thresholds resets both dwells`() {
        val g = GamePacing.Governor(60)
        g.sample(0.96f, 1, 0)
        g.sample(0.9f, 1, 5_000)
        assertNull(g.sample(0.96f, 1, 10_000))
        assertEquals(40, g.sample(0.96f, 1, 20_000))
    }

    @Test fun `severe status counts as hot and moderate blocks a step up, unknown headroom goes by status`() {
        val g = GamePacing.Governor(60)
        g.sample(Float.NaN, 3, 0)
        assertEquals(40, g.sample(Float.NaN, 3, 10_000))
        assertNull(g.sample(Float.NaN, 3, 20_000))
        assertNull(g.sample(0.5f, 2, 20_000))
        assertNull(g.sample(0.5f, 2, 60_000))
        g.sample(Float.NaN, 0, 70_000)
        assertEquals(60, g.sample(Float.NaN, 0, 100_000))
    }

    @Test fun `the display vote takes the lower of the session limit and the game cap`() {
        assertEquals(SessionPrefs.FPS_UNPACED, GamePacing.vote(SessionPrefs.FPS_UNPACED, 0))
        assertEquals(60, GamePacing.vote(SessionPrefs.FPS_UNPACED, 60))
        assertEquals(30, GamePacing.vote(30, 60))
        assertEquals(40, GamePacing.vote(60, 40))
    }
    @Test fun `an idle player gets the idle rate and a long idle ends the session`() {
        assertEquals(30, GamePacing.paceFps(30, 59_000L))
        assertEquals(15, GamePacing.paceFps(30, 60_000L))
        assertEquals(10, GamePacing.paceFps(10, 600_000L))
        assertEquals(0, GamePacing.paceFps(0, 600_000L))
        assertEquals(null, GamePacing.idleStop(44 * 60_000L))
        assertEquals("no input for 45 min", GamePacing.idleStop(45 * 60_000L))
    }

    @Test fun `the pace file is written whole`() {
        val dir = java.nio.file.Files.createTempDirectory("pace").toFile()
        val file = java.io.File(dir, "pace")
        GamePacing.writePace(file, 30)
        GamePacing.writePace(file, 15)
        assertEquals("15", file.readText())
        assertEquals(listOf("pace"), dir.list()!!.toList())
        dir.deleteRecursively()
    }
}
