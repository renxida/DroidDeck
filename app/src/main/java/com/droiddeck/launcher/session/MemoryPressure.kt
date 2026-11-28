package com.droiddeck.launcher.session

import java.io.File
import java.io.FileNotFoundException

/**
 * /proc/pressure/memory. Android's app domains may not read it (proc_pressure_mem), and every refused open is an
 * SELinux denial with a logcat line, so after the first refusal (or a kernel without PSI) it is not opened again.
 */
internal class MemoryPressure(private val read: () -> String = { File(PATH).readText() }) {
    @Volatile var unreadable = false
        private set

    fun text(): String? {
        if (unreadable) return null
        return try {
            read()
        } catch (e: FileNotFoundException) {
            unreadable = true
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val PATH = "/proc/pressure/memory"
        val shared = MemoryPressure()
    }
}
