package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/** Which Steam branch a launch should play; droiddeck-steam-branch applies it before Steam starts. */
object SteamBranch {
    const val BETA = "beta"
    const val STABLE = "public"
    private const val TAG = "SteamBranch"
    private val offeredApps = setOf(2868840)

    fun offered(appId: Int): Boolean = appId in offeredApps

    fun request(context: Context, appId: Int, branch: String) {
        if (!offered(appId) || branch !in setOf(BETA, STABLE)) return
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
