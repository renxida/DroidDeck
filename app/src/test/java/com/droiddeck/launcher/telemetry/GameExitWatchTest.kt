package com.droiddeck.launcher.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameExitWatchTest {
    @get:Rule val tmp = TemporaryFolder()
    private val live = HashSet<Int>()
    private val watch = GameExitWatch { it in live }

    @Test fun aGameWhoseProcessEndsExitsAfterASettleTime() {
        live += 412
        watch.line("DROIDDECK_TIMELINE event=game_exec app_id=2868840 wall_ms=1000", 1_000)
        watch.focus(412, "DXVK", steam = false, nowMs = 5_000)
        assertTrue(watch.poll(60_000).isEmpty())
        live -= 412
        assertTrue(watch.poll(61_000).isEmpty())
        assertTrue(watch.poll(62_000).isEmpty())
        val exit = watch.poll(64_000).single()
        assertEquals("2868840", exit.appId)
        assertEquals(412, exit.pid)
        assertEquals(60_000L, exit.runMs)
        assertFalse(exit.abnormal)
        assertEquals("process gone", exit.how)
        assertTrue(watch.poll(70_000).isEmpty())
    }

    @Test fun theWrapperStatusAndCrashLinesMakeItAbnormal() {
        live += 7
        watch.line("DROIDDECK_TIMELINE event=game_exec app_id=42 wall_ms=1000", 1_000)
        watch.focus(7, "VKD3D", steam = false, nowMs = 2_000)
        watch.line("wine: Unhandled page fault on read access to 0000000000000000", 9_000)
        live -= 7
        watch.poll(10_000)
        watch.line("DROIDDECK_TIMELINE event=game_exit app_id=42 status=139 signal=11 run_ms=9000 wall_ms=10000", 10_500)
        val exit = watch.poll(11_000).single()
        assertEquals(139, exit.status)
        assertEquals(11, exit.signal)
        assertEquals(9_000L, exit.runMs)
        assertTrue(exit.abnormal)
        assertEquals(listOf("wine: Unhandled page fault on read access to 0000000000000000"), exit.evidence)
        assertEquals(true, exit.fields()["abnormal"])
    }

    @Test fun aLaunchThatDiesBeforeItsWindowIsReportedFromItsCrashOutput() {
        watch.line("DROIDDECK_TIMELINE event=game_exec app_id=42 wall_ms=1000", 1_000)
        watch.line("Unhandled exception. System.Exception", 2_000)
        assertTrue(watch.poll(20_000).isEmpty())
        watch.line("droiddeck-fex: SIGILL at 0x00007f0000001000 (si_code 01)", 21_000)
        assertTrue(watch.poll(23_000).isEmpty())
        val exit = watch.poll(26_000).single()
        assertEquals("crash output", exit.how)
        assertEquals("42", exit.appId)
        assertEquals(2, exit.evidence.size)
        assertTrue(exit.abnormal)
    }

    @Test fun crashOutputWithNoGameRunningIsNotEvidence() {
        watch.line("Segmentation fault (core dumped)", 1_000)
        live += 5
        watch.line("DROIDDECK_TIMELINE event=game_exec app_id=42 wall_ms=2000", 2_000)
        watch.focus(5, "DXVK", steam = false, nowMs = 3_000)
        live.clear()
        watch.poll(4_000)
        val exit = watch.poll(8_000).single()
        assertFalse(exit.abnormal)
        assertTrue(watch.poll(30_000).isEmpty())
    }

    @Test fun steamTheCompositorAndDeadPidsAreNotGames() {
        live += listOf(1, 2)
        watch.focus(1, "GAMESCOPE", steam = false, nowMs = 0)
        watch.focus(2, "DXVK", steam = true, nowMs = 0)
        watch.focus(3, "DXVK", steam = false, nowMs = 0)
        live.clear()
        assertTrue(watch.poll(10_000).isEmpty())
        assertTrue(watch.poll(20_000).isEmpty())
    }

    @Test fun theTailReadsOnlyNewWholeLines() {
        val f = File(tmp.root, "session.log").apply { writeText("old line\n") }
        val tail = GameExitWatch.Tail(listOf(f, File(tmp.root, "missing.log")))
        val seen = ArrayList<String>()
        f.appendText("one\ntw")
        tail.read { seen += it }
        f.appendText("o\nthree\n")
        tail.read { seen += it }
        assertEquals(listOf("one", "two", "three"), seen)
    }

    @Test fun theExplorerWatchdogsLinesAreEventsThatEndNoGame() {
        val failed = "DROIDDECK_TIMELINE event=game_explorer_failed app_id=2868840 attempt=1 action=relaunch run_ms=41000 wall_ms=1700000041000"
        val relaunch = "DROIDDECK_TIMELINE event=game_relaunch app_id=2868840 attempt=2 reason=explorer_failed ended_by=wineserver wall_ms=1700000043000"
        assertEquals("game.explorer_failed" to mapOf("app_id" to "2868840", "attempt" to "1", "action" to "relaunch",
            "run_ms" to "41000", "wall_ms" to "1700000041000"), GameExitWatch.relaunchEvent(failed))
        assertEquals("game.relaunch" to mapOf("app_id" to "2868840", "attempt" to "2", "reason" to "explorer_failed",
            "ended_by" to "wineserver", "wall_ms" to "1700000043000"), GameExitWatch.relaunchEvent(relaunch))
        assertNull(GameExitWatch.relaunchEvent("DROIDDECK_TIMELINE event=game_exec app_id=2868840 wall_ms=1000"))
        watch.line("DROIDDECK_TIMELINE event=game_exec app_id=2868840 wall_ms=1000", 1_000)
        watch.line(failed, 41_000)
        watch.line(relaunch, 43_000)
        assertTrue(watch.poll(120_000).isEmpty())
        val f = File(tmp.root, "session.log").apply { writeText("$failed\n$relaunch\n") }
        assertEquals("s: $failed\ns: $relaunch\n", CrashEvidence.crashLines(listOf(Triple("s", f, 0L))))
    }

    @Test fun crashLinesKeepTheFaultReportsMappingLine() {
        val f = File(tmp.root, "session.log").apply {
            writeText("before\n")
        }
        val from = f.length()
        f.appendText("droiddeck-fex: SIGILL at 0x1 (si_code 01), outside any mapping\n  next detail\nplain\nSegmentation fault (core dumped)\n")
        val text = CrashEvidence.crashLines(listOf(Triple("s", f, from)))
        assertEquals("s: droiddeck-fex: SIGILL at 0x1 (si_code 01), outside any mapping\ns:   next detail\ns: Segmentation fault (core dumped)\n", text)
        assertEquals("", CrashEvidence.crashLines(listOf(Triple("s", File(tmp.root, "none"), 0L))))
    }

    @Test fun gameLogsStayInsideTheirPrefix() {
        val compat = File(tmp.root, "compatdata")
        val roaming = File(compat, "10/pfx/drive_c/users/steamuser/AppData/Roaming").apply { mkdirs() }
        val outside = File(tmp.root, "elsewhere/logs").apply { mkdirs() }
        File(outside, "secret.log").writeText("x")
        java.nio.file.Files.createSymbolicLink(File(roaming, "Escape").toPath(), outside.parentFile.toPath())
        File(roaming, "Game/logs").mkdirs()
        File(roaming, "Game/logs/godot.log").writeText("y")
        File(compat, "not-a-prefix").mkdirs()
        val found = CrashEvidence.gameLogs(listOf(compat), 0)
        assertEquals(listOf("game-logs/10/AppData/Roaming/Game/logs/godot.log"), found.map { it.first })
    }
}
