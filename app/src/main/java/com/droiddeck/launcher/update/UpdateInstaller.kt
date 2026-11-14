package com.droiddeck.launcher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A way to install an update. [SelfUpdate] tries [SelfUpdate.installers] in order and only ever passes an APK that
 * has just passed the package, signing-certificate and newer checks. [install] returns false when this backend
 * can't install here, so the next one is tried; otherwise it reports through [onResult], possibly later.
 */
interface UpdateInstaller {
    sealed class Result {
        object Success : Result()
        /** The system wants the person to confirm; [confirm] opens its dialog. */
        class NeedsConfirmation(val confirm: Intent) : Result()
        object Cancelled : Result()
        class Failed(val message: String) : Result()
    }

    val name: String

    /**
     * True when this backend is switched on to install with nobody at the phone and no prompt;
     * [SelfUpdate.installUnattended] hands an update only to such backends.
     */
    fun unattended(context: Context): Boolean = false

    fun install(context: Context, verifiedApk: File, onResult: (Result) -> Unit): Boolean
}

/** The system installer's one-tap confirmation; at targetSdk 28 a self-update always needs it. */
object PackageInstallerPrompt : UpdateInstaller {
    private const val TAG = "SelfUpdate"
    internal const val ACTION_STATUS = "dev.renxida.kestrel.SELF_UPDATE_STATUS"
    internal const val EXTRA_SESSION = "self_update_session"
    private val callbacks = ConcurrentHashMap<Int, (UpdateInstaller.Result) -> Unit>()

    override val name = "package-installer"

    override fun install(context: Context, verifiedApk: File, onResult: (UpdateInstaller.Result) -> Unit): Boolean {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        runCatching {
            installer.mySessions.filter { it.appPackageName == app.packageName }.forEach { installer.abandonSession(it.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(verifiedApk.length())
        }
        val id = runCatching { installer.createSession(params) }
            .onFailure { Log.w(TAG, "no installer session: ${it.message}") }.getOrNull() ?: return false
        return try {
            installer.openSession(id).use { session ->
                verifiedApk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, verifiedApk.length()).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                callbacks[id] = onResult
                val status = Intent(app, SelfUpdateReceiver::class.java).setAction(ACTION_STATUS).putExtra(EXTRA_SESSION, id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(app, id, status, flags).intentSender)
            }
            Log.i(TAG, "installer session $id committed")
            true
        } catch (e: Exception) {
            callbacks.remove(id)
            runCatching { installer.abandonSession(id) }
            Log.w(TAG, "installer session $id failed: ${e.javaClass.simpleName}: ${e.message}")
            onResult(UpdateInstaller.Result.Failed("${e.javaClass.simpleName}: ${e.message}"))
            true
        }
    }

    internal fun result(intent: Intent): UpdateInstaller.Result {
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        return when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> UpdateInstaller.Result.Success
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) UpdateInstaller.Result.NeedsConfirmation(confirm)
                else UpdateInstaller.Result.Failed("no confirmation intent")
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstaller.Result.Cancelled
            else -> UpdateInstaller.Result.Failed("status $status $message".trim())
        }
    }

    internal fun deliver(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_SESSION, -1)
        val result = result(intent)
        val callback = if (result is UpdateInstaller.Result.NeedsConfirmation) callbacks[id] else callbacks.remove(id)
        if (callback != null) callback(result) else SelfUpdate.installResult(context.applicationContext, result)
    }
}

class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PackageInstallerPrompt.ACTION_STATUS) PackageInstallerPrompt.deliver(context, intent)
    }
}
