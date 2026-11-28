package com.droiddeck.launcher.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties.StateDescription
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.SessionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The phone-sized screens (a Pixel 10a is 411 x 923 dp), with a picture of each in build/reports/phone-ui. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h923dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneUiLayoutTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var root: View
    private var theme = Themes.LANNER
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun openOnSteam() { SetupTabs.current = SETUP_STEAM }
    @After fun leaveOnSteam() { SetupTabs.current = SETUP_STEAM }

    @Test fun homeKeepsTheBuildLineOffTheNavigation() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            root = LocalView.current
            DroidDeckTheme(theme) {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides true) {
                    FocusGlideHost {
                        LauncherLayout(
                            true, LocalPalette.current.line, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            navigation = { BottomBar(state(), "steam", {}, {}, actions(), Modifier) },
                        ) {
                            SteamHome(state(), actions(), {}, Modifier.fillMaxSize())
                            SelfUpdateBar()
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("rail-setup").assertIsDisplayed()
        compose.onNodeWithTag("self-update").assertDoesNotExist()
        compose.onAllNodesWithText(buildLine()).assertCountEquals(0)
        screenshot("home")
    }

    @Test fun steamSettingsPutEachToggleOnItsTitleRow() {
        showSettings()
        assertTogglesOnTitleRows()
        compose.onNodeWithTag("setting-offline").assert(SemanticsMatcher.expectValue(StateDescription, context.getString(R.string.widgets_on)))
        compose.onNodeWithText(context.getString(R.string.setup_offline_on, "rad1enc3")).assertIsDisplayed()
        compose.onNodeWithTag("chips-scroll").assertIsDisplayed()
        screenshot("settings-steam")
    }

    @Test fun offlineModeOffNamesOnlyTheAccount() {
        showSettings(offline = false)
        compose.onNodeWithTag("setting-offline").assert(SemanticsMatcher.expectValue(StateDescription, context.getString(R.string.widgets_off)))
        compose.onNodeWithText(context.getString(R.string.setup_signed_in, "rad1enc3")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.setup_offline_on, "rad1enc3")).assertDoesNotExist()
    }

    @Test fun longDescriptionsOpenOnTap() {
        showSettings()
        val hint = context.getString(R.string.mode_shader_precache_hint)
        val closed = compose.onNodeWithText(hint).getUnclippedBoundsInRoot()
        compose.onNodeWithText(hint).performScrollTo().performClick()
        val open = compose.onNodeWithText(hint).getUnclippedBoundsInRoot()
        assertTrue("the description grows when tapped", open.bottom - open.top > closed.bottom - closed.top)
        compose.onNodeWithText(context.getString(R.string.widgets_hint_less)).assertExists()
        screenshot("settings-steam-description-open")
        compose.onNodeWithText(hint).performClick()
        val again = compose.onNodeWithText(hint).getUnclippedBoundsInRoot()
        assertEquals(closed.bottom - closed.top, again.bottom - again.top)
    }

    @Test fun largestAppScaleKeepsTogglesOnTheirTitleRows() {
        showSettings(scale = AppUiPrefs.scales.max())
        assertTogglesOnTitleRows()
        screenshot("settings-steam-scale-${AppUiPrefs.scales.max()}")
    }

    @Test fun secondThemeDrawsTheSameRows() {
        theme = Themes.PAPER
        showSettings()
        assertTogglesOnTitleRows()
        screenshot("settings-steam-paper")
    }

    @Test fun advancedEndsWithTheBuildLineAndCopiesIt() {
        showSettings()
        compose.onAllNodesWithText(context.getString(R.string.setup_tab_advanced)).onFirst().performClick()
        compose.onNodeWithTag("about-build").performScrollTo().assertIsDisplayed().assertTextEquals(buildLine())
        compose.onNodeWithTag("about-build-copy").performScrollTo().performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { assertEquals(buildLine(), clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        screenshot("settings-about")
    }

    @Test @Config(qualifiers = "w923dp-h411dp-420dpi")
    fun bootCardShowsItsStepsAndFoldsTheDetails() {
        compose.mainClock.autoAdvance = false
        val flavor = "Proton says it's Windows. Nobody tell the game."
        val lines = listOf(
            "Proton + stock FEX", "Lanner ae32618 · Nov 28 02:30 UTC · perf/lanner-id",
            "on     godot-render,warmup,all,compat-quiet,memory,adpf-all,two-frames,one-limiter,native-hz,quick-tap",
            "fex    no Proton follows Auto", "memory 3720 MB free · swap 1178 of 3785 MB used",
        )
        compose.setContent {
            root = LocalView.current
            DroidDeckTheme(theme) {
                LoadingOverlay("", -1, "", "", false, LoadingState.Topic.STEAM_STARTING, readable = false, onCancel = {}, info = lines, flavor = flavor)
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(context.getString(R.string.load_step, 4, 4)).assertIsDisplayed()
        compose.onAllNodesWithTag("load-step-done").assertCountEquals(3)
        compose.onAllNodesWithTag("load-step-running").assertCountEquals(1)
        compose.onAllNodesWithTag("load-step-todo").assertCountEquals(0)
        compose.onNodeWithText(flavor).assertIsDisplayed()
        compose.onNodeWithTag("boot-memory").assertIsDisplayed().assertTextEquals(lines.last())
        compose.onNodeWithTag("boot-details-lines").assertDoesNotExist()
        screenshot("boot-card")
        compose.onNodeWithTag("boot-details").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("boot-details-lines").assertIsDisplayed().assertTextEquals(lines.subList(2, 4).joinToString("\n"))
        screenshot("boot-card-details")
    }

    @Test @Config(qualifiers = "w923dp-h411dp-420dpi")
    fun bootCardFillsOneSegmentPerStepFromTheLeft() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            root = LocalView.current
            DroidDeckTheme(theme) { LoadingOverlay("Downloading", 40, "", "", false, LoadingState.Topic.SESSION, readable = true) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(context.getString(R.string.load_step, 2, 4)).assertIsDisplayed()
        compose.onAllNodesWithTag("load-step-done").assertCountEquals(1)
        compose.onAllNodesWithTag("load-step-running").assertCountEquals(1)
        compose.onAllNodesWithTag("load-step-todo").assertCountEquals(2)
        val label = compose.onNodeWithText(context.getString(R.string.load_step, 2, 4)).getUnclippedBoundsInRoot()
        val first = compose.onNodeWithTag("load-step-done").getUnclippedBoundsInRoot()
        val running = compose.onNodeWithTag("load-step-running").getUnclippedBoundsInRoot()
        assertEquals(label.left, first.left)
        assertTrue(running.left > first.right)
        screenshot("boot-card-step-2")
    }

    private fun assertTogglesOnTitleRows() {
        val pane = compose.onNodeWithTag("chips-scroll").getUnclippedBoundsInRoot()
        for ((tag, title) in listOf(
            "setting-offline" to R.string.setup_offline, "setting-mangoapp" to R.string.mode_mangoapp,
            "setting-returnaftergame" to R.string.mode_return_after_game, "setting-shaderprecache" to R.string.mode_shader_precache,
        )) {
            val toggle = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val label = compose.onAllNodesWithText(context.getString(title)).onFirst().getUnclippedBoundsInRoot()
            assertTrue("$tag is right of its title", toggle.left >= label.right)
            assertTrue("$tag is at the right edge", pane.right - toggle.right < 16.dp)
            assertTrue("$tag is level with the first line of its title", toggle.top <= label.top && toggle.bottom > label.top + 16.dp)
        }
    }

    private fun showSettings(scale: Int = AppUiPrefs.DEFAULT_SCALE, offline: Boolean = true) {
        val sheet = ModeSettings(
            mode = SessionService.MODE_STEAM, resolution = "auto", hdr = false, hdrReason = null,
            touchMode = "auto", suspendPolicy = "auto", oscMode = "auto", directAudio = null, mic = null,
            returnAfterGame = true, shaderPrecache = true, mangoapp = true,
        )
        val sheetActions = ModeSettingsActions(
            onResolution = {}, onHdr = {}, onTouch = {}, onSuspendPolicy = {}, onOsc = {},
            onInputSettings = {}, onDirectAudio = {}, onMic = {}, onDismiss = {},
        )
        compose.setContent {
            root = LocalView.current
            DroidDeckTheme(theme, appScale = scale) {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides true) {
                    FocusGlideHost {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp).padding(top = 12.dp)) {
                            SetupPanel(
                                state(offline),
                                actions { _, tab, extras -> ModeSheetRows(sheet, sheetActions, tab, extras) },
                                onOpenDeveloperOptions = {}, onRequestWirelessAdb = {}, onPairWirelessDebugging = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildLine() = context.getString(R.string.version_line_installed, VersionLine.forThisApp(context))

    private fun state(offline: Boolean = false) = FrontEndState(
        installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
        offlineAccount = "rad1enc3", offline = offline, frameGenLabel = "",
        logsEnabled = false, steamGames = emptyList(), running = null,
    )

    private fun actions(
        modeSheet: @androidx.compose.runtime.Composable (String, String, SheetExtras) -> Unit = { _, _, _ -> },
    ) = FrontEndActions(
        onPlay = {}, onSteamGame = {},
        onResume = {}, onSteamSettings = {}, modeSheet = modeSheet,
        onRuntime = {}, onFrameGenPick = {},
        onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
        onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> },
    )

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val drawn = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(drawn))
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            image.eraseColor(Themes.byId(theme).background.toArgb())
            Canvas(image).drawBitmap(drawn, 0f, 0f, null)
            val file = File("build/reports/phone-ui/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            drawn.recycle()
            image.recycle()
        }
    }
}
