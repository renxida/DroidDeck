package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddonPackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.filesDir, "addons/sample")

    @Before fun clean() {
        root.deleteRecursively()
    }

    private fun installed(version: String, complete: Boolean = true, modified: Long = 1_000L): File =
        File(root, version).apply {
            mkdirs()
            File(this, "tool").writeText("x")
            if (complete) File(this, AddonPack.COMPLETE).writeText(version)
            setLastModified(modified)
        }

    @Test fun unpinnedLocalBuildReusesTheNewestCompletePack() {
        installed("a".repeat(64), modified = 1_000L)
        installed("b".repeat(64), modified = 2_000L)
        installed("c".repeat(64), complete = false, modified = 3_000L)
        val pack = AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = true)

        assertTrue(pack.installed(context))
        assertEquals("b".repeat(64), pack.dir(context).name)
    }

    @Test fun unpinnedLocalBuildWithNothingInstalledHasNoPack() {
        assertFalse(AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = true).installed(context))
    }

    @Test fun pinnedBuildIgnoresOtherInstalledVersions() {
        installed("b".repeat(64))
        val pack = AddonPack("sample", "a".repeat(64), 1L, listOf("tool"))

        assertFalse(pack.installed(context))
        assertEquals("a".repeat(64), pack.dir(context).name)
    }

    @Test fun ciBuildWithoutAPinNeverReuses() {
        installed("b".repeat(64))
        assertFalse(AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = false).installed(context))
    }

    @Test fun localBuildInstallsTheBundledPackWithoutDownloading() {
        val archive = tarZst("tool" to "x")
        val pack = AddonPack("sample", Hashes.sha256(archive), archive.length(), listOf("tool"), bundle = { archive.inputStream() })

        assertTrue(pack.await(context))
        assertEquals("x", pack.file(context, "tool").readText())
    }

    @Test fun bundledPackWithTheWrongDigestIsRejected() {
        val archive = tarZst("tool" to "x")
        val pack = AddonPack("sample", "a".repeat(64), archive.length(), listOf("tool"),
            bundle = { archive.inputStream() })

        val copy = File(context.cacheDir, "copy.tar.zst")

        assertFalse(pack.bundled(context, copy))
        assertFalse(copy.exists())
    }

    private fun tarZst(vararg files: Pair<String, String>): File =
        File(context.cacheDir, "sample-${System.nanoTime()}.tar.zst").apply {
            TarArchiveOutputStream(ZstdOutputStream(outputStream())).use { tar ->
                for ((path, text) in files) {
                    val bytes = text.toByteArray()
                    tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }

    @Test fun aForkBuildAlsoLooksInTheForksOwnReleases() {
        assertEquals(
            listOf(
                "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/pack-powervr-aa0fcacd40acdece/powervr.tar.zst",
                "https://github.com/renxida/DroidDeck/releases/download/pack-powervr-aa0fcacd40acdece/powervr.tar.zst",
            ),
            AddonPack.urls("pack-powervr-aa0fcacd40acdece", "powervr", "renxida/DroidDeck", 0),
        )
    }

    @Test fun upstreamAndLocalBuildsLookOnlyInTheComponentsRelease() {
        assertEquals(1, AddonPack.urls("pack-proot-0123456789abcdef", "proot", "", 0).size)
        assertEquals(1, AddonPack.urls("pack-proot-0123456789abcdef", "proot", "Droid-Deck/DroidDeck-Components", 0).size)
        assertEquals(3, AddonPack.urls("pack-proot-0123456789abcdef", "proot", "renxida/DroidDeck", 7).size)
    }

    @Test fun aFailedDownloadIsRetriedAfterPauses() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L), AddonPack.RETRY_DELAYS_MS)
    }
}
