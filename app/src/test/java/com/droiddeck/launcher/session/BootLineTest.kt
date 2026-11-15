package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BootLineTest {
    /** The shipped English templates, read from strings.xml so the samples below are what the screen shows. */
    private val words: BootLine.Words by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        fun s(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(xml)!!.groupValues[1]
            .replace("\\'", "'").replace("&amp;", "&")
        BootLine.Words(
            app = s("boot_app"), nativeIntended = s("boot_native_intended"), native = s("boot_native"),
            protonKestrel = s("boot_proton_kestrel"), protonStock = s("boot_proton_stock"), proton = s("boot_proton"),
            skipped = s("boot_native_skipped"), beta = s("boot_branch_beta"), stable = s("boot_branch_stable"),
        )
    }

    private fun engine(native: Boolean = false, kestrel: Boolean = false, actual: BootLine.Engine? = null, branch: String? = null) =
        BootLine.engine(words, native, kestrel, actual, branch)

    @Test fun parsesTheNativeLine() {
        assertEquals(BootLine.Engine(BootLine.Path.NATIVE, "native-engine-54"),
            BootLine.parse("droiddeck-game-env: engine: native native-engine-54"))
    }

    @Test fun parsesTheProtonLineWithWhyNativeWasSkipped() {
        assertEquals(BootLine.Engine(BootLine.Path.PROTON, "FEX-Kestrel-2510-p9", "Steam library gap"),
            BootLine.parse("droiddeck-game-env: engine: proton FEX-Kestrel-2510-p9; native skipped: Steam library gap\r"))
        assertEquals(BootLine.Engine(BootLine.Path.PROTON, "unknown"), BootLine.parse("droiddeck-game-env: engine: proton"))
    }

    @Test fun otherLinesAreNotEngineLines() {
        assertNull(BootLine.parse("droiddeck-game-env: experiments: fex-fast"))
        assertNull(BootLine.parse("droiddeck-game-env: engine: box64 0.3"))
        assertNull(BootLine.parse("droiddeck-game-env: native-engine: ran 40 s natively, exit 0"))
    }

    @Test fun beforeTheGuestSaysTheLineIsTheSwitchesIntent() {
        assertEquals("Native engine, Proton if it does not start", engine(native = true, kestrel = true))
        assertEquals("Proton + Kestrel FEX", engine(kestrel = true))
        assertEquals("Proton + stock FEX", engine())
    }

    @Test fun theGuestsLineNamesTheBuildThatBooted() {
        assertEquals("Native engine (native-engine-54)",
            engine(native = true, actual = BootLine.Engine(BootLine.Path.NATIVE, "native-engine-54")))
        assertEquals("Proton + FEX (FEX-Kestrel-2510-p9)",
            engine(kestrel = true, actual = BootLine.Engine(BootLine.Path.PROTON, "FEX-Kestrel-2510-p9")))
        assertEquals("Native engine skipped (Steam library gap) → Proton + FEX (FEX-Kestrel-2510-p9)",
            engine(native = true, actual = BootLine.Engine(BootLine.Path.PROTON, "FEX-Kestrel-2510-p9", "Steam library gap")))
    }

    @Test fun withoutAFexVersionNameTheSwitchesNameTheFex() {
        assertEquals("Native engine skipped (no first frame) → Proton + stock FEX",
            engine(native = true, actual = BootLine.Engine(BootLine.Path.PROTON, "unknown", "no first frame")))
    }

    @Test fun theSteamBranchFollowsWhenTheLaunchChoseOne() {
        assertEquals("Native engine (native-engine-54) · beta",
            engine(actual = BootLine.Engine(BootLine.Path.NATIVE, "native-engine-54"), branch = BootLine.SteamBranchNames.BETA))
        assertEquals("Proton + Kestrel FEX · stable", engine(kestrel = true, branch = BootLine.SteamBranchNames.STABLE))
        assertEquals("Proton + Kestrel FEX", engine(kestrel = true, branch = "something-else"))
    }

    @Test fun theAppLineHasCommitTimeAndBranch() {
        // 2026-11-14T15:57:55Z
        assertEquals("Lanner e890b89 · Nov 14 15:57 UTC · perf/switchboard",
            BootLine.app(words, "e890b8912345678", 1_794_671_875_000L, "perf/switchboard"))
        assertEquals("Lanner e890b89", BootLine.app(words, "e890b89", 0L, "unknown"))
        assertEquals("Lanner unknown · Nov 14 15:57 UTC", BootLine.app(words, "", 1_794_671_875_000L, " "))
    }

    @Test fun telemetryFieldsNameThePathBuildAndSkip() {
        assertEquals(mapOf("path" to "proton", "build" to "FEX-x", "native_skipped" to "last check failed"),
            BootLine.Engine(BootLine.Path.PROTON, "FEX-x", "last check failed").fields())
    }
}
