package com.droiddeck.launcher.agent

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object AgentUi {
    private const val TIMEOUT_MS = 5_000L
    private const val GATE_TAG = "setting-agent-commands"

    fun handle(request: JSONObject): JSONObject = when (val op = request.optString("op", "dump")) {
        "dump" -> onMain { dump(request.optBoolean("all")) }
        "click" -> onMain { act(request) { node -> perform(node, SemanticsActions.OnClick) } }
        "set-text" -> onMain {
            val text = request.optString("text")
            act(request) { node ->
                node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text))
                    ?: throw AgentException("NOT_EDITABLE", "That node takes no text")
            }
        }
        "tap" -> touch(request, swipe = false)
        "swipe" -> touch(request, swipe = true)
        "back" -> onMain { back() }
        else -> throw AgentException("INVALID_REQUEST", "ui op '$op' is not dump, click, set-text, tap, swipe or back")
    }.put("ok", true).put("command", "ui")

    /** The app's own windows, bottom to top, that are showing. */
    private fun windows(): List<View> {
        if (Build.VERSION.SDK_INT < 29) throw AgentException("UNSUPPORTED", "UI input needs Android 10 or later")
        return WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 }
    }

    private fun origin(view: View) = IntArray(2).also { view.getLocationOnScreen(it) }

    /** The topmost window under a point on the screen. */
    private fun windowAt(x: Float, y: Float): View? = windows().lastOrNull { w ->
        val o = origin(w)
        x >= o[0] && y >= o[1] && x < o[0] + w.width && y < o[1] + w.height
    }

    /** Refuses a touch on the Debugging tools switch, which is turned on only by a hand on the device. */
    private fun guard(x: Float, y: Float) {
        for ((view, root) in roots()) {
            val o = origin(view)
            fun visit(node: SemanticsNode) {
                if (node.config.getOrNull(SemanticsProperties.TestTag) == GATE_TAG) {
                    val b = node.boundsInWindow
                    if (x >= o[0] + b.left && x <= o[0] + b.right && y >= o[1] + b.top && y <= o[1] + b.bottom)
                        throw AgentException("PROTECTED_CONTROL", "Debugging tools are switched on the device only")
                }
                node.children.forEach(::visit)
            }
            visit(root.semanticsOwner.rootSemanticsNode)
        }
    }

    /**
     * A finger on the app's screen at screen pixels (x, y), or at fractions of the screen (fx, fy): "tap" holds for
     * "ms" (default 60), "swipe" moves to (x2, y2) / (fx2, fy2) over "ms" (default 300). The touch goes to the
     * topmost window there the way a finger's would, on-screen controls and the game's picture included.
     */
    private fun touch(request: JSONObject, swipe: Boolean): JSONObject {
        val (w, h) = onMain { screen() }
        fun coord(px: String, frac: String, size: Int): Float = when {
            request.has(px) -> request.getDouble(px).toFloat()
            request.has(frac) -> request.getDouble(frac).toFloat() * size
            else -> throw AgentException("INVALID_REQUEST", "Give $px or $frac")
        }
        val x1 = coord("x", "fx", w); val y1 = coord("y", "fy", h)
        val x2 = if (swipe) coord("x2", "fx2", w) else x1
        val y2 = if (swipe) coord("y2", "fy2", h) else y1
        val ms = request.optLong("ms", if (swipe) 300L else 60L).coerceIn(1L, 10_000L)
        val steps = if (swipe) (ms / 16L).coerceIn(2L, 120L).toInt() else 0
        val target = onMain {
            guard(x1, y1); if (swipe) guard(x2, y2)
            windowAt(x1, y1) ?: throw AgentException("NO_WINDOW", "No window of the app at ($x1, $y1)")
        }
        val o = onMain { origin(target) }
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float, at: Long) = onMain {
            val event = android.view.MotionEvent.obtain(down, at, action, x - o[0], y - o[1], 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { target.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, x1, y1, down)
        for (i in 1..steps) {
            Thread.sleep(ms / steps)
            val t = i.toFloat() / steps
            send(android.view.MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, android.os.SystemClock.uptimeMillis())
        }
        if (!swipe) Thread.sleep(ms)
        send(android.view.MotionEvent.ACTION_UP, x2, y2, android.os.SystemClock.uptimeMillis())
        return JSONObject().put("from", JSONArray().put(x1).put(y1)).put("to", JSONArray().put(x2).put(y2)).put("ms", ms)
            .put("window", target.javaClass.simpleName).put("screen", JSONArray().put(w).put(h))
    }

    private fun screen(): Pair<Int, Int> {
        val all = windows()
        val o = all.map { origin(it) }
        return Pair(all.indices.maxOfOrNull { o[it][0] + all[it].width } ?: 0, all.indices.maxOfOrNull { o[it][1] + all[it].height } ?: 0)
    }

    private fun back(): JSONObject {
        val top = windows().lastOrNull { it.hasWindowFocus() } ?: windows().lastOrNull()
            ?: throw AgentException("NO_WINDOW", "No window of the app is showing")
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
            top.dispatchKeyEvent(android.view.KeyEvent(now, now, action, android.view.KeyEvent.KEYCODE_BACK, 0))
        return JSONObject().put("window", top.javaClass.simpleName)
    }

    /**
     * A picture of the app's windows as drawn, bottom to top, scaled to at most [maxWidth] wide, as JPEG. The game's own
     * picture is on a separate surface and shows black here; "screenshot" without "what": "ui" takes that one.
     */
    fun picture(maxWidth: Int = 1280): ByteArray = onMain {
        val all = windows()
        if (all.isEmpty()) throw AgentException("NO_WINDOW", "No window of the app is showing")
        val (w, h) = screen()
        val scale = minOf(1f, maxWidth.toFloat() / w)
        val bitmap = android.graphics.Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.scale(scale, scale)
        for (view in all) {
            val o = origin(view)
            canvas.save(); canvas.translate(o[0].toFloat(), o[1].toFloat())
            runCatching { view.draw(canvas) }
            canvas.restore()
        }
        java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it); bitmap.recycle() }.toByteArray()
    }

    private fun roots(): List<Pair<View, ViewRootForTest>> {
        if (Build.VERSION.SDK_INT < 29) throw AgentException("UNSUPPORTED", "The UI dump needs Android 10 or later")
        val found = mutableListOf<Pair<View, ViewRootForTest>>()
        fun walk(view: View) {
            if (view is ViewRootForTest) found += view to view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }.forEach(::walk)
        return found
    }

    private fun dump(all: Boolean): JSONObject {
        val windows = JSONArray()
        for ((view, root) in roots()) {
            val nodes = JSONArray()
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            fun visit(node: SemanticsNode) {
                val json = describe(node, location)
                if (all || json.has("tag") || json.has("text") || json.has("description") || json.optBoolean("clickable")) nodes.put(json)
                node.children.forEach(::visit)
            }
            visit(root.semanticsOwner.rootSemanticsNode)
            windows.put(JSONObject()
                .put("display", view.display?.displayId ?: JSONObject.NULL)
                .put("window", view.rootView.javaClass.simpleName)
                .put("nodes", nodes))
        }
        return JSONObject().put("windows", windows)
    }

    private fun describe(node: SemanticsNode, origin: IntArray): JSONObject {
        val config = node.config
        val json = JSONObject().put("id", node.id)
        config.getOrNull(SemanticsProperties.TestTag)?.let { json.put("tag", it) }
        val text = textFields(config)
        text.keys().forEach { json.put(it, text.get(it)) }
        config.getOrNull(SemanticsProperties.Role)?.let { json.put("role", it.toString()) }
        config.getOrNull(SemanticsProperties.ToggleableState)?.let { json.put("toggle", it.name) }
        config.getOrNull(SemanticsProperties.Selected)?.let { json.put("selected", it) }
        config.getOrNull(SemanticsProperties.Focused)?.let { json.put("focused", it) }
        if (config.contains(SemanticsProperties.Disabled)) json.put("disabled", true)
        if (config.contains(SemanticsActions.OnClick)) json.put("clickable", true)
        if (config.contains(SemanticsActions.SetText)) json.put("editable", true)
        val b = node.boundsInWindow
        json.put("bounds", JSONArray()
            .put(origin[0] + b.left.toInt()).put(origin[1] + b.top.toInt())
            .put(origin[0] + b.right.toInt()).put(origin[1] + b.bottom.toInt()))
        return json
    }

    internal fun textFields(config: SemanticsConfiguration): JSONObject {
        val password = config.contains(SemanticsProperties.Password)
        val json = JSONObject()
        for ((key, value) in listOf(
            "text" to config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            "editableText" to config.getOrNull(SemanticsProperties.EditableText)?.text,
            "description" to config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            "state" to config.getOrNull(SemanticsProperties.StateDescription),
        )) value?.let { json.put(key, if (password) "<redacted>" else it) }
        return json
    }

    private fun act(request: JSONObject, action: (SemanticsNode) -> Unit): JSONObject {
        val tag = request.optString("tag").takeIf { it.isNotEmpty() }
        val text = if (tag == null) request.optString("match").takeIf { it.isNotEmpty() } else null
        if (tag == null && text == null) throw AgentException("INVALID_REQUEST", "Name the node by tag or match")
        val matches = mutableListOf<Pair<SemanticsNode, IntArray>>()
        for ((view, root) in roots()) {
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            fun visit(node: SemanticsNode) {
                val config = node.config
                val hit = if (tag != null) config.getOrNull(SemanticsProperties.TestTag) == tag
                else (config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                    ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "")
                    .contains(text!!, ignoreCase = true)
                if (hit) matches += node to origin
                node.children.forEach(::visit)
            }
            visit(root.semanticsOwner.rootSemanticsNode)
        }
        val index = request.optInt("index", 0)
        val (node, origin) = matches.getOrNull(index)
            ?: throw AgentException("NO_SUCH_NODE", "No node with ${if (tag != null) "tag '$tag'" else "text '$text'"} (${matches.size} found)")
        if (generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == GATE_TAG }) {
            throw AgentException("PROTECTED_CONTROL", "Debugging tools are switched on the device only")
        }
        action(node)
        return JSONObject().put("matches", matches.size).put("node", describe(node, origin))
    }

    private fun perform(node: SemanticsNode, key: androidx.compose.ui.semantics.SemanticsPropertyKey<androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>>) {
        val action = node.config.getOrNull(key)?.action ?: throw AgentException("NOT_CLICKABLE", "That node has no click action")
        action()
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching(block))
            done.countDown()
        }
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw AgentException("TIMEOUT", "The UI thread did not answer")
        return result.get().getOrThrow()
    }
}
