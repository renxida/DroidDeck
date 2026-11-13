package com.droiddeck.launcher.session

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FexStatsLogTest {
    /** A stats file as FEX writes it: 64-byte header, then threads linked by offsets. */
    private fun file(vararg threads: Pair<Int, LongArray>, version: Int = 2): ByteArray {
        val stride = 8 + 8 * 13
        val b = ByteBuffer.allocate(64 + stride * threads.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, version.toByte())
        b.put(1, 4)
        b.putShort(2, stride.toShort())
        "FEX-2611".toByteArray().forEachIndexed { i, c -> b.put(4 + i, c) }
        b.putInt(52, if (threads.isEmpty()) 0 else 64)
        threads.forEachIndexed { i, (tid, f) ->
            val o = 64 + i * stride
            b.putInt(o, if (i + 1 < threads.size) o + stride else 0)
            b.putInt(o + 4, tid)
            f.forEachIndexed { k, v -> b.putLong(o + 8 + 8 * k, v) }
        }
        return b.array()
    }

    private fun counters(jit: Long, smc: Long, blocks: Long) = LongArray(13).also { it[0] = jit; it[3] = smc; it[8] = blocks }

    @Test fun readsEveryThreadOfAVersion2File() {
        val s = FexStatsLog.parse(file(101 to counters(5, 1, 10), 102 to counters(7, 0, 3)))!!
        assertEquals("FEX-2611", s.fexVersion)
        assertEquals(listOf(101, 102), s.threads.keys.toList())
        assertEquals(7L, s.threads[102]!![0])
    }

    @Test fun ignoresOtherVersionsAndShortFiles() {
        assertNull(FexStatsLog.parse(file(1 to counters(1, 1, 1), version = 1)))
        assertNull(FexStatsLog.parse(ByteArray(10)))
    }

    @Test fun deltaCountsNewAndReplacedThreadsInFull() {
        val before = mapOf(1 to counters(100, 2, 50), 2 to counters(40, 0, 4))
        val after = mapOf(1 to counters(130, 5, 60), 2 to counters(10, 1, 1), 3 to counters(8, 0, 2))
        val d = FexStatsLog.delta(before, after)
        assertEquals(30L + 10 + 8, d[0])
        assertEquals(3L + 1 + 0, d[3])
        assertEquals(10L + 1 + 2, d[8])
        assertArrayEquals(counters(5, 1, 10), FexStatsLog.delta(null, mapOf(9 to counters(5, 1, 10))))
    }

    @Test fun rowConvertsTicksToMilliseconds() {
        val d = LongArray(13).also { it[0] = 2_000_000; it[3] = 4; it[8] = 9 }
        val row = FexStatsLog.row(1000, 5000, 77, "SlayTheSpire2.exe", 12, d, 1_000_000_000.0)
        val cols = row.split(',')
        assertEquals(FexStatsLog.HEADER.split(',').size, cols.size)
        assertEquals("2.0", cols[5])
        assertEquals("9", cols[6])
        assertEquals("4", cols[7])
    }
}
