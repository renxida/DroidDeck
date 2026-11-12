package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.PhantomProcessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExitCauseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun guest(
        status: Int,
        stop: Boolean = false,
        phantom: PhantomProcessStatus = PhantomProcessStatus.DISABLED,
        xiaomi: Boolean = false,
        pressure: Boolean = false,
    ) = ExitCause.ofGuest(status, stop, phantom, xiaomi, pressure)

    @Test fun waitForStatusesAboveOneHundredTwentyEightAreSignals() {
        assertEquals(9, ExitCause.signalOf(137))
        assertEquals(11, ExitCause.signalOf(139))
        assertNull(ExitCause.signalOf(128))
        assertNull(ExitCause.signalOf(1))
        assertNull(ExitCause.signalOf(255))
    }

    @Test fun cleanAndOrdinaryExitCodesAreNotKills() {
        assertEquals(ExitCause.Kind.CLEAN, guest(0).kind)
        val code = guest(1)
        assertEquals(ExitCause.Kind.EXIT_CODE, code.kind)
        assertNull(code.signal)
        assertNull(ExitCause.failureCode(code))
    }

    @Test fun sigkillWithTheChildProcessLimitOnBlamesThePhantomProcessKiller() {
        for (status in listOf(PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET)) {
            val verdict = guest(137, phantom = status, xiaomi = true, pressure = true)
            assertEquals(ExitCause.Kind.KILLED, verdict.kind)
            assertEquals(ExitCause.Suspect.PHANTOM_PROCESS_KILLER, verdict.suspect)
            assertEquals(ExitCause.FAILURE_PHANTOM, ExitCause.failureCode(verdict))
        }
    }

    @Test fun anUnverifiableLimitIsNotBlamed() {
        val verdict = guest(137, phantom = PhantomProcessStatus.UNREADABLE)
        assertEquals(ExitCause.Suspect.UNKNOWN, verdict.suspect)
        assertNull(ExitCause.failureCode(verdict))
    }

    @Test fun sigkillOnXiaomiBlamesTheVendorWatchdogThenMemoryPressure() {
        assertEquals(ExitCause.Suspect.VENDOR_MEMORY_WATCHDOG, guest(137, xiaomi = true).suspect)
        assertEquals(ExitCause.FAILURE_VENDOR, ExitCause.failureCode(guest(137, xiaomi = true)))
        val pressure = guest(137, pressure = true)
        assertEquals(ExitCause.Suspect.MEMORY_PRESSURE, pressure.suspect)
        assertNull(ExitCause.failureCode(pressure))
    }

    @Test fun aRequestedStopIsNeverBlamedOnAnyone() {
        val verdict = guest(137, stop = true, phantom = PhantomProcessStatus.ENABLED, xiaomi = true)
        assertEquals(ExitCause.Suspect.NONE, verdict.suspect)
        assertNull(ExitCause.failureCode(verdict))
    }

    @Test fun crashSignalsAndHangupsHaveTheirOwnClasses() {
        assertEquals(ExitCause.Kind.CRASH_SIGNAL, guest(139).kind)
        assertEquals(ExitCause.Kind.CRASH_SIGNAL, guest(134).kind)
        assertEquals(ExitCause.Kind.HANGUP, guest(129).kind)
        assertEquals(ExitCause.Kind.TERMINATED, guest(143).kind)
    }

    @Test fun xiaomiFamilyMatchesManufacturerOrBrand() {
        assertTrue(ExitCause.isXiaomiFamily("Xiaomi", "POCO"))
        assertTrue(ExitCause.isXiaomiFamily("Google", "Redmi"))
        assertEquals(false, ExitCause.isXiaomiFamily("Google", "google"))
        assertEquals(false, ExitCause.isXiaomiFamily(null, null))
    }

    @Test fun appExitRecordsMapToLowMemoryCpuAndNativeCrashes() {
        assertEquals(ExitCause.Kind.LOW_MEMORY, ExitCause.ofAppExit(ExitCause.REASON_LOW_MEMORY, 0, null).kind)
        val child = ExitCause.ofAppExit(ExitCause.REASON_EXCESSIVE_RESOURCE_USAGE, 9,
            "Caused by child process: excessive cpu 40000 during 300000 dur=600000 limit=2")
        assertEquals(ExitCause.Kind.EXCESSIVE_CPU, child.kind)
        assertEquals(ExitCause.Suspect.PHANTOM_PROCESS_KILLER, child.suspect)
        val native = ExitCause.ofAppExit(ExitCause.REASON_CRASH_NATIVE, 11, null)
        assertEquals(ExitCause.Kind.APP_NATIVE_CRASH, native.kind)
        assertEquals(ExitCause.Suspect.GPU_OR_NATIVE, native.suspect)
    }

    @Test fun anAbandonedSessionTakesTheFirstExitRecordAfterItStarted() {
        val exits = listOf(
            ExitCause.AppExit(500, ExitCause.REASON_CRASH, 0, "before the session"),
            ExitCause.AppExit(2_000, ExitCause.REASON_USER_REQUESTED, 0, null),
            ExitCause.AppExit(1_500, ExitCause.REASON_LOW_MEMORY, 0, null),
        )
        assertEquals(ExitCause.Kind.LOW_MEMORY, ExitCause.ofAbandoned(1_000, 1_400, 10_000, exits).kind)
    }

    @Test fun anAbandonedSessionWithoutARecordFromBeforeThisBootIsADeviceReboot() {
        val verdict = ExitCause.ofAbandoned(1_000, 5_000, 65_000, emptyList())
        assertEquals(ExitCause.Kind.DEVICE_REBOOT, verdict.kind)
        assertTrue(verdict.detail, verdict.detail.contains("60 s"))
        assertEquals(ExitCause.Kind.UNKNOWN, ExitCause.ofAbandoned(1_000, 70_000, 65_000, emptyList()).kind)
        assertEquals(ExitCause.Kind.UNKNOWN, ExitCause.ofAbandoned(null, null, 65_000, emptyList()).kind)
    }

    @Test fun theLogLineIsOneGreppableLine() {
        val line = ExitCause.line("session", guest(137, phantom = PhantomProcessStatus.ENABLED))
        assertTrue(line, line.startsWith("DROIDDECK_EXIT_CAUSE scope=session class=sigkill suspect=phantom_process_killer status=137 signal=SIGKILL detail=\""))
        assertEquals(1, line.lines().size)
        val quoted = ExitCause.line("app", ExitCause.ofAppExit(ExitCause.REASON_ANR, 0, "a \"quoted\"\nreason"))
        assertEquals(1, quoted.lines().size)
        assertTrue(quoted, quoted.endsWith("detail=\"a 'quoted' reason\""))
    }

    @Test fun psiAveragesAreRead() {
        val (some, full) = ExitCause.parsePsi("some avg10=12.50 avg60=3.00 avg300=1.00 total=1\nfull avg10=4.25 avg60=1.00 avg300=0.50 total=2\n")
        assertEquals(12.5, some!!, 0.0)
        assertEquals(4.25, full!!, 0.0)
        assertEquals(null to null, ExitCause.parsePsi(""))
    }

    @Test fun eventTimesAreTheFirstAndLastEvent() {
        val events = tmp.newFile("events.jsonl")
        events.writeText(
            "{\"t\":100,\"event\":\"session.created\"}\n" +
                "not json\n" +
                "{\"t\":250,\"event\":\"frame.first\",\"sessionId\":\"x\"}\n"
        )
        assertEquals(100L to 250L, SessionArtifacts.eventTimes(events))
        assertEquals(null to null, SessionArtifacts.eventTimes(java.io.File(tmp.root, "missing.jsonl")))
    }
}
