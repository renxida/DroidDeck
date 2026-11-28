package com.droiddeck.launcher.session

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressureTest {
    @Test fun aRefusedOpenIsNotTriedAgain() {
        var opens = 0
        val psi = MemoryPressure { opens++; throw FileNotFoundException("/proc/pressure/memory: open failed: EACCES (Permission denied)") }
        assertNull(psi.text())
        assertNull(psi.text())
        assertNull(psi.text())
        assertEquals(1, opens)
        assertTrue(psi.unreadable)
    }

    @Test fun aReadableFileIsReadEveryTime() {
        var opens = 0
        val psi = MemoryPressure { "some avg10=${opens++}.00 avg60=0.00 avg300=0.00 total=1\n" }
        assertEquals(0.0, ExitCause.parsePsi(psi.text()!!).first)
        assertEquals(1.0, ExitCause.parsePsi(psi.text()!!).first)
        assertFalse(psi.unreadable)
    }

    @Test fun aFailedReadOfAnOpenFileIsTriedAgain() {
        var opens = 0
        val psi = MemoryPressure { if (opens++ == 0) throw IOException("EIO") else "some avg10=2.00 total=1\n" }
        assertNull(psi.text())
        assertEquals(2.0, ExitCause.parsePsi(psi.text()!!).first)
    }
}
