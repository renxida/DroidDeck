package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller

/** Diagnostics' memory-ordering test: a session that runs droiddeck-tso-litmus and ends when it is done. */
object TsoLitmus {
    const val PROGRAM = "/usr/local/bin/droiddeck-tso-litmus"

    fun intent(context: Context): Intent = Intent(context, SessionActivity::class.java)
        .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
        .putExtra(SessionService.EXTRA_PROGRAM, PROGRAM)
        .putExtra(SessionService.EXTRA_PROGRAM_ARGS, emptyArray<String>())

    fun start(context: Context): Boolean {
        if (SessionState.running || LinuxRuntimeInstaller.isBusy()) {
            Toast.makeText(context, R.string.setup_tso_litmus_busy, Toast.LENGTH_LONG).show()
            return false
        }
        context.startActivity(intent(context).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return true
    }
}
