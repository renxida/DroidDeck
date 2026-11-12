package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrimaryButtonFocusContrastTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun aFocusedPrimaryButtonStillClicks() {
        var clicks = 0
        compose.setContent {
            DroidDeckTheme(Themes.ELECTRIC) { PrimaryButton("Play") { clicks++ } }
        }
        val button = compose.onNodeWithText("Play")
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused()
        button.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun focusMovesIntoAndBackOutOfPrimaryButtons() {
        val focus = FrontFocus()
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides focus) {
                    Column {
                        SecondaryButton("Tab") {}
                        PrimaryButton("Play Steam", main = true, large = true) {}
                    }
                }
            }
        }
        val tab = compose.onNodeWithText("Tab")
        val play = compose.onNodeWithText("Play Steam")
        tab.performSemanticsAction(SemanticsActions.RequestFocus)
        tab.assertIsFocused()
        compose.runOnIdle { focus.primary.requestFocus() }
        play.assertIsFocused()
        tab.assertIsNotFocused()
        tab.performSemanticsAction(SemanticsActions.RequestFocus)
        tab.assertIsFocused()
    }
}
