package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinuxFexTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("fex").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun modesAreStoredBesideTheApp() {
        assertEquals(LinuxFex.AUTO, LinuxFex.mode(dir))
        assertTrue(LinuxFex.setMode(dir, LinuxFex.ON))
        assertEquals(LinuxFex.ON, LinuxFex.mode(dir))
        assertTrue(LinuxFex.setMode(dir, LinuxFex.OFF))
        assertEquals(LinuxFex.OFF, LinuxFex.mode(dir))
        assertTrue(LinuxFex.setMode(dir, LinuxFex.AUTO))
        assertFalse(File(dir, "fex").exists())
        assertFalse(LinuxFex.setMode(dir, "sometimes"))
        File(dir, "fex").writeText("garbage")
        assertEquals(LinuxFex.AUTO, LinuxFex.mode(dir))
    }
}
