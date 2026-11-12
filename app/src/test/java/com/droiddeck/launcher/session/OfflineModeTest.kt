package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineModeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("offline-mode").toFile()

    @After fun tearDown() {
        root.deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    private fun loginUsers(base: File = root) = File(base, "root/.local/share/Steam/config/loginusers.vdf")

    private fun write(file: File, vararg lines: String) {
        file.parentFile!!.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun user(id: String, name: String, vararg extra: String) = arrayOf(
        "\t\"$id\"",
        "\t{",
        "\t\t\"AccountName\"\t\t\"$name\"",
        "\t\t\"PersonaName\"\t\t\"${name.uppercase()}\"",
    ) + extra + arrayOf("\t}")

    private fun users(vararg blocks: Array<String>) = arrayOf("\"users\"", "{") + blocks.flatten() + arrayOf("}")

    private fun block(text: String, name: String): String {
        val lines = text.lines()
        val at = lines.indexOfFirst { it.contains("\"AccountName\"\t\t\"$name\"") }
        val end = (at until lines.size).first { lines[it].trim() == "}" }
        return lines.subList(at, end).joinToString("\n")
    }

    private fun enable(on: Boolean) {
        OfflineMode.setEnabled(context, on)
        assertEquals(on, OfflineMode.enabled(context))
    }

    @Test fun enablingFlipsTheExistingSwitches() {
        val file = loginUsers()
        write(file, *users(user("1", "alpha", "\t\t\"WantsOfflineMode\"\t\t\"0\"", "\t\t\"SkipOfflineModeWarning\"\t\t\"0\"")))
        enable(true)

        OfflineMode.apply(context, root)

        val expected = users(user("1", "alpha", "\t\t\"WantsOfflineMode\"\t\t\"1\"", "\t\t\"SkipOfflineModeWarning\"\t\t\"1\""))
        assertEquals(expected.joinToString("\n", postfix = "\n"), file.readText())
        assertFalse(File(file.parentFile, "loginusers.vdf.staged").exists())
    }

    @Test fun missingSwitchesAreAddedUnderTheAccountName() {
        val file = loginUsers()
        write(file, *users(user("1", "alpha", "\t\t\"MostRecent\"\t\t\"1\"")))
        enable(true)

        OfflineMode.apply(context, root)

        val lines = file.readText().lines()
        val at = lines.indexOf("\t\t\"AccountName\"\t\t\"alpha\"")
        assertEquals("\t\t\"WantsOfflineMode\"\t\t\"1\"", lines[at + 1])
        assertEquals("\t\t\"SkipOfflineModeWarning\"\t\t\"1\"", lines[at + 2])
    }

    @Test fun disablingTurnsTheSwitchesBackOffAndLeavesAnUpToDateFileAlone() {
        val file = loginUsers()
        write(file, *users(user("1", "alpha", "\t\t\"WantsOfflineMode\"\t\t\"1\"", "\t\t\"SkipOfflineModeWarning\"\t\t\"1\"")))
        enable(false)

        OfflineMode.apply(context, root)
        val once = file.readText()
        assertTrue(once.contains("\"WantsOfflineMode\"\t\t\"0\""))
        assertTrue(once.contains("\"SkipOfflineModeWarning\"\t\t\"0\""))

        OfflineMode.apply(context, root)
        assertEquals(once, file.readText())
    }

    @Test fun everyRememberedAccountGetsTheSwitchEvenWhenAnotherAlreadyHasIt() {
        val file = loginUsers()
        write(file, *users(
            user("1", "alpha", "\t\t\"WantsOfflineMode\"\t\t\"0\"", "\t\t\"SkipOfflineModeWarning\"\t\t\"0\""),
            user("2", "beta", "\t\t\"MostRecent\"\t\t\"1\""),
        ))
        enable(true)

        OfflineMode.apply(context, root)

        val text = file.readText()
        for (name in listOf("alpha", "beta")) {
            assertTrue("$name wants offline", block(text, name).contains("\"WantsOfflineMode\"\t\t\"1\""))
            assertTrue("$name skips the warning", block(text, name).contains("\"SkipOfflineModeWarning\"\t\t\"1\""))
        }
        assertEquals(1, Regex("\"WantsOfflineMode\"").findAll(block(text, "beta")).count())
    }

    @Test fun theX64ClientsAccountsAreSwitchedToo() {
        val x64 = File(root, "root/.droiddeck-x64/.local/share/Steam/config/loginusers.vdf")
        write(x64, *users(user("1", "alpha", "\t\t\"WantsOfflineMode\"\t\t\"0\"", "\t\t\"SkipOfflineModeWarning\"\t\t\"0\"")))
        enable(true)

        OfflineMode.apply(context, root)

        assertTrue(x64.readText().contains("\"WantsOfflineMode\"\t\t\"1\""))
        assertFalse(loginUsers().exists())
    }

    @Test fun theOfflineAccountIsTheRememberedPersona() {
        val file = loginUsers(LinuxRuntime.rootDir(context))
        assertNull(OfflineMode.account(context))

        write(file, *users(user("1", "alpha", "\t\t\"RememberPassword\"\t\t\"0\"")))
        assertNull(OfflineMode.account(context))

        write(file, *users(user("1", "alpha", "\t\t\"RememberPassword\"\t\t\"1\"")))
        assertEquals("ALPHA", OfflineMode.account(context))
    }
}
