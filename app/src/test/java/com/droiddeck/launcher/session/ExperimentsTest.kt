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
            "sched" to "DROIDDECK_EXP_SCHED", "fex-codegen" to "DROIDDECK_EXP_FEX_CODEGEN",
            "fex-memcpy-tso" to "DROIDDECK_EXP_FEX_MEMCPY_TSO", "fex-vector-tso" to "DROIDDECK_EXP_FEX_VECTOR_TSO",
            "fex-tso" to "DROIDDECK_EXP_FEX_TSO", "fex-fast" to "DROIDDECK_EXP_FEX_FAST",
        )
        assertEquals(expected.keys.toList(), Experiments.ids)
        assertEquals(Experiments.ids.toSet(), (Experiments.toggles + Experiments.present + Experiments.fexOrder).map { it.id }.toSet())
        for ((id, name) in expected) {
            Experiments.setOne(context, id, true)
            assertEquals(mapOf(name to "1"), Experiments.env(Experiments.enabled(context)))
            assertEquals("1", shared().getString(name))
            Experiments.setOne(context, id, false)
            assertEquals(emptyMap<String, String>(), Experiments.env(Experiments.enabled(context)))
            assertFalse(shared().has(name))
        }
    }

    @Test fun aFreshInstallPublishesMainsEnvironment() {
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

    @Test fun safeOnesLeaveOutFexOrderingLayerFenceAndTheFrameCap() {
        assertEquals(
            setOf("godot-render", "warmup", "all", "compat-quiet", "memory", "adpf-all", "two-frames", "fex-codegen"),
            Experiments.safe,
        )
        Experiments.set(context, setOf(Experiments.LAYER_FENCE, Experiments.FEX_TSO, Experiments.SCHED))
        Experiments.set(context, Experiments.safeOn(Experiments.enabled(context)))
        assertEquals(Experiments.safe + Experiments.FEX_TSO + Experiments.SCHED, Experiments.enabled(context))
        assertEquals(1, Experiments.presentMode(Experiments.enabled(context)))
        assertEquals(Experiments.safe, Experiments.safeOn(emptySet()))
        Experiments.set(context, emptySet())
        assertEquals("none", Experiments.line(Experiments.enabled(context)))
        assertTrue(shared().keys().asSequence().none { it.startsWith("DROIDDECK_EXP_") })
    }

    @Test fun buildTxtNamesTheSessionsExperiments() {
        assertTrue(com.droiddeck.launcher.telemetry.BuildInfo.text(context).lines().contains("experiments: none"))
        Experiments.setOne(context, Experiments.WARMUP, true)
        Experiments.setOne(context, Experiments.FEX_FAST, true)
        Experiments.beginSession(context)
        assertTrue(com.droiddeck.launcher.telemetry.BuildInfo.text(context).lines().contains("experiments: warmup,fex-fast"))
    }

    @Test fun theSessionKeepsTheSwitchesItStartedWith() {
        Experiments.setOne(context, Experiments.MEMORY, true)
        assertEquals(setOf(Experiments.MEMORY), Experiments.beginSession(context))
        Experiments.setOne(context, Experiments.MEMORY, false)
        assertTrue(Experiments.on(context, Experiments.MEMORY))
        Experiments.endSession()
        assertFalse(Experiments.on(context, Experiments.MEMORY))
    }
}
