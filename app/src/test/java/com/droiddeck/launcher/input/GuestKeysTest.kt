package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GuestKeysTest {
    private val physical = 5
    private val virtual = -1

    private fun event(action: Int, keyCode: Int, unicode: Int = 0, scanCode: Int = 0, deviceId: Int = physical): KeyEvent =
        object : KeyEvent(0L, 0L, action, keyCode, 0, 0, deviceId, scanCode) {
            override fun getUnicodeChar(): Int = unicode
        }

    private fun press(keyCode: Int, unicode: Int = 0, scanCode: Int = 0, deviceId: Int = physical): List<Pair<Int, Boolean>> {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val record: (Int, Boolean) -> Unit = { code, down -> sent += code to down }
        assertTrue(GuestKeys.send(event(KeyEvent.ACTION_DOWN, keyCode, unicode, scanCode, deviceId), record))
        assertTrue(GuestKeys.send(event(KeyEvent.ACTION_UP, keyCode, unicode, scanCode, deviceId), record))
        return sent
    }

    @Test fun aPhysicalKeyIsSentAsItsEvdevCode() {
        assertEquals(listOf(30 to true, 30 to false), press(KeyEvent.KEYCODE_A, 'a'.code))
    }

    @Test fun aPhysicalKeyboardsOwnShiftIsNotDoubled() {
        assertEquals(listOf(2 to true, 2 to false), press(KeyEvent.KEYCODE_1, '!'.code))
    }

    @Test fun aShiftedCharacterFromAKeyboardWithoutShiftIsWrappedInShift() {
        assertEquals(listOf(42 to true, 2 to true, 2 to false, 42 to false),
            press(KeyEvent.KEYCODE_UNKNOWN, '!'.code, deviceId = virtual))
        assertEquals(listOf(42 to true, 30 to true, 30 to false, 42 to false),
            press(KeyEvent.KEYCODE_A, 'A'.code, deviceId = virtual))
        assertEquals(listOf(30 to true, 30 to false), press(KeyEvent.KEYCODE_A, 'a'.code, deviceId = virtual))
    }

    @Test fun aCharacterWithoutAKeyCodeIsTypedByItsCharacter() {
        assertEquals(listOf(52 to true, 52 to false), press(KeyEvent.KEYCODE_UNKNOWN, '.'.code))
    }

    @Test fun anUnmappedKeyFallsBackToItsScanCode() {
        assertEquals(listOf(125 to true, 125 to false), press(KeyEvent.KEYCODE_META_LEFT, scanCode = 125))
    }

    @Test fun backWithTheEscapeScanCodeIsEscape() {
        assertEquals(listOf(1 to true, 1 to false), press(KeyEvent.KEYCODE_BACK, scanCode = 1))
    }

    @Test fun keysWithNothingToSendAndOtherActionsAreRefused() {
        val sent = mutableListOf<Int>()
        assertFalse(GuestKeys.send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) { code, _ -> sent += code })
        assertFalse(GuestKeys.send(event(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_A, 'a'.code)) { code, _ -> sent += code })
        assertEquals(emptyList<Int>(), sent)
    }
}
