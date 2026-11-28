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
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.telemetry.FrameLogComponent
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    @get:Rule val tmp = org.junit.rules.TemporaryFolder()
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
        show(game)
        compose.onNodeWithTag("home-card").assertIsDisplayed()
        compose.onNodeWithTag("play-steam").assertIsDisplayed()
        compose.onNodeWithTag("home-branch-beta").assertIsSelected()
        compose.onNodeWithTag("play-last").performClick()
        compose.onNodeWithTag("home-branch-stable").performClick()
        compose.onNodeWithTag("home-branch-stable").assertIsSelected()
        compose.onNodeWithTag("play-last").performClick()
        compose.runOnIdle { assertEquals(listOf("beta", "stable"), played) }
    }

    @Test fun withNothingKnownTheCardShowsTheColourBlockAndOnlyMemory() {
        show(game)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-card-art").assertDoesNotExist()
        compose.onNodeWithTag("home-last-played").assertDoesNotExist()
        compose.onNodeWithTag("home-last-session").assertDoesNotExist()
        screenshot("home-draft")
    }

    @Test fun theCardShowsSteamsCachedHeaderLastPlayedAndTheLastSessionsFrames() {
        val cache = File(SessionPrefs.activeSteamRoot(context), "appcache/librarycache")
        val header = File(cache, "${game.appId}/header.jpg").apply { parentFile?.mkdirs() }
        header.outputStream().use { fixtureArt().compress(Bitmap.CompressFormat.JPEG, 90, it) }
        assertEquals(header, homeCardArt(cache, game))
        val decoded = decodeCardArt(header, 230)!!
        assertEquals(230, decoded.width)
        sessionWithGameFrames(File(context.filesDir, "logs/2026-11-27-01-steam"))
        val playedAt = System.currentTimeMillis() / 1000 - 2 * 3600
        show(Library.SteamGame(game.appId, game.name, null, "", lastPlayed = playedAt))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-card-art").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-last-session").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-last-played").assertIsDisplayed()
        compose.onNodeWithTag("home-last-session").assertTextEquals(context.getString(R.string.home_last_session, "50", "40"))
        screenshot("home-draft-art")
    }

    @Test fun aSessionWithoutEnoughGameFramesSaysNothing() {
        val steamOnly = tmp.newFolder("2026-11-27-02-steam")
        steamOnly.resolve(FrameLogComponent.FILE).writeText("# frames t0_wall_ms=1000 t0_mono_ns=1 source=gamescope\nt_ms,frametime_us\n" +
            "# t_ms=10 engine=GAMESCOPE pid=10 steam=1 output=1616x720@60000mHz\n" + (1..2000).joinToString("") { "${it * 16},16000\n" })
        assertNull(LastSession.of(steamOnly))
        val frames = LastSession.of(sessionWithGameFrames(tmp.newFolder("2026-11-27-03-steam")))!!
        assertEquals(50.0, frames.fps, 0.5)
        assertEquals(40.0, frames.p99Ms, 0.5)
    }

    /** 1960 frames of 20 ms and 40 of 40 ms from a game: about 50 fps with a 99th percentile of 40 ms. */
    private fun sessionWithGameFrames(dir: File): File {
        dir.mkdirs()
        val times = (1..2000).map { if (it % 50 == 0) 40_000 else 19_592 }
        dir.resolve(FrameLogComponent.FILE).writeText("# frames t0_wall_ms=1000 t0_mono_ns=1 source=gamescope\nt_ms,frametime_us\n" +
            "# t_ms=10 engine=GAMESCOPE pid=10 steam=1 output=1616x720@60000mHz\n" +
            "# t_ms=20 engine=GAMESCOPE pid=77 steam=0 output=1616x720@60000mHz\n" +
            times.withIndex().joinToString("") { (i, us) -> "${100 + i * 20},$us\n" })
        return dir
    }

    private fun fixtureArt(): Bitmap {
        val art = Bitmap.createBitmap(460, 215, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(art)
        val paint = android.graphics.Paint()
        for (band in 0 until 5) {
            paint.color = android.graphics.Color.HSVToColor(floatArrayOf(band * 60f, 0.7f, 0.8f))
            canvas.drawRect(band * 92f, 0f, (band + 1) * 92f, 215f, paint)
        }
        return art
    }

    private fun show(g: Library.SteamGame) {
        compose.setContent {
            root = LocalView.current
            DroidDeckTheme(Themes.LANNER) {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides true) {
                    FocusGlideHost {
                        SteamHomeDraft(state(g), actions, {}, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File("build/reports/phone-ui/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private fun state(g: Library.SteamGame) = FrontEndState(

        installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
        offlineAccount = null, offline = false, frameGenLabel = "",
        logsEnabled = false, steamGames = listOf(g), running = null,
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
