package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFromSteamClientTest {
    @Test fun onlyTheClientsOwnInstallsAreDroppedOnASwitch() {
        val games = listOf(
            Library.SteamGame(440, "Team Fortress 2", null, "Steam library"),
            Library.SteamGame(1, "God of War", null, Library.ADDED, gameId = 9_000_000_001L),
        )
        assertEquals(listOf("God of War"), games.filterNot(Library::fromSteamClient).map { it.name })
    }
}
