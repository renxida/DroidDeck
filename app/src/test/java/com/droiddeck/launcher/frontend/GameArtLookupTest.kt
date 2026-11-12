package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.CredentialCipher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameArtLookupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() {
        SteamGridDb.setUserKey(app, "")
        SteamGridDb.keys = CredentialCipher.Keystore
    }

    @Test fun aSteamGridDbSearchResultIsTakenOnlyWhenItsNameMatches() {
        assertNull(SteamGridDb.match("My Obscure Game", listOf(5 to "Something Else")))
        assertEquals(6, SteamGridDb.match("The Witcher 3 - Wild Hunt", listOf(5 to "Witcher", 6 to "The Witcher 3: Wild Hunt")))
    }

    @Test fun theUsersKeyWinsOverTheBuildsAndNoneMeansNone() {
        assertEquals("user", SteamGridDb.pick("user", "build"))
        assertEquals("build", SteamGridDb.pick("", "build"))
        assertEquals("build", SteamGridDb.pick("  ", "build"))
        assertEquals("", SteamGridDb.pick("", ""))
        val builtIn = SteamGridDb.builtInKey()
        assertTrue(builtIn == BuildConfig.SGDB_API_KEY)
        assertTrue(SteamGridDb.key(app) == builtIn.trim())
    }

    @Test fun theUsersKeyIsSealedOnDiskAndClears() {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        SteamGridDb.keys = CredentialCipher.KeyProvider { key }
        SteamGridDb.setUserKey(app, "abcdef0123456789secretkey")
        val file = File(app.filesDir, "sgdb/key.json")
        assertFalse(file.readText().contains("secretkey"))
        assertTrue(CredentialCipher.isEnvelope(JSONObject(file.readText())))
        assertEquals("abcdef0123456789secretkey", SteamGridDb.key(app))
        SteamGridDb.setUserKey(app, "")
        assertFalse(file.exists())
        assertTrue(SteamGridDb.key(app) == SteamGridDb.builtInKey().trim())
    }

    @Test fun aFailedLookupIsNotRememberedAsNoMatch() {
        val marker = File(tmp.newFolder(), "sgdb-id")
        try {
            GameArt.remembered(marker) { throw SteamGridDb.Failed(SteamGridDb.Status.BAD_KEY) }
            fail("the failure was swallowed")
        } catch (e: SteamGridDb.Failed) {
            assertEquals(SteamGridDb.Status.BAD_KEY, e.status)
        }
        assertFalse(marker.exists())
    }

    @Test fun aMissIsKeptUntilTheKeyChangesOrTheUserAsksAgain() {
        val marker = File(tmp.newFolder(), "sgdb-id")
        var asked = 0
        assertNull(GameArt.remembered(marker) { asked++; null })
        assertEquals("none", marker.readText())
        assertNull(GameArt.remembered(marker) { asked++; "9" })
        assertEquals(1, asked)
        assertEquals("9", GameArt.remembered(marker, notBefore = System.currentTimeMillis() + 1000) { asked++; "9" })
        assertEquals(2, asked)
        assertEquals("9", GameArt.remembered(marker) { fail("asked again"); null })

        val old = File(marker.parentFile, "old-id").apply { writeText("none") }
        assertEquals("5", GameArt.remembered(old, retryMiss = true) { "5" })
        assertEquals("5", old.readText())
    }

    @Test fun savingAKeyMarksWhenItChanged() {
        SteamGridDb.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES") }
        assertEquals(0L, SteamGridDb.keyChangedAt(app))
        SteamGridDb.setUserKey(app, "abcdef0123456789")
        assertTrue(SteamGridDb.keyChangedAt(app) > 0L)
    }

    @Test fun aBadResponseSaysWhatWentWrong() {
        assertEquals(SteamGridDb.Status.BAD_KEY, SteamGridDb.failure(401))
        assertEquals(SteamGridDb.Status.BAD_KEY, SteamGridDb.failure(403))
        assertEquals(SteamGridDb.Status.LIMIT, SteamGridDb.failure(429))
        assertEquals(SteamGridDb.Status.SERVER, SteamGridDb.failure(503))
        assertNull(SteamGridDb.failure(404))
        assertNull(SteamGridDb.failure(200))
    }

    @Test fun searchResultsKeepOnlyGamesWithAnIdAndAName() {
        val data = JSONArray()
            .put(JSONObject().put("id", 7).put("name", "DONTFORGET").put("verified", true))
            .put(JSONObject().put("id", 0).put("name", "No id"))
            .put(JSONObject().put("id", 8).put("name", ""))
            .put("junk")
        val games = SteamGridDb.games(data)
        assertEquals(1, games.size)
        assertEquals(7, games[0].id)
        assertEquals("DONTFORGET", games[0].name)
        assertTrue(games[0].verified)
    }

    @Test fun eachSlotAsksItsOwnEndpoint() {
        assertEquals("heroes/game/5?nsfw=false&humor=false", SteamGridDb.path(5, GameArt.Slot.BACKGROUND))
        assertEquals("grids/game/5?dimensions=600x900&nsfw=false&humor=false", SteamGridDb.path(5, GameArt.Slot.COVER))
        assertEquals("grids/game/5?dimensions=460x215,920x430&nsfw=false&humor=false", SteamGridDb.path(5, GameArt.Slot.WIDE))
    }
}
