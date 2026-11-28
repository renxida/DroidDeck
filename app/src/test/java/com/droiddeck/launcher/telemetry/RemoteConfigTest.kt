package com.droiddeck.launcher.telemetry

import android.content.Context
import com.droiddeck.launcher.session.Experiments
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteConfigTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private val session get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @After fun tearDown() {
        session.edit().clear().commit()
        app.getSharedPreferences("remote_config", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        File(TelemetryUploadSettings.dir(app), "remote-config.json").delete()
    }

    private fun exit(runMs: Long, status: Int = 0) =
        GameExitWatch.Exit("2868840", 10, "godot", runMs, status, null, "exited", emptyList())

    @Test fun inputLayoutsReplaceTheSts2PadOnlyWhenTheListChangesAndNeverThePersonsOwn() {
        val libraries = com.droiddeck.launcher.input.InputLibraries
        val pad = com.droiddeck.launcher.input.Sts2Pad
        val stock = pad.layout()
        val bigger = stock.copy(elements = stock.elements.map { if (it.id == "legacy:a") it.copy(height = .2f) else it })
        val overlay = libraries.read(app).layouts.single { it.id == libraries.OVERLAY }
        val list = org.json.JSONArray().put(libraries.encodeLayout(bigger)).put(libraries.encodeLayout(overlay.copy(name = "taken")))
        val config = JSONObject().put("schema", 1).put("input", JSONObject().put("layouts", list))
        val applied = RemoteConfig.apply(app, config, "test")
        assertEquals(listOf(pad.LAYOUT), applied.layouts)
        assertTrue(applied.skipped.contains("layout:" + libraries.OVERLAY))
        assertEquals(.2f, libraries.read(app).layouts.single { it.id == pad.LAYOUT }.elements.single { it.id == "legacy:a" }.height, 1e-6f)
        assertEquals(overlay.name, libraries.read(app).layouts.single { it.id == libraries.OVERLAY }.name)
        // An edit on the device stays while the list is the same, and a new list wins again.
        libraries.update(app) { it.withLayout(stock) }
        assertTrue(RemoteConfig.apply(app, config, "test").layouts.isEmpty())
        assertEquals(stock, libraries.read(app).layouts.single { it.id == pad.LAYOUT })
        val again = JSONObject().put("schema", 1).put("input", JSONObject().put("layouts",
            org.json.JSONArray().put(libraries.encodeLayout(bigger.copy(name = "Slay the Spire 2 (big A)")))))
        assertEquals(listOf(pad.LAYOUT), RemoteConfig.apply(app, again, "test").layouts)
        assertEquals("Slay the Spire 2 (big A)", libraries.read(app).layouts.single { it.id == pad.LAYOUT }.name)
    }

    @Test fun sessionSettingsAreWrittenWithTheirOwnTypeAndNullResets() {
        session.edit().putBoolean("gpuClockPin", true).putInt("ramCapMb", 4096).putString("fexPreset", "safe").commit()
        val applied = RemoteConfig.apply(app, JSONObject("""{"schema":1,"session":{"gpuClockPin":false,"ramCapMb":6144,"fexPreset":null}}"""), "test")
        assertFalse(session.getBoolean("gpuClockPin", true))
        assertEquals(6144, session.getInt("ramCapMb", 0))
        assertFalse(session.contains("fexPreset"))
        assertTrue(applied.skipped.isEmpty())
    }

    @Test fun telemetryAndUnknownKeysAreNeverWritten() {
        val applied = RemoteConfig.apply(app, JSONObject("""{"session":{"telemetryOff":true,"somethingElse":1,"gpuClockPin":"yes"}}"""), "test")
        assertFalse(session.contains("telemetryOff"))
        assertFalse(session.contains("somethingElse"))
        assertFalse(session.contains("gpuClockPin"))
        assertEquals(listOf("telemetryOff", "somethingElse", "gpuClockPin"), applied.skipped)
    }

    @Test fun rotationMovesOnOnlyAfterAPlayedSession() {
        val cfg = JSONObject("""{"experiments":{"always":["sched"],"rotate":[["memory"],["warmup","two-frames","layer-fence"]]}}""")
        val first = RemoteConfig.apply(app, cfg, "test")
        assertEquals(0, first.row)
        assertEquals(setOf("sched", "memory"), first.experiments)
        assertEquals(0, RemoteConfig.apply(app, cfg, "test").row)
        RemoteConfig.gameExited(app, exit(30_000))
        assertEquals(0, RemoteConfig.apply(app, cfg, "test").row)
        RemoteConfig.gameExited(app, exit(300_000))
        val second = RemoteConfig.apply(app, cfg, "test")
        assertEquals(1, second.row)
        // one of a group: layer-fence comes later and replaces two-frames
        assertEquals(setOf("sched", "warmup", "layer-fence"), second.experiments)
        assertEquals(second.experiments, Experiments.enabled(app))
        RemoteConfig.gameExited(app, exit(5_000, status = 1))
        assertEquals(0, RemoteConfig.apply(app, cfg, "test").row)
        RemoteConfig.markPlayed(app)
        assertEquals(1, RemoteConfig.apply(app, cfg, "test").row)
    }

    @Test fun applyCachedUsesTheKeptCopyAndSnapshotListsSettings() {
        File(TelemetryUploadSettings.dir(app), "remote-config.json").writeText("""{"experiments":{"on":["sched"]},"session":{"gpuClockPin":false}}""")
        val applied = RemoteConfig.applyCached(app)!!
        assertEquals(setOf("sched"), applied.experiments)
        val dir = tmp.newFolder("s")
        RemoteConfig.writeSnapshot(app, dir, applied, applied.experiments!!)
        val text = File(dir, RemoteConfig.SETTINGS_FILE).readText()
        assertTrue(text, text.contains("gpuClockPin=false"))
        assertTrue(text, text.contains("remote config: fetched"))
    }

    @Test fun noKeptCopyAppliesNothing() {
        assertEquals(null, RemoteConfig.applyCached(app))
    }
}
