package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

object JoyCon {
    const val LEFT = 0
    const val RIGHT = 1

    private const val VENDOR = 0x057e
    private const val PRODUCT_LEFT = 0x2006
    private const val PRODUCT_RIGHT = 0x2007

    private const val BTN_SOUTH = 0x130
    private const val BTN_EAST = 0x131
    private const val BTN_NORTH = 0x133
    private const val BTN_WEST = 0x134
    private const val BTN_Z = 0x135
    private const val BTN_TL = 0x136
    private const val BTN_TR = 0x137
    private const val BTN_TL2 = 0x138
    private const val BTN_TR2 = 0x139
    private const val BTN_SELECT = 0x13a
    private const val BTN_START = 0x13b
    private const val BTN_MODE = 0x13c
    private const val BTN_THUMBL = 0x13d
    private const val BTN_THUMBR = 0x13e
    private const val BTN_DPAD_UP = 0x220
    private const val BTN_DPAD_DOWN = 0x221
    private const val BTN_DPAD_LEFT = 0x222
    private const val BTN_DPAD_RIGHT = 0x223

    @JvmStatic
    fun side(device: InputDevice?): Int = when {
        device == null || device.isVirtual || device.vendorId != VENDOR -> -1
        device.productId == PRODUCT_LEFT -> LEFT
        device.productId == PRODUCT_RIGHT -> RIGHT
        else -> -1
    }

    @JvmStatic
    fun stickAxes(side: Int, device: InputDevice, source: Int): IntArray = when {
        side == LEFT -> intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        device.getMotionRange(MotionEvent.AXIS_RX, source) != null -> intArrayOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        else -> intArrayOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
    }

    @JvmStatic
    fun isRail(side: Int, scanCode: Int): Boolean = when (side) {
        LEFT -> scanCode == BTN_TR || scanCode == BTN_TR2
        RIGHT -> scanCode == BTN_TL || scanCode == BTN_TL2
        else -> false
    }

    internal fun padKey(side: Int, scanCode: Int): Int = when (side) {
        LEFT -> when (scanCode) {
            BTN_TL -> KeyEvent.KEYCODE_BUTTON_L1
            BTN_TL2 -> KeyEvent.KEYCODE_BUTTON_L2
            BTN_SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
            BTN_THUMBL -> KeyEvent.KEYCODE_BUTTON_THUMBL
            BTN_Z -> KeyEvent.KEYCODE_BUTTON_Z
            BTN_DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
            BTN_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            BTN_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            BTN_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
        RIGHT -> when (scanCode) {
            BTN_SOUTH -> KeyEvent.KEYCODE_BUTTON_A
            BTN_EAST -> KeyEvent.KEYCODE_BUTTON_B
            BTN_NORTH -> KeyEvent.KEYCODE_BUTTON_Y
            BTN_WEST -> KeyEvent.KEYCODE_BUTTON_X
            BTN_TR -> KeyEvent.KEYCODE_BUTTON_R1
            BTN_TR2 -> KeyEvent.KEYCODE_BUTTON_R2
            BTN_START -> KeyEvent.KEYCODE_BUTTON_START
            BTN_THUMBR -> KeyEvent.KEYCODE_BUTTON_THUMBR
            BTN_MODE -> KeyEvent.KEYCODE_BUTTON_MODE
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    @JvmStatic
    fun normalize(event: KeyEvent): KeyEvent {
        val side = side(event.device)
        if (side < 0) return event
        val code = padKey(side, event.scanCode)
        if (code == KeyEvent.KEYCODE_UNKNOWN || code == event.keyCode) return event
        return KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount, event.metaState,
            event.deviceId, event.scanCode, event.flags, event.source)
    }
}
