package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class GuestTextTest {
    private fun typed(text: String): Pair<List<Pair<Int, Boolean>>, Int> {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val skipped = GuestText.type(text) { code, down -> sent += code to down }
        return sent to skipped
    }

    @Test fun lowercaseAndDigitsArePlainPresses() {
        assertEquals(listOf(30 to true, 30 to false, 2 to true, 2 to false) to 0, typed("a1"))
    }

    @Test fun capitalsAndShiftedSymbolsAreWrappedInShift() {
        assertEquals(listOf(42 to true, 30 to true, 30 to false, 42 to false,
            42 to true, 3 to true, 3 to false, 42 to false) to 0, typed("A@"))
    }

    @Test fun everyPrintableAsciiCharacterHasAKey() {
        val printable = (32..126).map { it.toChar() }.joinToString("")
        assertEquals(0, typed(printable).second)
    }

    @Test fun charactersWithoutAUsKeyAreCountedNotSent() {
        val (sent, skipped) = typed("é€x")
        assertEquals(2, skipped)
        assertEquals(listOf(45 to true, 45 to false), sent)
    }

    @Test fun signInTabsFromTheAccountToThePassword() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val skipped = GuestText.signIn("a", java.nio.CharBuffer.wrap(charArrayOf('b'))) { code, down -> sent += code to down }
        assertEquals(0, skipped)
        assertEquals(listOf(30 to true, 30 to false, 15 to true, 15 to false, 48 to true, 48 to false), sent)
    }

    @Test fun signInWithOneFieldPressesNoTab() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        GuestText.signIn("", "b") { code, down -> sent += code to down }
        assertEquals(listOf(48 to true, 48 to false), sent)
    }

    @Test fun pasteChordHoldsCtrlAroundV() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        GuestText.chord(GuestText.KEY_LEFTCTRL, GuestText.KEY_V) { code, down -> sent += code to down }
        assertEquals(listOf(29 to true, 47 to true, 47 to false, 29 to false), sent)
    }
}
