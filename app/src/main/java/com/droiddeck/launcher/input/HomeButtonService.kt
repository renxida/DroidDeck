package com.droiddeck.launcher.input

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

object SystemKeyCapture {
    private var owner: Any? = null
    private var active: () -> Boolean = { false }
    private var send: (KeyEvent) -> Unit = {}
    private val held = HashMap<Pair<Int, Int>, KeyEvent>()
    private val releasing = HashSet<Pair<Int, Int>>()
    internal var isKeyboard: (KeyEvent) -> Boolean = ::fromKeyboard

    fun attach(owner: Any, active: () -> Boolean, send: (KeyEvent) -> Unit) {
        this.owner = owner
        this.active = active
        this.send = send
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        held.values.forEach { send(KeyEvent.changeAction(it, KeyEvent.ACTION_UP)) }
        releasing += held.keys
        held.clear()
        this.owner = null
        active = { false }
        send = {}
    }

    internal fun captures(keyCode: Int, metaState: Int): Boolean = when {
        keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT -> true
        metaState and KeyEvent.META_META_ON != 0 -> true
        metaState and KeyEvent.META_ALT_ON != 0 -> keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_ESCAPE
        else -> false
    }

    private fun fromKeyboard(event: KeyEvent): Boolean =
        event.device?.let { !it.isVirtual && it.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true

    fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            releasing -= key
        } else if (key in releasing) {
            if (event.action == KeyEvent.ACTION_UP) releasing -= key
            return true
        }
        val taken = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val take = key in held || (event.repeatCount == 0 && active() && isKeyboard(event) && captures(event.keyCode, event.metaState))
                if (take) held[key] = KeyEvent(event)
                take
            }
            KeyEvent.ACTION_UP -> held.remove(key) != null
            else -> false
        }
        if (taken) send(event)
        return taken
    }
}

class HomeButtonService : AccessibilityService() {
    private var bottomScreen: BottomScreenWatch? = null

    override fun onServiceConnected() {
        if (bottomScreen == null && BottomScreenGuests.supported()) bottomScreen = BottomScreenWatch(this).also { it.start() }
    }

    private fun stopBottomScreen() {
        bottomScreen?.stop()
        bottomScreen = null
    }

    override fun onKeyEvent(event: KeyEvent) = SystemKeyCapture.onKeyEvent(event)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { event?.let { bottomScreen?.onEvent(it) } }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopBottomScreen()
        com.droiddeck.launcher.core.SteamIntegration.syncShortcutsListing(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopBottomScreen()
        super.onDestroy()
    }
}
