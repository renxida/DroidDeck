package com.droiddeck.launcher.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoTestPlanTest {
    private val idle = AutoTestPlan.State(overlay = true, charging = true, battery = 80, interactive = false,
        sessionBusy = false, nowMs = 10_000_000L)
    private val none = AutoTestPlan.Record(null, 0, 0L, false)
    private val request = AutoTestPlan.Request("tex-astc-1", 2868840, 240, SteamBranch.BETA, 0)

    @Test
    fun parsesTheConfigEntryAndRejectsBadIds() {
        val config = JSONObject("""{"autotest": {"id": "tex-astc-1", "app": 2868840, "menu_seconds": 5000, "branch": "x"}}""")
        assertEquals(AutoTestPlan.Request("tex-astc-1", 2868840, 1800, SteamBranch.BETA, 0), AutoTestPlan.parse(config))
        assertNull(AutoTestPlan.parse(JSONObject("""{"autotest": {"id": "a b", "app": 2868840}}""")))
        assertNull(AutoTestPlan.parse(JSONObject("""{"autotest": {"id": "ok"}}""")))
        assertNull(AutoTestPlan.parse(JSONObject("{}")))
        assertNull(AutoTestPlan.parse(null))
    }

    @Test
    fun runsOnlyWhileChargingIdleAndAllowed() {
        assertNull(AutoTestPlan.skip(request, idle, none))
        assertEquals("needs Display over other apps", AutoTestPlan.skip(request, idle.copy(overlay = false), none))
        assertEquals("not charging", AutoTestPlan.skip(request, idle.copy(charging = false), none))
        assertEquals("battery 20%", AutoTestPlan.skip(request, idle.copy(battery = 20), none))
        assertEquals("screen is on", AutoTestPlan.skip(request, idle.copy(interactive = true), none))
        assertEquals("a session is running", AutoTestPlan.skip(request, idle.copy(sessionBusy = true), none))
        assertEquals("no test requested", AutoTestPlan.skip(null, idle, none))
    }

    @Test
    fun aTestIdRunsOnceWithOneRetry() {
        val first = AutoTestPlan.nextRecord(request, none, idle.nowMs)
        assertEquals(AutoTestPlan.Record("tex-astc-1", 1, idle.nowMs, false), first)
        assertNull(AutoTestPlan.skip(request, idle, first))
        val second = AutoTestPlan.nextRecord(request, first, idle.nowMs + 1)
        assertEquals(2, second.attempts)
        assertEquals("gave up after 2 tries", AutoTestPlan.skip(request, idle, second))
        assertEquals("already ran", AutoTestPlan.skip(request, idle, first.copy(completed = true)))
        assertNull(AutoTestPlan.skip(request.copy(id = "tex-astc-2"), idle, first.copy(completed = true)))
    }

    @Test
    fun repeatingTestsWaitTheirHours() {
        val nightly = request.copy(repeatHours = 20)
        val ran = AutoTestPlan.Record(nightly.id, 1, idle.nowMs - 3_600_000L, true)
        assertEquals("ran 60 min ago", AutoTestPlan.skip(nightly, idle, ran))
        assertNull(AutoTestPlan.skip(nightly, idle, ran.copy(startedMs = idle.nowMs - 21 * 3_600_000L)))
        assertEquals(1, AutoTestPlan.nextRecord(nightly, ran, idle.nowMs).attempts)
    }

    @Test
    fun stopsAfterTheMenuTimeOnUnplugOrAtTheLimit() {
        assertNull(AutoTestPlan.stopReason(60_000L, 0L, 240, charging = true))
        assertEquals("done", AutoTestPlan.stopReason(300_000L, 240_000L, 240, charging = true))
        assertEquals("unplugged", AutoTestPlan.stopReason(10_000L, 0L, 240, charging = false))
        assertEquals("timeout", AutoTestPlan.stopReason(AutoTestPlan.LIMIT_MS + 240_000L, 0L, 240, charging = true))
    }
}
