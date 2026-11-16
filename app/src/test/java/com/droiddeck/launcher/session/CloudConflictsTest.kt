package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConflictsTest {
    private val local = "2245bc3fcb9e196d7cdc98be5abe9745a6a591e4"
    private val remote = "9eb24f0b2a127307928c12518205880c3ad41c00"

    private fun log(vararg extra: String) = listOf(
        "[2026-11-15 20:49:11] [AppID 2868840] profile1/saves/progress.save has local changes but mismatch - creating a conflict. Local/Remote SHA: bc8d90e341cc71f592919a63748cb704193503a5 / $remote, Timestamps: 1794616344 / 1794791954, persist state: 0 / 0",
        "[2026-11-15 20:49:11] [AppID 2868840] Download failed due to conflicts in build list",
        "[2026-11-15 20:51:30] [AppID 2868840] profile1/saves/progress.save already marked as conflicting. Local/Remote SHA: $local / $remote, Local/Remote persist state: 0 / 0",
    ).plus(extra).joinToString("\n")

    private fun cache(sha: String) = "\"2868840\"\n{\n\t\"ChangeNumber\"\t\t\"132\"\n" +
        "\t\"profile.save\"\n\t{\n\t\t\"sha\"\t\t\"14d440f75ea9219798836ca78da21289d8c3a349\"\n\t}\n" +
        "\t\"profile1/saves/progress.save\"\n\t{\n\t\t\"size\"\t\t\"3\"\n\t\t\"sha\"\t\t\"$sha\"\n\t\t\"syncstate\"\t\t\"4\"\n\t}\n}\n"

    @Test fun theNewestLineGivesTheOpenConflict() {
        val open = CloudConflicts.parse(log())
        assertEquals(listOf(CloudConflicts.Conflict("2868840", "profile1/saves/progress.save", local, remote)), open)
    }

    @Test fun aLaterSuccessfulSyncClosesIt() {
        assertTrue(CloudConflicts.parse(log("[2026-11-15 20:56:06] [AppID 2868840] Successfully synced to ChangeNumber 136")).isEmpty())
    }

    @Test fun readsAndDropsOneCacheEntry() {
        val c = cache(local)
        assertEquals(local, CloudConflicts.cachedSha(c, "profile1/saves/progress.save"))
        val without = CloudConflicts.withoutEntry(c, "profile1/saves/progress.save")
        assertNull(CloudConflicts.cachedSha(without, "profile1/saves/progress.save"))
        assertEquals("14d440f75ea9219798836ca78da21289d8c3a349", CloudConflicts.cachedSha(without, "profile.save"))
    }

    private fun guest(fileText: String, cacheSha: String, logText: String): File {
        val root = Files.createTempDirectory("cloud").toFile()
        val steam = File(root, "root/.local/share/Steam")
        File(steam, "logs").mkdirs()
        File(steam, "logs/cloud_log.txt").writeText(logText)
        val dir = File(steam, "userdata/155889176/2868840")
        File(dir, "remote/profile1/saves").mkdirs()
        File(dir, "remote/profile1/saves/progress.save").writeText(fileText)
        File(dir, "remotecache.vdf").writeText(cache(cacheSha))
        val game = File(root, "root/.local/share/SlayTheSpire2/steam/76561198116154904/profile1/saves")
        game.mkdirs()
        File(game, "progress.save").writeText(fileText)
        File(game, "progress.save.backup").writeText(fileText)
        return root
    }

    private fun sha1(text: String) = java.security.MessageDigest.getInstance("SHA-1")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun anOpenConflictTakesTheCloudCopyAndKeepsThePhones() {
        val text = "phone"
        val sha = sha1(text)
        val logText = log().replace(local, sha)
        val root = guest(text, sha, logText)
        assertEquals(listOf("2868840:profile1/saves/progress.save"), CloudConflicts.apply(root, now = 0))
        val dir = File(root, "root/.local/share/Steam/userdata/155889176/2868840")
        assertFalse(File(dir, "remote/profile1/saves/progress.save").exists())
        assertNull(CloudConflicts.cachedSha(File(dir, "remotecache.vdf").readText(), "profile1/saves/progress.save"))
        assertFalse(File(root, "root/.local/share/SlayTheSpire2/steam/76561198116154904/profile1/saves/progress.save").exists())
        val backups = File(root, "root/lanner-cloud-backups/2868840").listFiles()!!.single()
        assertEquals(text, File(backups, "steam/profile1/saves/progress.save").readText())
        assertEquals(text, File(backups, "game/profile1/saves/progress.save").readText())
        assertTrue(File(dir, "remotecache.vdf.bak").isFile)
    }

    @Test fun aSettledFileIsLeftAlone() {
        val root = guest("cloud", remote, log())
        assertTrue(CloudConflicts.apply(root).isEmpty())
        assertTrue(File(root, "root/.local/share/Steam/userdata/155889176/2868840/remote/profile1/saves/progress.save").isFile)
    }

    @Test fun aFileThatChangedSinceTheLogIsLeftAlone() {
        val root = guest("newer phone write", local, log())
        assertTrue(CloudConflicts.apply(root).isEmpty())
    }
}
