package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryArchTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun aScanReadsTheClientItWasGivenNotTheSelectedOne() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setSteamClientArch(app, "arm64")
        val apps = File(SessionPrefs.steamRoot(app, "x86_64"), "steamapps").apply { mkdirs() }
        File(apps, "appmanifest_440.acf").writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"440\"\n\t\"name\"\t\t\"Team Fortress 2\"\n\t\"StateFlags\"\t\t\"4\"\n}\n")
        assertEquals(listOf("Team Fortress 2"), Library.launchableGames(app, arch = "x86_64").map { it.name })
        assertEquals(emptyList<String>(), Library.launchableGames(app, arch = "arm64").map { it.name })
    }

    @Test fun aGameTheOtherClientSharesIsListedFromItsFiles() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val apps = File(SessionPrefs.steamRoot(app, "x86_64"), "steamapps").apply { mkdirs() }
        File(apps, "common/Team Fortress 2").mkdirs()
        val manifest = "\"AppState\"\n{\n\t\"appid\"\t\t\"440\"\n\t\"name\"\t\t\"Team Fortress 2\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Team Fortress 2\"\n}\n"
        File(apps, "appmanifest_440.acf").writeText(manifest)
        val view = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app), "root/.droiddeck-x64/shared/x86-64/steamapps").apply { mkdirs() }
        File(view, "appmanifest_440.acf").writeText(manifest)
        val shared = Library.steamGames(app, arch = "arm64").single()
        assertEquals("Team Fortress 2", shared.name)
        assertEquals(File(apps, "common/Team Fortress 2"), shared.gameFiles)
        assertEquals(listOf("Team Fortress 2"), Library.steamGames(app, arch = "x86_64").map { it.name })
    }
}
