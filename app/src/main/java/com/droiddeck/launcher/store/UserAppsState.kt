package com.droiddeck.launcher.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.UserApps

object UserAppsState {
    private val main = Handler(Looper.getMainLooper())

    var items by mutableStateOf<List<UserApps.App>>(emptyList())
        private set
    var working by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<String?>(null)
        private set
    var percent by mutableStateOf(-1)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    private var listing = 0

    fun refresh(context: Context) {
        val app = context.applicationContext
        val seq = ++listing
        Thread({
            val list = runCatching { UserApps.list(app) }.getOrDefault(emptyList())
            main.post { if (seq == listing) items = list }
        }, "user-apps-list").start()
    }

    fun add(context: Context, request: UserApps.Request, label: String) =
        run(context, label, R.string.user_apps_added) { app, progress -> UserApps.add(app, request, progress) }

    fun remove(context: Context, target: UserApps.App) =
        run(context, target.name, R.string.user_apps_removed) { app, _ -> UserApps.remove(app, target) }

    fun edit(context: Context, target: UserApps.App, name: String, icon: String?, fex: String? = null) =
        run(context, name, R.string.user_apps_saved) { app, _ -> UserApps.edit(app, target, name, icon, fex) }

    private fun run(context: Context, label: String, done: Int, work: (Context, (String, Int) -> Unit) -> String?) {
        if (working != null) return
        val app = context.applicationContext
        working = label; stage = null; percent = -1; lastError = null
        Thread({
            val problem = try {
                work(app) { s, p -> main.post { stage = s; percent = p } }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            main.post {
                working = null; stage = null; percent = -1
                lastError = problem?.let { "$label: $it" }
                if (problem == null) Toast.makeText(app, app.getString(done, label), Toast.LENGTH_SHORT).show()
                refresh(app)
            }
        }, "user-apps-work").start()
    }
}
