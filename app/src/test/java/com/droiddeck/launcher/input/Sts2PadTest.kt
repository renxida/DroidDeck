package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Sts2PadTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller", "session")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private data class Screen(val width: Int, val height: Int, val density: Float)

    private val screens = listOf(Screen(2424, 1080, 2.625f), Screen(1920, 1080, 2.625f), Screen(2400, 1080, 2.625f), Screen(2340, 1080, 2.625f),
        Screen(1600, 720, 2f), Screen(2560, 1600, 2f), Screen(2048, 1536, 2f), Screen(1280, 570, 1.4f), Screen(1280, 800, 1f), Screen(2208, 1840, 2.625f))

    private fun box(e: InputSurfaceElement, s: Screen) = GamepadGeometry.Box((e.x + e.width / 2f) * s.width, (e.y + e.height / 2f) * s.height,
        e.width * s.width, e.height * s.height)

    private fun elements(s: Screen) = Sts2Pad.elements(s.width, s.height, s.density)

    @Test fun everyButtonIsInsideTheScreenAndStorable() {
        for (s in screens) {
            val elements = elements(s)
            assertEquals(Sts2Pad.THUMB_IDS + Sts2Pad.HUD_IDS + "builtin:keyboard", elements.map { it.id }.toSet())
            assertEquals(elements.size, elements.map { it.id }.distinct().size)
            for (e in elements) {
                assertEquals("$s ${e.id}", e, e.bounded())
                val b = box(e, s)
                assertTrue("$s ${e.id}", b.left >= -.5f && b.top >= -.5f && b.right <= s.width + .5f && b.bottom <= s.height + .5f)
            }
        }
    }

    @Test fun theSwipePadIsOnTheLeftAndXBYAOnTheRight() {
        for (s in screens) {
            val elements = elements(s)
            val swipe = elements.single { it.id == Sts2Pad.SWIPE }
            assertEquals(InputElementKind.SWIPE, swipe.kind)
            assertTrue("$s", box(swipe, s).cx < s.width / 2f)
            val face = elements.filter { it.id in Sts2Pad.FACE_IDS }
            assertEquals(setOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y), face.map { it.control }.toSet())
            for (e in face) assertTrue("$s ${e.id}", box(e, s).cx > s.width / 2f)
            assertTrue(elements.none { it.id in Sts2Pad.DPAD_IDS || it.kind == InputElementKind.DPAD })
            assertTrue(elements.none { it.kind == InputElementKind.STICK || it.kind == InputElementKind.TRACKPAD })
        }
    }

    @Test fun noButtonOverlapsAnotherOrTheFoldToggleCorner() {
        for (s in screens) {
            val elements = elements(s)
            // Tiles meet edge to edge, so a rounding sliver of overlap does not count.
            fun inner(e: InputSurfaceElement) = box(e, s).let { GamepadGeometry.Box(it.cx, it.cy, it.width - 1f, it.height - 1f) }
            for ((i, a) in elements.withIndex()) for (b in elements.drop(i + 1)) {
                // The swipe pad lies under the buttons on the game's icons it meets (the draw pile); they come after it, so they are hit first.
                if (a.id == Sts2Pad.SWIPE && b.id in Sts2Pad.HUD_IDS) continue
                assertFalse("$s ${a.id} overlaps ${b.id}", inner(a).overlaps(inner(b)))
            }
            assertEquals(Sts2Pad.SWIPE, elements.first().id)
            val corner = 64 * s.density
            val toggle = GamepadGeometry.Box(corner / 2f, corner / 2f, corner, corner)
            for (e in elements) assertFalse("$s ${e.id}", box(e, s).overlaps(toggle))
        }
    }

    @Test fun onAPixel10TheHudButtonsSitOnTheGameIconsAndTheThumbsWhereTheMockupPutsThem() {
        val s = Screen(2424, 1080, 2.625f)
        val elements = elements(s).associateBy { it.id }
        // Centres of the game's icons on a Pixel 10 screenshot (combat, death screen), in screen pixels.
        val icons = mapOf("legacy:lt" to (303f to 1023f), "legacy:rt" to (2112f to 1023f), "legacy:rb" to (2117f to 828f),
            Sts2Pad.END_TURN to (1964f to 888f), "legacy:select" to (1951f to 39f), "legacy:lb" to (2036f to 39f), "legacy:start" to (2115f to 39f))
        for ((id, at) in icons) {
            val b = box(elements.getValue(id), s)
            assertEquals(id, at.first, b.cx, 20f)
            assertEquals(id, at.second, b.cy, 20f)
        }
        // left, top, right, bottom in screen pixels
        val mockup = mapOf(Sts2Pad.SWIPE to listOf(0f, 330f, 620f, 1080f),
            "legacy:x" to listOf(1904f, 90f, 2144f, 370f), "legacy:b" to listOf(2144f, 90f, 2424f, 370f),
            "legacy:y" to listOf(1904f, 370f, 2144f, 720f), "legacy:a" to listOf(2144f, 370f, 2424f, 720f))
        for ((id, edges) in mockup) {
            val b = box(elements.getValue(id), s)
            assertEquals(id, edges, listOf(b.left, b.top, b.right, b.bottom).map { kotlin.math.round(it) })
        }
        assertTrue(box(elements.getValue("legacy:a"), s).bottom < box(elements.getValue(Sts2Pad.END_TURN), s).top)
        assertTrue(box(elements.getValue("builtin:keyboard"), s).right < (2424 - 1080 * 16f / 9f) / 2f)
    }

    @Test fun xBYAAreBigSeeThroughTilesWithNoGapsBetweenThemOrTheScreensEdge() {
        for (s in screens) {
            val e = elements(s).associateBy { it.id }
            fun b(id: String) = box(e.getValue("legacy:$id"), s)
            val tolerance = 1f
            assertEquals("$s", s.width.toFloat(), b("b").right, tolerance)
            assertEquals("$s", s.width.toFloat(), b("a").right, tolerance)
            assertEquals("$s", b("x").right, b("b").left, tolerance)
            assertEquals("$s", b("y").right, b("a").left, tolerance)
            assertEquals("$s", b("x").left, b("y").left, tolerance)
            assertEquals("$s", b("x").bottom, b("y").top, tolerance)
            assertEquals("$s", b("b").bottom, b("a").top, tolerance)
            assertEquals("$s", b("x").top, b("b").top, tolerance)
            assertEquals("$s", b("y").bottom, b("a").bottom, tolerance)
            for (id in listOf("x", "b", "y", "a")) {
                assertEquals("$s $id", InputButtonShape.RECTANGLE, e.getValue("legacy:$id").buttonShape)
                e.getValue("legacy:$id").opacity.let { assertTrue("$s $id $it", it >= .4f && it < 1f) }
                assertTrue("$s $id", b(id).width >= 84 * s.density - tolerance)
                assertTrue("$s $id", b(id).height >= 72 * s.density - tolerance)
            }
            // Below the game's icons at the top and above its buttons at the bottom (End Turn among them) wherever they meet the grid.
            val hud = Sts2Pad.HUD_IDS.map { box(e.getValue(it), s) }.filter { it.right > b("x").left }
            for (h in hud) if (h.cy < s.height / 2f) assertTrue("$s", b("x").top >= h.bottom) else assertTrue("$s", b("y").bottom <= h.top)
        }
    }

    @Test fun theSwipePadIsAFaintZoneFromTheLeftEdgeToTheBottomBelowTheKeyboardKey() {
        for (s in screens) {
            val e = elements(s).associateBy { it.id }
            val zone = e.getValue(Sts2Pad.SWIPE)
            val b = box(zone, s)
            assertEquals("$s", 0f, b.left, 1f)
            assertEquals("$s", s.height.toFloat(), b.bottom, 1f)
            assertTrue("$s", b.top >= box(e.getValue("builtin:keyboard"), s).bottom)
            assertTrue("$s", b.width >= 150 * s.density - 1f || b.width >= s.width * .3f - 1f)
            assertTrue("$s", b.height >= 150 * s.density - 1f)
            assertTrue("$s", zone.opacity < .7f && zone.opacity >= .2f)
            assertEquals(InputButtonShape.ROUNDED, zone.buttonShape)
            assertEquals("Swipe", zone.name)
        }
    }

    /** Where the pad put the d-pad and A / B / Y before they were raised (stock pad at 2424 × 1080, density 2.625). */
    private val lowThumbs = mapOf(
        "legacy:up" to floatArrayOf(.05414604f, .5347222f), "legacy:right" to floatArrayOf(.103960395f, .64652777f),
        "legacy:down" to floatArrayOf(.05414604f, .7583333f), "legacy:left" to floatArrayOf(.0043316833f, .64652777f),
        "legacy:y" to floatArrayOf(.89820546f, .5347222f), "legacy:b" to floatArrayOf(.9480198f, .64652777f), "legacy:a" to floatArrayOf(.89820546f, .7583333f))

    private val classicThumbs = Sts2Pad.DPAD_IDS + Sts2Pad.FACE_IDS

    private fun lowPad(): SavedInputLayout = Sts2Pad.classic().let { pad ->
        pad.copy(elements = pad.elements.map { e -> lowThumbs[e.id]?.let { e.copy(x = it[0], y = it[1], width = .047648516f, height = .10694444f) } ?: e })
    }

    @Test fun aPadSavedWithTheThumbsLowGetsThemRaisedButAThumbThePersonMovedStays() {
        assertEquals(Sts2Pad.classic(), Sts2Pad.raiseThumbs(lowPad()))
        val moved = lowPad().let { pad -> pad.copy(elements = pad.elements.map { if (it.id == "legacy:a") it.copy(x = .5f, y = .5f) else it }) }
        val raised = Sts2Pad.raiseThumbs(moved)
        assertEquals(listOf(.5f, .5f), raised.elements.single { it.id == "legacy:a" }.let { listOf(it.x, it.y) })
        assertEquals(Sts2Pad.classic().elements.filterNot { it.id == "legacy:a" }, raised.elements.filterNot { it.id == "legacy:a" })
        for (pad in listOf(Sts2Pad.classic(), Sts2Pad.layout())) assertSame(pad, Sts2Pad.raiseThumbs(pad))
        val other = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }
        assertSame(other, Sts2Pad.raiseThumbs(other))
    }

    /** A stock pad as an earlier version saved it: its thumbs where, and as, that version put them (X only if it had one). */
    private fun earlierPad(stock: List<InputSurfaceElement>): SavedInputLayout = Sts2Pad.classic().let { pad ->
        val before = stock.associateBy { it.id }
        pad.copy(elements = pad.elements.mapNotNull { e -> if (e.id in classicThumbs) before[e.id]?.let {
            e.copy(x = it.x, y = it.y, width = it.width, height = it.height, buttonShape = it.buttonShape, opacity = it.opacity) } else e })
    }

    @Test fun everyEarlierPadGetsTheTilesAndAnXOnce() {
        val earlier = Sts2Pad.earlier()
        assertEquals(Sts2Pad.classic().elements, earlier.last())
        for ((i, stock) in earlier.withIndex()) {
            val pad = earlierPad(stock)
            assertEquals("$i", i >= earlier.size - 2, pad.elements.any { it.id == "legacy:x" })
            if (i < earlier.lastIndex) assertTrue("$i", pad.elements.filter { it.id in classicThumbs }.all { it.opacity == 1f })
            assertEquals("$i", Sts2Pad.classic(), Sts2Pad.raiseThumbs(pad))
        }
        val removed = Sts2Pad.classic().let { it.copy(elements = it.elements.filterNot { e -> e.id == "legacy:y" }) }
        assertEquals(removed.elements.map { it.id }.toSet(), Sts2Pad.raiseThumbs(removed).elements.map { it.id }.toSet())
    }

    @Test fun aPadSavedWithTheRaisedCirclesGetsTheTilesButAMovedOrReshapedThumbStays() {
        val circles = earlierPad(Sts2Pad.earlier()[1])
        assertTrue(circles.elements.filter { it.id in classicThumbs }.all { it.buttonShape == InputButtonShape.CIRCLE })
        assertEquals(Sts2Pad.classic(), Sts2Pad.raiseThumbs(circles))
        assertEquals(Sts2Pad.classic(), Sts2Pad.raiseThumbs(earlierPad(Sts2Pad.earlier().first())))
        assertTrue(Sts2Pad.earlier().dropLast(2).all { stock -> stock.none { it.id == "legacy:x" } })
        val moved = circles.copy(elements = circles.elements.map { if (it.id == "legacy:a") it.copy(x = .5f, y = .5f) else it })
        val tiled = Sts2Pad.raiseThumbs(moved)
        assertEquals(InputButtonShape.CIRCLE, tiled.elements.single { it.id == "legacy:a" }.buttonShape)
        assertEquals(listOf(.5f, .5f), tiled.elements.single { it.id == "legacy:a" }.let { listOf(it.x, it.y) })
        assertEquals(Sts2Pad.classic().elements.filterNot { it.id == "legacy:a" }, tiled.elements.filterNot { it.id == "legacy:a" })
    }

    @Test fun versionSixteenToNineteenLibrariesGetTheSwipePadInPlaceOfTheirUntouchedDpad() {
        for ((version, stock) in listOf(16 to Sts2Pad.earlier()[1], 17 to Sts2Pad.earlier()[2], 18 to Sts2Pad.earlier()[3], 19 to Sts2Pad.earlier()[4])) {
            reset()
            val fresh = InputLibraries.read(context)
            val old = fresh.withLayout(earlierPad(stock))
            prefs.edit().putString("library", JSONObject(InputLibraries.encode(old)).put("version", version).toString()).commit()
            val upgraded = InputLibraries.read(context)
            assertEquals("$version", Sts2Pad.layout(), upgraded.layouts.single { it.id == Sts2Pad.LAYOUT })
            assertTrue("$version", upgraded.layouts.none { it.id == Sts2Pad.CLASSIC } && upgraded.bindings.none { it.id == Sts2Pad.CLASSIC_BINDINGS })
            assertEquals("$version", Sts2Pad.bindings(), upgraded.preset(Sts2Pad.BINDINGS))
            assertEquals(old.profiles, upgraded.profiles)
            assertEquals(upgraded, InputLibraries.read(context))
        }
    }

    @Test fun aPadWhoseDpadThePersonEditedGetsTheSwipePadAndKeepsTheirPadAsTheClassicChoice() {
        val classic = Sts2Pad.classic()
        val endTurn = classic.elements.single { it.id == Sts2Pad.END_TURN }
        val edited = classic.copy(elements = classic.elements.map { when (it.id) {
            "legacy:up" -> it.copy(y = it.y - .05f)
            "legacy:a" -> it.copy(buttonShape = InputButtonShape.CIRCLE)
            Sts2Pad.END_TURN -> it.copy(x = .6f)
            else -> it
        } })
        val fresh = InputLibraries.read(context)
        val remapped = Sts2Pad.bindings().copy(elements = Sts2Pad.bindings().elements - Sts2Pad.SWIPE + (Sts2Pad.END_TURN to InputAction.Native(InputControl.A)))
        val old = fresh.withLayout(edited).withPreset(remapped).validate()
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(old)).put("version", 19).toString()).commit()
        val upgraded = InputLibraries.read(context)

        // The swipe pad replaces the d-pad and the tiles; the End Turn the person moved stays where they put it.
        val pad = upgraded.layouts.single { it.id == Sts2Pad.LAYOUT }
        assertEquals(Sts2Pad.layout().elements.map { if (it.id == Sts2Pad.END_TURN) endTurn.copy(x = .6f) else it }, pad.elements)
        assertEquals(InputAction.Native(), upgraded.preset(Sts2Pad.BINDINGS).elements[Sts2Pad.SWIPE])
        assertEquals(InputAction.Native(InputControl.A), upgraded.preset(Sts2Pad.BINDINGS).elements[Sts2Pad.END_TURN])
        assertEquals(Sts2Pad.LAYOUT, Sts2Pad.forGame(upgraded, Sts2Pad.APP_ID).activeLayout(InputRole.PRIMARY)?.id)

        // Their pad is kept whole, with its bindings, and can be chosen as the primary layout again.
        val kept = upgraded.layouts.single { it.id == Sts2Pad.CLASSIC }
        assertEquals(edited.elements, kept.elements)
        assertTrue(Sts2Pad.owns(kept) && kept.supports(InputRole.PRIMARY))
        val keptBindings = upgraded.preset(Sts2Pad.CLASSIC_BINDINGS)
        assertEquals(Sts2Pad.CLASSIC, keptBindings.layoutId)
        assertEquals(remapped.elements, keptBindings.elements)
        val chosen = Sts2Pad.forGame(upgraded.selectPart(InputRole.PRIMARY, Sts2Pad.CLASSIC_BINDINGS).validate(), Sts2Pad.APP_ID)
        assertEquals(Sts2Pad.CLASSIC, chosen.activeLayout(InputRole.PRIMARY)?.id)
        assertEquals(InputAction.Native(), chosen.compileActive().elementAction(InputRole.PRIMARY, "legacy:up", InputControl.UP))
        assertEquals(classic.elements, InputLibraries.defaultPositions(context, upgraded, kept))

        assertEquals(old.profiles, upgraded.profiles)
        assertEquals(upgraded, InputLibraries.read(context))
    }

    @Test fun aPadWithADpadButtonRemovedCountsAsEdited() {
        val classic = Sts2Pad.classic()
        val removed = classic.copy(elements = classic.elements.filterNot { it.id == "legacy:x" })
        val upgraded = Sts2Pad.withSwipe(InputLibraries.read(context).withLayout(removed).validate())
        assertEquals(Sts2Pad.layout(), upgraded.layouts.single { it.id == Sts2Pad.LAYOUT })
        assertEquals(removed.elements, upgraded.layouts.single { it.id == Sts2Pad.CLASSIC }.elements)
        assertSame(upgraded, Sts2Pad.withSwipe(upgraded))
    }

    @Test fun aSwipePadSentWithoutItsBindingStillPressesTheDpad() {
        val bare = Sts2Pad.layout().copy(elements = Sts2Pad.layout().elements.filterNot { it.id == Sts2Pad.SWIPE })
        InputLibraries.putRemoteLayout(context, InputLibraries.encodeLayout(bare))
        assertNull(InputLibraries.read(context).preset(Sts2Pad.BINDINGS).elements[Sts2Pad.SWIPE])
        InputLibraries.putRemoteLayout(context, InputLibraries.encodeLayout(Sts2Pad.layout()))
        val profile = Sts2Pad.forGame(InputLibraries.read(context), Sts2Pad.APP_ID).compileActive()
        assertEquals(InputAction.Native(), profile.elementAction(InputRole.PRIMARY, Sts2Pad.SWIPE, InputControl.LS))
    }

    @Test fun xSendsTheControllersXSoThePotionsOpen() {
        val profile = Sts2Pad.forGame(InputLibraries.read(context), Sts2Pad.APP_ID).compileActive()
        assertEquals(InputAction.Native(), profile.elementAction(InputRole.PRIMARY, "legacy:x", InputControl.X))
    }

    @Test fun aShapeThePersonChoseSurvivesPlacementAndTheStockShapeFollowsTheStrip() {
        val layout = InputLibraries.read(context).layouts.single { it.id == Sts2Pad.LAYOUT }
        val density = context.resources.displayMetrics.density
        val rounded = layout.copy(elements = layout.elements.map { if (it.id == "legacy:b") it.copy(buttonShape = InputButtonShape.ROUNDED) else it })
        val placed = OnScreenLayout.elements(context, rounded, 2424, 1080, Rect()).associateBy { it.id }
        assertEquals(InputButtonShape.ROUNDED, placed.getValue("legacy:b").buttonShape)
        val narrow = OnScreenLayout.elements(context, layout, 1920, 1080, Rect()).associateBy { it.id }
        assertEquals(InputButtonShape.RECTANGLE, narrow.getValue("legacy:a").buttonShape)
        assertEquals(Sts2Pad.elements(1920, 1080, density), OnScreenLayout.elements(context, layout, 1920, 1080, Rect()))
    }

    @Test fun versionFifteenLibrariesGetTheSwipePadInPlaceOfTheirUntouchedLowDpadOnce() {
        val fresh = InputLibraries.read(context)
        val old = fresh.withLayout(lowPad())
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(old)).put("version", 15).toString()).commit()
        val upgraded = InputLibraries.read(context)
        assertEquals(Sts2Pad.layout(), upgraded.layouts.single { it.id == Sts2Pad.LAYOUT })
        assertEquals(old.profiles, upgraded.profiles)
        assertEquals(upgraded, InputLibraries.read(context))
    }

    @Test fun theLibraryOffersThePadAndUsesItForSlayTheSpire2Only() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.single { it.id == Sts2Pad.LAYOUT }
        assertEquals(Sts2Pad.layout(), layout)
        assertTrue(layout.supports(InputRole.PRIMARY))
        assertEquals(Sts2Pad.LAYOUT, library.preset(Sts2Pad.BINDINGS).layoutId)
        assertEquals(library, InputLibraries.decode(InputLibraries.encode(library)))

        val sts2 = Sts2Pad.forGame(library, Sts2Pad.APP_ID)
        assertEquals(Sts2Pad.LAYOUT, sts2.validate().activeLayout(InputRole.PRIMARY)?.id)
        val profile = sts2.compileActive()
        assertEquals(InputAction.Native(InputControl.Y), profile.elementAction(InputRole.PRIMARY, Sts2Pad.END_TURN, InputControl.A))
        assertEquals(InputAction.Command(InputCommand.KEYBOARD), profile.elementAction(InputRole.PRIMARY, "builtin:keyboard", InputControl.A))
        assertEquals(InputAction.Native(), profile.elementAction(InputRole.PRIMARY, Sts2Pad.SWIPE, InputControl.LS))
        for (control in listOf(InputControl.LT, InputControl.RT, InputControl.RB, InputControl.LB, InputControl.SELECT, InputControl.START, InputControl.X, InputControl.A))
            assertEquals(InputAction.Native(), profile.elementAction(InputRole.PRIMARY, "legacy:${control.id}", control))

        for (game in listOf(null, "1145360")) assertEquals(InputLibraries.OVERLAY, Sts2Pad.forGame(library, game).activeLayout(InputRole.PRIMARY)?.id)
        val chosen = library.selectPart(InputRole.PRIMARY, library.activePart(InputRole.PRIMARY).bindings)
        assertEquals(InputLibraries.OVERLAY, Sts2Pad.forGame(chosen, Sts2Pad.APP_ID).activeLayout(InputRole.PRIMARY)?.id)
        assertEquals(Sts2Pad.LAYOUT, library.selectPart(InputRole.PRIMARY, Sts2Pad.BINDINGS).validate().activeLayout(InputRole.PRIMARY)?.id)
    }

    @Test fun versionFourteenLibrariesGainThePadOnceAndADeletedPadStaysDeleted() {
        val fresh = InputLibraries.read(context)
        val old = fresh.copy(layouts = fresh.layouts.filterNot { it.id == Sts2Pad.LAYOUT }, bindings = fresh.bindings.filterNot { it.id == Sts2Pad.BINDINGS }).validate()
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(old)).put("version", 14).toString()).commit()
        val upgraded = InputLibraries.read(context)
        assertEquals(old.profiles, upgraded.profiles)
        assertEquals(old.layouts, upgraded.layouts.filterNot { it.id == Sts2Pad.LAYOUT })
        assertEquals(Sts2Pad.layout(), upgraded.layouts.single { it.id == Sts2Pad.LAYOUT })
        assertEquals(upgraded, InputLibraries.read(context))
        InputLibraries.update(context) { it.removeLayout(Sts2Pad.LAYOUT) }
        assertTrue(InputLibraries.read(context).layouts.none { it.id == Sts2Pad.LAYOUT })
        assertEquals(InputLibraries.OVERLAY, Sts2Pad.forGame(InputLibraries.read(context), Sts2Pad.APP_ID).activeLayout(InputRole.PRIMARY)?.id)
    }

    @Test fun onScreenPlacementFollowsTheGamesPictureAndKeepsButtonsThePersonMoved() {
        val layout = InputLibraries.read(context).layouts.single { it.id == Sts2Pad.LAYOUT }
        val density = context.resources.displayMetrics.density
        for ((w, h) in listOf(2424 to 1080, 1920 to 1080, 1600 to 720))
            assertEquals(Sts2Pad.elements(w, h, density), OnScreenLayout.elements(context, layout, w, h, Rect()))
        val moved = layout.copy(elements = layout.elements.map { if (it.id == "legacy:a") it.copy(x = .5f, y = .5f) else it })
        val placed = OnScreenLayout.elements(context, moved, 2424, 1080, Rect())
        assertEquals(.5f, placed.single { it.id == "legacy:a" }.x, .02f)
        assertEquals(.5f, placed.single { it.id == "legacy:a" }.y, .02f)
        assertEquals(Sts2Pad.elements(2424, 1080, density).single { it.id == "legacy:lt" }, placed.single { it.id == "legacy:lt" })
        assertEquals(Sts2Pad.layout().elements, InputLibraries.defaultPositions(context, InputLibraries.read(context), layout))
    }
}
