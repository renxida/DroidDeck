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
        resolve(LatencyLogComponent.FILE).writeText("# latency t0_wall_ms=1762941600000 t0_mono_ns=1\n${LatencyCsvWriter.COLUMNS}\n5,touch_up,40,9000,9500,25000,7,2,8333,60\n")
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
            listOf("clocks.log", FrameLogComponent.FILE, LatencyLogComponent.FILE, "events.jsonl", BuildInfo.FILE, "memory.csv").forEach {
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
            assertTrue(names.containsAll(listOf("upload.txt", BuildInfo.FILE, FrameLogComponent.FILE, LatencyLogComponent.FILE, "clocks.log",
                "memory.csv", "events.jsonl", "device.txt", "session.log")))
            val log = archive.getInputStream(archive.getEntry("session.log")).bufferedReader().readText()
            assertTrue(log.startsWith("first line\n"))
            assertTrue(log.trimEnd().endsWith("last line"))
            assertTrue(log.length < 6000)
            assertEquals("10,16666", archive.getInputStream(archive.getEntry(FrameLogComponent.FILE)).bufferedReader().readLines().last())
        }
    }

    @Test fun theUploadBundleCarriesCrashEvidenceNewestFirst() {
        val dir = session()
        dir.resolve("session.log").appendText("droiddeck-fex: SIGILL at 0x00007f0000001000 (si_code 01), offset 0x00001000\n" +
            "  in 7f0000000000-7f0000100000 r-xp 00000000 00:00 0 /game/libmono.so\nordinary line\n")
        dir.resolve("crash.log").writeText("--------- beginning of crash\nF libc: Fatal signal 11\n")
        dir.resolve("${AppExits.TRACE_PREFIX}1762941700000-anr.txt").writeText("\"main\" prio=5 tid=1 Blocked\n")
        dir.resolve("games/20251112-100000-abc-2868840").mkdirs()
        dir.resolve("games/20251112-100000-abc-2868840/steam-2868840.log").writeText("wine: Unhandled page fault on read access\n")
        val x64 = File(tmp.root, "steam-x64.log").apply { writeText("before the session\n") }
        val from = x64.length()
        x64.appendText("DROIDDECK_TIMELINE event=game_exec app_id=2868840 wall_ms=1762941610000\nUnhandled exception. System.NullReferenceException\n")
        val compat = File(tmp.root, "compatdata")
        val logs = File(compat, "2868840/pfx/drive_c/users/steamuser/AppData/Roaming/SlayTheSpire2/logs").apply { mkdirs() }
        File(logs, "godot2025-11-12T09.00.00.log").apply { writeText("old run\n"); setLastModified(1762941600000L - 3_600_000) }
        File(logs, "godot.log").apply { writeText("Godot Engine v4\nCrashHandlerException: Program crashed\n"); setLastModified(1762941700000L) }
        val unity = File(compat, "400/pfx/drive_c/users/steamuser/AppData/LocalLow/Studio/Game").apply { mkdirs() }
        File(unity, "Player.log").apply { writeText("unity\n"); setLastModified(1762941650000L) }
        dir.resolve("processes.csv").writeText("t_wall_ms,pid,name,side,rss_mb,swap_mb,cpu_pct\n1762941660000,10,steamwebhelper,droiddeck,300,200,4.0\n")
        val steamLogs = File(tmp.root, "steam-logs").apply { mkdirs() }
        File(steamLogs, "shader_log.txt").apply { writeText("[2025-11-12 10:01:00] Starting processing job for app 2868840\n"); setLastModified(1762941660000L) }
        File(steamLogs, "content_log.txt").apply { writeText("old download\n"); setLastModified(1762941600000L - 3_600_000) }
        File(steamLogs, "webhelper.txt").apply { writeText("not wanted\n"); setLastModified(1762941660000L) }
        val upload = File(tmp.root, "upload.zip")
        val sources = TelemetryBundle.Sources(1762941600000L, x64, from, listOf(compat), listOf("arm64" to steamLogs))
        assertTrue(TelemetryBundle.write(app, dir, upload, 4096, "kind: checkpoint", sources))
        ZipFile(upload).use { archive ->
            val names = archive.entries().toList().map { it.name }
            fun text(name: String) = archive.getInputStream(archive.getEntry(name)).bufferedReader().readText()
            val lines = text(CrashEvidence.LINES_FILE)
            assertTrue(lines.contains("session.log: droiddeck-fex: SIGILL at"))
            assertTrue(lines.contains("session.log:   in 7f0000000000"))
            assertTrue(lines.contains("steam-x64.log: DROIDDECK_TIMELINE event=game_exec app_id=2868840"))
            assertTrue(lines.contains("steam-x64.log: Unhandled exception."))
            assertFalse(lines.contains("ordinary line"))
            assertTrue(text("crash.log").contains("Fatal signal 11"))
            assertTrue(names.contains("${AppExits.TRACE_PREFIX}1762941700000-anr.txt"))
            assertFalse(text("steam-x64.log").contains("before the session"))
            assertTrue(text("proton/20251112-100000-abc-2868840/steam-2868840.log").contains("Unhandled page fault"))
            val game = "game-logs/2868840/AppData/Roaming/SlayTheSpire2/logs/godot.log"
            assertTrue(text(game).contains("Program crashed"))
            assertFalse(names.any { it.contains("godot2025") })
            assertTrue(names.indexOf(game) < names.indexOf("game-logs/400/AppData/LocalLow/Studio/Game/Player.log"))
            assertTrue(text("processes.csv").contains("steamwebhelper,droiddeck,300,200,4.0"))
            assertTrue(text("steam-logs/arm64/shader_log.txt").contains("processing job for app 2868840"))
            assertFalse(names.contains("steam-logs/arm64/content_log.txt"))
            assertFalse(names.any { it.endsWith("webhelper.txt") })
        }
    }

    @Test fun theUploadBundleCarriesTheSessionScreenshotAsIs() {
        val dir = session()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2048) { it.toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        dir.resolve(com.droiddeck.launcher.session.SessionScreenshot.FILE).writeBytes(jpeg)
        val upload = File(tmp.root, "upload.zip")
        assertTrue(TelemetryBundle.write(app, dir, upload, 4096, "kind: checkpoint", TelemetryBundle.Sources(1762941600000L, null, -1, emptyList())))
        ZipFile(upload).use { archive ->
            val entry = archive.getEntry(com.droiddeck.launcher.session.SessionScreenshot.FILE)
            assertNotNull(entry)
            assertTrue(jpeg.contentEquals(archive.getInputStream(entry).readBytes()))
        }
    }

    @Test fun theUploadBundleCarriesGuestSampleFilesAsIsOnlyWhenWrittenThisSession() {
        val dir = session()
        val bytes = ByteArray(4096) { (it * 7).toByte() }
        val fresh = File(tmp.root, "native-samples.zip").apply { writeBytes(bytes) }
        val start = fresh.lastModified() - 1000
        val upload = File(tmp.root, "upload.zip")
        assertTrue(TelemetryBundle.write(app, dir, upload, 4096, "kind: checkpoint",
            TelemetryBundle.Sources(start, null, -1, emptyList(), guestFiles = listOf("native-samples.zip" to fresh))))
        ZipFile(upload).use { archive ->
            assertTrue(bytes.contentEquals(archive.getInputStream(archive.getEntry("native-samples.zip")).readBytes()))
        }
        val stale = File(tmp.root, "upload2.zip")
        assertTrue(TelemetryBundle.write(app, dir, stale, 4096, "kind: checkpoint",
            TelemetryBundle.Sources(fresh.lastModified() + 3_600_000, null, -1, emptyList(), guestFiles = listOf("native-samples.zip" to fresh))))
        ZipFile(stale).use { assertNull(it.getEntry("native-samples.zip")) }
    }

    @Test fun theFirstUploadNamesNoBranchSoAnEmptyRepositoryGetsItsFirstCommit() {
        val first = org.json.JSONObject(TelemetryUploader.requestHead("runs/a/b/c.zip", null) + "QQ==\"}")
        assertEquals(setOf("message", "content"), first.keys().asSequence().toSet())
        assertEquals("telemetry runs/a/b/c.zip", first.getString("message"))
        assertEquals("QQ==", first.getString("content"))
        val update = org.json.JSONObject(TelemetryUploader.requestHead("runs/a/b/c.zip", "abc123") + "QQ==\"}")
        assertEquals("abc123", update.getString("sha"))
        assertFalse(update.has("branch"))
    }

    @Test fun appExitsAttachToTheSessionThatWasRunning() {
        val dir = session()
        val start = TelemetryBundle.sessionStartMs(dir)
        assertEquals(1762941600000L, start)
        dir.listFiles()!!.forEach { it.setLastModified(start + 600_000) }
        assertTrue(AppExits.wasActive(dir, start + 620_000))
        assertFalse(AppExits.wasActive(dir, start + 3_600_000))
        assertFalse(AppExits.wasActive(dir, start - 1))
        assertEquals("anr", AppExits.reasonName(android.app.ApplicationExitInfo.REASON_ANR))
        assertEquals("crash_native", AppExits.reasonName(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE))
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
