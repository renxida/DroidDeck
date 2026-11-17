package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class VersionLineTest {
    private val utc = ZoneId.of("UTC")
    private val t = 1_794_924_000_000L // 2026-11-17 14:00 UTC

    @Test fun installedLineNamesVersionCommitTimeAndEngine() {
        assertEquals("0.7.1 · 7b9eef4 · Nov 17 14:00 · engine 105",
            VersionLine.installed("0.7.1", "7b9eef4abcdef0123", t, "105", utc))
    }

    @Test fun missingPartsAreLeftOut() {
        assertEquals("0.7.1", VersionLine.installed("0.7.1", "", 0L, null, utc))
    }

    @Test fun waitingBuildIsNamedByCommitAndTime() {
        assertEquals("21d85c7 · Nov 17 14:00", VersionLine.build("21d85c7931bd", t, utc))
        assertNull(VersionLine.build("", 0L, utc))
    }

    @Test fun engineTagBecomesItsNumber() {
        assertEquals("105", VersionLine.engineNumber("native-engine-105"))
        assertEquals("custom", VersionLine.engineNumber("custom"))
        assertNull(VersionLine.engineNumber(""))
        assertNull(VersionLine.engineNumber(null))
    }
}
