package com.droiddeck.launcher.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class LatencyLogTest {
    private val t0 = 5_000_000_000L
    private val ms = 1_000_000L

    private fun row(kind: Int, event: Long, inject: Long, commit: Long, present: Long, display: Long = 0,
                    id: Long = 0, pending: Int = -1, refresh: Long = 8_333_333) =
        LatencyCsvWriter.Row(kind, event, inject, commit, present, display, id, pending, refresh)

    @Test fun eachStageIsWrittenInMicrosecondsAfterTheAndroidEvent() {
        val out = StringWriter()
        val csv = LatencyCsvWriter(out, t0, 1L shl 20)
        csv.header(1_700_000_000_000L)
        csv.add(row(InputLatency.TOUCH_UP, t0 + 2000 * ms, t0 + 2000 * ms + 40_000, t0 + 2009 * ms, t0 + 2010 * ms,
            t0 + 2034 * ms, id = 7, pending = 2), 60)
        csv.add(row(InputLatency.PAD_DOWN, t0 + 3000 * ms, t0 + 3001 * ms, 0, 0), 0)
        csv.flush()

        val lines = out.toString().lines().filter { it.isNotEmpty() }
        assertEquals("# latency t0_wall_ms=1700000000000 t0_mono_ns=$t0", lines[0])
        assertEquals("t_ms,kind,inject_us,commit_us,present_us,display_us,present_id,swap_pending,refresh_us,cap", lines[1])
        assertEquals("2000,touch_up,40,9000,10000,34000,7,2,8333,60", lines[2])
        assertEquals("3000,pad_down,1000,,,,0,-1,8333,0", lines[3])
        assertEquals(2L, csv.rows)
        assertEquals(out.toString().length.toLong(), csv.bytes)
    }

    @Test fun rowsAreReadFromTheNativeLayout() {
        val rows = longArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 1, 10, 11, 12, 13, 14, 15, 3, 16_666_667)
        assertEquals(row(InputLatency.TOUCH_UP, 10, 11, 12, 13, 14, 15, 3, 16_666_667), LatencyCsvWriter.Row.read(rows, 1))
    }

    @Test fun pastTheSizeLimitOneNoteIsWrittenAndNothingElse() {
        val out = StringWriter()
        val csv = LatencyCsvWriter(out, t0, 200)
        repeat(10) { csv.add(row(InputLatency.TOUCH_DOWN, t0 + it * ms, t0 + it * ms, t0 + it * ms, t0 + it * ms), 60) }
        assertTrue(csv.capped)
        assertTrue(out.toString().endsWith("# capped at 200 bytes after ${csv.rows} inputs\n"))
        assertTrue(out.toString().length <= 200)
    }

    @Test fun theMinuteSummaryGivesMedianAndNinetiethPercentilePerPath() {
        val summary = LatencySummary()
        assertTrue(summary.empty)
        for (i in 1..10) {
            val e = i * 1000 * ms
            summary.add(row(InputLatency.TOUCH_DOWN, e, e + ms, e + i * 5 * ms, e + i * 10 * ms, e + i * 20 * ms,
                id = i.toLong(), pending = i % 3), if (i < 5) 60 else 40)
        }
        summary.add(row(InputLatency.PAD_DOWN, 0, ms, 4 * ms, 20 * ms), 40)
        summary.add(row(InputLatency.TOUCH_UP, 0, ms, 0, 0), 40)
        assertFalse(summary.empty)

        val fields = summary.fields(dropped = 2)
        assertEquals(12, fields["inputs"])
        assertEquals(1, fields["unanswered"])
        assertEquals(10, fields["touch_present_n"])
        assertEquals(50.0, fields["touch_present_p50_ms"])
        assertEquals(90.0, fields["touch_present_p90_ms"])
        assertEquals(100.0, fields["touch_display_p50_ms"])
        assertEquals(180.0, fields["touch_display_p90_ms"])
        assertEquals(25.0, fields["touch_commit_p50_ms"])
        assertEquals(11, fields["touch_inject_n"])
        assertEquals(1.0, fields["touch_inject_p50_ms"])
        assertEquals(20.0, fields["pad_present_p50_ms"])
        assertEquals(2, fields["swap_pending_max"])
        assertEquals(120, fields["refresh_hz"])
        assertEquals("40/60", fields["cap"])
        assertEquals(2, fields["dropped"])
    }

    @Test fun percentilesUseTheNearestRank() {
        assertEquals(3L, LatencySummary.percentile(listOf(5, 1, 3, 2, 4), 50))
        assertEquals(5L, LatencySummary.percentile(listOf(5, 1, 3, 2, 4), 90))
        assertEquals(7L, LatencySummary.percentile(listOf(7), 90))
    }
}
