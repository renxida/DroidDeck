package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
class JoyConTest {

    @Test fun onlyNintendoJoyConsHaveASide() {
        assertEquals(JoyCon.LEFT, JoyCon.side(device(LEFT_PRODUCT)))
        assertEquals(JoyCon.RIGHT, JoyCon.side(device(RIGHT_PRODUCT)))
        assertEquals(-1, JoyCon.side(device(PRO_CONTROLLER)))
        assertEquals(-1, JoyCon.side(device(LEFT_PRODUCT, vendor = 0x045e)))
        assertEquals(-1, JoyCon.side(null))
    }

    @Test fun theRightJoyConFaceButtonsKeepTheirPositions() {
        val right = device(RIGHT_PRODUCT)
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, JoyCon.normalize(key(right, KeyEvent.KEYCODE_BUTTON_X, BTN_NORTH)).keyCode)
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, JoyCon.normalize(key(right, KeyEvent.KEYCODE_BUTTON_Y, BTN_WEST)).keyCode)
        val south = key(right, KeyEvent.KEYCODE_BUTTON_A, BTN_SOUTH)
        assertSame(south, JoyCon.normalize(south))
        val remapped = JoyCon.normalize(key(right, KeyEvent.KEYCODE_BUTTON_X, BTN_NORTH, KeyEvent.ACTION_UP))
        assertEquals(KeyEvent.ACTION_UP, remapped.action)
        assertEquals(right.id, remapped.deviceId)
        assertEquals(BTN_NORTH, remapped.scanCode)
        assertEquals(InputDevice.SOURCE_GAMEPAD, remapped.source)
    }

    @Test fun theLeftJoyConArrowsBecomeADpad() {
        val left = device(LEFT_PRODUCT)
        val arrows = mapOf(0x220 to KeyEvent.KEYCODE_DPAD_UP, 0x221 to KeyEvent.KEYCODE_DPAD_DOWN,
            0x222 to KeyEvent.KEYCODE_DPAD_LEFT, 0x223 to KeyEvent.KEYCODE_DPAD_RIGHT)
        for ((scanCode, code) in arrows) {
            assertEquals(code, JoyCon.normalize(key(left, KeyEvent.KEYCODE_UNKNOWN, scanCode)).keyCode)
        }
    }

    @Test fun railButtonsAndOtherPadsPassThroughUntouched() {
        val left = device(LEFT_PRODUCT)
        val right = device(RIGHT_PRODUCT)
        for (event in listOf(key(left, KeyEvent.KEYCODE_BUTTON_R1, BTN_TR), key(left, KeyEvent.KEYCODE_BUTTON_R2, BTN_TR2),
            key(right, KeyEvent.KEYCODE_BUTTON_L1, BTN_TL), key(right, KeyEvent.KEYCODE_BUTTON_L2, BTN_TL2),
            key(device(PRO_CONTROLLER), KeyEvent.KEYCODE_BUTTON_X, BTN_NORTH))) {
            assertSame(event, JoyCon.normalize(event))
        }
        assertTrue(JoyCon.isRail(JoyCon.LEFT, BTN_TR))
        assertTrue(JoyCon.isRail(JoyCon.RIGHT, BTN_TL2))
        assertFalse(JoyCon.isRail(JoyCon.LEFT, BTN_TL))
        assertFalse(JoyCon.isRail(JoyCon.RIGHT, BTN_TR))
        assertFalse(JoyCon.isRail(-1, BTN_TR))
    }

    @Test fun eachJoyConReadsItsOwnStick() {
        val left = device(LEFT_PRODUCT)
        val right = device(RIGHT_PRODUCT, axes = intArrayOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY))
        val oldRight = device(RIGHT_PRODUCT, axes = intArrayOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ))
        val source = InputDevice.SOURCE_JOYSTICK
        assertArrayEquals(intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y), JoyCon.stickAxes(JoyCon.LEFT, left, source))
        assertArrayEquals(intArrayOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY), JoyCon.stickAxes(JoyCon.RIGHT, right, source))
        assertArrayEquals(intArrayOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ), JoyCon.stickAxes(JoyCon.RIGHT, oldRight, source))
    }

    private var nextId = 40

    private fun device(product: Int, vendor: Int = NINTENDO, axes: IntArray = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)): InputDevice {
        val id = nextId++
        val builder = InputDeviceBuilder.newBuilder().setId(id).setName("Joy-Con fixture $id").setDescriptor("joycon-$id")
            .setExternal(true).setVendorId(vendor).setProductId(product)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_KEYBOARD)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setKeyCharacterMap(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD))
        for (axis in axes) builder.addMotionRange(axis, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0f, 0f, 0f)
        val device = builder.build()
        val manager = RuntimeEnvironment.getApplication().getSystemService(InputManager::class.java)
        shadowOf(manager).addInputDevice(device)
        return device
    }

    private fun key(device: InputDevice, code: Int, scanCode: Int, action: Int = KeyEvent.ACTION_DOWN): KeyEvent {
        val event = KeyEvent(0L, 0L, action, code, 0, 0, device.id, scanCode, 0, InputDevice.SOURCE_GAMEPAD)
        assertSame(device, event.device)
        return event
    }

    private companion object {
        const val NINTENDO = 0x057e
        const val LEFT_PRODUCT = 0x2006
        const val RIGHT_PRODUCT = 0x2007
        const val PRO_CONTROLLER = 0x2009
        const val BTN_SOUTH = 0x130
        const val BTN_EAST = 0x131
        const val BTN_NORTH = 0x133
        const val BTN_WEST = 0x134
        const val BTN_Z = 0x135
        const val BTN_TL = 0x136
        const val BTN_TR = 0x137
        const val BTN_TL2 = 0x138
        const val BTN_TR2 = 0x139
        const val BTN_SELECT = 0x13a
        const val BTN_START = 0x13b
        const val BTN_MODE = 0x13c
        const val BTN_THUMBL = 0x13d
        const val BTN_THUMBR = 0x13e
    }
}
