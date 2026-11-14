package com.droiddeck.launcher.update

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class UpdateCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("self_update_test", Context.MODE_PRIVATE)

    class Request(val url: String, val headers: Map<String, String>)

    /** Answers each URL from a script and records what was asked; nothing touches the network. */
    class FakeHttp : UpdateHttp {
        val requests = ArrayList<Request>()
        var release: () -> UpdateHttp.Reply = { error("no release reply") }
        var apk: () -> UpdateHttp.Reply = { error("no APK reply") }

        override fun get(url: String, headers: Map<String, String>): UpdateHttp.Reply {
            requests += Request(url, HashMap(headers))
            return when (url) {
                UpdateCheck.RELEASE_API -> release()
                UpdateCheck.APK_URL -> apk()
                else -> error("unexpected $url")
            }
        }

        fun apkRequests() = requests.filter { it.url == UpdateCheck.APK_URL }
        fun releaseRequests() = requests.filter { it.url == UpdateCheck.RELEASE_API }
    }

    private fun releaseJson(id: Long = 7, updatedAt: String = "2026-11-14T18:47:45Z", size: Int = APK.size,
                            url: String = UpdateCheck.APK_URL) = """
        {"tag_name": "latest", "body": "APK from run 1",
         "assets": [{"name": "other.txt", "id": 1, "size": 3, "updated_at": "x", "browser_download_url": "https://example.invalid/x"},
                    {"name": "kestrel.apk", "id": $id, "size": $size, "updated_at": "$updatedAt", "browser_download_url": "$url"}]}
    """.trimIndent()

    private fun ok(body: ByteArray, etag: String?) = UpdateHttp.Reply(200, etag, body.size.toLong(), ByteArrayInputStream(body))
    private fun notModified() = UpdateHttp.Reply(304, null, -1, null)

    private fun updateCheck(http: FakeHttp, verify: (File) -> String? = { null }) =
        UpdateCheck(tmp.root, prefs, http, verify)

    @Test fun aNewReleaseIsDownloadedVerifiedAndItsTagsKept() {
        val http = FakeHttp().apply {
            release = { ok(releaseJson().toByteArray(), "\"r1\"") }
            apk = { ok(APK, "\"a1\"") }
        }
        val outcome = updateCheck(http).run()
        assertTrue(outcome is UpdateCheck.Outcome.Ready)
        assertTrue(APK.contentEquals((outcome as UpdateCheck.Outcome.Ready).file.readBytes()))
        assertNull(http.releaseRequests().single().headers["If-None-Match"])
        assertNull(http.apkRequests().single().headers["If-None-Match"])
        assertEquals("\"r1\"", prefs.getString(UpdateCheck.RELEASE_ETAG, null))
        assertEquals("\"a1\"", prefs.getString(UpdateCheck.APK_ETAG, null))
    }

    @Test fun anUnchangedReleaseCostsOneConditionalRequestAndNoDownload() {
        val http = FakeHttp().apply {
            release = { ok(releaseJson().toByteArray(), "\"r1\"") }
            apk = { ok(APK, "\"a1\"") }
        }
        updateCheck(http).run()
        http.release = { notModified() }
        val outcome = updateCheck(http).run()
        assertTrue("the staged APK is still offered", outcome is UpdateCheck.Outcome.Ready)
        assertEquals("\"r1\"", http.releaseRequests().last().headers["If-None-Match"])
        assertEquals(1, http.apkRequests().size)
    }

    @Test fun anEditedReleaseWithTheSameAssetDoesNotDownloadAgain() {
        val http = FakeHttp().apply {
            release = { ok(releaseJson().toByteArray(), "\"r1\"") }
            apk = { ok(APK, "\"a1\"") }
        }
        updateCheck(http).run()
        http.release = { ok(releaseJson().toByteArray(), "\"r2\"") }
        updateCheck(http).run()
        assertEquals(1, http.apkRequests().size)
        assertEquals("\"r2\"", prefs.getString(UpdateCheck.RELEASE_ETAG, null))
    }

    @Test fun aReplacedAssetIsAskedForConditionallyAndA304KeepsWhatIsThere() {
        val http = FakeHttp().apply {
            release = { ok(releaseJson().toByteArray(), "\"r1\"") }
            apk = { ok(APK, "\"a1\"") }
        }
        updateCheck(http).run()
        http.release = { ok(releaseJson(id = 8).toByteArray(), "\"r2\"") }
        http.apk = { notModified() }
        val outcome = updateCheck(http).run()
        assertEquals("\"a1\"", http.apkRequests().last().headers["If-None-Match"])
        assertTrue(outcome is UpdateCheck.Outcome.Ready)
        assertEquals("\"r2\"", prefs.getString(UpdateCheck.RELEASE_ETAG, null))
    }

    @Test fun aRejectedApkIsDeletedAndNotFetchedAgain() {
        val http = FakeHttp().apply {
            release = { ok(releaseJson().toByteArray(), "\"r1\"") }
            apk = { ok(APK, "\"a1\"") }
        }
        val outcome = updateCheck(http) { "signing certificates differ" }.run()
        assertEquals("rejected: signing certificates differ", outcome.line())
        assertFalse(File(tmp.root, UpdateCheck.APK).exists())
        assertFalse(File(tmp.root, UpdateCheck.APK + ".part").exists())
        http.release = { notModified() }
        assertTrue(updateCheck(http) { "signing certificates differ" }.run() is UpdateCheck.Outcome.UpToDate)
        assertEquals(1, http.apkRequests().size)
    }

    @Test fun aBrokenDownloadKeepsNoTagsSoItIsTriedAgain() {
        val short = APK.copyOf(APK.size - 1)
        val http = FakeHttp().apply {
            release = { ok(releaseJson().toByteArray(), "\"r1\"") }
            apk = { UpdateHttp.Reply(200, "\"a1\"", -1, ByteArrayInputStream(short)) }
        }
        assertTrue(updateCheck(http).run() is UpdateCheck.Outcome.Failed)
        assertNull(prefs.getString(UpdateCheck.RELEASE_ETAG, null))
        assertNull(prefs.getString(UpdateCheck.APK_ETAG, null))
        assertFalse(File(tmp.root, UpdateCheck.APK).exists())
        http.apk = { ok(APK, "\"a1\"") }
        assertTrue(updateCheck(http).run() is UpdateCheck.Outcome.Ready)
        assertNull(http.releaseRequests().last().headers["If-None-Match"])
    }

    @Test fun theApkComesOnlyFromTheFixedUrlWhateverTheReleaseNames() {
        val http = FakeHttp().apply {
            release = { ok(releaseJson(url = "https://example.invalid/evil.apk").toByteArray(), "\"r1\"") }
            apk = { ok(APK, "\"a1\"") }
        }
        updateCheck(http).run()
        assertEquals(listOf(UpdateCheck.RELEASE_API, UpdateCheck.APK_URL), http.requests.map { it.url })
    }

    @Test fun aReleaseWithoutTheApkOrAnErrorChangesNothing() {
        val http = FakeHttp().apply { release = { ok("""{"assets": []}""".toByteArray(), "\"r1\"") } }
        assertEquals("failed: the release has no kestrel.apk", updateCheck(http).run().line())
        http.release = { UpdateHttp.Reply(403, null, -1, null) }
        assertEquals("failed: release HTTP 403", updateCheck(http).run().line())
        assertNull(prefs.getString(UpdateCheck.RELEASE_ETAG, null))
        assertTrue(http.apkRequests().isEmpty())
    }

    @Test fun aStagedApkThatNoLongerVerifiesIsDropped() {
        val http = FakeHttp().apply {
            release = { ok(releaseJson().toByteArray(), "\"r1\"") }
            apk = { ok(APK, "\"a1\"") }
        }
        updateCheck(http).run()
        http.release = { notModified() }
        val outcome = updateCheck(http) { "same commit 2222222" }.run()
        assertTrue(outcome is UpdateCheck.Outcome.UpToDate)
        assertFalse(File(tmp.root, UpdateCheck.APK).exists())
    }

    private companion object {
        val APK = ByteArray(200_000) { (it * 31 % 251).toByte() }
    }
}
