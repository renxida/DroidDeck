package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h923dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeDraftTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var root: View
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val game = Library.SteamGame(2868840, "Slay the Spire 2", null, "")
    private val played = mutableListOf<String>()

    @Test fun theDraftIsOffUntilSwitchedOn() {
        assertFalse(SessionPrefs.homeDraft(context))
        HomeDraft.load(context)
        assertFalse(HomeDraft.on)
        HomeDraft.set(context, true)
        assertTrue(SessionPrefs.homeDraft(context))
        HomeDraft.set(context, false)
        assertFalse(HomeDraft.on)
    }

    @Test fun onePlayButtonStartsTheBranchTheToggleShows() {
        compose.setContent {
            root = LocalView.current
            DroidDeckTheme(Themes.LANNER) {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides true) {
                    FocusGlideHost {
                        SteamHomeDraft(state, actions, {}, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    }
                }
            }
        }
        compose.onNodeWithTag("home-card").assertIsDisplayed()
        compose.onNodeWithTag("play-steam").assertIsDisplayed()
        compose.onNodeWithTag("home-branch-beta").assertIsSelected()
        compose.onNodeWithTag("play-last").performClick()
        compose.onNodeWithTag("home-branch-stable").performClick()
        compose.onNodeWithTag("home-branch-stable").assertIsSelected()
        compose.onNodeWithTag("play-last").performClick()
        compose.runOnIdle { assertEquals(listOf("beta", "stable"), played) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File("build/reports/phone-ui/home-draft.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private val state = FrontEndState(
        installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
        offlineAccount = null, offline = false, frameGenLabel = "",
        logsEnabled = false, steamGames = listOf(game), running = null,
    )

    private val actions = FrontEndActions(
        onPlay = {}, onSteamGame = { played += "beta" }, onSteamGameStable = { played += "stable" },
        onResume = {}, onSteamSettings = {},
        onRuntime = {}, onFrameGenPick = {},
        onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
        onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> },
    )
}
