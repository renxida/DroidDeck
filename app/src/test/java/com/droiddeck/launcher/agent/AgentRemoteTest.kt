package com.droiddeck.launcher.agent

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
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

    private val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val pub = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)

    private fun seal(payload: String, key: java.security.PrivateKey = pair.private): JSONObject {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }
        return JSONObject().put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("sig", Base64.encodeToString(sig, Base64.NO_WRAP))
    }

    @Test
    fun onlySignedFreshCommandsForThisFileOpen() {
        val good = """{"id": "c1", "expires": 2000, "method": "state"}"""
        val (command, refused) = AgentRemote.unseal(seal(good), "c1", 1000, pub)
        assertNull(refused)
        assertNotNull(command)
        assertEquals("state", command!!.optString("method"))
        assertEquals("id does not match the file name", AgentRemote.unseal(seal(good), "c2", 1000, pub).second)
        assertEquals("expired", AgentRemote.unseal(seal(good), "c1", 3000, pub).second)
        val other = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertEquals("bad signature", AgentRemote.unseal(seal(good, other.private), "c1", 1000, pub).second)
        val tampered = seal(good).put("payload", Base64.encodeToString(
            """{"id": "c1", "expires": 2000, "method": "guest"}""".toByteArray(), Base64.NO_WRAP))
        assertEquals("bad signature", AgentRemote.unseal(tampered, "c1", 1000, pub).second)
        assertEquals("no signature", AgentRemote.unseal(JSONObject().put("payload", "e30="), "c1", 1000, pub).second)
    }
}
