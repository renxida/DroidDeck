package com.droiddeck.launcher.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.util.Log
import com.caverock.androidsvg.SVG
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.io.RandomAccessFile

object UserApps {
    private const val TAG = "UserApps"
    const val GUEST_DIR = "/opt/droiddeck-apps"
    const val SCRIPT_LAUNCHER = "/usr/local/bin/droiddeck-script-run"
    const val DESKTOP_IMPORT = "desktop-import"
    private const val ICON_SIZE = 256
    private const val ELF_SCAN_LIMIT = 4000

    class App(
        val key: String, val name: String, val icon: File?, val detail: String?,
        val program: String, val args: List<String>, val copied: Boolean = false,
        val fex: String = LinuxFex.AUTO,
    )

    class Request(val script: File, val name: String?, val icon: String?, val meta: Map<String, String> = emptyMap())

    class ScriptPlan(val folder: File, val copy: Boolean, val bytes: Long)

    private fun root(context: Context) = File(LinuxRuntime.rootDir(context), GUEST_DIR.substring(1))

    private fun menuEntry(context: Context, id: String) =
        File(LinuxRuntime.rootDir(context), "usr/local/share/applications/droiddeck-app-$id.desktop")

    fun launches(program: String?): Boolean = program == SCRIPT_LAUNCHER

    fun list(context: Context): List<App> =
        root(context).listFiles()?.filter { File(it, "entry").isFile }?.map { dir ->
            App(
                "script:${dir.name}", readName(dir) ?: dir.name, File(dir, "icon.png").takeIf { it.isFile },
                FileUtils.readString(File(dir, "source"))?.trim(), SCRIPT_LAUNCHER, listOf("$GUEST_DIR/${dir.name}"),
                copied = !java.nio.file.Files.isSymbolicLink(File(dir, "files").toPath()),
                fex = LinuxFex.mode(dir),
            )
        }.orEmpty().sortedBy { it.name.lowercase() }

    private fun readName(dir: File) = FileUtils.readString(File(dir, "name"))?.trim()?.takeIf { it.isNotEmpty() }

    fun add(context: Context, request: Request, onProgress: (String, Int) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
        return addScript(context, request.script, name, request.icon?.let(::File), request.meta, onProgress)
    }

    fun edit(context: Context, app: App, name: String, icon: String?, fex: String? = null): String? {
        val id = app.key.substringAfter(':')
        if (id.isEmpty() || '/' in id || id.startsWith(".")) return null
        val dir = File(root(context), id).apply { mkdirs() }
        FileUtils.writeString(File(dir, "name"), name.trim())
        val target = File(dir, "icon.png")
        when {
            icon == null -> {}
            icon.isEmpty() -> target.delete()
            else -> if (!saveIcon(File(icon), target)) return context.getString(R.string.user_apps_icon_failed)
        }
        if (fex != null && !LinuxFex.setMode(dir, fex)) return context.getString(R.string.user_apps_failed)
        writeScriptEntry(context, dir)
        return null
    }

    fun remove(context: Context, app: App): String? {
        val id = app.key.substringAfter(':')
        if (id.isEmpty() || '/' in id || id.startsWith(".")) return null
        val dir = File(root(context), id)
        File(dir, "files").delete()
        FileUtils.delete(dir)
        menuEntry(context, id).delete()
        return null
    }

    fun binds(context: Context): List<String> {
        val internal = Environment.getExternalStorageDirectory().canonicalPath
        return root(context).listFiles().orEmpty().mapNotNull { dir ->
            val target = runCatching { Os.readlink(File(dir, "files").path) }.getOrNull() ?: return@mapNotNull null
            target.takeIf { !it.startsWith("$internal/") && File(it).isDirectory }
        }.distinct()
    }

    fun desktopImport(context: Context, key: String): String? =
        root(context).listFiles()?.firstOrNull { dir ->
            File(dir, "entry").isFile && FileUtils.readString(File(dir, DESKTOP_IMPORT))?.lineSequence()?.firstOrNull() == key
        }?.let { "$GUEST_DIR/${it.name}" }

    fun planScript(script: File): ScriptPlan {
        val folder = script.canonicalFile.parentFile ?: script.parentFile!!
        var scanned = 0
        val copy = folder.walkTopDown().maxDepth(4).filter { it.isFile }.takeWhile { scanned++ < ELF_SCAN_LIMIT }.any(::isElf)
        val bytes = if (copy) folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        return ScriptPlan(folder, copy, bytes)
    }

    private fun isElf(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() }
    } catch (e: Exception) { false }

    private fun addScript(
        context: Context, script: File, name: String?, icon: File?, meta: Map<String, String>, onProgress: (String, Int) -> Unit,
    ): String? {
        if (!script.isFile) return context.getString(R.string.user_apps_file_gone)
        val plan = planScript(script)
        val base = root(context).apply { mkdirs() }
        if (plan.copy && StatFs(base.path).availableBytes < plan.bytes + (64L shl 20)) {
            return context.getString(R.string.user_apps_no_space, FileUtils.sizeToString(plan.bytes))
        }
        val title = name ?: script.nameWithoutExtension
        val id = idFor(title, base.list()?.toSet() ?: emptySet())
        val dir = File(base, id)
        try {
            dir.mkdirs()
            val files = File(dir, "files")
            if (plan.copy) {
                onProgress(context.getString(R.string.user_apps_copying, plan.folder.name), -1)
                copyTree(plan.folder, files)
            } else {
                Os.symlink(plan.folder.path, files.path)
            }
            FileUtils.writeString(File(dir, "entry"), script.name)
            FileUtils.writeString(File(dir, "name"), title)
            FileUtils.writeString(File(dir, "source"), script.canonicalPath)
            if (icon != null && !saveIcon(icon, File(dir, "icon.png"))) Log.w(TAG, "icon ${icon.path} could not be read")
            meta.forEach { (k, v) -> FileUtils.writeString(File(dir, k), v) }
            writeScriptEntry(context, dir)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "script ${script.path}", e)
            File(dir, "files").takeIf { !plan.copy }?.delete()
            FileUtils.delete(dir)
            return e.message ?: context.getString(R.string.user_apps_failed)
        }
    }

    internal fun idFor(title: String, taken: Set<String>): String {
        val base = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifEmpty { "app" }
        var id = base
        var n = 2
        while (id in taken) id = "$base-${n++}"
        return id
    }

    private fun writeScriptEntry(context: Context, dir: File) {
        val id = dir.name
        val icon = if (File(dir, "icon.png").isFile) "$GUEST_DIR/$id/icon.png" else "utilities-terminal"
        FileUtils.writeString(menuEntry(context, id),
            "[Desktop Entry]\nType=Application\nName=${readName(dir) ?: id}\nExec=$SCRIPT_LAUNCHER $GUEST_DIR/$id\nIcon=$icon\n" +
            "Terminal=false\nCategories=Game;\nX-DroidDeck-App=$id\n")
    }

    private fun copyTree(from: File, to: File) {
        from.walkTopDown().forEach { src ->
            val dst = File(to, src.relativeTo(from).path)
            if (src.isDirectory) dst.mkdirs()
            else {
                src.inputStream().use { input -> dst.outputStream().use { FileUtils.copy(input, it) } }
                if (isElf(dst) || dst.extension == "sh") dst.setExecutable(true, false)
            }
        }
    }

    internal fun saveIcon(source: File, target: File): Boolean {
        val bitmap = (if (isSvg(source)) renderSvg(source) else decodeScaled(source)) ?: return false
        return try {
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.w(TAG, "icon ${source.path}", e); false
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeScaled(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = ICON_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun isSvg(f: File): Boolean = try {
        f.inputStream().use { val head = ByteArray(1024); val n = it.read(head); n > 0 && String(head, 0, n).contains("<svg") }
    } catch (e: Exception) { false }

    private fun renderSvg(source: File): Bitmap? = try {
        val svg = source.inputStream().use { SVG.getFromInputStream(it) }
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    } catch (e: Exception) {
        Log.w(TAG, "svg ${source.path}", e); null
    }
}
