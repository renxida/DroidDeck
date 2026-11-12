package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridNavigationTest {
    @Test fun downAndUpPreserveEveryColumnAcrossSeveralRows() {
        for (columns in 1..6) for (index in 0 until columns * 3) {
            val down = gridNeighbor(index, columns * 4, columns, 1)!!
            assertEquals(index % columns, down % columns)
            assertEquals(index, gridNeighbor(down, columns * 4, columns, -1))
        }
    }

    @Test fun incompleteLastRowDoesNotWrapBackToTheLeft() {
        assertEquals(8, gridNeighbor(4, 10, 4, 1))
        assertEquals(9, gridNeighbor(5, 10, 4, 1))
        assertNull(gridNeighbor(6, 10, 4, 1))
        assertNull(gridNeighbor(7, 10, 4, 1))
        assertNull(gridNeighbor(9, 10, 4, 1))
    }

    @Test fun firstRowCanLeaveUpwardsAndChangedColumnCountsAreUsed() {
        assertNull(gridNeighbor(2, 10, 4, -1))
        assertEquals(5, gridNeighbor(2, 10, 3, 1))
        assertEquals(6, gridNeighbor(2, 10, 4, 1))
    }

    @Test fun downFromTheInstalledSectionLandsInTheSameColumnBelowTheDivider() {
        assertEquals(6, gridNeighbor(0, 12, 4, 1, split = 6).let { gridNeighbor(it!!, 12, 4, 1, split = 6) })
        assertEquals(6, gridNeighbor(4, 12, 4, 1, split = 6))
        assertEquals(7, gridNeighbor(5, 12, 4, 1, split = 6))
        assertEquals(8, gridNeighbor(2, 12, 4, 1, split = 6))
    }

    @Test fun upFromBelowTheDividerClampsToTheShortInstalledRow() {
        assertEquals(4, gridNeighbor(6, 12, 4, -1, split = 6))
        assertEquals(5, gridNeighbor(7, 12, 4, -1, split = 6))
        assertEquals(5, gridNeighbor(9, 12, 4, -1, split = 6))
        assertEquals(6, gridNeighbor(10, 12, 4, -1, split = 6))
    }

    @Test fun lastSectionStillStopsAtItsIncompleteRow() {
        assertNull(gridNeighbor(9, 11, 4, 1, split = 6))
        assertEquals(10, gridNeighbor(6, 11, 4, 1, split = 6))
        assertNull(gridNeighbor(1, 11, 4, -1, split = 6))
    }
}
