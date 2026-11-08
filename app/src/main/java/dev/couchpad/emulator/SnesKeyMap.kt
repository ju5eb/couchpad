package dev.couchpad.emulator

import android.view.KeyEvent
import dev.couchpad.domain.ControllerButton

/** Maps controller buttons to the Android gamepad key codes LibretroDroid understands. */
object SnesKeyMap {
    fun keyCode(button: ControllerButton): Int = when (button) {
        ControllerButton.UP -> KeyEvent.KEYCODE_DPAD_UP
        ControllerButton.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        ControllerButton.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        ControllerButton.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        ControllerButton.A -> KeyEvent.KEYCODE_BUTTON_A
        ControllerButton.B -> KeyEvent.KEYCODE_BUTTON_B
        ControllerButton.X -> KeyEvent.KEYCODE_BUTTON_X
        ControllerButton.Y -> KeyEvent.KEYCODE_BUTTON_Y
        ControllerButton.SHOULDER_L -> KeyEvent.KEYCODE_BUTTON_L1
        ControllerButton.SHOULDER_R -> KeyEvent.KEYCODE_BUTTON_R1
        ControllerButton.START -> KeyEvent.KEYCODE_BUTTON_START
        ControllerButton.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
    }
}
