package com.xiaoian.app.input

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Input for the running desktop, whichever frontend it has: both expose the
 * same small static surface (XiaoianInput in each module), since their
 * activities are AppCompat ones the app cannot compile against.
 *
 * Also owns the sticky modifiers of the special-keys bar, so a character
 * typed on the soft keyboard while CTRL is on goes out as Ctrl+key rather
 * than as text.
 */
class DesktopInput(private val de: String) {

    enum class Mod(val keyCode: Int, val label: String) {
        CTRL(KeyEvent.KEYCODE_CTRL_LEFT, "CTRL"),
        ALT(KeyEvent.KEYCODE_ALT_LEFT, "ALT"),
        SHIFT(KeyEvent.KEYCODE_SHIFT_LEFT, "SHIFT"),
        SUPER(KeyEvent.KEYCODE_META_LEFT, "SUPER"),
    }

    enum class ModState { OFF, ONCE, LOCKED }

    private val mods = Mod.entries.associateWith { ModState.OFF }.toMutableMap()

    /** Called whenever a modifier changes, so the bar can redraw its keys. */
    var onModsChanged: (() -> Unit)? = null

    fun modState(mod: Mod): ModState = mods.getValue(mod)

    /** Tap: off -> once -> off. Long press: locked, or off again. */
    fun toggleMod(mod: Mod, lock: Boolean) {
        mods[mod] = when {
            lock -> if (mods[mod] == ModState.LOCKED) ModState.OFF else ModState.LOCKED
            mods[mod] == ModState.OFF -> ModState.ONCE
            else -> ModState.OFF
        }
        onModsChanged?.invoke()
    }

    private val kde get() = de == "kde"

    fun available(): Boolean =
        if (kde) com.anland.termux.XiaoianInput.available() else com.termux.x11.XiaoianInput.available()

    fun move(dx: Float, dy: Float) =
        if (kde) com.anland.termux.XiaoianInput.move(dx, dy) else com.termux.x11.XiaoianInput.move(dx, dy)

    fun button(button: Int, down: Boolean) =
        if (kde) com.anland.termux.XiaoianInput.button(button, down) else com.termux.x11.XiaoianInput.button(button, down)

    fun scroll(dx: Float, dy: Float) =
        if (kde) com.anland.termux.XiaoianInput.scroll(dx, dy) else com.termux.x11.XiaoianInput.scroll(dx, dy)

    private fun rawKey(keyCode: Int, down: Boolean) =
        if (kde) com.anland.termux.XiaoianInput.key(keyCode, down) else com.termux.x11.XiaoianInput.key(keyCode, down)

    fun click(button: Int) {
        button(button, true)
        button(button, false)
    }

    /** A full press with the active modifiers held around it; ONCE ones are used up. */
    fun key(keyCode: Int, extraShift: Boolean = false) {
        val held = Mod.entries.filter { mods[it] != ModState.OFF }.map { it.keyCode }.toMutableList()
        if (extraShift && KeyEvent.KEYCODE_SHIFT_LEFT !in held) held += KeyEvent.KEYCODE_SHIFT_LEFT
        held.forEach { rawKey(it, true) }
        rawKey(keyCode, true)
        rawKey(keyCode, false)
        held.asReversed().forEach { rawKey(it, false) }
        consumeOnce()
    }

    /**
     * Text from the soft keyboard. With a modifier on, each character goes
     * out as a key press instead -- Ctrl+C has to be a key combination, not
     * the letter c.
     */
    fun text(s: String) {
        if (s.isEmpty()) return
        if (Mod.entries.none { mods[it] != ModState.OFF }) {
            if (kde) com.anland.termux.XiaoianInput.text(s) else com.termux.x11.XiaoianInput.text(s)
            return
        }
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (ch in s) {
            val events = map.getEvents(charArrayOf(ch))
            val down = events?.firstOrNull { it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode) }
            if (down == null) {
                if (kde) com.anland.termux.XiaoianInput.text(ch.toString()) else com.termux.x11.XiaoianInput.text(ch.toString())
                continue
            }
            key(down.keyCode, extraShift = down.isShiftPressed)
        }
    }

    private fun consumeOnce() {
        var changed = false
        for (m in Mod.entries) if (mods[m] == ModState.ONCE) { mods[m] = ModState.OFF; changed = true }
        if (changed) onModsChanged?.invoke()
    }
}
