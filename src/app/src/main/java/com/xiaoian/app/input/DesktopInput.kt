package com.xiaoian.app.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.anland.termux.ExtraKeysBar

/**
 * Input for the running desktop, whichever frontend it has: both expose the
 * same small static surface (XiaoianInput in each module), since their
 * activities are AppCompat ones the app cannot compile against.
 *
 * Also the [ExtraKeysBar.Sender] for the touchpad's special-keys bar -- the
 * same bar, and the same layout setting, as under the terminal. The bar
 * speaks evdev; the X11 side wants Android key codes, see [Evdev].
 */
class DesktopInput(private val de: String) : ExtraKeysBar.Sender {

    /** The bar, once built: its modifiers apply to soft-keyboard input too. */
    var bar: ExtraKeysBar? = null

    var onToggleKeyboard: () -> Unit = {}
    var onOpenSettings: () -> Unit = {}

    private val kde get() = de == "kde"

    fun available(): Boolean =
        if (kde) com.anland.termux.XiaoianInput.available() else com.termux.x11.XiaoianInput.available()

    fun move(dx: Float, dy: Float) =
        if (kde) com.anland.termux.XiaoianInput.move(dx, dy) else com.termux.x11.XiaoianInput.move(dx, dy)

    fun button(button: Int, down: Boolean) =
        if (kde) com.anland.termux.XiaoianInput.button(button, down) else com.termux.x11.XiaoianInput.button(button, down)

    fun scroll(dx: Float, dy: Float) =
        if (kde) com.anland.termux.XiaoianInput.scroll(dx, dy) else com.termux.x11.XiaoianInput.scroll(dx, dy)

    fun click(button: Int) {
        button(button, true)
        button(button, false)
    }

    private fun rawText(s: String) =
        if (kde) com.anland.termux.XiaoianInput.text(s) else com.termux.x11.XiaoianInput.text(s)

    private fun rawEvdev(evdev: Int, down: Boolean) {
        if (kde) {
            com.anland.termux.XiaoianInput.keyEvdev(evdev, down)
        } else {
            val keyCode = Evdev.toKeyCode(evdev)
            if (keyCode >= 0) com.termux.x11.XiaoianInput.key(keyCode, down)
        }
    }

    // ---- ExtraKeysBar.Sender ---------------------------------------------

    override fun key(action: Int, evdev: Int) = rawEvdev(evdev, action == 0)
    override fun text(s: String?) { if (!s.isNullOrEmpty()) rawText(s) }
    override fun toggleKeyboard() = onToggleKeyboard()
    /** No floating virtual keyboard here; the system IME is the only one. */
    override fun toggleVirtualKeyboard() = onToggleKeyboard()
    override fun openSettings() = onOpenSettings()

    // ---- soft keyboard ---------------------------------------------------

    /** A full press of an Android key, wrapped in whatever the bar has held. */
    fun typeKey(keyCode: Int) {
        val evdev = Evdev.fromKeyCode(keyCode)
        if (evdev < 0) return
        val b = bar
        if (b != null && b.hasActiveModifier()) {
            b.sendKeyComboFromExternal(evdev)
        } else {
            rawEvdev(evdev, true)
            rawEvdev(evdev, false)
        }
    }

    /**
     * Text from the soft keyboard. With a bar modifier on, each character
     * goes out as a key press instead -- Ctrl+C has to be a key combination,
     * not the letter c.
     */
    fun typeText(s: String) {
        if (s.isEmpty()) return
        val b = bar
        if (b == null || !b.hasActiveModifier()) {
            rawText(s)
            return
        }
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (ch in s) {
            val down = map.getEvents(charArrayOf(ch))
                ?.firstOrNull { it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode) }
            val evdev = down?.let { Evdev.fromKeyCode(it.keyCode) } ?: -1
            if (evdev < 0) rawText(ch.toString()) else b.sendKeyComboFromExternal(evdev)
        }
    }
}
