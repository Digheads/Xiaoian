package com.xiaoian.app.input

import android.util.SparseIntArray
import android.view.KeyEvent
import com.anland.termux.KeyCodeMapper

/**
 * Android key codes and evdev scan codes, both ways. The extra-keys bar
 * speaks evdev (it was written for the KDE desktop); the terminal and the
 * X11 desktop want Android key codes.
 */
object Evdev {

    /** Android key code -> evdev, or -1. KeyCodeMapper plus the two it lacks. */
    fun fromKeyCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_UP -> 104
        KeyEvent.KEYCODE_PAGE_DOWN -> 109
        else -> KeyCodeMapper.getScanCode(keyCode)
    }

    /** evdev -> Android key code, or -1. */
    fun toKeyCode(evdev: Int): Int = TO_KEYCODE.get(evdev, -1)

    /**
     * The inverse of [KeyCodeMapper.getScanCode].
     *
     * Built by walking the Android key codes rather than written out by hand,
     * so it cannot drift from the forward table. Lowest key code wins where
     * several map to the same scan code, except where that one is wrong for a
     * desktop: SEARCH and ASSIST both sit on META's 125, and SUPER has to
     * arrive as META.
     */
    private val TO_KEYCODE = SparseIntArray().apply {
        for (keyCode in 0..KeyEvent.getMaxKeyCode()) {
            val scan = KeyCodeMapper.getScanCode(keyCode)
            if (scan >= 0 && indexOfKey(scan) < 0) put(scan, keyCode)
        }
        // The forward table has no entry for these two, but the bar's
        // default layout has PGUP and PGDN keys on them.
        put(104, KeyEvent.KEYCODE_PAGE_UP)
        put(109, KeyEvent.KEYCODE_PAGE_DOWN)
        put(125, KeyEvent.KEYCODE_META_LEFT)
    }
}
