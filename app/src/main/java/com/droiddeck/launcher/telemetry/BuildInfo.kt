package com.droiddeck.launcher.telemetry

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.BuildConfig
import java.io.File

/** build.txt in each session folder: which APK and device a log bundle or telemetry upload came from. */
object BuildInfo {
    const val FILE = "build.txt"

    private val LABEL_REF = Regex("""(\S+) at ([0-9a-f]{7,40})\s*$""")

    /** The branch the APK was built from; CI push builds leave BUILD_BRANCH empty, so it falls back to the label. */
    fun branch(label: String = BuildConfig.BUILD_LABEL, branch: String = BuildConfig.BUILD_BRANCH): String =
        branch.trim().ifEmpty { LABEL_REF.find(label)?.groupValues?.get(1) ?: "unknown" }

    fun shortCommit(commit: String = BuildConfig.SOURCE_COMMIT, label: String = BuildConfig.BUILD_LABEL): String =
        commit.trim().take(7).ifEmpty { LABEL_REF.find(label)?.groupValues?.get(2)?.take(7) ?: "unknown" }

    /** Path-safe build name for uploads, e.g. "perf-all-1a2b3c4". */
    fun slug(label: String = BuildConfig.BUILD_LABEL, branch: String = BuildConfig.BUILD_BRANCH,
             commit: String = BuildConfig.SOURCE_COMMIT): String =
        safe("${branch(label, branch)}-${shortCommit(commit, label)}")

    fun deviceSlug(): String = safe("${Build.MANUFACTURER} ${Build.MODEL}")

    internal fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(80).ifEmpty { "unknown" }

    fun text(context: Context): String = buildString {
        fun k(key: String, value: Any?) = appendLine("$key: ${value ?: "?"}")
        k("build_label", BuildConfig.BUILD_LABEL)
        k("branch", branch())
        k("commit", BuildConfig.SOURCE_COMMIT.ifEmpty { shortCommit() })
        k("build_slug", slug())
        k("pr", BuildConfig.PR_NUMBER)
        k("ci_build", BuildConfig.CI_BUILD)
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        k("version", info?.let { "${it.versionName} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else 0})" })
        k("package", context.packageName)
        k("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})")
        if (Build.VERSION.SDK_INT >= 31) k("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        k("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        k("gpu", runCatching { com.droiddeck.launcher.gpu.GpuInfo.detect().let { "${it.name} (${it.family.label})" } }.getOrNull())
        k("gpu_driver_mode", runCatching { com.droiddeck.launcher.session.SessionPrefs.gpuDriverMode(context) }.getOrNull())
        // Each launch's game.experiments event in events.jsonl gives the switches that run had.
        k("experiments_at_session_start", runCatching {
            com.droiddeck.launcher.session.Experiments.run { line(session ?: enabled(context)) }
        }.getOrNull())
        com.droiddeck.launcher.gpu.VulkanInfo.cachedOrNull()?.let { v ->
            k("vulkan_device", v["device"])
            k("vulkan_api", v["api"])
            k("vulkan_driver_version", v["driver_version"])
        }
    }

    fun write(context: Context, dir: File) {
        runCatching { File(dir, FILE).writeText(text(context)) }
            .onFailure { android.util.Log.w("BuildInfo", "could not write $FILE", it) }
    }
}
