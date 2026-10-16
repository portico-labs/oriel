package dev.fmagana.oriel.session

import android.view.KeyEvent
import dev.fmagana.oriel.protocol.Modifiers

/** Android key codes to USB HID usages (keyboard page 0x07), which the Mac posts as its own keys. */
object KeyMap {
    private val table: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, 0x04 + i)
        for (i in 1..9) put(KeyEvent.KEYCODE_1 + i - 1, 0x1E + i - 1)
        put(KeyEvent.KEYCODE_0, 0x27)
        put(KeyEvent.KEYCODE_ENTER, 0x28)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x58)
        put(KeyEvent.KEYCODE_ESCAPE, 0x29)
        put(KeyEvent.KEYCODE_DEL, 0x2A)
        put(KeyEvent.KEYCODE_TAB, 0x2B)
        put(KeyEvent.KEYCODE_SPACE, 0x2C)
        put(KeyEvent.KEYCODE_MINUS, 0x2D)
        put(KeyEvent.KEYCODE_EQUALS, 0x2E)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30)
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31)
        put(KeyEvent.KEYCODE_SEMICOLON, 0x33)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34)
        put(KeyEvent.KEYCODE_GRAVE, 0x35)
        put(KeyEvent.KEYCODE_COMMA, 0x36)
        put(KeyEvent.KEYCODE_PERIOD, 0x37)
        put(KeyEvent.KEYCODE_SLASH, 0x38)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, 0x3A + i)
        put(KeyEvent.KEYCODE_INSERT, 0x49)
        put(KeyEvent.KEYCODE_MOVE_HOME, 0x4A)
        put(KeyEvent.KEYCODE_PAGE_UP, 0x4B)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C)
        put(KeyEvent.KEYCODE_MOVE_END, 0x4D)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4F)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 0x50)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 0x51)
        put(KeyEvent.KEYCODE_DPAD_UP, 0x52)
    }

    /** The HID usage of `keyCode`; null for a key the Mac has no counterpart for (or a modifier's own key). */
    fun hidUsage(keyCode: Int): Int? = table[keyCode]

    /** Android's meta state as UIKeyModifierFlags: Ctrl is Control, Alt is Option, Meta is Command. */
    fun modifiers(metaState: Int): Long {
        var m = 0L
        if (metaState and KeyEvent.META_SHIFT_ON != 0) m = m or Modifiers.SHIFT
        if (metaState and KeyEvent.META_CTRL_ON != 0) m = m or Modifiers.CONTROL
        if (metaState and KeyEvent.META_ALT_ON != 0) m = m or Modifiers.OPTION
        if (metaState and KeyEvent.META_META_ON != 0) m = m or Modifiers.COMMAND
        return m
    }
}
