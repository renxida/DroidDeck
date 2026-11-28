package com.droiddeck.launcher.hud

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Lanner's mark: a falcon in a stoop (wings swept back, head down). The body takes the ink colour and the
 * head the accent. Source: artwork/lanner-mark.svg; the view box is the mark's own bounds, so ink fills it.
 */
internal object BrandMark {
    const val VIEW_W = 50.30f
    const val VIEW_H = 44.80f
    const val BODY = "M37.00 44.80L37.50 32.10L50.30 0.20L33.30 16.60L27.50 0.00L18.40 3.00L23.40 19.80L0.00 16.50L29.10 34.80Z"
    const val HEAD = "M37.00 44.80L37.50 32.10L29.10 34.80Z"

    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath().asAndroidPath()
    private val body by lazy { parse(BODY) }
    private val head by lazy { parse(HEAD) }
    private val scaled = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun widthFor(height: Float): Float = height * VIEW_W / VIEW_H

    fun inkLeftFor(height: Float): Float = 0f
    fun inkRightFor(height: Float): Float = widthFor(height)

    fun draw(canvas: Canvas, left: Float, top: Float, height: Float, bodyColor: Int, accentColor: Int, alpha: Int = 255) {
        val s = height / VIEW_H
        matrix.setScale(s, s)
        matrix.postTranslate(left, top)
        paint.color = bodyColor
        paint.alpha = android.graphics.Color.alpha(bodyColor) * alpha / 255
        body.transform(matrix, scaled)
        canvas.drawPath(scaled, paint)
        paint.color = accentColor
        paint.alpha = android.graphics.Color.alpha(accentColor) * alpha / 255
        head.transform(matrix, scaled)
        canvas.drawPath(scaled, paint)
    }

    /** Draws the mark centred on (cx, cy), fitting a square of side [size]. */
    fun drawCentered(canvas: Canvas, cx: Float, cy: Float, size: Float, bodyColor: Int, accentColor: Int, alpha: Int = 255) {
        val h = if (VIEW_W > VIEW_H) size * VIEW_H / VIEW_W else size
        val w = widthFor(h)
        draw(canvas, cx - w / 2f, cy - h / 2f, h, bodyColor, accentColor, alpha)
    }
}
