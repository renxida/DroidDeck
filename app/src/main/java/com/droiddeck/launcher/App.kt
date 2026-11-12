package com.droiddeck.launcher

import android.app.Application
import com.droiddeck.launcher.runtime.BwrapSpawner
import com.droiddeck.launcher.session.CrashHandler
import com.droiddeck.launcher.session.EnhancedSessionLogShare
import com.droiddeck.launcher.session.GpuClockPin

class App : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(base))
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName
        com.droiddeck.launcher.lab.LabOverlay.load(this, process == packageName)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.droiddeck.launcher.core.AppLanguage.systemChanged(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        Thread({ runCatching { EnhancedSessionLogShare.cleanupAbandonedStages(this) } }, "diagnostic-stage-cleanup").start()
        com.droiddeck.launcher.ui.Themes.loadGradient(this)
        com.droiddeck.launcher.ui.BuildWatermark.install(this)
        HomeApp.trackRecents(this)
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (process == "$packageName:shortcuts_sync" || process == "$packageName:bottom_desktop") return
        CrashHandler.install(this)
        com.droiddeck.launcher.agent.AgentHost.refresh(this)
        if (com.droiddeck.launcher.agent.AgentHost.available) com.droiddeck.launcher.lab.LabNotice.openOnInstall = "agent"
        com.droiddeck.launcher.lab.LabNotice.started(this)
        com.droiddeck.launcher.core.SteamIntegration.syncShortcutsListing(this)
        com.droiddeck.launcher.session.QuestExperimental.initialize(this)
        com.droiddeck.launcher.session.SessionPrefs.applyNtsyncDefault(this)
        com.droiddeck.launcher.session.SessionPrefs.settleGpuDriverMode(this)
        com.droiddeck.launcher.update.AppUpdates.init(this)
        com.droiddeck.launcher.core.DownloadSource.init(this)
        prefetchAddonPacks()
        GpuClockPin.clearLeftover(this)
        BwrapSpawner.start(this)
        com.droiddeck.launcher.runtime.RootSpawner.start(this)
        com.droiddeck.launcher.frontend.GameFileSync.start(this)
        com.droiddeck.launcher.sync.Syncthing.init(this)
    }

    private fun prefetchAddonPacks() {
        val app = this
        Thread({
            runCatching {
                if (com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app).isDirectory) {
                    com.droiddeck.launcher.core.AddonPack.PROOT.ensure(app)
                    com.droiddeck.launcher.core.AddonPack.DIRECTAUDIO.ensure(app)
                }
            }.onFailure { android.util.Log.w("App", "add-on pack prefetch failed", it) }
        }, "addon-packs").start()
    }
}
