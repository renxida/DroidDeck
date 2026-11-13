package com.droiddeck.launcher.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveStatusTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_762_941_600_000L

    private fun input(sample: LiveSampler.Sample?, history: List<String> = emptyList(), settings: String? = null,
                      extra: List<Pair<String, String>> = emptyList(), ended: Boolean = false) =
        LiveText.Input("perf-live-abc1234", "Google-Pixel-10", t0, t0 + 750_000, ended, null, sample, settings,
            null, history, extra)

    private fun session(name: String = "2026-11-12-01-steam"): File = tmp.newFolder(name).apply {
        resolve("events.jsonl").writeText(listOf(
            """{"t":$t0,"event":"session.created","sessionId":"s","mode":"steam"}""",
            """{"t":${t0 + 60_000},"event":"steam.game_launch_requested","sessionId":"s","gameId":"2868840"}""",
            """{"t":${t0 + 90_000},"event":"pacing.cap","sessionId":"s","app_id":"2868840","cap":60,"game_cap":60,"why":"game started"}""",
            """{"t":${t0 + 95_000},"event":"game.experiments","sessionId":"s","on":"fex-tso-fast"}""",
            """{"t":${t0 + 100_000},"event":"game.first_frame","sessionId":"s","engine":"DXVK","pid":42}""",
            """{"t":${t0 + 101_000},"event":"keyboard.requested","sessionId":"s"}""",
        ).joinToString("\n", postfix = "\n"))
        resolve(RemoteConfig.SETTINGS_FILE).writeText("# settings this session started with\nremote config: none\n" +
            "experiments: fex-tso-fast\nhudMode=2\n")
        resolve("clocks.log").writeText("== clocks: test\nstart: wall_ms=$t0 tz_offset_min=0\n" +
            "10:12:28 p0 1800/1800 p4 2400/2400 | gpu 600 MHz | thermal 0 headroom 0.51 | battery 37.0 C\n" +
            "10:12:30 p0 1800/1800 p4 2400/2400(of 3100) | gpu 700 MHz | thermal 1 headroom 0.62 | battery 38.5 C\n")
        resolve("memory.csv").writeText(
            "t_wall_ms,used_mb,available_mb,swap_used_mb,game_mb,droiddeck_mb,app_mb,gpu_mb,game_swap_mb,droiddeck_swap_mb,top_process,top_mb," +
                "game_majflt_s,droiddeck_majflt_s,swapin_mb_s,swapout_mb_s\n" +
                "${t0 + 735_000},9000,3000,1200,4100,900,300,800,0,10,SlayTheSpire2.,4100,,,,\n" +
                "${t0 + 740_000},9000,3000,2600,600,900,300,800,2200,10,SlayTheSpire2.,2800,412.25,3.0,18.5,0.4\n")
        resolve("processes.csv").writeText("t_wall_ms,pid,name,side,rss_mb,swap_mb,cpu_pct\n" +
            "${t0 + 600_000},42,old,game,10,0,99.0\n" +
            "${t0 + 660_000},42,SlayTheSpire2.,game,4100,0,180.5\n" +
            "${t0 + 660_000},7,FEXServer,droiddeck,120,0,12.0\n" +
            "${t0 + 660_000},8,steamwebhelper,droiddeck,300,5,\n")
        val frames = StringBuilder("# frames t0_wall_ms=$t0 t0_mono_ns=1 source=gamescope\nt_ms,frametime_us\n")
        frames.append("# t_ms=1000 engine=GAMESCOPE pid=10 steam=1 output=1920x1080@60000mHz\n")
        var t = 1000L
        repeat(30) { t += 100; frames.append("$t,100000\n") }
        frames.append("# t_ms=100000 engine=DXVK pid=42 steam=0 output=1920x1080@60000mHz\n")
        t = 100_000L
        while (t < 740_000L) { t += 16; frames.append("$t,16667\n") }
        frames.append("${t + 60},60000\n${t + 180},120000\n${t + 1400},1200000\n")
        resolve(FrameLogComponent.FILE).writeText(frames.toString())
    }

    @Test fun frameSummaryMatchesPerfReport() {
        val s = FrameSummary.of(List(98) { 10_000L } + listOf(60_000L, 120_000L, 2_000_000L))
        assertEquals(100, s.frames)
        assertEquals(1, s.pauses)
        assertEquals(10.0, s.medianMs!!, 1e-9)
        assertEquals(10.0, s.p95Ms!!, 1e-9)
        assertEquals(60.0, s.p99Ms!!, 1e-9)
        assertEquals(1000.0 / 60.0, s.low1Fps!!, 1e-9)
        assertEquals(2, s.over50)
        assertEquals(1, s.over100)
        assertEquals(100 / (1_160_000 / 1e6), s.fps!!, 1e-9)
        assertEquals(FrameSummary.EMPTY, FrameSummary.of(emptyList()))
    }

    @Test fun histogramPercentilesAreWithinItsBucket() {
        val h = FrameHistogram()
        val values = (1..1000).map { 10_000L + it * 37 }
        values.forEach(h::add)
        h.add(5_000_000)
        val exact = FrameSummary.of(values)
        val approx = h.summary()
        assertEquals(exact.frames, approx.frames)
        assertEquals(exact.fps!!, approx.fps!!, 1e-9)
        assertEquals(exact.medianMs!!, approx.medianMs!!, 0.1)
        assertEquals(exact.p95Ms!!, approx.p95Ms!!, 0.1)
        assertEquals(exact.p99Ms!!, approx.p99Ms!!, 0.1)
        assertEquals(1, approx.pauses)
    }

    @Test fun historyKeepsOnlyTheNewestLines() {
        val history = LiveHistory(3)
        (1..5).forEach { history.add("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), history.lines())
        assertEquals(240, LiveStatus.HISTORY_LINES)
    }

    @Test fun liveSessionCapsItsHistory() {
        val live = LiveSession(session(), "runs/b/d/x.live.txt")
        var text = ""
        repeat(LiveStatus.HISTORY_LINES + 5) { text = live.render("b", "d", t0 + 750_000L + it * 30_000L, false, null, null, emptyList()) }
        assertEquals(LiveStatus.HISTORY_LINES, live.history.lines().size)
        val history = text.substringAfter("## history").lines().drop(1).filter { it.isNotBlank() }
        assertEquals(LiveStatus.HISTORY_LINES, history.size)
        assertTrue(history.first(), history.first().startsWith("+0:15:00 "))
    }

    @Test fun noSessionDataStillGivesAFile() {
        val text = LiveText.build(input(null))
        assertTrue(text, text.contains("game running: unknown"))
        assertTrue(text.contains("app id: -"))
        assertTrue(text.contains("settings.txt: none"))
        assertTrue(text.contains("pacing: no game frame cap"))
        assertTrue(text.contains("no frame data"))
        assertTrue(text.contains("## events\nnone yet"))
        assertTrue(text.trimEnd().endsWith("## history (${LiveText.HISTORY_COLUMNS})"))
        assertTrue(text.contains("game: rss - MB, swap - MB, major faults -/s, swap-in - MB/s"))
        assertEquals("+0:12:30 - - - - - - - - - -", LiveText.historyLine(t0, t0 + 750_000, null))
    }

    @Test fun aSessionWithoutAGame() {
        val dir = tmp.newFolder("2026-11-12-02-steam")
        dir.resolve("events.jsonl").writeText("""{"t":$t0,"event":"session.created","sessionId":"s"}""" + "\n")
        dir.resolve(FrameLogComponent.FILE).writeText("# frames t0_wall_ms=$t0 t0_mono_ns=1 source=gamescope\nt_ms,frametime_us\n" +
            "# t_ms=10 engine=GAMESCOPE pid=10 steam=1 output=1920x1080@60000mHz\n" +
            (1..100).joinToString("") { "${700_000 + it * 100},100000\n" })
        val sample = LiveSampler(dir).sample(t0 + 720_000)
        assertFalse(sample.gameRunning)
        assertEquals(0, sample.game.frames)
        assertEquals(100, sample.all.frames)
        assertEquals(100, sample.window.frames)
        assertEquals("Steam pid 10", sample.focus)
        assertNull(sample.cap)
        assertTrue(sample.memory.isEmpty())
        assertTrue(sample.processes.isEmpty())
        val text = LiveText.build(input(sample))
        assertTrue(text, text.contains("game running: no"))
        assertTrue(text.contains("pacing: no game frame cap"))
    }

    @Test fun samplesAGameSession() {
        val dir = session()
        val sampler = LiveSampler(dir)
        val now = t0 + 750_000
        val s = sampler.sample(now)
        assertEquals(t0, sampler.startMs)
        assertTrue(s.gameRunning)
        assertEquals(t0 + 100_000, s.gameSinceMs)
        assertEquals("2868840", s.appId)
        assertEquals("DXVK pid 42", s.focus)
        assertEquals(60, s.cap)
        assertEquals("game started", s.capWhy)
        assertEquals("fex-tso-fast", s.experiments)
        assertEquals(1, s.thermalStatus)
        assertEquals(0.62, s.headroom!!, 1e-9)
        assertEquals(38.5, s.batteryC!!, 1e-9)
        assertEquals(t0 + 740_000, s.memoryAtMs)
        assertEquals("600", s.gameRssMb)
        assertEquals("2200", s.gameSwapMb)
        assertEquals("412.25", s.gameMajfltS)
        assertEquals("18.5", s.swapinMbS)
        assertEquals("0.4", s.memory["swapout_mb_s"])
        assertFalse(s.memory.containsKey("t_wall_ms"))
        assertEquals(3, s.processes.size)
        assertTrue(s.processes[0], s.processes[0].contains("SlayTheSpire2. (pid 42)"))
        assertTrue(s.processes[2], s.processes[2].contains("steamwebhelper"))
        assertEquals(40_000 + 2, s.game.frames)
        assertEquals(1, s.game.pauses)
        assertEquals(2, s.game.over50)
        assertEquals(1, s.game.over100)
        assertEquals(30 + 40_002, s.all.frames)
        assertEquals(1_252, s.window.frames)
        assertEquals(60.0, s.window.fps!!, 1.0)
        assertEquals(4, s.events.size)
        assertTrue(s.events.none { it.contains("keyboard") })
        assertTrue(s.events.last(), s.events.last().startsWith("+0:01:40 game.first_frame engine=DXVK pid=42"))

        dir.resolve("events.jsonl").appendText(
            """{"t":${now + 10_000},"event":"pacing.cap","sessionId":"s","app_id":"2868840","cap":40,"game_cap":60,"why":"thermal governor, headroom 0.90, status 2"}""" + "\n" +
                """{"t":${now + 20_000},"event":"game.exited","sessionId":"s","app_id":"2868840","run_s":670.0,"abnormal":true,"how":"pid gone"}""" + "\n")
        dir.resolve(FrameLogComponent.FILE).appendText("# t_ms=760000 engine=GAMESCOPE pid=10 steam=1 output=1920x1080@60000mHz\n760100,100000\n")
        val later = sampler.sample(now + 30_000)
        assertFalse(later.gameRunning)
        assertEquals(40, later.cap)
        assertEquals(60, later.gameCap)
        assertTrue(later.lastExit!!, later.lastExit!!.contains("abnormal=true"))
        assertEquals(40_002, later.game.frames)
        assertEquals(1, later.window.frames)

        val text = LiveText.build(input(later, settings = dir.resolve(RemoteConfig.SETTINGS_FILE).readText(),
            extra = listOf("input latency" to "p50 12 ms")))
        assertTrue(text, text.contains("pacing: cap 40 fps of 60 (thermal governor, headroom 0.90, status 2)"))
        assertTrue(text.contains("remote config: none\nexperiments: fex-tso-fast\nhudMode=2\n"))
        assertFalse(text.contains("# settings this session"))
        assertTrue(text.contains("## input latency\np50 12 ms\n"))
        assertTrue(text.contains("thermal: status 1, headroom 0.62, battery 38.5 C"))
        assertTrue(text.contains("game (10 s ago): rss 600 MB, swap 2200 MB, major faults 412.3/s, swap-in 18.5 MB/s"))
        assertTrue(text.contains("memory.csv: used_mb=9000 available_mb=3000 swap_used_mb=2600 droiddeck_mb=900"))
        assertTrue(text, text.contains("power: no power.csv yet"))
        assertEquals("+0:13:00 10.0 100.0 0.62 40 38.5 600 2200 412.3 18.5 -", LiveText.historyLine(t0, now + 30_000, later))
    }

    @Test fun memoryColumnsAreReadByName() {
        val dir = tmp.newFolder("2026-11-12-04-steam")
        dir.resolve("memory.csv").writeText("t_wall_ms,swapin_mb_s,game_swap_mb,extra,game_mb,game_majflt_s\n" +
            "${t0 + 1000},7.25,1500,x,700,90\n")
        val s = LiveSampler(dir).sample(t0 + 2000)
        assertEquals("700", s.gameRssMb)
        assertEquals("1500", s.gameSwapMb)
        assertEquals("90", s.gameMajfltS)
        assertEquals("7.25", s.swapinMbS)
        assertEquals(t0 + 1000, s.memoryAtMs)
        assertTrue(LiveText.historyLine(t0, t0 + 2000, s).endsWith(" 700 1500 90.0 7.3 -"))
    }

    @Test fun powerCsvIsReadIncrementallyIntoTheDeviceSectionAndHistory() {
        val dir = tmp.newFolder("2026-11-12-05-steam")
        val power = dir.resolve(PowerRow.FILE)
        fun row(t: Long, ua: Long, game: Int) = "$t,$ua,2500000,4000,${-ua * 4 / 1000},50,discharging,none,36.0,0,0.50,128,1,1,$game\n"
        power.writeText(PowerRow.HEADER + "\n" + row(t0, -1_000_000, 1) + row(t0 + 5_000, -1_000_000, 1))
        val sampler = LiveSampler(dir)
        val first = sampler.sample(t0 + 10_000)
        assertEquals(5_000L, first.power!!.gameMs)
        assertEquals(4000.0, first.power!!.gameAvgMw!!, 1e-6)
        assertEquals(4000.0, first.windowMw!!, 1e-6)
        // the 35 s gap before the last row is a stopped app, not play
        power.appendText(row(t0 + 10_000, -2_000_000, 1) + row(t0 + 45_000, -2_000_000, 1))
        val second = sampler.sample(t0 + 50_000)
        assertEquals(4, second.power!!.rows)
        assertEquals(10_000L, second.power!!.gameMs)
        assertEquals(5000.0, second.power!!.gameAvgMw!!, 1e-6)
        assertEquals(8000.0, second.windowMw!!, 1e-6)
        val text = LiveText.build(input(second))
        assertTrue(text, text.contains("power: now 8000 mW (discharging, 50%, 4.00 V, brightness 128 auto); game 0.2 min avg 5000 mW"))
        assertTrue(LiveText.historyLine(t0, t0 + 50_000, second).endsWith(" 8000"))
    }

    @Test fun fileStaysSmall() {
        val live = LiveSession(session(), "p")
        var text = ""
        repeat(LiveStatus.HISTORY_LINES) { text = live.render("perf-live-abc1234", "Google-Pixel-10", t0 + 750_000L + it * 30_000L,
            false, "2868840", "remote config: fetched 2026-11-13T10:00:00Z, rotation row 1 of 2", emptyList()) }
        assertTrue("${text.length} bytes", text.length < 14 * 1024)
        println(LiveText.build(input(LiveSampler(session("2026-11-12-03-steam")).sample(t0 + 750_000),
            history = live.history.lines().take(4), settings = live.dir.resolve(RemoteConfig.SETTINGS_FILE).readText())))
    }

    @Test fun livePathSitsBesideTheZip() {
        assertEquals("runs/b/d/2026-11-13T10-00-00Z.live.txt", LiveStatus.remotePath("runs/b/d/2026-11-13T10-00-00Z.zip"))
        assertNotNull(LiveStatus.extraSections())
    }
}
