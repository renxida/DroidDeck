package com.droiddeck.launcher.core

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

internal class AddonPack(
    val name: String, private val sha256: String, val size: Long, private val required: List<String>,
    private val reuseDeviceCopy: Boolean = sha256.isEmpty() && !BuildConfig.CI_BUILD,
    private val bundle: (Context) -> java.io.InputStream = { it.assets.open("packs/$name.tar.zst") },
) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Failed : State
        data object Ready : State
    }

    private val mutableState = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val lock = Any()

    val available: Boolean get() = sha256.isNotEmpty()
    private val tag get() = "pack-$name-${sha256.take(16)}"
    private val urls get() = urls(tag, name, BuildConfig.PACK_FORK_REPO, BuildConfig.PR_NUMBER)

    private fun root(context: Context) = File(context.filesDir, "addons/$name")
    fun dir(context: Context) = File(root(context), version(context))
    fun file(context: Context, relative: String) = File(dir(context), relative)

    fun installed(context: Context): Boolean = version(context).isNotEmpty() && File(dir(context), COMPLETE).isFile

    private fun version(context: Context): String = if (!reuseDeviceCopy) sha256 else deviceCopy(context).orEmpty()

    private fun deviceCopy(context: Context): String? = root(context).listFiles().orEmpty()
        .filter { dir ->
            dir.isDirectory && runCatching { File(dir, COMPLETE).readText().trim() }.getOrNull() == dir.name &&
                required.all { File(dir, it).isFile }
        }
        .maxByOrNull { it.lastModified() }?.name

    fun refresh(context: Context): State {
        val now = mutableState.value
        if (now is State.Downloading) return now
        return (if (installed(context)) State.Ready else if (now == State.Failed) State.Failed else State.Missing)
            .also { mutableState.value = it }
    }

    fun ensure(context: Context, onReady: (Context) -> Unit = {}) {
        val app = context.applicationContext
        synchronized(lock) {
            if (installed(app)) { mutableState.value = State.Ready; return }
            if (mutableState.value is State.Downloading) return
            mutableState.value = State.Downloading(0f)
        }
        scope.launch {
            val ok = runCatching { install(app) }.onFailure { Log.w(TAG, "$name: install failed", it) }.getOrDefault(false)
            com.droiddeck.launcher.telemetry.Timeline.mark("setup.addon.done", mapOf("pack" to name, "ok" to ok))
            mutableState.value = if (ok) State.Ready else State.Failed
            if (ok) onReady(app)
        }
    }

    @JvmOverloads
    fun await(context: Context, running: () -> Boolean = { true }, onState: (State) -> Unit = {}): Boolean {
        if (installed(context)) return true
        ensure(context)
        while (running()) {
            val now = state.value
            onState(now)
            when (now) {
                State.Ready -> return installed(context)
                State.Failed -> return false
                else -> try { Thread.sleep(250) } catch (e: InterruptedException) { return false }
            }
        }
        return false
    }

    private fun install(context: Context): Boolean {
        if (!available) {
            Log.w(TAG, if (reuseDeviceCopy) "$name: this build has no pack and none is installed" else "$name: this build has no pack")
            return false
        }
        com.droiddeck.launcher.telemetry.Timeline.mark("setup.addon.start", mapOf("pack" to name))
        val root = root(context).apply { mkdirs() }
        val archive = File(root, "$sha256.tar.zst")
        val downloaded = bundled(context, archive) || download(archive)
        if (!downloaded) return false
        val staging = File(root, "$sha256.partial")
        staging.deleteRecursively()
        try {
            if (!FileInputStream(archive).use { TarZst.extract(it, staging) }) return false
            check(required.all { File(staging, it).isFile }) { "$name pack is incomplete" }
            File(staging, COMPLETE).writeText(sha256)
            val target = dir(context)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "could not move the $name pack into place" }
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
        root.listFiles().orEmpty().filter { it.name != sha256 }.forEach { it.deleteRecursively() }
        return true
    }

    /**
     * Each source in turn, then again after a pause, [RETRY_DELAYS_MS] apart, so a dropped connection does not end the
     * session. A partial file is kept between tries of the same source, which resume it; it goes when the source changes.
     */
    private fun download(archive: File): Boolean {
        for ((round, delay) in (listOf(0L) + RETRY_DELAYS_MS).withIndex()) {
            if (delay > 0) try { Thread.sleep(delay) } catch (e: InterruptedException) { return false }
            for (url in urls) {
                if (lastSource != null && lastSource != url) archive.delete()
                lastSource = url
                if (Downloader.downloadFile(url, archive, true, sha256) { p -> mutableState.value = State.Downloading(p.coerceIn(0f, 1f)) }) return true
                Log.w(TAG, "$name: download round ${round + 1} failed from $url")
            }
        }
        archive.delete()
        return false
    }

    private var lastSource: String? = null

    internal fun bundled(context: Context, archive: File): Boolean {
        val copied = runCatching {
            bundle(context).use { input -> archive.outputStream().use { input.copyTo(it) } }
        }.isSuccess
        if (copied && Hashes.sha256(archive).equals(sha256, ignoreCase = true)) return true
        if (copied) Log.w(TAG, "$name: the bundled pack does not match the pinned digest")
        archive.delete()
        return false
    }

    companion object {
        private const val TAG = "AddonPack"
        private const val REPO = "Droid-Deck/DroidDeck-Components"
        private const val PR_REPO = "Droid-Deck/DroidDeck-CI"
        internal val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 15_000L)

        /**
         * Upstream's components release first; then, for a fork's build, the fork's own release of the same pack, which
         * the fork's CI publishes, so a pack the fork changed is found too; then a PR's pack.
         */
        internal fun urls(tag: String, name: String, forkRepo: String, pr: Int): List<String> = listOfNotNull(
            "https://github.com/$REPO/releases/download/$tag/$name.tar.zst",
            forkRepo.takeIf { it.isNotEmpty() && it != REPO }?.let { "https://github.com/$it/releases/download/$tag/$name.tar.zst" },
            pr.takeIf { it > 0 }?.let { "https://github.com/$PR_REPO/releases/download/pr-$it/$tag.tar.zst" },
        )
        const val COMPLETE = ".complete"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @JvmField
        val PROOT = AddonPack("proot", BuildConfig.PACK_PROOT_SHA256, BuildConfig.PACK_PROOT_SIZE,
            listOf("libproot.so", "libproot-loader.so"))
        @JvmField
        val DIRECTAUDIO = AddonPack("directaudio", BuildConfig.PACK_DIRECTAUDIO_SHA256, BuildConfig.PACK_DIRECTAUDIO_SIZE,
            listOf("linux-wine11/version.txt", "linux-wine11-systhread/version.txt"))
        @JvmField
        val POWERVR = AddonPack("powervr", BuildConfig.PACK_POWERVR_SHA256, BuildConfig.PACK_POWERVR_SIZE,
            listOf("runtime.tzst", "version.txt"))
    }
}
