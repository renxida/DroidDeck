package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.GamepadGeometry.Box
import kotlin.math.abs
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
    private const val REST = .70f
    private const val CLUSTER_SHARE = .34f
    private const val HUD_OPACITY = .35f
    private const val KEYBOARD_X_DP = 34f
    private const val KEYBOARD_Y_DP = 86f
    private const val KEYBOARD_RADIUS_DP = 18f

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
        return stored.map { e ->
            if (untouched(e)) fresh.getValue(e.id).let { e.copy(x = it.x, y = it.y, width = it.width, height = it.height) } else moved.getValue(e.id)
        }
    }

    fun elements(width: Int, height: Int, density: Float, top: Int = 0, bottom: Int = 0): List<InputSurfaceElement> {
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
        // The bars beside the picture are empty; the clusters rise only when a narrower screen puts them over the piles below.
        fun cluster(buttons: List<Pair<InputControl, Pair<Float, Float>>>, cx: Float): List<Pair<InputControl, Box>> {
            var cy = (h - bottom) * REST
            for ((_, offset) in buttons) {
                val x = cx + offset.first * spread
                for ((_, b) in hud) if (b.cy > h / 2f && b.left < x + radius + gap && x - radius - gap < b.right)
                    cy = min(cy, b.top - gap - radius - offset.second * spread)
            }
            cy = cy.coerceAtLeast(top + edge + spread + radius)
            return buttons.map { (control, offset) -> control to Box(cx + offset.first * spread, cy + offset.second * spread, 2 * radius, 2 * radius) }
        }
        val thumbs = cluster(DPAD, edge + spread + radius) + cluster(FACE, w - edge - spread - radius)
        fun element(control: InputControl, b: Box, id: String = "legacy:${control.id}", name: String = control.label,
            shape: InputButtonShape = InputButtonShape.CIRCLE, opacity: Float = 1f, icon: Int = 0) =
            InputSurfaceElement(control, b.left / w, b.top / h, b.width / w, b.height / h, id, name = name, floating = false,
                icon = icon, opacity = opacity, buttonShape = shape).bounded()
        val keyboardR = KEYBOARD_RADIUS_DP * density
        val keyboard = element(InputControl.A, Box(KEYBOARD_X_DP * density, top + KEYBOARD_Y_DP * density, 2 * keyboardR, 2 * keyboardR), KEYBOARD,
            "Keyboard", icon = InputButtonIcons.KEYBOARD)
        return thumbs.map { (control, b) -> element(control, b) } +
            hud.map { (s, b) -> element(s.control, b, s.id, s.name, s.shape, HUD_OPACITY) } + keyboard
    }
}
