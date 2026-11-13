package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLogTest {
    private fun stat(name: String, majflt: Long, utime: Long, stime: Long, core: Int): String {
        val after = MutableList(40) { "0" }
        after[0] = "S"; after[9] = "$majflt"; after[11] = "$utime"; after[12] = "$stime"; after[36] = "$core"
        return "4242 ($name) " + after.joinToString(" ")
    }

    @Test fun aThreadNameMayHoldSpacesParenthesesAndCommas() {
        assertEquals(".NET TP Worker", ThreadLog.statName(stat(".NET TP Worker", 0, 0, 0, 0)))
        assertEquals("a (b)_c", ThreadLog.statName(stat("a (b),c", 0, 0, 0, 0)))
        assertNull(ThreadLog.statName("garbage"))
    }

    @Test fun cpuTimeComesFromSchedstatWithTicksAsFallback() {
        val withSched = ThreadLog.task(7, stat("WorkerThread 0", 3, 10, 5, 6), "2000000000 500000000 99\n", "Cpus_allowed_list:\t0-7\n", 100)!!
        assertEquals(2_000_000_000L, withSched.runNs)
        assertEquals(500_000_000L, withSched.waitNs)
        assertEquals(6, withSched.core)
        assertEquals(3L, withSched.majflt)
        assertEquals("0-7", withSched.allowed)
        val ticksOnly = ThreadLog.task(7, stat("x", 0, 10, 5, 2), null, null, 100)!!
        assertEquals(150_000_000L, ticksOnly.runNs)
        assertEquals(-1L, ticksOnly.waitNs)
        assertEquals("", ticksOnly.allowed)
    }

    @Test fun allowedCpusKeepsCommasOutOfTheCsv() {
        assertEquals("0-1 5-7", ThreadLog.allowedCpus("Name:\tx\nCpus_allowed_list:\t0-1,5-7\n"))
    }

    @Test fun rowsGiveBusyThreadsFirstThenTheWholeProcess() {
        val before = mapOf(
            1 to ThreadLog.Task(1, "SlayTheSpire2.e", 0, 0, 0, 7, "0-7"),
            2 to ThreadLog.Task(2, "WorkerThread 0", 0, 0, 0, 5, "0-7"),
            3 to ThreadLog.Task(3, "idle", 0, 0, 0, 0, "0-7"),
        )
        val after = listOf(
            ThreadLog.Task(1, "SlayTheSpire2.e", 4_500_000_000, 250_000_000, 50, 7, "0-7"),
            ThreadLog.Task(2, "WorkerThread 0", 1_000_000_000, 0, 0, 5, "0-7"),
            ThreadLog.Task(3, "idle", 1_000_000, 0, 0, 0, "0-7"),
            ThreadLog.Task(4, "new thread", 9_000_000_000, 0, 0, 1, "0-7"),
        )
        val rows = ThreadLog.rows(1000, 99, "SlayTheSpire2.e", before, after, 5_000)
        assertEquals(3, rows.size)
        assertEquals("1000,99,1,SlayTheSpire2.e,SlayTheSpire2.e,90.0,5.0,10.0,7,0-7", rows[0])
        assertTrue(rows[1].startsWith("1000,99,2,SlayTheSpire2.e,WorkerThread 0,20.0,0.0,"))
        assertEquals("1000,99,,SlayTheSpire2.e,*,110.0,5.0,10.0,,", rows[2])
        assertTrue(ThreadLog.rows(1000, 99, "x", before, after, 0).isEmpty())
    }

    @Test fun liveLinesShowTheBusiestThreads() {
        val lines = ThreadLog.liveLines(listOf(
            "1,99,2,game,small,1.0,0.0,0.0,3,0-7",
            "1,99,1,game,main,90.0,5.0,0.0,7,0-7",
        ))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("main"))
    }
}
