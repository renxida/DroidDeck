package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExperimentsTest {
    private lateinit var context: Context
    private lateinit var guest: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("experiments", Context.MODE_PRIVATE).edit().clear().commit()
        guest = File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/game-environment.json")
        File(context.filesDir, "game-environment.json").delete()
        Experiments.endSession()
    }

    @After fun tearDown() {
        context.getSharedPreferences("experiments", Context.MODE_PRIVATE).edit().clear().commit()
        Experiments.endSession()
    }

    private fun shared() = JSONObject(guest.readText()).getJSONObject("shared")

    @Test fun everySwitchSetsItsOwnVariableOnlyWhenOn() {
        val expected = mapOf(
            "godot-render" to "DROIDDECK_EXP_GODOT_RENDER", "warmup" to "DROIDDECK_EXP_WARMUP",
            "all" to "DROIDDECK_EXP_ALL", "compat-quiet" to "DROIDDECK_EXP_COMPAT_QUIET",
            "memory" to "DROIDDECK_EXP_MEMORY", "adpf-all" to "DROIDDECK_EXP_ADPF_ALL",
            "two-frames" to "DROIDDECK_EXP_TWO_FRAMES", "layer-fence" to "DROIDDECK_EXP_LAYER_FENCE",
            "sched" to "DROIDDECK_EXP_SCHED", "one-limiter" to "DROIDDECK_EXP_ONE_LIMITER",
            "native-hz" to "DROIDDECK_EXP_NATIVE_HZ", "quick-tap" to "DROIDDECK_EXP_QUICK_TAP",
            "fex-codegen" to "DROIDDECK_EXP_FEX_CODEGEN",
            "fex-memcpy-tso" to "DROIDDECK_EXP_FEX_MEMCPY_TSO", "fex-vector-tso" to "DROIDDECK_EXP_FEX_VECTOR_TSO",
            "fex-tso" to "DROIDDECK_EXP_FEX_TSO", "fex-fast" to "DROIDDECK_EXP_FEX_FAST",
            "dotnet-trace" to "DROIDDECK_EXP_DOTNET_TRACE", "dotnet-gen0" to "DROIDDECK_EXP_DOTNET_GEN0",
            "dotnet-gc-stock" to "DROIDDECK_EXP_DOTNET_GC_STOCK",
            "fex-kestrel" to "DROIDDECK_EXP_FEX_KESTREL", "fex-stats" to "DROIDDECK_EXP_FEX_STATS",
            "wayland-present" to "DROIDDECK_EXP_WAYLAND_PRESENT",
            "vk-count" to "DROIDDECK_EXP_VK_COUNT",
            "native-inventory" to "DROIDDECK_EXP_NATIVE_INVENTORY",
            "free-memory" to "DROIDDECK_EXP_FREE_MEMORY",
            "hybris-index" to "DROIDDECK_EXP_HYBRIS_INDEX",
            "fex-l1" to "DROIDDECK_EXP_FEX_L1",
            "hybris-index-session" to "DROIDDECK_EXP_HYBRIS_INDEX_SESSION",
            "native-engine" to "DROIDDECK_EXP_NATIVE_ENGINE",
            "dxvk-arm64ec" to "DROIDDECK_EXP_DXVK_ARM64EC",
            "composite-timing" to "DROIDDECK_EXP_COMPOSITE_TIMING",
            "dotnet-r2r" to "DROIDDECK_EXP_DOTNET_R2R",
            "fex-code-buffer" to "DROIDDECK_EXP_FEX_CODE_BUFFER",
            "fex-sample" to "DROIDDECK_EXP_FEX_SAMPLE",
        )
        assertEquals(expected.keys.toList(), Experiments.ids)
        assertEquals(Experiments.ids.toSet(), (Experiments.toggles + Experiments.present + Experiments.fexOrder).map { it.id }.toSet())
        Experiments.set(context, emptySet())
        for ((id, name) in expected) {
            Experiments.setOne(context, id, true)
            assertEquals(mapOf(name to "1"), Experiments.env(Experiments.enabled(context)))
            assertEquals("1", shared().getString(name))
            Experiments.setOne(context, id, false)
            assertEquals(emptyMap<String, String>(), Experiments.env(Experiments.enabled(context)))
            assertFalse(shared().has(name))
        }
    }

    @Test fun aFreshInstallTurnsEverythingOnButLayerFenceAndTheFrameCap() {
        val everything = setOf("godot-render", "warmup", "all", "compat-quiet", "memory", "adpf-all", "two-frames",
            "one-limiter", "native-hz", "quick-tap", "fex-codegen", "fex-fast", "fex-stats", "free-memory")
        assertEquals(everything, Experiments.defaults)
        assertEquals(everything, Experiments.enabled(context))
        assertEquals(1, Experiments.presentMode(Experiments.enabled(context)))
        assertEquals("godot-render,warmup,all,compat-quiet,memory,adpf-all,two-frames,one-limiter,native-hz,quick-tap,fex-codegen,fex-fast,fex-stats,free-memory",
            Experiments.line(Experiments.enabled(context)))
        GameEnvironmentStore.publish(context)
        assertEquals(everything.map { Experiments.envName(it) }.toSet(),
            shared().keys().asSequence().filter { it.startsWith("DROIDDECK_EXP_") }.toSet())
        Experiments.set(context, setOf(Experiments.SCHED))
        Experiments.set(context, Experiments.defaults)
        assertEquals(everything, Experiments.enabled(context))
    }

    @Test fun resetToBaselinePublishesMainsEnvironment() {
        Experiments.set(context, emptySet())
        assertEquals(emptySet<String>(), Experiments.enabled(context))
        assertEquals("none", Experiments.line(Experiments.enabled(context)))
        assertEquals(0, Experiments.presentMode(Experiments.enabled(context)))
        GameEnvironmentStore.publish(context)
        val published = shared()
        val names = published.keys().asSequence().toSet()
        assertTrue(names.none { it.startsWith("DROIDDECK_EXP_") })
        val main = GameEnvironment.defaults(SessionPrefs.fexPreset(context), SessionPrefs.forceSsbs(context))
        assertEquals(main.keys, names)
        main.forEach { (name, value) -> assertEquals(value, if (published.isNull(name)) null else published.getString(name)) }
    }

    @Test fun eachGroupKeepsAtMostOneOn() {
        Experiments.set(context, emptySet())
        Experiments.setOne(context, Experiments.TWO_FRAMES, true)
        Experiments.setOne(context, Experiments.LAYER_FENCE, true)
        assertEquals(setOf(Experiments.LAYER_FENCE), Experiments.enabled(context))
        assertEquals(2, Experiments.presentMode(Experiments.enabled(context)))
        Experiments.setOne(context, Experiments.FEX_TSO, true)
        Experiments.setOne(context, Experiments.FEX_FAST, true)
        Experiments.setOne(context, Experiments.WARMUP, true)
        assertEquals(setOf(Experiments.LAYER_FENCE, Experiments.FEX_FAST, Experiments.WARMUP), Experiments.enabled(context))
        assertEquals("warmup,layer-fence,fex-fast", Experiments.line(Experiments.enabled(context)))
    }

    @Test fun buildTxtNamesTheSessionsExperiments() {
        Experiments.set(context, emptySet())
        assertTrue(com.droiddeck.launcher.telemetry.BuildInfo.text(context).lines().contains("experiments_at_session_start: none"))
        Experiments.setOne(context, Experiments.WARMUP, true)
        Experiments.setOne(context, Experiments.FEX_FAST, true)
        Experiments.beginSession(context)
        assertTrue(com.droiddeck.launcher.telemetry.BuildInfo.text(context).lines().contains("experiments_at_session_start: warmup,fex-fast"))
    }

    @Test fun eachLaunchRecordsTheSwitchesInEffect() {
        val fields = { line: String -> com.droiddeck.launcher.telemetry.FrameLogComponent.experimentsFields(line) }
        assertEquals(mapOf("on" to "none"), fields("droiddeck-game-env: experiments: none"))
        assertEquals(mapOf("on" to "two-frames,fex-fast", "pending" to "two-frames off, memory(app) on"),
            fields("droiddeck-game-env: experiments: two-frames,fex-fast; saved but needs a new session: two-frames off, memory(app) on"))
    }

    @Test fun theSessionKeepsTheSwitchesItStartedWith() {
        Experiments.set(context, emptySet())
        Experiments.setOne(context, Experiments.MEMORY, true)
        assertEquals(setOf(Experiments.MEMORY), Experiments.beginSession(context))
        Experiments.setOne(context, Experiments.MEMORY, false)
        assertTrue(Experiments.on(context, Experiments.MEMORY))
        Experiments.endSession()
        assertFalse(Experiments.on(context, Experiments.MEMORY))
    }
}
