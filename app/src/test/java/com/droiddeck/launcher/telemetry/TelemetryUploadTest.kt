package com.droiddeck.launcher.telemetry

import com.droiddeck.launcher.core.DeviceReport
import com.droiddeck.launcher.session.EnhancedSessionLogShare
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.installJvmSecureSourceOpener
import com.droiddeck.launcher.session.resetSecureSourceOpener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelemetryUploadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private val token = "github_pat_11ABCDEFG0telemetryTOKENvalue_" + "x".repeat(40)

    @Before fun setUp() = installJvmSecureSourceOpener()

    @After fun tearDown() {
        resetSecureSourceOpener()
        TelemetryUploadSettings.clearToken(app)
        SessionLogShare.clear(app)
    }

    private fun session(): File = tmp.newFolder("2026-11-12-01-steam").apply {
        resolve("session.log").writeText("== STEP 10:00:00.000 starting the Steam client\n" + "guest line\n".repeat(50))
        resolve("events.jsonl").writeText("{\"t\":1762941600000,\"event\":\"session.created\",\"sessionId\":\"$name\"}\n")
        resolve("clocks.log").writeText("== clocks: test\nstart: wall_ms=1762941600000 tz_offset_min=0\n10:00:02 p0 1800/1800\n")
        resolve(FrameLogComponent.FILE).writeText("# frames t0_wall_ms=1762941600000 t0_mono_ns=1 source=gamescope\nt_ms,frametime_us\n10,16666\n")
        resolve("memory.csv").writeText("t_wall_ms,used_mb\n1762941600000,4000\n")
        resolve("device.txt").writeText("Model: test\n")
        BuildInfo.write(app, this)
    }

    private fun zipText(zip: File): String = ZipFile(zip).use { archive ->
        archive.entries().toList().joinToString("\n") { it.name + "\n" + archive.getInputStream(it).bufferedReader().readText() }
    }

    @Test fun theTokenNeverReachesShareBundlesReportsOrTheUpload() {
        assertTrue(TelemetryUploadSettings.setToken(app, token))
        TelemetryUploadSettings.setEnabled(app, true)
        val dir = session()

        val texts = ArrayList<String>()
        EnhancedSessionLogShare.create(app, dir).forEach { texts += zipText(it) }
        EnhancedSessionLogShare.createLegacy(app, dir)?.let { texts += zipText(it) }
        val upload = File(tmp.root, "upload.zip")
        assertTrue(TelemetryBundle.write(app, dir, upload, 1L shl 20, "kind: final"))
        texts += zipText(upload)
        runCatching { DeviceReport.build(app, "steam") }.getOrNull()?.let { texts += it }
        texts += BuildInfo.text(app)
        dir.walk().filter { it.isFile }.forEach { texts += it.readText() }

        assertTrue(texts.size >= 5)
        texts.forEach { assertFalse(it.contains(token)) }
        assertFalse(TelemetryUploadSettings.dir(app).path.startsWith(app.filesDir.path))
        assertEquals(token, TelemetryUploadSettings.token(app))
    }

    @Test fun theShareBundleNamesTheTelemetryFiles() {
        val dir = session()
        val sessionZip = EnhancedSessionLogShare.create(app, dir).single { it.name.endsWith("-session.zip") }
        ZipFile(sessionZip).use { archive ->
            listOf("clocks.log", FrameLogComponent.FILE, "events.jsonl", BuildInfo.FILE, "memory.csv").forEach {
                assertNotNull(it, archive.getEntry("${dir.name}/$it"))
            }
        }
    }

    @Test fun theUploadBundleCarriesTheTelemetryAndAnExcerptOfTheLog() {
        val dir = session()
        dir.resolve("session.log").writeText("first line\n" + "middle line\n".repeat(20_000) + "last line\n")
        val upload = File(tmp.root, "upload.zip")
        assertTrue(TelemetryBundle.write(app, dir, upload, 4096, "kind: checkpoint"))
        ZipFile(upload).use { archive ->
            val names = archive.entries().toList().map { it.name }.toSet()
            assertTrue(names.containsAll(listOf("upload.txt", BuildInfo.FILE, FrameLogComponent.FILE, "clocks.log",
                "memory.csv", "events.jsonl", "device.txt", "session.log")))
            val log = archive.getInputStream(archive.getEntry("session.log")).bufferedReader().readText()
            assertTrue(log.startsWith("first line\n"))
            assertTrue(log.trimEnd().endsWith("last line"))
            assertTrue(log.length < 6000)
            assertEquals("10,16666", archive.getInputStream(archive.getEntry(FrameLogComponent.FILE)).bufferedReader().readLines().last())
        }
    }

    @Test fun settingsRejectMalformedValues() {
        assertFalse(TelemetryUploadSettings.setToken(app, "short"))
        assertFalse(TelemetryUploadSettings.setToken(app, "has space in it but is long enough"))
        assertNull(TelemetryUploadSettings.token(app))
        assertFalse(TelemetryUploadSettings.setRepo(app, "no-slash"))
        assertFalse(TelemetryUploadSettings.setRepo(app, "../x"))
        assertEquals(TelemetryUploadSettings.DEFAULT_REPO, TelemetryUploadSettings.repo(app))
        assertTrue(TelemetryUploadSettings.setRepo(app, " someone/telemetry "))
        assertEquals("someone/telemetry", TelemetryUploadSettings.repo(app))
        assertFalse(TelemetryUploadSettings.active(app))
    }

    @Test fun uploadPathsAndBuildNamesAreSafe() {
        assertEquals("runs/perf-all-1a2b3c4/Google-Pixel-10/2025-11-12T10-00-00Z.zip",
            TelemetryUploader.remotePath("perf-all-1a2b3c4", "Google Pixel 10", 1762941600000L))
        assertEquals("perf/all", BuildInfo.branch("remote clean · perf/all at 1a2b3c4", ""))
        assertEquals("pr12", BuildInfo.branch("remote clean · pr12 at 1a2b3c4", ""))
        assertEquals("feature/x", BuildInfo.branch("remote clean · main at 1a2b3c4", "feature/x"))
        assertEquals("perf-all-1a2b3c4", BuildInfo.slug("remote clean · perf/all at 1a2b3c4", "", ""))
        assertEquals("main-abcdef0", BuildInfo.slug("remote clean · main at 1234567", "", "abcdef0123"))
    }
}
