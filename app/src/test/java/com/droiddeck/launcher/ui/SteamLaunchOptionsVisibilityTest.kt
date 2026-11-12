package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLaunchOptionsVisibilityTest {
    private fun game() =
        Library.SteamGame(42, "Test game", null, "main")

    @Test fun nativeSteamGamesExposeLaunchOptionsOutsideShortcutPicking() {
        assertTrue(canEditSteamLaunchOptions(game(), shortcutPicker = false))
    }

    @Test fun shortcutPickingDoesNotExposeSteamLaunchOptions() {
        assertFalse(canEditSteamLaunchOptions(game(), shortcutPicker = true))
    }
}
