package com.droiddeck.launcher.input

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SessionInputPointerTest.WriterShadow::class])
class SecondScreenProfileMigrationTest {
    private lateinit var activity: SessionActivity
    private val previousMode = SessionState.mode
    private val previousPanel = SessionState.secondScreenMode
    private val previousDisplay = SessionState.secondScreenDisplay
    private val previousDeckPad = SessionState.deckPad

    @Before fun setUp() {
        SessionState.secondScreenMode = SecondScreenMode.NONE
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val displays = activity.getSystemService(DisplayManager::class.java)
        shadowOf(displays.getDisplay(Display.DEFAULT_DISPLAY)).setFlags(Display.FLAG_PRESENTATION)
        ReflectionHelpers.setField(activity, "displayManager", displays)
        ReflectionHelpers.setField(activity, "started", true)
        val display: MutableState<Int> = ReflectionHelpers.getField(activity, "selectedSecondScreenDisplay\$delegate")
        display.value = Display.DEFAULT_DISPLAY
    }

    @After fun tearDown() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "closeSecondScreen", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        SessionState.mode = previousMode
        SessionState.secondScreenMode = previousPanel
        SessionState.secondScreenDisplay = previousDisplay
        SessionState.deckPad = previousDeckPad
    }

    @Test fun firstOpeningKeepsTheLibraryAndOpensTheControllerPanel() {
        for (deckPad in listOf(false, true)) {
            val original = customLibrary()
            open(deckPad)
            assertEquals(original, InputLibraries.read(activity))
            assertNull(SessionPrefs.secondScreenPanel(activity))
            val dashboard = ReflectionHelpers.callInstanceMethod<List<*>>(activity, "bottomPages").isNotEmpty()
            val expected = when {
                deckPad -> SecondScreenMode.DECK_CONTROLS
                dashboard -> SecondScreenMode.DASHBOARD
                else -> SecondScreenMode.KEYBOARD_TRACKPAD
            }
            assertEquals(expected, SessionState.secondScreenMode)
        }
    }

    @Test fun gamesReopenTheLastPanel() {
        customLibrary()
        SessionPrefs.setSecondScreenPanel(activity, SecondScreenMode.CUSTOM_CONTROLS.id)
        open(deckPad = true)
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SessionState.secondScreenMode)
    }

    @Test fun theSharedPanelChoiceCarriesOverToGamesExceptTheForcedProfileControls() {
        val prefs = activity.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().putString("secondScreenPanel", SecondScreenMode.TERMINAL.id).commit()
        assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(activity))
        prefs.edit().putString("secondScreenPanel", SecondScreenMode.CUSTOM_CONTROLS.id).commit()
        assertNull(SessionPrefs.secondScreenPanel(activity))
    }

    @Test fun trackpadAndDeckPanelsUseTheirStockBindingsWithoutChangingTheProfile() {
        for (mode in listOf(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.DECK_CONTROLS)) {
            val original = customLibrary()
            SessionState.deckPad = true
            val stock = original.preset(mode.bindings!!)
            show(mode)
            assertEquals(original, InputLibraries.read(activity))
            assertEquals(stock.layoutId, routedLayout()?.id)
            assertEquals(stock.elements, routedProfile().elements[InputRole.SECONDARY].orEmpty())
            show(SecondScreenMode.CUSTOM_CONTROLS)
            assertEquals("custom.layout", routedLayout()?.id)
            assertEquals(InputAction.Keys(listOf(57)), routedProfile().elementAction(InputRole.SECONDARY, "custom.button", InputControl.A))
        }
    }

    @Test fun aDeckPanelLeftOverWithoutTheDeckPadOpensTheFallbackInstead() {
        customLibrary()
        SessionState.deckPad = false
        show(SecondScreenMode.DECK_CONTROLS)
        val fallback = ReflectionHelpers.callInstanceMethod<SecondScreenMode>(activity, "openingPanel")
        assertNotEquals(SecondScreenMode.DECK_CONTROLS, fallback)
        assertEquals(fallback, SessionState.secondScreenMode)
        assertNotEquals(InputLibraries.DECK, routedLayout()?.id)
    }

    private fun customLibrary(): InputLibrary {
        session()
        return InputLibraries.update(activity) { library ->
            val element = InputSurfaceElement(InputControl.A, .3f, .2f, .2f, .2f, "custom.button")
            val layout = SavedInputLayout("custom.layout", "Custom controls", listOf(element), screen = InputLayoutScreen.SECONDARY)
            val bindings = SavedInputBindings("custom.bindings", "Custom actions", false, layout.id,
                elements = mapOf(element.id to InputAction.Keys(listOf(57))))
            val profile = library.profile(InputProfile.GAME)
            library.withLayout(layout).withPreset(bindings)
                .withProfile(profile.copy(parts = profile.parts + (InputRole.SECONDARY to InputPart(bindings.id, layout.id))))
                .copy(selections = emptyMap())
        }
    }

    private fun routedProfile(): InputProfile = ReflectionHelpers.getField(activity, "inputProfile")

    private fun routedLayout(): SavedInputLayout? = ReflectionHelpers.getField<MutableState<SavedInputLayout?>>(activity, "secondaryInputLayout\$delegate").value

    private fun session() {
        SessionState.mode = SessionService.MODE_STEAM
        activity.intent = Intent().putExtra(SessionService.EXTRA_MODE, SessionState.mode)
    }

    private fun open(deckPad: Boolean) {
        session()
        SessionState.deckPad = deckPad
        show(ReflectionHelpers.callInstanceMethod<SecondScreenMode>(activity, "openingPanel"))
    }

    private fun show(mode: SecondScreenMode) =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showSecondScreen", ReflectionHelpers.ClassParameter.from(SecondScreenMode::class.java, mode))
}
