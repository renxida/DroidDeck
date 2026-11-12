package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamInstallDirsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun steamInstallDirsComeFromTheLibrarysManifests() {
        val library = tmp.newFolder("card")
        val common = File(library, "steamapps/common").apply { mkdirs() }
        File(library, "steamapps/appmanifest_2852190.acf").writeText(
            "\"AppState\"\n{\n\t\"appid\"\t\t\"2852190\"\n\t\"installdir\"\t\t\"MONSTER_HUNTER_STORIES_3_TWISTED_REFLECTION\"\n}\n",
        )
        File(library, "steamapps/appmanifest_17410.acf").writeText(
            "\"AppState\"\n{\n\t\"appid\"\t\t\"17410\"\n\t\"installdir\"\t\t\"Mirrors Edge\"\n}\n",
        )
        assertEquals(setOf("monster_hunter_stories_3_twisted_reflection", "mirrors edge"), SteamLibraries.steamInstallDirs(common))
        assertEquals(emptySet<String>(), SteamLibraries.steamInstallDirs(library))
    }
}
