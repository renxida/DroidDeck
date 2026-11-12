package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEntriesTest {
    @Test fun eachGameShowsOnceUnderItsSource() {
        val steam = Library.SteamGame(440, "Team Fortress 2", null, "Steam library")
        val added = Library.SteamGame(-5, "Cyberpunk", null, Library.ADDED)
        val entries = libraryEntries(listOf(steam, added, steam))
        assertEquals(listOf("app:440", "app:-5"), entries.map { it.key })
        assertEquals(listOf(SOURCE_STEAM, Library.ADDED), entries.map { it.source })
    }

    @Test fun sourcesListSteamFirstAndAddedLastOnlyWhenTheyHaveGames() {
        val added = Library.SteamGame(-5, "Cyberpunk", null, Library.ADDED)
        val steam = Library.SteamGame(440, "Team Fortress 2", null, "Steam library")
        assertEquals(listOf(Library.ADDED), librarySources(libraryEntries(listOf(added))))
        assertEquals(listOf(SOURCE_STEAM, Library.ADDED), librarySources(libraryEntries(listOf(added, steam))))
    }
}
