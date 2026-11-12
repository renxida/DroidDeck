package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PickListingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun storage(): File {
        val root = tmp.newFolder("emulated0")
        for (d in listOf("Download", "Android", "DCIM", "Games", "Documents", "Winlator", ".thumbnails", "alarms")) File(root, d).mkdirs()
        for (f in listOf("Setup.exe", "game.EXE", "cover.jpg", "notes.txt", "logo.PNG", "icon.ico", "hero.webp", ".hidden.png")) File(root, f).writeText("x")
        return root
    }

    @Test fun everyFolderIsListedAToZAndDotFoldersAreNot() {
        val listing = pickListing(storage())
        assertEquals(listOf("alarms", "Android", "DCIM", "Documents", "Download", "Games", "Winlator"), listing.folders.map { it.dir.name })
    }

    @Test fun hiddenDirectoriesAndImagesCanBeShownOnRequest() {
        val root = storage()
        val hidden = File(root, ".Art").apply { mkdirs() }
        File(hidden, "cover.png").writeText("art")
        assertFalse(pickListing(root).folders.any { it.dir.name == ".Art" })
        val listing = pickListing(root, showHidden = true)
        assertTrue(listing.folders.any { it.dir.name == ".Art" })
        assertTrue(listing.files.any { it.name == ".hidden.png" })
        assertEquals(listOf("cover.png"), pickListing(hidden).files.map { it.name })
    }

    @Test fun onlyImagesAreListed() {
        assertEquals(listOf("cover.jpg", "hero.webp", "icon.ico", "logo.PNG"), pickListing(storage()).files.map { it.name })
    }

    @Test fun aFolderTheAppCannotReadIsListedButMarked() {
        val root = storage()
        val locked = File(root, "Android")
        locked.setReadable(false, false)
        try {
            assumeFalse("running as root reads everything", locked.list() != null)
            val folders = pickListing(root).folders.associate { it.dir.name to it.readable }
            assertFalse(folders.getValue("Android"))
            assertTrue(folders.getValue("Download"))
        } finally {
            locked.setReadable(true, false)
        }
    }
}
