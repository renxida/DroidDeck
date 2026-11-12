package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLaunchOptionsVisibilityTest {
    private fun game(library: String = "main") =
        Library.SteamGame(42, "Test game", null, library)

    @Test fun nativeSteamGamesExposeLaunchOptionsOutsideShortcutPicking() {
        assertTrue(canEditSteamLaunchOptions(game(), shortcutPicker = false))
    }

    @Test fun pickerAndAddedGamesDoNotExposeSteamLaunchOptions() {
        assertFalse(canEditSteamLaunchOptions(game(), shortcutPicker = true))
        assertFalse(canEditSteamLaunchOptions(game(library = Library.ADDED), shortcutPicker = false))
    }
}
