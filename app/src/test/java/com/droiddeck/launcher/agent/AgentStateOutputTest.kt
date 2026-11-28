package com.droiddeck.launcher.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentStateOutputTest {
    private fun session(running: Boolean, size: Pair<Int, Int>) =
        JSONObject().apply { AgentBridgeProvider.outputFields(running, size).forEach { (key, value) -> put(key, value) } }

    @Test fun aRunningSessionReportsItsOutput() {
        val state = session(true, 1616 to 720)
        assertEquals("[1616,720]", state.getJSONArray("output").toString())
        assertEquals(true, state.isNull("outputPlaceholder"))
        assertEquals(true, state.has("outputPlaceholder"))
    }

    @Test fun withNoSessionOutputIsNullAndTheHeldSizeIsNamedAPlaceholder() {
        val state = session(false, 1920 to 1080)
        assertEquals(true, state.has("output"))
        assertEquals(true, state.isNull("output"))
        assertEquals("[1920,1080]", state.getJSONArray("outputPlaceholder").toString())
    }
}
