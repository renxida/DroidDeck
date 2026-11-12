package com.droiddeck.launcher.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PackageCatalogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anInterruptedInstallIsFoundAgainWithEverythingNeededToFinishIt() {
        val root = temporary.root
        val entry = PackageCatalog.Entry(
            PackageCatalog.PROTON_SEED_ID, "Proton", 1, "proton-r2", "tar",
            "https://example.invalid/proton.tar.zst", "a".repeat(64), 846933224L, "", "", "",
        )
        assertTrue(PackageCatalog.interrupted(root).isEmpty())
        File(root, ".droiddeck-pkg-${entry.id}.installing").writeText(PackageCatalog.entryJson(entry).toString())
        File(root, ".droiddeck-pkg-emulators.installing").writeText("not json")
        File(root, ".droiddeck-pkg-emulators").writeText("1")

        val found = PackageCatalog.interrupted(root).single()

        assertEquals(entry.id, found.id)
        assertEquals(entry.version, found.version)
        assertEquals(entry.url, found.url)
        assertEquals(entry.sha256, found.sha256)
        assertEquals(entry.size, found.size)
        assertEquals(entry.kind, found.kind)
    }
}
