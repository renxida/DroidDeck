package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.PhantomProcessStatus

/**
 * Names the likely cause of a session or app death so every run's logs carry one greppable
 * `DROIDDECK_EXIT_CAUSE` line. Evidence and sources: docs/research/crash-catalogue.md.
 */
object ExitCause {
    enum class Kind(val tag: String) {
        CLEAN("clean"),
        EXIT_CODE("exit_code"),
        KILLED("sigkill"),
        TERMINATED("sigterm"),
        HANGUP("sighup"),
        CRASH_SIGNAL("crash_signal"),
        OTHER_SIGNAL("signal"),
        LOW_MEMORY("lmk"),
        EXCESSIVE_CPU("excessive_cpu"),
        APP_CRASH("app_crash"),
        APP_NATIVE_CRASH("app_native_crash"),
        ANR("anr"),
        FROZEN("freezer"),
        USER("user"),
        DEVICE_REBOOT("device_reboot"),
        UNKNOWN("unknown"),
    }

    enum class Suspect(val tag: String) {
        NONE("none"),
        PHANTOM_PROCESS_KILLER("phantom_process_killer"),
        VENDOR_MEMORY_WATCHDOG("vendor_memory_watchdog"),
        MEMORY_PRESSURE("memory_pressure"),
        LMKD("lmkd"),
        GUEST("guest"),
        GPU_OR_NATIVE("gpu_driver_or_native_code"),
        SYSTEM("system"),
        UNKNOWN("unknown"),
    }

    data class Verdict(val kind: Kind, val suspect: Suspect, val status: Int?, val signal: Int?, val detail: String)

    // ApplicationExitInfo.REASON_* values (API 30+, FREEZER API 33); kept numeric so this stays host-testable.
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_FREEZER = 14

    private val crashSignals = setOf(4, 5, 6, 7, 8, 11, 31)

    /** Process.waitFor() reports a child killed by signal n as 128 + n, as shells do. */
    fun signalOf(status: Int): Int? = if (status in 129..192) status - 128 else null

    fun signalName(signal: Int): String = when (signal) {
        1 -> "SIGHUP"; 2 -> "SIGINT"; 4 -> "SIGILL"; 5 -> "SIGTRAP"; 6 -> "SIGABRT"; 7 -> "SIGBUS"
        8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"; 13 -> "SIGPIPE"; 15 -> "SIGTERM"; 31 -> "SIGSYS"
        else -> "signal $signal"
    }

    fun isXiaomiFamily(manufacturer: String?, brand: String?): Boolean =
        listOf(manufacturer, brand).any { it?.lowercase() in setOf("xiaomi", "redmi", "poco") }

    /**
     * The session's guest (proot) ended while the app itself lived on. [pressureRecent] is true when
     * Android sent a memory trim or MemAvailable was low shortly before.
     */
    fun ofGuest(
        status: Int,
        stopRequested: Boolean,
        phantom: PhantomProcessStatus,
        xiaomi: Boolean,
        pressureRecent: Boolean,
    ): Verdict {
        if (status == 0) return Verdict(Kind.CLEAN, Suspect.NONE, 0, null, "the guest exited normally")
        val signal = signalOf(status)
        if (signal == null) {
            return Verdict(Kind.EXIT_CODE, Suspect.GUEST, status, null,
                if (stopRequested) "exit code $status after a requested stop" else "the guest's own exit code $status; see session.log")
        }
        val name = signalName(signal)
        return when {
            signal == 9 && stopRequested ->
                Verdict(Kind.KILLED, Suspect.NONE, status, signal, "SIGKILL during a requested stop")
            signal == 9 && (phantom == PhantomProcessStatus.ENABLED || phantom == PhantomProcessStatus.UNSET) ->
                Verdict(Kind.KILLED, Suspect.PHANTOM_PROCESS_KILLER, status, signal,
                    "SIGKILL while Android's child-process limit is ${phantom.name.lowercase()}")
            signal == 9 && xiaomi ->
                Verdict(Kind.KILLED, Suspect.VENDOR_MEMORY_WATCHDOG, status, signal,
                    "SIGKILL on a Xiaomi-family ROM, whose memory watchdog kills large native processes")
            signal == 9 && pressureRecent ->
                Verdict(Kind.KILLED, Suspect.MEMORY_PRESSURE, status, signal, "SIGKILL right after memory pressure")
            signal == 9 ->
                Verdict(Kind.KILLED, Suspect.UNKNOWN, status, signal, "SIGKILL from outside the session")
            signal == 15 ->
                Verdict(Kind.TERMINATED, if (stopRequested) Suspect.NONE else Suspect.UNKNOWN, status, signal, name)
            signal == 1 ->
                Verdict(Kind.HANGUP, Suspect.SYSTEM, status, signal, "SIGHUP reached the session tree")
            signal in crashSignals ->
                Verdict(Kind.CRASH_SIGNAL, Suspect.GUEST, status, signal, "$name in the guest's root process")
            else -> Verdict(Kind.OTHER_SIGNAL, Suspect.UNKNOWN, status, signal, name)
        }
    }

    /** One of Android's ApplicationExitInfo records for this app's main process. */
    fun ofAppExit(reason: Int, status: Int, description: String?): Verdict {
        val said = description?.trim()?.takeIf { it.isNotEmpty() }
        val detail = said ?: "reason $reason"
        return when (reason) {
            REASON_LOW_MEMORY -> Verdict(Kind.LOW_MEMORY, Suspect.LMKD, status, 9, said ?: "lmkd killed the app and its whole process group")
            REASON_EXCESSIVE_RESOURCE_USAGE -> Verdict(Kind.EXCESSIVE_CPU,
                if (said?.contains("child process", ignoreCase = true) == true) Suspect.PHANTOM_PROCESS_KILLER else Suspect.SYSTEM,
                status, 9, detail)
            REASON_CRASH -> Verdict(Kind.APP_CRASH, Suspect.UNKNOWN, status, null, detail)
            REASON_CRASH_NATIVE -> Verdict(Kind.APP_NATIVE_CRASH, Suspect.GPU_OR_NATIVE, status, status.takeIf { it in 1..64 }, detail)
            REASON_ANR -> Verdict(Kind.ANR, Suspect.SYSTEM, status, null, detail)
            REASON_FREEZER -> Verdict(Kind.FROZEN, Suspect.SYSTEM, status, null, detail)
            REASON_USER_REQUESTED, REASON_USER_STOPPED, REASON_EXIT_SELF -> Verdict(Kind.USER, Suspect.NONE, status, null, detail)
            REASON_SIGNALED -> Verdict(if (status == 9) Kind.KILLED else Kind.OTHER_SIGNAL,
                if (status == 9) Suspect.UNKNOWN else Suspect.SYSTEM, status, status, said ?: signalName(status))
            else -> Verdict(Kind.UNKNOWN, Suspect.UNKNOWN, status, null, detail)
        }
    }

    data class AppExit(val timestampMs: Long, val reason: Int, val status: Int, val description: String?)

    /**
     * A session folder whose app process never ran its teardown. The first exit record after the
     * session's start names the cause; without one, a last event older than the current boot means
     * the device went down (reboot, kernel or firmware reset, power loss) while the session ran.
     */
    fun ofAbandoned(sessionStartMs: Long?, lastEventMs: Long?, bootMs: Long, exits: List<AppExit>): Verdict {
        val since = sessionStartMs ?: lastEventMs
        val record = exits.filter { since == null || it.timestampMs >= since }.minByOrNull { it.timestampMs }
        if (record != null) return ofAppExit(record.reason, record.status, record.description)
        if (lastEventMs != null && lastEventMs < bootMs) {
            return Verdict(Kind.DEVICE_REBOOT, Suspect.SYSTEM, null, null,
                "the device restarted ${(bootMs - lastEventMs) / 1000} s after the session's last event")
        }
        return Verdict(Kind.UNKNOWN, Suspect.UNKNOWN, null, null, "no exit record from Android")
    }

    const val FAILURE_PHANTOM = "PHANTOM_KILLED"
    const val FAILURE_VENDOR = "VENDOR_MEMORY_KILLED"

    /** The session failure code that gets a user-facing explanation, if any. */
    fun failureCode(verdict: Verdict): String? = when {
        verdict.kind != Kind.KILLED -> null
        verdict.suspect == Suspect.PHANTOM_PROCESS_KILLER -> FAILURE_PHANTOM
        verdict.suspect == Suspect.VENDOR_MEMORY_WATCHDOG -> FAILURE_VENDOR
        else -> null
    }

    /** `some` and `full` avg10 from /proc/pressure/memory. */
    fun parsePsi(text: String): Pair<Double?, Double?> {
        fun avg10(kind: String) = Regex("""(?m)^$kind avg10=([0-9.]+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        return avg10("some") to avg10("full")
    }

    fun line(scope: String, verdict: Verdict): String = buildString {
        append("DROIDDECK_EXIT_CAUSE scope=").append(scope)
        append(" class=").append(verdict.kind.tag)
        append(" suspect=").append(verdict.suspect.tag)
        append(" status=").append(verdict.status?.toString() ?: "-")
        append(" signal=").append(verdict.signal?.let { signalName(it) }?.replace(' ', '_') ?: "-")
        append(" detail=\"").append(verdict.detail.replace('"', '\'').replace('\n', ' ')).append('"')
    }

    fun fields(scope: String, verdict: Verdict): Map<String, Any?> = mapOf(
        "scope" to scope,
        "class" to verdict.kind.tag,
        "suspect" to verdict.suspect.tag,
        "status" to verdict.status,
        "signal" to verdict.signal,
        "detail" to verdict.detail,
    )
}
