package com.droiddeck.launcher.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashEvidenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun resourceLoadsListsEachPathOnceInFirstLoadOrderWithCountsPerExtension() {
        val log = tmp.newFile("godot.log").apply {
            writeText(
                "Godot Engine v4.5.1\n" +
                    "Loading resource: res://fonts/kreon_regular.fontdata\n" +
                    "Loading resource: res://images/card.ctex\n" +
                    "some other line\n" +
                    "Loading resource: res://fonts/kreon_regular.fontdata\n" +
                    "Loading resource: res://scenes/main.tscn  \n"
            )
        }
        assertEquals(
            "# resources Godot loaded: 3 distinct, 4 loads\n" +
                "# .fontdata 1\n# .ctex 1\n# .tscn 1\n" +
                "2\tres://fonts/kreon_regular.fontdata\n1\tres://images/card.ctex\n1\tres://scenes/main.tscn\n",
            CrashEvidence.resourceLoads(log),
        )
    }

    @Test fun aLogWithoutVerboseLoadsGivesNothing() {
        val log = tmp.newFile("godot.log").apply { writeText("Godot Engine v4.5.1\nResource stats VRAM=601.2MB\n") }
        assertNull(CrashEvidence.resourceLoads(log))
        assertNull(CrashEvidence.resourceLoads(tmp.root.resolve("missing.log")))
    }

    @Test fun aLogGodotCopiedAsideAtStartIsNotTheLiveLog() {
        assertTrue(CrashEvidence.rotatedGodotLog("godot2026-11-14T15.33.10.log"))
        assertFalse(CrashEvidence.rotatedGodotLog("godot.log"))
        assertFalse(CrashEvidence.rotatedGodotLog("Player.log"))
    }
}
