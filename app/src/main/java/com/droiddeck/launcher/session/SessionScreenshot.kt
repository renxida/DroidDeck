package com.droiddeck.launcher.session

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.Window
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * One picture of a session for its telemetry bundle: the game's surface with the app's own overlays (on-screen
 * gamepad, HUD, menus) drawn over it, as the person saw it, saved as a small JPEG named [FILE] in the session folder.
 */
object SessionScreenshot {
    const val FILE = "screenshot.jpg"
    /** How long a game has been in front before the picture is taken. */
    const val DELAY_MS = 3 * 60_000L
    const val MAX_BYTES = 300 * 1024
    private const val MAX_WIDTH = 1280
    private const val MIN_WIDTH = 320
    private const val TAG = "SessionScreenshot"

    fun taken(dir: File?) = dir != null && File(dir, FILE).isFile

    /**
     * Copies [surface] (the game) and [window] (everything drawn over it), then composes and writes the JPEG on a
     * background thread. [done] runs on that thread with whether a file was written.
     */
    fun capture(window: Window, surface: SurfaceView, dir: File, handler: Handler, done: (Boolean) -> Unit = {}) {
        val decor = window.peekDecorView()
        val width = decor?.width ?: 0
        val height = decor?.height ?: 0
        if (width <= 0 || height <= 0) { done(false); return }
        val at = IntArray(2).also(surface::getLocationInWindow)
        val gameRect = RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + surface.width).toFloat(), (at[1] + surface.height).toFloat())
        val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val game = if (surface.width > 0 && surface.height > 0 && surface.holder.surface?.isValid == true)
            Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888) else null
        fun finish(gameOk: Boolean, overlayOk: Boolean) {
            Thread({
                val ok = runCatching {
                    if (!gameOk && !overlayOk) return@runCatching false
                    val bytes = encode(compose(width, height, game?.takeIf { gameOk }, gameRect, overlay.takeIf { overlayOk }), MAX_BYTES)
                        ?: return@runCatching false
                    val tmp = File(dir, "$FILE.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(File(dir, FILE))
                }.onFailure { Log.w(TAG, "screenshot failed", it) }.getOrDefault(false)
                game?.recycle(); overlay.recycle()
                Log.i(TAG, if (ok) "session screenshot saved" else "session screenshot not saved")
                done(ok)
            }, "session-screenshot").start()
        }
        fun copyWindow(gameOk: Boolean) = runCatching {
            PixelCopy.request(window, overlay, { result -> finish(gameOk, result == PixelCopy.SUCCESS) }, handler)
        }.onFailure { finish(gameOk, false) }
        if (game == null) copyWindow(false)
        else runCatching {
            PixelCopy.request(surface, game, { result -> copyWindow(result == PixelCopy.SUCCESS) }, handler)
        }.onFailure { copyWindow(false) }
    }

    /** The game picture at its place in the window, the window's own pixels over it, scaled to at most [MAX_WIDTH] wide. */
    internal fun compose(width: Int, height: Int, game: Bitmap?, gameRect: RectF, overlay: Bitmap?): Bitmap {
        val scale = minOf(1f, MAX_WIDTH.toFloat() / width)
        val out = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.scale(scale, scale)
        game?.let { canvas.drawBitmap(it, null, gameRect, paint) }
        overlay?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        return out
    }

    /** JPEG bytes no larger than [maxBytes]: lower quality first, then a smaller picture; null if even the smallest is too big. */
    internal fun encode(picture: Bitmap, maxBytes: Int): ByteArray? {
        var bitmap = picture
        try {
            while (true) {
                for (quality in intArrayOf(80, 70, 60, 50, 40)) {
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                    if (bytes.size <= maxBytes) return bytes
                }
                if (bitmap.width / 2 < MIN_WIDTH) return null
                val smaller = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, (bitmap.height / 2).coerceAtLeast(1), true)
                if (bitmap !== picture) bitmap.recycle()
                bitmap = smaller
            }
        } finally {
            if (bitmap !== picture) bitmap.recycle()
            picture.recycle()
        }
    }
}
