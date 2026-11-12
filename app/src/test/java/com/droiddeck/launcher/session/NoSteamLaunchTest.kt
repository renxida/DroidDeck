package com.droiddeck.launcher.session

import com.droiddeck.launcher.frontend.Library
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NoSteamLaunchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clean() {
        File(context.filesDir, "game-environment.json").delete()
        NoSteamLaunch.names.clear()
    }

    private val steamGame = Library.SteamGame(413150, "Stardew Valley", null, "internal")
    private val other = Library.SteamGame(1172470, "Apex Legends", null, "internal")

    @Test fun eachGameKeepsTheSettingUnderItsOwnId() {
        assertEquals("413150", NoSteamLaunch.scope(steamGame))
        assertNull(NoSteamLaunch.scope(Library.SteamGame(0, "nothing", null, "internal")))
    }

    @Test fun theSettingIsRememberedPerGame() {
        assertFalse(NoSteamLaunch.enabled(context, steamGame))
        GameEnvironmentStore.setGameNoSteam(context, "413150", true)
        assertTrue(NoSteamLaunch.enabled(context, steamGame))
        assertTrue(NoSteamLaunch.enabled(context, 413150L))
        assertFalse(NoSteamLaunch.enabled(context, other))
        GameEnvironmentStore.setGameNoSteam(context, "413150", false)
        assertFalse(NoSteamLaunch.enabled(context, steamGame))
    }

    @Test fun eachGameStartsInItsOwnProtonWithoutSteam() {
        val steam = NoSteamLaunch.command(steamGame)!!
        assertEquals(NoSteamLaunch.PROTON_RUN, steam.program)
        assertEquals(listOf("--app", "413150"), steam.args)
        assertNull(NoSteamLaunch.command(Library.SteamGame(0, "nothing", null, "internal")))
    }

    @Test fun theRunningSessionIsNamedAfterItsGame() {
        val command = NoSteamLaunch.command(steamGame)!!
        assertNull(NoSteamLaunch.nameFor(command.program, command.args))
        NoSteamLaunch.remember(command)
        assertEquals("Stardew Valley", NoSteamLaunch.nameFor(command.program, command.args))
        assertNull(NoSteamLaunch.nameFor("/usr/bin/retroarch", emptyList()))
        assertNull(NoSteamLaunch.nameFor(NoSteamLaunch.PROTON_RUN, listOf("/x/a.exe")))
    }
}
