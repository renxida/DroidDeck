package com.droiddeck.launcher.session

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/**
 * Runs alone in the :balloon process and takes memory on request for [FreeMemory]: shared memory filled with
 * random bytes, so zram cannot compress it away. Losing this process ends nothing else; on unbind it lets the
 * memory go and exits.
 */
class MemoryBalloonService : Service() {
    private val regions = ArrayList<SharedMemory>()
    private val maps = ArrayList<ByteBuffer>()
    private var totalMb = 0
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        Log.w(TAG, "no release after ${WATCHDOG_MS / 1000} s; letting $totalMb MB go")
        release()
    }
    private val chunk: ByteArray by lazy { ByteArray(1 shl 20).also { java.util.Random().nextBytes(it) } }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != Process.myUid()) return false
            when (code) {
                PID -> reply?.writeInt(Process.myPid())
                GROW -> reply?.writeInt(runCatching { grow(data.readInt()) }.getOrElse { Log.w(TAG, "grow", it); -1 })
                RELEASE -> { release(); reply?.writeInt(0) }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    private fun grow(mb: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return totalMb
        val add = mb.coerceIn(0, minOf(MAX_STEP_MB, MAX_MB - totalMb))
        if (add > 0) {
            val memory = SharedMemory.create("kestrel-balloon", add shl 20)
            regions += memory
            val map = memory.mapReadWrite()
            maps += map
            while (map.remaining() >= chunk.size) map.put(chunk)
            totalMb += add
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        return totalMb
    }

    @Synchronized
    private fun release() {
        handler.removeCallbacks(watchdog)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        maps.forEach { runCatching { SharedMemory.unmap(it) } }
        maps.clear()
        regions.forEach { runCatching { it.close() } }
        regions.clear()
        totalMb = 0
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
        handler.postDelayed({ exitProcess(0) }, EXIT_DELAY_MS)
    }

    companion object {
        private const val TAG = "MemoryBalloon"
        const val PID = IBinder.FIRST_CALL_TRANSACTION
        const val GROW = IBinder.FIRST_CALL_TRANSACTION + 1
        const val RELEASE = IBinder.FIRST_CALL_TRANSACTION + 2
        const val PROCESS_SUFFIX = ":balloon"
        private const val MAX_MB = 4096
        private const val MAX_STEP_MB = 1024
        private const val WATCHDOG_MS = 15_000L
        private const val EXIT_DELAY_MS = 300L
    }
}
