package com.droiddeck.launcher.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/** power.csv's units, signs and game-phase sums; plain JUnit, no Android. */
class PowerLogTest {
    private val t0 = 1_794_559_000_000L

    private fun row(t: Long, ua: Long?, plugged: String = PowerRow.PLUGGED_NONE, game: Boolean = true, level: Int? = 80,
                    chargeUah: Long? = 4_000_000L, mv: Int? = 4000) =
        PowerRow(t, ua, chargeUah, mv, PowerMath.drawMw(ua, mv, plugged != PowerRow.PLUGGED_NONE), level,
            if (plugged == PowerRow.PLUGGED_NONE) "discharging" else "charging", plugged, 35.5, 1, 0.42f, 102, false, true, game)

    @Test fun vendorMilliampsBecomeMicroamps() {
        assertEquals(-2_000_000L, PowerMath.toMicroamps(-2_000L))
        assertEquals(9_999_000L, PowerMath.toMicroamps(9_999L))
        assertEquals(-2_000_000L, PowerMath.toMicroamps(-2_000_000L))
        assertEquals(10_000L, PowerMath.toMicroamps(10_000L))
        assertEquals(0L, PowerMath.toMicroamps(0L))
    }

    @Test fun microampsTimesMillivoltsIsMilliwatts() {
        assertEquals(8000.0, PowerMath.milliwatts(2_000_000L, 4000), 1e-9)
        assertEquals(-3862.0, PowerMath.milliwatts(-1_000_000L, 3862), 1e-9)
    }

    @Test fun unpluggedDrawIsPositiveWhicheverSignTheVendorUses() {
        // Pixel: negative while discharging (Android's convention)
        assertEquals(6000.0, PowerMath.drawMw(-1_500_000L, 4000, plugged = false)!!, 1e-9)
        // vendors with the opposite sign
        assertEquals(6000.0, PowerMath.drawMw(1_500_000L, 4000, plugged = false)!!, 1e-9)
    }

    @Test fun pluggedInChargingIsNegativeDraw() {
        assertEquals(-8000.0, PowerMath.drawMw(2_000_000L, 4000, plugged = true)!!, 1e-9)
        // plugged in but the game outruns the charger: the battery still drains
        assertEquals(1200.0, PowerMath.drawMw(-300_000L, 4000, plugged = true)!!, 1e-9)
    }

    @Test fun drawNeedsCurrentAndVoltage() {
        assertNull(PowerMath.drawMw(null, 4000, false))
        assertNull(PowerMath.drawMw(-1_000_000L, null, false))
        assertNull(PowerMath.drawMw(-1_000_000L, 0, false))
    }

    @Test fun fullCapacityFromCounterAndLevel() {
        assertEquals(5000.0, PowerMath.fullMah(4_000_000L, 80)!!, 1e-9)
        assertNull(PowerMath.fullMah(0L, 80))
        assertNull(PowerMath.fullMah(4_000_000L, 0))
        assertNull(PowerMath.fullMah(null, 50))
    }

    @Test fun pluggedAndStatusNames() {
        assertEquals("none", PowerMath.pluggedName(0))
        assertEquals("ac", PowerMath.pluggedName(1))
        assertEquals("usb", PowerMath.pluggedName(2))
        assertEquals("wireless", PowerMath.pluggedName(4))
        assertEquals("dock", PowerMath.pluggedName(8))
        assertEquals("discharging", PowerMath.statusName(3))
        assertEquals("charging", PowerMath.statusName(2))
        assertEquals("full", PowerMath.statusName(5))
        assertEquals("not_charging", PowerMath.statusName(4))
        assertEquals("unknown", PowerMath.statusName(1))
    }

    @Test fun csvRowRoundTripsThroughTheHeader() {
        val r = row(t0, -1_250_000L)
        val line = r.csv()
        assertEquals("$t0,-1250000,4000000,4000,5000,80,discharging,none,35.5,1,0.42,102,0,1,1", line)
        assertEquals(PowerRow.HEADER.split(',').size, line.split(',').size)
        val back = PowerRow.parse(PowerRow.HEADER.split(','), line)!!
        assertEquals(r, back)
        assertNull(PowerRow.parse(PowerRow.HEADER.split(','), PowerRow.HEADER))
        assertNull(PowerRow.parse(PowerRow.HEADER.split(','), "# capped"))
    }

    @Test fun blankCellsStayUnknown() {
        val r = PowerRow(t0, null, null, null, null, null, "unknown", "none", null, null, Float.NaN, null, null, null, false)
        assertEquals("$t0,,,,,,unknown,none,,,,,,,0", r.csv())
        val back = PowerRow.parse(PowerRow.HEADER.split(','), r.csv())!!
        assertNull(back.currentUa)
        assertNull(back.headroom)
        assertNull(back.screenOn)
        assertFalse(back.game)
    }

    @Test fun columnsAreReadByName() {
        val header = listOf("t_wall_ms", "game", "power_mw", "plugged")
        val r = PowerRow.parse(header, "$t0,1,4321,usb")!!
        assertEquals(4321.0, r.powerMw!!, 1e-9)
        assertTrue(r.game)
        assertFalse(r.unplugged)
    }

    @Test fun gamePhaseAddsUpUnpluggedGameIntervalsOnly() {
        val acc = PowerAccumulator()
        // Steam menu, unplugged: counts toward unplugged power but not the game
        acc.add(row(t0, -1_000_000L, game = false))
        acc.add(row(t0 + 5_000, -1_000_000L, game = false))
        // game: 2 A then 3 A at 4 V; the counter falls 3000 then 4000 µAh
        acc.add(row(t0 + 10_000, -2_000_000L, chargeUah = 4_000_000L))
        acc.add(row(t0 + 15_000, -2_000_000L, chargeUah = 3_997_000L))
        acc.add(row(t0 + 20_000, -3_000_000L, chargeUah = 3_993_000L, level = 79))
        // plugged in mid-game: left out and reported as excluded
        acc.add(row(t0 + 25_000, 1_000_000L, plugged = "usb"))
        acc.add(row(t0 + 30_000, 1_000_000L, plugged = "usb"))
        acc.add(row(t0 + 35_000, -2_000_000L))
        // a 60 s gap (app stopped) is not play
        acc.add(row(t0 + 95_000, -2_000_000L))
        val s = acc.stats()
        assertEquals(9, s.rows)
        assertEquals(10_000L, s.gameMs)
        // 5 s at 8 W, 5 s at (8+12)/2 W
        val energy = (8000.0 * 5_000 + 10_000.0 * 5_000) / 3_600_000.0
        assertEquals(energy, s.gameEnergyMwh, 1e-9)
        assertEquals(9000.0, s.gameAvgMw!!, 1e-9)
        assertEquals(150.0, s.gameMwhPerMin!!, 1e-9)
        // 2 A × 5 s + 2.5 A × 5 s = 22.5 A·s = 6.25 mAh
        assertEquals(6.25, s.gameMah, 1e-9)
        assertEquals(7.0, s.gameCounterMah!!, 1e-9)
        assertEquals(1, s.gameLevelDrop)
        assertEquals(15_000L, s.gameChargingMs)
        // menu 5 s, menu-to-game 5 s, game 10 s
        assertEquals(20_000L, s.unpluggedMs)
        assertEquals(s.gameMinutes, 10.0 / 60, 1e-12)
        // 6.25 mAh in 10 s is 2250 mA, over a ~5000 mAh battery: the mean of every row's counter / level
        val full = (5000.0 * 7 + 3_997_000.0 * 100 / 80 / 1000 + 3_993_000.0 * 100 / 79 / 1000) / 9
        assertEquals(full, s.fullMah!!, 1e-6)
        assertEquals(6.25 / full * 100 * 360, s.gamePctPerHour!!, 1e-6)
    }

    @Test fun drainFallsBackToLevelWithoutACounter() {
        val acc = PowerAccumulator()
        acc.add(row(t0, -2_000_000L, chargeUah = null, level = 80))
        acc.add(row(t0 + 30_000, -2_000_000L, chargeUah = null, level = 79))
        val s = acc.stats()
        assertNull(s.fullMah)
        assertNull(s.gameCounterMah)
        assertEquals(1.0 / (30_000 / 3_600_000.0), s.gamePctPerHour!!, 1e-9)
    }

    @Test fun noGameTimeMeansNoGameFigures() {
        val acc = PowerAccumulator()
        acc.add(row(t0, -1_000_000L, game = false))
        acc.add(row(t0 + 5_000, -1_000_000L, game = false))
        val s = acc.stats()
        assertEquals(0L, s.gameMs)
        assertNull(s.gameAvgMw)
        assertNull(s.gamePctPerHour)
        assertNull(s.gameMwhPerMin)
        assertEquals(4000.0, s.unpluggedAvgMw!!, 1e-9)
        assertTrue(s.liveLine(), s.liveLine().endsWith("; no unplugged game time yet"))
        assertTrue(s.summaryText().contains("game_avg_mw: -\n"))
    }

    @Test fun statsFromCsvTextMatchTheAccumulator() {
        val rows = listOf(row(t0, -2_000_000L), row(t0 + 5_000, -2_000_000L), row(t0 + 10_000, -2_000_000L))
        val text = "${PowerRow.HEADER}\n" + rows.joinToString("") { it.csv() + "\n" } + "# capped at 9 bytes\n"
        val s = PowerStats.of(text)
        assertEquals(3, s.rows)
        assertEquals(10_000L, s.gameMs)
        assertEquals(8000.0, s.gameAvgMw!!, 1e-9)
        val summary = s.summaryText()
        assertTrue(summary, summary.contains("game_minutes: 0.17\n"))
        assertTrue(summary, summary.contains("game_avg_mw: 8000\n"))
        assertTrue(summary, summary.contains("game_mwh_per_min: 133.3\n"))
        assertTrue(summary, summary.contains("game_mah: 5.6\n"))
        assertTrue(summary, summary.contains("battery_full_mah: 5000\n"))
        assertTrue(s.liveLine(), s.liveLine().startsWith("now 8000 mW (discharging, 80%, 4.00 V, brightness 102); game 0.2 min avg 8000 mW, 5.6 mAh, "))
        assertEquals(0, PowerStats.of("").rows)
        assertEquals(0, PowerStats.of("no header\n$t0,1,2\n").rows)
    }

    @Test fun csvWriterWritesTheHeaderAndStopsAtItsCap() {
        val out = StringWriter()
        val csv = PowerCsv(out, maxBytes = 400)
        csv.header()
        repeat(10) { csv.add(row(t0 + it * 5_000L, -1_000_000L)) }
        val lines = out.toString().lines().filter { it.isNotEmpty() }
        assertEquals(PowerRow.HEADER, lines.first())
        assertTrue(lines.last(), lines.last().startsWith("# capped at 400 bytes"))
        assertTrue(out.toString().length <= 400)
        assertEquals(1, lines.count { it.startsWith("#") })
    }

    @Test fun headroomIsAskedAtMostOncePerGap() {
        var now = 0L
        var calls = 0
        val cache = HeadroomCache(1_500L) { now }
        assertEquals(0.5f, cache.get { calls++; 0.5f })
        now = 1_000L
        assertEquals(0.5f, cache.get { calls++; 0.9f })
        assertEquals(1, calls)
        now = 1_600L
        assertEquals(0.9f, cache.get { calls++; 0.9f })
        now = 3_200L
        assertNull(cache.get { calls++; Float.NaN })
        now = 4_000L
        assertNull(cache.get { calls++; 0.7f })
        assertEquals(3, calls)
    }
}
