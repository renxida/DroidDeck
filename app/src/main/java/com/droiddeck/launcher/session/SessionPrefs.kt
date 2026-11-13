package com.droiddeck.launcher.session

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.gpu.ScreenEffects
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import com.droiddeck.launcher.gpu.TurnipDriver
import org.json.JSONObject
import java.io.File

object SessionPrefs {
    data class GameTextureFiltering(val anisotropy: Int? = null, val lodBias: String? = null)

    const val SUSPEND_AUTO = "auto"
    const val SUSPEND_NATIVE = "native"
    const val SUSPEND_MANUAL = "manual"
    const val SUSPEND_NEVER = "never"
    private const val LEGACY_SUSPEND_DOWNLOADS = "downloads"
    private const val STEAM_DOWNLOADS_IN_BACKGROUND = "steamDownloadsInBackground"
    private const val LOW_STORAGE_WARNING = "lowStorageWarning"
    private const val SLOW_USB_WARNING_OFF = "slowUsbWarningOff"
    private const val SLOW_USB_DISMISSED = "slowUsbDismissed"

    const val CONTROLLER_DECK = "deck"
    const val CONTROLLER_XBOX360 = "xbox360"
    const val CONTROLLER_STEAM = "steamcontroller"
    const val CONTROLLER_DS4 = "dualshock4"
    const val CONTROLLER_DUALSENSE = "dualsense"
    const val CONTROLLER_NONE = "none"
    const val OSC_AUTO = "auto"
    const val OSC_ALWAYS = "always"
    const val OSC_STEAM_QAM = "steam-qam"
    const val OSC_NEVER = "never"
    const val OSC_STEAM_TOUCH = "steam-touch"

    val BACK_MENU_THEN_QAM = R.string.back_menu_then_qam
    val BACK_QAM_THEN_MENU = R.string.back_qam_then_menu

    @StringRes
    fun backActionsOrder(inverted: Boolean): Int =
        if (inverted) BACK_QAM_THEN_MENU else BACK_MENU_THEN_QAM

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val orientationOptions = listOf(
        ActivityInfo.SCREEN_ORIENTATION_USER to R.string.setup_launcher_orientation_auto,
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to R.string.setup_orientation_portrait,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to R.string.setup_orientation_auto,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to R.string.setup_orientation_landscape,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to R.string.setup_orientation_reverse,
    )

    fun orientation(context: Context): Int =
        prefs(context).getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            .takeIf { value -> orientationOptions.any { it.first == value } }
            ?: ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    fun setOrientation(context: Context, orientation: Int) {
        require(orientationOptions.any { it.first == orientation })
        prefs(context).edit().putInt("orientation", orientation).apply()
    }

    fun windowOrientation(context: Context, followScreen: Boolean, smallestWidthDp: Int): Int =
        if (followScreen && smallestWidthDp >= SessionDisplay.UNFOLDED_MIN_DP && !prefs(context).contains("orientation"))
            ActivityInfo.SCREEN_ORIENTATION_USER else orientation(context)

    fun portrait(context: Context): Boolean = when (orientation(context)) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> true
        ActivityInfo.SCREEN_ORIENTATION_USER ->
            context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        else -> false
    }

    fun launcherFullscreen(context: Context): Boolean = prefs(context).getBoolean("launcherFullscreen", true)

    fun setLauncherFullscreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("launcherFullscreen", on).apply()
    }

    private fun savedDualScreenMode(context: Context, key: String): String? =
        prefs(context).getString(key, null)?.takeIf { it in com.droiddeck.launcher.input.DualScreen.modes }

    private fun legacyDualScreenMode(context: Context): String =
        if (!prefs(context).getBoolean("dualScreenLauncher", true)) com.droiddeck.launcher.input.DualScreen.MODE_OFF
        else savedDualScreenMode(context, "dualScreenMode") ?: com.droiddeck.launcher.input.DualScreen.MODE_AUTO

    fun dualScreenMode(context: Context): String = legacyDualScreenMode(context)

    fun setDualScreenMode(context: Context, mode: String) {
        val sessions = dualScreenSessionMode(context)
        prefs(context).edit().remove("dualScreenLauncher").putString("dualScreenMode", mode).putString("dualScreenSessionMode", sessions).apply()
    }

    fun dualScreenSessionMode(context: Context): String =
        savedDualScreenMode(context, "dualScreenSessionMode") ?: legacyDualScreenMode(context)

    fun setDualScreenSessionMode(context: Context, mode: String) {
        val launcher = dualScreenMode(context)
        prefs(context).edit().remove("dualScreenLauncher").putString("dualScreenMode", launcher).putString("dualScreenSessionMode", mode).apply()
    }

    fun secondScreenPanel(context: Context): String? = prefs(context).let {
        it.getString("secondScreenPanel.game", null) ?: it.getString("secondScreenPanel", null)?.takeIf { id -> id != "custom-controls" }
    }

    fun setSecondScreenPanel(context: Context, mode: String) {
        prefs(context).edit().putString("secondScreenPanel.game", mode).apply()
    }

    fun statsStrip(context: Context): String? = prefs(context).getString("statsStrip", null)

    fun setStatsStrip(context: Context, id: String) {
        prefs(context).edit().putString("statsStrip", id).apply()
    }

    fun bottomStripMetrics(context: Context): Set<StripMetric> = StripMetric.parse(prefs(context).getString("bottomStripMetrics", null))

    fun setBottomStripMetric(context: Context, metric: StripMetric, on: Boolean) {
        val now = bottomStripMetrics(context).let { if (on) it + metric else it - metric }
        prefs(context).edit().putString("bottomStripMetrics", StripMetric.encode(now)).apply()
    }

    fun bottomHud(context: Context): Boolean = prefs(context).getBoolean("bottomHud", true)

    fun setBottomHud(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("bottomHud", on).apply()
    }

    fun bottomStripOnTabs(context: Context): Boolean = prefs(context).getBoolean("bottomStripTabs", true)

    fun setBottomStripOnTabs(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("bottomStripTabs", on).apply()
    }

    fun statsHud(context: Context): String? = prefs(context).getString("statsHud", null)

    fun setStatsHud(context: Context, id: String) {
        prefs(context).edit().putString("statsHud", id).apply()
    }

    fun statsSpeedUnit(context: Context): String? = prefs(context).getString("statsSpeedUnit", null)

    fun setStatsSpeedUnit(context: Context, id: String) {
        prefs(context).edit().putString("statsSpeedUnit", id).apply()
    }

    fun statsDash(context: Context): String? = prefs(context).getString("statsDash", null)

    fun setStatsDash(context: Context, id: String) {
        prefs(context).edit().putString("statsDash", id).apply()
    }

    fun telemetryAuto(context: Context): Boolean = prefs(context).getBoolean("telemetryAuto", true)

    fun setTelemetryAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("telemetryAuto", on).apply()
    }

    fun telemetryOff(context: Context): Set<String> = prefs(context).getStringSet("telemetryOff", null)?.toSet().orEmpty()

    fun setTelemetryOff(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet("telemetryOff", keys.toSet()).apply()
    }

    fun dualScreenSessionControls(context: Context): Boolean = prefs(context).getBoolean("dualScreenSessionControls", true)

    fun setDualScreenSessionControls(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("dualScreenSessionControls", on).apply()
    }

    fun androidTextFieldKeyboard(context: Context): Boolean = prefs(context).getBoolean("androidTextFieldKeyboard", false)

    fun setAndroidTextFieldKeyboard(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("androidTextFieldKeyboard", on).apply()
    }

    fun librarySort(context: Context): String =
        prefs(context).getString("librarySort", null)?.takeIf { it in LIBRARY_SORTS } ?: LIBRARY_SORTS.first()

    fun setLibrarySort(context: Context, sort: String) {
        prefs(context).edit().putString("librarySort", if (sort in LIBRARY_SORTS) sort else LIBRARY_SORTS.first()).apply()
    }

    fun libraryView(context: Context): String =
        prefs(context).getString("libraryView", null)?.takeIf { it in LIBRARY_VIEWS } ?: DEFAULT_LIBRARY_VIEW

    fun setLibraryView(context: Context, view: String) {
        prefs(context).edit().putString("libraryView", if (view in LIBRARY_VIEWS) view else DEFAULT_LIBRARY_VIEW).apply()
    }

    fun libraryCardLevel(context: Context): Int = prefs(context).getInt("libraryCardLevel", 2)

    fun setLibraryCardLevel(context: Context, level: Int) {
        prefs(context).edit().putInt("libraryCardLevel", level).apply()
    }

    fun libraryShowTitle(context: Context): Boolean = prefs(context).getBoolean("libraryShowTitle", true)

    fun setLibraryShowTitle(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean("libraryShowTitle", show).apply()
    }

    fun libraryShowSize(context: Context): Boolean = prefs(context).getBoolean("libraryShowSize", true)

    fun setLibraryShowSize(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean("libraryShowSize", show).apply()
    }

    fun railLabels(context: Context): Boolean = prefs(context).getBoolean("railLabels", false)

    fun setRailLabels(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean("railLabels", show).apply()
    }

    private fun idList(raw: String?): List<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val LIBRARY_SORTS = listOf("lastPlayed", "recentlyInstalled", "titleAsc", "titleDesc", "sizeDesc", "sizeAsc")
    val LIBRARY_VIEWS = listOf("cards", "capsules", "list")
    const val DEFAULT_LIBRARY_VIEW = "capsules"

    fun hudEnabled(context: Context): Boolean = prefs(context).getBoolean(hudKey(context), false)

    fun setHudEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(hudKey(context), on).apply()
    }

    fun hudShowFps(context: Context): Boolean = prefs(context).getBoolean("hudFps", true)

    fun setHudShowFps(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("hudFps", on).apply()
    }

    fun hudShowBattery(context: Context): Boolean = prefs(context).getBoolean("hudBattery", true)

    fun setHudShowBattery(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("hudBattery", on).apply()
    }

    fun hudShowGraph(context: Context): Boolean = prefs(context).getBoolean("hudGraph", true)

    fun setHudShowGraph(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("hudGraph", on).apply()
    }

    fun hudColor(context: Context): String =
        prefs(context).getString("hudColor", HUD_COLOR_MINT)?.takeIf { it in HUD_COLORS } ?: HUD_COLOR_MINT

    fun setHudColor(context: Context, color: String) {
        if (color in HUD_COLORS) prefs(context).edit().putString("hudColor", color).apply()
    }

    fun hudOpacity(context: Context): Int =
        prefs(context).getInt("hudOpacity", HUD_OPACITY_DEFAULT).takeIf { it in HUD_OPACITY_RANGE } ?: HUD_OPACITY_DEFAULT

    fun setHudOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt("hudOpacity", percent.coerceIn(HUD_OPACITY_RANGE)).apply()
    }

    const val PC_KEYBOARD_SIZE_DEFAULT = 47
    val PC_KEYBOARD_SIZE_RANGE = 25..70
    const val PC_KEYBOARD_OPACITY_DEFAULT = 100
    val PC_KEYBOARD_OPACITY_RANGE = 30..100

    fun pcKeyboardSize(context: Context): Int =
        prefs(context).getInt("pcKeyboardSize", PC_KEYBOARD_SIZE_DEFAULT).takeIf { it in PC_KEYBOARD_SIZE_RANGE } ?: PC_KEYBOARD_SIZE_DEFAULT

    fun setPcKeyboardSize(context: Context, percent: Int) {
        prefs(context).edit().putInt("pcKeyboardSize", percent.coerceIn(PC_KEYBOARD_SIZE_RANGE)).apply()
    }

    fun pcKeyboardOpacity(context: Context): Int =
        prefs(context).getInt("pcKeyboardOpacity", PC_KEYBOARD_OPACITY_DEFAULT).takeIf { it in PC_KEYBOARD_OPACITY_RANGE } ?: PC_KEYBOARD_OPACITY_DEFAULT

    fun setPcKeyboardOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt("pcKeyboardOpacity", percent.coerceIn(PC_KEYBOARD_OPACITY_RANGE)).apply()
    }

    fun pcKeyboardHiddenKeys(context: Context): Set<String> =
        idList(prefs(context).getString("pcKeyboardHiddenKeys", null)).toSet()

    fun setPcKeyboardHiddenKeys(context: Context, hidden: Set<String>) {
        prefs(context).edit().putString("pcKeyboardHiddenKeys", hidden.joinToString(",")).apply()
    }

    fun droidDeckHudConfig(context: Context): String? = prefs(context).getString("__KEEP_droidDeckHud__", null)

    fun setDroidDeckHudConfig(context: Context, config: String) {
        prefs(context).edit().putString("__KEEP_droidDeckHud__", config).apply()
    }

    fun droidDeckHudPosition(context: Context): Pair<Float, Float>? =
        prefs(context).getString("__KEEP_droidDeckHudPos__", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun setDroidDeckHudPosition(context: Context, x: Float, y: Float) {
        prefs(context).edit().putString("__KEEP_droidDeckHudPos__", "$x,$y").apply()
    }

    const val HUD_COLOR_MINT = "mint"
    val HUD_COLORS = listOf(HUD_COLOR_MINT, "white", "sky", "amber", "violet")
    val HUD_OPACITY_RANGE = 30..100
    const val HUD_OPACITY_DEFAULT = 70

    private fun hudKey(context: Context) = if (mangoappSession(context)) "hudDeck" else "hud"

    private fun mangoappSession(context: Context) =
        SessionState.mode == SessionService.MODE_STEAM && mangoapp(context)

    fun lowLatency(context: Context): Boolean = prefs(context).getBoolean("lowLatency", true)

    fun setLowLatency(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("lowLatency", on).apply()
    }

    fun evdevMouse(context: Context): Boolean = prefs(context).getBoolean("evdevMouse", false)

    fun setEvdevMouse(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("evdevMouse", on).apply()
    }

    fun directCursor(context: Context): Boolean = prefs(context).getBoolean("directCursor", false)

    fun setDirectCursor(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("directCursor", on).apply()
    }

    fun backHold(context: Context): Boolean = prefs(context).getBoolean("backHold", true)

    fun setBackHold(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("backHold", on).apply()
    }

    fun backActionsInverted(context: Context): Boolean = prefs(context).getBoolean("backActionsInverted", false)

    fun setBackActionsInverted(context: Context, inverted: Boolean) {
        prefs(context).edit().putBoolean("backActionsInverted", inverted).apply()
    }

    val BACK_DOUBLE_TAP_MS_OPTIONS = (100..800 step 100).toList()

    fun backDoubleTapMs(context: Context): Int =
        prefs(context).getInt("backDoubleTapMs", 500).takeIf { it in BACK_DOUBLE_TAP_MS_OPTIONS } ?: 500

    fun setBackDoubleTapMs(context: Context, ms: Int) {
        require(ms in BACK_DOUBLE_TAP_MS_OPTIONS)
        prefs(context).edit().putInt("backDoubleTapMs", ms).apply()
    }

    const val TOUCH_AUTO = "auto"
    const val TOUCH_PAD = "touchpad"
    const val TOUCH_DIRECT = "direct"
    const val TOUCH_GESTURES = "gestures"
    const val TOUCH_OFF = "off"

    fun touchMode(context: Context): String = prefs(context).getString("touch", TOUCH_AUTO) ?: TOUCH_AUTO

    fun setTouchMode(context: Context, mode: String) {
        prefs(context).edit().putString("touch", mode).apply()
    }

    const val SHAPE_AUTO = "auto"
    const val SHAPE_WIDE = "16:9"
    const val SHAPE_EXACT = "exact"

    private fun shapeMode(context: Context): String =
        prefs(context).getString("shape", null)
            ?: if (context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")) SHAPE_WIDE else SHAPE_AUTO

    fun oscMode(context: Context): String = prefs(context).getString("osc", OSC_AUTO) ?: OSC_AUTO

    /**
     * Whether the on-screen gamepad starts folded (only its + button and the keyboard key) while [game] is in front.
     * A game starts folded until the person unfolds the pad during it: many games are played by touch, and the pad
     * would cover what they tap. The choice is kept per game.
     */
    fun padFolded(context: Context, game: String, default: Boolean = true): Boolean = prefs(context).getBoolean("padFolded.$game", default)

    fun setPadFolded(context: Context, game: String, folded: Boolean) {
        prefs(context).edit().putBoolean("padFolded.$game", folded).apply()
    }

    fun setOscMode(context: Context, mode: String) {
        prefs(context).edit().putString("osc", mode).apply()
    }

    fun linuxDriver(context: Context): String =
        prefs(context).getString("linuxDriver", null)
            ?: prefs(context).getString("linuxDriver.steam", "") ?: ""

    fun setLinuxDriver(context: Context, id: String) {
        prefs(context).edit().putString("linuxDriver", id).apply()
    }

    const val GPU_DRIVERS_AUTO = "auto"
    const val GPU_DRIVERS_MANUAL = "manual"
    const val GPU_DRIVERS_MIX = "mix"

    const val TP_UBWC_AUTO = "auto"
    const val TP_UBWC_ON = "on"
    const val TP_UBWC_OFF = "off"

    fun tpUbwcMode(context: Context): String =
        prefs(context).getString("tpUbwcMode", TP_UBWC_AUTO)
            ?.takeIf { it in setOf(TP_UBWC_AUTO, TP_UBWC_ON, TP_UBWC_OFF) } ?: TP_UBWC_AUTO

    fun setTpUbwcMode(context: Context, mode: String) {
        require(mode in setOf(TP_UBWC_AUTO, TP_UBWC_ON, TP_UBWC_OFF))
        prefs(context).edit().putString("tpUbwcMode", mode).commit()
    }

    fun gpuDriverMode(context: Context): String =
        prefs(context).getString("gpuDriverMode", GPU_DRIVERS_AUTO) ?: GPU_DRIVERS_AUTO

    fun setGpuDriverMode(context: Context, mode: String) {
        prefs(context).edit().putString("gpuDriverMode", mode).apply()
    }

    fun componentMode(context: Context, comp: String): String =
        prefs(context).getString("componentMode.$comp", ComponentUpdates.AUTO)
            ?.takeIf { it == ComponentUpdates.AUTO || it == ComponentUpdates.MANUAL } ?: ComponentUpdates.AUTO

    fun componentModeChosen(context: Context, comp: String): Boolean = prefs(context).contains("componentMode.$comp")

    fun setComponentMode(context: Context, comp: String, mode: String) {
        require(mode == ComponentUpdates.AUTO || mode == ComponentUpdates.MANUAL)
        prefs(context).edit().putString("componentMode.$comp", mode).commit()
    }

    fun settleGpuDriverMode(context: Context) {
        val p = prefs(context)
        if (p.contains("gpuDriverMode")) return
        val chosen = androidDriver(context).isNotEmpty() || linuxDriver(context).isNotEmpty()
        p.edit().putString("gpuDriverMode", if (chosen) GPU_DRIVERS_MANUAL else GPU_DRIVERS_AUTO).apply()
    }

    @JvmStatic
    fun ensurePowerVrAutoDrivers(context: Context) {
        if (com.droiddeck.launcher.core.DeviceSupport.openDriverActive(context)) return
        ensurePowerVrAutoDrivers(context, SystemVulkanDriver.isPowerVr())
    }

    internal fun ensurePowerVrAutoDrivers(context: Context, powerVr: Boolean) {
        if (!powerVr || gpuDriverMode(context) != GPU_DRIVERS_AUTO) return
        prefs(context).edit().putString("linuxDriver", "").putString("androidDriver", TurnipDriver.AUTO).apply()
    }

    fun gpuAutoInstalled(context: Context): Set<String> =
        prefs(context).getStringSet("gpuAutoInstalled", emptySet()).orEmpty()

    fun setGpuAutoInstalled(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet("gpuAutoInstalled", ids.toSet()).apply()
    }


    fun picturePosition(context: Context): String =
        prefs(context).getString("picturePosition", "center")
            ?.takeIf { it in listOf("top", "bottom", "left", "right", "center") } ?: "center"

    fun setPicturePosition(context: Context, position: String) {
        prefs(context).edit().putString("picturePosition",
            position.takeIf { it in listOf("top", "bottom", "left", "right", "center") } ?: "center").apply()
    }

    fun returnAfterGame(context: Context): Boolean = prefs(context).getBoolean("returnAfterGame", true)

    fun setReturnAfterGame(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("returnAfterGame", on).apply()
    }

    private fun shaderPrecacheApplied(context: Context) =
        java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.bl-shader-precache-applied")

    fun pendingShaderPrecache(context: Context): Boolean? {
        val prefs = prefs(context)
        if (!prefs.contains("shaderPrecache")) {
            if (steamConfig(context).exists()) return null
            prefs.edit().putBoolean("shaderPrecache", false).apply()
        }
        val on = prefs.getBoolean("shaderPrecache", true)
        val applied = shaderPrecacheApplied(context)
        val written = runCatching { applied.readText().trim() }.getOrNull() ?: return on
        applied.delete()
        if (written != if (on) "1" else "0") return on
        prefs.edit().remove("shaderPrecache").apply()
        return null
    }

    fun setShaderPrecache(context: Context, on: Boolean) {
        shaderPrecacheApplied(context).delete()
        prefs(context).edit().putBoolean("shaderPrecache", on).apply()
    }

    private fun steamConfig(context: Context) =
        java.io.File(steamRoot(context, steamClientArch(context)), "config/config.vdf")

    fun shaderPrecache(context: Context): Boolean = pendingShaderPrecache(context)
        ?: runCatching { com.droiddeck.launcher.storage.SteamStorage.shaderPrecache(
            steamConfig(context).readText()) }.getOrDefault(true)

    fun stretch16x9(context: Context): Boolean = prefs(context).getBoolean("stretch16x9", false)

    fun setStretch16x9(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("stretch16x9", on).apply()
    }


    fun directAudio(context: Context): Boolean = prefs(context).getBoolean("directAudio", true)

    fun setDirectAudio(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("directAudio", on).apply()
    }

    fun micEnabled(context: Context): Boolean = prefs(context).getBoolean("mic", false)

    fun micAsked(context: Context): Boolean = prefs(context).getBoolean("micAsked", false)

    fun setMicAsked(context: Context) {
        prefs(context).edit().putBoolean("micAsked", true).apply()
    }

    fun setMicEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("mic", on).apply()
    }

    fun wifiDiscoveryEnabled(context: Context): Boolean = prefs(context).getBoolean("wifiDiscovery", false)

    fun setWifiDiscoveryEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("wifiDiscovery", on).apply()
    }

    fun wifiDiscoveryAsked(context: Context): Boolean = prefs(context).getBoolean("wifiDiscoveryAsked", false)

    fun setWifiDiscoveryAsked(context: Context) {
        prefs(context).edit().putBoolean("wifiDiscoveryAsked", true).apply()
    }

    fun clientCpusOverride(context: Context): Boolean = prefs(context).getBoolean("clientCpusOverride", false)

    fun setClientCpusOverride(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("clientCpusOverride", on).apply()
    }

    fun clientCpus(context: Context): String = prefs(context).getString("clientCpus", "").orEmpty()

    fun setClientCpus(context: Context, list: String) {
        prefs(context).edit().putString("clientCpus", list).apply()
    }

    fun gameCpus(context: Context): String = prefs(context).getString("gameCpus", "").orEmpty()

    fun setGameCpus(context: Context, list: String) {
        prefs(context).edit().putString("gameCpus", list).apply()
    }

    fun noXalia(context: Context): Boolean = prefs(context).getBoolean("noXalia", true)

    fun setNoXalia(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("noXalia", on).apply()
    }

    fun fastSync(context: Context): Boolean = prefs(context).getBoolean("fastSync", true)

    fun setFastSync(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("fastSync", on).apply()
    }

    fun fsyncFirst(context: Context): Boolean = prefs(context).getBoolean("fsyncFirst", false)

    fun setFsyncFirst(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("fsyncFirst", on).apply()
    }

    const val SYNC_ESYNC = "esync"
    const val SYNC_NTSYNC = "ntsync"
    const val SYNC_FSYNC = "fsync"
    const val SYNC_WINESERVER = "wineserver"

    fun syncBackend(context: Context): String = syncBackendOf(fastSync(context), fsyncFirst(context), syncFallback(context))

    fun syncBackendOf(fastSync: Boolean, fsyncFirst: Boolean, syncFallback: Boolean): String = when {
        fastSync -> SYNC_NTSYNC
        fsyncFirst -> SYNC_FSYNC
        syncFallback -> SYNC_ESYNC
        else -> SYNC_WINESERVER
    }

    fun setSyncBackend(context: Context, backend: String) {
        require(backend == SYNC_ESYNC || backend == SYNC_NTSYNC || backend == SYNC_FSYNC || backend == SYNC_WINESERVER) { "unknown sync $backend" }
        prefs(context).edit()
            .putBoolean("fastSync", backend == SYNC_NTSYNC)
            .putBoolean("fsyncFirst", backend == SYNC_FSYNC)
            .putBoolean("syncFallback", backend != SYNC_WINESERVER)
            .apply()
    }

    fun applyNtsyncDefault(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean("ntsyncDefaultV1", false)) return
        prefs.edit()
            .putBoolean("fastSync", true)
            .putBoolean("fsyncFirst", false)
            .putBoolean("syncFallback", true)
            .putBoolean("ntsyncDefaultV1", true)
            .apply()
    }

    fun syncFallback(context: Context): Boolean = prefs(context).getBoolean("syncFallback", true)

    fun setSyncFallback(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("syncFallback", on).apply()
    }

    fun gamescopeRealtime(context: Context): Boolean = prefs(context).getBoolean("gamescopeRealtime", false)

    fun setGamescopeRealtime(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gamescopeRealtime", on).apply()
    }

    fun ramCapMb(context: Context): Int = prefs(context).getInt("ramCapMb", 0).coerceAtLeast(0)

    fun setRamCapMb(context: Context, mb: Int) {
        prefs(context).edit().putInt("ramCapMb", mb.coerceAtLeast(0)).apply()
    }

    fun zramClient(context: Context): Boolean = prefs(context).getBoolean("zramClient", true)

    fun setZramClient(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("zramClient", on).apply()
    }

    fun vramCapMb(context: Context): Int = prefs(context).getInt("vramCapMb", 0).coerceAtLeast(0)

    fun setVramCapMb(context: Context, mb: Int) {
        prefs(context).edit().putInt("vramCapMb", mb.coerceAtLeast(0)).apply()
    }

    fun gpuClockPin(context: Context): Boolean = prefs(context).getBoolean("gpuClockPin", false)

    fun setGpuClockPin(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gpuClockPin", on).apply()
    }

    fun gameThreadHints(context: Context): Boolean = prefs(context).getBoolean("gameThreadHints", true)

    fun prootNoSeccomp(context: Context): Boolean = prefs(context).getBoolean("prootNoSeccomp", false)

    fun setProotNoSeccomp(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("prootNoSeccomp", on).apply()
    }

    fun nativeUserland(context: Context): Boolean = prefs(context).getBoolean("nativeUserland", false)

    fun setNativeUserland(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("nativeUserland", on).apply()
    }

    fun prootFastPath(context: Context): Boolean = prefs(context).getBoolean("prootFastPath", true)

    fun setProotFastPath(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("prootFastPath", on).apply()
    }

    fun cursorComposite(context: Context): Boolean = prefs(context).getBoolean("cursorComposite", false)

    fun setCursorComposite(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("cursorComposite", on).apply()
    }

    @JvmStatic
    fun allowStockProot(context: Context): Boolean =
        context.getExternalFilesDir(null)?.let { File(it, "droiddeck-stock-proot").isFile } == true

    const val DEFAULT_GUEST_HOSTNAME = "DroidDeck"

    @JvmStatic
    fun guestHostname(context: Context): String =
        validGuestHostname(prefs(context).getString("guestHostname", null)) ?: DEFAULT_GUEST_HOSTNAME

    fun setGuestHostname(context: Context, name: String) {
        val valid = validGuestHostname(name)
        prefs(context).edit().apply { if (valid == null) remove("guestHostname") else putString("guestHostname", valid) }.apply()
    }

    fun validGuestHostname(name: String?): String? {
        val trimmed = name?.trim() ?: return null
        return trimmed.takeIf { Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?").matches(it) }
    }

    fun tuSysmem(context: Context): Boolean = prefs(context).getBoolean("tuSysmem", true)

    fun setTuSysmem(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("tuSysmem", on).apply()
    }

    fun glThread(context: Context): Boolean = prefs(context).getBoolean("glThread", true)
    fun setGlThread(context: Context, on: Boolean) { prefs(context).edit().putBoolean("glThread", on).apply() }

    fun noGlError(context: Context): Boolean = prefs(context).getBoolean("noGlError", true)
    fun setNoGlError(context: Context, on: Boolean) { prefs(context).edit().putBoolean("noGlError", on).apply() }

    fun steamController(context: Context): String =
        prefs(context).getString("steamController", CONTROLLER_DECK) ?: CONTROLLER_DECK
    fun setSteamController(context: Context, id: String) { prefs(context).edit().putString("steamController", id).apply() }

    fun builtinPad(context: Context): Boolean = steamController(context) != CONTROLLER_NONE
    fun padType(id: String): Int = when (id) {
        CONTROLLER_STEAM -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_STEAM
        CONTROLLER_XBOX360 -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_XBOX
        CONTROLLER_DS4 -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DS4
        CONTROLLER_DUALSENSE -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DUALSENSE
        else -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DECK
    }

    fun mangoapp(context: Context): Boolean = prefs(context).getBoolean("mangoapp", true)
    fun vsyncThread(context: Context): Boolean = prefs(context).getBoolean("vsyncThread", true)
    fun renderOnCommit(context: Context): Boolean = prefs(context).getBoolean("renderOnCommit", true)
    fun setMangoapp(context: Context, on: Boolean) { prefs(context).edit().putBoolean("mangoapp", on).apply() }

    fun zinkLazy(context: Context): Boolean = prefs(context).getBoolean("zinkLazy", true)

    @JvmStatic
    fun vkBridge(context: Context): Boolean = prefs(context).getBoolean("vkBridge", com.droiddeck.launcher.core.DeviceSupport.mali())

    fun venus(context: Context): Boolean = prefs(context).getBoolean("venus", false)

    fun hostVulkanApi(context: Context): String = prefs(context).getString("hostVulkanApi", "").orEmpty()

    fun setHostVulkanApi(context: Context, api: String) {
        if (api != hostVulkanApi(context)) prefs(context).edit().putString("hostVulkanApi", api).apply()
    }

    fun bridgeFellBack(context: Context): Boolean = prefs(context).getBoolean("bridgeFellBack", false)

    fun bridgeFellBackForMemory(context: Context): Boolean = prefs(context).getString("bridgeFallbackReason", "") == "memory"

    fun setBridgeFellBack(context: Context, on: Boolean, reason: String = "") {
        prefs(context).edit().putBoolean("bridgeFellBack", on).putString("bridgeFallbackReason", if (on) reason else "").apply()
    }

    fun steamGpuCompositing(context: Context): Boolean {
        if (!steamGpuCompositingAllowed()) {
            if (prefs(context).getBoolean("steamGpuCompositing", false))
                prefs(context).edit().putBoolean("steamGpuCompositing", false).apply()
            return false
        }
        return prefs(context).getBoolean("steamGpuCompositing", false)
    }

    fun steamGpuCompositingAllowed(): Boolean =
        !com.droiddeck.launcher.core.DeviceSupport.adreno() && !com.droiddeck.launcher.core.DeviceSupport.mali()

    fun setSteamGpuCompositing(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("steamGpuCompositing", on && steamGpuCompositingAllowed()).apply()
    }

    fun takeMemoryKillNotice(context: Context): Boolean {
        val on = prefs(context).getBoolean("memoryKillNotice", false)
        if (on) prefs(context).edit().putBoolean("memoryKillNotice", false).apply()
        return on
    }

    fun setMemoryKillNotice(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("memoryKillNotice", on).apply()
    }

    fun maliFallbackByAuto(context: Context): String = prefs(context).getString("maliFallbackByAuto", "").orEmpty()

    fun maliFallbackKnown(context: Context): Boolean = prefs(context).contains("maliFallbackByAuto")

    fun setMaliFallbackByAuto(context: Context, route: String) {
        prefs(context).edit().putString("maliFallbackByAuto", route).apply()
    }

    fun setVenus(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("venus", on).apply()
    }

    fun setVkBridge(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("vkBridge", on).putBoolean("bridgeFellBack", false).apply()
    }

    @JvmStatic
    fun bridgeProtonDxvk(context: Context): Boolean = prefs(context).getBoolean("bridgeProtonDxvk", false)

    fun setBridgeProtonDxvk(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("bridgeProtonDxvk", on).apply()
    }

    @JvmStatic
    fun vkBridgeSafe(context: Context): Boolean = prefs(context).getBoolean("vkBridgeSafe", true)

    fun setVkBridgeSafe(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("vkBridgeSafe", on).apply()
    }

    @JvmStatic
    fun vkBridgeMode(context: Context): String = if (vkBridgeSafe(context)) "safe" else "performance"

    fun setZinkLazy(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("zinkLazy", on).apply()
    }

    @JvmStatic
    fun androidDriver(context: Context): String = prefs(context).getString("androidDriver", "") ?: ""

    @JvmStatic
    fun setAndroidDriver(context: Context, id: String) {
        prefs(context).edit().putString("androidDriver", id).apply()
    }


    fun logsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("logs", true)

    fun setLogsEnabled(context: Context, on: Boolean): Boolean = synchronized(GameEnvironmentStore) {
        setLogPreference(
            context,
            key = "logs",
            oldValue = logsEnabled(context),
            newValue = on,
            newEffective = gameDebugLogsEffective(context, on, gameDebugLogsEnabled(context)),
        )
    }

    fun gameDebugLogsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("gameDebugLogsEnabled", false)

    fun setGameDebugLogsEnabled(context: Context, on: Boolean): Boolean = synchronized(GameEnvironmentStore) {
        setLogPreference(
            context,
            key = "gameDebugLogsEnabled",
            oldValue = gameDebugLogsEnabled(context),
            newValue = on,
            newEffective = gameDebugLogsEffective(context, logsEnabled(context), on),
        )
    }

    private fun setLogPreference(
        context: Context,
        key: String,
        oldValue: Boolean,
        newValue: Boolean,
        newEffective: Boolean,
    ): Boolean {
        if (oldValue == newValue) return true
        val saved = prefs(context)
        val oldEffective = gameDebugLogsEffective(context)
        fun publish(value: Boolean) = GameEnvironmentStore.publish(context, gameDebugLogsOverride = value)
        if (oldEffective == newEffective) {
            if (saved.edit().putBoolean(key, newValue).commit()) return true
            saved.edit().putBoolean(key, oldValue).commit()
            return false
        }
        if (oldEffective && !newEffective) {
            try {
                publish(false)
            } catch (e: Exception) {
                android.util.Log.e("GameEnvironment", "Could not disable game diagnostics", e)
                return false
            }
            if (saved.edit().putBoolean(key, newValue).commit()) return true
            saved.edit().putBoolean(key, oldValue).commit()
            runCatching { publish(oldEffective) }
                .onFailure { android.util.Log.e("GameEnvironment", "Could not restore game diagnostics", it) }
            return false
        }
        if (!saved.edit().putBoolean(key, newValue).commit()) {
            saved.edit().putBoolean(key, oldValue).commit()
            return false
        }
        return try {
            publish(newEffective)
            true
        } catch (e: Exception) {
            saved.edit().putBoolean(key, oldValue).commit()
            runCatching { publish(oldEffective) }
                .onFailure { android.util.Log.e("GameEnvironment", "Could not restore game diagnostics", it) }
            android.util.Log.e("GameEnvironment", "Could not update game diagnostics", e)
            false
        }
    }

    fun detailedGraphicsLogs(context: Context): Boolean =
        prefs(context).getBoolean("detailedGraphicsLogs", false)

    fun setDetailedGraphicsLogs(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("detailedGraphicsLogs", on).apply()
    }

    internal fun gameDebugLogsEffective(
        context: Context,
        logs: Boolean = logsEnabled(context),
        gameLogs: Boolean = gameDebugLogsEnabled(context),
    ): Boolean = EnhancedDiagnostics.isCurrentSessionActive(context) ||
        (logs && gameLogs && SessionPaths.currentSessionLogsRetained(context))

    fun clearCachedApps(context: Context): Boolean = prefs(context).getBoolean("clearCachedApps", false)
    fun setClearCachedApps(context: Context, on: Boolean) { prefs(context).edit().putBoolean("clearCachedApps", on).apply() }

    fun backgroundAppLimit(context: Context): Int =
        prefs(context).getInt("backgroundAppLimit", com.droiddeck.launcher.core.BackgroundAppLimit.OFF)
            .takeIf { it in com.droiddeck.launcher.core.BackgroundAppLimit.CHOICES } ?: com.droiddeck.launcher.core.BackgroundAppLimit.OFF
    fun setBackgroundAppLimit(context: Context, limit: Int) {
        require(limit in com.droiddeck.launcher.core.BackgroundAppLimit.CHOICES)
        prefs(context).edit().putInt("backgroundAppLimit", limit).apply()
    }

    const val MEMORY_HUD_OFF = "off"
    const val MEMORY_HUD_SIMPLE = "simple"
    const val MEMORY_HUD_ADVANCED = "advanced"

    fun memoryHud(context: Context): String =
        prefs(context).getString("memoryHud", MEMORY_HUD_OFF)
            ?.takeIf { it == MEMORY_HUD_SIMPLE || it == MEMORY_HUD_ADVANCED } ?: MEMORY_HUD_OFF
    fun setMemoryHud(context: Context, value: String) {
        require(value == MEMORY_HUD_OFF || value == MEMORY_HUD_SIMPLE || value == MEMORY_HUD_ADVANCED)
        prefs(context).edit().putString("memoryHud", value).apply()
    }

    fun storageDiagnosticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("storageDiagnostics", false)

    fun setStorageDiagnosticsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("storageDiagnostics", on).apply()
    }

    fun vacDiagnostics(context: Context): Boolean =
        prefs(context).getBoolean("vac_diagnostics", false)

    fun setVacDiagnostics(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("vac_diagnostics", on).apply()
    }

    fun buildWatermarkEnabled(context: Context): Boolean =
        BuildWatermarkSetting.enabled(context)

    fun setBuildWatermarkEnabled(context: Context, on: Boolean) {
        BuildWatermarkSetting.setEnabled(context, on)
    }

    private fun resolutionCap(context: Context, mode: String): Int = prefs(context).getInt("resolutionCap.$mode", 720)

    fun resolutionChosen(context: Context, mode: String): Boolean =
        prefs(context).contains("displayResolution.$mode") ||
            prefs(context).contains("resolutionCap.$mode") || customResolution(context, mode) != null

    fun resolutionChoice(context: Context, mode: String, panel: Pair<Int, Int>): String {
        val saved = prefs(context)
        saved.getString("displayResolution.$mode", null)?.let { value ->
            if (value == SessionDisplay.MATCH_SCREEN || SessionDisplay.isFollow(value) ||
                SessionDisplay.presetHeight(value) != null) return value
            parseResolution(value)?.let { return "${it.first}x${it.second}" }
        }
        if (!resolutionChosen(context, mode) && !saved.contains("shape")) return SessionDisplay.DEFAULT_RESOLUTION
        val custom = customResolution(context, mode)
        val legacy = SessionDisplay.resolve(panel, resolutionCap(context, mode), shapeMode(context), custom)
        return if (legacy == SessionDisplay.screenSize(panel)) SessionDisplay.MATCH_SCREEN
        else if (custom == null && legacy == SessionDisplay.resolve(panel, legacy.second, SHAPE_AUTO)) "${legacy.second}p"
        else "${legacy.first}x${legacy.second}"
    }

    fun setResolutionChoice(context: Context, mode: String, choice: String) {
        val value = if (choice == SessionDisplay.MATCH_SCREEN || SessionDisplay.isFollow(choice) ||
                SessionDisplay.presetHeight(choice) != null) choice else {
            val size = requireNotNull(parseResolution(choice)) { "Invalid resolution" }
            "${size.first}x${size.second}"
        }
        prefs(context).edit().putString("displayResolution.$mode", value).apply()
    }

    fun fexPreset(context: Context): String = prefs(context).getString("fexPreset", DEFAULT_FEX_PRESET) ?: DEFAULT_FEX_PRESET

    private const val DEFAULT_FEX_PRESET = "PERFORMANCE_TSO"

    fun forceSsbs(context: Context): Boolean = prefs(context).getBoolean("forceSsbs", false)

    fun setForceSsbs(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("forceSsbs", on).apply()
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { android.util.Log.e("GameEnvironment", "Could not update game environment", it) }
    }

    fun setFexPreset(context: Context, id: String) {
        prefs(context).edit().putString("fexPreset", id).apply()
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { android.util.Log.e("GameEnvironment", "Could not update game environment", it) }
    }

    fun steamClientArch(context: Context): String = prefs(context).getString("steamClientArch", null) ?: "arm64"

    fun setSteamClientArch(context: Context, id: String) {
        prefs(context).edit().putString("steamClientArch", id).apply()
    }

    fun activeSteamClientArch(context: Context): String =
        SessionState.steamClientArch?.takeIf { SessionState.running } ?: steamClientArch(context)

    fun activeSteamRoot(context: Context): java.io.File = steamRoot(context, activeSteamClientArch(context))

    fun steamRoot(context: Context, arch: String): java.io.File = java.io.File(
        com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context),
        if (arch == "x86_64") "root/.droiddeck-x64/.local/share/Steam" else "root/.local/share/Steam",
    )

    fun bootMovie(context: Context): String =
        prefs(context).getString("bootMovie", BootMovie.DROIDDECK_30) ?: BootMovie.DROIDDECK_30
    fun setBootMovie(context: Context, id: String) { prefs(context).edit().putString("bootMovie", id).apply() }
    fun bootMovieSound(context: Context): Boolean = prefs(context).getBoolean("bootMovieSound", true)
    fun setBootMovieSound(context: Context, on: Boolean) { prefs(context).edit().putBoolean("bootMovieSound", on).apply() }
    fun bootMovieVolume(context: Context): Int = prefs(context).getInt("bootMovieVolume", 100).coerceIn(0, 100)
    fun setBootMovieVolume(context: Context, percent: Int) { prefs(context).edit().putInt("bootMovieVolume", percent.coerceIn(0, 100)).apply() }
    fun bootMovieSeconds(context: Context): Int = prefs(context).getInt("bootMovieSeconds", 0).coerceAtLeast(0)
    fun setBootMovieSeconds(context: Context, seconds: Int) { prefs(context).edit().putInt("bootMovieSeconds", seconds.coerceAtLeast(0)).apply() }
    fun bootMovieApplyNext(context: Context): Boolean = prefs(context).getBoolean("bootMovieApplyNext", false)
    fun setBootMovieApplyNext(context: Context, on: Boolean) { prefs(context).edit().putBoolean("bootMovieApplyNext", on).apply() }
    fun bootMovieSteamSeen(context: Context): String? = prefs(context).getString("bootMovieSteamSeen", null)
    fun setBootMovieSteamSeen(context: Context, path: String) { prefs(context).edit().putString("bootMovieSteamSeen", path).apply() }
    fun bootMovieCustomName(context: Context): String? = prefs(context).getString("bootMovieCustomName", null)
    fun setBootMovieCustomName(context: Context, name: String?) {
        prefs(context).edit().apply { if (name == null) remove("bootMovieCustomName") else putString("bootMovieCustomName", name) }.apply()
    }

    const val STARTUP_OFF = ""
    const val STARTUP_STEAM = "steam"

    fun startupSession(context: Context): String {
        val p = prefs(context)
        return p.getString("startupSession", null) ?: if (p.getBoolean("runSteamAtStartup", false)) STARTUP_STEAM else STARTUP_OFF
    }

    fun setStartupSession(context: Context, id: String) {
        prefs(context).edit().putString("startupSession", id).remove("runSteamAtStartup").apply()
    }

    fun addedGamesDirs(context: Context): List<String> {
        val p = prefs(context)
        val list = p.getString("addedGamesDirs", null)
        if (list != null) return list.split('\n').filter { it.isNotEmpty() }
        val old = p.getString("addedGamesDir", "") ?: ""
        return if (old.isEmpty()) emptyList() else listOf(old)
    }

    fun setAddedGamesDirs(context: Context, dirs: List<String>) {
        prefs(context).edit().putString("addedGamesDirs", dirs.distinct().joinToString("\n")).remove("addedGamesDir").apply()
    }

    fun gameFolderBinds(context: Context): List<Pair<String, String>> =
        (prefs(context).getString("gameFolderBinds", "") ?: "").split('\n').mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { it[0] to it[1] }
        }

    fun setGameFolderBinds(context: Context, binds: List<Pair<String, String>>) {
        prefs(context).edit().putString("gameFolderBinds", binds.distinctBy { it.first }.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    fun theme(context: Context): String = prefs(context).getString("theme", "graphite") ?: "graphite"

    fun setTheme(context: Context, id: String) {
        prefs(context).edit().putString("theme", id).apply()
    }

    fun gradientColors(context: Context): Pair<Int, Int> = prefs(context).let {
        it.getInt("gradientPrimary", com.droiddeck.launcher.ui.Themes.DEFAULT_PRIMARY) to
            it.getInt("gradientSecondary", com.droiddeck.launcher.ui.Themes.DEFAULT_SECONDARY)
    }

    fun setGradientColors(context: Context, primary: Int, secondary: Int) {
        prefs(context).edit().putInt("gradientPrimary", primary).putInt("gradientSecondary", secondary).apply()
    }

    private fun customResolution(context: Context, mode: String): Pair<Int, Int>? =
        parseResolution(prefs(context).getString("customRes.$mode", null))

    fun parseResolution(text: String?): Pair<Int, Int>? {
        val parts = text?.trim()?.split('x', 'X', '×', '*')?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w !in 320..3840 || h !in 240..2160) return null
        return Pair(w and 1.inv(), h and 1.inv())
    }

    fun hdr(context: Context, mode: String): Boolean = prefs(context).getBoolean("hdr.$mode", false)

    fun setHdr(context: Context, mode: String, on: Boolean) {
        prefs(context).edit().putBoolean("hdr.$mode", on).apply()
    }

    const val FPS_UNPACED = -1

    const val FPS_LIMIT_OFF = 15
    const val FPS_LIMIT_MAX = 165

    const val PIP_FPS = 30

    fun compositorFpsLimit(sessionLimit: Int = 0, pip: Boolean = false): Int {
        val limit = if (sessionLimit <= 0) FPS_UNPACED else sessionLimit
        return if (pip && (limit <= 0 || limit > PIP_FPS)) PIP_FPS else limit
    }

    val upscalerChoices = listOf(
        0 to "Linear", 2 to "Nearest", 4 to "AMD FSR 1", 3 to "Snapdragon GSR",
        8 to "Snapdragon GSR (quality)", 7 to "NVIDIA NIS", 6 to "Sharpen only",
    )

    private val upscalerLabels = mapOf(
        0 to R.string.sprefs_upscaler_linear, 2 to R.string.sprefs_upscaler_nearest,
        8 to R.string.sprefs_upscaler_gsr_quality, 6 to R.string.sprefs_upscaler_sharpen,
    )

    fun upscalerChoices(context: Context): List<Pair<Int, String>> =
        upscalerChoices.map { (mode, label) -> mode to (upscalerLabels[mode]?.let(context::getString) ?: label) }

    fun canonicalUpscaler(mode: Int): Int = when (mode) {
        1 -> 0
        5 -> 4
        else -> mode.takeIf { m -> upscalerChoices.any { it.first == m } } ?: 0
    }

    fun upscalerHasSharpness(mode: Int): Boolean = canonicalUpscaler(mode) in 3..8

    fun upscaler(context: Context): Int = canonicalUpscaler(prefs(context).getInt("upscaler", 0))

    fun setUpscaler(context: Context, mode: Int) {
        prefs(context).edit().putInt("upscaler", canonicalUpscaler(mode)).apply()
    }

    fun upscaleSharpness(context: Context): Int = prefs(context).getInt("upscaleSharpness", 75).coerceIn(0, 100)

    fun setUpscaleSharpness(context: Context, pct: Int) {
        prefs(context).edit().putInt("upscaleSharpness", pct.coerceIn(0, 100)).apply()
    }


    fun screenEffects(context: Context): ScreenEffects {
        val text = prefs(context).getString("screenEffects", null) ?: return ScreenEffects.OFF
        return runCatching { ScreenEffects.decode(JSONObject(text)) }.getOrDefault(ScreenEffects.OFF)
    }

    fun setScreenEffects(context: Context, effects: ScreenEffects) {
        prefs(context).edit().putString("screenEffects", effects.encode().toString()).apply()
    }

    fun sessionPrefs(context: Context): android.content.SharedPreferences = prefs(context)

    val textureAnisotropyChoices = TextureFiltering.ANISOTROPY.map { it to if (it == 0) "Off" else "${it}x" }

    fun textureAnisotropyChoices(context: Context): List<Pair<Int, String>> =
        textureAnisotropyChoices.map { (value, label) -> value to if (value == 0) context.getString(R.string.frame_gen_off) else label }

    val textureLodBiasChoices = TextureFiltering.LOD_BIAS.map {
        it to when (it) {
            TextureFiltering.LOD_BIAS_OFF -> "Off"
            TextureFiltering.LOD_BIAS_AUTO -> "Auto (match scaling)"
            else -> it
        }
    }

    fun textureLodBiasChoices(context: Context): List<Pair<String, String>> = textureLodBiasChoices.map { (value, label) ->
        value to when (value) {
            TextureFiltering.LOD_BIAS_OFF -> context.getString(R.string.frame_gen_off)
            TextureFiltering.LOD_BIAS_AUTO -> context.getString(R.string.sprefs_lod_bias_auto)
            else -> label
        }
    }

    fun textureAnisotropy(context: Context): Int =
        prefs(context).getInt("textureAnisotropy", 0).takeIf { it in TextureFiltering.ANISOTROPY } ?: 0

    fun setTextureAnisotropy(context: Context, value: Int) {
        prefs(context).edit().putInt("textureAnisotropy", value).apply()
        publishGameEnvironment(context)
    }

    fun textureLodBias(context: Context): String =
        prefs(context).getString("textureLodBias", null)?.takeIf { it in TextureFiltering.LOD_BIAS } ?: TextureFiltering.LOD_BIAS_OFF

    fun setTextureLodBias(context: Context, choice: String) {
        prefs(context).edit().putString("textureLodBias", choice).apply()
        publishGameEnvironment(context)
    }

    fun gameTextureFiltering(context: Context, scope: String): GameTextureFiltering {
        require(GameEnvironment.validScope(scope))
        return gameTextureFiltering(context)[scope] ?: GameTextureFiltering()
    }

    fun gameTextureFiltering(context: Context): Map<String, GameTextureFiltering> {
        val json = runCatching {
            JSONObject(prefs(context).getString("gameTextureFiltering", null) ?: "{}")
        }.getOrElse { JSONObject() }
        return buildMap {
            json.keys().forEach { scope ->
                if (!GameEnvironment.validScope(scope)) return@forEach
                val entry = json.optJSONObject(scope) ?: return@forEach
                val anisotropy = entry.optInt("anisotropy").takeIf {
                    entry.has("anisotropy") && it in TextureFiltering.ANISOTROPY
                }
                val lodBias = entry.optString("lodBias").takeIf {
                    entry.has("lodBias") && it in TextureFiltering.LOD_BIAS
                }
                if (anisotropy != null || lodBias != null) {
                    put(scope, GameTextureFiltering(anisotropy, lodBias))
                }
            }
        }
    }

    fun setGameTextureAnisotropy(context: Context, scope: String, value: Int?) {
        require(value == null || value in TextureFiltering.ANISOTROPY)
        updateGameTextureFiltering(context, scope) { it.copy(anisotropy = value) }
    }

    fun setGameTextureLodBias(context: Context, scope: String, choice: String?) {
        require(choice == null || choice in TextureFiltering.LOD_BIAS)
        updateGameTextureFiltering(context, scope) { it.copy(lodBias = choice) }
    }

    fun clearGameTextureFiltering(context: Context, scope: String) {
        require(GameEnvironment.validScope(scope))
        val choices = gameTextureFiltering(context).toMutableMap()
        if (choices.remove(scope) == null) return
        saveGameTextureFiltering(context, choices)
        publishGameEnvironment(context)
    }

    private fun updateGameTextureFiltering(
        context: Context,
        scope: String,
        change: (GameTextureFiltering) -> GameTextureFiltering,
    ) {
        require(GameEnvironment.validScope(scope))
        val choices = gameTextureFiltering(context).toMutableMap()
        val next = change(choices[scope] ?: GameTextureFiltering())
        if (next.anisotropy == null && next.lodBias == null) choices.remove(scope) else choices[scope] = next
        saveGameTextureFiltering(context, choices)
        publishGameEnvironment(context)
    }

    private fun saveGameTextureFiltering(context: Context, choices: Map<String, GameTextureFiltering>) {
        val json = JSONObject()
        choices.forEach { (id, choice) ->
            json.put(id, JSONObject().apply {
                choice.anisotropy?.let { put("anisotropy", it) }
                choice.lodBias?.let { put("lodBias", it) }
            })
        }
        prefs(context).edit().putString("gameTextureFiltering", json.toString()).apply()
    }

    private fun publishGameEnvironment(context: Context) {
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { android.util.Log.e("GameEnvironment", "Could not update game environment", it) }
    }

    fun prefMode(mode: String): String = if (mode == SessionService.MODE_RUN) SessionService.MODE_STEAM else mode

    fun pipAutoEnter(context: Context): Boolean = prefs(context).getBoolean("pipAutoEnter", true)

    fun setPipAutoEnter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("pipAutoEnter", enabled).apply()
    }

    fun unfoldedControls(context: Context): Boolean = prefs(context).getBoolean("unfoldedControls", false)

    fun controlsBelowWanted(context: Context, mode: String, width: Int, height: Int, pip: Boolean = false): Boolean =
        SessionDisplay.unfoldedControlsWanted(
            enabled = unfoldedControls(context),
            foldable = SessionDisplay.foldable(context),
            controllerConnected = com.droiddeck.launcher.input.PadBridge.anyControllerConnected() ||
                com.droiddeck.launcher.input.SteamControllerBle.connected,
            keepWithController = unfoldedControlsWithController(context),
            gamepadAvailable = mode == SessionService.MODE_STEAM && builtinPad(context),
            pip = pip, width = width, height = height,
            smallestWidthDp = context.resources.configuration.smallestScreenWidthDp,
        )

    fun setUnfoldedControls(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("unfoldedControls", enabled).apply()
    }

    fun unfoldedControlsWithController(context: Context): Boolean =
        prefs(context).getBoolean("unfoldedControlsWithController", false)

    fun setUnfoldedControlsWithController(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("unfoldedControlsWithController", enabled).apply()
    }

    const val SURFACE_STICK = "stick"
    const val SURFACE_TRACKPAD = "trackpad"

    fun gamepadSurface(context: Context, right: Boolean): String =
        prefs(context).getString(if (right) "gamepadRight" else "gamepadLeft", null)
            ?: if (right) SURFACE_TRACKPAD else SURFACE_STICK

    fun deckPanel(context: Context): com.droiddeck.launcher.input.DeckPanel =
        com.droiddeck.launcher.input.DeckPanel.from(prefs(context).getString("deckPanel", null))

    fun setDeckPanel(context: Context, panel: com.droiddeck.launcher.input.DeckPanel) {
        prefs(context).edit().putString("deckPanel", panel.id).apply()
    }

    fun suspendPolicy(context: Context, mode: String): String =
        prefs(context).getString("suspendPolicy.${prefMode(mode)}",
            if (mode == SessionService.MODE_STEAM) SUSPEND_NATIVE else SUSPEND_MANUAL)
            ?.let { if (it == LEGACY_SUSPEND_DOWNLOADS) SUSPEND_AUTO else it }
            ?.takeIf { it in setOf(SUSPEND_AUTO, SUSPEND_NATIVE, SUSPEND_MANUAL, SUSPEND_NEVER) }
            ?.let { if (mode != SessionService.MODE_STEAM && it == SUSPEND_NATIVE) SUSPEND_AUTO else it }
            ?: SUSPEND_MANUAL

    fun setSuspendPolicy(context: Context, mode: String, policy: String) {
        val normalized = policy.takeIf { it in setOf(SUSPEND_AUTO, SUSPEND_MANUAL, SUSPEND_NEVER) ||
            (it == SUSPEND_NATIVE && mode == SessionService.MODE_STEAM) }
            ?: SUSPEND_MANUAL
        val prefs = prefs(context)
        val key = "suspendPolicy.${prefMode(mode)}"
        prefs.edit().apply {
            if (mode == SessionService.MODE_STEAM && prefs.getString(key, null) == LEGACY_SUSPEND_DOWNLOADS) {
                putBoolean(STEAM_DOWNLOADS_IN_BACKGROUND, true)
            }
            putString(key, normalized)
        }.apply()
    }


    fun lowStorageWarning(context: Context): Boolean = prefs(context).getBoolean(LOW_STORAGE_WARNING, true)

    fun setLowStorageWarning(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(LOW_STORAGE_WARNING, on).apply()
    }

    fun slowUsbWarningOff(context: Context): Boolean = prefs(context).getBoolean(SLOW_USB_WARNING_OFF, false)

    fun setSlowUsbWarningOff(context: Context) {
        prefs(context).edit().putBoolean(SLOW_USB_WARNING_OFF, true).apply()
    }

    fun slowUsbDismissed(context: Context): Set<String> = prefs(context).getStringSet(SLOW_USB_DISMISSED, null)?.toSet().orEmpty()

    fun setSlowUsbDismissed(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(SLOW_USB_DISMISSED, keys.toSet()).apply()
    }

    fun steamDownloadsInBackground(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(STEAM_DOWNLOADS_IN_BACKGROUND, false) ||
            prefs.getString("suspendPolicy.${SessionService.MODE_STEAM}", null) == LEGACY_SUSPEND_DOWNLOADS
    }

    fun setSteamDownloadsInBackground(context: Context, enabled: Boolean) {
        val prefs = prefs(context)
        val policyKey = "suspendPolicy.${SessionService.MODE_STEAM}"
        prefs.edit().apply {
            putBoolean(STEAM_DOWNLOADS_IN_BACKGROUND, enabled)
            if (prefs.getString(policyKey, null) == LEGACY_SUSPEND_DOWNLOADS) {
                putString(policyKey, SUSPEND_AUTO)
            }
        }.apply()
    }


    fun gameStorage(context: Context): String = prefs(context).getString("gameStorage", "") ?: ""

    const val GAME_STORAGE_OFF = "off"

    fun gameStorageLabel(context: Context): String = prefs(context).getString("gameStorageLabel", "SD Card") ?: "SD Card"

    fun setGameStorage(context: Context, path: String, label: String) {
        prefs(context).edit().putString("gameStorage", path).putString("gameStorageLabel", label).apply()
    }

    fun steamLibraries(context: Context): String = prefs(context).getString("steamLibraries", "") ?: ""

    fun setSteamLibraries(context: Context, json: String) {
        prefs(context).edit().putString("steamLibraries", json).apply()
    }

    fun steamLibraryNextId(context: Context): Int = prefs(context).getInt("steamLibraryNextId", 1)

    fun setSteamLibraryNextId(context: Context, id: Int) {
        prefs(context).edit().putInt("steamLibraryNextId", id).apply()
    }
}
