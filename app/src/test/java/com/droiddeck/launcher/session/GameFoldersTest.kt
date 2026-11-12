package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameFoldersTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun anOldGamesFolderBecomesABindOnceAndStays() {
        val pc = tmp.newFolder("PC")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        val roots = GameFolders.roots(app)
        assertEquals(listOf(pc.path to GameFolders.GUEST_DIR + "/PC"), roots.map { it.host.path to it.guest })
        assertEquals(emptyList<String>(), SessionPrefs.addedGamesDirs(app))
        val binds = SessionPrefs.gameFolderBinds(app)
        GameFolders.roots(app)
        GameFolders.roots(app)
        assertEquals(binds, SessionPrefs.gameFolderBinds(app))
    }

    @Test fun twoFoldersOfOneNameKeepTheirOwnGuestPaths() {
        val a = File(tmp.newFolder("a"), "Games").apply { mkdirs() }
        val b = File(tmp.newFolder("b"), "Games").apply { mkdirs() }
        val guests = GameFolders.legacyRoots(listOf(a.path, b.path)).map { it.guest }
        assertTrue(guests.all { it.startsWith(GameFolders.GUEST_DIR + "/Games-") })
        assertTrue(guests[0] != guests[1])
        SessionPrefs.setAddedGamesDirs(app, listOf(a.path, b.path))
        assertEquals(guests, GameFolders.roots(app).map { it.guest })
    }
}
