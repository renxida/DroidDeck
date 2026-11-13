package com.droiddeck.launcher.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionScreenshotTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theOverlayIsDrawnOverTheGameAndThePictureIsScaledDown() {
        val game = Bitmap.createBitmap(1616, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val overlay = Bitmap.createBitmap(2424, 1080, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            for (x in 0 until 200) for (y in 0 until 200) setPixel(x, y, Color.BLUE)
        }
        val out = SessionScreenshot.compose(2424, 1080, game, RectF(0f, 0f, 2424f, 1080f), overlay)
        assertEquals(1280, out.width)
        assertEquals(570, out.height)
        assertEquals(Color.BLUE, out.getPixel(20, 20))
        assertEquals(Color.RED, out.getPixel(900, 400))
    }

    @Test fun aBusyPictureIsEncodedUnderTheLimit() {
        val random = Random(7)
        val noise = Bitmap.createBitmap(1280, 570, Bitmap.Config.ARGB_8888)
        for (x in 0 until noise.width) for (y in 0 until noise.height) noise.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        val bytes = SessionScreenshot.encode(noise, SessionScreenshot.MAX_BYTES)
        assertNotNull(bytes)
        assertTrue(bytes!!.size <= SessionScreenshot.MAX_BYTES)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        assertTrue(decoded.width in 320..1280)
    }

    @Test fun takenLooksForTheFileInTheSessionFolder() {
        val dir = tmp.newFolder("session")
        assertFalse(SessionScreenshot.taken(dir))
        assertFalse(SessionScreenshot.taken(null))
        dir.resolve(SessionScreenshot.FILE).writeBytes(byteArrayOf(1))
        assertTrue(SessionScreenshot.taken(dir))
    }
}
