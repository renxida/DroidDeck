package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.GamepadGeometry.Box
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The on-screen pad for Slay the Spire 2: a d-pad and A / B / Y beside the game's 16:9 picture, and faint buttons on the
 * game's own draw, discard and exhaust piles, End Turn and map / deck / settings icons, each sending the controller button
 * the game binds to that action.
 */
internal object Sts2Pad {
    const val APP_ID = "2868840"
    const val LAYOUT = "builtin.sts2"
    const val BINDINGS = "preset.sts2"
    const val END_TURN = "sts2:end-turn"
    private const val KEYBOARD = "builtin:keyboard"

    private const val PICTURE_ASPECT = 16f / 9f
    private const val REFERENCE_WIDTH = 2424
    private const val REFERENCE_HEIGHT = 1080
    private const val REFERENCE_DENSITY = 2.625f
    private const val TOLERANCE = .002f
    private const val RADIUS_DP = 22f
    private const val SPREAD_DP = 46f
    private const val EDGE_DP = 4f
    private const val GAP_DP = 6f
    /** Centre of the d-pad and A / B / Y as a share of the usable height: upper middle, in the empty strips beside the picture. */
    private const val REST = .45f
    /** Where they sat before (low, near the piles), kept to recognise a pad saved untouched from then. */
    private const val LOW_REST = .70f
    private const val CLUSTER_SHARE = .34f
    private const val HUD_OPACITY = .35f
    private const val KEYBOARD_X_DP = 34f
    private const val KEYBOARD_Y_DP = 86f
    private const val KEYBOARD_RADIUS_DP = 18f
    /** Tallest row of the tiled d-pad and A / B / Y; the rows are as wide as the strip beside the picture. */
    private const val ROW_DP = 72f
    /** Narrowest strip, and shortest row, the d-pad and A / B / Y are tiled in; below either they are circles. */
    private const val MIN_STRIP_DP = 56f
    private const val MIN_ROW_DP = 48f

    /** Centre as a fraction of the game's picture and size as a fraction of its height, measured on the game's combat and death screens (v0.107). */
    private class Spot(val id: String, val control: InputControl, val x: Float, val y: Float, val width: Float, val height: Float = width,
        val shape: InputButtonShape = InputButtonShape.CIRCLE, val name: String = control.label)

    private val HUD = listOf(
        Spot("legacy:lt", InputControl.LT, .027f, .946f, .085f),
        Spot("legacy:rt", InputControl.RT, .972f, .946f, .085f),
        Spot("legacy:rb", InputControl.RB, .973f, .767f, .07f),
        Spot(END_TURN, InputControl.A, .893f, .823f, .18f, .075f, InputButtonShape.PILL, InputControl.Y.label),
        Spot("legacy:select", InputControl.SELECT, .886f, .036f, .07f),
        Spot("legacy:lb", InputControl.LB, .930f, .036f, .07f),
        Spot("legacy:start", InputControl.START, .972f, .036f, .07f),
    )
    private val DPAD = listOf(InputControl.UP to (0f to -1f), InputControl.RIGHT to (1f to 0f), InputControl.DOWN to (0f to 1f), InputControl.LEFT to (-1f to 0f))
    private val FACE = listOf(InputControl.Y to (0f to -1f), InputControl.B to (1f to 0f), InputControl.A to (0f to 1f))
    val THUMB_IDS = (DPAD + FACE).map { "legacy:${it.first.id}" }.toSet()
    val HUD_IDS = HUD.map { it.id }.toSet()

    private val stock by lazy { elements(REFERENCE_WIDTH, REFERENCE_HEIGHT, REFERENCE_DENSITY) }
    /** Where earlier pads put the d-pad and A / B / Y: low circles near the piles, then raised circles. Kept to recognise a pad saved untouched from then. */
    private val earlierStocks by lazy { listOf(
        elements(REFERENCE_WIDTH, REFERENCE_HEIGHT, REFERENCE_DENSITY, raised = false, tiled = false),
        elements(REFERENCE_WIDTH, REFERENCE_HEIGHT, REFERENCE_DENSITY, tiled = false)) }

    /** The earlier stock pads, oldest first, for tests of the upgrade. */
    internal fun earlier(): List<List<InputSurfaceElement>> = earlierStocks

    fun owns(layout: SavedInputLayout?) = layout != null && (layout.origin ?: layout.id) == LAYOUT

    fun layout() = SavedInputLayout(LAYOUT, "Slay the Spire 2", stock, screen = InputLayoutScreen.MAIN,
        aspect = REFERENCE_WIDTH.toFloat() / REFERENCE_HEIGHT)

    fun bindings() = SavedInputBindings(BINDINGS, "Slay the Spire 2", false, LAYOUT, elements = mapOf(
        END_TURN to InputAction.Native(InputControl.Y), KEYBOARD to InputAction.Command(InputCommand.KEYBOARD)))

    /** Uses this pad while the game is in front, unless the person chose a primary layout or gave the profile a layout other than the gamepad. */
    fun forGame(library: InputLibrary, game: String?): InputLibrary {
        if (game != APP_ID || InputRole.PRIMARY in library.selection().parts) return library
        if (library.layouts.none { it.id == LAYOUT } || library.bindings.none { it.id == BINDINGS && it.layoutId == LAYOUT }) return library
        if (library.activeLayout(InputRole.PRIMARY)?.id != InputLibraries.OVERLAY) return library
        return library.selectPart(InputRole.PRIMARY, BINDINGS)
    }

    /** Places the untouched buttons on the game's picture at this size; buttons the person moved are placed by [fit]. */
    fun place(stored: List<InputSurfaceElement>, width: Int, height: Int, density: Float, top: Int, bottom: Int,
        fit: (List<InputSurfaceElement>) -> List<InputSurfaceElement>): List<InputSurfaceElement> {
        val reference = stock.associateBy { it.id }
        fun untouched(e: InputSurfaceElement) = reference[e.id]?.let { s ->
            abs(s.x - e.x) <= TOLERANCE && abs(s.y - e.y) <= TOLERANCE && abs(s.width - e.width) <= TOLERANCE && abs(s.height - e.height) <= TOLERANCE
        } == true
        val fresh = elements(width, height, density, top, bottom).associateBy { it.id }
        val moved = fit(stored.filterNot(::untouched)).associateBy { it.id }
        // A shape the person chose stays; the stock shape follows the place (tiles in wide strips, circles in narrow ones).
        return stored.map { e ->
            if (untouched(e)) fresh.getValue(e.id).let {
                e.copy(x = it.x, y = it.y, width = it.width, height = it.height,
                    buttonShape = if (e.buttonShape == reference.getValue(e.id).buttonShape) it.buttonShape else e.buttonShape)
            } else moved.getValue(e.id)
        }
    }

    /**
     * Moves the d-pad and A / B / Y of a saved pad that still has them where an earlier pad put them (low circles, then
     * raised circles) to the gapless tiles they are now, shape included; buttons the person moved, and every other
     * button, are left as they are.
     */
    fun raiseThumbs(layout: SavedInputLayout): SavedInputLayout {
        if (!owns(layout)) return layout
        val earlier = earlierStocks.map { it.associateBy { e -> e.id } }
        val now = stock.associateBy { it.id }
        fun same(a: InputSurfaceElement, b: InputSurfaceElement) = abs(a.x - b.x) <= TOLERANCE && abs(a.y - b.y) <= TOLERANCE &&
            abs(a.width - b.width) <= TOLERANCE && abs(a.height - b.height) <= TOLERANCE
        val elements = layout.elements.map { e ->
            if (e.id !in THUMB_IDS || earlier.none { before -> before[e.id]?.let { same(it, e) } == true }) return@map e
            now.getValue(e.id).let { e.copy(x = it.x, y = it.y, width = it.width, height = it.height, buttonShape = it.buttonShape) }
        }
        return if (elements == layout.elements) layout else layout.copy(elements = elements)
    }

    fun elements(width: Int, height: Int, density: Float, top: Int = 0, bottom: Int = 0): List<InputSurfaceElement> =
        elements(width, height, density, top, bottom, raised = true, tiled = true)

    private fun elements(width: Int, height: Int, density: Float, top: Int = 0, bottom: Int = 0, raised: Boolean = true,
        tiled: Boolean = true): List<InputSurfaceElement> {
        val w = width.toFloat()
        val h = height.toFloat()
        val pictureW = min(w, h * PICTURE_ASPECT)
        val pictureH = min(h, w / PICTURE_ASPECT)
        val pictureX = (w - pictureW) / 2f
        val pictureY = (h - pictureH) / 2f
        val hud = HUD.map { s -> s to Box(pictureX + s.x * pictureW, pictureY + s.y * pictureH, s.width * pictureH, s.height * pictureH) }
        val reach = (SPREAD_DP + RADIUS_DP) * density
        val scale = min(1f, (h - top - bottom) * CLUSTER_SHARE / (2 * reach))
        val radius = RADIUS_DP * density * scale
        val spread = SPREAD_DP * density * scale
        val edge = EDGE_DP * density
        val gap = GAP_DP * density
        val keyboardR = KEYBOARD_RADIUS_DP * density
        val keyboardBox = Box(KEYBOARD_X_DP * density, top + KEYBOARD_Y_DP * density, 2 * keyboardR, 2 * keyboardR)
        // The bars beside the picture are empty, so the clusters sit high in them, within the thumbs' reach: below the keyboard
        // key and the game's top icons, and above the piles at the bottom, which win when a short screen leaves no room for both.
        fun beside(b: Box, x: Float) = b.left < x + radius + gap && x - radius - gap < b.right
        fun belowTop(buttons: List<Pair<InputControl, Pair<Float, Float>>>, cx: Float, start: Float): Float {
            var cy = start
            for ((_, offset) in buttons) {
                val x = cx + offset.first * spread
                for (b in hud.map { it.second } + keyboardBox) if (b.cy < h / 2f && beside(b, x))
                    cy = max(cy, b.bottom + gap + radius - offset.second * spread)
            }
            return cy
        }
        val leftX = edge + spread + radius
        val rightX = w - edge - spread - radius
        // Both clusters start level, low enough to clear whatever is at the top on either side.
        val rest = if (raised) top + (h - top - bottom) * REST else (h - bottom) * LOW_REST
        val start = if (raised) max(belowTop(DPAD, leftX, rest), belowTop(FACE, rightX, rest)) else rest
        fun cluster(buttons: List<Pair<InputControl, Pair<Float, Float>>>, cx: Float): List<Pair<InputControl, Box>> {
            var cy = start
            for ((_, offset) in buttons) {
                val x = cx + offset.first * spread
                for ((_, b) in hud) if (b.cy > h / 2f && beside(b, x))
                    cy = min(cy, b.top - gap - radius - offset.second * spread)
            }
            cy = cy.coerceAtLeast(top + edge + spread + radius)
            return buttons.map { (control, offset) -> control to Box(cx + offset.first * spread, cy + offset.second * spread, 2 * radius, 2 * radius) }
        }
        val tiles = if (tiled) tiles(w, h, density, top, bottom, pictureX, pictureW, keyboardBox.bottom + gap) else null
        val thumbs = tiles ?: (cluster(DPAD, leftX) + cluster(FACE, rightX))
        val thumbShape = if (tiles != null) InputButtonShape.RECTANGLE else InputButtonShape.CIRCLE
        fun element(control: InputControl, b: Box, id: String = "legacy:${control.id}", name: String = control.label,
            shape: InputButtonShape = InputButtonShape.CIRCLE, opacity: Float = 1f, icon: Int = 0) =
            InputSurfaceElement(control, b.left / w, b.top / h, b.width / w, b.height / h, id, name = name, floating = false,
                icon = icon, opacity = opacity, buttonShape = shape).bounded()
        val keyboard = element(InputControl.A, keyboardBox, KEYBOARD, "Keyboard", icon = InputButtonIcons.KEYBOARD)
        return thumbs.map { (control, b) -> element(control, b, shape = thumbShape) } +
            hud.map { (s, b) -> element(s.control, b, s.id, s.name, s.shape, HUD_OPACITY) } + keyboard
    }

    /**
     * The d-pad and A / B / Y as big rectangles filling the strips beside the picture edge to edge, with no gap between them
     * for a thumb to slip through to the game: Up, Left | Right, Down on the left and Y, B, A on the right, in rows as wide
     * as the strip. Null when the strips are too narrow or short for that; the pad then uses circles.
     */
    private fun tiles(w: Float, h: Float, density: Float, top: Int, bottom: Int, pictureX: Float, pictureW: Float,
        below: Float): List<Pair<InputControl, Box>>? {
        val bar = pictureX
        if (bar < MIN_STRIP_DP * density) return null
        val spanTop = max(below, top.toFloat())
        val spanBottom = h - bottom - EDGE_DP * density
        val row = minOf(bar, ROW_DP * density, (spanBottom - spanTop) / 3f)
        if (row < MIN_ROW_DP * density) return null
        val centre = top + (h - top - bottom) * REST
        val first = (centre - 1.5f * row).coerceIn(spanTop, spanBottom - 3 * row)
        fun rowY(i: Int) = first + (i + .5f) * row
        val right = pictureX + pictureW
        val rightW = w - right
        return listOf(
            InputControl.UP to Box(bar / 2f, rowY(0), bar, row),
            InputControl.LEFT to Box(bar / 4f, rowY(1), bar / 2f, row),
            InputControl.RIGHT to Box(bar * 3f / 4f, rowY(1), bar / 2f, row),
            InputControl.DOWN to Box(bar / 2f, rowY(2), bar, row),
            InputControl.Y to Box(right + rightW / 2f, rowY(0), rightW, row),
            InputControl.B to Box(right + rightW / 2f, rowY(1), rightW, row),
            InputControl.A to Box(right + rightW / 2f, rowY(2), rightW, row))
    }
}
