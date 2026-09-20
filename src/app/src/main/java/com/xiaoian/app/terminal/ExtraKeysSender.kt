package com.xiaoian.app.terminal

import android.util.SparseIntArray
import android.view.KeyEvent
import com.anland.termux.ExtraKeysBar
import com.anland.termux.KeyCodeMapper
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView

/**
 * Drives a [TerminalView] from the desktop's [ExtraKeysBar].
 *
 * The bar exists to talk to a Wayland/X11 compositor, so it speaks evdev scan
 * codes and sends every key as a down/up pair with any active modifiers
 * wrapped around it. A terminal wants the opposite: one event, with the
 * modifiers as a bit mask. Translating here rather than teaching the bar about
 * terminals keeps the desktop path untouched -- the bar is shared, and its
 * layout comes from the same preference either way.
 */
class ExtraKeysSender(
    private val terminalView: TerminalView,
    private val onToggleKeyboard: () -> Unit,
    private val onOpenSettings: () -> Unit,
) : ExtraKeysBar.Sender {

    /** Modifiers the bar currently has down, as a [KeyHandler] mask. */
    private var keyMod = 0

    override fun key(action: Int, evdev: Int) {
        val modBit = MODIFIERS[evdev]
        if (modBit != 0) {
            // The bar brackets each key with its modifiers, so tracking the
            // pairs here is enough -- no need to ask the bar what is held.
            if (action == ACTION_DOWN) keyMod = keyMod or modBit else keyMod = keyMod and modBit.inv()
            return
        }
        // One event per key press, on the down; the up would repeat it.
        if (action != ACTION_DOWN) return

        val keyCode = EVDEV_TO_KEYCODE.get(evdev, -1)
        if (keyCode < 0) return
        terminalView.handleKeyCode(keyCode, keyMod)
    }

    override fun text(s: String?) {
        if (s.isNullOrEmpty()) return
        val ctrl = keyMod and KeyHandler.KEYMOD_CTRL != 0
        val alt = keyMod and KeyHandler.KEYMOD_ALT != 0
        var i = 0
        while (i < s.length) {
            val codePoint = s.codePointAt(i)
            // Through inputCodePoint, not write(): it is what turns Ctrl + '/'
            // into ^_ instead of sending a literal slash.
            terminalView.inputCodePoint(
                TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, ctrl, alt,
            )
            i += Character.charCount(codePoint)
        }
    }

    override fun toggleKeyboard() = onToggleKeyboard()

    /** No floating virtual keyboard here; the system IME is the only one. */
    override fun toggleVirtualKeyboard() = onToggleKeyboard()

    override fun openSettings() = onOpenSettings()

    companion object {
        private const val ACTION_DOWN = 0

        /** evdev scan code -> [KeyHandler] modifier bit. */
        private val MODIFIERS = SparseIntArray().apply {
            put(29, KeyHandler.KEYMOD_CTRL)   // KEY_LEFTCTRL
            put(97, KeyHandler.KEYMOD_CTRL)   // KEY_RIGHTCTRL
            put(56, KeyHandler.KEYMOD_ALT)    // KEY_LEFTALT
            put(100, KeyHandler.KEYMOD_ALT)   // KEY_RIGHTALT
            put(42, KeyHandler.KEYMOD_SHIFT)  // KEY_LEFTSHIFT
            put(54, KeyHandler.KEYMOD_SHIFT)  // KEY_RIGHTSHIFT
        }

        /**
         * The inverse of [KeyCodeMapper.getScanCode].
         *
         * Built by walking the Android key codes rather than written out by
         * hand, so it cannot drift from the forward table. Lowest key code
         * wins where several map to the same scan code -- the forward table has
         * a few of those (SEARCH and ASSIST both sit on META's 125), and none
         * of them matter to a terminal.
         */
        private val EVDEV_TO_KEYCODE = SparseIntArray().apply {
            for (keyCode in 0..KeyEvent.getMaxKeyCode()) {
                val scan = KeyCodeMapper.getScanCode(keyCode)
                if (scan >= 0 && indexOfKey(scan) < 0) put(scan, keyCode)
            }
            // The forward table has no entry for these two, but the bar's
            // default layout has PGUP and PGDN keys on them.
            put(104, KeyEvent.KEYCODE_PAGE_UP)
            put(109, KeyEvent.KEYCODE_PAGE_DOWN)
        }
    }
}
