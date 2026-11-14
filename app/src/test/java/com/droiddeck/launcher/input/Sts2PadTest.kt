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

    @Test fun theThumbsHoldOnlyTheDpadOnTheLeftAndABYOnTheRight() {
        for (s in screens) {
            val elements = elements(s)
            val thumbs = elements.filter { it.id in Sts2Pad.THUMB_IDS }
            val dpad = setOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)
            val face = setOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)
            assertEquals(dpad + face, thumbs.map { it.control }.toSet())
            for (e in thumbs) {
                val centre = box(e, s).cx
                if (e.control in dpad) assertTrue("$s ${e.id}", centre < s.width / 2f) else assertTrue("$s ${e.id}", centre > s.width / 2f)
            }
            assertTrue(elements.none { it.kind == InputElementKind.STICK || it.kind == InputElementKind.TRACKPAD })
        }
    }

    @Test fun noButtonOverlapsAnotherOrTheFoldToggleCorner() {
        for (s in screens) {
            val elements = elements(s)
            // Tiles meet edge to edge, so a rounding sliver of overlap does not count.
            fun inner(e: InputSurfaceElement) = box(e, s).let { GamepadGeometry.Box(it.cx, it.cy, it.width - 1f, it.height - 1f) }
            for ((i, a) in elements.withIndex()) for (b in elements.drop(i + 1))
                assertFalse("$s ${a.id} overlaps ${b.id}", inner(a).overlaps(inner(b)))
            val corner = 64 * s.density
            val toggle = GamepadGeometry.Box(corner / 2f, corner / 2f, corner, corner)
            for (e in elements) assertFalse("$s ${e.id}", box(e, s).overlaps(toggle))
        }
    }

    @Test fun onAPixel10TheHudButtonsSitOnTheGameIconsAndTheThumbsInTheBlackBars() {
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
        // The thumbs rest where a thumb lies on a held phone: the line between Left | Right (X | B) about 17 mm in from each
        // side, past the black bars (about 15 mm), so the thumb does not pull back to reach them.
        val mm = 2.625f * 160f / 25.4f
        for ((id, edge) in listOf("legacy:left" to { b: GamepadGeometry.Box -> b.right }, "legacy:b" to { b: GamepadGeometry.Box -> 2424 - b.left }))
            assertEquals(id, 17f, edge(box(elements.getValue(id), s)) / mm, 1f)
        for (id in listOf("legacy:up", "legacy:down", "legacy:y", "legacy:a"))
            assertEquals(id, 30f, box(elements.getValue(id), s).width / mm, 2f)
        assertTrue(box(elements.getValue("builtin:keyboard"), s).right < (2424 - 1080 * 16f / 9f) / 2f)
    }

    @Test fun theThumbsSitInTheMiddleOfTheEmptyStripsWithinReach() {
        for (s in screens.filter { it.width > it.height * 16f / 9f }) {
            val elements = elements(s).associateBy { it.id }
            val strip = (s.width - s.height * 16f / 9f) / 2f
            val dpad = listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left").map { box(elements.getValue(it), s) }
            val face = listOf("legacy:y", "legacy:x", "legacy:b", "legacy:a").map { box(elements.getValue(it), s) }
            for (cluster in listOf(dpad, face)) {
                val cy = cluster.map { it.cy }.average().toFloat()
                assertTrue("$s $cy", cy in s.height * .35f..s.height * .62f)
            }
            assertEquals("$s", dpad.map { it.cy }.average(), face.map { it.cy }.average(), 1.0)
            val reach = maxOf(strip, 110 * s.density) + 84 * s.density
            assertTrue("$s", dpad.map { it.cx }.average() < reach && face.map { it.cx }.average() > s.width - reach)
        }
    }

    @Test fun inWideStripsTheThumbsAreBigSeeThroughTilesWithNoGapsReachingUnderTheThumbs() {
        val wide = screens.filter { (it.width - it.height * 16f / 9f) / 2f >= 56 * it.density }
        assertTrue(Screen(2424, 1080, 2.625f) in wide)
        for (s in wide) {
            val e = elements(s).associateBy { it.id }
            fun b(id: String) = box(e.getValue("legacy:$id"), s)
            val strip = (s.width - s.height * 16f / 9f) / 2f
            val split = maxOf(strip, 110 * s.density)
            val reach = split + 84 * s.density
            for (id in Sts2Pad.THUMB_IDS) {
                assertEquals("$s $id", InputButtonShape.RECTANGLE, e.getValue(id).buttonShape)
                e.getValue(id).opacity.let { assertTrue("$s $id $it", it >= .4f && it < 1f) }
            }
            val tolerance = 1f
            // Left: Up, then Left | Right, then Down, from the screen's edge to past the picture's, split under the thumb.
            for (id in listOf("up", "down")) { assertEquals("$s $id", 0f, b(id).left, tolerance); assertEquals("$s $id", reach, b(id).right, tolerance) }
            assertEquals("$s", 0f, b("left").left, tolerance)
            assertEquals("$s", split, b("left").right, tolerance)
            assertEquals("$s", b("left").right, b("right").left, tolerance)
            assertEquals("$s", reach, b("right").right, tolerance)
            assertEquals("$s", b("up").bottom, b("left").top, tolerance)
            assertEquals("$s", b("up").bottom, b("right").top, tolerance)
            assertEquals("$s", b("left").bottom, b("down").top, tolerance)
            // Right: Y, then X | B, then A, mirrored.
            for (id in listOf("y", "a")) { assertEquals("$s $id", s.width - reach, b(id).left, tolerance); assertEquals("$s $id", s.width.toFloat(), b(id).right, tolerance) }
            assertEquals("$s", s.width - reach, b("x").left, tolerance)
            assertEquals("$s", b("x").right, b("b").left, tolerance)
            assertEquals("$s", s.width - split, b("b").left, tolerance)
            assertEquals("$s", s.width.toFloat(), b("b").right, tolerance)
            for (id in listOf("x", "b")) { assertEquals("$s $id", b("y").bottom, b(id).top, tolerance); assertEquals("$s $id", b(id).bottom, b("a").top, tolerance) }
            // Big: every row at least 48 dp tall, every cell at least 84 dp wide.
            for (id in listOf("up", "left", "right", "down", "y", "x", "b", "a")) {
                assertTrue("$s $id", b(id).height >= 48 * s.density - tolerance)
                assertTrue("$s $id", b(id).width >= 84 * s.density - tolerance)
            }
            assertEquals("$s", b("up").top, b("y").top, tolerance)
        }
        for (s in screens.filter { it.width <= it.height * 16f / 9f })
            for (id in Sts2Pad.THUMB_IDS) {
                assertEquals("$s $id", InputButtonShape.CIRCLE, elements(s).single { it.id == id }.buttonShape)
                assertEquals("$s $id", 1f, elements(s).single { it.id == id }.opacity)
            }
    }

    /** Where the pad put the d-pad and A / B / Y before they were raised (stock pad at 2424 × 1080, density 2.625). */
    private val lowThumbs = mapOf(
        "legacy:up" to floatArrayOf(.05414604f, .5347222f), "legacy:right" to floatArrayOf(.103960395f, .64652777f),
        "legacy:down" to floatArrayOf(.05414604f, .7583333f), "legacy:left" to floatArrayOf(.0043316833f, .64652777f),
        "legacy:y" to floatArrayOf(.89820546f, .5347222f), "legacy:b" to floatArrayOf(.9480198f, .64652777f), "legacy:a" to floatArrayOf(.89820546f, .7583333f))

    private fun lowPad(): SavedInputLayout = Sts2Pad.layout().let { pad ->
        pad.copy(elements = pad.elements.map { e -> lowThumbs[e.id]?.let { e.copy(x = it[0], y = it[1], width = .047648516f, height = .10694444f) } ?: e })
    }

    @Test fun aPadSavedWithTheThumbsLowGetsThemRaisedButAThumbThePersonMovedStays() {
        assertEquals(Sts2Pad.layout(), Sts2Pad.raiseThumbs(lowPad()))
        val moved = lowPad().let { pad -> pad.copy(elements = pad.elements.map { if (it.id == "legacy:a") it.copy(x = .5f, y = .5f) else it }) }
        val raised = Sts2Pad.raiseThumbs(moved)
        assertEquals(listOf(.5f, .5f), raised.elements.single { it.id == "legacy:a" }.let { listOf(it.x, it.y) })
        assertEquals(Sts2Pad.layout().elements.filterNot { it.id == "legacy:a" }, raised.elements.filterNot { it.id == "legacy:a" })
        val pad = Sts2Pad.layout()
        assertSame(pad, Sts2Pad.raiseThumbs(pad))
        val other = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }
        assertSame(other, Sts2Pad.raiseThumbs(other))
    }

    /** A stock pad as an earlier version saved it: its thumbs where, and as, that version put them (X only if it had one). */
    private fun earlierPad(stock: List<InputSurfaceElement>): SavedInputLayout = Sts2Pad.layout().let { pad ->
        val before = stock.associateBy { it.id }
        pad.copy(elements = pad.elements.mapNotNull { e -> if (e.id in Sts2Pad.THUMB_IDS) before[e.id]?.let {
            e.copy(x = it.x, y = it.y, width = it.width, height = it.height, buttonShape = it.buttonShape, opacity = it.opacity) } else e })
    }

    @Test fun everyEarlierPadGetsTheTilesAndAnXOnce() {
        for ((i, stock) in Sts2Pad.earlier().withIndex()) {
            val pad = earlierPad(stock)
            assertEquals("$i", i == Sts2Pad.earlier().lastIndex, pad.elements.any { it.id == "legacy:x" })
            assertTrue("$i", pad.elements.filter { it.id in Sts2Pad.THUMB_IDS }.all { it.opacity == 1f })
            assertEquals("$i", Sts2Pad.layout(), Sts2Pad.raiseThumbs(pad))
        }
        val removed = Sts2Pad.layout().let { it.copy(elements = it.elements.filterNot { e -> e.id == "legacy:y" }) }
        assertEquals(removed.elements.map { it.id }.toSet(), Sts2Pad.raiseThumbs(removed).elements.map { it.id }.toSet())
    }

    @Test fun aPadSavedWithTheRaisedCirclesGetsTheTilesButAMovedOrReshapedThumbStays() {
        val circles = earlierPad(Sts2Pad.earlier()[1])
        assertTrue(circles.elements.filter { it.id in Sts2Pad.THUMB_IDS }.all { it.buttonShape == InputButtonShape.CIRCLE })
        assertEquals(Sts2Pad.layout(), Sts2Pad.raiseThumbs(circles))
        assertEquals(Sts2Pad.layout(), Sts2Pad.raiseThumbs(earlierPad(Sts2Pad.earlier().first())))
        assertTrue(Sts2Pad.earlier().dropLast(1).all { stock -> stock.none { it.id == "legacy:x" } })
        val moved = circles.copy(elements = circles.elements.map { if (it.id == "legacy:a") it.copy(x = .5f, y = .5f) else it })
        val tiled = Sts2Pad.raiseThumbs(moved)
        assertEquals(InputButtonShape.CIRCLE, tiled.elements.single { it.id == "legacy:a" }.buttonShape)
        assertEquals(listOf(.5f, .5f), tiled.elements.single { it.id == "legacy:a" }.let { listOf(it.x, it.y) })
        assertEquals(Sts2Pad.layout().elements.filterNot { it.id == "legacy:a" }, tiled.elements.filterNot { it.id == "legacy:a" })
    }

    @Test fun versionSixteenToEighteenLibrariesGetTheirUntouchedPadOnTheWideTilesWithAnXOnce() {
        for ((version, stock) in listOf(16 to Sts2Pad.earlier()[1], 17 to Sts2Pad.earlier()[2], 18 to Sts2Pad.earlier()[3])) {
            reset()
            val fresh = InputLibraries.read(context)
            val old = fresh.withLayout(earlierPad(stock))
            prefs.edit().putString("library", JSONObject(InputLibraries.encode(old)).put("version", version).toString()).commit()
            val upgraded = InputLibraries.read(context)
            assertEquals("$version", Sts2Pad.layout(), upgraded.layouts.single { it.id == Sts2Pad.LAYOUT })
            assertEquals(old.profiles, upgraded.profiles)
            assertEquals(upgraded, InputLibraries.read(context))
        }
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
        assertEquals(InputButtonShape.CIRCLE, narrow.getValue("legacy:a").buttonShape)
        assertEquals(Sts2Pad.elements(1920, 1080, density), OnScreenLayout.elements(context, layout, 1920, 1080, Rect()))
    }

    @Test fun versionFifteenLibrariesGetTheirUntouchedPadRaisedOnce() {
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
        for (control in listOf(InputControl.LT, InputControl.RT, InputControl.RB, InputControl.LB, InputControl.SELECT, InputControl.START, InputControl.UP, InputControl.A))
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
