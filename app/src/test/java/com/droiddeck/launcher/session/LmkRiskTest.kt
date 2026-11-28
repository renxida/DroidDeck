package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LmkRiskTest {
    private val gb = 1024L * 1024

    private fun reading(availableGb: Double = 2.0, swapFreeGb: Double = 0.5, psiSome: Double? = 25.0) = LmkRisk.Reading(
        totalKb = 12 * gb, availableKb = (availableGb * gb).toLong(), swapTotalKb = 8 * gb,
        swapFreeKb = (swapFreeGb * gb).toLong(), psiSome = psiSome, psiFull = psiSome?.div(5),
    )

    @Test fun lowSwapWithMemoryStallIsARisk() {
        assertTrue(LmkRisk.atRisk(reading()))
        assertFalse("swap above lmkd's 10 %", LmkRisk.atRisk(reading(swapFreeGb = 1.0)))
        assertFalse("no stall", LmkRisk.atRisk(reading(psiSome = 2.0)))
    }

    @Test fun freeSwapIsCappedByAvailableMemoryAsLmkdDoes() {
        assertEquals((0.25 * gb).toLong(), LmkRisk.freeSwapKb(reading(availableGb = 0.25, swapFreeGb = 3.0)))
        assertTrue(LmkRisk.swapLow(reading(availableGb = 0.25, swapFreeGb = 3.0)))
    }

    @Test fun withoutPsiItFallsBackToAvailableMemory() {
        assertFalse(LmkRisk.atRisk(reading(psiSome = null)))
        assertTrue(LmkRisk.atRisk(reading(availableGb = 0.5, psiSome = null)))
    }

    @Test fun noSwapIsNeverLowSwap() {
        assertFalse(LmkRisk.atRisk(reading().copy(swapTotalKb = 0, swapFreeKb = 0)))
    }

    @Test fun oneWarningPerEpisodeAndACooldown() {
        val gate = LmkRisk.Gate()
        assertTrue(gate.warn(true, 0))
        assertFalse(gate.warn(true, 2_000))
        assertFalse(gate.warn(false, 30_000))
        assertFalse("episode not over yet", gate.warn(true, 40_000))
        assertFalse(gate.warn(false, 110_000))
        assertFalse("cooldown", gate.warn(true, 120_000))
        assertFalse(gate.warn(false, 200_000))
        assertTrue("new episode after the cooldown", gate.warn(true, LmkRisk.COOLDOWN_MS + 1))
    }

    @Test fun theLineIsGreppable() {
        val line = LmkRisk.line(reading())
        assertTrue(line, line.startsWith("DROIDDECK_LMK_RISK action=warn short=memory_and_swap availableMb=2048 totalMb=12288 " +
            "swapFreeMb=512 swapTotalMb=8192 lmkdSwapFreeMb=512 "))
        assertTrue(line, line.endsWith("psiSomeAvg10=25.0 psiFullAvg10=5.0"))
    }

    private fun pixel10a(availableMb: Long, swapFreeMb: Long) = LmkRisk.Reading(
        totalKb = 7_571L * 1024, availableKb = availableMb * 1024, swapTotalKb = 3_785L * 1024,
        swapFreeKb = swapFreeMb * 1024, psiSome = null, psiFull = null,
    )

    @Test fun theWarningSaysWhetherSwapIsShortToo() {
        val roomInSwap = pixel10a(availableMb = 365, swapFreeMb = 1_261)
        assertTrue(LmkRisk.atRisk(roomInSwap))
        assertFalse(LmkRisk.swapShort(roomInSwap))
        assertEquals("memory", LmkRisk.short(roomInSwap))
        assertEquals(mapOf<String, Any?>("short" to "memory", "swapFreeMb" to 1_261L, "swapTotalMb" to 3_785L,
            "lmkdSwapFreeMb" to 365L, "availableMb" to 365L, "totalMb" to 7_571L, "psiSomeAvg10" to null, "psiFullAvg10" to null),
            LmkRisk.fields(roomInSwap))

        val bothShort = pixel10a(availableMb = 52, swapFreeMb = 286)
        assertTrue(LmkRisk.atRisk(bothShort))
        assertTrue(LmkRisk.swapShort(bothShort))
        assertEquals("memory_and_swap", LmkRisk.fields(bothShort)["short"])
        assertEquals(286L, LmkRisk.fields(bothShort)["swapFreeMb"])
        assertEquals(52L, LmkRisk.fields(bothShort)["lmkdSwapFreeMb"])
        assertTrue(LmkRisk.line(bothShort).contains(" short=memory_and_swap availableMb=52 "))
        assertFalse(LmkRisk.swapShort(pixel10a(availableMb = 52, swapFreeMb = 0).copy(swapTotalKb = 0)))
    }

    @Test fun theCacheLineCountsMegabytes() {
        assertEquals("DROIDDECK_APP_CACHE dropped=coil reason=session_start mb=96", AppCaches.line("session_start", 96 shl 20))
    }
}
