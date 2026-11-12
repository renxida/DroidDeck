package com.droiddeck.launcher.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class FrameCsvWriterTest {
    private val t0 = 5_000_000_000L

    @Test fun framesAreWrittenRelativeToTheSessionStartInMicroseconds() {
        val out = StringWriter()
        val csv = FrameCsvWriter(out, t0, 1L shl 20)
        csv.header(1_700_000_000_000L, "gamescope")
        csv.add(longArrayOf(16_666_667, 33_400_000), longArrayOf(t0 + 1_016_000_000, t0 + 1_049_400_000), 2)
        csv.flush()

        val lines = out.toString().lines().filter { it.isNotEmpty() }
        assertEquals("# frames t0_wall_ms=1700000000000 t0_mono_ns=$t0 source=gamescope", lines[0])
        assertEquals("t_ms,frametime_us", lines[1])
        assertEquals(listOf("1016,16666", "1049,33400"), lines.drop(2))
        assertEquals(2L, csv.frames)
        assertEquals(out.toString().length.toLong(), csv.bytes)
    }

    @Test fun framesFromBeforeTheSessionOrAlreadyWrittenAreSkipped() {
        val out = StringWriter()
        val csv = FrameCsvWriter(out, t0, 1L shl 20)
        csv.add(longArrayOf(16_000_000, 16_000_000), longArrayOf(t0 - 10, t0 + 2_000_000), 2)
        csv.add(longArrayOf(16_000_000, 16_000_000, 0), longArrayOf(t0 + 2_000_000, t0 + 18_000_000, t0 + 30_000_000), 3)

        assertEquals("2,16000\n18,16000\n", out.toString())
        assertEquals(2L, csv.frames)
    }

    @Test fun focusInfoIsWrittenOnlyWhenItChanges() {
        val out = StringWriter()
        val csv = FrameCsvWriter(out, t0, 1L shl 20)
        assertTrue(csv.info(t0 + 3_000_000_000, "DXVK", 412, false, 1920, 1080, 60000))
        assertFalse(csv.info(t0 + 3_500_000_000, "DXVK", 412, false, 1920, 1080, 60000))
        assertTrue(csv.info(t0 + 4_000_000_000, "GAMESCOPE", 99, true, 1920, 1080, 60000))
        assertTrue(csv.info(t0 + 5_000_000_000, "bad name\n", 1, false, 1, 1, 1))

        assertEquals(
            "# t_ms=3000 engine=DXVK pid=412 steam=0 output=1920x1080@60000mHz\n" +
                "# t_ms=4000 engine=GAMESCOPE pid=99 steam=1 output=1920x1080@60000mHz\n" +
                "# t_ms=5000 engine=bad_name_ pid=1 steam=0 output=1x1@1mHz\n",
            out.toString(),
        )
    }

    @Test fun theFileStopsWithOneNoteAtTheCap() {
        val out = StringWriter()
        val csv = FrameCsvWriter(out, t0, 200)
        val n = 50
        val frametimes = LongArray(n) { 16_666_667 }
        val times = LongArray(n) { t0 + (it + 1) * 16_666_667L }
        csv.add(frametimes, times, n)
        csv.add(frametimes, LongArray(n) { times.last() + (it + 1) * 16_666_667L }, n)

        assertTrue(csv.capped)
        assertTrue(out.toString().length <= 200)
        assertEquals(1, out.toString().lines().count { it.startsWith("# capped at 200 bytes") })
        assertTrue(out.toString().endsWith("# capped at 200 bytes after ${csv.frames} frames\n"))
    }
}
