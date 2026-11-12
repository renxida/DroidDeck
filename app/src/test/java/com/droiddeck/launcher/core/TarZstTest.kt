package com.droiddeck.launcher.core

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarZstTest {
    private val dir = Files.createTempDirectory("tarzst").toFile()
    private val out = File(dir, "out")

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private class Item(val name: String, val bytes: ByteArray? = null, val mode: Int = "644".toInt(8))

    private fun archive(vararg items: Item): ByteArray {
        val buffer = ByteArrayOutputStream()
        TarArchiveOutputStream(ZstdOutputStream(buffer)).use { tar ->
            for (item in items) {
                val entry = TarArchiveEntry(item.name, true)
                entry.mode = item.mode
                item.bytes?.let { entry.size = it.size.toLong() }
                tar.putArchiveEntry(entry)
                item.bytes?.let { tar.write(it) }
                tar.closeArchiveEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun extract(bytes: ByteArray) = TarZst.extract(ByteArrayInputStream(bytes), out)

    @Test fun filesDirectoriesAndExecutableBitsArrive() {
        val big = ByteArray(300_000) { (it * 31).toByte() }
        val ok = extract(archive(
            Item("usr/", mode = "755".toInt(8)),
            Item("usr/bin/tool", "#!/bin/sh\n".toByteArray(), "755".toInt(8)),
            Item("usr/share/data.bin", big),
            Item("empty/dir/", mode = "755".toInt(8)),
            Item("nested/without/parent/entries.txt", "x".toByteArray()),
        ))

        assertTrue(ok)
        assertEquals("#!/bin/sh\n", File(out, "usr/bin/tool").readText())
        assertTrue(File(out, "usr/bin/tool").canExecute())
        assertArrayEquals(big, File(out, "usr/share/data.bin").readBytes())
        assertFalse(File(out, "usr/share/data.bin").canExecute())
        assertTrue(File(out, "empty/dir").isDirectory)
        assertEquals("x", File(out, "nested/without/parent/entries.txt").readText())
    }

    @Test fun entriesThatEscapeTheDestinationAreSkippedAndTheRestExtracted() {
        val ok = extract(archive(
            Item("../escaped.txt", "bad".toByteArray()),
            Item("inside/../../also-escaped.txt", "bad".toByteArray()),
            Item("kept.txt", "good".toByteArray()),
        ))

        assertTrue(ok)
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(File(dir, "also-escaped.txt").exists())
        assertEquals("good", File(out, "kept.txt").readText())
    }

    @Test fun anExistingFileIsReplaced() {
        File(out, "a.txt").apply { parentFile!!.mkdirs(); writeText("old and longer") }

        assertTrue(extract(archive(Item("a.txt", "new".toByteArray()))))
        assertEquals("new", File(out, "a.txt").readText())
    }

    @Test fun aTruncatedOrForeignStreamFails() {
        val whole = archive(Item("data.bin", ByteArray(200_000).also { java.util.Random(7).nextBytes(it) }))
        assertFalse(extract(whole.copyOf(whole.size / 2)))
        assertFalse(extract("not a zstd stream".toByteArray()))
    }
}
