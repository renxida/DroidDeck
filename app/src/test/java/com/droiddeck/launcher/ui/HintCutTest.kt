package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintCutTest {
    private val link = "… More"

    /** A layout of fixed-width characters, [perLine] to a line: the cut text carries the link after it. */
    private fun measure(text: String, perLine: Int): (Int?, Int) -> Pair<Boolean, Int> = { cut, lines ->
        val length = if (cut == null) text.length else cut + link.length
        (length > perLine * lines) to minOf(length, perLine * lines)
    }

    @Test fun textThatFitsIsLeftWhole() {
        val text = "Applies next session."
        assertNull(hintCut(text, 2, 300, measure(text, 20)))
    }

    @Test fun longTextIsCutSoTheLinkEndsTheLastLine() {
        val text = "x".repeat(200)
        val cut = hintCut(text, 2, 300, measure(text, 20))!!
        assertTrue(cut + link.length <= 40)
        assertTrue("no more is cut than the link needs", cut + link.length > 40 - 3)
    }

    @Test fun aRowTooNarrowForTheLinkKeepsNoText() {
        val text = "x".repeat(50)
        assertEquals(0, hintCut(text, 2, 300, measure(text, 2)))
    }
}
