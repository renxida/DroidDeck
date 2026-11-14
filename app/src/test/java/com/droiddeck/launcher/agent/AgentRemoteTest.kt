package com.droiddeck.launcher.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentRemoteTest {
    @Test
    fun commandIdsComeFromSafeJsonNamesOnly() {
        assertEquals("20261114-0001", AgentRemote.idFromName("20261114-0001.json"))
        assertNull(AgentRemote.idFromName("../x.json"))
        assertNull(AgentRemote.idFromName("a.jpg"))
        assertNull(AgentRemote.idFromName(".json"))
    }

    @Test
    fun pendingSkipsDoneDirectoriesAndOtherFilesInNameOrder() {
        val listing = JSONArray("""[
            {"name": "b.json", "type": "file", "sha": "2"},
            {"name": "a.json", "type": "file", "sha": "1"},
            {"name": "done.json", "type": "file", "sha": "3"},
            {"name": "sub.json", "type": "dir", "sha": "4"},
            {"name": "note.txt", "type": "file", "sha": "5"}]""")
        assertEquals(listOf("a" to "1", "b" to "2"), AgentRemote.pending(listing, setOf("done")))
    }
}
