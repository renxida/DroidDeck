package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEntriesTest {
    @Test fun eachGameShowsOnce() {
        val tf2 = Library.SteamGame(440, "Team Fortress 2", null, "Steam library")
        val portal = Library.SteamGame(400, "Portal", null, "internal")
        val entries = libraryEntries(listOf(tf2, portal, tf2))
        assertEquals(listOf("app:440", "app:400"), entries.map { it.key })
        assertEquals(listOf("Team Fortress 2", "Portal"), entries.map { it.title })
    }
}
