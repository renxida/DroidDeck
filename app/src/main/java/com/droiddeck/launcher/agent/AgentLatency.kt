package com.droiddeck.launcher.agent

import android.os.SystemClock
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * The `latency` method: injects inputs and follows, for each, the first game frame whose content changes in a
 * watched region to the screen (response_probe.c). `inputs` are AgentInput requests of type pad, key or tap,
 * used in turn; each should undo the one before (pad down, pad up) so the picture ends where it started.
 */
object AgentLatency {
    const val MAX_COUNT = 20
    private const val STATUS_NOT_CALIBRATED = 3L
    private val STATUS = arrayOf("ok", "no-change", "unreadable", "not-calibrated", "superseded", "no-present")
    private val lock = Any()

    fun handle(request: JSONObject): JSONObject = synchronized(lock) {
        if (!SessionState.running || !SessionState.firstFrameSeen) {
            throw AgentException("SESSION_NOT_READY", "The latency probe needs a running session that has drawn its first frame")
        }
        val region = region(request)
        val inputs = request.optJSONArray("inputs")?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()
        if (inputs.isEmpty()) throw AgentException("INVALID_INPUT", "inputs is required: one or more pad, key or tap inputs")
        val count = request.optInt("count", 10).coerceIn(1, MAX_COUNT)
        val gapMs = request.optLong("gapMs", 400).coerceIn(100, 5_000)
        val holdMs = request.optLong("holdMs", 60).coerceIn(10, 1_000)
        val first = request.optInt("first", 0).coerceAtLeast(0)
        WaylandCompositor.nativeResponseArm(region[0], region[1], region[2], region[3])
        try {
            val state = IntArray(3)
            val rows = JSONArray()
            drain()
            var done = 0
            var attempts = 0
            while (done < count && attempts < count + 4) {
                attempts++
                Thread.sleep(gapMs + Random.nextLong(0, 17))
                val input = inputs[(first + done) % inputs.size]
                val eventNs = SystemClock.uptimeNanos()
                AgentInput.edge(input, true)
                WaylandCompositor.nativeResponseInput(kind(input), eventNs)
                val row = await(1_600)
                Thread.sleep(holdMs)
                AgentInput.edge(input, false)
                if (row == null) throw AgentException("PROBE_SILENT", "The compositor returned no row for an input")
                if (row[12] == STATUS_NOT_CALIBRATED) continue
                rows.put(describe(row, input))
                done++
            }
            WaylandCompositor.nativeResponseState(state)
            JSONObject().put("ok", true).put("command", "latency").put("rows", rows)
                .put("frames", state[0]).put("noise", state[1]).put("readable", state[2] != 0)
                .put("displayTiming", WaylandCompositor.nativeDisplayTiming())
                .put("refreshHz", SessionState.refreshHz.toDouble())
                .put("next", (first + done) % inputs.size)
        } finally {
            WaylandCompositor.nativeResponseArm(0, 0, 0, 0)
        }
    }

    private fun region(request: JSONObject): IntArray {
        val array = request.optJSONArray("region")
        if (array == null || array.length() != 4) {
            throw AgentException("INVALID_INPUT", "region is required: x, y, width, height as fractions of the game picture")
        }
        val v = DoubleArray(4) { array.getDouble(it) }
        if (v.any { it.isNaN() || it < 0.0 || it > 1.0 } || v[2] <= 0.0 || v[3] <= 0.0 || v[0] + v[2] > 1.0001 || v[1] + v[3] > 1.0001) {
            throw AgentException("INVALID_INPUT", "region must lie inside the picture: fractions between 0 and 1")
        }
        return IntArray(4) { (v[it] * 10_000).toInt().coerceIn(if (it >= 2) 1 else 0, 10_000) }
    }

    private fun kind(input: JSONObject): Int = when (input.optString("type")) {
        "tap" -> 0
        "pad" -> 2
        else -> 4
    }

    private fun drain(): LongArray? {
        val buffer = LongArray(WaylandCompositor.RESPONSE_FIELDS * 8)
        var last: LongArray? = null
        while (true) {
            val n = WaylandCompositor.nativeResponseDrain(buffer)
            if (n <= 0) return last
            last = buffer.copyOfRange((n - 1) * WaylandCompositor.RESPONSE_FIELDS, n * WaylandCompositor.RESPONSE_FIELDS)
        }
    }

    private fun await(timeoutMs: Long): LongArray? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            drain()?.let { return it }
            Thread.sleep(4)
        }
        return drain()
    }

    private fun describe(row: LongArray, input: JSONObject): JSONObject {
        val event = row[1]
        fun us(at: Long): Any = if (at > 0) (at - event) / 1000 else JSONObject.NULL
        return JSONObject()
            .put("input", input.optString("type") + ":" + (input.optString("button").ifEmpty { input.optString("key") }))
            .put("status", STATUS.getOrElse(row[12].toInt()) { "unknown" })
            .put("injectUs", us(row[2])).put("firstFrameUs", us(row[3])).put("changeUs", us(row[4]))
            .put("frames", row[5]).put("presentUs", us(row[6])).put("displayUs", us(row[7]))
            .put("refreshUs", row[9] / 1000).put("noise", row[10]).put("delta", row[11])
    }
}
