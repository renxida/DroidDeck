package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvdevKeysTest {
    private val typedPunctuation = "`-=[]\;',./"

    @Test fun everyCharacterKeyOfTheOnScreenKeyboardTypesItsOwnCode() {
        var checked = 0
        for (key in PC_KEY_ROWS.flatten()) {
            if (key.labelRes != 0 || key.label.length != 1) continue
            val ch = key.label[0]
            if (!ch.isLetterOrDigit() && ch !in typedPunctuation) continue
            val keycode = EvdevKeys.keycodeForChar(ch.code)
            assertTrue("'$ch' has no Android key code", keycode != 0)
            assertEquals("evdev code typed for '$ch'", key.code.toLong(), EvdevKeys.fromKeyCode(keycode).toLong())
            checked++
        }
        assertEquals(26L + 10L + typedPunctuation.length, checked.toLong())
    }

    @Test fun shiftedLegendsUnshiftToTheirKeysCharacter() {
        val shifted = PC_KEY_ROWS.flatten().filter { it.shifted != null }
        assertEquals(21L, shifted.size.toLong())
        for (key in shifted) {
            assertEquals(key.shifted, key.label[0].code.toLong(), EvdevKeys.unshiftedChar(key.shifted!![0].code).toLong())
        }
        for (c in 'A'..'Z') assertEquals(c.lowercaseChar().code.toLong(), EvdevKeys.unshiftedChar(c.code).toLong())
        assertEquals(0L, EvdevKeys.unshiftedChar('a'.code).toLong())
        assertEquals(0L, EvdevKeys.unshiftedChar('1'.code).toLong())
    }

    @Test fun controlAndNavigationKeysUseLinuxCodes() {
        val expected = mapOf(
            KeyEvent.KEYCODE_ESCAPE to 1, KeyEvent.KEYCODE_DEL to 14, KeyEvent.KEYCODE_TAB to 15,
            KeyEvent.KEYCODE_ENTER to 28, KeyEvent.KEYCODE_NUMPAD_ENTER to 28, KeyEvent.KEYCODE_SPACE to 57,
            KeyEvent.KEYCODE_CAPS_LOCK to 58, KeyEvent.KEYCODE_FORWARD_DEL to 111, KeyEvent.KEYCODE_INSERT to 110,
            KeyEvent.KEYCODE_SHIFT_LEFT to 42, KeyEvent.KEYCODE_SHIFT_RIGHT to 54,
            KeyEvent.KEYCODE_CTRL_LEFT to 29, KeyEvent.KEYCODE_CTRL_RIGHT to 97,
            KeyEvent.KEYCODE_ALT_LEFT to 56, KeyEvent.KEYCODE_ALT_RIGHT to 100,
            KeyEvent.KEYCODE_DPAD_UP to 103, KeyEvent.KEYCODE_DPAD_DOWN to 108,
            KeyEvent.KEYCODE_DPAD_LEFT to 105, KeyEvent.KEYCODE_DPAD_RIGHT to 106,
            KeyEvent.KEYCODE_MOVE_HOME to 102, KeyEvent.KEYCODE_MOVE_END to 107,
            KeyEvent.KEYCODE_PAGE_UP to 104, KeyEvent.KEYCODE_PAGE_DOWN to 109,
            KeyEvent.KEYCODE_F1 to 59, KeyEvent.KEYCODE_F10 to 68, KeyEvent.KEYCODE_F11 to 87, KeyEvent.KEYCODE_F12 to 88,
        )
        for ((keycode, evdev) in expected) {
            assertEquals("key code $keycode", evdev.toLong(), EvdevKeys.fromKeyCode(keycode).toLong())
        }
        assertEquals(-1L, EvdevKeys.fromKeyCode(KeyEvent.KEYCODE_UNKNOWN).toLong())
        assertEquals(-1L, EvdevKeys.fromKeyCode(KeyEvent.KEYCODE_VOLUME_UP).toLong())
    }

    @Test fun everyOnScreenKeyBesidesSuperHasAPhysicalKeyCode() {
        val reachable = (0..1000).map { EvdevKeys.fromKeyCode(it) }.toSet()
        val missing = PC_KEYS.keys.filter { it !in reachable }
        assertEquals(listOf(125), missing)
        assertTrue(PC_MODIFIERS.all { it in PC_KEYS })
    }
}
