package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/** Which Steam branch a launch should play; droiddeck-steam-branch applies it before Steam starts. */
object SteamBranch {
    const val BETA = BootLine.SteamBranchNames.BETA
    const val STABLE = BootLine.SteamBranchNames.STABLE
    private const val TAG = "SteamBranch"
    private val offeredApps = setOf(2868840)

    fun offered(appId: Int): Boolean = appId in offeredApps

    /** The branch the latest launch asked for, for the loading screen: null when that game offers no choice. */
    class Request(val appId: Int, val branch: String, val at: Long)
    @Volatile var last: Request? = null
        private set

    /** Forgets the latest request, so a test starts with no branch on the loading screen. */
    internal fun forget() { last = null }

    fun request(context: Context, appId: Int, branch: String) {
        val chosen = offered(appId) && branch in setOf(BETA, STABLE)
        last = if (chosen) Request(appId, branch, System.currentTimeMillis()) else null
        if (!chosen) return
        runCatching { write(File(LinuxRuntime.sessionRoot(context), "steam-branch"), appId, branch) }
            .onFailure { Log.w(TAG, "could not record branch $branch for $appId", it) }
    }

    internal fun write(directory: File, appId: Int, branch: String) {
        directory.mkdirs()
        val staged = File(directory, "$appId.tmp")
        staged.writeText("$branch\n")
        check(staged.renameTo(File(directory, appId.toString()))) { "could not publish branch request" }
    }
}
